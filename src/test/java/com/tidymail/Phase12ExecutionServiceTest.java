package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.dto.ActionScopePreview;
import com.tidymail.dto.ActionScopePresentation;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.ProtectedSenderRepository;
import com.tidymail.repository.UnsubscribeAttemptRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.ActionConfirmationIntentService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.ExactScopeExecutor;
import com.tidymail.service.ExecutionReservationService;
import com.tidymail.service.ProtectionHandler;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.unsubscribe.DestinationResolver;
import com.tidymail.unsubscribe.ResolvedDestination;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
class Phase12ExecutionServiceTest {
    @Autowired UserAccountRepository accounts;
    @Autowired ActionScopeRepository scopes;
    @Autowired UserActionRepository actions;
    @Autowired ActionExecutionRepository executions;
    @Autowired ActionExecutionTargetRepository executionTargets;
    @Autowired ActionEventRepository events;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ProtectedSenderRepository protectedSenders;
    @Autowired UnsubscribeAttemptRepository unsubscribeAttempts;
    @Autowired UnsubscribeCandidateService unsubscribeCandidates;
    @Autowired ActionScopeService scopeService;
    @Autowired ActionConfirmationIntentService confirmations;
    @Autowired ActionCommandService commands;
    @Autowired ExactScopeExecutor executor;
    @Autowired ExecutionReservationService reservations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @SpyBean ProtectionHandler protectionHandler;

    @MockBean(name = "gmailMailService")
    MailProvider gmail;
    @MockBean(name = "outlookMailService")
    MailProvider outlook;
    @MockBean DestinationResolver destinationResolver;
    @MockBean UnsubscribeHttpTransport unsubscribeTransport;

    @BeforeEach
    void clean() {
        actions.deleteAll();
        scopes.deleteAll();
        accounts.deleteAll();
        reset(gmail, outlook, destinationResolver, unsubscribeTransport, protectionHandler);
    }

