package com.tidymail.service;

import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.entity.ActionScope;
import com.tidymail.repository.ActionScopeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Loads and verifies the exact scope stored by a dashboard preview. */
@Service
public class ActionScopeService {

    private final ActionScopeHasher hasher;
    private final ActionScopeRepository repository;

    public ActionScopeService(
            ActionScopeHasher hasher,
            ActionScopeRepository repository) {
        this.hasher = hasher;
        this.repository = repository;
    }

    /** Loads and integrity-checks a scope, or fails if missing. */
    @Transactional(readOnly = true)
    public ActionScope requireScope(Long accountId, Long scopeId) {
        if (accountId == null || accountId <= 0 || scopeId == null || scopeId <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scope_not_found");
        }
        ActionScope scope = repository.findByIdAndAccountId(scopeId, accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "scope_not_found"));
        requireIntegrity(scope);
        return scope;
    }

    /** Recomputes the canonical hash and validates the scope's summaries. */
    private void requireIntegrity(ActionScope scope) {
        ActionScopeHasher.CanonicalScope canonical = hasher.canonicalize(
            scope.getAccountId(),
            scope.getActionType(),
            scope.getTargetKind(),
            scope.getMessageIds());
        boolean valid =
            scope.getScopeHash().equals(canonical.scopeHash())
                && scope.getMessageIds().equals(canonical.messageIds())
                && scope.getMessageCount().equals(canonical.messageIds().size());
        if (!valid) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "action_scope_incompatible");
        }
        ActionScopeRiskSummary riskSummary = scope.getRiskSummary();
        if (riskSummary.protectedCount() > canonical.messageIds().size()
                || riskSummary.highRiskCount() > canonical.messageIds().size()) {
            throw new IllegalArgumentException("Risk counts cannot exceed the exact scope size");
        }
    }
}
