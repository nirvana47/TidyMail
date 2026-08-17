package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.SenderPageModel;
import com.tidymail.support.PostgresJourneyTest;
import org.junit.jupiter.api.Test;

class SenderTriageJourneyTest extends PostgresJourneyTest {

    private static final String OWNER = "owner@example.test";

    @Test
    void userConnectsGmailAndSeesTheirNoisiestSenders() throws Exception {
        long accountId = userAccounts.createAccount(OWNER);
        seedMessages(accountId, "noisy@example.test", "Noisiest Sender", 5);
        seedMessages(accountId, "medium@example.test", "Medium Sender", 3);
        seedMessages(accountId, "quiet@example.test", "Quiet Sender", 1);

        String body = mockMvc.perform(get("/senders")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(OWNER)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String visibleText = body.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ");
        assertThat(visibleText)
            .contains("Noisiest Sender", "noisy@example.test", "Medium Sender",
                "medium@example.test", "Quiet Sender", "quiet@example.test");

        int noisy = visibleText.indexOf("Noisiest Sender");
        int medium = visibleText.indexOf("Medium Sender");
        int quiet = visibleText.indexOf("Quiet Sender");
        assertThat(noisy).isGreaterThanOrEqualTo(0);
        assertThat(medium).isGreaterThan(noisy);
        assertThat(quiet).isGreaterThan(medium);
        assertThat(visibleText.substring(noisy, medium)).contains("5");
        assertThat(visibleText.substring(medium, quiet)).contains("3");
        assertThat(visibleText.substring(quiet)).contains("1");

        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void multiLabelUnreadMessageCountsOncePerScope() throws Exception {
        long accountId = userAccounts.createAccount(OWNER);
        long foreignAccountId = userAccounts.createAccount("foreign@example.test");
        jdbc.update("""
            INSERT INTO mailbox_sync_state(
                account_id, provider, lifecycle, initial_batch_completed_at
            ) VALUES (?, 'google', 'ACTIVE', CURRENT_TIMESTAMP)
            """, accountId);
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_thread_id,
                from_address, from_display_name, from_domain, received_at,
                unread, labels
            ) VALUES (
                ?, 'google', 'multi-label-unread', 'multi-label-thread',
                'labels@example.test', 'Multi Label Sender', 'example.test',
                CURRENT_TIMESTAMP, true,
                ARRAY['INBOX','UNREAD','IMPORTANT','CATEGORY_UPDATES']
            )
            """, accountId);
        seedMessages(foreignAccountId, "foreign@example.test", "Foreign Sender", 4);

        var result = mockMvc.perform(get("/senders")
                .param("account", Long.toString(accountId))
                .with(authenticatedAs(OWNER)))
            .andExpect(status().isOk())
            .andReturn();
        SenderPageModel page =
            (SenderPageModel) result.getModelAndView().getModel().get("page");
        String visibleText = result.getResponse().getContentAsString()
            .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ");

        assertThat(page.unreadScopeCounts().available()).isTrue();
        assertThat(page.unreadScopeCounts().inboxUnreadCount()).isEqualTo(1);
        assertThat(page.unreadScopeCounts().allMailUnreadCount()).isEqualTo(1);
        assertThat(visibleText).contains("All Mail unread 1", "Multi Label Sender");
        assertThat(visibleText).doesNotContain("Foreign Sender");
    }

    private void seedMessages(long accountId, String sender, String displayName, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("""
                INSERT INTO message_metadata(
                    account_id,
                    provider,
                    provider_message_id,
                    provider_thread_id,
                    from_address,
                    from_display_name,
                    from_domain,
                    received_at,
                    unread,
                    labels
                )
                VALUES (?, 'google', ?, ?, ?, ?, 'example.test',
                    CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'), true, ARRAY['INBOX'])
                """,
                accountId,
                sender + "-message-" + index,
                sender + "-thread-" + index,
                sender,
                displayName,
                index);
        }
    }
}
