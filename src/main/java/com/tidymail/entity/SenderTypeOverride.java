package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * User override that pins a sender to a specific {@link SenderType} (LIST, AUTOMATED,
 * TRANSACTIONAL, PERSONAL), overriding the type inferred during sync.
 * <p>Product functionality: sender-type overrides — lets users correct how
 * TidyMail classifies a sender so cleanup rules treat it appropriately.
 */
@Entity
@Table(name = "sender_type_overrides")
public class SenderTypeOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "sender_address", nullable = false, length = 320)
    private String senderAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 32)
    private SenderType senderType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SenderTypeOverride() {
    }

    /**
     * Creates a sender-type override, validating the account, sender address, and type.
     */
    public static SenderTypeOverride create(
            long accountId, String senderAddress, SenderType senderType) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()
                || senderType == null) {
            throw new IllegalArgumentException("Invalid sender type override");
        }
        SenderTypeOverride override = new SenderTypeOverride();
        override.accountId = accountId;
        override.senderAddress = senderAddress;
        override.senderType = senderType;
        return override;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getSenderAddress() { return senderAddress; }
    public SenderType getSenderType() { return senderType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void changeType(SenderType senderType) {
        if (senderType == null) {
            throw new IllegalArgumentException("Sender type is required");
        }
        this.senderType = senderType;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * The sender classification used by cleanup decisioning.
     */
    public enum SenderType {
        LIST, AUTOMATED, TRANSACTIONAL, PERSONAL
    }
}
