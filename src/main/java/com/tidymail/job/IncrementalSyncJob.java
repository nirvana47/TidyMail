package com.tidymail.job;

import com.tidymail.config.MailboxSyncExecutorConfig;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.repository.MailboxSyncStateRepository.LaneQueue;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import com.tidymail.service.MessageMetadataSyncService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that keeps user mailboxes in sync with their mail provider.
 *
 * <p>Product functionality supported: incremental mailbox cleanup sync —
 * periodically pulls new/changed messages from connected providers and drives
 * the backfill, freshness, and rescan lanes, coordinating work via leased
 * per-account lanes so multiple instances do not duplicate effort.
 */
@Component
public class IncrementalSyncJob {
    public static final String JOB_TYPE = "MAILBOX_SYNC";

    private static final Logger log = LoggerFactory.getLogger(IncrementalSyncJob.class);

    private final Map<String, MailboxSyncProvider> providerMap;
    private final UserAccountRepository accounts;
    private final SyncProgressRepository progress;
    private final MailboxSyncStateRepository states;
    private final MessageMetadataSyncService synchronization;
    private final TaskExecutor freshnessExecutor;
    private final TaskExecutor backfillExecutor;
    private final MailboxSyncExecutorConfig config;
    private final String instanceOwner = UUID.randomUUID().toString();
    private final Map<LeaseKey, String> activeLeases = new ConcurrentHashMap<>();

    private record LeaseKey(long accountId, Lane lane) {}

    public IncrementalSyncJob(
            Map<String, MailboxSyncProvider> providerMap,
            UserAccountRepository accounts,
            SyncProgressRepository progress,
            MailboxSyncStateRepository states,
            MessageMetadataSyncService synchronization,
            @Qualifier("freshnessTaskExecutor") TaskExecutor freshnessExecutor,
            @Qualifier("backfillRescanTaskExecutor") TaskExecutor backfillExecutor,
            MailboxSyncExecutorConfig config) {
        this.providerMap = providerMap;
        this.accounts = accounts;
        this.progress = progress;
        this.states = states;
        this.synchronization = synchronization;
        this.freshnessExecutor = freshnessExecutor;
        this.backfillExecutor = backfillExecutor;
        this.config = config;
    }

    /**
     * Scheduled entry point that requests a sync for every active, token-bearing
     * Google account on a fixed freshness cycle.
     */
    @Scheduled(
        fixedRateString = "${app.sync.freshness-cycle-ms:300000}",
        initialDelayString = "${app.sync.startup-delay-ms:5000}")
    public void run() {
        accounts.findAll().stream()
            .filter(account -> account.getEncryptedAccessToken() != null)
            .filter(account -> account.getOauthStatus() == UserAccount.OAuthStatus.ACTIVE)
            .filter(account -> "google".equals(account.getProvider()))
            .forEach(account -> requestSync(account.getId()));
    }

    /**
     * Requests a sync for an account, choosing the backfill lane when no
     * incremental cursor or initial batch exists, otherwise the freshness lane.
     */
    public SyncProgress requestSync(Long accountId) {
        UserAccount account = accounts.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account does not exist"));
        states.ensureState(accountId, account.getProvider());
        StateSnapshot state = states.findByAccountId(accountId)
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        Lane lane = state.incrementalCursorValue() == null
                || state.initialBatchCompletedAt() == null
            ? Lane.BACKFILL
            : Lane.FRESHNESS;
        SyncProgress requested = requestLane(accountId, lane);
        if (lane == Lane.FRESHNESS && !"COMPLETE".equals(state.backfillStatus())) {
            // A failed page must resume even after bootstrap has established freshness.
            requestLane(accountId, Lane.BACKFILL);
        }
        return requested;
    }

