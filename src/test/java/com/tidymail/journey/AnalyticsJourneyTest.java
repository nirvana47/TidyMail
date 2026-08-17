package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.support.PostgresJourneyTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AnalyticsJourneyTest extends PostgresJourneyTest {

    private static final String OWNER = "owner-182026@mailbox.test";
    private static final Instant WINDOW = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void userOpensAnalyticsAndSeesHonestCounts() throws Exception {
        var mailbox = seedSyntheticMailbox();
        jdbc.update("""
            UPDATE message_metadata
               SET received_at = ?,
                   from_address = 'analytics-top@journey.test',
                   from_display_name = 'Analytics Top Sender',
                   from_domain = 'journey.test',
                   size_estimate = 4096
             WHERE id IN (
                 SELECT id
                   FROM message_metadata
                  WHERE account_id = ?
                  ORDER BY id
                  LIMIT 120
             )
            """,
            Timestamp.from(WINDOW),
            mailbox.accountId());
        jdbc.update("""
            INSERT INTO sync_progress(
                account_id, job_type, status, started_at, finished_at,
                last_successful_at, messages_scanned
            ) VALUES (?, 'INCREMENTAL_SYNC', 'COMPLETE', ?, ?, ?, 10000)
            """,
            mailbox.accountId(),
            Timestamp.from(WINDOW.minusSeconds(60)),
            Timestamp.from(WINDOW.plusSeconds(60)),
            Timestamp.from(WINDOW.plusSeconds(60)));

        var result = mockMvc.perform(get("/analytics")
                .with(authenticatedAs(OWNER))
                .param("from", "2026-08-14")
                .param("to", "2026-08-14")
                .param("account", Long.toString(mailbox.accountId()))
                .param("action", "all")
                .param("group", "day")
                .param("compare", "none"))
            .andExpect(status().isOk())
            .andReturn();
        String visible = visibleText(result.getResponse().getContentAsString());
        assertThat(visible).contains(
            "Analytics", "Loaded 10000 messages", "analytics-top@journey.test",
            "Received", "Read", "TidyMail actions");
        assertThat(visible).doesNotContainIgnoringCase(
            "export", "csv", "snapshot", "download");

        AnalyticsPageModel page = (AnalyticsPageModel) result
            .getModelAndView().getModel().get("page");
        assertThat(page.mailbox().total("RECEIVED")).isEqualTo(113);
        assertThat(page.mailbox().total("SENT")).isEqualTo(7);
        assertThat(page.mailbox().topReceivedEmails().getFirst().label())
            .isEqualTo("analytics-top@journey.test");
        assertThat(page.mailbox().topReceivedEmails().getFirst().count()).isEqualTo(113);
        assertThat(page.dashboard().reviewCoverage().numerator()).isZero();
        assertThat(page.dashboard().reviewCoverage().denominator()).isZero();
        assertThat(page.dashboard().cleanupCoverage().numerator()).isZero();
        assertThat(page.dashboard().cleanupCoverage().denominator()).isEqualTo(120);
        assertThat(page.dashboard().unsubscribeCompletion().numerator()).isZero();
        assertThat(page.dashboard().unsubscribeCompletion().denominator()).isZero();

        BigDecimal median = jdbc.queryForObject("""
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY size_estimate)
              FROM message_metadata
             WHERE account_id = ?
               AND received_at >= TIMESTAMPTZ '2026-08-14T00:00:00Z'
               AND received_at < TIMESTAMPTZ '2026-08-15T00:00:00Z'
            """, BigDecimal.class, mailbox.accountId());
        assertThat(median).isEqualByComparingTo("4096");
    }

    @Test
    void missingInboxUnreadSizeRendersUnavailable() throws Exception {
        long accountId = userAccounts.createAccount(OWNER);
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, initial_batch_completed_at
            ) VALUES (?, 'google', 'ACTIVE', ?)
            """, accountId, Timestamp.from(WINDOW));
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_thread_id,
                from_address, from_display_name, from_domain, subject_header,
                received_at, unread, labels, size_estimate
            ) VALUES (
                ?, 'google', 'missing-size', 'missing-size-thread',
                'size@journey.test', 'Missing Size', 'journey.test',
                'Missing size metadata', ?, true, ARRAY['INBOX','UNREAD'], NULL
            )
            """, accountId, Timestamp.from(WINDOW));
        jdbc.update("""
            INSERT INTO sync_progress(
                account_id, job_type, status, started_at, finished_at,
                last_successful_at, messages_scanned
            ) VALUES (?, 'INCREMENTAL_SYNC', 'COMPLETE', ?, ?, ?, 1)
            """, accountId, Timestamp.from(WINDOW.minusSeconds(60)),
            Timestamp.from(WINDOW), Timestamp.from(WINDOW));

        var result = mockMvc.perform(get("/analytics")
                .with(authenticatedAs(OWNER))
                .param("from", "2026-08-14")
                .param("to", "2026-08-14")
                .param("account", Long.toString(accountId))
                .param("action", "all")
                .param("group", "day")
                .param("compare", "none"))
            .andExpect(status().isOk())
            .andReturn();
        AnalyticsPageModel page = (AnalyticsPageModel) result
            .getModelAndView().getModel().get("page");
        String visible = visibleText(result.getResponse().getContentAsString());

        assertThat(page.unreadScopeCounts().inboxUnreadCount()).isEqualTo(1);
        assertThat(page.unreadScopeCounts().allMailUnreadCount()).isEqualTo(1);
        assertThat(page.mailbox().sizes().available()).isFalse();
        assertThat(visible).contains("Inbox unread size", "Unavailable");
        assertThat(visible).doesNotContain("Inbox unread size 0");
    }

    private static String visibleText(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
