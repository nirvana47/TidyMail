package com.tidymail.entity;

import com.tidymail.unsubscribe.UnsubscribeConfirmationSource;
import com.tidymail.unsubscribe.UnsubscribeMethod;
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

@Entity
@Table(name = "unsubscribe_attempts")
public class UnsubscribeAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "candidate_fingerprint", nullable = false, length = 64)
    private String candidateFingerprint;

    @Column(name = "sender_key_fingerprint", nullable = false, length = 64)
    private String senderKeyFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private UnsubscribeMethod method;

    @Column(name = "destination_host", length = 253)
    private String destinationHost;

    @Column(name = "destination_fingerprint", length = 64)
    private String destinationFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_status", nullable = false, length = 24)
    private UnsubscribeOutcomeStatus latestStatus;

    @Column(name = "http_status_class")
    private Short httpStatusClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 48)
    private UnsubscribeReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_source", length = 24)
    private UnsubscribeConfirmationSource confirmationSource;

    protected UnsubscribeAttempt() {
    }

    public static UnsubscribeAttempt pending(
            long accountId,
            String candidateFingerprint,
            String senderKeyFingerprint,
            UnsubscribeMethod method,
            String destinationHost,
            String destinationFingerprint,
            Instant createdAt) {
        requireFingerprint(candidateFingerprint);
        requireFingerprint(senderKeyFingerprint);
        if (destinationFingerprint != null) requireFingerprint(destinationFingerprint);
        if (accountId <= 0 || method == null || createdAt == null
                || destinationHost != null && (destinationHost.isBlank()
                    || destinationHost.length() > 253
                    || !destinationHost.equals(destinationHost.strip().toLowerCase())
                    || destinationHost.matches(".*[/?#@].*"))) {
            throw new IllegalArgumentException("Invalid unsubscribe attempt facts");
        }
        UnsubscribeAttempt attempt = new UnsubscribeAttempt();
        attempt.accountId = accountId;
        attempt.candidateFingerprint = candidateFingerprint;
        attempt.senderKeyFingerprint = senderKeyFingerprint;
        attempt.method = method;
        attempt.destinationHost = destinationHost;
        attempt.destinationFingerprint = destinationFingerprint;
        attempt.createdAt = createdAt;
        attempt.latestStatus = UnsubscribeOutcomeStatus.PENDING;
        attempt.reasonCode = UnsubscribeReasonCode.NONE;
        return attempt;
    }

    public void summarize(
            UnsubscribeOutcomeStatus status,
            UnsubscribeReasonCode reason,
            Integer statusClass,
            UnsubscribeConfirmationSource source,
            Instant completedAt) {
        if (status == null || reason == null || completedAt == null
                || completedAt.isBefore(createdAt)
                || statusClass != null && (statusClass < 2 || statusClass > 5)
                || !validTransition(latestStatus, status)
                || !validOutcomeFacts(status, reason, statusClass, source)) {
            throw new IllegalArgumentException("Invalid unsubscribe outcome summary");
        }
        this.latestStatus = status;
        this.reasonCode = reason;
        this.httpStatusClass = statusClass == null ? null : statusClass.shortValue();
        this.confirmationSource = source;
        this.completedAt = completedAt;
    }

    private static boolean validTransition(
            UnsubscribeOutcomeStatus current,
            UnsubscribeOutcomeStatus next) {
        return current == UnsubscribeOutcomeStatus.PENDING
                && (next == UnsubscribeOutcomeStatus.ACCEPTED
                    || next == UnsubscribeOutcomeStatus.FAILED
                    || next == UnsubscribeOutcomeStatus.MANUAL_REQUIRED)
            || current == UnsubscribeOutcomeStatus.ACCEPTED
                && next == UnsubscribeOutcomeStatus.VERIFICATION_PENDING
            || current == UnsubscribeOutcomeStatus.MANUAL_REQUIRED
                && next == UnsubscribeOutcomeStatus.VERIFICATION_PENDING
            || current == UnsubscribeOutcomeStatus.VERIFICATION_PENDING
                && next == UnsubscribeOutcomeStatus.CONFIRMED;
    }

    private static boolean validOutcomeFacts(
            UnsubscribeOutcomeStatus status,
            UnsubscribeReasonCode reason,
            Integer statusClass,
            UnsubscribeConfirmationSource source) {
        return switch (status) {
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
            case PENDING -> false;
        };
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fingerprint must be lowercase SHA-256");
        }
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getCandidateFingerprint() { return candidateFingerprint; }
    public String getSenderKeyFingerprint() { return senderKeyFingerprint; }
    public UnsubscribeMethod getMethod() { return method; }
    public String getDestinationHost() { return destinationHost; }
    public String getDestinationFingerprint() { return destinationFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public UnsubscribeOutcomeStatus getLatestStatus() { return latestStatus; }
    public Short getHttpStatusClass() { return httpStatusClass; }
    public UnsubscribeReasonCode getReasonCode() { return reasonCode; }
    public UnsubscribeConfirmationSource getConfirmationSource() { return confirmationSource; }
}
