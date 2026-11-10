package com.tidymail.service;

import com.tidymail.dto.ActivityPageModel;
import com.tidymail.dto.ActivityStatusModel;
import com.tidymail.dto.ActivityStatusModel.Action;
import com.tidymail.dto.ActivityStatusModel.Event;
import com.tidymail.dto.ActivityStatusModel.Lane;
import com.tidymail.dto.ActivityStatusModel.ListingProgress;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAccount.OAuthStatus;
import com.tidymail.repository.UserAccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
/**
 * Builds the Activity page model, summarizing the current mailbox indexing/sync
 * status, progress lanes, recent events, and freshness for the selected
 * account.
 *
 * <p>
 * Product functionality supported: the <em>Activity</em> flow, which shows
 * indexing readiness, freshness, and bounded recent sync activity.
 */
public class ActivityQueryService {

    private static final int EVENT_LIMIT = 8;

    private final OwnedAccountScopeService accountScope;
    private final WorkspaceModelService workspaceModels;
    private final UserAccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ActivityQueryService(
            OwnedAccountScopeService accountScope,
            WorkspaceModelService workspaceModels,
            UserAccountRepository accounts,
            JdbcTemplate jdbc) {
        this(accountScope, workspaceModels, accounts, jdbc, Clock.systemUTC());
    }

