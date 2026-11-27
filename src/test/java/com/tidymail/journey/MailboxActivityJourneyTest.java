package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.support.PostgresJourneyTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

class MailboxActivityJourneyTest extends PostgresJourneyTest {
    @org.springframework.beans.factory.annotation.Autowired
    com.tidymail.mail.gmail.GmailRateLimiter gmailLimiter;

    @Test
    void syncDetailsShowsLiveBatchRetryWaitAndAccountScopedProgress() throws Exception {
        String email = "batch-feedback@example.test";
        long account = userAccounts.createAccount(email);
        long other = userAccounts.createAccount("other-batch-feedback@example.test");
        gmailLimiter.beginSyncRead(account,500);
        gmailLimiter.updateSyncRead(account,75,true);
        gmailLimiter.beginSyncRead(other,200);
        gmailLimiter.updateSyncRead(other,25,false);
        gmailLimiter.recordSyncThrottle(account,null);
        try {
            String html = mockMvc.perform(get("/activity/status").param("account",Long.toString(account))
                    .with(authenticatedAs(email))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("75 of 500 messages read", "Waiting for Gmail", "retries automatically",
                "Last refreshed", "checks every 5 seconds").doesNotContain("25 of 200");
        } finally {
            gmailLimiter.finishSyncRead(account);
            gmailLimiter.finishSyncRead(other);
            // Fixture cleanup reuses database account IDs between journeys.
            ((java.util.Map<?,?>) org.springframework.test.util.ReflectionTestUtils.getField(gmailLimiter,"budgets")).remove(account);
        }
        assertThat(gmailLimiter.readProgress().snapshot(account)).isNull();
    }

    @org.springframework.boot.test.mock.mockito.MockBean
    com.tidymail.job.IncrementalSyncJob syncJob;


    @Test
    void cleanupDashboardIsAStandalonePageWithAccountScopedEmptyHistory() throws Exception {
        String email = "dashboard-activity@example.test";
        userAccounts.createAccount(email);
        String page = mockMvc.perform(get("/activity").with(authenticatedAs(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("<h1>Activity</h1>", "Total mail cleared", "Top cleared senders",
            "Cleanup history", "No cleanup activity yet", "Page 1 of 1", "Estimated space freed after deletion", "≈ 0 B", "Back to Mailbox",
            "href=\"/activity\" data-activity-link aria-current=\"page\"")
            .doesNotContain("<dialog", "id=\"senderTable\"");
        mockMvc.perform(get("/activity").param("page", "-1").with(authenticatedAs(email)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void activityShowsOnlyTheActiveMailboxDespiteLegacyAccountParameters() throws Exception {
        String email = "activity@example.test";
        long accountId = userAccounts.createAccount(email);
        long foreignId = userAccounts.createAccount("foreign@example.test");
        Instant checkedAt = Instant.now().minusSeconds(90);
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, incremental_cursor_kind,
                incremental_cursor_value, mailbox_checked_at,
                backfill_status, initial_batch_completed_at
            ) VALUES (?, 'google', 'ACTIVE', 'GMAIL_HISTORY_ID', 'hidden-cursor', ?,
                'COMPLETE', ?)
            """, accountId, Timestamp.from(checkedAt), Timestamp.from(checkedAt));

        String page = mockMvc.perform(get("/activity/sync")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(page)
            .contains(
                "<title>TidyMail - Sync details</title>",
                "<h1>Sync details</h1>", "href=\"/activity\">← Back to Activity",
                "aria-current=\"page\"",
                "id=\"activity-status\"",
                "Indexed messages",
                "Last successful check",
                "Current activity",
                "Connection",
                "Checked ",
                "datetime=\"" + checkedAt,
                "role=\"status\"",
                "aria-atomic=\"false\"")
            .doesNotContain(
                "Initial batch",
                "Freshness",
                "Initial import",
                "Provider total",
                "Remaining",
                "Estimated",
                "hidden-cursor",
                "GMAIL_HISTORY_ID",
                "provider_message_id",
                "keep this window open",
                "do not close");

        String fragment = mockMvc.perform(get("/activity/status")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(fragment)
            .contains("id=\"activity-status\"")
            .doesNotContain("<html", "workspace-rail", "<main");

        String ignoredForeignSelection = mockMvc.perform(get("/activity/status")
                .param("account", Long.toString(foreignId))
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(ignoredForeignSelection)
            .contains("datetime=\"" + checkedAt)
            .doesNotContain("foreign@example.test", "hidden-cursor");
    }

    @Test
    @Transactional
    void largeGmailIndexUsesChangingScopedEstimateAndNeverTheOldProfileTotal() throws Exception {
        String email = "large-activity@example.test";
        long account = userAccounts.createAccount(email);
        jdbc.update("""
            INSERT INTO mailbox_sync_state(account_id, provider, initial_batch_completed_at,
                backfill_status, gmail_backfill_phase, provider_total, provider_total_observed_at)
            VALUES (?, 'google', now(), 'RUNNING', 'ALL_MAIL', 63465, now())
            """, account);
        // Seed a large count without queuing 70,029 unrelated classification and grouping updates.
        jdbc.execute("ALTER TABLE message_metadata DISABLE TRIGGER USER");
        jdbc.update("""
            INSERT INTO message_metadata(account_id, provider, provider_message_id, labels)
            SELECT ?, 'google', 'activity-' || n, ARRAY[]::text[]
              FROM generate_series(1, 70029) n
            """, account);
        jdbc.execute("ALTER TABLE message_metadata ENABLE TRIGGER USER");
        jdbc.update("""
            INSERT INTO message_metadata(account_id, provider, provider_message_id, labels)
            VALUES (?, 'google', 'hidden', ARRAY['TRASH'])
            """, account);
        assertThat(activityStatus(email)).contains("Counting messages… 70,029 indexed", "class=\"activity-numeric\">70,029</td>", "<progress max=\"100\"")
            .doesNotContain("63,465", "100%");

        jdbc.update("UPDATE mailbox_sync_state SET gmail_scope_total=74500 WHERE account_id=?", account);
        String first = activityStatus(email);
        assertThat(first).contains("70,029 of ~74,500 indexed (estimated total)", "value=\"" + (100.0 * 70029 / 74500))
            .doesNotContain("63,465", "100%");
        jdbc.update("UPDATE mailbox_sync_state SET gmail_scope_total=75200 WHERE account_id=?", account);
        assertThat(activityStatus(email)).contains("70,029 of ~75,200 indexed (estimated total)",
            "value=\"" + (100.0 * 70029 / 75200)).doesNotContain("~74,500", "100%");
        jdbc.update("UPDATE mailbox_sync_state SET gmail_scope_total=70029 WHERE account_id=?", account);
        assertThat(activityStatus(email)).contains("Counting messages… 70,029 indexed").doesNotContain("100%");
    }

    private String activityStatus(String email) throws Exception {
        return activityStatus(email, "google");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor providerAuthentication(String email, String provider) {
        var base = oauth2Authentication(email);
        var attributes = new java.util.LinkedHashMap<String, Object>(base.getPrincipal().getAttributes());
        attributes.put("sub", "synthetic-" + provider + "-" + email);
        var principal = new org.springframework.security.oauth2.core.user.DefaultOAuth2User(base.getAuthorities(), attributes, "sub");
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
            new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                principal, base.getAuthorities(), provider));
    }

    private String activityStatus(String email, String provider) throws Exception {
        return mockMvc.perform(get("/activity/status").with(providerAuthentication(email, provider)))
            .andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "microsoft"})
    void failedFirstImportShowsRetryAndKeepsPollingUntilRecovery(String provider) throws Exception {
        String email = "failed-import@example.test";
        long account = userAccounts.createAccount(email, provider);
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider) VALUES(?,?)", account, provider);
        jdbc.update("INSERT INTO sync_progress(account_id,job_type,lane,phase,status) VALUES(?,'MAILBOX_SYNC','BACKFILL','FAILED','FAILED')", account);
        assertThat(activityStatus(email, provider)).contains("Sync paused after a recoverable error", "Retry sync",
                "Initial import", "Not completed yet", "Sync interrupted", "every 30s", "value=\"/activity/sync\"")
            .doesNotContain("Historical mail completed", "No message changes recorded");
        mockMvc.perform(post("/api/metadata-sync").param("account", Long.toString(account))
                .param("returnTo", "/activity/sync").with(providerAuthentication(email, provider)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/activity/sync"));
        org.mockito.Mockito.verify(syncJob).requestSync(account);
        jdbc.update("INSERT INTO sync_progress(account_id,job_type,lane,phase,status) VALUES(?,'MAILBOX_SYNC','BACKFILL','PROVIDER','RUNNING')", account);
        assertThat(activityStatus(email, provider)).contains("Preparing your cleanup workspace", "every 5s")
                .doesNotContain("Sync paused after a recoverable error");
        jdbc.update("UPDATE mailbox_sync_state SET lifecycle='REAUTH_REQUIRED' WHERE account_id=?", account);
        assertThat(activityStatus(email, provider)).contains("Reconnect required", "/oauth2/authorization/" + provider)
            .doesNotContain("/oauth2/authorization/" + (provider.equals("google") ? "microsoft" : "google"));
    }

    @Test
    void retrySyncRequiresCsrfAndReturnsToActivity() throws Exception {
        String email = "retry-activity@example.test";
        long accountId = userAccounts.createAccount(email);

        mockMvc.perform(post("/api/metadata-sync")
                .param("account", Long.toString(accountId))
                .param("returnTo", "/activity")
                .with(authenticatedAs(email)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/metadata-sync")
                .param("account", Long.toString(accountId))
                .param("returnTo", "/activity")
                .with(authenticatedAs(email))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/activity"));
    }

    @Test
    void pollingSlowsAtCompletionAndKeepsDetectingNewWorkWithoutExposingCursor() throws Exception {
        String email = "polling-activity@example.test";
        long accountId = userAccounts.createAccount(email);

        String active = mockMvc.perform(get("/activity/status")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(active).contains(
            "id=\"activity-status\"",
            "hx-trigger=\"every 5s\"",
            "hx-target=\"#activity-status\"",
            "hx-swap=\"outerHTML settle:120ms\"",
            "Preparing your cleanup workspace",
            "Sync continues in the background; you can close this page.");

        Instant checkedAt = Instant.now().minusSeconds(30);
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, incremental_cursor_kind,
                incremental_cursor_value, mailbox_checked_at,
                backfill_status, backfill_completed_at, initial_batch_completed_at
            ) VALUES (?, 'google', 'ACTIVE', 'GMAIL_HISTORY_ID', 'server-only', ?,
                'COMPLETE', ?, ?)
            """, accountId, Timestamp.from(checkedAt), Timestamp.from(checkedAt),
            Timestamp.from(checkedAt));
        String terminal = mockMvc.perform(get("/activity/status")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(terminal)
            .contains("Mailbox activity is current", "Checked ", "hx-trigger=\"every 30s\"", "Last refreshed", "data-local-time")
            .doesNotContain("server-only", "GMAIL_HISTORY_ID");

    }
}
