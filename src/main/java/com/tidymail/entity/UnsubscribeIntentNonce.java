package com.tidymail.entity;

import com.tidymail.unsubscribe.UnsubscribeMethod;
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
@Table(name = "unsubscribe_intent_nonces")
public class UnsubscribeIntentNonce {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "nonce_fingerprint", nullable = false, length = 64)
    private String nonceFingerprint;

    @Column(name = "candidate_fingerprint", nullable = false, length = 64)
    private String candidateFingerprint;

    @Column(name = "destination_fingerprint", nullable = false, length = 64)
    private String destinationFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private UnsubscribeMethod method;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected UnsubscribeIntentNonce() {
    }

    public static UnsubscribeIntentNonce issue(
            long accountId,
            String nonceFingerprint,
            String candidateFingerprint,
            String destinationFingerprint,
            UnsubscribeMethod method,
            Instant issuedAt,
            Instant expiresAt) {
        requireFingerprint(nonceFingerprint);
        requireFingerprint(candidateFingerprint);
        requireFingerprint(destinationFingerprint);
        if (accountId <= 0 || method == null || issuedAt == null || expiresAt == null
                || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Invalid unsubscribe intent nonce");
        }
        UnsubscribeIntentNonce nonce = new UnsubscribeIntentNonce();
        nonce.accountId = accountId;
        nonce.nonceFingerprint = nonceFingerprint;
        nonce.candidateFingerprint = candidateFingerprint;
        nonce.destinationFingerprint = destinationFingerprint;
        nonce.method = method;
        nonce.issuedAt = issuedAt;
        nonce.expiresAt = expiresAt;
        return nonce;
    }

    public void consume(Instant instant) {
        if (instant == null || instant.isBefore(issuedAt) || consumedAt != null) {
            throw new IllegalStateException("Intent nonce cannot be consumed");
        }
        consumedAt = instant;
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fingerprint must be lowercase SHA-256");
        }
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getNonceFingerprint() { return nonceFingerprint; }
    public String getCandidateFingerprint() { return candidateFingerprint; }
    public String getDestinationFingerprint() { return destinationFingerprint; }
    public UnsubscribeMethod getMethod() { return method; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
