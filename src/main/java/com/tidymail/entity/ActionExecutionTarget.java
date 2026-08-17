package com.tidymail.entity;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ExecutionTargetStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "action_execution_targets")
public class ActionExecutionTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "execution_id", nullable = false)
    private Long executionId;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;
    @Column(name = "scope_ordinal", nullable = false)
    private Integer scopeOrdinal;
    @Column(name = "target_fingerprint", nullable = false, length = 64)
    private String targetFingerprint;
    @Column(name = "dispatch_state", nullable = false, length = 16)
    private String dispatchState;
    @Column(name = "status", nullable = false, length = 32)
    private String status;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "reason_code", length = 48)
    private String reasonCode;
    @Column(name = "unsubscribe_attempt_id")
    private Long unsubscribeAttemptId;

    protected ActionExecutionTarget() {
    }

    public static ActionExecutionTarget pending(
            long executionId, ActionScopeTarget target) {
        ActionExecutionTarget row = new ActionExecutionTarget();
        row.executionId = executionId;
        row.accountId = target.getAccountId();
        row.scopeId = target.getScopeId();
        row.scopeOrdinal = target.getScopeOrdinal();
        row.targetFingerprint = target.getTargetFingerprint();
        row.dispatchState = "NOT_DISPATCHED";
        row.status = ExecutionTargetStatus.PENDING.name();
        return row;
    }

    public void markDispatched(Instant now) {
        dispatchState = "DISPATCHED";
        dispatchedAt = now;
    }

    public void complete(
            ExecutionTargetStatus status, boolean accepted,
            ActionReasonCode reason, Instant now) {
        if (status == ExecutionTargetStatus.PENDING) {
            throw new IllegalArgumentException("Completion must be terminal");
        }
        this.status = status.name();
        acceptedAt = accepted ? now : null;
        reasonCode = reason == null ? null : reason.name();
        completedAt = now;
    }

    public void linkUnsubscribeAttempt(long attemptId) {
        if (attemptId <= 0 || unsubscribeAttemptId != null) {
            throw new IllegalArgumentException("Invalid unsubscribe attempt link");
        }
        unsubscribeAttemptId = attemptId;
    }

    public Long getId() { return id; }
    public Long getExecutionId() { return executionId; }
    public Long getAccountId() { return accountId; }
    public Long getScopeId() { return scopeId; }
    public Integer getScopeOrdinal() { return scopeOrdinal; }
    public String getTargetFingerprint() { return targetFingerprint; }
    public String getDispatchState() { return dispatchState; }
    public ExecutionTargetStatus targetStatus() {
        return ExecutionTargetStatus.valueOf(status);
    }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getReasonCode() { return reasonCode; }
    public Long getUnsubscribeAttemptId() { return unsubscribeAttemptId; }
}
