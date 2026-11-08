package com.tidymail.entity;

import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable snapshot of the exact set of messages targeted by an action, with
 * target and risk summaries.
 *
 * <p>Product functionality supported: precise EXACT-scope targeting for the
 * action-execution engine.
 */
@Entity
@Immutable
@Table(name = "action_scopes")
public class ActionScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "target_kind", nullable = false, length = 32)
    private String targetKind;

    @Column(name = "message_ids", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> messageIds;

    @Column(name = "scope_hash", nullable = false, length = 64)
    private String scopeHash;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "target_summary", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private ActionScopeTargetSummary targetSummary;

    @Column(name = "risk_summary", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private ActionScopeRiskSummary riskSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ActionScope() {
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getTargetKind() {
        return targetKind;
    }

    public List<String> getMessageIds() {
        return List.copyOf(messageIds);
    }

    public String getScopeHash() {
        return scopeHash;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public ActionScopeTargetSummary getTargetSummary() {
        return targetSummary;
    }

    public ActionScopeRiskSummary getRiskSummary() {
        return riskSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
