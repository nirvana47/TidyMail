package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.support.LegacyHistoryFixtures;
import com.tidymail.dto.ActionHistoryModel;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.UserAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "tidymail.review.max-actions=100",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET="
})
class Phase12ReviewWebTest {
    private static final String EMAIL = "review-owner@example.com";
    private static final String OWNER = EMAIL + ":google";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository accounts;

    @MockBean(name = "gmailMailService")
    MailProvider gmail;
    @MockBean(name = "outlookMailService")
    MailProvider outlook;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_actions");
        jdbc.update("DELETE FROM action_scopes");
        jdbc.update("DELETE FROM message_metadata");
        jdbc.update("DELETE FROM user_accounts");
        reset(gmail, outlook);
    }

    @Test
    @DisplayName("ACTN-10 History filters legacy truth and provider-free rendering")
    void ACTN_10_historyIsProviderFreeFilteredAndTruthfulForLegacyRows()
            throws Exception {
        UserAccount account = account(EMAIL);
        create(account, "ARCHIVE", "history@example.com",
            List.of("PRIVATE_HISTORY_PROVIDER_ID"), "APPLIED");
        create(account, "TRASH", "other@example.com",
            List.of("OTHER_PRIVATE_ID"), "FAILED");

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .param("action", "archive")
                .param("status", "applied")
                .param("target", "history@example.com")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains(">History</h2>", "class=\"review-data-view\"",
                "class=\"review-filter-form review-filters\"",
                "class=\"review-data-table history-table\"", "history@example.com",
                "Legacy action - per-target outcome detail is unavailable.",
                  "view=history", "value=\"archive\" selected=\"selected\"",
                  "value=\"applied\" selected=\"selected\"")
            .doesNotContain(
                "review-metrics", "History metrics", "Succeeded targets",
                "workspace-state", "Showing partial indexed data", "state-summary", "sync-form",
                "other@example.com", "PRIVATE_HISTORY_PROVIDER_ID",
                "OTHER_PRIVATE_ID", "Export CSV", "<canvas");
        assertThat(body.indexOf("class=\"page-header\"")).isLessThan(
            body.indexOf("id=\"review-history\""));
        assertThat(body.indexOf("class=\"review-filter-form review-filters\"")).isLessThan(
            body.indexOf("class=\"review-data-table-wrap history-table-wrap\""));
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_10_historySumsSafeReasonGroupsAndUsesProviderNeutralLabels()
            throws Exception {
        UserAccount account = account(EMAIL);
        var action = create(
            account, "ARCHIVE", "reason@example.com", List.of("R1", "R2"), "QUEUED");
        LegacyHistoryFixtures.outcome(jdbc, action, "FAILED",
            List.of("FAILED", "FAILED"), List.of("PROVIDER_RESULT_INVALID", "NOT_DISPATCHED"), null);

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("2 targets: Request could not be completed",
                EMAIL + " (Google)", "value=\"review\"",
                "value=\"in_progress\"", "value=\"undone\"")
            .doesNotContain("1 targets: Request could not be completed");
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_10_historyDoesNotAggregateSuccessfulTargetsAsFailureReasons()
            throws Exception {
        UserAccount account = account(EMAIL);
        var action = create(
            account, "ARCHIVE", "partial@example.com", List.of("R1", "R2"), "QUEUED");
        LegacyHistoryFixtures.outcome(jdbc, action, "PARTIAL",
            List.of("SUCCEEDED", "FAILED"), List.of("NONE", "PROVIDER_REJECTED"), null);

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("1 targets: Provider rejected the request")
            .doesNotContain("Request could not be completed");
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_10_everyHistoryFilterCombinationWildcardAndStablePageRoundTrips()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAccount other = account("history-other@example.com");
        Instant tied = Instant.parse("2026-08-09T12:00:00Z");
        for (int index = 0; index < 30; index++) {
            create(account, index % 2 == 0 ? "ARCHIVE" : "TRASH",
                "history-%02d@example.com".formatted(index),
                List.of("history-" + index),
                index % 3 == 0 ? "FAILED" : "APPLIED");
        }
        create(account, "ARCHIVE", "literal%_target@example.com",
            List.of("literal-wildcard"), "APPLIED");
        create(other, "ARCHIVE", "foreign-history@example.com",
            List.of("foreign-history"), "APPLIED");
        jdbc.update("""
            UPDATE user_actions
               SET created_at=?, completed_at=?
             WHERE account_id IN (?,?)
            """, java.sql.Timestamp.from(tied), java.sql.Timestamp.from(tied),
            account.getId(), other.getId());

        var pageZero = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .param("from", "2026-08-09")
                .param("to", "2026-08-09")
                .param("action", "archive")
                .param("status", "applied")
                .param("target", "history")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn();
        ActionHistoryModel first = (ActionHistoryModel) pageZero
            .getModelAndView().getModel().get("history");
        assertThat(first.rows()).isNotEmpty()
            .allSatisfy(row -> {
                assertThat(row.action()).isEqualTo("Archive");
                assertThat(row.status()).isEqualTo("Applied");
                assertThat(row.accountLabel()).contains(EMAIL);
                assertThat(row.targetLabel()).contains("history");
                assertThat(row.occurredAt()).isEqualTo(tied);
            });
        assertThat(first.canonicalUrl()).contains(
            "view=history", "account=" + account.getId(), "from=2026-08-09",
            "to=2026-08-09", "action=archive", "status=applied", "target=history");

        ActionHistoryModel allFirst = historyModel(account, 0);
        ActionHistoryModel allSecond = historyModel(account, 1);
        assertThat(allFirst.rows()).hasSize(25);
        assertThat(allSecond.rows()).hasSize(6);
        assertThat(allFirst.rows()).extracting(row -> row.actionPublicId())
            .doesNotContainAnyElementsOf(
                allSecond.rows().stream().map(row -> row.actionPublicId()).toList());
        assertThat(allFirst.nextUrl()).contains(
            "view=history", "account=" + account.getId(), "page=1");
        assertThat(allSecond.previousUrl()).contains(
            "view=history", "account=" + account.getId());
        assertThat(allFirst.rows().getLast().occurredAt())
            .isEqualTo(allSecond.rows().getFirst().occurredAt());

        String literal = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .param("target", "%_")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(literal).contains("literal%_target@example.com")
            .doesNotContain("history-00@example.com", "foreign-history@example.com");
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_10_historyLinksTheOwnedV8UnsubscribeOutcomeAndCompensationAnchor()
            throws Exception {
        UserAccount account = account(EMAIL);
        var unsubscribe = create(
            account, "UNSUBSCRIBE", "linked-list@example.com",
            List.of("linked-source"), "VERIFICATION_PENDING");
        long attemptId = insertUnsubscribeAttempt(account.getId());
        long executionId = LegacyHistoryFixtures.outcome(jdbc, unsubscribe, "VERIFICATION_PENDING",
            List.of("VERIFICATION_PENDING"), List.of("RESULT_UNKNOWN_AFTER_DISPATCH"), attemptId);
        jdbc.update("""
            UPDATE user_actions SET completed_at=? WHERE id=?
            """, java.sql.Timestamp.from(Instant.parse("2026-08-09T12:00:00Z")),
            unsubscribe.id());

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body)
            .contains("Linked unsubscribe outcome: Verification pending",
                "No automatic undo", "opens in a new tab")
            .doesNotContain("linked-source", "unsubscribe.example/private");
        assertThat(jdbc.queryForObject("SELECT status FROM action_executions WHERE id=?", String.class, executionId))
            .isEqualTo("VERIFICATION_PENDING");
        verifyNoInteractions(gmail, outlook);
    }

    private ActionHistoryModel historyModel(UserAccount account, int page)
            throws Exception {
        var request = get("/review")
            .param("view", "history")
            .param("account", String.valueOf(account.getId()))
            .with(authentication(auth()));
        if (page > 0) request.param("page", String.valueOf(page));
        return (ActionHistoryModel) mvc.perform(request)
            .andExpect(status().isOk()).andReturn()
            .getModelAndView().getModel().get("history");
    }

    private long insertUnsubscribeAttempt(long accountId) {
        jdbc.update("""
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,
                method,latest_status
            ) VALUES (?,?,?,'RFC8058_POST','VERIFICATION_PENDING')
            """, accountId, "a".repeat(64), "b".repeat(64));
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM unsubscribe_attempts WHERE account_id=?",
            Long.class, accountId);
    }

    private LegacyHistoryFixtures.SavedAction create(UserAccount account, String type,
            String label, List<String> ids, String status) {
        return LegacyHistoryFixtures.action(jdbc, account.getId(), type, label, ids, status);
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("provider-" + email);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.saveAndFlush(account);
    }

    private static OAuth2AuthenticationToken auth() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", EMAIL);
        attributes.put("name", "Review Owner");
        attributes.put("sub", "review-owner");
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
