package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.ApprovalConfirmation;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.UndoConfirmation;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.service.DecisionRowKey;
import com.tidymail.support.PostgresJourneyTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BulkActionJourneyTest extends PostgresJourneyTest {

    private static final String OWNER = "owner-182026@mailbox.test";

    @Test
    void userBulkArchivesAndUndoesExactly() throws Exception {
        var mailbox = seedSyntheticMailbox();
        long eligible = message(
            mailbox.accountId(), "journey-archive-eligible", "eligible@journey.test");
        long protectedMessage = message(
            mailbox.accountId(), "journey-archive-protected", "protected@journey.test");
        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source)
            VALUES (?, 'protected@journey.test', 'USER')
            """, mailbox.accountId());

        var previewResult = mockMvc.perform(post("/api/selection-preview")
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .param("surface", "UNREAD")
                .param("mode", "INDIVIDUAL")
                .param("action", "ARCHIVE")
                .param("account", Long.toString(mailbox.accountId()))
                .param("rowKey",
                    DecisionRowKey.unread(mailbox.accountId(), eligible),
                    DecisionRowKey.unread(mailbox.accountId(), protectedMessage)))
            .andExpect(status().isOk())
            .andReturn();
        SelectionScopePreview preview = (SelectionScopePreview) previewResult
            .getModelAndView().getModel().get("preview");
        assertThat(preview.messageCount()).isEqualTo(1);
        assertThat(preview.excludedProtectedMessageCount()).isEqualTo(1);

        String stagedText = visibleText(mockMvc.perform(post("/api/action-commands")
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .header("HX-Request", "true")
                .param("confirmation", preview.confirmationIntent())
                .param("executionMode", "STAGED"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(stagedText).contains("Exact scope staged for Review", "Nothing changed");

        UUID actionId = UUID.fromString(jdbc.queryForObject(
            "SELECT public_id::text FROM user_actions ORDER BY id DESC LIMIT 1",
            String.class));
        String originalScopeHash = jdbc.queryForObject("""
            SELECT s.scope_hash
              FROM action_scopes s
              JOIN user_actions a ON a.action_scope_id = s.id
             WHERE a.public_id = ?
            """, String.class, actionId);
        assertThat(jdbc.queryForList("""
            SELECT unnest(s.message_ids)
              FROM action_scopes s
              JOIN user_actions a ON a.action_scope_id = s.id
             WHERE a.public_id = ?
            """, String.class, actionId))
            .containsExactly("journey-archive-eligible");

        when(gmailProvider.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });
        var confirmationResult = mockMvc.perform(
                post("/review/actions/{id}/execute/confirm", actionId)
                    .with(authenticatedAs(OWNER))
                    .with(csrfToken())
                    .param("account", Long.toString(mailbox.accountId())))
            .andExpect(status().isOk())
            .andReturn();
        ApprovalConfirmation confirmation = (ApprovalConfirmation) confirmationResult
            .getModelAndView().getModel().get("confirmation");

        String executionText = visibleText(mockMvc.perform(post("/review/approve-selected")
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .header("HX-Request", "true")
                .param("account", Long.toString(mailbox.accountId()))
                .param("selectedAction", actionId.toString())
                .param("operationIntent", confirmation.intent()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(executionText).contains("1 requested", "1 succeeded");

        when(gmailProvider.applyLabel(any(), any(), any(), any(Boolean.class)))
            .thenReturn(BulkActionResult.ok(1, List.of("journey-archive-eligible")));
        var undoConfirmationResult = mockMvc.perform(
                post("/review/actions/{id}/undo/confirm", actionId)
                    .with(authenticatedAs(OWNER))
                    .with(csrfToken())
                    .param("account", Long.toString(mailbox.accountId())))
            .andExpect(status().isOk())
            .andReturn();
        UndoConfirmation undo = (UndoConfirmation) undoConfirmationResult
            .getModelAndView().getModel().get("undoConfirmation");
        assertThat(undo.appliedCount()).isEqualTo(1);

        String undoText = visibleText(mockMvc.perform(post("/api/actions/{id}/undo", actionId)
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .param("account", Long.toString(mailbox.accountId()))
                .param("undoIntent", undo.intent()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(undoText).contains("Undo outcome", "Undo reverted 1");

        assertThat(jdbc.queryForObject("""
            SELECT s.scope_hash
              FROM action_scopes s
              JOIN user_actions a ON a.action_scope_id = s.id
             WHERE a.public_id = ?
            """, String.class, actionId)).isEqualTo(originalScopeHash);
        ArgumentCaptor<List<String>> undoIds = ArgumentCaptor.forClass(List.class);
        verify(gmailProvider).applyLabel(
            any(), undoIds.capture(), any(), any(Boolean.class));
        assertThat(undoIds.getValue()).containsExactly("journey-archive-eligible");
    }

    private long message(long accountId, String providerId, String sender) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_thread_id,
                from_address, from_display_name, from_domain, subject_header,
                received_at, unread, labels
            ) VALUES (
                ?, 'google', ?, ?, ?, 'Journey Sender', 'journey.test',
                'Journey archive message', CURRENT_TIMESTAMP, true,
                ARRAY['INBOX','UNREAD']
            )
            RETURNING id
            """, Long.class, accountId, providerId, providerId + "-thread", sender);
    }

    private static String visibleText(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