    /** Keep paced/retrying provider work owned without holding a DB transaction. */
    @Scheduled(fixedDelay = 2000)
    public void renewActiveLeases() {
        activeLeases.forEach((key, owner) -> {
            try {
                for (int attempt = 0; attempt < 4; attempt++) {
                    StateSnapshot state = states.findByAccountId(key.accountId()).orElse(null);
                    if (state == null || !laneOwnedBy(state, key.lane(), owner)
                            || !laneOwnedAndLive(state, key.lane(), Instant.now())) {
                        return;
                    }
                    Instant expiry = key.lane() == Lane.FRESHNESS
                        ? state.freshnessLeaseExpiresAt() : state.backfillLeaseExpiresAt();
                    if (expiry.isAfter(Instant.now().plus(config.laneLeaseDuration().dividedBy(2)))) {
                        return;
                    }
                    if (states.heartbeatLaneLease(
                            key.accountId(), key.lane(), owner,
                            Instant.now().plus(config.laneLeaseDuration()), state.version())) {
                        return;
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("Mailbox lease renewal failed for account {} lane {}",
                    key.accountId(), key.lane());
            }
        });
    }

    /** Requests a full backfill sync for an account. */
    public SyncProgress requestBackfill(Long accountId) {
        return requestLane(accountId, Lane.BACKFILL);
    }

    /** Requests an initial backfill sync to bootstrap an account's data. */
    public SyncProgress requestBootstrap(Long accountId) {
        return requestLane(accountId, Lane.BACKFILL);
    }

    /** Requests a rescan lane sync for an account. */
    public SyncProgress requestRescan(Long accountId) {
        return requestLane(accountId, Lane.RESCAN);
    }

    private SyncProgress requestLane(Long accountId, Lane lane) {
        UserAccount account = accounts.findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account does not exist"));
        states.ensureState(accountId, account.getProvider());
        LaneQueue queued = states.queueLane(accountId, lane);
        // A persisted QUEUED row may outlive its executor after an application restart.
        // The lane lease fences duplicate submissions; re-dispatch it on every sync request.
        submitLane(accountId, queued.progressId(), lane);
        return progress.findById(queued.progressId())
            .orElseThrow(() -> new IllegalStateException("Queued sync work disappeared"));
    }

    /**
     * Runs a single bounded sync unit: acquires the lane lease, performs the
     * provider sync, marks progress, and releases the lease, then continues
     * queued work or schedules follow-up lanes as needed.
     */
    public void dispatchBoundedUnit(Long accountId, Long progressId, Lane lane) {
        String leaseOwner = instanceOwner + ":" + UUID.randomUUID();
        StateSnapshot leased = takeOverStaleLease(accountId, lane, leaseOwner);
        if (leased == null) {
            return;
        }
        LeaseKey leaseKey = new LeaseKey(accountId, lane);
        activeLeases.put(leaseKey, leaseOwner);
        try {
            SyncProgress row = progress.findById(progressId).orElse(null);
            if (row == null || !"QUEUED".equals(row.getStatus())) {
                return;
            }
            row.setStatus("RUNNING");
            row.setPhase("PROVIDER");
            row.setStartedAt(Instant.now());
            row.setFinishedAt(null);
            row.setErrorMessage(null);
            progress.save(row);

            UserAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Account no longer exists"));
            if (account.getOauthStatus() != UserAccount.OAuthStatus.ACTIVE) {
                throw new IllegalStateException("Account requires reauthentication");
            }
            MailboxSyncProvider provider = resolveProvider(account);
            SyncOutcome outcome =
                synchronization.synchronize(account, provider, leaseOwner, lane);
            row = progress.findById(progressId).orElseThrow();
            row.setStatus("COMPLETE");
            row.setPhase("COMMIT");
            row.setFinishedAt(Instant.now());
            row.setLastSuccessfulAt(row.getFinishedAt());
            row.setErrorMessage(null);
            progress.save(row);
            releaseLane(accountId, lane, leaseOwner);
            if (outcome.moreWork()) {
                requestLane(accountId, lane);
            }
            if (outcome.queueFreshness()) {
                requestLane(accountId, Lane.FRESHNESS);
            }
            if (outcome.queueRescan()) {
                requestLane(accountId, Lane.RESCAN);
            }
        } catch (RuntimeException exception) {
            recordWorkerFailure(progressId);
            releaseLane(accountId, lane, leaseOwner);
            log.warn(
                "Mailbox sync unit failed for account {} lane {} supportCode=SYNC_UNIT_FAILED",
                accountId, lane);
        } finally {
            activeLeases.remove(leaseKey, leaseOwner);
            releaseLane(accountId, lane, leaseOwner);
            drainQueuedLane(accountId, lane);
        }
    }

    /**
     * Acquires the lease for a lane, retrying on contention and only grabbing
     * lanes whose previous lease is stale or unowned; returns null if the lane
     * is already live or could not be taken over.
     */
    public StateSnapshot takeOverStaleLease(
            Long accountId, Lane lane, String leaseOwner) {
        for (int attempt = 0; attempt < 4; attempt++) {
            Instant now = Instant.now();
            StateSnapshot current = states.findByAccountId(accountId).orElse(null);
            if (current == null) {
                return null;
            }
            if (states.acquireLaneLease(
                    accountId, lane, leaseOwner,
                    now.plus(config.laneLeaseDuration()), now, current.version())) {
                return states.findByAccountId(accountId).orElseThrow();
            }
            StateSnapshot observed = states.findByAccountId(accountId).orElse(null);
            if (observed != null && laneOwnedAndLive(observed, lane, now)) {
                return null;
            }
        }
        return null;
    }

    /** Re-queues a lane for an account so its pending work is rescheduled. */
    public void requeueLane(Long accountId, Lane lane) {
        requestLane(accountId, lane);
    }

    /**
     * Marks a still-queued sync submission as failed when the worker executor
     * rejected scheduling it.
     */
    public void recordRejectedSubmission(Long progressId) {
        progress.findById(progressId).ifPresent(row -> {
            if ("QUEUED".equals(row.getStatus())) {
                row.setStatus("FAILED");
                row.setPhase("REJECTED");
                row.setFinishedAt(Instant.now());
                row.setRetryCode("EXECUTOR_REJECTED");
                row.setErrorMessage("Sync worker could not be scheduled.");
                progress.save(row);
            }
        });
    }

    private void submitLane(Long accountId, Long progressId, Lane lane) {
        TaskExecutor executor = lane == Lane.FRESHNESS
            ? freshnessExecutor : backfillExecutor;
        try {
            executor.execute(() -> dispatchBoundedUnit(accountId, progressId, lane));
        } catch (RuntimeException exception) {
            recordRejectedSubmission(progressId);
            log.warn(
                "Mailbox sync submission rejected for account {} lane {}",
                accountId, lane);
        }
    }

    private void drainQueuedLane(Long accountId, Lane lane) {
        progress.findTopByAccountIdAndJobTypeAndLaneAndStatusOrderByIdDesc(
                accountId, JOB_TYPE, lane.name(), "QUEUED")
            .ifPresent(row -> submitLane(accountId, row.getId(), lane));
    }

    private void releaseLane(Long accountId, Lane lane, String leaseOwner) {
        for (int attempt = 0; attempt < 4; attempt++) {
            StateSnapshot current = states.findByAccountId(accountId).orElse(null);
            if (current == null || !laneOwnedBy(current, lane, leaseOwner)) {
                return;
            }
            if (states.releaseLaneLease(
                    accountId, lane, leaseOwner, current.version())) {
                return;
            }
        }
    }

    private void recordWorkerFailure(Long progressId) {
        progress.findById(progressId).ifPresent(row -> {
            if ("QUEUED".equals(row.getStatus()) || "RUNNING".equals(row.getStatus())) {
                row.setStatus("FAILED");
                row.setPhase("FAILED");
                row.setFinishedAt(Instant.now());
                row.setRetryCode("SYNC_UNIT_FAILED");
                row.setErrorMessage("Mailbox sync unit failed; retry is available.");
                progress.save(row);
            }
        });
    }

    private MailboxSyncProvider resolveProvider(UserAccount account) {
        String beanName = switch (account.getProvider()) {
            case "google" -> "gmailMailService";
            default -> throw new IllegalStateException("Provider sync is unavailable");
        };
        MailboxSyncProvider provider = providerMap.get(beanName);
        if (provider == null) {
            throw new IllegalStateException("Provider sync is unavailable");
        }
        return provider;
    }

    private static boolean laneOwnedAndLive(
            StateSnapshot state, Lane lane, Instant now) {
        Instant expiry = lane == Lane.FRESHNESS
            ? state.freshnessLeaseExpiresAt() : state.backfillLeaseExpiresAt();
        return expiry != null && expiry.isAfter(now);
    }

    private static boolean laneOwnedBy(
            StateSnapshot state, Lane lane, String owner) {
        String actual = lane == Lane.FRESHNESS
            ? state.freshnessLeaseOwner() : state.backfillLeaseOwner();
        return owner.equals(actual);
    }
}
