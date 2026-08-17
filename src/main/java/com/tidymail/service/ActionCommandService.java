package com.tidymail.service;

import com.tidymail.action.ExecutionMode;
import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.dto.UnsubscribeApprovalConfirmation;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.UserActionRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActionCommandService {
    private final ActionConfirmationIntentService confirmations;
    private final ActionCommandPersistenceService persistence;
    private final ExactScopeExecutor executor;
    private final UserActionRepository actions;
    private final ActionConflictService conflicts;
    private final ActionScopeRepository scopes;
    private final ActionIntentRepository intents;

    public ActionCommandService(
            ActionConfirmationIntentService confirmations,
            ActionCommandPersistenceService persistence,
            ExactScopeExecutor executor,
            UserActionRepository actions,
            ActionConflictService conflicts,
            ActionScopeRepository scopes,
            ActionIntentRepository intents) {
        this.confirmations = confirmations;
        this.persistence = persistence;
        this.executor = executor;
        this.actions = actions;
        this.conflicts = conflicts;
        this.scopes = scopes;
        this.intents = intents;
    }

    public CommandResult submit(
            String ownerKey,
            List<Long> authorizedAccountIds,
            String confirmation,
            String modeValue,
            boolean immediateAcknowledged) {
        return submit(
            ownerKey, authorizedAccountIds, confirmation, modeValue,
            immediateAcknowledged, null);
    }

    public CommandResult submit(
            String ownerKey,
            List<Long> authorizedAccountIds,
            String confirmation,
            String modeValue,
            boolean immediateAcknowledged,
            String unsubscribeMethod) {
        ExecutionMode mode = mode(modeValue);
        if (mode == ExecutionMode.IMMEDIATE && !immediateAcknowledged) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "immediate_acknowledgement_required");
        }
        var payload = confirmations.verify(ownerKey, confirmation);
        validateExplicitMethod(payload, unsubscribeMethod);
        if (!authorizedAccountIds.containsAll(
                payload.scopes().stream().map(
                    ActionConfirmationIntentService.ScopeBinding::accountId).toList())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
        }
        List<UserAction> actions = persistence.create(payload, confirmation, mode);
        if (payload.actionType() != com.tidymail.action.ActionType.REVIEW) {
            conflicts.recompute(actions.stream().map(UserAction::getAccountId)
                .distinct().sorted().toList());
        }
        boolean executeNow = mode == ExecutionMode.IMMEDIATE
            || payload.actionType() == com.tidymail.action.ActionType.REVIEW;
        if (!executeNow) {
            return new CommandResult(mode, actions, List.of());
        }
        List<ExactScopeExecutor.ExecutionReceipt> receipts = actions.stream()
            .map(action -> executor.execute(
                action.getAccountId(), action.getPublicId(),
                idempotencyToken(confirmation, action.getPublicId())))
            .toList();
        return new CommandResult(mode, actions, receipts);
    }

    private static void validateExplicitMethod(
            ActionConfirmationIntentService.Payload payload, String submittedMethod) {
        boolean unsubscribe =
            payload.actionType() == com.tidymail.action.ActionType.UNSUBSCRIBE;
        if (!unsubscribe && submittedMethod != null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unexpected_unsubscribe_method");
        }
        if (unsubscribe && (submittedMethod == null || submittedMethod.isBlank()
                || payload.scopes().size() != 1
                || !submittedMethod.equals(
                    payload.scopes().getFirst().unsubscribeMethod()))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "unsubscribe_method_changed");
        }
    }

    public ExactScopeExecutor.ExecutionReceipt approve(
            long accountId, UUID actionPublicId, UUID idempotencyToken) {
        UserAction action = actions.findByPublicIdAndAccountId(
            actionPublicId, accountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found"));
        if ("UNSUBSCRIBE".equals(action.getActionType())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "fresh_unsubscribe_consent_required");
        }
        return executor.execute(accountId, actionPublicId, idempotencyToken);
    }

    public ExactScopeExecutor.ExecutionReceipt approveUnsubscribe(
            String ownerKey,
            List<Long> authorizedAccountIds,
            UUID actionPublicId,
            String confirmation,
            String unsubscribeMethod) {
        var payload = confirmations.verify(ownerKey, confirmation);
        validateExplicitMethod(payload, unsubscribeMethod);
        if (payload.actionType() != com.tidymail.action.ActionType.UNSUBSCRIBE
                || payload.scopes().size() != 1
                || !authorizedAccountIds.contains(payload.scopes().getFirst().accountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
        }
        UserAction action = persistence.consumeUnsubscribeApproval(
            payload, confirmation, actionPublicId);
        return executor.execute(
            action.getAccountId(), action.getPublicId(),
            idempotencyToken(confirmation, action.getPublicId()));
    }

    public UnsubscribeApprovalConfirmation prepareUnsubscribeApproval(
            String ownerKey,
            List<Long> authorizedAccountIds,
            UUID actionPublicId) {
        UserAction action = authorizedAccountIds.stream()
            .map(accountId -> actions.findByPublicIdAndAccountId(
                actionPublicId, accountId).orElse(null))
            .filter(value -> value != null)
            .findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found"));
        if (!ActionType.UNSUBSCRIBE.name().equals(action.getActionType())
                || !"QUEUED".equals(action.getStatus())
                || action.getActionScopeId() == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "unsubscribe_action_not_confirmable");
        }
        ActionScope scope = scopes.findByIdAndAccountId(
            action.getActionScopeId(), action.getAccountId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found"));
        ActionIntent intent = intents.findByActionIdAndAccountId(
            action.getId(), action.getAccountId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "action_confirmation_stale"));
        if (intent.getUnsubscribeCandidateFingerprint() == null
                || intent.getUnsubscribeDestinationFingerprint() == null
                || intent.getUnsubscribeMethod() == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "action_confirmation_stale");
        }
        String confirmation = confirmations.issue(
            ownerKey, ActionType.UNSUBSCRIBE, DecisionSource.REVIEW,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                action.getAccountId(), scope.getScopeHash(),
                intent.getUnsubscribeCandidateFingerprint(),
                intent.getUnsubscribeDestinationFingerprint(),
                intent.getUnsubscribeMethod())));
        return new UnsubscribeApprovalConfirmation(
            action.getPublicId(), intent.getSafeTargetLabel(),
            action.getAffectedCount(), intent.getUnsubscribeMethod(), confirmation);
    }

    public void cancel(long accountId, UUID actionPublicId) {
        persistence.cancel(accountId, actionPublicId);
    }

    private static ExecutionMode mode(String value) {
        if (value == null || value.isBlank()) return ExecutionMode.STAGED;
        try {
            return ExecutionMode.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "invalid_execution_mode");
        }
    }

    private static UUID idempotencyToken(String confirmation, UUID actionPublicId) {
        return UUID.nameUUIDFromBytes(
            (confirmation + ":" + actionPublicId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public record CommandResult(
        ExecutionMode mode,
        List<UserAction> actions,
        List<ExactScopeExecutor.ExecutionReceipt> receipts
    ) {
        public CommandResult {
            actions = List.copyOf(actions);
            receipts = List.copyOf(receipts);
        }
    }
}
