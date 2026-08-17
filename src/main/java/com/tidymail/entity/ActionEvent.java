package com.tidymail.entity;

import com.tidymail.action.ActionReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Immutable record of a single lifecycle event for a user action (queued, dispatched,
 * verified, etc.), capturing the counts and decision facts at the moment the event occurred.
 * <p>Product functionality: action history/events — powers the audit trail and
 * progress reporting for cleanup actions.
 */
@Entity
@Immutable
@Table(name = "action_events")
public class ActionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "action_id", nullable = false)
    private Long actionId;
    @Column(name = "execution_id")
    private Long executionId;
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;
    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;
    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;
    @Column(name = "decision_source", nullable = false, length = 16)
    private String decisionSource;
    @Column(name = "resulting_status", nullable = false, length = 32)
    private String resultingStatus;
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
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ActionEvent() {
    }

    /**
     * Builds an event snapshot from an action (and optional execution), capturing the
     * counts and decision facts at the moment the event occurred.
     */
    public static ActionEvent from(
            UserAction action, ActionExecution execution, String eventType,
            ActionReasonCode reason, Instant now) {
        ActionEvent event = new ActionEvent();
        event.accountId = action.getAccountId();
        event.actionId = action.getId();
        event.executionId = execution == null ? null : execution.getId();
        event.eventType = eventType;
        event.actionType = action.getActionType();
        event.executionMode = action.getExecutionMode();
        event.decisionSource = action.getDecisionSource();
        event.resultingStatus = action.getStatus();
        event.requestedCount = execution == null ? action.getAffectedCount()
            : execution.getRequestedCount();
        event.acceptedCount = execution == null ? 0 : execution.getAcceptedCount();
        event.succeededCount = execution == null ? 0 : execution.getSucceededCount();
        event.failedCount = execution == null ? 0 : execution.getFailedCount();
        event.verificationPendingCount =
            execution == null ? 0 : execution.getVerificationPendingCount();
        event.notDispatchedCount =
            execution == null ? action.getAffectedCount() : execution.getNotDispatchedCount();
        event.reasonCode = reason == null ? null : reason.name();
        event.occurredAt = now;
        return event;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getActionId() { return actionId; }
    public Long getExecutionId() { return executionId; }
    public String getEventType() { return eventType; }
    public String getActionType() { return actionType; }
    public String getExecutionMode() { return executionMode; }
    public String getDecisionSource() { return decisionSource; }
    public String getResultingStatus() { return resultingStatus; }
    public Integer getRequestedCount() { return requestedCount; }
    public Integer getAcceptedCount() { return acceptedCount; }
    public Integer getSucceededCount() { return succeededCount; }
    public Integer getFailedCount() { return failedCount; }
    public Integer getVerificationPendingCount() { return verificationPendingCount; }
    public Integer getNotDispatchedCount() { return notDispatchedCount; }
    public String getReasonCode() { return reasonCode; }
    public Instant getOccurredAt() { return occurredAt; }
}
