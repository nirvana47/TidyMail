package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionConflictRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeTargetRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.ProtectedSenderRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs pre-execution safety checks on a queued action: verifies the scope is
 * intact and compatible, the intent matches the action type, current protection
 * still holds, and no open conflict exists.
 *
 * <p>Product functionality supported: the action execution engine's safety
 * preflight, which prevents executing stale or unsafe actions.
 */
@Service
public class ActionPreflightService {
    private final ActionScopeService scopes;
    private final ActionIntentRepository intents;
    private final ActionConflictRepository conflicts;
    private final ActionScopeTargetRepository scopeTargets;
    private final MessageMetadataRepository metadata;
    private final ProtectedSenderRepository protectedSenders;
    private final UnsubscribeCandidateService unsubscribeCandidates;

    public ActionPreflightService(
            ActionScopeService scopes,
            ActionIntentRepository intents,
            ActionConflictRepository conflicts,
            ActionScopeTargetRepository scopeTargets,
            MessageMetadataRepository metadata,
            ProtectedSenderRepository protectedSenders,
            UnsubscribeCandidateService unsubscribeCandidates) {
        this.scopes = scopes;
        this.intents = intents;
        this.conflicts = conflicts;
        this.scopeTargets = scopeTargets;
        this.metadata = metadata;
        this.protectedSenders = protectedSenders;
        this.unsubscribeCandidates = unsubscribeCandidates;
    }

    /** Validates a queued action and returns its preflight facts, or fails. */
    public PreflightResult check(UserAction action) {
        if (action == null || action.getActionScopeId() == null
                || !"QUEUED".equals(action.getStatus())) {
            throw conflict();
        }
        ActionScope scope = scopes.requireScope(
            action.getAccountId(), action.getActionScopeId());
        scopes.assertCompatible(action);
        ActionType type;
        try {
            type = ActionType.valueOf(action.getActionType());
        } catch (IllegalArgumentException unsupported) {
            throw conflict();
        }
        ActionIntent intent = intents.findByActionIdAndAccountId(
            action.getId(), action.getAccountId()).orElseThrow(
                ActionPreflightService::conflict);
        List<com.tidymail.entity.ActionScopeTarget> indexed = scopeTargets
            .findByScopeIdAndAccountIdOrderByScopeOrdinal(
                scope.getId(), action.getAccountId());
          if (indexed.size() != scope.getMessageIds().size()
                  || new HashSet<>(scope.getMessageIds()).size()
                      != scope.getMessageIds().size()) {
              throw conflict();
          }
        for (int ordinal = 0; ordinal < indexed.size(); ordinal++) {
            var target = indexed.get(ordinal);
            if (target.getScopeOrdinal() != ordinal
                    || !target.getTargetFingerprint().equals(
                        ActionFingerprints.sha256(scope.getMessageIds().get(ordinal)))) {
                throw conflict();
            }
        }
        assertCurrentProtection(scope, type);
        assertTypedIntent(action, scope, intent, type);
        if (conflicts
                .existsByAccountIdAndStatusAndLowerActionIdOrAccountIdAndStatusAndHigherActionId(
                    action.getAccountId(), "OPEN", action.getId(),
                    action.getAccountId(), "OPEN", action.getId())) {
            throw conflict();
        }
        return new PreflightResult(action, scope, intent, type);
    }

    /** Verifies the intent matches the action type's expected subject. */
    private void assertTypedIntent(
            UserAction action, ActionScope scope, ActionIntent intent, ActionType type) {
        switch (type) {
            case PROTECT, REMOVE_PROTECTION -> {
                if (!"SENDER".equals(intent.getSubjectKind())
                        || intent.getNormalizedSenderAddress() == null
                        || uniqueCurrentSenders(scope).size() != 1
                        || !uniqueCurrentSenders(scope).contains(
                            intent.getNormalizedSenderAddress())) {
                    throw conflict();
                }
            }
            case REVIEW -> {
                if (!"LOCAL_REVIEW".equals(intent.getSubjectKind())) throw conflict();
            }
            case UNSUBSCRIBE -> {
                if (!"UNSUBSCRIBE_CANDIDATE".equals(intent.getSubjectKind())
                        || scope.getMessageCount() != 1
                        || intent.getUnsubscribeCandidateFingerprint() == null
                        || intent.getUnsubscribeDestinationFingerprint() == null
                        || intent.getUnsubscribeMethod() == null) {
                    throw conflict();
                }
                var candidate = unsubscribeCandidates.executionCandidateByFingerprint(
                    action.getAccountId(),
                    intent.getUnsubscribeCandidateFingerprint(),
                    UnsubscribeMethod.valueOf(intent.getUnsubscribeMethod()));
                if (!candidate.destinationFingerprint().equals(
                        intent.getUnsubscribeDestinationFingerprint())
                        || !scope.getMessageIds().equals(
                            List.of(candidate.providerMessageId()))) {
                    throw conflict();
                }
            }
            default -> {
                if (!"MESSAGES".equals(intent.getSubjectKind())) throw conflict();
            }
        }
    }

    /** Returns the normalized senders currently on the scope's messages. */
    private Set<String> uniqueCurrentSenders(ActionScope scope) {
        return metadata.findByAccountIdAndProviderMessageIdIn(
                scope.getAccountId(), scope.getMessageIds()).stream()
            .map(MessageMetadata::getFromAddress)
            .filter(address -> address != null && !address.isBlank())
            .map(address -> address.strip().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    }

    /** Verifies the current protection state still matches the scope's risk summary. */
    private void assertCurrentProtection(ActionScope scope, ActionType type) {
        List<MessageMetadata> messages = metadata.findByAccountIdAndProviderMessageIdIn(
            scope.getAccountId(), scope.getMessageIds());
        if (messages.size() != scope.getMessageCount()) {
            throw conflict();
        }
        long currentProtected;
        if ("SENDER_ADDRESS".equals(scope.getTargetKind())) {
            currentProtected = messages.stream()
                .filter(message -> message.getLabels().contains("STARRED")
                    || message.getLabels().contains("IMPORTANT"))
                .count();
        } else {
            Set<String> addresses = messages.stream()
                .map(MessageMetadata::getFromAddress)
                .filter(address -> address != null && !address.isBlank())
                .map(address -> address.strip().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
            Set<String> protectedAddresses = addresses.isEmpty()
                ? Set.of()
                : protectedSenders.findByAccountIdInAndSenderAddressIn(
                        List.of(scope.getAccountId()), addresses)
                    .stream()
                    .map(sender -> sender.getSenderAddress()
                        .strip().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            currentProtected = messages.stream()
                .map(MessageMetadata::getFromAddress)
                .filter(address -> address != null
                    && protectedAddresses.contains(
                        address.strip().toLowerCase(Locale.ROOT)))
                .count();
        }
        if (type == ActionType.PROTECT
                && (currentProtected == 0
                    || currentProtected == scope.getMessageCount())) {
            return;
        }
        if (type == ActionType.REMOVE_PROTECTION
                && currentProtected == scope.getMessageCount()) {
            return;
        }
        if (currentProtected != scope.getRiskSummary().protectedCount()) {
            throw conflict();
        }
    }

    /** Throws a conflict when preflight fails. */
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "action_preflight_failed");
    }

    /** The validated facts needed to execute an action. */
    public record PreflightResult(
        UserAction action,
        ActionScope scope,
        ActionIntent intent,
        ActionType actionType
    ) {
    }
}
