package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Immutable row listing a single fingerprint-identified item within an action
 * scope.
 *
 * <p>Product functionality supported: exact-scope targeting for the
 * action-execution engine.
 */
@Entity
@Immutable
@Table(name = "action_scope_targets")
public class ActionScopeTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "scope_ordinal", nullable = false)
    private Integer scopeOrdinal;
    @Column(name = "target_fingerprint", nullable = false, length = 64)
    private String targetFingerprint;

    protected ActionScopeTarget() {
    }

    /**
     * Creates a scope target from its scope, account, ordinal, and fingerprint.
     */
    public static ActionScopeTarget create(
            long scopeId, long accountId, int ordinal, String fingerprint) {
        if (scopeId <= 0 || accountId <= 0 || ordinal < 0
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid scope target");
        }
        ActionScopeTarget target = new ActionScopeTarget();
        target.scopeId = scopeId;
        target.accountId = accountId;
        target.scopeOrdinal = ordinal;
        target.targetFingerprint = fingerprint;
        return target;
    }

    public Long getId() { return id; }
    public Long getScopeId() { return scopeId; }
    public Long getAccountId() { return accountId; }
    public Integer getScopeOrdinal() { return scopeOrdinal; }
    public String getTargetFingerprint() { return targetFingerprint; }
}
