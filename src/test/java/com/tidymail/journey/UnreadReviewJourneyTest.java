package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.UnreadPageModel;
import com.tidymail.service.DecisionRowKey;
import com.tidymail.service.ReviewOperationIntentService;
import com.tidymail.support.PostgresJourneyTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UnreadReviewJourneyTest extends PostgresJourneyTest {

    private static final String OWNER = "owner-182026@mailbox.test";

    @Autowired
    private ReviewOperationIntentService operationIntents;

    @Test
    void userReviewsUnreadAndClearsTheQueue() throws Exception {
        var mailbox = seedSyntheticMailbox();
        long metadataId = jdbc.queryForObject("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_thread_id,
                from_address, from_display_name, from_domain, subject_header,
                received_at, unread, labels
            ) VALUES (
                ?, 'google', 'journey-review-message', 'journey-review-thread',
                'review@journey.test', 'Review Journey', 'journey.test',
                'Journey review subject', CURRENT_TIMESTAMP, true,
                ARRAY['INBOX','UNREAD']
            )
            RETURNING id
            """, Long.class, mailbox.accountId());

        String unreadText = visibleText(mockMvc.perform(get("/unread")
                .with(authenticatedAs(OWNER))
                .param("account", Long.toString(mailbox.accountId()))
                .param("search", "Journey review subject"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(unreadText).contains(
            "Inbox unread", "Direct mail", "1 sender", "1 Inbox unread");

        var previewResult = mockMvc.perform(post("/api/selection-preview")
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .param("surface", "UNREAD")
                .param("mode", "INDIVIDUAL")
                .param("action", "MARK_READ")
                .param("account", Long.toString(mailbox.accountId()))
                .param("rowKey", DecisionRowKey.unread(mailbox.accountId(), metadataId)))
            .andExpect(status().isOk())
            .andReturn();
        SelectionScopePreview preview = (SelectionScopePreview) previewResult
            .getModelAndView().getModel().get("preview");

        mockMvc.perform(post("/api/action-commands")
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .param("confirmation", preview.confirmationIntent())
                .param("executionMode", "STAGED"))
            .andExpect(status().is3xxRedirection());

        UUID actionId = UUID.fromString(jdbc.queryForObject(
            "SELECT public_id::text FROM user_actions ORDER BY id DESC LIMIT 1",
            String.class));
        String queuedText = visibleText(mockMvc.perform(get("/review")
                .with(authenticatedAs(OWNER))
                .param("account", Long.toString(mailbox.accountId())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(queuedText).contains("Queue", "Mark read", "1 email");

        String cancelIntent = operationIntents.issue(
            OWNER + ":google",
            "CANCEL",
            ReviewOperationIntentService.subject(actionId));
        mockMvc.perform(post("/api/actions/{id}/cancel", actionId)
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .param("account", Long.toString(mailbox.accountId()))
                .param("operationIntent", cancelIntent))
            .andExpect(status().is3xxRedirection());

        String emptyText = visibleText(mockMvc.perform(get("/review")
                .with(authenticatedAs(OWNER))
                .param("account", Long.toString(mailbox.accountId())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(emptyText).contains("No actions are waiting for review");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM user_actions WHERE public_id = ?",
            String.class, actionId)).isEqualTo("CANCELLED");
    }

    @Test
    void pendingUnreadScopeDoesNotRenderAsZero() throws Exception {
        long accountId = userAccounts.createAccount(OWNER);

        var pendingResult = mockMvc.perform(get("/unread")
                .with(authenticatedAs(OWNER))
                .param("account", Long.toString(accountId)))
            .andExpect(status().isOk())
            .andReturn();
        UnreadPageModel pendingPage =
            (UnreadPageModel) pendingResult.getModelAndView().getModel().get("page");
        String pendingText = visibleText(pendingResult.getResponse().getContentAsString());

        assertThat(pendingPage.workspace().unreadScopeCounts().available()).isFalse();
        assertThat(pendingPage.workspace().unreadScopeCounts().inboxUnreadCount()).isNull();
        assertThat(pendingText).contains("Inbox unread", "Unavailable");
        assertThat(pendingText).doesNotContain("Inbox unread 0");

        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, initial_batch_completed_at
            ) VALUES (?, 'google', 'ACTIVE', CURRENT_TIMESTAMP)
            """, accountId);

        var emptyResult = mockMvc.perform(get("/unread")
                .with(authenticatedAs(OWNER))
                .param("account", Long.toString(accountId)))
            .andExpect(status().isOk())
            .andReturn();
        UnreadPageModel emptyPage =
            (UnreadPageModel) emptyResult.getModelAndView().getModel().get("page");
        String emptyText = visibleText(emptyResult.getResponse().getContentAsString());

        assertThat(emptyPage.workspace().unreadScopeCounts().available()).isTrue();
        assertThat(emptyPage.workspace().unreadScopeCounts().inboxUnreadCount()).isZero();
        assertThat(emptyPage.workspace().unreadScopeCounts().allMailUnreadCount()).isZero();
        assertThat(emptyText).contains("Inbox unread 0");
    }

    private static String visibleText(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
