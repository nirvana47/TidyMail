package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.support.PostgresJourneyTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MailboxActivityJourneyTest extends PostgresJourneyTest {

    @Test
    void activityRouteRendersTruthfulAccountScopedStatus() throws Exception {
        String email = "activity@example.test";
        long accountId = userAccounts.createAccount(email);
        long foreignId = userAccounts.createAccount("foreign@example.test");
        Instant checkedAt = Instant.now().minusSeconds(90);
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, incremental_cursor_kind,
                incremental_cursor_value, incremental_last_success_at,
                backfill_status, initial_batch_completed_at
            ) VALUES (?, 'google', 'ACTIVE', 'GMAIL_HISTORY_ID', 'hidden-cursor', ?,
                'COMPLETE', ?)
            """, accountId, Timestamp.from(checkedAt), Timestamp.from(checkedAt));

        String page = mockMvc.perform(get("/activity")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(page)
            .contains(
                "<title>TidyMail - Sync / Activity</title>",
                "Mailbox status",
                "<span>Sync / Activity</span>",
                "aria-current=\"page\"",
                "id=\"activity-status\"",
                "Indexed messages",
                "Initial batch",
                "Freshness",
                "Checked ",
                "datetime=\"" + checkedAt,
                "role=\"status\"",
                "aria-atomic=\"false\"")
            .doesNotContain(
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

        mockMvc.perform(get("/activity/status")
                .param("account", Long.toString(foreignId))
                .with(authenticatedAs(email)))
            .andExpect(status().isNotFound());
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
    void pollingPreservesFactsFocusAndServerAuthority() throws Exception {
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
                incremental_cursor_value, incremental_last_success_at,
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
            .contains("Mailbox activity is current", "Checked ")
            .doesNotContain("hx-trigger=", "server-only", "GMAIL_HISTORY_ID");

        String script = Files.readString(
            Path.of("src/main/resources/static/js/htmx-lite.js"));
        assertThat(script)
            .contains(
                "function parsePollingTrigger(value)",
                "/^every ([1-9][0-9]{0,2})s$/",
                "seconds >= 1 && seconds <= 300",
                "function schedulePolling(element, seconds)",
                "state.inFlight",
                "window.setTimeout",
                "function parseSwapSpec(value)",
                "outerHTML|none",
                "function replaceAfterSettle",
                "pendingResponse",
                "focus({ preventScroll: true })",
                "window.scrollTo(scrollPosition.x, scrollPosition.y)",
                "function recordPollingFailure(element)",
                "state.failures >= 2 ? 30 : state.baseInterval",
                "Activity updates are temporarily unavailable. "
                    + "This does not stop background sync.",
                "removePollingWarning(element)",
                "initializePolling(eventTarget)",
                "disposeDisconnectedPolling()")
            .doesNotContain("setInterval(");
    }
}
