package com.tidymail.service;

import com.tidymail.dto.ActivityPageModel;
import com.tidymail.dto.ActivityStatusModel;
import com.tidymail.dto.ActivityStatusModel.Action;
import com.tidymail.dto.ActivityStatusModel.Event;
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
import java.util.List;
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
 * status, recent milestones, and completed checks for the selected
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
                status(account, indexed, state.orElse(null), progress, events(account.getId(), clock.instant()), clock.instant()));
    }

    /** Translate each provider's work into the same user-facing summary. */
    private ActivityStatusModel status(UserAccount account, long indexed, StateRow state,
            List<ProgressRow> progress, List<Event> events, Instant now) {
        boolean outlook = "microsoft".equalsIgnoreCase(account.getProvider());
        boolean supported = outlook || "google".equalsIgnoreCase(account.getProvider());
        boolean reconnect = account.getOauthStatus() == OAuthStatus.REAUTH_REQUIRED
            || state != null && "REAUTH_REQUIRED".equals(state.lifecycle());
        boolean importing = supported && (state == null || !"COMPLETE".equals(state.backfillStatus()));
        boolean failed = progress.stream().anyMatch(row -> "FAILED".equals(row.status())
            && (outlook || !"BACKFILL".equals(row.lane()) || importing));
        failed |= state != null && ("ERROR".equals(state.lifecycle()) || state.errorCode() != null);
        boolean working = progress.stream().anyMatch(row ->
            "RUNNING".equals(row.status()) || "QUEUED".equals(row.status()));
        boolean rechecking = !outlook && state != null
            && ("REQUIRED".equals(state.rescanStatus()) || "RUNNING".equals(state.rescanStatus()));
        Instant checkedAt = state == null ? null : state.mailboxCheckedAt();
        String connection = reconnect ? "Reconnect required" : "Connected";
        String current = "Waiting for next check";
        String headline = checkedAt == null ? "Waiting for a completed mailbox check" : "Mailbox activity is current";
        String guidance = "Indexed cleanup data is available for this account.";
        String stateClass = "current";
        Integer polling = null;
        Action action = null;
        ListingProgress listing = !outlook && importing ? listingProgress(state, indexed) : null;
        if (!supported) {
            current = "Unavailable";
            headline = "Sync activity is unavailable for this provider";
            guidance = "Indexed messages remain available for cleanup.";
            stateClass = "unsupported";
            connection = "Unavailable";
        } else if (reconnect) {
            current = "Needs attention";
            headline = "Reconnect " + providerName(account.getProvider()) + " to continue syncing";
            guidance = "Your indexed data remains available. Reconnect to resume mailbox checks.";
            stateClass = "reauthorization";
            action = new Action("Reconnect " + providerName(account.getProvider()),
                "/oauth2/authorization/" + account.getProvider(), false, "Reconnect this mailbox");
        } else if (failed) {
            current = "Needs attention";
            headline = "Sync paused after a recoverable error";
            guidance = "Your indexed data is still available. Retry now or wait for the next automatic attempt.";
            stateClass = "error";
            polling = 30;
            action = new Action("Retry sync", "/api/metadata-sync", true, "Retry mailbox sync");
        } else if (rechecking) {
            current = "Rechecking mailbox";
            headline = "Rechecking mailbox history";
            guidance = "Sync continues in the background; you can close this page.";
            stateClass = "active";
            polling = 5;
        } else if (importing) {
            current = "Importing existing mail";
            headline = state == null || state.initialBatchCompletedAt() == null
                ? "Preparing your cleanup workspace" : "Cleanup is ready while indexing continues";
            guidance = "Existing mail is being made available for cleanup. Sync continues in the background; you can close this page.";
            stateClass = "active";
            polling = 5;
        } else if (working) {
            current = "Checking for changes";
            headline = current;
            guidance = "Sync continues in the background; you can close this page.";
            stateClass = "active";
            polling = 5;
        }
        String phase = rechecking && state != null && "__VERIFY_MISSING__".equals(state.rescanAnchor())
            ? "Checking missing messages" : "Mailbox status";
        return new ActivityStatusModel(stateClass, headline, phase, guidance, indexed, listing,
            importing ? "In progress — making existing mail available for cleanup" : null,
            freshness(checkedAt, now), checkedAt, current, connection, events, action, polling,
            reconnect || failed, headline);
    }

    /** Loads the account's current sync state row. */
    private Optional<StateRow> state(long accountId) {
        List<StateRow> rows = jdbc.query("""
                SELECT lifecycle, mailbox_checked_at, error_code, backfill_status,
                       initial_batch_completed_at, gmail_scope_total, gmail_backfill_phase, rescan_status, rescan_anchor
                  FROM mailbox_sync_state
                 WHERE account_id = ?
                """, (result, rowNumber) -> stateRow(result), accountId);
        return rows.stream().findFirst();
    }

    /** Loads the account's recent sync progress rows. */
    private List<ProgressRow> progress(long accountId) {
        return jdbc.query("""
                SELECT DISTINCT ON (lane) lane, status
                  FROM sync_progress
                 WHERE account_id = ? AND job_type = 'MAILBOX_SYNC' AND lane <> 'LEGACY'
                 ORDER BY lane, id DESC
                """, (result, rowNumber) -> progressRow(result), accountId);
    }

    /** Only actual milestones and failures, never individual successful work units. */
    private List<Event> events(long accountId, Instant now) {
        return jdbc.query("""
            SELECT kind, occurred_at FROM (
                SELECT kind, occurred_at FROM mailbox_sync_events WHERE account_id=?
                UNION ALL
                SELECT 'INTERRUPTED', COALESCE(finished_at,started_at) FROM sync_progress
                WHERE account_id=? AND job_type='MAILBOX_SYNC' AND status='FAILED' AND lane<>'LEGACY'
            ) history ORDER BY occurred_at DESC LIMIT ?
            """, (row, index) -> {
                Instant at = instant(row, "occurred_at");
                String kind = row.getString("kind");
                String summary = switch (kind) {
                    case "IMPORT_COMPLETE" -> "Initial import completed";
                    case "CHECK_COMPLETE" -> "Mailbox check completed";
                    case "RESCAN_COMPLETE" -> "Mailbox recheck completed";
                    default -> "Sync interrupted";
                };
                String detail = switch (kind) {
                    case "IMPORT_COMPLETE" -> "Existing mail is available for cleanup";
                    case "CHECK_COMPLETE" -> "Included mail folders checked for changes";
                    case "RESCAN_COMPLETE" -> "Mailbox history rechecked";
                    default -> "Another automatic attempt will be made; you can also retry now";
                };
                return new Event(at, relative(at, now), summary, detail);
            }, accountId, accountId, EVENT_LIMIT);
    }

    private static ListingProgress listingProgress(StateRow state, long indexed) {
        if (state == null) return new ListingProgress(indexed, null, "Indexing Inbox");
        Long total = state.gmailScopeTotal();
        // A stale low estimate cannot represent remaining work or a completed bar.
        return new ListingProgress(indexed, total != null && total > indexed ? total : null,
            "ALL_MAIL".equals(state.gmailBackfillPhase()) ? "Indexing All Mail" : "Indexing Inbox");
    }

    private static String freshness(Instant instant, Instant now) {
        return instant == null ? "Not completed yet" : "Checked " + relative(instant, now);
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

    private static StateRow stateRow(ResultSet result) throws SQLException {
        return new StateRow(result.getString("lifecycle"), instant(result, "mailbox_checked_at"),
            result.getString("error_code"), result.getString("backfill_status"),
            instant(result, "initial_batch_completed_at"), result.getObject("gmail_scope_total", Long.class),
            result.getString("rescan_status"), result.getString("gmail_backfill_phase"), result.getString("rescan_anchor"));
    }

    private static ProgressRow progressRow(ResultSet result) throws SQLException {
        return new ProgressRow(result.getString("lane"), result.getString("status"));
    }

    /** Reads a nullable timestamp column as an instant. */
    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record StateRow(String lifecycle, Instant mailboxCheckedAt, String errorCode,
            String backfillStatus, Instant initialBatchCompletedAt, Long gmailScopeTotal, String rescanStatus, String gmailBackfillPhase, String rescanAnchor) {}

    private record ProgressRow(String lane, String status) {}
}
