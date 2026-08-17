package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ActionStatus;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.ExecutionTargetStatus;
import com.tidymail.action.OperationKind;
import com.tidymail.dto.UndoConfirmation;
import com.tidymail.entity.ActionCommandToken;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionExecution;
import com.tidymail.entity.ActionExecutionTarget;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.ActionScopeTarget;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionCommandTokenRepository;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.ActionScopeTargetRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * Implements the undo capability: prepares and executes the inverse of an applied
 * archive/trash/mark-read action against the exact applied targets, guarded by a
 * short-lived intent token, an undo window, and lease-based idempotency.
 *
 * <p>Product functionality supported: the <em>Undo</em> flow, which reverses
 * recently applied cleanup actions.
 */
public class UndoService {
    private static final Duration INTENT_TTL = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final List<String> TYPES = List.of("ARCHIVE", "TRASH", "MARK_READ");

    private final UserActionRepository actions;
    private final UserAccountRepository accounts;
    private final ActionScopeService scopes;
    private final ActionScopeTargetRepository scopeTargets;
    private final ActionExecutionRepository executions;
    private final ActionExecutionTargetRepository targets;
    private final ActionCommandTokenRepository tokens;
    private final ActionEventRepository events;
    private final ExecutionRecoveryService recovery;
    private final Map<String, MailProvider> providers;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    public UndoService(
            UserActionRepository actions,
            UserAccountRepository accounts,
            ActionScopeService scopes,
            ActionScopeTargetRepository scopeTargets,
            ActionExecutionRepository executions,
            ActionExecutionTargetRepository targets,
            ActionCommandTokenRepository tokens,
            ActionEventRepository events,
            ExecutionRecoveryService recovery,
            Map<String, MailProvider> providers,
            TransactionTemplate transactions) {
        this.actions = actions;
        this.accounts = accounts;
        this.scopes = scopes;
        this.scopeTargets = scopeTargets;
        this.executions = executions;
        this.targets = targets;
        this.tokens = tokens;
        this.events = events;
        this.recovery = recovery;
        this.providers = providers;
        this.transactions = transactions;
    }

    public UndoService(
            UserActionRepository actions,
            ActionScopeService scopes) {
        this(actions, null, scopes, null, null, null, null, null, null, Map.of(), null);
    }

    /** Prepares an undo intent token for an eligible action. */
    public UndoConfirmation prepare(
            String ownerKey, long accountId, UUID actionPublicId) {
        return transactions.execute(status -> {
            UserAction action = eligibleAction(accountId, actionPublicId, clock.instant());
            List<ActionExecutionTarget> applied =
                targets.findForwardSucceededTargets(action.getId(), accountId);
            List<Integer> ordinals = applied.stream()
                .map(ActionExecutionTarget::getScopeOrdinal).toList();
            verifyOrdinals(action, ordinals);
            String raw = UUID.randomUUID().toString();
            Instant now = clock.instant();
            tokens.save(ActionCommandToken.issue(
                accountId, ActionFingerprints.sha256(raw),
                ActionFingerprints.sha256(ownerKey),
                ActionFingerprints.sha256("UNDO"),
                subject(action, applied), now, now.plus(INTENT_TTL)));
            UserAccount account = accounts.findById(accountId).orElseThrow(
                UndoService::notFound);
            return new UndoConfirmation(
                action.getPublicId(), accountId, visible(action.getActionType()),
                accountLabel(account), applied.size(),
                action.getAffectedCount() - applied.size(),
                inverseLabel(action.getActionType()),
                now.plus(INTENT_TTL), raw);
        });
    }

    /** Executes the undo, reserving, dispatching the inverse, and completing. */
    public UndoResult execute(
            String ownerKey, long accountId, UUID actionPublicId, String rawIntent) {
        UndoAttempt attempt = transactions.execute(status ->
            reserveOrRecover(ownerKey, accountId, actionPublicId, rawIntent));
        if (attempt.existingExecutionId() != null) {
            recovery.recover(accountId, attempt.existingExecutionId());
            return transactions.execute(status ->
                result(accountId, actionPublicId, attempt.existingExecutionId()));
        }
        ReservedUndo reserved = attempt.reserved();
        BulkActionResult result;
        try {
            result = inverse(provider(reserved.account()), reserved);
        } catch (RuntimeException unknownAfterDispatch) {
            result = null;
        }
        BulkActionResult completedResult = result;
        return transactions.execute(status -> complete(reserved, completedResult));
    }

