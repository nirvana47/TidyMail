package com.tidymail.entity;

import com.tidymail.action.ActionStatus;
import com.tidymail.action.DecisionSource;
import com.tidymail.action.ExecutionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_actions")
public class UserAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "action_scope_id")
    private Long actionScopeId;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "sender_group_key")
    private String senderGroupKey;

    /**
     * IDs of all messages touched by this action.  Stored as a native SQL array;
     * Dialect-agnostic by relying on Hibernate's type resolver instead of a hard
     * {@code columnDefinition} literal.  Validator matches TEXT[] (PG prod) and
     * VARCHAR ARRAY (H2 test Flyway overrides).
     */
    @Column(name = "message_ids", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> messageIds;

    /**
     * Blob used by the "UNDO" action-type to restore messages to their previous
     * location / flags.  Stored as structured JSON; no hard columnDefinition so
     * PostgresDialect maps to JSONB and H2Dialect maps to JSON.
     */
    @Column(name = "inverse_action_data", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> inverseActionData = new HashMap<>();

    @Column(name = "target_kind", length = 32)
    private String targetKind = "SENDER_ADDRESS";

    @Column(name = "target_id_json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> targetIdJson = new HashMap<>();

    @Column(name = "affected_count", nullable = false)
    private Integer affectedCount = 0;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "QUEUED";

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode = ExecutionMode.STAGED.name();

    @Column(name = "decision_source", nullable = false, length = 16)
    private String decisionSource = DecisionSource.LEGACY.name();

    @Column(name = "batch_public_id")
    private UUID batchPublicId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "undo_eligible_until")
    private Instant undoEligibleUntil;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "queued_at", nullable = false, insertable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "\"sequence\"", nullable = false, insertable = false, updatable = false)
    private Long sequence;

    @Column(name = "undone_at")
    private Instant undoneAt;

    public UserAction() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getActionScopeId() {
        return actionScopeId;
    }

    public void setActionScopeId(Long actionScopeId) {
        this.actionScopeId = actionScopeId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getSenderGroupKey() {
        return senderGroupKey;
    }

    public void setSenderGroupKey(String senderGroupKey) {
        this.senderGroupKey = senderGroupKey;
    }

    public List<String> getMessageIds() {
        return messageIds;
    }

    public void setMessageIds(List<String> messageIds) {
        this.messageIds = messageIds;
    }

    public Map<String, Object> getInverseActionData() {
        return inverseActionData;
    }

    public void setInverseActionData(Map<String, Object> inverseActionData) {
        this.inverseActionData = inverseActionData;
    }

    public String getTargetKind() {
        return targetKind;
    }

    public void setTargetKind(String targetKind) {
        this.targetKind = targetKind;
    }

    public Map<String, Object> getTargetIdJson() {
        return targetIdJson;
    }

    public void setTargetIdJson(Map<String, Object> targetIdJson) {
        this.targetIdJson = targetIdJson;
    }

    public Integer getAffectedCount() {
        return affectedCount;
    }

    public void setAffectedCount(Integer affectedCount) {
        this.affectedCount = affectedCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public void setPublicId(UUID publicId) {
        this.publicId = publicId;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getDecisionSource() {
        return decisionSource;
    }

    public void setDecisionSource(String decisionSource) {
        this.decisionSource = decisionSource;
    }

    public UUID getBatchPublicId() {
        return batchPublicId;
    }

    public void setBatchPublicId(UUID batchPublicId) {
        this.batchPublicId = batchPublicId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getUndoEligibleUntil() {
        return undoEligibleUntil;
    }

    public void setUndoEligibleUntil(Instant undoEligibleUntil) {
        this.undoEligibleUntil = undoEligibleUntil;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Long getSequence() {
        return sequence;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(Instant undoneAt) {
        this.undoneAt = undoneAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (executionMode == null) {
            executionMode = ExecutionMode.STAGED.name();
        }
        if (decisionSource == null) {
            decisionSource = DecisionSource.LEGACY.name();
        }
        ActionStatus.valueOf(status);
        ExecutionMode.valueOf(executionMode);
        DecisionSource.valueOf(decisionSource);
        if (expiresAt == null && actionScopeId == null) {
            expiresAt = createdAt.plusSeconds(5);
        }
        if (targetIdJson == null) {
            targetIdJson = new HashMap<>();
        }
        if (affectedCount == null) {
            affectedCount = 0;
        }
        if (targetKind == null) {
            targetKind = "SENDER_ADDRESS";
        }
        if (inverseActionData == null) {
            inverseActionData = new HashMap<>();
        }
    }
}
