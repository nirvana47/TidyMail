package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ActionStatus;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionExecution;
import com.tidymail.entity.ActionExecutionTarget;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.UserActionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records the completion of an execution: applies per-target results, updates the
 * execution and action statuses, sets undo eligibility, and logs the event.
 *
 * <p>Product functionality supported: the action execution engine's completion
 * and undo-window tracking.
 */
@Service
public class ExecutionCompletionService {
    private static final Duration UNDO_WINDOW = Duration.ofMinutes(10);

    private final ActionExecutionRepository executions;
    private final ActionExecutionTargetRepository targets;
    private final UserActionRepository actions;
    private final ActionEventRepository events;
    private final Clock clock;

    @Autowired
    public ExecutionCompletionService(
            ActionExecutionRepository executions,
            ActionExecutionTargetRepository targets,
            UserActionRepository actions,
            ActionEventRepository events) {
        this(executions, targets, actions, events, Clock.systemUTC());
    }

    ExecutionCompletionService(
            ActionExecutionRepository executions,
            ActionExecutionTargetRepository targets,
            UserActionRepository actions,
            ActionEventRepository events,
            Clock clock) {
        this.executions = executions;
        this.targets = targets;
        this.actions = actions;
        this.events = events;
        this.clock = clock;
    }

    /** Completes an execution from the handler result, updating all state. */
    @Transactional
    public Completion complete(
            long accountId, long executionId, String lease,
            ActionHandler.HandlerResult handlerResult) {
        var result = handlerResult.classified();
        ActionExecution execution = executions.findForUpdate(executionId, accountId)
            .orElseThrow(ExecutionCompletionService::notFound);
          Instant now = clock.instant();
          if (lease == null || !lease.equals(execution.getLeaseFingerprint())
                  || execution.getLeaseExpiresAt() == null
                  || !now.isBefore(execution.getLeaseExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale_execution_lease");
        }
        List<ActionExecutionTarget> rows = targets
            .findByExecutionIdAndAccountIdOrderByScopeOrdinal(executionId, accountId);
        if (rows.size() != execution.getRequestedCount()
                || result.targets().size() != rows.size()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "execution_target_incompatible");
        }
        for (ActionExecutionTarget row : rows) {
            var fact = result.targets().get(row.getScopeOrdinal());
            if (fact == null || !"DISPATCHED".equals(row.getDispatchState())) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "execution_target_incompatible");
            }
            row.complete(fact.status(), fact.accepted(), fact.reason(), now);
            if (handlerResult.unsubscribeAttemptId() != null) {
                if (rows.size() != 1) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "execution_target_incompatible");
                }
                row.linkUnsubscribeAttempt(handlerResult.unsubscribeAttemptId());
            }
        }
        targets.saveAll(rows);

        ExecutionFacts facts = result.facts();
        ActionReasonCode reason = result.contractInvalid()
            ? ActionReasonCode.PROVIDER_RESULT_INVALID : facts.reason();
        execution.complete(
            facts.status(), facts.accepted(), facts.succeeded(), facts.failed(),
            facts.verificationPending(), facts.notDispatched(), reason, now);
        executions.save(execution);

        UserAction action = actions.findForUpdateByIdAndAccountId(
            execution.getActionId(), accountId).orElseThrow(
                ExecutionCompletionService::notFound);
        ActionStatus finalActionStatus = handlerResult.actionStatusOverride() == null
            ? actionStatus(facts.status()) : handlerResult.actionStatusOverride();
        action.setStatus(finalActionStatus.name());
        action.setCompletedAt(now);
        action.setAppliedAt(
            facts.succeeded() > 0 && finalActionStatus != ActionStatus.BLOCKED
                ? now : null);
        action.setUndoEligibleUntil(
            handlerResult.undoEligible() && facts.succeeded() > 0
                ? now.plus(UNDO_WINDOW) : null);
        actions.save(action);
        events.save(ActionEvent.from(
            action, execution, "EXECUTION_COMPLETED", reason, now));
        return new Completion(
            execution.getPublicId(), action.getPublicId(), facts, action.getStatus());
    }

    /** Maps an execution status to the corresponding action status. */
    private static ActionStatus actionStatus(ExecutionStatus status) {
        return switch (status) {
            case APPLIED -> ActionStatus.APPLIED;
            case PARTIAL -> ActionStatus.PARTIAL;
            case VERIFICATION_PENDING -> ActionStatus.VERIFICATION_PENDING;
            case FAILED -> ActionStatus.FAILED;
            case RESERVED, RUNNING -> throw new IllegalArgumentException(
                "Execution is not complete");
        };
    }

    /** Returns a not-found exception for a missing execution. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "execution_not_found");
    }

    /** The result of completing an execution. */
    public record Completion(
        java.util.UUID executionPublicId,
        java.util.UUID actionPublicId,
        ExecutionFacts facts,
        String actionStatus
    ) {
    }
}
