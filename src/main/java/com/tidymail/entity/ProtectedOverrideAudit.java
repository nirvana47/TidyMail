package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Immutable audit row recording when a protected-sender guard was overridden during
 * a cleanup action, including the selection surface, query fingerprint, and how many
 * protected senders/messages were bypassed.
 * <p>Product functionality: protected-override auditing — tracks deliberate
 * overrides of protected-sender protection for accountability.
 */
@Entity
@Immutable
@Table(name = "protected_override_audits")
public class ProtectedOverrideAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "selection_surface", nullable = false, length = 16)
    private String selectionSurface;

    @Column(name = "selection_mode", nullable = false, length = 16)
    private String selectionMode;

    @Column(name = "action_type", nullable = false, length = 16)
    private String actionType;

    @Column(name = "query_fingerprint", nullable = false, length = 64)
    private String queryFingerprint;

    @Column(name = "protected_sender_count", nullable = false)
    private Integer protectedSenderCount;

    @Column(name = "protected_message_count", nullable = false)
    private Integer protectedMessageCount;

    @Column(name = "reason_code", nullable = false, length = 32)
    private String reasonCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProtectedOverrideAudit() {
    }

    /**
     * Creates an audit row from validated server-side facts about an override.
     */
    public static ProtectedOverrideAudit create(
            Long accountId,
            String surface,
            String mode,
            String action,
            String fingerprint,
            int protectedSenderCount,
            int protectedMessageCount,
            String reason,
            Instant createdAt) {
        if (accountId == null || accountId <= 0 || protectedSenderCount < 0
                || protectedMessageCount < 0 || createdAt == null) {
            throw new IllegalArgumentException("Valid server audit facts are required");
        }
        ProtectedOverrideAudit audit = new ProtectedOverrideAudit();
        audit.accountId = accountId;
        audit.selectionSurface = surface;
        audit.selectionMode = mode;
        audit.actionType = action;
        audit.queryFingerprint = fingerprint;
        audit.protectedSenderCount = protectedSenderCount;
        audit.protectedMessageCount = protectedMessageCount;
        audit.reasonCode = reason;
        audit.createdAt = createdAt;
        return audit;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getSelectionSurface() { return selectionSurface; }
    public String getSelectionMode() { return selectionMode; }
    public String getActionType() { return actionType; }
    public String getQueryFingerprint() { return queryFingerprint; }
    public Integer getProtectedSenderCount() { return protectedSenderCount; }
    public Integer getProtectedMessageCount() { return protectedMessageCount; }
    public String getReasonCode() { return reasonCode; }
    public Instant getCreatedAt() { return createdAt; }
}