    /** Reserves an undo execution or replays an existing one. */
    private UndoAttempt reserveOrRecover(
            String ownerKey, long accountId, UUID actionPublicId, String rawIntent) {
        Instant now = clock.instant();
        UserAction action = actions.findForUpdateByPublicIdAndAccountId(
            actionPublicId, accountId).orElseThrow(UndoService::notFound);
        List<ActionExecutionTarget> applied =
            targets.findForwardSucceededTargets(action.getId(), accountId);
        List<Integer> ordinals = applied.stream()
            .map(ActionExecutionTarget::getScopeOrdinal).toList();
        ActionScope scope = verifyOrdinals(action, ordinals);
        ActionCommandToken token = tokens.findForConsume(
            accountId, ActionFingerprints.sha256(rawIntent)).orElseThrow(
                UndoService::stale);
        if (!token.getOwnerFingerprint().equals(
                    ActionFingerprints.sha256(ownerKey))
                || !token.getOperationFingerprint().equals(
                    ActionFingerprints.sha256("UNDO"))
                || !token.getSubjectFingerprint().equals(subject(action, applied))) {
            throw stale();
        }
        ActionExecution existing = executions.findByActionIdAndOperationKind(
            action.getId(), OperationKind.UNDO.name()).orElse(null);
        if (existing != null) {
            if (token.getConsumedAt() == null
                    || !action.getId().equals(token.getResultingActionId())
                    || !existing.getId().equals(token.getResultingExecutionId())) {
                throw stale();
            }
            return new UndoAttempt(null, existing.getId());
        }
        assertEligible(action, applied, now);
        if (token.getConsumedAt() != null || !now.isBefore(token.getExpiresAt())) {
            throw stale();
        }
        String lease = ActionFingerprints.bind(
            "undo-lease-v1", UUID.randomUUID(), accountId, action.getId(), now);
        ActionExecution execution = executions.saveAndFlush(ActionExecution.reserve(
            accountId, action.getId(), OperationKind.UNDO,
            ActionFingerprints.bind("undo-idempotency-v1", rawIntent),
            lease, applied.size(), now, now.plus(LEASE)));
        Map<Integer, ActionScopeTarget> indexed = new LinkedHashMap<>();
        scopeTargets.findByScopeIdAndAccountIdOrderByScopeOrdinal(
            scope.getId(), accountId).forEach(row ->
                indexed.put(row.getScopeOrdinal(), row));
        List<ActionExecutionTarget> undoTargets = ordinals.stream()
            .map(indexed::get).map(row ->
                ActionExecutionTarget.pending(execution.getId(), row)).toList();
        targets.saveAll(undoTargets);
        execution.start(now);
        executions.save(execution);
        undoTargets.forEach(row -> row.markDispatched(now));
        targets.saveAll(undoTargets);
        token.consume(action.getId(), now);
        token.linkExecution(execution.getId());
        tokens.save(token);
        events.save(ActionEvent.from(
            action, execution, "UNDO_RESERVED", null, now));
        UserAccount account = accounts.findById(accountId).orElseThrow(
            UndoService::notFound);
        List<String> ids = ordinals.stream().map(scope.getMessageIds()::get).toList();
        return new UndoAttempt(new ReservedUndo(
            action, account, execution.getId(), lease, ids, ordinals), null);
    }

    /** Builds the undo result from an existing execution. */
    private UndoResult result(
            long accountId, UUID actionPublicId, long executionId) {
        UserAction action = actions.findByPublicIdAndAccountId(
            actionPublicId, accountId).orElseThrow(UndoService::notFound);
        ActionExecution execution = executions.findById(executionId)
            .filter(row -> row.getAccountId() == accountId
                && row.getActionId().equals(action.getId()))
            .orElseThrow(UndoService::notFound);
        return new UndoResult(
            actionPublicId, execution.getRequestedCount(),
            execution.getSucceededCount(), execution.getFailedCount(),
            execution.getVerificationPendingCount(),
            execution.executionStatus() == com.tidymail.action.ExecutionStatus.RUNNING
                ? "IN_PROGRESS" : action.getStatus());
    }

