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
@Table(name = "action_conflicts")
public class ActionConflict {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "lower_action_id", nullable = false)
    private Long lowerActionId;
    @Column(name = "higher_action_id", nullable = false)
    private Long higherActionId;
    @Column(name = "reason_code", nullable = false, length = 48)
    private String reasonCode;
    @Column(name = "overlap_count", nullable = false)
    private Integer overlapCount;
    @Column(name = "status", nullable = false, length = 16)
    private String status;
    @Column(name = "resolution_code", length = 32)
    private String resolutionCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ActionConflict() {
    }

    public static ActionConflict open(
            long accountId, long lowerActionId, long higherActionId,
            String reasonCode, int overlapCount, Instant now) {
        if (accountId <= 0 || lowerActionId <= 0 || lowerActionId >= higherActionId
                || reasonCode == null || overlapCount <= 0 || now == null) {
            throw new IllegalArgumentException("Invalid action conflict");
        }
        ActionConflict conflict = new ActionConflict();
        conflict.publicId = UUID.randomUUID();
        conflict.accountId = accountId;
        conflict.lowerActionId = lowerActionId;
        conflict.higherActionId = higherActionId;
        conflict.reasonCode = reasonCode;
        conflict.overlapCount = overlapCount;
        conflict.status = "OPEN";
        conflict.createdAt = now;
        return conflict;
    }

    public void refresh(String reason, int overlap) {
        if (!"OPEN".equals(status) || reason == null || overlap <= 0) {
            throw new IllegalStateException("Conflict cannot be refreshed");
        }
        reasonCode = reason;
        overlapCount = overlap;
    }

    public void resolve(String resolution, Instant now) {
        if (!"OPEN".equals(status) || resolution == null || now == null) {
            throw new IllegalStateException("Conflict cannot be resolved");
        }
        status = "RESOLVED";
        resolutionCode = resolution;
        resolvedAt = now;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getAccountId() { return accountId; }
    public Long getLowerActionId() { return lowerActionId; }
    public Long getHigherActionId() { return higherActionId; }
    public String getReasonCode() { return reasonCode; }
    public Integer getOverlapCount() { return overlapCount; }
    public String getStatus() { return status; }
    public String getResolutionCode() { return resolutionCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
