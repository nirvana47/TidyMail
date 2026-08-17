package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.ActionApprovalService;
import com.tidymail.service.ActionConfirmationIntentService;
import com.tidymail.service.ActionScopeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
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
class Phase12MultiAccountTest {
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ActionScopeRepository scopes;
    @Autowired UserActionRepository actions;
    @Autowired ActionExecutionRepository executions;
    @Autowired ActionExecutionTargetRepository targets;
    @Autowired ActionScopeService scopeService;
    @Autowired ActionConfirmationIntentService confirmations;
    @Autowired ActionCommandService commands;
    @Autowired ActionApprovalService approvals;

    @MockBean(name = "gmailMailService")
    MailProvider gmail;
    @MockBean(name = "outlookMailService")
    MailProvider outlook;

    @BeforeEach
    void clean() {
        actions.deleteAll();
        scopes.deleteAll();
        accounts.deleteAll();
        reset(gmail, outlook);
    }

    @Test
    void allAccountCommandCreatesIndependentOrderedActionsExecutionsAndReceipts() {
        UserAccount first = account("first@example.com");
        UserAccount second = account("second@example.com");
        metadata(first, "same-provider-id");
        metadata(second, "same-provider-id");
        var firstScope = scopeService.createOrGet(
            first.getId(), "ARCHIVE", "UNREAD_SELECTION",
            List.of("same-provider-id"),
            new ActionScopeTargetSummary("First account target", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        var secondScope = scopeService.createOrGet(
            second.getId(), "ARCHIVE", "UNREAD_SELECTION",
            List.of("same-provider-id"),
            new ActionScopeTargetSummary("Second account target", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String confirmation = confirmations.issue(
            "owner:google", ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(
                new ActionConfirmationIntentService.ScopeBinding(
                    second.getId(), secondScope.scopeHash()),
                new ActionConfirmationIntentService.ScopeBinding(
                    first.getId(), firstScope.scopeHash())));
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });

        var result = commands.submit(
            "owner:google", List.of(second.getId(), first.getId()),
            confirmation, "IMMEDIATE", true);

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions()).extracting(action -> action.getAccountId())
            .containsExactly(first.getId(), second.getId());
        assertThat(result.actions()).extracting(action -> action.getBatchPublicId())
            .doesNotContainNull()
            .containsOnly(result.actions().getFirst().getBatchPublicId());
        assertThat(result.receipts()).hasSize(2)
            .allSatisfy(receipt -> assertThat(receipt.status()).isEqualTo("APPLIED"));
        assertThat(executions.count()).isEqualTo(2);
        assertThat(targets.count()).isEqualTo(2);
        assertThat(executions.findAll()).allSatisfy(execution -> {
            assertThat(actions.findByIdAndAccountId(
                execution.getActionId(), execution.getAccountId())).isPresent();
        });
    }

    @Test
    void foreignAccountFailsWholeCommandAndPostReservationFailureStaysLocal() {
        UserAccount first = account("one@example.com");
        UserAccount second = account("two@example.com");
        metadata(first, "first-id");
        metadata(second, "second-id");
        var firstScope = scopeService.createOrGet(
            first.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("first-id"),
            new ActionScopeTargetSummary("One", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        var secondScope = scopeService.createOrGet(
            second.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("second-id"),
            new ActionScopeTargetSummary("Two", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        List<ActionConfirmationIntentService.ScopeBinding> bindings = List.of(
            new ActionConfirmationIntentService.ScopeBinding(
                first.getId(), firstScope.scopeHash()),
            new ActionConfirmationIntentService.ScopeBinding(
                second.getId(), secondScope.scopeHash()));
        String rejected = confirmations.issue(
            "owner:google", ActionType.ARCHIVE, DecisionSource.UNREAD, bindings);
        assertThatThrownBy(() -> commands.submit(
            "owner:google", List.of(first.getId()), rejected, "IMMEDIATE", true))
            .hasMessageContaining("action_not_found");
        assertThat(actions.count()).isZero();
        assertThat(executions.count()).isZero();

        String accepted = confirmations.issue(
            "owner:google", ActionType.ARCHIVE, DecisionSource.UNREAD, bindings);
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            UserAccount account = invocation.getArgument(0);
            if (account.getId().equals(second.getId())) {
                throw new IllegalStateException("provider failure canary");
            }
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });
        commands.submit(
            "owner:google", List.of(first.getId(), second.getId()),
            accepted, "IMMEDIATE", true);

        assertThat(actions.findAll())
            .filteredOn(action -> action.getAccountId().equals(first.getId()))
            .singleElement()
            .satisfies(action -> assertThat(action.getStatus()).isEqualTo("APPLIED"));
        assertThat(actions.findAll())
            .filteredOn(action -> action.getAccountId().equals(second.getId()))
            .singleElement()
            .satisfies(action ->
                assertThat(action.getStatus()).isEqualTo("VERIFICATION_PENDING"));
    }

    @Test
    void ACTN_05_reviewSelectedReservesAcrossAccountsThroughOneBatchPath() {
        UserAccount first = account("batch@example.com");
        UserAccount second = account("batch+two@example.com");
        metadata(first, "batch-first");
        metadata(second, "batch-second");
        var firstScope = scopeService.createOrGet(
            first.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("batch-first"),
            new ActionScopeTargetSummary("First batch target", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        var secondScope = scopeService.createOrGet(
            second.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("batch-second"),
            new ActionScopeTargetSummary("Second batch target", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String creation = confirmations.issue(
            "batch-owner", ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(
                new ActionConfirmationIntentService.ScopeBinding(
                    second.getId(), secondScope.scopeHash()),
                new ActionConfirmationIntentService.ScopeBinding(
                    first.getId(), firstScope.scopeHash())));
        var staged = commands.submit(
            "batch-owner", List.of(second.getId(), first.getId()),
            creation, "STAGED", false);
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });
        ReviewQuery query = new ReviewQuery(
            "queued", "all", "all", "all", "", 0);
        var confirmation = approvals.confirmSelected(
            "batch-owner", List.of(first.getId(), second.getId()), query,
            staged.actions().stream().map(
                com.tidymail.entity.UserAction::getPublicId).toList());

        var receipt = approvals.executeSelected(
            "batch-owner", List.of(first.getId(), second.getId()), query,
            confirmation.actionPublicIds(), confirmation.intent());

        assertThat(receipt.actionCount()).isEqualTo(2);
        assertThat(receipt.accountCount()).isEqualTo(2);
        assertThat(receipt.requested()).isEqualTo(2);
        assertThat(actions.findAll()).extracting(
            com.tidymail.entity.UserAction::getStatus).containsOnly("APPLIED");
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

    private void metadata(UserAccount account, String providerId) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setFromAddress("sender@example.com");
        row.setFromDomain("example.com");
        row.setLabels(List.of());
        metadata.saveAndFlush(row);
    }
}