    ActivityQueryService(
            OwnedAccountScopeService accountScope,
            WorkspaceModelService workspaceModels,
            UserAccountRepository accounts,
            JdbcTemplate jdbc,
            Clock clock) {
        this.accountScope = accountScope;
        this.workspaceModels = workspaceModels;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Builds the activity page model for the selected account. */
    public ActivityPageModel pageModel(
            String accountSelection, OAuth2AuthenticationToken authentication) {
        List<Long> accountIds = accountScope.resolve(authentication, accountSelection);
        WorkspacePageModel workspace = workspaceModels.buildResolved(
                WorkspaceRoute.ACTIVITY, accountSelection, accountIds, authentication);
        UserAccount account = accounts.findById(accountIds.getFirst())
                .orElseThrow(() -> new IllegalArgumentException("Resolved account no longer exists"));
        long indexed = jdbc.queryForObject("""
                SELECT count(*) FROM message_metadata
                WHERE account_id = ? AND (provider <> 'google' OR NOT(labels && ARRAY['SPAM','TRASH']))
                """, Long.class, account.getId());
        Optional<StateRow> state = state(account.getId());
        List<ProgressRow> progress = progress(account.getId());
        return new ActivityPageModel(
                workspace,
                providerName(account.getProvider()),
                status(account, indexed, state.orElse(null), progress, clock.instant()));
    }

    /** Summarizes the account's sync state and progress into a status model. */
    private ActivityStatusModel status(
            UserAccount account,
            long indexed,
            StateRow state,
            List<ProgressRow> progress,
            Instant now) {
        if (!"google".equalsIgnoreCase(account.getProvider())) {
            return model(
                    "unsupported", "Sync activity is unavailable for this provider",
                    "Provider status", "Indexed messages remain available for cleanup.",
                    indexed, null, "Unavailable", "Not checked yet", null,
                    "Not needed", List.of(), events(progress, now), null, null,
                    false);
        }

        if (account.getOauthStatus() == OAuthStatus.REAUTH_REQUIRED
                || state != null && "REAUTH_REQUIRED".equals(state.lifecycle())) {
            Instant freshnessAt = state == null ? null : state.incrementalLastSuccessAt();
            return model(
                    "reauthorization", "Reconnect Gmail to continue syncing",
                    "Gmail authorization",
                    "Gmail authorization needs attention. Reconnect Gmail to continue syncing; "
                            + "your indexed data will remain available.",
                    indexed, null,
                    initialBatch(state), freshness(freshnessAt, now), freshnessAt,
                    rescan(state, now), lanes(state, progress, now),
                    events(progress, now),
                    new Action("Reconnect Gmail", "/accounts/connect/google", false,
                            "Reconnect Gmail for " + account.getEmail()),
                    null, true);
        }

        if (state != null && "ERROR".equals(state.lifecycle())) {
            return model(
                    "error", "Sync paused after a recoverable error",
                    "Mailbox sync paused",
                    "Sync paused after a recoverable error. Your indexed data is still available. "
                            + "Retry now or wait for the next automatic attempt.",
                    indexed, null,
                    initialBatch(state), freshness(state.incrementalLastSuccessAt(), now),
                    state.incrementalLastSuccessAt(), rescan(state, now),
                    lanes(state, progress, now), events(progress, now),
                    new Action("Retry sync", "/api/metadata-sync", true,
                            "Retry Gmail sync for " + account.getEmail()),
                    hasAutomaticRetry(progress) ? 30 : null, true);
        }

        ListingProgress listing = listingProgress(state, indexed);
        if (state == null || state.initialBatchCompletedAt() == null) {
            return model(
                    "preparing", "Preparing your cleanup workspace",
                    "Indexing Inbox",
                    "Your most recent Inbox messages are being indexed first. "
                            + "Sync continues in the background; you can close this page.",
                    indexed, listing, "Preparing",
                    freshness(state == null ? null : state.incrementalLastSuccessAt(), now),
                    state == null ? null : state.incrementalLastSuccessAt(),
                    rescan(state, now),
                    lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        if ("RESCAN_REQUIRED".equals(state.lifecycle())
                || "REQUIRED".equals(state.rescanStatus())) {
            return model(
                    "warning", "A mailbox rescan is required", "Mailbox rescan queued",
                    "Gmail will recheck All Mail and verify messages missing from the listing.",
                    indexed, null, "Ready",
                    freshness(state.incrementalLastSuccessAt(), now),
                    state.incrementalLastSuccessAt(), "Required",
                    lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        if ("RESCANNING".equals(state.lifecycle())
                || "RUNNING".equals(state.rescanStatus())) {
            return model(
                    "warning", "Rechecking mailbox history",
                    "__VERIFY_MISSING__".equals(state.rescanAnchor()) ? "Checking missing messages" : "Rescanning All Mail",
                    "Sync continues in the background; you can close this page.",
                    indexed, null, "Ready",
                    freshness(state.incrementalLastSuccessAt(), now),
                    state.incrementalLastSuccessAt(), "In progress",
                    lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        boolean backfillActive = !"COMPLETE".equals(state.backfillStatus());
        boolean freshnessActive = laneActive(progress, "FRESHNESS");
        if (backfillActive) {
            return model(
                    "active", "Cleanup is ready while indexing continues",
                    indexingPhase(state),
                    "Your indexed messages are ready to review. Sync continues in the background.",
                    indexed, listing, "Ready",
                    freshness(state.incrementalLastSuccessAt(), now),
                    state.incrementalLastSuccessAt(), rescan(state, now),
                    lanes(state, progress, now), events(progress, now),
                    new Action("Open cleanup",
                            "/senders?account=" + account.getId(), false, "Open cleanup"),
                    5, false);
        }

        if (freshnessActive) {
            return model(
                    "active", "Checking for new mail", "New mail",
                    "Sync continues in the background; you can close this page.",
                    indexed, listing, "Ready",
                    freshness(state.incrementalLastSuccessAt(), now),
                    state.incrementalLastSuccessAt(), rescan(state, now),
                    lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        if (indexed == 0 && listing != null && Long.valueOf(0).equals(listing.total())
                && state.incrementalLastSuccessAt() != null) {
            return model(
                    "empty", "No messages were found", "Mailbox checked",
                    "Gmail reported no messages for this account. "
                            + "Sync will continue checking for new mail.",
                    0, listing, "Ready",
                    freshness(state.incrementalLastSuccessAt(), now),
                    state.incrementalLastSuccessAt(), rescan(state, now),
                    lanes(state, progress, now), events(progress, now), null, null, false);
        }

        String headline = state.incrementalLastSuccessAt() == null
                ? "Mailbox history is indexed"
                : "Mailbox activity is current";
        return model(
                "current", headline, "Mailbox checked",
                "Indexed cleanup data is available for this account.",
                indexed, listing, "Ready",
                freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), rescan(state, now),
                lanes(state, progress, now), events(progress, now), null, null, false);
    }

    /** Assembles an activity status model from its parts. */
    private ActivityStatusModel model(
            String stateClass,
            String headline,
            String phase,
            String guidance,
            long indexed,
            ListingProgress listing,
            String initialBatch,
            String freshness,
            Instant freshnessAt,
            String rescan,
            List<Lane> lanes,
            List<Event> events,
            Action action,
            Integer pollingSeconds,
            boolean alert) {
        return new ActivityStatusModel(
                stateClass, headline, phase, guidance, indexed, listing, initialBatch, freshness, freshnessAt,
                rescan, lanes, events, action, pollingSeconds, alert, headline);
    }

    /** Loads the account's current sync state row. */
    private Optional<StateRow> state(long accountId) {
        List<StateRow> rows = jdbc.query("""
                SELECT lifecycle, incremental_last_success_at,
                       backfill_status, backfill_started_at, backfill_completed_at,
                       initial_batch_completed_at, gmail_backfill_phase, gmail_scope_total,
                       rescan_status, rescan_started_at, rescan_completed_at, rescan_anchor,
                       freshness_lease_expires_at, backfill_lease_expires_at
                  FROM mailbox_sync_state
                 WHERE account_id = ?
                """, (result, rowNumber) -> stateRow(result), accountId);
        return rows.stream().findFirst();
    }

    /** Loads the account's recent sync progress rows. */
    private List<ProgressRow> progress(long accountId) {
        return jdbc.query("""
                SELECT lane, phase, status, started_at, finished_at, last_successful_at,
                       messages_fetched, messages_changed, messages_deleted,
                       indexed_messages, retry_code
                  FROM sync_progress
                 WHERE account_id = ? AND job_type = 'MAILBOX_SYNC'
                 ORDER BY id DESC
                 LIMIT 24
                """, (result, rowNumber) -> progressRow(result), accountId);
    }

    /** Builds the ordered progress lanes from the state and progress rows. */
    private static List<Lane> lanes(
            StateRow state, List<ProgressRow> progress, Instant now) {
        Map<String, ProgressRow> latest = new LinkedHashMap<>();
        progress.forEach(row -> latest.putIfAbsent(row.lane(), row));
        List<Lane> result = new ArrayList<>();
        addLane(result, "FRESHNESS", "New mail", latest.get("FRESHNESS"), now);
        if (state == null || state.initialBatchCompletedAt() == null
                || !"NOT_STARTED".equals(state.backfillStatus())
                || latest.containsKey("BACKFILL")) {
            addLane(result, "BACKFILL",
                    state != null && "COMPLETE".equals(state.backfillStatus()) ? "Initial sync" : indexingPhase(state),
                    latest.get("BACKFILL"), now);
        }
        if (state != null && !"NOT_STARTED".equals(state.rescanStatus())) {
            addLane(result, "RESCAN", "Mailbox rescan", latest.get("RESCAN"), now);
        }
        return List.copyOf(result);
    }

    /** Adds a single progress lane with its work copy and status. */
    private static void addLane(
            List<Lane> lanes, String key, String name, ProgressRow row, Instant now) {
        if (row == null) {
            lanes.add(new Lane(name, "Waiting", null, "Not started", "No activity yet"));
            return;
        }
        Instant at = row.finishedAt() != null ? row.finishedAt() : row.startedAt();
        lanes.add(new Lane(
                name,
                statusCopy(row.status()),
                at,
                at == null ? "Not recorded" : relative(at, now),
                workCopy(row)));
    }

    /** Builds the recent event list from the progress rows. */
    private static List<Event> events(List<ProgressRow> progress, Instant now) {
        return progress.stream()
                .filter(row -> !"LEGACY".equals(row.lane()))
                .filter(row -> row.finishedAt() != null || row.startedAt() != null)
                .limit(EVENT_LIMIT)
                .map(row -> {
                    Instant at = row.finishedAt() != null ? row.finishedAt() : row.startedAt();
                    return new Event(
                            at, relative(at, now),
                            laneName(row.lane()) + " " + statusCopy(row.status()).toLowerCase(Locale.ROOT),
                            workCopy(row));
                })
                .toList();
    }

    /** Produces a human-readable description of a progress row's work. */
    private static String workCopy(ProgressRow row) {
        if ("QUEUED".equals(row.status())) {
            return "Waiting to start";
        }
        long fetched = value(row.messagesFetched());
        long changed = value(row.messagesChanged());
        long deleted = value(row.messagesDeleted());
        long indexed = value(row.indexedMessages());
        if (fetched == 0 && changed == 0 && deleted == 0 && indexed == 0) {
            return "No message changes recorded";
        }
        return fetched + " fetched; " + changed + " changed; "
                + deleted + " removed; " + indexed + " indexed";
    }

    /** Returns true if the given lane is currently active. */
    private static boolean laneActive(List<ProgressRow> progress, String lane) {
        return progress.stream()
                .filter(row -> lane.equals(row.lane()))
                .findFirst()
                .map(row -> "QUEUED".equals(row.status()) || "RUNNING".equals(row.status()))
                .orElse(false);
    }

    /** Returns true if any progress row is eligible for automatic retry. */
    private static boolean hasAutomaticRetry(List<ProgressRow> progress) {
        return progress.stream().findFirst()
                .map(row -> "QUEUED".equals(row.status()) && row.retryCode() != null)
                .orElse(false);
    }

    private static String indexingPhase(StateRow state) {
        return state != null && "ALL_MAIL".equals(state.gmailBackfillPhase())
            ? "Indexing All Mail" : "Indexing Inbox";
    }

    private static ListingProgress listingProgress(StateRow state, long indexed) {
        if (state == null) return new ListingProgress(indexed, null, false);
        Long total = state.gmailScopeTotal();
        if ("COMPLETE".equals(state.backfillStatus())) {
            // Freeze the completed listing's count. Ongoing History updates the live indexed count.
            return total == null ? null : new ListingProgress(total, total, true);
        }
        // A stale low estimate cannot represent remaining work or a completed bar.
        return new ListingProgress(indexed, total != null && total > indexed ? total : null, false);
    }

    /** Describes the initial-batch completion state. */
    private static String initialBatch(StateRow state) {
        return state != null && state.initialBatchCompletedAt() != null
                ? "Ready"
                : "Preparing";
    }

    /** Describes how fresh the last successful sync was. */
    private static String freshness(Instant instant, Instant now) {
        return instant == null ? "Not checked yet" : "Checked " + relative(instant, now);
    }

    /** Describes the rescan state and its timing. */
    private static String rescan(StateRow state, Instant now) {
        if (state == null)
            return "Not needed";
        return switch (state.rescanStatus()) {
            case "REQUIRED" -> "Required";
            case "RUNNING" -> "In progress";
            case "COMPLETE" -> state.rescanCompletedAt() == null
                    ? "Completed"
                    : "Completed " + relative(state.rescanCompletedAt(), now);
            default -> "Not needed";
        };
    }

    /** Formats a relative time description between two instants. */
    private static String relative(Instant instant, Instant now) {
        if (instant == null)
            return "Not recorded";
        Duration age = Duration.between(instant, now);
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(1)) < 0)
            return "just now";
        if (age.compareTo(Duration.ofHours(1)) < 0) {
            long minutes = age.toMinutes();
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        if (age.compareTo(Duration.ofDays(1)) < 0) {
            long hours = age.toHours();
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = age.toDays();
        return days + (days == 1 ? " day ago" : " days ago");
    }

    /** Formats a provider identifier as a display name. */
    private static String providerName(String provider) {
        if ("google".equalsIgnoreCase(provider))
            return "Gmail";
        if ("microsoft".equalsIgnoreCase(provider))
            return "Microsoft Outlook";
        return "Connected provider";
    }

    /** Formats a lane identifier as a display name. */
    private static String laneName(String lane) {
        return switch (lane) {
            case "FRESHNESS" -> "New mail";
            case "BACKFILL" -> "Historical mail";
            case "RESCAN" -> "Mailbox rescan";
            default -> "Mailbox sync";
        };
    }

    /** Maps a raw status to a human-readable description. */
    private static String statusCopy(String status) {
        return switch (status) {
            case "QUEUED" -> "Queued";
            case "RUNNING" -> "In progress";
            case "COMPLETE", "SUCCESS" -> "Completed";
            case "FAILED" -> "Needs attention";
            default -> "Status unavailable";
        };
    }

    /** Returns the long value, defaulting to zero when null. */
    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    /** Maps a result-set row to a state row. */
    private static StateRow stateRow(ResultSet result) throws SQLException {
        return new StateRow(
                result.getString("lifecycle"),
                instant(result, "incremental_last_success_at"),
                result.getString("backfill_status"),
                instant(result, "backfill_started_at"),
                instant(result, "backfill_completed_at"),
                instant(result, "initial_batch_completed_at"),
                result.getString("gmail_backfill_phase"),
                result.getObject("gmail_scope_total", Long.class),
                result.getString("rescan_status"),
                instant(result, "rescan_started_at"),
                instant(result, "rescan_completed_at"),
                result.getString("rescan_anchor"),
                instant(result, "freshness_lease_expires_at"),
                instant(result, "backfill_lease_expires_at"));
    }

    /** Maps a result-set row to a progress row. */
    private static ProgressRow progressRow(ResultSet result) throws SQLException {
        return new ProgressRow(
                result.getString("lane"),
                result.getString("phase"),
                result.getString("status"),
                instant(result, "started_at"),
                instant(result, "finished_at"),
                instant(result, "last_successful_at"),
                result.getObject("messages_fetched", Long.class),
                result.getObject("messages_changed", Long.class),
                result.getObject("messages_deleted", Long.class),
                result.getObject("indexed_messages", Long.class),
                result.getString("retry_code"));
    }

    /** Reads a nullable timestamp column as an instant. */
    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record StateRow(
            String lifecycle,
            Instant incrementalLastSuccessAt,
            String backfillStatus,
            Instant backfillStartedAt,
            Instant backfillCompletedAt,
            Instant initialBatchCompletedAt,
            String gmailBackfillPhase,
            Long gmailScopeTotal,
            String rescanStatus,
            Instant rescanStartedAt,
            Instant rescanCompletedAt,
            String rescanAnchor,
            Instant freshnessLeaseExpiresAt,
            Instant backfillLeaseExpiresAt) {
    }

    private record ProgressRow(
            String lane,
            String phase,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Instant lastSuccessfulAt,
            Long messagesFetched,
            Long messagesChanged,
            Long messagesDeleted,
            Long indexedMessages,
            String retryCode) {
    }
}
