package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.support.LegacyHistoryFixtures;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.UserAccountRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
class Phase17ReviewHistoryTest {
    private static final String EMAIL = "history-owner@example.com";
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

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
    void sourceKeepsHistoryCompactResponsiveAndAuditDetailsReachable()
            throws Exception {
        String history = Files.readString(
            TEMPLATES.resolve("fragments/review-history-frag.html"));
        String outcome = Files.readString(
            TEMPLATES.resolve("fragments/action-outcome-frag.html"));
        String css = Files.readString(
            Path.of("src/main/resources/static/css/workspace.css"));

        assertThat(history)
            .contains(
                "No completed actions yet.",
                "<th scope=\"col\">Action</th>",
                "<th scope=\"col\">Counts</th>",
                "<th scope=\"col\">Details</th>",
                "history-compact-row",
                "row.primaryTimeLabel",
                "row.countLabel",
                "row.availabilityLabel",
                "history-row-details",
                "history-details-open\">Details",
                "history-details-closed\">Hide details",
                "outcomeCompact(${row.outcome})",
                "outcomeDetails(${row.outcome})",
                "Linked unsubscribe outcome: ${row.linkedUnsubscribeOutcome}",
                "Open provider options",
                "#action-${row.compensatesActionPublicId}")
            .doesNotContain(
                "<th scope=\"col\">Account</th>",
                "<th scope=\"col\">Mode</th>",
                "th:text=\"${row.occurredAt}\"",
                "aria-label=\"Action history table; scroll horizontally if needed\"");
        assertThat(outcome)
            .contains(
                "th:fragment=\"outcomeCompact(outcome)\"",
                "th:fragment=\"outcomeDetails(outcome)\"",
                "aria-label=\"Append-only action timeline\"",
                "aria-label=\"Outcome reasons\"");
        assertThat(css)
            .contains(
                ".history-table {",
                "table-layout: fixed",
                ".history-status-chip",
                ".history-followup-chip",
                ".history-row-details summary",
                ".history-details-panel",
                ".history-audit-list",
                "@media (max-width: 720px)",
                ".history-compact-row",
                ".history-table {",
                "min-width: 0",
                "overflow-wrap: anywhere");
    }

    @Test
    void renderedHistoryUsesCompactRowsWhilePreservingUndoReasonsTimelineAndFollowUps()
            throws Exception {
        UserAccount account = account(EMAIL);
        var archive = LegacyHistoryFixtures.action(jdbc, account.getId(), "ARCHIVE",
            "reason@example.com", List.of("R1", "R2"), "PARTIAL");
        LegacyHistoryFixtures.outcome(jdbc, archive, "PARTIAL",
            List.of("SUCCEEDED", "FAILED"), List.of("NONE", "PROVIDER_REJECTED"), null);

        var unsubscribe = LegacyHistoryFixtures.action(jdbc, account.getId(), "UNSUBSCRIBE",
            "linked-list@example.com", List.of("linked-source"), "VERIFICATION_PENDING");
        LegacyHistoryFixtures.outcome(jdbc, unsubscribe, "VERIFICATION_PENDING",
            List.of("VERIFICATION_PENDING"), List.of("RESULT_UNKNOWN_AFTER_DISPATCH"),
            insertUnsubscribeAttempt(account.getId()));

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains(
                "class=\"review-data-table history-table\"",
                "class=\"history-compact-row\"",
                ">Action</th>",
                ">Counts</th>",
                ">Details</th>",
                "requested / ",
                "Details",
                "Hide details",
                "1 targets: Provider rejected the request",
                "Append-only action timeline",
                "Linked unsubscribe outcome: Verification pending",
                "Open provider options",
                "opens in a new tab")
            .doesNotContain(
                "<th scope=\"col\">Account</th>",
                "<th scope=\"col\">Mode</th>",
                "linked-source", "Undo 1 applied", "/undo/confirm",
                "provider_message_id",
                "scopeHash",
                "Export CSV");
        verifyNoInteractions(gmail, outlook);
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
        attributes.put("name", "History Owner");
        attributes.put("sub", "history-owner");
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
