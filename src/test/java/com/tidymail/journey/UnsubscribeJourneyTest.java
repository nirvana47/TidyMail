package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.support.PostgresJourneyTest;
import com.tidymail.unsubscribe.UnsubscribeIntentService;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UnsubscribeJourneyTest extends PostgresJourneyTest {

    private static final String OWNER = "owner-182026@mailbox.test";

    @Autowired
    private UnsubscribeCandidateService candidates;

    @Autowired
    private UnsubscribeIntentService intents;

    @Test
    void userFindsBulkUnsubscribeCandidatesAndUnsubscribes() throws Exception {
        var mailbox = seedSyntheticMailbox();
        String sender = jdbc.queryForObject("""
            SELECT from_address
              FROM message_metadata
             WHERE account_id = ?
               AND provider_message_id = 'synthetic-182026-9999'
            """, String.class, mailbox.accountId());
        jdbc.update("""
            UPDATE message_metadata
               SET list_id_values = ARRAY['journey-list.journey.test'],
                   list_unsubscribe_values =
                       ARRAY['<mailto:leave@journey.test?subject=PRIVATE_SUBJECT>'],
                   list_unsubscribe_post_values = ARRAY[]::text[],
                   unsubscribe_dkim_pass = false,
                   unsubscribe_dkim_covers_list_unsubscribe = false,
                   unsubscribe_dkim_covers_list_unsubscribe_post = false,
                   unsubscribe_auth_reason = NULL,
                   received_at = CURRENT_TIMESTAMP
             WHERE account_id = ? AND lower(from_address) = lower(?)
            """, mailbox.accountId(), sender);

        UnsubscribeQuery query = UnsubscribeQuery.parse(Map.of(
            "q", List.of(sender),
            "account", List.of(Long.toString(mailbox.accountId()))));
        var candidate = candidates.query(List.of(mailbox.accountId()), query).rows().getFirst();

        String pageText = visibleText(mockMvc.perform(get("/unsubscribe")
                .with(authenticatedAs(OWNER))
                .param("account", Long.toString(mailbox.accountId()))
                .param("q", sender))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(pageText).contains("Bulk Unsubscribe", sender);

        String inspectionText = visibleText(mockMvc.perform(
                get("/unsubscribe/candidates/{key}", candidate.candidateKey())
                    .with(authenticatedAs(OWNER))
                    .param("account", Long.toString(mailbox.accountId())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(inspectionText)
            .contains("Inspect unsubscribe method", "Mailto", "leave@journey.test")
            .doesNotContain("PRIVATE_SUBJECT", "mailto:leave@journey.test");

        String intent = intents.issue(
            List.of(mailbox.accountId()), candidate.candidateKey(), UnsubscribeMethod.MAILTO);
        String outcomeText = visibleText(mockMvc.perform(post("/unsubscribe/manual-complete")
                .with(authenticatedAs(OWNER))
                .with(csrfToken())
                .param("candidateKey", candidate.candidateKey())
                .param("method", "MAILTO")
                .param("intent", intent)
                .param("confirmed", "true"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(outcomeText)
            .contains("Unsubscribe outcome", "Persisted outcome evidence", "Confirmed by you");
        assertThat(jdbc.queryForList("""
            SELECT status
              FROM unsubscribe_outcome_events
             ORDER BY occurred_at, id
            """, String.class)).containsExactly(
                "MANUAL_REQUIRED", "VERIFICATION_PENDING", "CONFIRMED");

        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source)
            VALUES (?, ?, 'USER')
            """, mailbox.accountId(), sender);
        String protectedText = visibleText(mockMvc.perform(
                get("/unsubscribe/candidates/{key}", candidate.candidateKey())
                    .with(authenticatedAs(OWNER))
                    .param("account", Long.toString(mailbox.accountId()))
                    .param("protected", "include"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(protectedText).contains("This sender is protected", "Unavailable");
    }

    private static String visibleText(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
