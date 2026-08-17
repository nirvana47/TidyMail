package com.tidymail.entity;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.OperationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "action_executions")
public class ActionExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "action_id", nullable = false)
    private Long actionId;
    @Column(name = "operation_kind", nullable = false, length = 16)
    private String operationKind;
    @Column(name = "status", nullable = false, length = 32)
    private String status;
    @Column(name = "idempotency_fingerprint", nullable = false, length = 64)
    private String idempotencyFingerprint;
    @Column(name = "lease_fingerprint", length = 64)
    private String leaseFingerprint;
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
    @Column(name = "requested_count", nullable = false)
    private Integer requestedCount;
    @Column(name = "accepted_count", nullable = false)
    private Integer acceptedCount;
    @Column(name = "succeeded_count", nullable = false)
    private Integer succeededCount;
    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;
    @Column(name = "verification_pending_count", nullable = false)
    private Integer verificationPendingCount;
    @Column(name = "not_dispatched_count", nullable = false)
    private Integer notDispatchedCount;
    @Column(name = "reason_code", length = 48)
    private String reasonCode;
    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected ActionExecution() {
    }

    public static ActionExecution reserve(
            long accountId, long actionId, OperationKind operation,
            String idempotencyFingerprint, String leaseFingerprint,
            int requested, Instant now, Instant leaseExpiresAt) {
        if (accountId <= 0 || actionId <= 0 || requested <= 0
                || idempotencyFingerprint == null
                || !idempotencyFingerprint.matches("[0-9a-f]{64}")
                || leaseFingerprint == null || !leaseFingerprint.matches("[0-9a-f]{64}")
                || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid execution reservation");
        }
        ActionExecution execution = new ActionExecution();
        execution.publicId = UUID.randomUUID();
        execution.accountId = accountId;
        execution.actionId = actionId;
        execution.operationKind = operation.name();
        execution.status = ExecutionStatus.RESERVED.name();
        execution.idempotencyFingerprint = idempotencyFingerprint;
        execution.leaseFingerprint = leaseFingerprint;
        execution.leaseExpiresAt = leaseExpiresAt;
        execution.requestedCount = requested;
        execution.acceptedCount = 0;
        execution.succeededCount = 0;
        execution.failedCount = 0;
        execution.verificationPendingCount = 0;
        execution.notDispatchedCount = requested;
        execution.reservedAt = now;
        return execution;
    }

    public void start(Instant now) {
        if (executionStatus() != ExecutionStatus.RESERVED
                || leaseExpiresAt == null || !now.isBefore(leaseExpiresAt)) {
            throw new IllegalStateException("Execution lease is not dispatchable");
        }
        status = ExecutionStatus.RUNNING.name();
        startedAt = now;
    }

    public void renewReservationLease(
            String replacementFingerprint, Instant now, Instant replacementExpiry) {
        if (executionStatus() != ExecutionStatus.RESERVED
                || leaseExpiresAt == null || now.isBefore(leaseExpiresAt)
                || replacementFingerprint == null
                || !replacementFingerprint.matches("[0-9a-f]{64}")
                || !replacementExpiry.isAfter(now)) {
            throw new IllegalStateException("Execution lease cannot be renewed");
        }
        leaseFingerprint = replacementFingerprint;
        leaseExpiresAt = replacementExpiry;
    }

    public void renewRunningLease(
            String replacementFingerprint, Instant now, Instant replacementExpiry) {
        if (executionStatus() != ExecutionStatus.RUNNING
                || leaseExpiresAt == null || now.isBefore(leaseExpiresAt)
                || replacementFingerprint == null
                || !replacementFingerprint.matches("[0-9a-f]{64}")
                || !replacementExpiry.isAfter(now)) {
            throw new IllegalStateException("Running execution lease cannot be renewed");
        }
        leaseFingerprint = replacementFingerprint;
        leaseExpiresAt = replacementExpiry;
    }

    public void complete(
            ExecutionStatus status, int accepted, int succeeded, int failed,
            int pending, int notDispatched, ActionReasonCode reason, Instant now) {
        if (accepted < 0 || accepted > requestedCount || succeeded < 0 || failed < 0
                || pending < 0 || notDispatched < 0
                || requestedCount != succeeded + failed + pending + notDispatched) {
            throw new IllegalArgumentException("Invalid execution count partition");
        }
        this.status = status.name();
        acceptedCount = accepted;
        succeededCount = succeeded;
        failedCount = failed;
        verificationPendingCount = pending;
        notDispatchedCount = notDispatched;
        reasonCode = reason == null ? null : reason.name();
        completedAt = now;
        leaseFingerprint = null;
        leaseExpiresAt = null;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getAccountId() { return accountId; }
    public Long getActionId() { return actionId; }
    public OperationKind operationKind() { return OperationKind.valueOf(operationKind); }
    public ExecutionStatus executionStatus() { return ExecutionStatus.valueOf(status); }
    public String getIdempotencyFingerprint() { return idempotencyFingerprint; }
    public String getLeaseFingerprint() { return leaseFingerprint; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Integer getRequestedCount() { return requestedCount; }
    public Integer getAcceptedCount() { return acceptedCount; }
    public Integer getSucceededCount() { return succeededCount; }
    public Integer getFailedCount() { return failedCount; }
    public Integer getVerificationPendingCount() { return verificationPendingCount; }
    public Integer getNotDispatchedCount() { return notDispatchedCount; }
    public String getReasonCode() { return reasonCode; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
