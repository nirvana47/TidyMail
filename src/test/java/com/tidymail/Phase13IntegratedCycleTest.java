package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.ActionConfirmationIntentService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.UndoService;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET="
})
class Phase13IntegratedCycleTest {
    private static final String EMAIL = "cycle-owner@example.com";
    private static final String OWNER = EMAIL + ":google";

    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired SyncProgressRepository progress;
    @Autowired ActionScopeService scopes;
    @Autowired ActionConfirmationIntentService confirmations;
    @Autowired ActionCommandService commands;
    @Autowired UndoService undo;
    @Autowired ActionExecutionRepository executions;
    @Autowired MockMvc mvc;

    @MockBean(name = "gmailMailService") MailProvider gmail;
    @MockBean(name = "outlookMailService") MailProvider outlook;
    @MockBean UnsubscribeHttpTransport unsubscribeTransport;

    @Test
    void mockedCycleConnectsBothModesHistoryAnalyticsAndExactUndo()
            throws Exception {
        UserAccount account = account();
        Instant now = Instant.now();
        message(account, "immediate-message", now.minusSeconds(120));
        message(account, "staged-message", now.minusSeconds(60));
        completeProgress(account, now.plusSeconds(60));
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(
                request.messageIds().size(), request.messageIds());
        });

        var immediateScope = scope(account, "immediate-message", "Immediate message");
        String immediateConfirmation = confirmation(account, immediateScope.scopeHash());
        var immediate = commands.submit(
            OWNER, List.of(account.getId()), immediateConfirmation, "IMMEDIATE", true);
        assertThat(immediate.receipts()).singleElement()
            .satisfies(receipt -> assertThat(receipt.status()).isEqualTo("APPLIED"));

        var stagedScope = scope(account, "staged-message", "Staged message");
        String stagedConfirmation = confirmation(account, stagedScope.scopeHash());
        var staged = commands.submit(
            OWNER, List.of(account.getId()), stagedConfirmation, "STAGED", false);
        assertThat(staged.receipts()).isEmpty();
        var stagedAction = staged.actions().getFirst();
        assertThat(commands.approve(
            account.getId(), stagedAction.getPublicId(), UUID.randomUUID()).status())
            .isEqualTo("APPLIED");

        var undoConfirmation =
            undo.prepare(OWNER, account.getId(), stagedAction.getPublicId());
        assertThat(undo.execute(
            OWNER, account.getId(), stagedAction.getPublicId(),
            undoConfirmation.intent()).status()).isEqualTo("UNDONE");
        assertThat(executions.findByActionIdAndOperationKind(
            stagedAction.getId(), "UNDO")).isPresent();

        String history = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", account.getId().toString())
                .with(authentication(auth())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(history).contains("Mark read", "Undone");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var analyticsResult = mvc.perform(get("/analytics")
                .param("from", today.toString())
                .param("to", today.toString())
                .param("account", account.getId().toString())
                .param("action", "mark_read")
                .param("group", "day")
                .param("compare", "none")
                .with(authentication(auth())))
            .andExpect(status().isOk())
            .andReturn();
        AnalyticsPageModel page = (AnalyticsPageModel) analyticsResult
            .getModelAndView().getModel().get("page");
        assertThat(page.dashboard().outcomes().requested()).isEqualTo(2);
        assertThat(page.dashboard().outcomes().succeeded()).isEqualTo(2);
        assertThat(page.dashboard().metrics().get(
            com.tidymail.dto.AnalyticsMetric.MetricId.MARKED_READ).value())
            .isEqualTo(2);

        verifyNoInteractions(outlook, unsubscribeTransport);
    }

    private UserAccount account() {
        UserAccount account = new UserAccount();
        account.setEmail(EMAIL);
        account.setProvider("google");
        account.setProviderAccountId("provider-cycle");
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.saveAndFlush(account);
    }

    private void message(UserAccount account, String providerId, Instant receivedAt) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId("thread-" + providerId);
        row.setReceivedAt(receivedAt);
        row.setFromDisplayName("Cycle sender");
        row.setFromAddress("cycle@example.com");
        row.setFromDomain("example.com");
        row.setUnread(true);
        metadata.saveAndFlush(row);
    }

    private void completeProgress(
            UserAccount account, Instant completedAt) {
        SyncProgress row = new SyncProgress();
        row.setAccountId(account.getId());
        row.setJobType("INCREMENTAL_SYNC");
        row.setStatus("COMPLETE");
        row.setStartedAt(completedAt.minusSeconds(60));
        row.setFinishedAt(completedAt);
        row.setLastSuccessfulAt(completedAt);
        progress.saveAndFlush(row);
    }

    private com.tidymail.dto.ActionScopePreview scope(
            UserAccount account, String messageId, String label) {
        return scopes.createOrGet(
            account.getId(), "MARK_READ", "UNREAD_SELECTION", List.of(messageId),
            new ActionScopeTargetSummary(label, null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
    }

    private String confirmation(UserAccount account, String scopeHash) {
        return confirmations.issue(
            OWNER, ActionType.MARK_READ, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), scopeHash)));
    }

    private static OAuth2AuthenticationToken auth() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("sub", "cycle-subject");
        attributes.put("email", EMAIL);
        attributes.put("name", "Cycle Owner");
        return new OAuth2AuthenticationToken(
            new DefaultOAuth2User(authorities, attributes, "sub"),
            authorities, "google");
    }
}