    /** Completes the undo execution and updates the action status. */
    private UndoResult complete(ReservedUndo reserved, BulkActionResult rawResult) {
        Instant now = clock.instant();
        ActionExecution execution = executions.findForUpdate(
            reserved.executionId(), reserved.account().getId()).orElseThrow(
                UndoService::notFound);
        if (!reserved.lease().equals(execution.getLeaseFingerprint())
                || !now.isBefore(execution.getLeaseExpiresAt())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "stale_execution_lease");
        }
        LinkedHashMap<String, Integer> requested = new LinkedHashMap<>();
        for (int index = 0; index < reserved.ids().size(); index++) {
            requested.put(reserved.ids().get(index), reserved.ordinals().get(index));
        }
        ProviderResultClassifier.ClassifiedResult classified = rawResult == null
            ? ProviderResultClassifier.afterDispatchException(requested)
            : ProviderResultClassifier.classify(requested, rawResult);
        List<ActionExecutionTarget> rows =
            targets.findByExecutionIdAndAccountIdOrderByScopeOrdinal(
                execution.getId(), reserved.account().getId());
        for (ActionExecutionTarget row : rows) {
            var fact = classified.targets().get(row.getScopeOrdinal());
            ExecutionTargetStatus status =
                fact.status() == ExecutionTargetStatus.SUCCEEDED
                    ? ExecutionTargetStatus.REVERTED : fact.status();
            row.complete(status, fact.accepted(), fact.reason(), now);
        }
        targets.saveAll(rows);
        ExecutionFacts facts = classified.facts();
        execution.complete(
            facts.status(), facts.accepted(), facts.succeeded(), facts.failed(),
            facts.verificationPending(), facts.notDispatched(), facts.reason(), now);
        executions.save(execution);
        UserAction action = actions.findForUpdateByIdAndAccountId(
            reserved.action().getId(), reserved.account().getId()).orElseThrow(
                UndoService::notFound);
        boolean allReverted = facts.succeeded() == facts.requested();
        action.setStatus(allReverted
            ? ActionStatus.UNDONE.name() : ActionStatus.PARTIALLY_UNDONE.name());
        action.setUndoEligibleUntil(null);
        action.setCompletedAt(now);
        if (allReverted) action.setUndoneAt(now);
        actions.save(action);
        events.save(ActionEvent.from(
            action, execution, "UNDO_COMPLETED", facts.reason(), now));
        return new UndoResult(
            action.getPublicId(), facts.requested(), facts.succeeded(),
            facts.failed(), facts.verificationPending(), action.getStatus());
    }

    /** Loads and validates an action eligible for undo. */
    private UserAction eligibleAction(
            long accountId, UUID actionPublicId, Instant now) {
        UserAction action = actions.findForUpdateByPublicIdAndAccountId(
            actionPublicId, accountId).orElseThrow(UndoService::notFound);
        List<ActionExecutionTarget> applied =
            targets.findForwardSucceededTargets(action.getId(), accountId);
        assertEligible(action, applied, now);
        return action;
    }

    /** Requires the action to be within its undo window and not already undone. */
    private void assertEligible(
            UserAction action, List<ActionExecutionTarget> applied, Instant now) {
        if (action.getActionScopeId() == null || !TYPES.contains(action.getActionType())
                || action.getUndoEligibleUntil() == null
                || !now.isBefore(action.getUndoEligibleUntil())
                || !List.of("APPLIED", "PARTIAL").contains(action.getStatus())
                || executions.findByActionIdAndOperationKind(
                    action.getId(), OperationKind.UNDO.name()).isPresent()
                || applied.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "undo_unavailable");
        }
    }

    /** Verifies the applied target ordinals match the scope. */
    private ActionScope verifyOrdinals(UserAction action, List<Integer> ordinals) {
        ActionScope scope = scopes.requireScope(
            action.getAccountId(), action.getActionScopeId());
        scopes.assertCompatible(action);
        Map<Integer, String> fingerprints = new LinkedHashMap<>();
        scopeTargets.findByScopeIdAndAccountIdOrderByScopeOrdinal(
            scope.getId(), action.getAccountId()).forEach(row ->
                fingerprints.put(row.getScopeOrdinal(), row.getTargetFingerprint()));
        if (ordinals.isEmpty() || ordinals.stream().distinct().count() != ordinals.size()
                || ordinals.stream().anyMatch(ordinal -> ordinal < 0
                    || ordinal >= scope.getMessageIds().size()
                    || !ActionFingerprints.sha256(scope.getMessageIds().get(ordinal))
                        .equals(fingerprints.get(ordinal)))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "action_scope_incompatible");
        }
        return scope;
    }

    /** Computes the undo-intent subject fingerprint. */
    private static String subject(
            UserAction action, List<ActionExecutionTarget> applied) {
        return ActionFingerprints.bind(
            "undo-subject-v1", action.getPublicId(), action.getActionType(),
            applied.stream().map(row ->
                row.getScopeOrdinal() + ":" + row.getTargetFingerprint()).toList());
    }

    /** Computes the inverse provider operation for the action type. */
    private BulkActionResult inverse(MailProvider provider, ReservedUndo reserved) {
        return switch (reserved.action().getActionType()) {
            case "ARCHIVE" -> provider.applyLabel(
                reserved.account(), reserved.ids(), "INBOX", true);
            case "TRASH" -> provider.applyLabel(
                reserved.account(), reserved.ids(), "TRASH", false);
            case "MARK_READ" -> provider.batchModify(
                reserved.account(),
                BulkActionRequest.of(BulkActionType.MARK_UNREAD, reserved.ids()));
            default -> throw new ResponseStatusException(
                HttpStatus.CONFLICT, "undo_unavailable");
        };
    }

    /** Resolves the mail provider bean for the account. */
    private MailProvider provider(UserAccount account) {
        String bean = switch (account.getProvider()) {
            case "google" -> "gmailMailService";
            case "microsoft" -> "outlookMailService";
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unknown_provider");
        };
        MailProvider provider = providers.get(bean);
        if (provider == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable");
        }
        return provider;
    }

    /** The bulk actions that support undo. */
    public enum BulkAction { ARCHIVE, TRASH, MARK_READ }

    /** Returns the capped size of an ID list (max 1000). */
    public static int cap1000Size(List<?> ids) {
        return Math.min(1000, ids.size());
    }

    /** Caps an ID list to 1000, reporting the original size. */
    public static CapResult cap1000(List<String> ids) {
        int cap = cap1000Size(ids);
        return new CapResult(ids.stream().limit(cap).toList(), ids.size(), cap);
    }

    /** Builds the inverse-action metadata for an action row. */
    public static Map<String, Object> buildInverseActionData(
            BulkAction forward, List<String> messageIds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inverseType", switch (forward) {
            case ARCHIVE -> "INBOX_ADD";
            case TRASH -> "UNTRASH";
            case MARK_READ -> "MARK_UNREAD";
        });
        data.put("messageIds", List.copyOf(messageIds));
        data.put("forwardType", forward.name());
        data.put("formatVersion", 1);
        return data;
    }

    /** Creates a queued action row with inverse data (legacy helper). */
    public UserAction createActionRow(
            Long accountId, BulkAction action, String fromAddress,
            List<String> messageIds) {
        UserAction row = new UserAction();
        row.setAccountId(accountId);
        row.setActionType(action.name());
        row.setSenderGroupKey(fromAddress);
        row.setTargetKind("SENDER_ADDRESS");
        row.setTargetIdJson(Map.of("fromAddress", fromAddress));
        row.setMessageIds(new ArrayList<>(messageIds));
        row.setAffectedCount(messageIds.size());
        row.setInverseActionData(buildInverseActionData(action, messageIds));
        row.setStatus("QUEUED");
        row.setExpiresAt(null);
        return actions.save(row);
    }

    /** Returns true if the action is currently undoable. */
    public static boolean isUndoable(UserAction row, Instant now) {
        return row != null && row.getActionScopeId() != null
            && row.getUndoEligibleUntil() != null
            && now.isBefore(row.getUndoEligibleUntil())
            && TYPES.contains(row.getActionType());
    }

    /** Legacy undo entry point; reports that applied detail was not recorded. */
    public void executeUndo(UserAccount account, MailProvider provider, UserAction row) {
        throw new ResponseStatusException(
            HttpStatus.GONE,
            "Undo unavailable - applied target detail was not recorded.");
    }

    /** Returns the human-readable label for an inverse action. */
    private static String inverseLabel(String actionType) {
        return switch (actionType) {
            case "ARCHIVE" -> "Restore to Inbox";
            case "TRASH" -> "Remove from Trash";
            case "MARK_READ" -> "Mark unread";
            default -> throw new IllegalArgumentException("Unsupported inverse");
        };
    }

    /** Formats an enum-style value as a human-readable label. */
    private static String visible(String value) {
        String normalized = value.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    /** Formats an account label with its provider name. */
    private static String accountLabel(UserAccount account) {
        String provider = switch (account.getProvider()) {
            case "google" -> "Google";
            case "microsoft" -> "Microsoft Outlook";
            default -> visible(account.getProvider());
        };
        return account.getEmail() + " (" + provider + ")";
    }

    /** Throws a conflict when the undo intent is stale. */
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "undo_intent_stale");
    }

    /** Returns a not-found exception for a missing action. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
    }

    /** A reserved undo execution and its dispatch facts. */
    private record ReservedUndo(
        UserAction action, UserAccount account, long executionId, String lease,
        List<String> ids, List<Integer> ordinals
    ) {
    }

    /** A reserved undo or an existing execution to replay. */
    private record UndoAttempt(
        ReservedUndo reserved, Long existingExecutionId
    ) {
    }

    /** The result of capping an ID list. */
    public record CapResult(
        List<String> messageIds, int cappedOriginal, int actualApplied
    ) {
    }

    /** The result of executing an undo. */
    public record UndoResult(
        UUID actionPublicId, int requested, int reverted, int failed,
        int verificationPending, String status
    ) {
    }
}
