package com.tidymail.entity;

import com.tidymail.unsubscribe.UnsubscribeConfirmationSource;
import com.tidymail.unsubscribe.UnsubscribeOutcomeStatus;
import com.tidymail.unsubscribe.UnsubscribeReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Immutable event recording each outcome transition of an unsubscribe attempt, including
 * the status, reason, HTTP status class, and confirmation source.
 * <p>Product functionality: truncate flow — the event stream/history behind
 * unsubscribe attempt monitoring and diagnostics.
 */
@Entity
@Immutable
@Table(name = "unsubscribe_outcome_events")
public class UnsubscribeOutcomeEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private UnsubscribeOutcomeStatus status;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 48)
    private UnsubscribeReasonCode reasonCode;

    @Column(name = "http_status_class")
    private Short httpStatusClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_source", length = 24)
    private UnsubscribeConfirmationSource confirmationSource;

    protected UnsubscribeOutcomeEvent() {
    }

    /**
     * Records an unsubscribe outcome event from validated facts about the attempt.
     */
    public static UnsubscribeOutcomeEvent create(
            long accountId,
            long attemptId,
            UnsubscribeOutcomeStatus status,
            Instant occurredAt,
            UnsubscribeReasonCode reason,
            Integer statusClass,
            UnsubscribeConfirmationSource source) {
        if (accountId <= 0 || attemptId <= 0 || status == null || occurredAt == null
                || reason == null || statusClass != null && (statusClass < 2 || statusClass > 5)
                || !validOutcomeFacts(status, reason, statusClass, source)) {
            throw new IllegalArgumentException("Invalid unsubscribe outcome event");
        }
        UnsubscribeOutcomeEvent event = new UnsubscribeOutcomeEvent();
        event.accountId = accountId;
        event.attemptId = attemptId;
        event.status = status;
        event.occurredAt = occurredAt;
        event.reasonCode = reason;
        event.httpStatusClass = statusClass == null ? null : statusClass.shortValue();
        event.confirmationSource = source;
        return event;
    }

    private static boolean validOutcomeFacts(
            UnsubscribeOutcomeStatus status,
            UnsubscribeReasonCode reason,
            Integer statusClass,
            UnsubscribeConfirmationSource source) {
        return switch (status) {
            case PENDING -> reason == UnsubscribeReasonCode.NONE
                && statusClass == null && source == null;
            case ACCEPTED -> reason == UnsubscribeReasonCode.NONE
                && Integer.valueOf(2).equals(statusClass)
                && source == UnsubscribeConfirmationSource.ENDPOINT_ACCEPTED;
            case FAILED -> reason != UnsubscribeReasonCode.NONE && source == null;
            case MANUAL_REQUIRED -> reason == UnsubscribeReasonCode.USER_COMPLETED
                && statusClass == null && source == null;
            case VERIFICATION_PENDING -> statusClass == null && source == null;
            case CONFIRMED -> reason == UnsubscribeReasonCode.USER_COMPLETED
                && statusClass == null
                && source == UnsubscribeConfirmationSource.USER_CONFIRMED;
        };
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getAttemptId() { return attemptId; }
    public UnsubscribeOutcomeStatus getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public UnsubscribeReasonCode getReasonCode() { return reasonCode; }
    public Short getHttpStatusClass() { return httpStatusClass; }
    public UnsubscribeConfirmationSource getConfirmationSource() { return confirmationSource; }
}
