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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("performance")
class MailboxSyncPerformanceTest extends PostgresJourneyTest {
    private static final int WORK_UNIT_SIZE = 500;
    private static final Duration ROUTE_BUDGET = Duration.ofMinutes(1);
    private static final Duration UPSERT_BUDGET = Duration.ofMinutes(1);

    @Autowired MailboxSyncStateRepository states;
    @Autowired MailboxSyncCommitService commits;

    @Test
    void mailboxSyncUsesBoundedWorkAtRealisticVolume() throws Exception {
        int rowCount = configuredRowCount();
        long seedStarted = System.nanoTime();
        var mailbox = seedSyntheticMailbox(rowCount);
        Duration seedTime = elapsed(seedStarted);

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

        String owner = "owner-" + mailbox.seed() + "@mailbox.test";
        Duration dashboardRender = timedRoute(
            "/dashboard", owner, mailbox.accountId());
        Duration syncRender = timedRoute(
            "/activity/sync", owner, mailbox.accountId());
        Duration activityRender = timedRoute(
            "/activity", owner, mailbox.accountId());
        assertThat(List.of(dashboardRender, syncRender, activityRender))
            .allMatch(duration -> duration.compareTo(ROUTE_BUDGET) < 0);

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
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM message_metadata WHERE account_id=?", Long.class, mailbox.accountId()))
            .isEqualTo((long) rowCount + WORK_UNIT_SIZE);
        assertThat(states.findByAccountId(mailbox.accountId()).orElseThrow().incrementalCursorValue())
            .isEqualTo("2");

        System.out.printf(
            "MAILBOX_SYNC_PERFORMANCE rows=%d seed_ms=%d "
                + "dashboard_ms=%d sync_status_ms=%d activity_ms=%d upserted=%d "
                + "upsert_ms=%d%n",
            rowCount, seedTime.toMillis(),
            dashboardRender.toMillis(), syncRender.toMillis(), activityRender.toMillis(),
            committed.upserted(), upsertTime.toMillis());
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

}
