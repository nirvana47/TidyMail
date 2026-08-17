package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "action_command_tokens")
public class ActionCommandToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "token_fingerprint", nullable = false, length = 64)
    private String tokenFingerprint;
    @Column(name = "owner_fingerprint", nullable = false, length = 64)
    private String ownerFingerprint;
    @Column(name = "operation_fingerprint", nullable = false, length = 64)
    private String operationFingerprint;
    @Column(name = "subject_fingerprint", nullable = false, length = 64)
    private String subjectFingerprint;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "unsubscribe_intent_nonce_id")
    private Long unsubscribeIntentNonceId;
    @Column(name = "resulting_action_id")
    private Long resultingActionId;
    @Column(name = "resulting_execution_id")
    private Long resultingExecutionId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ActionCommandToken() {
    }

    public static ActionCommandToken issue(
            long accountId, String token, String owner, String operation,
            String subject, Instant createdAt, Instant expiresAt) {
        requireFingerprint(token);
        requireFingerprint(owner);
        requireFingerprint(operation);
        requireFingerprint(subject);
        if (accountId <= 0 || createdAt == null || expiresAt == null
                || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invalid command token");
        }
        ActionCommandToken row = new ActionCommandToken();
        row.publicId = UUID.randomUUID();
        row.accountId = accountId;
        row.tokenFingerprint = token;
        row.ownerFingerprint = owner;
        row.operationFingerprint = operation;
        row.subjectFingerprint = subject;
        row.createdAt = createdAt;
        row.expiresAt = expiresAt;
        return row;
    }

    public void consume(long actionId, Instant now) {
        if (consumedAt != null || !now.isBefore(expiresAt) || actionId <= 0) {
            throw new IllegalStateException("Command token cannot be consumed");
        }
        consumedAt = now;
        resultingActionId = actionId;
    }

    public void linkExecution(long executionId) {
        if (executionId <= 0 || resultingActionId == null) {
            throw new IllegalStateException("Execution cannot be linked");
        }
        resultingExecutionId = executionId;
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected lowercase SHA-256");
        }
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getAccountId() { return accountId; }
    public String getTokenFingerprint() { return tokenFingerprint; }
    public String getOwnerFingerprint() { return ownerFingerprint; }
    public String getOperationFingerprint() { return operationFingerprint; }
    public String getSubjectFingerprint() { return subjectFingerprint; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Long getResultingActionId() { return resultingActionId; }
    public Long getResultingExecutionId() { return resultingExecutionId; }
    public Instant getCreatedAt() { return createdAt; }
}
