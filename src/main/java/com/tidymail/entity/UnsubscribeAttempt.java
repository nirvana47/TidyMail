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

/**
 * Tracks an unsubscribe attempt for a sender candidate, including the method,
 * destination, outcome status, and reason over the course of the unsubscribe flow.
 * <p>Product functionality: truncate unsubscribe flow — records that an attempt was
 * made and the outcome of the remote unsubscribe request.
 */
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
