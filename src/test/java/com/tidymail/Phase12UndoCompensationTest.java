package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.dto.ActionScopePreview;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.HistoryQuery;
import com.tidymail.dto.AnalyticsQuery.AnalyticsAction;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionHistoryRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.AnalyticsLedgerRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.ProtectedSenderRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.ActionConfirmationIntentService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.CompensationService;
import com.tidymail.service.ExactScopeExecutor;
import com.tidymail.service.UndoService;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=", "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=", "MICROSOFT_CLIENT_SECRET="
})
class Phase12UndoCompensationTest {
    @Autowired UserAccountRepository accounts;
    @Autowired UserActionRepository actions;
    @Autowired ActionScopeRepository scopes;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ActionExecutionRepository executions;
    @Autowired ActionExecutionTargetRepository targets;
    @Autowired ActionIntentRepository intents;
    @Autowired ActionHistoryRepository history;
    @Autowired AnalyticsLedgerRepository analytics;
    @Autowired ProtectedSenderRepository protectedSenders;
    @Autowired ActionScopeService scopeService;
    @Autowired ActionConfirmationIntentService confirmations;
    @Autowired ActionCommandService commands;
    @Autowired ExactScopeExecutor executor;
    @Autowired UndoService undo;
    @Autowired CompensationService compensation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @MockBean(name = "gmailMailService") MailProvider gmail;
    @MockBean(name = "outlookMailService") MailProvider outlook;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM action_intents");
        jdbc.update("DELETE FROM user_actions");
        jdbc.update("DELETE FROM action_scopes");
        jdbc.update("DELETE FROM protected_senders");
        jdbc.update("DELETE FROM message_metadata");
        jdbc.update("DELETE FROM user_accounts");
        reset(gmail, outlook);
    }

    @Test
    @DisplayName("ACTN-08 undo sends only forward succeeded ordinals")
    void ACTN_08_undoSendsOnlyForwardSucceededOrdinals() {
        UserAccount account = account("undo@example.com");
        String owner = "undo@example.com:google";
        UserAction action = action(
            account, owner, ActionType.ARCHIVE, List.of("A", "B", "C", "D"), "sender");
        when(gmail.batchModify(any(), any())).thenReturn(
            new BulkActionResult(
                4, 2, 1, List.of("A", "B"), Map.of("C", "rejected"), Instant.now()));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());

        var confirmation = undo.prepare(owner, account.getId(), action.getPublicId());
        assertThat(confirmation.appliedCount()).isEqualTo(2);
        when(gmail.applyLabel(any(), any(), any(), any(Boolean.class)))
            .thenReturn(BulkActionResult.ok(2, List.of("A", "B")));
        var result = undo.execute(
            owner, account.getId(), action.getPublicId(), confirmation.intent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(gmail).applyLabel(
            any(UserAccount.class), ids.capture(),
            org.mockito.ArgumentMatchers.eq("INBOX"),
            org.mockito.ArgumentMatchers.eq(true));
        assertThat(ids.getValue()).containsExactly("A", "B");
        assertThat(result.reverted()).isEqualTo(2);
          assertThat(jdbc.queryForList("""
              SELECT status FROM action_execution_targets
               WHERE execution_id=(
                   SELECT id FROM action_executions
                    WHERE action_id=? AND operation_kind='UNDO'
               )
               ORDER BY scope_ordinal
              """, String.class, action.getId())).containsExactly("REVERTED", "REVERTED");
          Instant analyticsEnd = Instant.now().plusSeconds(60);
          assertThat(analytics.snapshot(
              List.of(account.getId()),
              new DateRange(analyticsEnd.minusSeconds(3_600), analyticsEnd),
              AnalyticsAction.ALL).inverseSucceeded()).isEqualTo(2);
        assertThat(executions.findByActionIdAndOperationKind(
            action.getId(), "UNDO")).isPresent();
        assertThat(targets.findForwardSucceededTargets(
            action.getId(), account.getId())).hasSize(2);
        var historyRow = history.page(
            List.of(account.getId()),
            new HistoryQuery(
                String.valueOf(account.getId()), null, null,
                "all", "all", "", 0)).getFirst();
        assertThat(historyRow.status()).isEqualTo("UNDONE");
        assertThat(historyRow.executionId()).isEqualTo(
            executions.findByActionIdAndOperationKind(
                action.getId(), "UNDO").orElseThrow().getId());
        assertThat(historyRow.succeeded()).isEqualTo(2);
    }

    @Test
    void ACTN_08_confirmationUsesProviderNeutralAccountLabel() {
        UserAccount account = account("outlook@example.com", "microsoft");
        String owner = "outlook@example.com:microsoft";
        UserAction action = action(
            account, owner, ActionType.ARCHIVE, List.of("M"), "sender");
        when(outlook.batchModify(any(), any()))
            .thenReturn(BulkActionResult.ok(1, List.of("M")));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());

        var confirmation = undo.prepare(
            owner, account.getId(), action.getPublicId());
        assertThat(confirmation.accountId()).isEqualTo(account.getId());
        assertThat(confirmation.accountLabel())
            .isEqualTo("outlook@example.com (Microsoft Outlook)");
    }

    @Test
    void ACTN_08_09_confirmationFormsPreserveAccountAndRequireCsrf()
            throws Exception {
        UserAccount selected = account("forms@example.com");
        String owner = "forms@example.com:google";
        UserAction archive = action(
            selected, owner, ActionType.ARCHIVE, List.of("F"), "sender");
        when(gmail.batchModify(any(), any()))
            .thenReturn(BulkActionResult.ok(1, List.of("F")));
        executor.execute(selected.getId(), archive.getPublicId(), UUID.randomUUID());

        mvc.perform(post("/review/actions/" + archive.getPublicId() + "/undo/confirm")
                .param("account", String.valueOf(selected.getId()))
                .with(authentication(auth("forms@example.com"))))
            .andExpect(status().isForbidden());
        String confirmation = mvc.perform(post(
                "/review/actions/" + archive.getPublicId() + "/undo/confirm")
                .param("account", String.valueOf(selected.getId()))
                .with(authentication(auth("forms@example.com"))).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(confirmation)
            .contains("name=\"account\" value=\"" + selected.getId() + "\"");

        UserAction protect = action(
            selected, owner, ActionType.PROTECT, List.of("PF"), "safe@example.com");
        executor.execute(selected.getId(), protect.getPublicId(), UUID.randomUUID());
        var compensationConfirmation =
            compensation.prepare(owner, selected.getId(), protect.getPublicId());
        long before = actions.count();
        mvc.perform(post(
                "/api/actions/" + protect.getPublicId() + "/remove-protection")
                .param("account", String.valueOf(selected.getId()))
                .param("compensationIntent", compensationConfirmation.intent())
                .param("executionMode", "STAGED")
                .with(authentication(auth("forms@example.com"))))
            .andExpect(status().isForbidden());
        assertThat(actions.count()).isEqualTo(before);
    }

    @Test
    void ACTN_08_legacyAndTamperedUndoPerformNoProviderCall() {
        UserAccount account = account("legacy@example.com");
        UserAction legacy = new UserAction();
        legacy.setAccountId(account.getId());
        legacy.setActionType("ARCHIVE");
        legacy.setMessageIds(List.of("legacy-id"));
        legacy.setAffectedCount(1);
        legacy.setInverseActionData(Map.of());
        legacy.setTargetIdJson(Map.of());
        legacy.setStatus("APPLIED");
        legacy = actions.saveAndFlush(legacy);

        UserAction saved = legacy;
        assertThatThrownBy(() -> undo.prepare(
            "legacy@example.com:google", account.getId(), saved.getPublicId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(gmail, org.mockito.Mockito.never())
            .batchModify(any(), any());
    }

    @Test
    void ACTN_08_expiredForeignTamperedAndFailedOnlyUndoPerformNoIo() {
        UserAccount account = account("negative@example.com");
        UserAccount foreign = account("foreign-undo@example.com");
        String owner = "negative@example.com:google";
        UserAction applied = action(
            account, owner, ActionType.ARCHIVE, List.of("N"), "sender");
        when(gmail.batchModify(any(), any()))
            .thenReturn(BulkActionResult.ok(1, List.of("N")));
        executor.execute(account.getId(), applied.getPublicId(), UUID.randomUUID());
        var confirmation =
            undo.prepare(owner, account.getId(), applied.getPublicId());
        reset(gmail, outlook);

        assertThatThrownBy(() -> undo.execute(
            owner, account.getId(), applied.getPublicId(), "tampered"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> undo.execute(
            owner, foreign.getId(), applied.getPublicId(), confirmation.intent()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update("""
            UPDATE user_actions
               SET undo_eligible_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
             WHERE id = ?
            """, applied.getId());
        assertThatThrownBy(() -> undo.prepare(
            owner, account.getId(), applied.getPublicId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        UserAction failed = action(
            account, owner, ActionType.ARCHIVE, List.of("F"), "sender");
        when(gmail.batchModify(any(), any())).thenReturn(
            BulkActionResult.withPartialFailures(
                1, List.of(), Map.of("F", "rejected")));
        executor.execute(account.getId(), failed.getPublicId(), UUID.randomUUID());
        reset(gmail, outlook);
        assertThatThrownBy(() -> undo.prepare(
            owner, account.getId(), failed.getPublicId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoMoreInteractions(gmail, outlook);
    }

    @Test
    void ACTN_08_trashAndMarkReadUseTheirExactInverse() {
        UserAccount account = account("inverse@example.com");
        String owner = "inverse@example.com:google";
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(
                request.messageIds().size(), request.messageIds());
        });

        UserAction trash = action(
            account, owner, ActionType.TRASH, List.of("T"), "trash");
        executor.execute(account.getId(), trash.getPublicId(), UUID.randomUUID());
        var trashConfirmation =
            undo.prepare(owner, account.getId(), trash.getPublicId());
        when(gmail.applyLabel(any(), any(), any(), any(Boolean.class)))
            .thenReturn(BulkActionResult.ok(1, List.of("T")));
        undo.execute(
            owner, account.getId(), trash.getPublicId(), trashConfirmation.intent());
        verify(gmail).applyLabel(
            any(UserAccount.class), org.mockito.ArgumentMatchers.eq(List.of("T")),
            org.mockito.ArgumentMatchers.eq("TRASH"),
            org.mockito.ArgumentMatchers.eq(false));

        UserAction read = action(
            account, owner, ActionType.MARK_READ, List.of("R"), "read");
        executor.execute(account.getId(), read.getPublicId(), UUID.randomUUID());
        var readConfirmation =
            undo.prepare(owner, account.getId(), read.getPublicId());
        undo.execute(
            owner, account.getId(), read.getPublicId(), readConfirmation.intent());
        ArgumentCaptor<BulkActionRequest> requests =
            ArgumentCaptor.forClass(BulkActionRequest.class);
        verify(gmail, times(3)).batchModify(
            any(UserAccount.class), requests.capture());
        assertThat(requests.getAllValues().getLast())
            .extracting(BulkActionRequest::actionType, BulkActionRequest::messageIds)
            .containsExactly(BulkActionType.MARK_UNREAD, List.of("R"));
    }

    @Test
    void ACTN_08_partialUndoAndExpiredReplayRemainTruthfulWithoutRedispatch() {
        UserAccount account = account("recover-undo@example.com");
        String owner = "recover-undo@example.com:google";
        UserAction action = action(
            account, owner, ActionType.ARCHIVE, List.of("A", "B"), "sender");
        when(gmail.batchModify(any(), any()))
            .thenReturn(BulkActionResult.ok(2, List.of("A", "B")));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());
        var confirmation = undo.prepare(owner, account.getId(), action.getPublicId());
        when(gmail.applyLabel(any(), any(), any(), any(Boolean.class)))
            .thenReturn(new BulkActionResult(
                2, 1, 0, List.of("A"), Map.of(), Instant.now()));
        var partial = undo.execute(
            owner, account.getId(), action.getPublicId(), confirmation.intent());
        assertThat(partial.status()).isEqualTo("PARTIALLY_UNDONE");
        assertThat(partial.reverted()).isOne();
        assertThat(partial.verificationPending()).isOne();

        long undoExecutionId = executions.findByActionIdAndOperationKind(
            action.getId(), "UNDO").orElseThrow().getId();
        jdbc.update("""
            UPDATE action_execution_targets
               SET status = 'PENDING', accepted_at = NULL, completed_at = NULL,
                   reason_code = NULL
             WHERE execution_id = ?
            """, undoExecutionId);
        jdbc.update("""
            UPDATE action_executions
               SET status = 'RUNNING', accepted_count = 0, succeeded_count = 0,
                   failed_count = 0, verification_pending_count = 0,
                   not_dispatched_count = requested_count,
                   completed_at = NULL, reason_code = NULL,
                   lease_fingerprint = ?,
                   lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
             WHERE id = ?
            """, "a".repeat(64), undoExecutionId);
        jdbc.update("""
            UPDATE user_actions
               SET status = 'APPLIED', undo_eligible_until = NULL, undone_at = NULL
             WHERE id = ?
            """, action.getId());

        var recovered = undo.execute(
            owner, account.getId(), action.getPublicId(), confirmation.intent());
        assertThat(recovered.status()).isEqualTo("PARTIALLY_UNDONE");
        assertThat(recovered.reverted()).isZero();
        assertThat(recovered.verificationPending()).isEqualTo(2);
        assertThat(targets.findByExecutionIdAndAccountIdOrderByScopeOrdinal(
            undoExecutionId, account.getId()))
            .allSatisfy(target ->
                assertThat(target.targetStatus().name())
                    .isEqualTo("VERIFICATION_PENDING"));
        verify(gmail, times(1)).applyLabel(
            any(), any(), any(), any(Boolean.class));
        verifyNoMoreInteractions(outlook);
    }

    @Test
    void ACTN_08_consumedReplayAndConcurrentDuplicateUndoDispatchAtMostOnce()
            throws Exception {
        UserAccount account = account("duplicate-undo@example.com");
        String owner = "duplicate-undo@example.com:google";
        UserAction action = action(
            account, owner, ActionType.ARCHIVE, List.of("DU"), "sender");
        when(gmail.batchModify(any(), any()))
            .thenReturn(BulkActionResult.ok(1, List.of("DU")));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());
        var confirmation = undo.prepare(owner, account.getId(), action.getPublicId());
        when(gmail.applyLabel(any(), any(), any(), any(Boolean.class)))
            .thenAnswer(invocation -> {
                Thread.sleep(100);
                return BulkActionResult.ok(1, List.of("DU"));
            });
        Callable<UndoService.UndoResult> submit = () -> undo.execute(
            owner, account.getId(), action.getPublicId(), confirmation.intent());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(submit, submit)).stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }).toList();
            assertThat(results).extracting(UndoService.UndoResult::status)
                .containsExactlyInAnyOrder("UNDONE", "IN_PROGRESS");
            assertThat(results).filteredOn(result -> "UNDONE".equals(result.status()))
                .singleElement()
                .satisfies(result -> assertThat(result.reverted()).isOne());
        }
        assertThat(undo.execute(
            owner, account.getId(), action.getPublicId(), confirmation.intent()).status())
            .isEqualTo("UNDONE");
        verify(gmail, times(1)).applyLabel(
            any(), any(), any(), any(Boolean.class));
        assertThat(executions.findAll().stream()
            .filter(row -> row.operationKind().name().equals("UNDO"))).hasSize(1);
    }

    @Test
    void ACTN_08_pendingOnlyForwardIsNeverUndoEligible() {
        UserAccount account = account("pending-only@example.com");
        String owner = "pending-only@example.com:google";
        UserAction action = action(
            account, owner, ActionType.ARCHIVE, List.of("PENDING"), "sender");
        when(gmail.batchModify(any(), any())).thenReturn(
            new BulkActionResult(1, 0, 0, List.of(), Map.of(), Instant.now()));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());

        assertThat(targets.findForwardSucceededTargets(
            action.getId(), account.getId())).isEmpty();
        assertThatThrownBy(() -> undo.prepare(
            owner, account.getId(), action.getPublicId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .extracting(error -> ((org.springframework.web.server.ResponseStatusException)
                error).getReason())
            .isEqualTo("undo_unavailable");
        verify(gmail, org.mockito.Mockito.never())
            .applyLabel(any(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("ACTN-09 remove protection is a linked independent compensation")
    void ACTN_09_removeProtectionIsNewLinkedActionAndOriginalRemains() {
        UserAccount account = account("protect@example.com");
        String owner = "protect@example.com:google";
        UserAction original = action(
            account, owner, ActionType.PROTECT, List.of("P"), "safe@example.com");
        executor.execute(account.getId(), original.getPublicId(), UUID.randomUUID());
        String originalStatus = actions.findById(original.getId()).orElseThrow().getStatus();

        var confirmation =
            compensation.prepare(owner, account.getId(), original.getPublicId());
        assertThat(confirmation.accountId()).isEqualTo(account.getId());
        var result = compensation.submit(
            owner, account.getId(), original.getPublicId(), confirmation.intent(),
            "IMMEDIATE", true);

        UserAction created = actions.findByPublicIdAndAccountId(
            result.actionPublicId(), account.getId()).orElseThrow();
        assertThat(created.getActionType()).isEqualTo("REMOVE_PROTECTION");
        assertThat(intents.findByActionIdAndAccountId(
            created.getId(), account.getId()).orElseThrow().getCompensatesActionId())
            .isEqualTo(original.getId());
        assertThat(actions.findById(original.getId()).orElseThrow().getStatus())
            .isEqualTo(originalStatus);
        assertThat(protectedSenders.findByAccountIdAndSenderAddress(
            account.getId(), "safe@example.com")).isEmpty();
        verify(gmail, org.mockito.Mockito.never()).batchModify(any(), any());
    }

    @Test
    void ACTN_09_stagedCompensationExecutesOnceAndRejectsReplayAndDuplicate() {
        UserAccount account = account("staged-compensation@example.com");
        String owner = "staged-compensation@example.com:google";
        UserAction original = action(
            account, owner, ActionType.PROTECT, List.of("SP"), "safe@example.com");
        executor.execute(account.getId(), original.getPublicId(), UUID.randomUUID());
        var confirmation =
            compensation.prepare(owner, account.getId(), original.getPublicId());

        var staged = compensation.submit(
            owner, account.getId(), original.getPublicId(), confirmation.intent(),
            "STAGED", false);
        assertThat(staged.mode().name()).isEqualTo("STAGED");
        assertThat(staged.receipt()).isNull();
        UserAction stagedAction = actions.findByPublicIdAndAccountId(
            staged.actionPublicId(), account.getId()).orElseThrow();
        assertThat(stagedAction.getStatus()).isEqualTo("QUEUED");
        assertThat(protectedSenders.findByAccountIdAndSenderAddress(
            account.getId(), "safe@example.com")).isPresent();

        assertThatThrownBy(() -> compensation.submit(
            owner, account.getId(), original.getPublicId(), confirmation.intent(),
            "STAGED", false))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .extracting(error -> ((org.springframework.web.server.ResponseStatusException)
                error).getReason())
            .isEqualTo("compensation_intent_stale");

        var receipt = commands.approve(
            account.getId(), stagedAction.getPublicId(), UUID.randomUUID());
        assertThat(receipt.status()).isEqualTo("APPLIED");
        assertThat(protectedSenders.findByAccountIdAndSenderAddress(
            account.getId(), "safe@example.com")).isEmpty();
        assertThat(actions.findById(original.getId()).orElseThrow().getStatus())
            .isEqualTo("APPLIED");
        assertThat(executions.findByActionIdAndOperationKind(
            stagedAction.getId(), "FORWARD")).isPresent();
        verifyNoMoreInteractions(gmail, outlook);
    }

    @Test
    void ACTN_09_conflictedCompensationWritesNothingAndConsumesNothing() {
        UserAccount account = account("conflict@example.com");
        String owner = "conflict@example.com:google";
        UserAction original = action(
            account, owner, ActionType.PROTECT, List.of("P"), "safe@example.com");
        executor.execute(account.getId(), original.getPublicId(), UUID.randomUUID());
        var confirmation =
            compensation.prepare(owner, account.getId(), original.getPublicId());

        ActionScope originalScope = scopes.findById(
            original.getActionScopeId()).orElseThrow();
        var reviewPreview = scopeService.createOrGetRequired(
            account.getId(), ActionType.REVIEW.name(),
            originalScope.getTargetKind(), originalScope.getMessageIds(),
            originalScope.getTargetSummary(), originalScope.getRiskSummary());
        ActionScope reviewScope = scopeService.requireScope(
            account.getId(), reviewPreview.scopeId());
        UserAction review = new UserAction();
        review.setAccountId(account.getId());
        review.setActionScopeId(reviewScope.getId());
        review.setActionType(ActionType.REVIEW.name());
        review.setMessageIds(reviewScope.getMessageIds());
        review.setInverseActionData(Map.of());
        review.setTargetKind(reviewScope.getTargetKind());
        review.setTargetIdJson(Map.of());
        review.setAffectedCount(reviewScope.getMessageCount());
        review.setStatus("QUEUED");
        review.setExecutionMode("STAGED");
        review.setDecisionSource("REVIEW");
        review = actions.saveAndFlush(review);
        intents.saveAndFlush(ActionIntent.review(
            review.getId(), account.getId(), "Review sender",
            "safe@example.com", Instant.now()));

        long actionCount = actions.count();
        long intentCount = intents.count();
        long eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM action_events", Long.class);
        assertThatThrownBy(() -> compensation.submit(
            owner, account.getId(), original.getPublicId(), confirmation.intent(),
            "IMMEDIATE", true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .extracting(error -> ((org.springframework.web.server.ResponseStatusException)
                error).getReason())
            .isEqualTo("compensation_conflict");
        assertThat(actions.count()).isEqualTo(actionCount);
        assertThat(intents.count()).isEqualTo(intentCount);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM action_events", Long.class)).isEqualTo(eventCount);
        assertThat(jdbc.queryForObject("""
            SELECT consumed_at FROM action_command_tokens
             WHERE token_fingerprint = ?
            """, java.sql.Timestamp.class,
            com.tidymail.service.ActionFingerprints.sha256(confirmation.intent())))
            .isNull();
        verifyNoMoreInteractions(gmail, outlook);
    }

    private UserAction action(
            UserAccount account, String owner, ActionType type,
            List<String> ids, String sender) {
        metadata.saveAllAndFlush(ids.stream().map(id -> {
            MessageMetadata row = new MessageMetadata();
            row.setAccountId(account.getId());
            row.setProvider("google");
            row.setProviderMessageId(id);
            row.setFromAddress(sender.contains("@") ? sender : "sender@example.com");
            row.setFromDomain("example.com");
            row.setLabels(List.of());
            return row;
        }).toList());
        ActionScopePreview preview = scopeService.createOrGet(
            account.getId(), type.name(), "SENDER_SELECTION", ids,
            new ActionScopeTargetSummary(sender, sender, "example.com", 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String token = confirmations.issue(
            owner, type, DecisionSource.SENDERS,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash())));
        return commands.submit(
            owner, List.of(account.getId()), token, "STAGED", false)
            .actions().getFirst();
    }

    private UserAccount account(String email) {
        return account(email, "google");
    }

    private UserAccount account(String email, String provider) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider(provider);
        account.setProviderAccountId(provider + "-" + email + "-" + UUID.randomUUID());
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.saveAndFlush(account);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Owner");
        attributes.put("sub", "owner");
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
