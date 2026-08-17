package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ActionStatus;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.ExecutionTargetStatus;
import com.tidymail.action.OperationKind;
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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recovers executions whose lease expired before completion: renews the lease for
 * undelivered work, or marks dispatched targets as verification-pending so the
 * outcome can be resolved later.
 *
 * <p>Product functionality supported: the action execution engine's crash/lease
 * recovery and verification-pending handling.
 */
@Service
public class ExecutionRecoveryService {
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final ActionExecutionRepository executions;
    private final ActionExecutionTargetRepository targets;
    private final UserActionRepository actions;
    private final ActionEventRepository events;
    private final Clock clock;

    @Autowired
    public ExecutionRecoveryService(
            ActionExecutionRepository executions,
            ActionExecutionTargetRepository targets,
            UserActionRepository actions,
            ActionEventRepository events) {
        this(executions, targets, actions, events, Clock.systemUTC());
    }

    ExecutionRecoveryService(
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

    /** Recovers an execution with an expired lease, returning its state. */
    @Transactional
    public Recovery recover(long accountId, long executionId) {
        ActionExecution execution = executions.findForUpdate(executionId, accountId)
            .orElseThrow(ExecutionRecoveryService::notFound);
        UserAction action = actions.findForUpdateByIdAndAccountId(
            execution.getActionId(), accountId).orElseThrow(
                ExecutionRecoveryService::notFound);
        Instant now = clock.instant();
        if (execution.getLeaseFingerprint() == null
                || execution.getLeaseExpiresAt() == null
                || now.isBefore(execution.getLeaseExpiresAt())) {
            return result(execution, action, false);
        }
        List<ActionExecutionTarget> rows =
            targets.findByExecutionIdAndAccountIdOrderByScopeOrdinal(
                executionId, accountId);
        if (rows.size() != execution.getRequestedCount()
                || rows.stream().anyMatch(row ->
                    row.targetStatus() != ExecutionTargetStatus.PENDING)) {
            throw incompatible();
        }

        if (execution.executionStatus() == ExecutionStatus.RESERVED) {
            if (rows.stream().anyMatch(row ->
                    !"NOT_DISPATCHED".equals(row.getDispatchState()))) {
                throw incompatible();
            }
            String replacement = lease(accountId, action.getId(), now);
            execution.renewReservationLease(replacement, now, now.plus(LEASE));
            executions.save(execution);
            events.save(ActionEvent.from(
                action, execution, "EXECUTION_RESERVED",
                ActionReasonCode.LEASE_EXPIRED, now));
            return result(execution, action, true);
        }

        if (execution.executionStatus() != ExecutionStatus.RUNNING) {
            return result(execution, action, false);
        }
        int dispatched = 0;
        for (ActionExecutionTarget row : rows) {
            if ("DISPATCHED".equals(row.getDispatchState())) {
                row.complete(
                    ExecutionTargetStatus.VERIFICATION_PENDING, false,
                    ActionReasonCode.LEASE_EXPIRED, now);
                dispatched++;
            } else if (!"NOT_DISPATCHED".equals(row.getDispatchState())) {
                throw incompatible();
            }
        }
        if (dispatched == 0) {
            String replacement = lease(accountId, action.getId(), now);
            execution.renewRunningLease(replacement, now, now.plus(LEASE));
            executions.save(execution);
            events.save(ActionEvent.from(
                action, execution, "EXECUTION_RESERVED",
                ActionReasonCode.LEASE_EXPIRED, now));
            return result(execution, action, true);
        }

        int notDispatched = rows.size() - dispatched;
        targets.saveAll(rows);
        execution.complete(
            ExecutionStatus.VERIFICATION_PENDING, 0, 0, 0,
            dispatched, notDispatched, ActionReasonCode.LEASE_EXPIRED, now);
        executions.save(execution);
        boolean undo = execution.operationKind() == OperationKind.UNDO;
        action.setStatus(undo
            ? ActionStatus.PARTIALLY_UNDONE.name()
            : ActionStatus.VERIFICATION_PENDING.name());
        action.setCompletedAt(now);
        action.setUndoEligibleUntil(null);
        actions.save(action);
        events.save(ActionEvent.from(
            action, execution, undo ? "UNDO_COMPLETED" : "EXECUTION_COMPLETED",
            ActionReasonCode.LEASE_EXPIRED, now));
        return result(execution, action, false);
    }

    /** Builds the recovery result for an execution. */
    private static Recovery result(
            ActionExecution execution, UserAction action, boolean resume) {
        return new Recovery(
            execution.getId(), execution.getPublicId(), action.getId(),
            action.getPublicId(), action.getAccountId(), action.getActionScopeId(),
            action.getActionType(), execution.getLeaseFingerprint(), resume,
            execution.executionStatus().name());
    }

    /** Generates a fresh lease fingerprint. */
    private static String lease(long accountId, long actionId, Instant now) {
        return ActionFingerprints.bind(
            "execution-lease-v1", UUID.randomUUID(), accountId, actionId, now);
    }

    /** Throws a conflict when the execution targets are incompatible. */
    private static ResponseStatusException incompatible() {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "execution_target_incompatible");
    }

    /** Returns a not-found exception for a missing execution. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(
            HttpStatus.NOT_FOUND, "execution_not_found");
    }

    /** The recovered execution state and whether dispatch is required. */
    public record Recovery(
        long executionId,
        UUID executionPublicId,
        long actionId,
        UUID actionPublicId,
        long accountId,
        long scopeId,
        String actionType,
        String leaseFingerprint,
        boolean dispatchRequired,
        String status
    ) {
    }
}
