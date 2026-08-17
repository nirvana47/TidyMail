package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionFingerprints;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.ExactScopeExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    @Autowired UserActionRepository actions;
    @Autowired ActionScopeRepository scopes;
    @Autowired ActionIntentRepository actionIntents;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ActionScopeService scopeService;
    @Autowired ExactScopeExecutor executor;

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
                "Undo will target only the ${row.undoAppliedCount} messages recorded as applied.",
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
        UserAction archive = create(
            account, "ARCHIVE", "reason@example.com", List.of("R1", "R2"), "QUEUED");
        when(gmail.batchModify(any(), any())).thenReturn(
            BulkActionResult.withPartialFailures(
                2, List.of("R1"), Map.of("R2", "provider rejection")));
        executor.execute(account.getId(), archive.getPublicId(), UUID.randomUUID());

        UserAction unsubscribe = create(
            account, "UNSUBSCRIBE", "linked-list@example.com",
            List.of("linked-source"), "VERIFICATION_PENDING");
        long attemptId = insertUnsubscribeAttempt(account.getId());
        insertLinkedExecution(unsubscribe, attemptId);

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
                "Undo available",
                "Undo 1 applied",
                "Details",
                "Hide details",
                "Undo will target only the 1 messages recorded as applied.",
                "1 targets: Provider rejected the request",
                "Append-only action timeline",
                "Linked unsubscribe outcome: Verification pending",
                "Open provider options",
                "opens in a new tab")
            .doesNotContain(
                "<th scope=\"col\">Account</th>",
                "<th scope=\"col\">Mode</th>",
                "linked-source",
                "provider_message_id",
                "scopeHash",
                "Export CSV");
        verify(gmail).batchModify(any(), any());
        verifyNoInteractions(outlook);
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

    private void insertLinkedExecution(UserAction action, long attemptId) {
        jdbc.update("""
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,reserved_at,
                started_at,completed_at
            ) VALUES (?,?,'FORWARD','VERIFICATION_PENDING',?,1,1,0,0,1,0,
                      CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, action.getAccountId(), action.getId(),
            ActionFingerprints.sha256("linked-execution"));
        long executionId = jdbc.queryForObject(
            "SELECT MAX(id) FROM action_executions WHERE action_id=?",
            Long.class, action.getId());
        jdbc.update("""
            INSERT INTO action_execution_targets(
                execution_id,account_id,scope_id,scope_ordinal,target_fingerprint,
                dispatch_state,status,accepted_at,dispatched_at,completed_at,
                reason_code,unsubscribe_attempt_id
            )
            SELECT ?,account_id,scope_id,scope_ordinal,target_fingerprint,
                   'DISPATCHED','VERIFICATION_PENDING',CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                   'RESULT_UNKNOWN_AFTER_DISPATCH',?
              FROM action_scope_targets
             WHERE scope_id=? AND scope_ordinal=0
            """, executionId, attemptId, action.getActionScopeId());
    }

    private UserAction create(
            UserAccount account,
            String actionType,
            String label,
            List<String> providerIds,
            String status) {
        providerIds.forEach(id -> {
            if (metadata.findByAccountIdAndProviderMessageIdIn(
                    account.getId(), List.of(id)).isEmpty()) {
                MessageMetadata row = new MessageMetadata();
                row.setAccountId(account.getId());
                row.setProvider("google");
                row.setProviderMessageId(id);
                row.setProviderThreadId("thread-" + id);
                row.setFromAddress(label.toLowerCase().replace(' ', '-') + "@example.com");
                row.setFromDomain("example.com");
                row.setLabels(List.of());
                row.setReceivedAt(Instant.now());
                metadata.saveAndFlush(row);
            }
        });
        var preview = scopeService.createOrGet(
            account.getId(), actionType,
            "UNSUBSCRIBE".equals(actionType) ? "UNSUBSCRIBE_CANDIDATE" : "UNREAD_SELECTION",
            providerIds,
            new ActionScopeTargetSummary(label, label + "@example.com", null, providerIds.size()),
            new ActionScopeRiskSummary(0, 0, List.of()));
        ActionScope scope = scopeService.requireScope(account.getId(), preview.scopeId());
        UserAction action = new UserAction();
        action.setAccountId(account.getId());
        action.setActionScopeId(scope.getId());
        action.setActionType(actionType);
        action.setMessageIds(scope.getMessageIds());
        action.setInverseActionData(new HashMap<>());
        action.setTargetKind(scope.getTargetKind());
        action.setTargetIdJson(Map.of());
        action.setAffectedCount(scope.getMessageCount());
        action.setStatus(status);
        action.setExecutionMode("STAGED");
        action.setDecisionSource("REVIEW");
        action.setExpiresAt(null);
        action = actions.saveAndFlush(action);
        ActionIntent intent = "UNSUBSCRIBE".equals(actionType)
            ? ActionIntent.unsubscribe(
                action.getId(), account.getId(), label, label,
                ActionFingerprints.sha256("candidate-" + label),
                ActionFingerprints.sha256("destination-" + label),
                "RFC8058_POST", Instant.now())
            : ActionIntent.messages(
                action.getId(), account.getId(), label, label, Instant.now());
        actionIntents.saveAndFlush(intent);
        return action;
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
