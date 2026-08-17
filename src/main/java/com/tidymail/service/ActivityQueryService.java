package com.tidymail.service;

import com.tidymail.dto.ActivityPageModel;
import com.tidymail.dto.ActivityStatusModel;
import com.tidymail.dto.ActivityStatusModel.Action;
import com.tidymail.dto.ActivityStatusModel.Event;
import com.tidymail.dto.ActivityStatusModel.Lane;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAccount.OAuthStatus;
import com.tidymail.repository.MessageMetadataRepository;
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
public class ActivityQueryService {

    private static final int EVENT_LIMIT = 8;

    private final OwnedAccountScopeService accountScope;
    private final WorkspaceModelService workspaceModels;
    private final UserAccountRepository accounts;
    private final MessageMetadataRepository messages;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ActivityQueryService(
            OwnedAccountScopeService accountScope,
            WorkspaceModelService workspaceModels,
            UserAccountRepository accounts,
            MessageMetadataRepository messages,
            JdbcTemplate jdbc) {
        this(accountScope, workspaceModels, accounts, messages, jdbc, Clock.systemUTC());
    }

    ActivityQueryService(
            OwnedAccountScopeService accountScope,
            WorkspaceModelService workspaceModels,
            UserAccountRepository accounts,
            MessageMetadataRepository messages,
            JdbcTemplate jdbc,
            Clock clock) {
        this.accountScope = accountScope;
        this.workspaceModels = workspaceModels;
        this.accounts = accounts;
        this.messages = messages;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ActivityPageModel pageModel(
            String accountSelection, OAuth2AuthenticationToken authentication) {
        List<Long> accountIds = accountScope.resolve(authentication, accountSelection);
        WorkspacePageModel workspace = workspaceModels.buildResolved(
            WorkspaceRoute.ACTIVITY, accountSelection, accountIds, authentication);
        UserAccount account = accounts.findById(accountIds.getFirst())
            .orElseThrow(() -> new IllegalArgumentException("Resolved account no longer exists"));
        long indexed = messages.countByAccountIdIn(accountIds);
        Optional<StateRow> state = state(account.getId());
        List<ProgressRow> progress = progress(account.getId());
        return new ActivityPageModel(
            workspace,
            providerName(account.getProvider()),
            status(account, indexed, state.orElse(null), progress, clock.instant()));
    }

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
                indexed, null, null, null, "Unavailable", "Not checked yet", null,
                "Not needed", false, List.of(), events(progress, now), null, null,
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
                indexed, null, null, null,
                initialBatch(state), freshness(freshnessAt, now), freshnessAt,
                rescan(state, now), false, lanes(state, progress, now),
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
                indexed, null, null, null,
                initialBatch(state), freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), rescan(state, now), false,
                lanes(state, progress, now), events(progress, now),
                new Action("Retry sync", "/api/metadata-sync", true,
                    "Retry Gmail sync for " + account.getEmail()),
                hasAutomaticRetry(progress) ? 30 : null, true);
        }

        if (state == null || state.initialBatchCompletedAt() == null) {
            return model(
                "preparing", "Preparing your cleanup workspace",
                "Initial setup",
                "Recent messages are being indexed first. "
                    + "Sync continues in the background; you can close this page.",
                indexed, reliableTotal(state), observedAt(state),
                remaining(state, indexed, false), "Preparing",
                freshness(state == null ? null : state.incrementalLastSuccessAt(), now),
                state == null ? null : state.incrementalLastSuccessAt(),
                rescan(state, now), progressAllowed(state),
                lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        if ("RESCAN_REQUIRED".equals(state.lifecycle())
                || "REQUIRED".equals(state.rescanStatus())) {
            return model(
                "warning", "A mailbox rescan is required", "Mailbox rescan queued",
                "Recent changes are reconciled before full mailbox history is checked. "
                    + "Sync continues in the background; you can close this page.",
                indexed, null, null, null, "Ready",
                freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), "Required", false,
                lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        if ("RESCANNING".equals(state.lifecycle())
                || "RUNNING".equals(state.rescanStatus())) {
            return model(
                "warning", "Rechecking mailbox history", "Mailbox rescan",
                "Sync continues in the background; you can close this page.",
                indexed, null, null, null, "Ready",
                freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), "In progress", false,
                lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        boolean backfillActive = !"COMPLETE".equals(state.backfillStatus());
        boolean freshnessActive = laneActive(progress, "FRESHNESS");
        Long total = reliableTotal(state);
        Long remaining = remaining(state, indexed, backfillActive);
        if (backfillActive) {
            return model(
                "active", "Cleanup is ready while history continues", "Historical mail",
                "Your recent messages are ready to review. Older mail will continue indexing "
                    + "in the background. Sync continues in the background; you can close this page.",
                indexed, total, state.providerTotalObservedAt(), remaining, "Ready",
                freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), rescan(state, now),
                total != null && total > 0,
                lanes(state, progress, now), events(progress, now),
                new Action("Open cleanup",
                    "/senders?account=" + account.getId(), false, "Open cleanup"),
                5, false);
        }

        if (freshnessActive) {
            return model(
                "active", "Checking for new mail", "New mail",
                "Sync continues in the background; you can close this page.",
                indexed, total, state.providerTotalObservedAt(), null, "Ready",
                freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), rescan(state, now), false,
                lanes(state, progress, now), events(progress, now), null, 5, false);
        }

        if (indexed == 0 && Long.valueOf(0).equals(total)
                && state.incrementalLastSuccessAt() != null) {
            return model(
                "empty", "No messages were found", "Mailbox checked",
                "Gmail reported no messages for this account. "
                    + "Sync will continue checking for new mail.",
                0, 0L, state.providerTotalObservedAt(), 0L, "Ready",
                freshness(state.incrementalLastSuccessAt(), now),
                state.incrementalLastSuccessAt(), rescan(state, now), false,
                lanes(state, progress, now), events(progress, now), null, null, false);
        }

        String headline = state.incrementalLastSuccessAt() == null
            ? "Mailbox history is indexed"
            : "Mailbox activity is current";
        return model(
            "current", headline, "Mailbox checked",
            "Indexed cleanup data is available for this account.",
            indexed, total, state.providerTotalObservedAt(),
            total == null ? null : Math.max(total - indexed, 0), "Ready",
            freshness(state.incrementalLastSuccessAt(), now),
            state.incrementalLastSuccessAt(), rescan(state, now), false,
            lanes(state, progress, now), events(progress, now), null, null, false);
    }

    private ActivityStatusModel model(
            String stateClass,
            String headline,
            String phase,
            String guidance,
            long indexed,
            Long providerTotal,
            Instant providerTotalObservedAt,
            Long remaining,
            String initialBatch,
            String freshness,
            Instant freshnessAt,
            String rescan,
            boolean showProgress,
            List<Lane> lanes,
            List<Event> events,
            Action action,
            Integer pollingSeconds,
            boolean alert) {
        return new ActivityStatusModel(
            stateClass, headline, phase, guidance, indexed, providerTotal,
            providerTotalObservedAt, remaining, initialBatch, freshness, freshnessAt,
            rescan, showProgress, lanes, events, action, pollingSeconds, alert, headline);
    }

    private Optional<StateRow> state(long accountId) {
        List<StateRow> rows = jdbc.query("""
            SELECT lifecycle, incremental_last_success_at,
                   backfill_status, backfill_started_at, backfill_completed_at,
                   initial_batch_completed_at, provider_total, provider_total_observed_at,
                   rescan_status, rescan_started_at, rescan_completed_at,
                   freshness_lease_expires_at, backfill_lease_expires_at
              FROM mailbox_sync_state
             WHERE account_id = ?
            """, (result, rowNumber) -> stateRow(result), accountId);
        return rows.stream().findFirst();
    }

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
                state == null || state.initialBatchCompletedAt() == null
                    ? "Initial setup" : "Historical mail",
                latest.get("BACKFILL"), now);
        }
        if (state != null && !"NOT_STARTED".equals(state.rescanStatus())) {
            addLane(result, "RESCAN", "Mailbox rescan", latest.get("RESCAN"), now);
        }
        return List.copyOf(result);
    }

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

    private static boolean laneActive(List<ProgressRow> progress, String lane) {
        return progress.stream()
            .filter(row -> lane.equals(row.lane()))
            .findFirst()
            .map(row -> "QUEUED".equals(row.status()) || "RUNNING".equals(row.status()))
            .orElse(false);
    }

    private static boolean hasAutomaticRetry(List<ProgressRow> progress) {
        return progress.stream().findFirst()
            .map(row -> "QUEUED".equals(row.status()) && row.retryCode() != null)
            .orElse(false);
    }

    private static boolean progressAllowed(StateRow state) {
        return state != null && state.providerTotal() != null
            && state.providerTotalObservedAt() != null && state.providerTotal() > 0;
    }

    private static Long reliableTotal(StateRow state) {
        return state != null && state.providerTotalObservedAt() != null
            ? state.providerTotal() : null;
    }

    private static Instant observedAt(StateRow state) {
        return state == null ? null : state.providerTotalObservedAt();
    }

    private static Long remaining(StateRow state, long indexed, boolean allowed) {
        Long total = reliableTotal(state);
        return allowed && total != null ? Math.max(total - indexed, 0) : null;
    }

    private static String initialBatch(StateRow state) {
        return state != null && state.initialBatchCompletedAt() != null
            ? "Ready" : "Preparing";
    }

    private static String freshness(Instant instant, Instant now) {
        return instant == null ? "Not checked yet" : "Checked " + relative(instant, now);
    }

    private static String rescan(StateRow state, Instant now) {
        if (state == null) return "Not needed";
        return switch (state.rescanStatus()) {
            case "REQUIRED" -> "Required";
            case "RUNNING" -> "In progress";
            case "COMPLETE" -> state.rescanCompletedAt() == null
                ? "Completed" : "Completed " + relative(state.rescanCompletedAt(), now);
            default -> "Not needed";
        };
    }

    private static String relative(Instant instant, Instant now) {
        if (instant == null) return "Not recorded";
        Duration age = Duration.between(instant, now);
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(1)) < 0) return "just now";
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

    private static String providerName(String provider) {
        if ("google".equalsIgnoreCase(provider)) return "Gmail";
        if ("microsoft".equalsIgnoreCase(provider)) return "Microsoft Outlook";
        return "Connected provider";
    }

    private static String laneName(String lane) {
        return switch (lane) {
            case "FRESHNESS" -> "New mail";
            case "BACKFILL" -> "Historical mail";
            case "RESCAN" -> "Mailbox rescan";
            default -> "Mailbox sync";
        };
    }

    private static String statusCopy(String status) {
        return switch (status) {
            case "QUEUED" -> "Queued";
            case "RUNNING" -> "In progress";
            case "COMPLETE", "SUCCESS" -> "Completed";
            case "FAILED" -> "Needs attention";
            default -> "Status unavailable";
        };
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static StateRow stateRow(ResultSet result) throws SQLException {
        return new StateRow(
            result.getString("lifecycle"),
            instant(result, "incremental_last_success_at"),
            result.getString("backfill_status"),
            instant(result, "backfill_started_at"),
            instant(result, "backfill_completed_at"),
            instant(result, "initial_batch_completed_at"),
            result.getObject("provider_total", Long.class),
            instant(result, "provider_total_observed_at"),
            result.getString("rescan_status"),
            instant(result, "rescan_started_at"),
            instant(result, "rescan_completed_at"),
            instant(result, "freshness_lease_expires_at"),
            instant(result, "backfill_lease_expires_at"));
    }

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
        Long providerTotal,
        Instant providerTotalObservedAt,
        String rescanStatus,
        Instant rescanStartedAt,
        Instant rescanCompletedAt,
        Instant freshnessLeaseExpiresAt,
        Instant backfillLeaseExpiresAt
    ) {
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
        String retryCode
    ) {
    }
}
