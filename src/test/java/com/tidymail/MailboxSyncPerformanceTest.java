package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.service.MailboxSyncCommitService;
import com.tidymail.support.PostgresJourneyTest;
import com.tidymail.support.SyntheticMailboxGenerator;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MailboxSyncPerformanceTest extends PostgresJourneyTest {
    private static final int WORK_UNIT_SIZE = 500;
    private static final Duration SEED_BUDGET = Duration.ofMinutes(2);
    private static final Duration ROUTE_BUDGET = Duration.ofMinutes(1);
    private static final Duration UPSERT_BUDGET = Duration.ofMinutes(1);
    private static final long HEAP_BUDGET_BYTES = 256L * 1024 * 1024;

    @Autowired MailboxSyncStateRepository states;
    @Autowired MailboxSyncCommitService commits;

    @Test
    void mailboxSyncUsesBoundedWorkAtRealisticVolume() throws Exception {
        int rowCount = configuredRowCount();
        long seedStarted = System.nanoTime();
        var mailbox = seedSyntheticMailbox(rowCount);
        Duration seedTime = elapsed(seedStarted);
        assertThat(seedTime).isLessThan(SEED_BUDGET);

        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, incremental_cursor_kind,
                incremental_cursor_value, incremental_last_success_at,
                backfill_status, backfill_completed_at, initial_batch_completed_at,
                provider_total
            ) VALUES (
                ?, 'google', 'ACTIVE', 'GMAIL_HISTORY_ID', '1', CURRENT_TIMESTAMP,
                'COMPLETE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?
            )
            """, mailbox.accountId(), rowCount);
        jdbc.update("""
            INSERT INTO sync_progress(
                account_id, job_type, lane, status, started_at, finished_at,
                last_successful_at, messages_scanned, messages_upserted
            ) VALUES (
                ?, 'MAILBOX_SYNC', 'FRESHNESS', 'COMPLETE',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?
            )
            """, mailbox.accountId(), rowCount, rowCount);
        jdbc.execute("ANALYZE message_metadata");

        long firstPageStarted = System.nanoTime();
        List<String> firstPopulatedRows = jdbc.queryForList("""
            SELECT provider_message_id
              FROM message_metadata
             WHERE account_id = ?
             ORDER BY received_at DESC, id DESC
             LIMIT 200
            """, String.class, mailbox.accountId());
        Duration firstPageTime = elapsed(firstPageStarted);
        assertThat(firstPopulatedRows).hasSize(200);
        assertThat(firstPageTime).isLessThan(ROUTE_BUDGET);

        String owner = "owner-" + mailbox.seed() + "@mailbox.test";
        Duration senderRender = timedRoute(
            "/senders", owner, mailbox.accountId());
        Duration unreadRender = timedRoute(
            "/unread", owner, mailbox.accountId());
        Duration activityRender = timedRoute(
            "/activity", owner, mailbox.accountId());
        assertThat(List.of(senderRender, unreadRender, activityRender))
            .allMatch(duration -> duration.compareTo(ROUTE_BUDGET) < 0);

        long heapBefore = usedHeap();
        ArrayList<MailMessage> workUnit = new ArrayList<>(WORK_UNIT_SIZE);
        for (int index = 0; index < WORK_UNIT_SIZE; index++) {
            workUnit.add(new MailMessage(
                "performance-upsert-" + index,
                "performance-thread-" + index,
                "google",
                Instant.parse("2026-08-14T12:00:00Z").plusSeconds(index),
                Map.of(
                    "From", List.of("Performance Sender <performance@example.test>"),
                    "Subject", List.of("Bounded work unit " + index)),
                Set.of("INBOX", "UNREAD"),
                4096L,
                BigInteger.valueOf(10_000_000L + index),
                AttachmentState.UNKNOWN));
        }
        long retainedHeap = Math.max(0, usedHeap() - heapBefore);
        assertThat(workUnit).hasSize(WORK_UNIT_SIZE);
        assertThat(retainedHeap).isLessThan(HEAP_BUDGET_BYTES);

        var beforeLease = states.findByAccountId(mailbox.accountId()).orElseThrow();
        Instant now = Instant.now();
        assertThat(states.acquireLaneLease(
            mailbox.accountId(), Lane.FRESHNESS, "performance-worker",
            now.plusSeconds(120), now, beforeLease.version())).isTrue();
        var leased = states.findByAccountId(mailbox.accountId()).orElseThrow();
        long upsertStarted = System.nanoTime();
        var committed = commits.commitFreshnessUnit(
            new MailboxSyncCommitService.SyncUnit(
                mailbox.accountId(),
                "performance-worker",
                leased.version(),
                workUnit,
                List.of(),
                "GMAIL_HISTORY_ID",
                "2",
                now.plusSeconds(1),
                WORK_UNIT_SIZE,
                (long) rowCount + WORK_UNIT_SIZE,
                null,
                null,
                false));
        Duration upsertTime = elapsed(upsertStarted);
        assertThat(committed.upserted()).isEqualTo(WORK_UNIT_SIZE);
        assertThat(upsertTime).isLessThan(UPSERT_BUDGET);

        String unreadPlan = explainWithIndexPreference("""
            SELECT COUNT(*)
              FROM message_metadata
             WHERE account_id = ?
               AND unread = TRUE
               AND received_at >= ?
            """, mailbox.accountId(), Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")));
        String generationPlan = explainWithIndexPreference("""
            SELECT id
              FROM message_metadata
             WHERE account_id = ?
               AND full_scan_generation IS DISTINCT FROM ?
               AND last_synced_at < ?
            """, mailbox.accountId(), java.util.UUID.randomUUID(),
            Timestamp.from(Instant.now().plusSeconds(1)));
        assertThat(unreadPlan)
            .contains("idx_message_metadata_account_unread_received")
            .contains("actual time", "Buffers:");
        assertThat(jdbc.queryForList("""
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = 'public'
               AND tablename = 'message_metadata'
            """, String.class))
            .contains(
                "idx_message_metadata_account_generation_freshness",
                "idx_message_metadata_account_last_synced");
        assertThat(generationPlan)
            .containsAnyOf(
                "idx_message_metadata_account_generation_freshness",
                "idx_message_metadata_account_last_synced")
            .contains("actual time", "Buffers:");

        System.out.printf(
            "MAILBOX_SYNC_PERFORMANCE rows=%d seed_ms=%d first_200_ms=%d "
                + "senders_ms=%d unread_ms=%d activity_ms=%d upserted=%d "
                + "upsert_ms=%d bounded_heap_bytes=%d%n",
            rowCount, seedTime.toMillis(), firstPageTime.toMillis(),
            senderRender.toMillis(), unreadRender.toMillis(), activityRender.toMillis(),
            committed.upserted(), upsertTime.toMillis(), retainedHeap);
        System.out.printf("MAILBOX_SYNC_EXPLAIN unread=%s%n", oneLine(unreadPlan));
        System.out.printf("MAILBOX_SYNC_EXPLAIN generation=%s%n", oneLine(generationPlan));
    }

    private Duration timedRoute(String route, String owner, long accountId)
            throws Exception {
        long started = System.nanoTime();
        mockMvc.perform(get(route)
                .with(authenticatedAs(owner))
                .param("account", Long.toString(accountId)))
            .andExpect(status().isOk());
        return elapsed(started);
    }

    private String explainWithIndexPreference(String sql, Object... parameters) {
        jdbc.execute("SET enable_seqscan TO off");
        try {
            return String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class, parameters));
        } finally {
            jdbc.execute("RESET enable_seqscan");
        }
    }

    private static int configuredRowCount() {
        String configured = System.getenv("TIDYMAIL_PERF_ROWS");
        int rows = configured == null || configured.isBlank()
            ? SyntheticMailboxGenerator.DEFAULT_ROW_COUNT
            : Integer.parseInt(configured);
        if (rows < SyntheticMailboxGenerator.DEFAULT_ROW_COUNT || rows > 100_000) {
            throw new IllegalArgumentException(
                "TIDYMAIL_PERF_ROWS must be between 10000 and 100000");
        }
        return rows;
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
