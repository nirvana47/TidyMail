package com.tidymail.service;

import com.tidymail.dto.ActionScopePreview;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionScopeRepository;
import java.sql.SQLException;
import java.util.Collection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates, loads, and validates exact action scopes. Handles deduplication by
 * scope hash (including concurrent-insert races) and verifies scope integrity
 * against the canonical form before use.
 *
 * <p>Product functionality supported: the <em>Bulk Action Preview</em> flow's
 * exact-scope materialization and the execution engine's scope validation.
 */
@Service
public class ActionScopeService {

    private final ActionScopeHasher hasher;
    private final ActionScopeRepository repository;
    private final ActionScopePersistenceService persistenceService;

    public ActionScopeService(
            ActionScopeHasher hasher,
            ActionScopeRepository repository,
            ActionScopePersistenceService persistenceService) {
        this.hasher = hasher;
        this.repository = repository;
        this.persistenceService = persistenceService;
    }

    /** Creates or returns the existing scope for the given exact targets. */
    public ActionScopePreview createOrGet(
            Long accountId,
            String actionType,
            String targetKind,
            Collection<String> messageIds,
            ActionScopeTargetSummary targetSummary,
            ActionScopeRiskSummary riskSummary) {
        if (targetSummary == null || riskSummary == null) {
            throw new IllegalArgumentException("Typed target and risk summaries are required");
        }
        ActionScopeHasher.CanonicalScope canonical =
            hasher.canonicalize(accountId, actionType, targetKind, messageIds);
        validateSummaries(canonical, targetSummary, riskSummary);
        ActionScope existing = repository
            .findByAccountIdAndScopeHash(accountId, canonical.scopeHash())
            .orElse(null);
        if (existing != null) {
            return toPreview(requireExact(existing, accountId, canonical));
        }

        ActionScope candidate = ActionScope.create(
            accountId,
            canonical.actionType(),
            canonical.targetKind(),
            canonical.messageIds(),
            canonical.scopeHash(),
            targetSummary,
            riskSummary);
        try {
            ActionScope inserted = persistenceService.insert(candidate);
            ActionScope persisted = repository
                .findByIdAndAccountId(inserted.getId(), accountId)
                .orElseThrow(() -> new IllegalStateException(
                    "Committed scope could not be reloaded"));
            return toPreview(requireExact(persisted, accountId, canonical));
        } catch (DataIntegrityViolationException race) {
            if (!isScopeHashUniqueViolation(race)) {
                throw race;
            }
            ActionScope winner = repository
                .findByAccountIdAndScopeHash(accountId, canonical.scopeHash())
                .orElseThrow(() -> new IllegalStateException(
                    "Scope insert conflicted but no committed winner exists", race));
            return toPreview(requireExact(winner, accountId, canonical));
        }
    }

    /** Creates or returns the scope within the caller's transaction. */
    public ActionScopePreview createOrGetRequired(
            Long accountId,
            String actionType,
            String targetKind,
            Collection<String> messageIds,
            ActionScopeTargetSummary targetSummary,
            ActionScopeRiskSummary riskSummary) {
        if (targetSummary == null || riskSummary == null) {
            throw new IllegalArgumentException("Typed target and risk summaries are required");
        }
        ActionScopeHasher.CanonicalScope canonical =
            hasher.canonicalize(accountId, actionType, targetKind, messageIds);
        validateSummaries(canonical, targetSummary, riskSummary);
        ActionScope existing = repository
            .findByAccountIdAndScopeHash(accountId, canonical.scopeHash())
            .orElse(null);
        if (existing != null) {
            return toPreview(requireExact(existing, accountId, canonical));
        }
        ActionScope candidate = ActionScope.create(
            accountId,
            canonical.actionType(),
            canonical.targetKind(),
            canonical.messageIds(),
            canonical.scopeHash(),
            targetSummary,
            riskSummary);
        ActionScope inserted = persistenceService.insertRequired(candidate);
        return toPreview(requireExact(inserted, accountId, canonical));
    }

