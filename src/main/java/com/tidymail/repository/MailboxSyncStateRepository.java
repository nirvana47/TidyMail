package com.tidymail.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MailboxSyncStateRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MailboxSyncStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StateSnapshot> findByAccountId(long accountId) {
        requireAccount(accountId);
        return jdbc.query("""
            SELECT account_id, provider, lifecycle, incremental_cursor_kind,
                   incremental_cursor_value, incremental_last_success_at,
                   backfill_page_token, backfill_status,
                   initial_batch_completed_at, provider_total,
                   rescan_status, rescan_generation, rescan_started_at, rescan_anchor,
                   freshness_lease_owner, freshness_lease_expires_at,
                   backfill_lease_owner, backfill_lease_expires_at, version
              FROM mailbox_sync_state
             WHERE account_id = :account_id
            """, account(accountId), result -> result.next()
                ? Optional.of(snapshot(result))
                : Optional.empty());
    }

    public StateSnapshot lockForCommit(long accountId) {
        requireAccount(accountId);
        return jdbc.query("""
            SELECT account_id, provider, lifecycle, incremental_cursor_kind,
                   incremental_cursor_value, incremental_last_success_at,
                   backfill_page_token, backfill_status,
                   initial_batch_completed_at, provider_total,
                   rescan_status, rescan_generation, rescan_started_at, rescan_anchor,
                   freshness_lease_owner, freshness_lease_expires_at,
                   backfill_lease_owner, backfill_lease_expires_at, version
              FROM mailbox_sync_state
             WHERE account_id = :account_id
             FOR UPDATE
            """, account(accountId), result -> {
                if (!result.next()) {
                    throw new IllegalStateException("Mailbox sync state not found");
                }
                return snapshot(result);
            });
    }

    public void ensureState(long accountId, String provider) {
        requireAccount(accountId);
        if (provider == null || provider.isBlank() || provider.length() > 16) {
            throw new IllegalArgumentException("Bounded provider is required");
        }
        jdbc.update("""
            INSERT INTO mailbox_sync_state(account_id, provider)
            VALUES (:account_id, :provider)
            ON CONFLICT (account_id) DO NOTHING
            """, account(accountId).addValue("provider", provider));
    }

    @Transactional
    public LaneQueue queueLane(long accountId, Lane lane) {
        requireAccount(accountId);
        jdbc.queryForObject("""
            SELECT version FROM mailbox_sync_state
             WHERE account_id = :account_id
             FOR UPDATE
            """, account(accountId), Long.class);
        Long queuedId = jdbc.query("""
            SELECT id FROM sync_progress
             WHERE account_id = :account_id
               AND job_type = 'MAILBOX_SYNC'
               AND lane = :lane
               AND status = 'QUEUED'
             ORDER BY id DESC
             LIMIT 1
            """, account(accountId).addValue("lane", lane.name()),
            result -> result.next() ? result.getLong("id") : null);
        if (queuedId != null) {
            return new LaneQueue(queuedId, false);
        }
        Long id = jdbc.queryForObject("""
            INSERT INTO sync_progress(
                account_id, job_type, status, started_at, lane, phase
            ) VALUES (
                :account_id, 'MAILBOX_SYNC', 'QUEUED', :queued_at, :lane, 'QUEUED'
            )
            RETURNING id
            """, account(accountId)
                .addValue("lane", lane.name())
                .addValue("queued_at", Timestamp.from(Instant.now())), Long.class);
        return new LaneQueue(id, true);
    }

    public boolean acquireLaneLease(
            long accountId,
            Lane lane,
            String owner,
            Instant expiresAt,
            Instant now,
            long expectedVersion) {
        requireLease(owner, expiresAt, now);
        String prefix = lane.leasePrefix();
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET %1$s_lease_owner = :owner,
                   %1$s_lease_expires_at = :expires_at,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND (%1$s_lease_owner IS NULL OR %1$s_lease_expires_at <= :now)
            """.formatted(prefix), account(accountId)
                .addValue("owner", owner)
                .addValue("expires_at", Timestamp.from(expiresAt))
                .addValue("now", Timestamp.from(now))
                .addValue("expected_version", expectedVersion)) == 1;
    }

    public boolean heartbeatLaneLease(
            long accountId,
            Lane lane,
            String owner,
            Instant expiresAt,
            long expectedVersion) {
        requireLease(owner, expiresAt, Instant.EPOCH);
        String prefix = lane.leasePrefix();
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET %1$s_lease_expires_at = :expires_at,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND %1$s_lease_owner = :owner
            """.formatted(prefix), account(accountId)
                .addValue("owner", owner)
                .addValue("expires_at", Timestamp.from(expiresAt))
                .addValue("expected_version", expectedVersion)) == 1;
    }

    public boolean releaseLaneLease(
            long accountId, Lane lane, String owner, long expectedVersion) {
        requireOwner(owner);
        String prefix = lane.leasePrefix();
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET %1$s_lease_owner = NULL,
                   %1$s_lease_expires_at = NULL,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND %1$s_lease_owner = :owner
            """.formatted(prefix), account(accountId)
                .addValue("owner", owner)
                .addValue("expected_version", expectedVersion)) == 1;
    }

    public boolean updateIncrementalCursor(
            long accountId,
            String owner,
            long expectedVersion,
            String cursorKind,
            String cursorValue,
            Instant completedAt) {
        requireCursor(cursorKind, cursorValue, completedAt);
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET incremental_cursor_kind = :cursor_kind,
                   incremental_cursor_value = :cursor_value,
                   incremental_last_attempt_at = :completed_at,
                   incremental_last_success_at = :completed_at,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND freshness_lease_owner = :owner
            """, account(accountId)
                .addValue("owner", requireOwner(owner))
                .addValue("expected_version", expectedVersion)
                .addValue("cursor_kind", cursorKind)
                .addValue("cursor_value", cursorValue)
                .addValue("completed_at", Timestamp.from(completedAt))) == 1;
    }

    public boolean recordNoChangeSuccess(
            long accountId,
            String owner,
            long expectedVersion,
            String cursorKind,
            String cursorValue,
            Instant completedAt) {
        return updateIncrementalCursor(
            accountId, owner, expectedVersion, cursorKind, cursorValue, completedAt);
    }

    public boolean completeBootstrap(
            long accountId,
            String owner,
            long expectedVersion,
            String cursorKind,
            String cursorValue,
            Long providerTotal,
            Instant completedAt) {
        requireCursor(cursorKind, cursorValue, completedAt);
        if (providerTotal != null && providerTotal < 0) {
            throw new IllegalArgumentException("Provider total cannot be negative");
        }
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET lifecycle = 'BACKFILLING',
                   incremental_cursor_kind = :cursor_kind,
                   incremental_cursor_value = :cursor_value,
                   initial_batch_completed_at = :completed_at,
                   provider_total = :provider_total,
                   provider_total_observed_at = :completed_at,
                   backfill_page_token = NULL,
                   backfill_status = 'RUNNING',
                   backfill_started_at = :completed_at,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND backfill_lease_owner = :owner
               AND initial_batch_completed_at IS NULL
            """, account(accountId)
                .addValue("owner", requireOwner(owner))
                .addValue("expected_version", expectedVersion)
                .addValue("cursor_kind", cursorKind)
                .addValue("cursor_value", cursorValue)
                .addValue("provider_total", providerTotal)
                .addValue("completed_at", Timestamp.from(completedAt))) == 1;
    }

    public boolean markRescanRequired(
            long accountId, String owner, long expectedVersion, Instant attemptedAt) {
        requireInstant(attemptedAt);
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET lifecycle = 'RESCAN_REQUIRED',
                   rescan_status = 'REQUIRED',
                   incremental_last_attempt_at = :attempted_at,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND freshness_lease_owner = :owner
            """, account(accountId)
                .addValue("owner", requireOwner(owner))
                .addValue("expected_version", expectedVersion)
                .addValue("attempted_at", Timestamp.from(attemptedAt))) == 1;
    }

    public boolean beginRescanAfterRepair(
            long accountId,
            String owner,
            long expectedVersion,
            String cursorKind,
            String cursorValue,
            UUID generation,
            Instant startedAt) {
        requireCursor(cursorKind, cursorValue, startedAt);
        if (generation == null) {
            throw new IllegalArgumentException("Rescan generation is required");
        }
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET lifecycle = 'RESCANNING',
                   incremental_cursor_kind = :cursor_kind,
                   incremental_cursor_value = :cursor_value,
                   incremental_last_attempt_at = :started_at,
                   rescan_status = 'RUNNING',
                   rescan_generation = :generation,
                   rescan_started_at = :started_at,
                   rescan_anchor = '__START__',
                   rescan_completed_at = NULL,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND backfill_lease_owner = :owner
               AND rescan_status = 'REQUIRED'
            """, account(accountId)
                .addValue("owner", requireOwner(owner))
                .addValue("expected_version", expectedVersion)
                .addValue("cursor_kind", cursorKind)
                .addValue("cursor_value", cursorValue)
                .addValue("generation", generation)
                .addValue("started_at", Timestamp.from(startedAt))) == 1;
    }

    public boolean updateBackfillCursor(
            long accountId,
            String owner,
            long expectedVersion,
            String pageToken,
            Instant completedAt) {
        requireInstant(completedAt);
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET backfill_page_token = :page_token,
                   backfill_status = CASE
                       WHEN :complete THEN 'COMPLETE' ELSE 'RUNNING' END,
                   backfill_started_at = COALESCE(backfill_started_at, :completed_at),
                   backfill_completed_at = CASE
                       WHEN :complete THEN :completed_at
                       ELSE CAST(NULL AS TIMESTAMPTZ) END,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND backfill_lease_owner = :owner
            """, account(accountId)
                .addValue("owner", requireOwner(owner))
                .addValue("expected_version", expectedVersion)
                .addValue("page_token", pageToken)
                .addValue("complete", pageToken == null)
                .addValue("completed_at", Timestamp.from(completedAt))) == 1;
    }

    public boolean updateRescanCursor(
            long accountId,
            String owner,
            long expectedVersion,
            UUID generation,
            String anchor,
            Instant completedAt) {
        requireInstant(completedAt);
        if (generation == null) throw new IllegalArgumentException("Rescan generation is required");
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET rescan_generation = :generation,
                   rescan_anchor = :anchor,
                   rescan_status = CASE
                       WHEN :complete THEN 'COMPLETE' ELSE 'RUNNING' END,
                   lifecycle = CASE
                       WHEN :complete THEN 'ACTIVE' ELSE 'RESCANNING' END,
                   rescan_completed_at = CASE
                       WHEN :complete THEN :completed_at
                       ELSE CAST(NULL AS TIMESTAMPTZ) END,
                   version = version + 1
             WHERE account_id = :account_id
               AND version = :expected_version
               AND backfill_lease_owner = :owner
            """, account(accountId)
                .addValue("owner", requireOwner(owner))
                .addValue("expected_version", expectedVersion)
                .addValue("generation", generation)
                .addValue("anchor", anchor)
                .addValue("complete", anchor == null)
                .addValue("completed_at", Timestamp.from(completedAt))) == 1;
    }

    public boolean updateLifecycle(
            long accountId, String lifecycle, long expectedVersion) {
        if (lifecycle == null || lifecycle.isBlank()) {
            throw new IllegalArgumentException("Lifecycle is required");
        }
        return jdbc.update("""
            UPDATE mailbox_sync_state
               SET lifecycle = :lifecycle, version = version + 1
             WHERE account_id = :account_id AND version = :expected_version
            """, account(accountId)
                .addValue("lifecycle", lifecycle)
                .addValue("expected_version", expectedVersion)) == 1;
    }

    public void recordActivity(
            long accountId,
            Lane lane,
            long fetched,
            long changed,
            long deleted,
            long indexed,
            Long providerTotal,
            Instant completedAt) {
        if (fetched < 0 || changed < 0 || deleted < 0 || indexed < 0
                || providerTotal != null && providerTotal < 0) {
            throw new IllegalArgumentException("Activity counters cannot be negative");
        }
        requireInstant(completedAt);
        jdbc.update("""
            INSERT INTO sync_progress(
                account_id, job_type, status, started_at, finished_at,
                messages_scanned, messages_upserted, last_successful_at,
                lane, phase, messages_fetched, messages_changed,
                messages_deleted, indexed_messages, provider_total_snapshot
            ) VALUES (
                :account_id, 'MAILBOX_SYNC', 'COMPLETE', :completed_at, :completed_at,
                :fetched, :indexed, :completed_at,
                :lane, 'COMMIT', :fetched, :changed,
                :deleted, :indexed, :provider_total
            )
            """, account(accountId)
                .addValue("lane", lane.name())
                .addValue("fetched", fetched)
                .addValue("changed", changed)
                .addValue("deleted", deleted)
                .addValue("indexed", indexed)
                .addValue("provider_total", providerTotal)
                .addValue("completed_at", Timestamp.from(completedAt)));
    }

    private static StateSnapshot snapshot(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new StateSnapshot(
            result.getLong("account_id"),
            result.getString("provider"),
            result.getString("lifecycle"),
            result.getString("incremental_cursor_kind"),
            result.getString("incremental_cursor_value"),
            instant(result.getTimestamp("incremental_last_success_at")),
            result.getString("backfill_page_token"),
            result.getString("backfill_status"),
            instant(result.getTimestamp("initial_batch_completed_at")),
            result.getObject("provider_total", Long.class),
            result.getString("rescan_status"),
            result.getObject("rescan_generation", UUID.class),
            instant(result.getTimestamp("rescan_started_at")),
            result.getString("rescan_anchor"),
            result.getString("freshness_lease_owner"),
            instant(result.getTimestamp("freshness_lease_expires_at")),
            result.getString("backfill_lease_owner"),
            instant(result.getTimestamp("backfill_lease_expires_at")),
            result.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static MapSqlParameterSource account(long accountId) {
        requireAccount(accountId);
        return new MapSqlParameterSource("account_id", accountId);
    }

    private static void requireAccount(long accountId) {
        if (accountId <= 0) throw new IllegalArgumentException("Account is required");
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("Bounded lease owner is required");
        }
        return owner;
    }

    private static void requireLease(
            String owner, Instant expiresAt, Instant lowerBound) {
        requireOwner(owner);
        requireInstant(expiresAt);
        requireInstant(lowerBound);
        if (!expiresAt.isAfter(lowerBound)) {
            throw new IllegalArgumentException("Lease expiry must be in the future");
        }
    }

    private static void requireCursor(
            String cursorKind, String cursorValue, Instant completedAt) {
        if (cursorKind == null || cursorKind.isBlank()
                || cursorValue == null || cursorValue.isBlank()
                || cursorKind.length() > 32 || cursorValue.length() > 2048) {
            throw new IllegalArgumentException("A bounded cursor is required");
        }
        requireInstant(completedAt);
    }

    private static void requireInstant(Instant value) {
        if (value == null) throw new IllegalArgumentException("Timestamp is required");
    }

    public enum Lane {
        FRESHNESS("freshness"),
        BACKFILL("backfill"),
        RESCAN("backfill");

        private final String leasePrefix;

        Lane(String leasePrefix) {
            this.leasePrefix = leasePrefix;
        }

        String leasePrefix() {
            return leasePrefix;
        }
    }

    public record StateSnapshot(
        long accountId,
        String provider,
        String lifecycle,
        String incrementalCursorKind,
        String incrementalCursorValue,
        Instant incrementalLastSuccessAt,
        String backfillPageToken,
        String backfillStatus,
        Instant initialBatchCompletedAt,
        Long providerTotal,
        String rescanStatus,
        UUID rescanGeneration,
        Instant rescanStartedAt,
        String rescanAnchor,
        String freshnessLeaseOwner,
        Instant freshnessLeaseExpiresAt,
        String backfillLeaseOwner,
        Instant backfillLeaseExpiresAt,
        long version
    ) {
    }

    public record LaneQueue(long progressId, boolean created) {
    }
}
