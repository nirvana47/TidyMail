package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Executes queued actions against their exact materialized scopes by reserving an
 * execution, dispatching to the appropriate {@link ActionHandler}, and recording
 * the completion. Supports single and batch execution with idempotency.
 *
 * <p>Product functionality supported: the action execution engine that applies
 * cleanup, protect, review, and unsubscribe actions.
 */
@Service
public class ExactScopeExecutor {
    private final ExecutionReservationService reservations;
    private final ExecutionCompletionService completions;
    private final ActionScopeService scopes;
    private final UserAccountRepository accounts;
    private final ActionExecutionRepository executions;
    private final UserActionRepository actions;
    private final ActionIntentRepository intents;
    private final Map<ActionType, ActionHandler> handlers;

    public ExactScopeExecutor(
            ExecutionReservationService reservations,
            ExecutionCompletionService completions,
            ActionScopeService scopes,
            UserAccountRepository accounts,
            ActionExecutionRepository executions,
            UserActionRepository actions,
            ActionIntentRepository intents,
            List<ActionHandler> handlers) {
        this.reservations = reservations;
        this.completions = completions;
        this.scopes = scopes;
        this.accounts = accounts;
        this.executions = executions;
        this.actions = actions;
        this.intents = intents;
        EnumMap<ActionType, ActionHandler> registry = new EnumMap<>(ActionType.class);
        for (ActionType type : List.of(
                ActionType.ARCHIVE, ActionType.TRASH, ActionType.MARK_READ,
                ActionType.KEEP, ActionType.PROTECT, ActionType.REVIEW,
                ActionType.UNSUBSCRIBE, ActionType.REMOVE_PROTECTION)) {
            List<ActionHandler> matches = handlers.stream()
                .filter(candidate -> candidate.supports(type)).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException(
                    "Expected one handler for " + type + ", found " + matches.size());
            }
            registry.put(type, matches.getFirst());
        }
        this.handlers = Map.copyOf(registry);
    }

    /** Executes a single action, returning its receipt. */
    public ExecutionReceipt execute(
            long accountId, UUID actionPublicId, UUID idempotencyToken) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Executor entry must not have a transaction");
        }
        var reservation = reservations.reserve(
            accountId, actionPublicId, idempotencyToken);
        return dispatch(reservation);
    }

    /** Dispatches a reservation to its handler and records completion. */
    private ExecutionReceipt dispatch(
            ExecutionReservationService.Reservation reservation) {
        long accountId = reservation.accountId();
        if (!reservation.dispatchRequired()) {
            var existing = executions.findById(reservation.executionId()).orElseThrow();
            return new ExecutionReceipt(
                existing.getPublicId(), reservation.actionPublicId(),
                existing.executionStatus().name(), existing.getRequestedCount(),
                existing.getSucceededCount(), existing.getFailedCount(),
                existing.getVerificationPendingCount(), false);
        }

        UserAccount account = accounts.findById(accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "account_not_found"));
        ActionScope scope = scopes.requireScope(accountId, reservation.scopeId());
        ActionType actionType = ActionType.valueOf(reservation.actionType());
        ActionHandler handler = handlers.get(actionType);
        if (handler == null) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_action");
        }
        var action = actions.findByIdAndAccountId(
            reservation.actionId(), accountId).orElseThrow();
        var intent = intents.findByActionIdAndAccountId(
            reservation.actionId(), accountId).orElseThrow();
        LinkedHashMap<String, Integer> ordinals = new LinkedHashMap<>();
        for (int index = 0; index < scope.getMessageIds().size(); index++) {
            ordinals.put(scope.getMessageIds().get(index), index);
        }
        if (ordinals.size() != scope.getMessageIds().size()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "scope_target_index_incompatible");
        }
        var dispatched = reservations.markDispatched(
            accountId, reservation.executionId(), reservation.leaseFingerprint());

        ActionHandler.HandlerResult handlerResult;
        try {
            handlerResult = handler.execute(new ActionHandler.ExecutionContext(
                account, action, scope, intent, actionType,
                scope.getMessageIds(), ordinals));
        } catch (RuntimeException unknownAfterDispatch) {
            handlerResult = localAction(actionType)
                ? ActionHandler.HandlerResult.local(
                    ProviderResultClassifier.failed(
                        ordinals,
                        com.tidymail.action.ActionReasonCode.STALE_ACTION))
                : ActionHandler.HandlerResult.local(
                    ProviderResultClassifier.afterDispatchException(ordinals));
        }
        var completion = completions.complete(
            accountId, dispatched.executionId(), dispatched.leaseFingerprint(),
            handlerResult);
        return new ExecutionReceipt(
            completion.executionPublicId(), completion.actionPublicId(),
            completion.actionStatus(), completion.facts().requested(),
            completion.facts().succeeded(), completion.facts().failed(),
            completion.facts().verificationPending(), true);
    }

    /** Returns true if the action is handled locally (not a provider mutation). */
    private static boolean localAction(ActionType actionType) {
        return actionType == ActionType.KEEP
            || actionType == ActionType.PROTECT
            || actionType == ActionType.REVIEW
            || actionType == ActionType.REMOVE_PROTECTION;
    }

    /** Executes a batch of actions for one account. */
    public List<ExecutionReceipt> executeBatch(
            long accountId, List<UUID> actionPublicIds, List<UUID> idempotencyTokens) {
        if (actionPublicIds.size() != idempotencyTokens.size()
                || actionPublicIds.isEmpty()) {
            throw new IllegalArgumentException("Action/token pairs are required");
        }
        java.util.ArrayList<BatchAction> actions = new java.util.ArrayList<>();
        for (int index = 0; index < actionPublicIds.size(); index++) {
            actions.add(new BatchAction(
                accountId, actionPublicIds.get(index), idempotencyTokens.get(index)));
        }
        return executeBatch(actions);
    }

    /** Executes a batch of actions, returning their receipts. */
    public List<ExecutionReceipt> executeBatch(List<BatchAction> actions) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Executor entry must not have a transaction");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("Batch actions are required");
        }
        List<ExecutionReservationService.Reservation> reserved =
            reservations.reserveBatch(actions.stream().map(action ->
                new ExecutionReservationService.ReservationRequest(
                    action.accountId(), action.actionPublicId(),
                    action.idempotencyToken())).toList());
        return reserved.stream().map(this::dispatch).toList();
    }

    /** One action to execute in a batch. */
    public record BatchAction(
        long accountId,
        UUID actionPublicId,
        UUID idempotencyToken
    ) {
    }

    /** The result of executing an action. */
    public record ExecutionReceipt(
        UUID executionPublicId,
        UUID actionPublicId,
        String status,
        int requested,
        int succeeded,
        int failed,
        int verificationPending,
        boolean dispatched
    ) {
    }
}
