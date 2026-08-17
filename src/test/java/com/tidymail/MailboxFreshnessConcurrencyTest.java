package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tidymail.config.MailboxSyncExecutorConfig;
import com.tidymail.entity.SyncProgress;
import com.tidymail.job.IncrementalSyncJob;
import com.tidymail.mail.sync.ChangePage;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.MailboxFreshnessService;
import com.tidymail.service.MessageMetadataSyncService;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MailboxFreshnessConcurrencyTest extends PostgresJourneyTest {

    @Autowired
    private MailboxSyncStateRepository states;

    @Autowired
    private MailboxFreshnessService freshness;

    @Autowired
    private MessageMetadataSyncService synchronization;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private SyncProgressRepository progress;

    @Test
    void twoApplicationInstancesCannotOwnOneLaneButSeparateLanesCanOverlap() {
        long accountId = userAccounts.createAccount("lane-owner@example.test");
        states.ensureState(accountId, "google");
        Instant now = Instant.now();

        assertThat(states.acquireLaneLease(
            accountId, Lane.FRESHNESS, "instance-a",
            now.plusSeconds(60), now, 0)).isTrue();
        assertThat(states.acquireLaneLease(
            accountId, Lane.FRESHNESS, "instance-b",
            now.plusSeconds(60), now, 0)).isFalse();

        long version = states.findByAccountId(accountId).orElseThrow().version();
        assertThat(states.acquireLaneLease(
            accountId, Lane.BACKFILL, "instance-b",
            now.plusSeconds(60), now, version)).isTrue();
        var state = states.findByAccountId(accountId).orElseThrow();
        assertThat(state.freshnessLeaseOwner()).isEqualTo("instance-a");
        assertThat(state.backfillLeaseOwner()).isEqualTo("instance-b");
    }

    @Test
    void freshnessStartsAndFinishesWhileBackfillExecutorIsSaturated() throws Exception {
        MailboxSyncExecutorConfig config = executorConfig();
        ThreadPoolTaskExecutor freshnessExecutor = config.freshnessTaskExecutor();
        ThreadPoolTaskExecutor backfillExecutor = config.backfillRescanTaskExecutor();
        freshnessExecutor.initialize();
        backfillExecutor.initialize();
        CountDownLatch backfillEntered = new CountDownLatch(1);
        CountDownLatch releaseBackfill = new CountDownLatch(1);
        CountDownLatch freshnessFinished = new CountDownLatch(1);
        try {
            backfillExecutor.execute(() -> {
                backfillEntered.countDown();
                await(releaseBackfill);
            });
            assertThat(backfillEntered.await(2, TimeUnit.SECONDS)).isTrue();

            freshnessExecutor.execute(freshnessFinished::countDown);

            assertThat(freshnessFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseBackfill.getCount()).isOne();
        } finally {
            releaseBackfill.countDown();
            freshnessExecutor.shutdown();
            backfillExecutor.shutdown();
        }
    }

    @Test
    void freshnessProviderRunsOutsideTransactionsAndCommitsNoChangeAtomically() {
        long accountId = userAccounts.createAccount("outside-tx@example.test");
        states.ensureState(accountId, "google");
        jdbc.update("""
            UPDATE mailbox_sync_state
               SET incremental_cursor_kind = 'GMAIL_HISTORY_ID',
                   incremental_cursor_value = '10'
             WHERE account_id = ?
            """, accountId);
        Instant now = Instant.now();
        assertThat(states.acquireLaneLease(
            accountId, Lane.FRESHNESS, "worker-a",
            now.plusSeconds(60), now, 0)).isTrue();

        MailboxSyncProvider provider = mock(MailboxSyncProvider.class);
        when(provider.capabilities()).thenReturn(new SyncCapabilities(
            SyncCapabilities.ChangeFeedKind.HISTORY, true, true));
        AtomicBoolean providerSawTransaction = new AtomicBoolean(true);
        when(provider.listChanges(any(), any())).thenAnswer(invocation -> {
            providerSawTransaction.set(
                TransactionSynchronizationManager.isActualTransactionActive());
            return new ChangePage(Set.of(), Set.of(), null, "11", null, false);
        });

        freshness.synchronizeFreshness(
            accounts.findById(accountId).orElseThrow(), provider, "worker-a");

        assertThat(providerSawTransaction).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT incremental_cursor_value
              FROM mailbox_sync_state
             WHERE account_id = ?
            """, String.class, accountId)).isEqualTo("11");
    }

    @Test
    void staleLeaseRevisionCannotTakeOwnershipAfterNewerRevisionWins() {
        long accountId = userAccounts.createAccount("stale-cas@example.test");
        states.ensureState(accountId, "google");
        Instant now = Instant.now();

        assertThat(states.acquireLaneLease(
            accountId, Lane.FRESHNESS, "winner",
            now.plusSeconds(60), now, 0)).isTrue();
        assertThat(states.acquireLaneLease(
            accountId, Lane.BACKFILL, "stale",
            now.plusSeconds(60), now, 0)).isFalse();
        assertThat(states.findByAccountId(accountId).orElseThrow().version()).isEqualTo(1);

        jdbc.update("""
            UPDATE mailbox_sync_state
               SET freshness_lease_expires_at = ?
             WHERE account_id = ?
            """, java.sql.Timestamp.from(now.minusSeconds(1)), accountId);
        assertThat(states.acquireLaneLease(
            accountId, Lane.FRESHNESS, "takeover",
            now.plusSeconds(60), now, 1)).isTrue();
        assertThat(states.findByAccountId(accountId).orElseThrow().freshnessLeaseOwner())
            .isEqualTo("takeover");
    }

    @Test
    void returningAccountWithoutHistoryCursorQueuesBootstrapBeforeFreshness() {
        long accountId = userAccounts.createAccount("migration-bootstrap@example.test");
        MailboxSyncExecutorConfig config = mock(MailboxSyncExecutorConfig.class);
        AtomicBoolean freshnessSubmitted = new AtomicBoolean();
        AtomicBoolean backfillSubmitted = new AtomicBoolean();
        IncrementalSyncJob job = new IncrementalSyncJob(
            Map.of(), accounts, progress, states, synchronization,
            task -> freshnessSubmitted.set(true),
            task -> backfillSubmitted.set(true), config);

        SyncProgress queued = job.requestSync(accountId);

        assertThat(queued.getLane()).isEqualTo("BACKFILL");
        assertThat(queued.getStatus()).isEqualTo("QUEUED");
        assertThat(freshnessSubmitted).isFalse();
        assertThat(backfillSubmitted).isTrue();
    }

    @Test
    void rejectedFreshnessSubmissionRecordsTerminalFailureAndCanBeRetried() {
        long accountId = userAccounts.createAccount("executor-retry@example.test");
        states.ensureState(accountId, "google");
        jdbc.update("""
            UPDATE mailbox_sync_state
               SET incremental_cursor_kind = 'GMAIL_HISTORY_ID',
                   incremental_cursor_value = '10',
                   initial_batch_completed_at = CURRENT_TIMESTAMP
             WHERE account_id = ?
            """, accountId);
        MailboxSyncExecutorConfig config = mock(MailboxSyncExecutorConfig.class);
        when(config.laneLeaseDuration()).thenReturn(Duration.ofSeconds(60));
        TaskExecutor rejecting = task -> {
            throw new RejectedExecutionException("saturated");
        };
        IncrementalSyncJob job = new IncrementalSyncJob(
            Map.of(), accounts, progress, states, synchronization,
            rejecting, Runnable::run, config);

        SyncProgress failed = job.requestSync(accountId);

        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getRetryCode()).isEqualTo("EXECUTOR_REJECTED");
        assertThat(states.queueLane(accountId, Lane.FRESHNESS).created()).isTrue();
    }

    private static MailboxSyncExecutorConfig executorConfig() {
        MailboxSyncExecutorConfig config = new MailboxSyncExecutorConfig();
        ReflectionTestUtils.setField(config, "freshnessCycleMs", 1000L);
        ReflectionTestUtils.setField(config, "freshnessThreads", 1);
        ReflectionTestUtils.setField(config, "freshnessQueueCapacity", 1);
        ReflectionTestUtils.setField(config, "backfillThreads", 1);
        ReflectionTestUtils.setField(config, "backfillQueueCapacity", 1);
        ReflectionTestUtils.setField(config, "laneLeaseSeconds", 60L);
        ReflectionTestUtils.invokeMethod(config, "validate");
        return config;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
