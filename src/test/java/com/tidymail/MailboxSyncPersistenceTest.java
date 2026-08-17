package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.service.MailboxSyncCommitService;
import com.tidymail.service.MailboxSyncCommitService.NoChangeUnit;
import com.tidymail.service.MailboxSyncCommitService.SyncUnit;
import com.tidymail.support.PostgresJourneyTest;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MailboxSyncPersistenceTest extends PostgresJourneyTest {
    private static final Instant COMMITTED_AT =
        Instant.parse("2026-08-14T18:00:00Z");

    @Autowired
    private MailboxSyncCommitService commits;

    @Test
    void failedCursorWriteRollsBackMetadataDeletesAndActivityTogether() {
        long accountId = userAccounts.createAccount("rollback@example.test");
        freshnessState(accountId, 0);
        insertMessage(
            accountId, "delete-me", BigInteger.ONE, "old",
            AttachmentState.UNKNOWN, null, COMMITTED_AT.minusSeconds(60), null);
        jdbc.execute("""
            CREATE FUNCTION fail_mailbox_state_update() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'forced cursor failure';
            END
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER fail_mailbox_state_update
            BEFORE UPDATE ON mailbox_sync_state
            FOR EACH ROW EXECUTE FUNCTION fail_mailbox_state_update()
            """);

        try {
            assertThatThrownBy(() -> commits.commitFreshnessUnit(freshnessUnit(
                accountId, 0,
                List.of(message(
                    "new-message", BigInteger.TWO, "new", true,
                    AttachmentState.PRESENT)),
                List.of("delete-me"), "cursor-2")))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .hasMessageContaining("forced cursor failure");

            assertThat(messageIds(accountId)).containsExactly("delete-me");
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_progress WHERE account_id = ?",
                Long.class, accountId)).isZero();
            assertThat(jdbc.queryForObject("""
                SELECT incremental_cursor_value
                  FROM mailbox_sync_state
                 WHERE account_id = ?
                """, String.class, accountId)).isEqualTo("cursor-1");
        } finally {
            jdbc.execute("DROP TRIGGER fail_mailbox_state_update ON mailbox_sync_state");
            jdbc.execute("DROP FUNCTION fail_mailbox_state_update()");
        }
    }

    @Test
    void replayIsIdempotentAndLowerRevisionCannotOverwriteNewerFacts() {
        long accountId = userAccounts.createAccount("revision@example.test");
        freshnessState(accountId, 0);
        MailMessage revisionTen = message(
            "same-message", BigInteger.TEN, "new subject", true,
            AttachmentState.PRESENT);
        commits.commitFreshnessUnit(freshnessUnit(
            accountId, 0, List.of(revisionTen), List.of(), "cursor-10"));
        long identity = jdbc.queryForObject("""
            SELECT id FROM message_metadata
             WHERE account_id = ? AND provider_message_id = 'same-message'
            """, Long.class, accountId);
        Instant firstSeen = jdbc.queryForObject("""
            SELECT first_seen_at FROM message_metadata
             WHERE account_id = ? AND provider_message_id = 'same-message'
            """, Timestamp.class, accountId).toInstant();

        commits.commitFreshnessUnit(freshnessUnit(
            accountId, 1, List.of(revisionTen), List.of(), "cursor-10"));
        commits.commitFreshnessUnit(freshnessUnit(
            accountId, 2,
            List.of(message(
                "same-message", BigInteger.valueOf(9), "stale subject", false,
                AttachmentState.UNKNOWN)),
            List.of(), "cursor-11"));

        var row = jdbc.queryForMap("""
            SELECT id, subject_header, unread, provider_revision,
                   attachment_state, first_seen_at
              FROM message_metadata
             WHERE account_id = ? AND provider_message_id = 'same-message'
            """, accountId);
        assertThat(row.get("id")).isEqualTo(identity);
        assertThat(row.get("subject_header")).isEqualTo("new subject");
        assertThat(row.get("unread")).isEqualTo(true);
        assertThat(row.get("provider_revision").toString()).isEqualTo("10");
        assertThat(row.get("attachment_state")).isEqualTo("PRESENT");
        assertThat(((Timestamp) row.get("first_seen_at")).toInstant()).isEqualTo(firstSeen);
        assertThat(messageIds(accountId)).containsExactly("same-message");
    }

    @Test
    void providerDeletesAreIdempotentAndConfinedToTheTargetAccount() {
        long owned = userAccounts.createAccount("owned-delete@example.test");
        long foreign = userAccounts.createAccount("foreign-delete@example.test");
        freshnessState(owned, 0);
        insertMessage(
            owned, "shared-provider-id", BigInteger.ONE, "owned",
            AttachmentState.UNKNOWN, null, COMMITTED_AT.minusSeconds(60), null);
        insertMessage(
            foreign, "shared-provider-id", BigInteger.ONE, "foreign",
            AttachmentState.UNKNOWN, null, COMMITTED_AT.minusSeconds(60), null);

        var first = commits.commitFreshnessUnit(freshnessUnit(
            owned, 0, List.of(), List.of("shared-provider-id"), "cursor-2"));
        var replay = commits.commitFreshnessUnit(freshnessUnit(
            owned, 1, List.of(), List.of("shared-provider-id"), "cursor-3"));

        assertThat(first.deleted()).isOne();
        assertThat(replay.deleted()).isZero();
        assertThat(messageIds(owned)).isEmpty();
        assertThat(messageIds(foreign)).containsExactly("shared-provider-id");
        assertThatThrownBy(() -> commits.commitFreshnessUnit(freshnessUnit(
            foreign, 0, List.of(), List.of("shared-provider-id"), "cursor-2")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("state not found");
    }

    @Test
    void noChangeProviderCheckAdvancesFreshnessWithoutCompletingBackfill() {
        long accountId = userAccounts.createAccount("no-change@example.test");
        freshnessState(accountId, 0);

        commits.commitNoChangeSuccess(new NoChangeUnit(
            accountId, "freshness-worker", 0,
            "GMAIL_HISTORY_ID", "cursor-2", COMMITTED_AT, 12_345L));

        var state = jdbc.queryForMap("""
            SELECT incremental_cursor_value, incremental_last_success_at,
                   backfill_status, backfill_completed_at, version
              FROM mailbox_sync_state
             WHERE account_id = ?
            """, accountId);
        assertThat(state.get("incremental_cursor_value")).isEqualTo("cursor-2");
        assertThat(state.get("incremental_last_success_at")).isNotNull();
        assertThat(state.get("backfill_status")).isEqualTo("NOT_STARTED");
        assertThat(state.get("backfill_completed_at")).isNull();
        assertThat(state.get("version")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT provider_total_snapshot
              FROM sync_progress
             WHERE account_id = ? AND lane = 'FRESHNESS'
            """, Long.class, accountId)).isEqualTo(12_345L);
    }

    @Test
    void completedRescanSweepsOnlyOldRowsOutsideTheGeneration() {
        long accountId = userAccounts.createAccount("rescan@example.test");
        UUID completedGeneration = UUID.randomUUID();
        UUID oldGeneration = UUID.randomUUID();
        Instant rescanStartedAt = COMMITTED_AT.minusSeconds(300);
        rescanState(accountId, completedGeneration, rescanStartedAt);
        insertMessage(
            accountId, "stale", BigInteger.ONE, "stale",
            AttachmentState.UNKNOWN, oldGeneration,
            rescanStartedAt.minusSeconds(1), null);
        insertMessage(
            accountId, "freshness-won", BigInteger.TWO, "fresh",
            AttachmentState.UNKNOWN, oldGeneration,
            rescanStartedAt.plusSeconds(1), null);
        insertMessage(
            accountId, "current-generation", BigInteger.ONE, "current",
            AttachmentState.UNKNOWN, completedGeneration,
            rescanStartedAt.minusSeconds(1), null);

        SyncUnit completed = new SyncUnit(
            accountId, "backfill-worker", 0, List.of(), List.of(),
            null, null, COMMITTED_AT, 0, null,
            completedGeneration, rescanStartedAt, true);
        var result = commits.commitRescanUnit(completed);

        assertThat(result.deleted()).isOne();
        assertThat(messageIds(accountId))
            .containsExactlyInAnyOrder("freshness-won", "current-generation");
        assertThat(jdbc.queryForObject("""
            SELECT rescan_status FROM mailbox_sync_state WHERE account_id = ?
            """, String.class, accountId)).isEqualTo("COMPLETE");
    }

    private void freshnessState(long accountId, long version) {
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle,
                incremental_cursor_kind, incremental_cursor_value,
                freshness_lease_owner, freshness_lease_expires_at, version
            ) VALUES (
                ?, 'google', 'ACTIVE', 'GMAIL_HISTORY_ID', 'cursor-1',
                'freshness-worker', ?, ?
            )
            """, accountId, Timestamp.from(COMMITTED_AT.plusSeconds(3600)), version);
    }

    private void rescanState(
            long accountId, UUID generation, Instant rescanStartedAt) {
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, rescan_status,
                rescan_generation, rescan_started_at,
                backfill_lease_owner, backfill_lease_expires_at
            ) VALUES (
                ?, 'google', 'RESCANNING', 'RUNNING', ?, ?,
                'backfill-worker', ?
            )
            """, accountId, generation, Timestamp.from(rescanStartedAt),
            Timestamp.from(COMMITTED_AT.plusSeconds(3600)));
    }

    private static SyncUnit freshnessUnit(
            long accountId,
            long expectedVersion,
            List<MailMessage> messages,
            List<String> deletes,
            String cursor) {
        return new SyncUnit(
            accountId, "freshness-worker", expectedVersion,
            messages, deletes, "GMAIL_HISTORY_ID", cursor,
            COMMITTED_AT, messages.size(), null, null, null, false);
    }

    private static MailMessage message(
            String id,
            BigInteger revision,
            String subject,
            boolean unread,
            AttachmentState attachmentState) {
        return new MailMessage(
            id, "thread-" + id, "google", COMMITTED_AT.minusSeconds(60),
            Map.of(
                "From", List.of("Sender <sender@example.test>"),
                "Subject", List.of(subject)),
            unread ? Set.of("INBOX", "UNREAD") : Set.of("INBOX"),
            2048L, revision, attachmentState);
    }

    private void insertMessage(
            long accountId,
            String providerMessageId,
            BigInteger revision,
            String subject,
            AttachmentState attachmentState,
            UUID generation,
            Instant lastSyncedAt,
            Instant attachmentObservedAt) {
        String source = attachmentObservedAt == null ? null : "GMAIL_SEARCH";
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, subject_header,
                first_seen_at, last_synced_at, provider_revision,
                full_scan_generation, attachment_state,
                attachment_observed_at, attachment_source
            ) VALUES (?, 'google', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, accountId, providerMessageId, subject,
            Timestamp.from(lastSyncedAt), Timestamp.from(lastSyncedAt),
            revision, generation, attachmentState.name(),
            attachmentObservedAt == null ? null : Timestamp.from(attachmentObservedAt),
            source);
    }

    private List<String> messageIds(long accountId) {
        return jdbc.queryForList("""
            SELECT provider_message_id
              FROM message_metadata
             WHERE account_id = ?
             ORDER BY provider_message_id
            """, String.class, accountId);
    }
}