    /** Returns the preview for an existing scope. */
    @Transactional(readOnly = true)
    public ActionScopePreview getPreview(Long accountId, Long scopeId) {
        return toPreview(requireScope(accountId, scopeId));
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

    /** Verifies an action's scope still matches its recorded facts. */
    @Transactional(readOnly = true)
    public void assertCompatible(UserAction action) {
        if (action == null || action.getActionScopeId() == null) {
            return;
        }
        ActionScope scope = repository
            .findByIdAndAccountId(action.getActionScopeId(), action.getAccountId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "action_scope_incompatible"));
        requireIntegrity(scope);
        boolean compatible =
            scope.getAccountId().equals(action.getAccountId())
                && scope.getActionType().equals(action.getActionType())
                && scope.getMessageIds().equals(action.getMessageIds())
                && scope.getMessageCount().equals(action.getAffectedCount());
        if (!compatible) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "action_scope_incompatible");
        }
    }

    /** Verifies a persisted scope exactly matches the canonical scope. */
    private static ActionScope requireExact(
            ActionScope scope,
            Long accountId,
            ActionScopeHasher.CanonicalScope canonical) {
        boolean exact =
            scope.getAccountId().equals(accountId)
                && scope.getActionType().equals(canonical.actionType())
                && scope.getTargetKind().equals(canonical.targetKind())
                && scope.getMessageIds().equals(canonical.messageIds())
                && scope.getMessageCount() == canonical.messageIds().size()
                && scope.getScopeHash().equals(canonical.scopeHash());
        if (!exact) {
            throw new IllegalStateException("Scope hash collision or incompatible persisted scope");
        }
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
        validateSummaries(canonical, scope.getTargetSummary(), scope.getRiskSummary());
    }

    /** Validates target/risk summaries against the scope's target kind and size. */
    private static void validateSummaries(
            ActionScopeHasher.CanonicalScope canonical,
            ActionScopeTargetSummary targetSummary,
            ActionScopeRiskSummary riskSummary) {
        if ("SENDER_ADDRESS".equals(canonical.targetKind())) {
            if (targetSummary.selectedTargetCount() != 1
                    || targetSummary.senderAddress() == null
                    || targetSummary.senderAddress().isBlank()) {
                throw new IllegalArgumentException(
                    "Sender-address scopes require exactly one sender address");
            }
        } else if ("SENDER_SELECTION".equals(canonical.targetKind())
                || "UNREAD_SELECTION".equals(canonical.targetKind())) {
            if (targetSummary.selectedTargetCount() < 1
                    || targetSummary.selectedTargetCount() > 1000) {
                throw new IllegalArgumentException(
                    "Selection scopes require between 1 and 1000 selected targets");
            }
        }
        if (riskSummary.protectedCount() > canonical.messageIds().size()
                || riskSummary.highRiskCount() > canonical.messageIds().size()) {
            throw new IllegalArgumentException("Risk counts cannot exceed the exact scope size");
        }
    }

    /** Returns true if the failure is a scope-hash unique-constraint violation. */
    private static boolean isScopeHashUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains("uq_action_scopes_account_hash")) {
                return true;
            }
        }
        return false;
    }

    /** Builds a preview DTO from a validated scope. */
    private ActionScopePreview toPreview(ActionScope scope) {
        requireIntegrity(scope);
        if (scope.getMessageCount() != scope.getMessageIds().size()) {
            throw new IllegalStateException("Persisted scope count does not match exact IDs");
        }
        return new ActionScopePreview(
            scope.getId(),
            scope.getScopeHash(),
            scope.getMessageCount(),
            scope.getMessageIds(),
            scope.getActionType(),
            scope.getTargetKind(),
            scope.getTargetSummary(),
            scope.getRiskSummary(),
            scope.getCreatedAt());
    }
}
