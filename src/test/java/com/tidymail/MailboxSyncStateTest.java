package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.support.PostgresJourneyTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class MailboxSyncStateTest extends PostgresJourneyTest {

    @Test
    void accountConnectsAndReceivesIndependentDurableMailboxLaneState() {
        long accountId = userAccounts.createAccount("mailbox-state@example.test");
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, incremental_cursor_kind,
                incremental_cursor_value, provider_total
            ) VALUES (?, 'google', 'GMAIL_HISTORY_ID', '18446744073709551615', 10000)
            """, accountId);

        var state = jdbc.queryForMap(
            "SELECT * FROM mailbox_sync_state WHERE account_id = ?", accountId);
        assertThat(state.get("lifecycle")).isEqualTo("NEW");
        assertThat(state.get("backfill_status")).isEqualTo("NOT_STARTED");
        assertThat(state.get("rescan_status")).isEqualTo("NOT_STARTED");
        assertThat(state.get("version")).isEqualTo(0L);
        assertThat(state.get("freshness_lease_owner")).isNull();
        assertThat(state.get("backfill_lease_owner")).isNull();

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE mailbox_sync_state
               SET freshness_lease_owner = 'worker'
             WHERE account_id = ?
            """, accountId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE mailbox_sync_state SET lifecycle = 'COMPLETE' WHERE account_id = ?
            """, accountId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE mailbox_sync_state
               SET incremental_cursor_kind = NULL,
                   incremental_cursor_value = 'orphan'
             WHERE account_id = ?
            """, accountId)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM user_accounts WHERE id = ?", accountId);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM mailbox_sync_state WHERE account_id = ?",
            Long.class, accountId)).isZero();
    }

    @Test
    void metadataPersistsUnsignedRevisionAndTriStateProvenance() {
        long accountId = userAccounts.createAccount("revision-state@example.test");
        UUID generation = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-08-14T12:00:00Z");
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id,
                provider_revision, full_scan_generation,
                attachment_state, attachment_observed_at, attachment_source
            ) VALUES (?, 'google', 'revision-max', ?, ?, 'PRESENT', ?, 'GMAIL_SEARCH')
            """, accountId, new BigDecimal("99999999999999999999"), generation,
            Timestamp.from(observedAt));

        var metadata = jdbc.queryForMap("""
            SELECT provider_revision, full_scan_generation, attachment_state,
                   attachment_observed_at, attachment_source
              FROM message_metadata
             WHERE account_id = ? AND provider_message_id = 'revision-max'
            """, accountId);
        assertThat(metadata.get("provider_revision").toString())
            .isEqualTo("99999999999999999999");
        assertThat(metadata.get("full_scan_generation")).isEqualTo(generation);
        assertThat(metadata.get("attachment_state")).isEqualTo("PRESENT");
        assertThat(metadata.get("attachment_source")).isEqualTo("GMAIL_SEARCH");

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_revision
            ) VALUES (?, 'google', 'revision-overflow', 100000000000000000000)
            """, accountId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id,
                attachment_state, attachment_observed_at, attachment_source
            ) VALUES (?, 'google', 'unknown-with-evidence', 'UNKNOWN', now(), 'GMAIL_SEARCH')
            """, accountId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationRetainsAccountIdentityAndCreatesGuardedSweepIndexes() {
        List<String> metadataColumns = jdbc.queryForList("""
            SELECT column_name
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'message_metadata'
            """, String.class);
        assertThat(metadataColumns)
            .contains(
                "provider_revision", "full_scan_generation", "attachment_state",
                "attachment_observed_at", "attachment_source")
            .doesNotContain("metadata_" + "snippet");

        List<String> stateColumns = jdbc.queryForList("""
            SELECT column_name
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'mailbox_sync_state'
            """, String.class);
        assertThat(stateColumns)
            .contains(
                "account_id", "incremental_cursor_value", "backfill_page_token",
                "rescan_generation", "freshness_lease_owner", "backfill_lease_owner")
            .doesNotContain("gmail_account_id", "email", "provider_account_id");

        List<String> indexes = jdbc.queryForList("""
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename IN ('mailbox_sync_state', 'message_metadata', 'sync_progress')
            """, String.class);
        assertThat(indexes).contains(
            "idx_message_metadata_account_generation_freshness",
            "idx_mailbox_sync_state_freshness_lease",
            "idx_mailbox_sync_state_backfill_lease",
            "idx_sync_progress_account_lane_newest");
    }
}