    @Test
    @DisplayName("ACTN-01 staged and immediate archive use one exact executor and ledger")
    void ACTN_01_stagedAndImmediateArchiveUseOneExactExecutorAndLedger() {
        UserAccount account = account("tracer@example.com");
        metadata(account, List.of("message-c", "message-a", "message-b"));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "SENDER_SELECTION",
            List.of("message-c", "message-a", "message-b"),
            new ActionScopeTargetSummary("3 selected senders", null, null, 3),
            new ActionScopeRiskSummary(0, 0, List.of()));
        ActionScope scope = scopes.findById(preview.scopeId()).orElseThrow();
        String owner = "tracer@example.com:google";
        var binding = new ActionConfirmationIntentService.ScopeBinding(
            account.getId(), scope.getScopeHash());
        AtomicBoolean providerSawTransaction = new AtomicBoolean(true);
        AtomicBoolean reservationVisible = new AtomicBoolean(false);
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            providerSawTransaction.set(
                TransactionSynchronizationManager.isActualTransactionActive());
            reservationVisible.set(
                executions.count() == 1
                    && executionTargets.count() == scope.getMessageCount()
                    && events.findAll().stream().anyMatch(
                        event -> event.getEventType().equals("EXECUTION_STARTED")));
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });

        String stagedIntent = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.SENDERS, List.of(binding));
        var staged = commands.submit(
            owner, List.of(account.getId()), stagedIntent, null, false);
        assertThat(staged.mode().name()).isEqualTo("STAGED");
        assertThat(staged.receipts()).isEmpty();
        assertThat(actions.findAll()).singleElement().satisfies(action -> {
            assertThat(action.getActionScopeId()).isEqualTo(scope.getId());
            assertThat(action.getMessageIds()).isEqualTo(scope.getMessageIds());
            assertThat(action.getStatus()).isEqualTo("QUEUED");
            assertThat(action.getExecutionMode()).isEqualTo("STAGED");
        });
        verify(gmail, never()).batchModify(any(), any());

        UserAction queued = actions.findAll().getFirst();
        ExactScopeExecutor.ExecutionReceipt approved = commands.approve(
            account.getId(), queued.getPublicId(), UUID.randomUUID());
        assertThat(approved.status()).isEqualTo("APPLIED");
        assertThat(approved.requested()).isEqualTo(3);
        assertThat(providerSawTransaction).isFalse();
        assertThat(reservationVisible).isTrue();

        String immediateIntent = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.SENDERS, List.of(binding));
        var immediate = commands.submit(
            owner, List.of(account.getId()), immediateIntent, "IMMEDIATE", true);
        assertThat(immediate.mode().name()).isEqualTo("IMMEDIATE");
        assertThat(immediate.receipts()).singleElement()
            .satisfies(receipt -> {
                assertThat(receipt.status()).isEqualTo("APPLIED");
                assertThat(receipt.requested()).isEqualTo(3);
            });
        assertThat(actions.findAll()).hasSize(2)
            .allSatisfy(action -> {
                assertThat(action.getActionScopeId()).isEqualTo(scope.getId());
                assertThat(action.getMessageIds()).isEqualTo(scope.getMessageIds());
            });
        assertThat(actions.findAll()).extracting(UserAction::getExecutionMode)
            .containsExactlyInAnyOrder("STAGED", "IMMEDIATE");
        assertThat(executions.count()).isEqualTo(2);
        assertThat(executionTargets.count()).isEqualTo(6);
        assertThat(events.findAll()).extracting(event -> event.getEventType())
            .contains("ACTION_QUEUED", "EXECUTION_RESERVED",
                "EXECUTION_STARTED", "EXECUTION_COMPLETED");
        verify(gmail, org.mockito.Mockito.times(2)).batchModify(any(), any());
    }

    @Test
    @DisplayName("ACTN-07 partial and malformed provider facts remain truthful")
    void ACTN_07_partialMissingAndMalformedProviderFactsRemainTruthful() {
        UserAccount account = account("partial@example.com");
        metadata(account, List.of("a", "b", "c"));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("a", "b", "c"),
            new ActionScopeTargetSummary("3 unread messages", null, null, 3),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String owner = "partial@example.com:google";
        String intent = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        when(gmail.batchModify(any(), any())).thenReturn(new BulkActionResult(
            99, 2, 1, List.of("a", "foreign"),
            java.util.Map.of("b", "raw provider reason"),
            java.time.Instant.now()));

        var result = commands.submit(
            owner, List.of(account.getId()), intent, "IMMEDIATE", true);
        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.succeeded()).isEqualTo(1);
            assertThat(receipt.failed()).isEqualTo(1);
            assertThat(receipt.verificationPending()).isEqualTo(1);
            assertThat(receipt.status()).isEqualTo("PARTIAL");
        });
        assertThat(executionTargets.findAll())
            .extracting(target -> target.targetStatus().name())
            .containsExactlyInAnyOrder("SUCCEEDED", "FAILED", "VERIFICATION_PENDING");
        assertThat(events.findAll().toString())
            .doesNotContain("raw provider reason", "foreign", "message");
    }

    @Test
    void ACTN_01_confirmationPostDefaultsStagedAndBlocksModeBypassBeforeIo()
            throws Exception {
        UserAccount account = account("web@example.com");
        metadata(account, List.of("private-id"));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("private-id"),
            new ActionScopeTargetSummary("1 unread message", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String owner = "web@example.com:google";
        String confirmation = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));

        mockMvc.perform(post("/api/action-commands")
                .with(authentication(auth("web@example.com")))
                .param("confirmation", confirmation))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/action-commands")
                .with(csrf())
                .param("confirmation", confirmation))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/api/action-commands")
                .with(csrf())
                .with(authentication(auth("web@example.com")))
                .param("confirmation", confirmation)
                .param("executionMode", "IMMEDIATE"))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/action-commands")
                .with(csrf())
                .with(authentication(auth("web@example.com")))
                .param("confirmation", confirmation)
                .param("executionMode", "STAGED", "IMMEDIATE"))
            .andExpect(status().isBadRequest());
        assertThat(actions.count()).isZero();
        verify(gmail, never()).batchModify(any(), any());

        mockMvc.perform(post("/api/action-commands")
                .with(csrf())
                .with(authentication(auth("web@example.com")))
                .param("confirmation", confirmation))
            .andExpect(status().is3xxRedirection());
        assertThat(actions.findAll()).singleElement()
            .satisfies(action -> {
                assertThat(action.getStatus()).isEqualTo("QUEUED");
                assertThat(action.getExecutionMode()).isEqualTo("STAGED");
            });
        verify(gmail, never()).batchModify(any(), any());
    }

    @Test
    void ACTN_01_queryCannotForceImmediateAndConsumedStagedIntentCannotEscalate()
            throws Exception {
        UserAccount account = account("mode-bound@example.com");
        metadata(account, List.of("mode-id"));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("mode-id"),
            new ActionScopeTargetSummary("1 unread message", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String owner = "mode-bound@example.com:google";
        String confirmation = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));

        mockMvc.perform(post(
                "/api/action-commands?executionMode=IMMEDIATE&immediateAcknowledged=true")
                .with(csrf())
                .with(authentication(auth("mode-bound@example.com")))
                .param("confirmation", confirmation))
            .andExpect(status().isBadRequest());
        assertThat(actions.count()).isZero();

        mockMvc.perform(post("/api/action-commands")
                .with(csrf())
                .with(authentication(auth("mode-bound@example.com")))
                .header("X-Execution-Mode", "IMMEDIATE")
                .param("confirmation", confirmation))
            .andExpect(status().is3xxRedirection());
        assertThat(actions.findAll()).singleElement()
            .satisfies(action -> assertThat(action.getExecutionMode()).isEqualTo("STAGED"));

        mockMvc.perform(post("/api/action-commands")
                .with(csrf())
                .with(authentication(auth("mode-bound@example.com")))
                .param("confirmation", confirmation)
                .param("executionMode", "IMMEDIATE")
                .param("immediateAcknowledged", "true"))
            .andExpect(status().isConflict());
        verify(gmail, never()).batchModify(any(), any());
    }

    @Test
    void ACTN_07_expiredLeasesResumeOnlyBeforeDispatchAndNeverRetryAfterDispatch() {
        UserAccount account = account("recovery@example.com");
        String owner = "recovery@example.com:google";

        UserAction resumable = stagedAction(
            account, owner, "resume-id", "resume target");
        var reserved = reservations.reserve(
            account.getId(), resumable.getPublicId(), UUID.randomUUID());
        jdbc.update(
            "UPDATE action_executions "
                + "SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
            reserved.executionId());
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });

        var resumed = executor.execute(
            account.getId(), resumable.getPublicId(), UUID.randomUUID());
        assertThat(resumed.status()).isEqualTo("APPLIED");
        verify(gmail, org.mockito.Mockito.times(1)).batchModify(any(), any());

        UserAction uncertain = stagedAction(
            account, owner, "uncertain-id", "uncertain target");
        var running = reservations.reserve(
            account.getId(), uncertain.getPublicId(), UUID.randomUUID());
        reservations.markDispatched(
            account.getId(), running.executionId(), running.leaseFingerprint());
        jdbc.update(
            "UPDATE action_executions "
                + "SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
            running.executionId());

        var recovered = executor.execute(
            account.getId(), uncertain.getPublicId(), UUID.randomUUID());
        assertThat(recovered.status()).isEqualTo("VERIFICATION_PENDING");
        assertThat(recovered.dispatched()).isFalse();
        assertThat(executionTargets
            .findByExecutionIdAndAccountIdOrderByScopeOrdinal(
                running.executionId(), account.getId()))
            .singleElement()
            .satisfies(target -> {
                assertThat(target.targetStatus().name())
                    .isEqualTo("VERIFICATION_PENDING");
                assertThat(target.getReasonCode()).isEqualTo("LEASE_EXPIRED");
            });
        verify(gmail, org.mockito.Mockito.times(1)).batchModify(any(), any());
    }

    @Test
    void ACTN_01_protectionChangeAfterPreviewBlocksReservationBeforeIo() {
        UserAccount account = account("stale-protection@example.com");
        UserAction action = stagedAction(
            account, "stale-protection@example.com:google",
            "protected-id", "protected target");
        jdbc.update("""
            INSERT INTO protected_senders(account_id,sender_address,source,created_at)
            VALUES (?,?,'USER',CURRENT_TIMESTAMP)
            """, account.getId(), "sender-protected-id@example.com");

        assertThatThrownBy(() -> commands.approve(
            account.getId(), action.getPublicId(), UUID.randomUUID()))
            .hasMessageContaining("action_preflight_failed");
        assertThat(executions.count()).isZero();
        verify(gmail, never()).batchModify(any(), any());
    }

    @Test
    void ACTN_01_concurrentImmediateReplayHasOneExecutionAndOneDispatch()
            throws Exception {
        UserAccount account = account("race@example.com");
        metadata(account, List.of("race-id"));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("race-id"),
            new ActionScopeTargetSummary("1 unread message", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String owner = "race@example.com:google";
        String confirmation = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(100);
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });
        Callable<ActionCommandService.CommandResult> submit = () -> commands.submit(
            owner, List.of(account.getId()), confirmation, "IMMEDIATE", true);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(submit, submit)).stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                }).toList();
            assertThat(results).allSatisfy(result ->
                assertThat(result.receipts()).singleElement()
                    .satisfies(receipt -> assertThat(receipt.status())
                        .isIn("APPLIED", "RUNNING", "RESERVED")));
            assertThat(results).extracting(result ->
                result.receipts().getFirst().executionPublicId()).containsOnly(
                    results.getFirst().receipts().getFirst().executionPublicId());
        }
        assertThat(actions.count()).isEqualTo(1);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(executionTargets.count()).isEqualTo(1);
        verify(gmail, org.mockito.Mockito.times(1)).batchModify(any(), any());
    }

    @Test
    @DisplayName("SEND-06 sender mailbox and local actions use the shared ledger")
    void SEND_06_and_UNRD_07_allMailboxAndLocalActionsUseTheSharedLedger() {
        UserAccount account = account("matrix@example.com");
        String owner = "matrix@example.com:google";
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });

        for (ActionType type : List.of(
                ActionType.ARCHIVE, ActionType.TRASH, ActionType.MARK_READ,
                ActionType.KEEP, ActionType.REVIEW)) {
            String messageId = "matrix-" + type.name().toLowerCase();
            metadata(account, List.of(messageId));
            ActionScopePreview preview = scopeService.createOrGet(
                account.getId(), type.name(), "UNREAD_SELECTION", List.of(messageId),
                new ActionScopeTargetSummary("One unread decision", null, null, 1),
                new ActionScopeRiskSummary(0, 0, List.of()));
            String confirmation = confirmations.issue(
                owner, type, DecisionSource.UNREAD,
                List.of(new ActionConfirmationIntentService.ScopeBinding(
                    account.getId(), preview.scopeHash())));
            var result = commands.submit(
                owner, List.of(account.getId()), confirmation, "IMMEDIATE", true);
            assertThat(result.receipts()).singleElement().satisfies(receipt -> {
                assertThat(receipt.requested()).isOne();
                assertThat(receipt.succeeded()).isOne();
                assertThat(receipt.status()).isEqualTo(
                    type == ActionType.REVIEW ? "BLOCKED" : "APPLIED");
            });
        }

        for (ActionType type : List.of(
                ActionType.ARCHIVE, ActionType.TRASH, ActionType.MARK_READ,
                ActionType.KEEP, ActionType.REVIEW)) {
            String messageId = "staged-" + type.name().toLowerCase();
            metadata(account, List.of(messageId));
            ActionScopePreview preview = scopeService.createOrGet(
                account.getId(), type.name(), "SENDER_SELECTION", List.of(messageId),
                new ActionScopeTargetSummary("One sender decision", null, null, 1),
                new ActionScopeRiskSummary(0, 0, List.of()));
            String confirmation = confirmations.issue(
                owner, type, DecisionSource.SENDERS,
                List.of(new ActionConfirmationIntentService.ScopeBinding(
                    account.getId(), preview.scopeHash())));
            var staged = commands.submit(
                owner, List.of(account.getId()), confirmation, "STAGED", false);
            if (type == ActionType.REVIEW) {
                assertThat(staged.receipts()).singleElement()
                    .satisfies(receipt -> assertThat(receipt.status())
                        .isEqualTo("BLOCKED"));
            } else {
                assertThat(staged.receipts()).isEmpty();
                UserAction queued = staged.actions().getFirst();
                assertThat(queued.getStatus()).isEqualTo("QUEUED");
                assertThat(commands.approve(
                    account.getId(), queued.getPublicId(), UUID.randomUUID()).status())
                    .isEqualTo("APPLIED");
            }
        }

        assertThat(executions.count()).isEqualTo(10);
        assertThat(executionTargets.count()).isEqualTo(10);
        assertThat(actions.findAll())
            .filteredOn(action -> action.getActionType().equals("REVIEW"))
            .hasSize(2)
            .allSatisfy(action -> {
                assertThat(action.getStatus()).isEqualTo("BLOCKED");
                assertThat(action.getUndoEligibleUntil()).isNull();
            });
        verify(gmail, org.mockito.Mockito.times(6)).batchModify(any(), any());
        verify(outlook, never()).batchModify(any(), any());
    }

    @Test
    @DisplayName("UNRD-07 unread staged and immediate actions use one exact executor")
    void UNRD_07_unreadStagedAndImmediateUseTheSameExactScopeExecutor() {
        UserAccount account = account("unread-requirement@example.com");
        String owner = "unread-requirement@example.com:google";
        metadata(account, List.of("unread-exact"));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "MARK_READ", "UNREAD_SELECTION",
            List.of("unread-exact"),
            new ActionScopeTargetSummary("One unread message", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            assertThat(request.messageIds()).containsExactly("unread-exact");
            return BulkActionResult.ok(1, request.messageIds());
        });

        String stagedIntent = confirmations.issue(
            owner, ActionType.MARK_READ, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        var staged = commands.submit(
            owner, List.of(account.getId()), stagedIntent, "STAGED", false);
        assertThat(staged.receipts()).isEmpty();
        assertThat(commands.approve(
            account.getId(), staged.actions().getFirst().getPublicId(),
            UUID.randomUUID()).status()).isEqualTo("APPLIED");

        String immediateIntent = confirmations.issue(
            owner, ActionType.MARK_READ, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        var immediate = commands.submit(
            owner, List.of(account.getId()), immediateIntent, "IMMEDIATE", true);
        assertThat(immediate.receipts()).singleElement()
            .satisfies(receipt -> assertThat(receipt.status()).isEqualTo("APPLIED"));
        assertThat(staged.actions().getFirst().getActionScopeId())
            .isEqualTo(immediate.actions().getFirst().getActionScopeId());
        verify(gmail, org.mockito.Mockito.times(2)).batchModify(any(), any());
    }

    @Test
    void SEND_06_protectIsOneSenderOwnedIdempotentAndProviderFree() {
        UserAccount account = account("protect@example.com");
        metadata(account, List.of("protect-a", "protect-b"));
        jdbc.update("""
            UPDATE message_metadata SET from_address='sender@example.com'
            WHERE account_id=?
            """, account.getId());
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "PROTECT", "SENDER_SELECTION",
            List.of("protect-a", "protect-b"),
            new ActionScopeTargetSummary("Sender Example", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String confirmation = confirmations.issue(
            "protect@example.com:google", ActionType.PROTECT, DecisionSource.SENDERS,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));

        var result = commands.submit(
            "protect@example.com:google", List.of(account.getId()),
            confirmation, "IMMEDIATE", true);

        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.status()).isEqualTo("APPLIED");
            assertThat(receipt.succeeded()).isEqualTo(2);
        });
        assertThat(protectedSenders.findByAccountIdAndSenderAddress(
            account.getId(), "sender@example.com")).isPresent();
        assertThat(actions.findAll()).singleElement()
            .satisfies(action -> assertThat(action.getUndoEligibleUntil()).isNull());
        verify(gmail, never()).batchModify(any(), any());
        verify(unsubscribeTransport, never()).execute(any());

        String stagedConfirmation = confirmations.issue(
            "protect@example.com:google", ActionType.PROTECT, DecisionSource.SENDERS,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        var staged = commands.submit(
            "protect@example.com:google", List.of(account.getId()),
            stagedConfirmation, "STAGED", false);
        assertThat(staged.receipts()).isEmpty();
        assertThat(commands.approve(
            account.getId(), staged.actions().getFirst().getPublicId(),
            UUID.randomUUID()).status()).isEqualTo("APPLIED");
        assertThat(protectedSenders.findByAccountIdAndSenderAddress(
            account.getId(), "sender@example.com")).isPresent();
    }

    @Test
    void localHandlerFailureIsFailedWithoutInventingProviderUncertainty() {
        UserAccount account = account("local-failure@example.com");
        metadata(account, List.of("protect-local-failure"));
        jdbc.update("""
            UPDATE message_metadata SET from_address='sender@example.com'
            WHERE account_id=?
            """, account.getId());
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "PROTECT", "SENDER_SELECTION",
            List.of("protect-local-failure"),
            new ActionScopeTargetSummary("Sender Example", null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String confirmation = confirmations.issue(
            "local-failure@example.com:google",
            ActionType.PROTECT, DecisionSource.SENDERS,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        org.mockito.Mockito.doThrow(new IllegalStateException("local failure canary"))
            .when(protectionHandler).execute(any());

        var result = commands.submit(
            "local-failure@example.com:google", List.of(account.getId()),
            confirmation, "IMMEDIATE", true);

        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.status()).isEqualTo("FAILED");
            assertThat(receipt.failed()).isOne();
            assertThat(receipt.verificationPending()).isZero();
        });
        assertThat(executionTargets.findAll()).singleElement().satisfies(target -> {
            assertThat(target.targetStatus().name()).isEqualTo("FAILED");
            assertThat(target.getReasonCode()).isEqualTo("STALE_ACTION");
        });
        verify(gmail, never()).batchModify(any(), any());
        verify(unsubscribeTransport, never()).execute(any());
    }

    @Test
    @DisplayName("UNSB-07 immediate unsubscribe retains Phase 11 safety and outcome truth")
    void UNSB_07_immediateUsesFreshPhase11SafetyAndLinksAcceptedPendingAttempt()
            throws Exception {
        UserAccount account = account("unsubscribe@example.com");
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId("unsubscribe-source-id");
        row.setProviderThreadId("private-thread");
        row.setFromAddress("news@example.com");
        row.setFromDisplayName("Example News");
        row.setFromDomain("example.com");
        row.setReceivedAt(java.time.Instant.now());
        row.setListIdValues(List.of("news.list.example"));
        row.setListUnsubscribeValues(List.of(
            "<https://unsubscribe.example/private/token?secret=hidden>"));
        row.setListUnsubscribePostValues(List.of("List-Unsubscribe=One-Click"));
        row.setUnsubscribeDkimPass(true);
        row.setUnsubscribeDkimCoversListUnsubscribe(true);
        row.setUnsubscribeDkimCoversListUnsubscribePost(true);
        row.setUnsubscribeAuthReason("TRUSTED_PASS");
        metadata.saveAndFlush(row);
        var candidate = unsubscribeCandidates.query(
            List.of(account.getId()),
            com.tidymail.dto.UnsubscribeQuery.parse(
                java.util.Map.of("account", List.of(String.valueOf(account.getId())))))
            .rows().getFirst();
        var executable = unsubscribeCandidates.executionCandidate(
            List.of(account.getId()), candidate.candidateKey(),
            UnsubscribeMethod.RFC8058_POST);
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "UNSUBSCRIBE", "UNSUBSCRIBE_CANDIDATE",
            List.of(executable.providerMessageId()),
            new ActionScopeTargetSummary(
                executable.safeLabel(), executable.senderAddress(), null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String confirmation = confirmations.issue(
            "unsubscribe@example.com:google",
            ActionType.UNSUBSCRIBE, DecisionSource.UNSUBSCRIBE,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash(),
                executable.candidateFingerprint(),
                executable.destinationFingerprint(),
                executable.method().name())));
        when(destinationResolver.resolveAndValidate("unsubscribe.example"))
            .thenReturn(new ResolvedDestination(
                "unsubscribe.example",
                List.of(InetAddress.getByName("8.8.8.8"))));
        when(unsubscribeTransport.execute(any()))
            .thenReturn(UnsubscribeHttpTransport.TransportResult.accepted());

        assertThatThrownBy(() -> commands.submit(
            "unsubscribe@example.com:google", List.of(account.getId()),
            confirmation, "IMMEDIATE", true, "LEGACY_HTTPS_GET"))
            .hasMessageContaining("unsubscribe_method_changed");
        assertThat(unsubscribeAttempts.count()).isZero();
        verify(unsubscribeTransport, never()).execute(any());

        var result = commands.submit(
            "unsubscribe@example.com:google", List.of(account.getId()),
            confirmation, "IMMEDIATE", true, "RFC8058_POST");

        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.status()).isEqualTo("VERIFICATION_PENDING");
            assertThat(receipt.succeeded()).isZero();
            assertThat(receipt.verificationPending()).isOne();
        });
        assertThat(executions.findAll()).singleElement().satisfies(execution -> {
            assertThat(execution.getAcceptedCount()).isOne();
            assertThat(execution.getSucceededCount()).isZero();
            assertThat(execution.getVerificationPendingCount()).isOne();
        });
        assertThat(executionTargets.findAll()).singleElement().satisfies(target ->
            assertThat(target.getUnsubscribeAttemptId()).isNotNull());
        assertThat(unsubscribeAttempts.count()).isOne();
        verify(unsubscribeTransport).execute(any());
        verify(gmail, never()).batchModify(any(), any());

        String stagedConfirmation = confirmations.issue(
            "unsubscribe@example.com:google",
            ActionType.UNSUBSCRIBE, DecisionSource.UNSUBSCRIBE,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash(),
                executable.candidateFingerprint(),
                executable.destinationFingerprint(),
                executable.method().name())));
        var staged = commands.submit(
            "unsubscribe@example.com:google", List.of(account.getId()),
            stagedConfirmation, "STAGED", false, "RFC8058_POST");
        assertThat(staged.receipts()).isEmpty();
        assertThat(staged.actions()).singleElement()
            .satisfies(action -> assertThat(action.getStatus()).isEqualTo("QUEUED"));
        assertThat(unsubscribeAttempts.count()).isOne();
        verify(unsubscribeTransport).execute(any());
        assertThatThrownBy(() -> commands.approve(
            account.getId(), staged.actions().getFirst().getPublicId(),
            UUID.randomUUID()))
            .hasMessageContaining("fresh_unsubscribe_consent_required");
        assertThat(unsubscribeAttempts.count()).isOne();
        verify(unsubscribeTransport).execute(any());

        String approvalConfirmation = confirmations.issue(
            "unsubscribe@example.com:google",
            ActionType.UNSUBSCRIBE, DecisionSource.REVIEW,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash(),
                executable.candidateFingerprint(),
                executable.destinationFingerprint(),
                executable.method().name())));
        var approved = commands.approveUnsubscribe(
            "unsubscribe@example.com:google", List.of(account.getId()),
            staged.actions().getFirst().getPublicId(),
            approvalConfirmation, "RFC8058_POST");
        assertThat(approved.status()).isEqualTo("VERIFICATION_PENDING");
        assertThat(unsubscribeAttempts.count()).isEqualTo(2);
        verify(unsubscribeTransport, org.mockito.Mockito.times(2)).execute(any());
        assertThatThrownBy(() -> commands.approveUnsubscribe(
            "unsubscribe@example.com:google", List.of(account.getId()),
            staged.actions().getFirst().getPublicId(),
            approvalConfirmation, "RFC8058_POST"))
            .hasMessageContaining("action_confirmation_stale");
        verify(unsubscribeTransport, org.mockito.Mockito.times(2)).execute(any());
    }

    @Test
    void sourceControllersAndFormsExposeNoProviderOrTransportAuthority() throws Exception {
        String selectionController = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/tidymail/controller/SelectionPreviewController.java"));
        String commandController = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/tidymail/controller/ActionCommandController.java"));
        String unsubscribeController = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/tidymail/controller/UnsubscribeExecutionController.java"));
        String preview = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/fragments/selection-preview-frag.html"));
        String unsubscribe = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/fragments/unsubscribe-inspection-frag.html"));

        assertThat(selectionController + commandController)
            .doesNotContain("MailProvider", "UnsubscribeHttpTransport");
        assertThat(unsubscribeController)
            .doesNotContain(
                "MailProvider", "UnsubscribeHttpTransport",
                "execution.execute(");
        assertThat(preview)
            .contains(
                "name=\"confirmation\"", "name=\"executionMode\"",
                "name=\"immediateAcknowledged\"")
            .doesNotContain(
                "name=\"scopeId\"", "name=\"accountId\"", "name=\"messageIds\"",
                "name=\"count\"", "name=\"normalizedSender\"", "name=\"outcome\"");
        assertThat(unsubscribe)
            .contains(
                "name=\"confirmation\"", "name=\"unsubscribeMethod\"",
                "name=\"executionMode\"", "name=\"immediateAcknowledged\"")
            .doesNotContain(
                "name=\"destination\"", "name=\"eligible\"", "name=\"accountId\"",
                "name=\"providerMessageId\"");

        UserAccount account = account("privacy-preview@example.com");
        metadata(account, List.of("PRIVATE_MODEL_PROVIDER_ID"));
        jdbc.update("""
            UPDATE message_metadata SET from_address='privacy-sender@example.com'
            WHERE account_id=?
            """, account.getId());
        var response = mockMvc.perform(post(
                    "/api/actions/bulk-by-sender/privacy-sender@example.com")
                .param("action", "ARCHIVE")
                .with(csrf())
                .with(authentication(auth("privacy-preview@example.com"))))
            .andExpect(status().isOk())
            .andReturn();
        Object modelPreview = response.getModelAndView().getModel().get("preview");
        assertThat(modelPreview).isInstanceOf(ActionScopePresentation.class);
        assertThat(java.util.Arrays.stream(
                modelPreview.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("scopeId", "messageIds", "scopeHash");
        assertThat(response.getResponse().getContentAsString())
            .doesNotContain("PRIVATE_MODEL_PROVIDER_ID");
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

    private UserAction stagedAction(
            UserAccount account, String owner, String messageId, String label) {
        metadata(account, List.of(messageId));
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of(messageId),
            new ActionScopeTargetSummary(label, null, null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String confirmation = confirmations.issue(
            owner, ActionType.ARCHIVE, DecisionSource.UNREAD,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        return commands.submit(
            owner, List.of(account.getId()), confirmation, "STAGED", false)
            .actions().getFirst();
    }

    private void metadata(UserAccount account, List<String> messageIds) {
        List<MessageMetadata> rows = messageIds.stream().map(messageId -> {
            MessageMetadata row = new MessageMetadata();
            row.setAccountId(account.getId());
            row.setProvider(account.getProvider());
            row.setProviderMessageId(messageId);
            row.setFromAddress("sender-" + messageId + "@example.com");
            row.setFromDomain("example.com");
            row.setLabels(List.of());
            return row;
        }).toList();
        metadata.saveAllAndFlush(rows);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new DefaultOAuth2User(
            authorities, java.util.Map.of(
                "sub", "subject-" + email,
                "email", email,
                "name", email), "sub");
        return new OAuth2AuthenticationToken(principal, authorities, "google");
    }
}
