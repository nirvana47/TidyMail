package com.tidymail.service;

import com.tidymail.action.ActionStatus;
import com.tidymail.action.ExecutionMode;
import com.tidymail.entity.ActionCommandToken;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionCommandTokenRepository;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ActionConfirmationIntentService.Payload;
import com.tidymail.service.ActionConfirmationIntentService.ScopeBinding;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists confirmed action commands: creates queued {@link UserAction} records
 * (and their intents/events) from a verified confirmation token, and handles
 * cancellation and unsubscribe-approval consumption.
 *
 * <p>Product functionality supported: the <em>Bulk Action</em> and <em>Review</em>
 * flows, which turn verified confirmations into durable, queued actions.
 */
@Service
public class ActionCommandPersistenceService {
    private final ActionScopeRepository scopes;
    private final UserAccountRepository accounts;
    private final UserActionRepository actions;
    private final ActionIntentRepository intents;
    private final ActionCommandTokenRepository tokens;
    private final ActionEventRepository events;
    private final ActionScopeService scopeService;
    private final MessageMetadataRepository metadata;
    private final Clock clock;

    @Autowired
    public ActionCommandPersistenceService(
            ActionScopeRepository scopes,
            UserAccountRepository accounts,
            UserActionRepository actions,
            ActionIntentRepository intents,
            ActionCommandTokenRepository tokens,
            ActionEventRepository events,
            ActionScopeService scopeService,
            MessageMetadataRepository metadata) {
        this(scopes, accounts, actions, intents, tokens, events, scopeService, metadata,
            Clock.systemUTC());
    }

    ActionCommandPersistenceService(
            ActionScopeRepository scopes,
            UserAccountRepository accounts,
            UserActionRepository actions,
            ActionIntentRepository intents,
            ActionCommandTokenRepository tokens,
            ActionEventRepository events,
            ActionScopeService scopeService,
            MessageMetadataRepository metadata,
            Clock clock) {
        this.scopes = scopes;
        this.accounts = accounts;
        this.actions = actions;
        this.intents = intents;
        this.tokens = tokens;
        this.events = events;
        this.scopeService = scopeService;
        this.metadata = metadata;
        this.clock = clock;
    }

    /** Creates queued actions for each scope in the payload, consuming the token. */
    @Transactional
    public List<UserAction> create(
            Payload payload, String rawToken, ExecutionMode mode) {
        Instant now = clock.instant();
        String tokenFingerprint = ActionFingerprints.sha256(rawToken);
        String subject = ActionConfirmationIntentService.subjectFingerprint(
            payload.scopes());
        UUID batchPublicId = payload.scopes().size() > 1 ? UUID.randomUUID() : null;
        List<UserAction> created = new ArrayList<>();
        for (ScopeBinding binding : payload.scopes()) {
            if (accounts.findAllForUpdateByIdInOrderByIdAsc(
                    List.of(binding.accountId())).size() != 1) {
                throw stale();
            }
            ActionCommandToken command = tokens.findForConsume(
                binding.accountId(), tokenFingerprint).orElseThrow(
                    ActionCommandPersistenceService::stale);
            String operation = ActionFingerprints.sha256(
                "CREATE_" + payload.actionType().name());
            if (!command.getOwnerFingerprint().equals(payload.ownerFingerprint())
                    || !command.getSubjectFingerprint().equals(subject)
                    || !command.getOperationFingerprint().equals(operation)
                    || !now.isBefore(command.getExpiresAt())) {
                throw stale();
            }
            if (command.getConsumedAt() != null) {
                UserAction replay = actions.findByIdAndAccountId(
                    command.getResultingActionId(), binding.accountId()).orElseThrow(
                        ActionCommandPersistenceService::stale);
                if (!mode.name().equals(replay.getExecutionMode())) {
                    throw stale();
                }
                created.add(replay);
                continue;
            }
            ActionScope scope = scopes.findByAccountIdAndScopeHash(
                binding.accountId(), binding.scopeHash()).orElseThrow(
                    ActionCommandPersistenceService::stale);
            scopeService.requireScope(binding.accountId(), scope.getId());
            if (!scope.getActionType().equals(payload.actionType().name())) {
                throw stale();
            }

            UserAction action = actions.findActiveForUpdate(
                binding.accountId(), scope.getId(), payload.actionType().name())
                .stream().findFirst().orElse(null);
            if (action == null) {
                action = new UserAction();
                action.setPublicId(UUID.randomUUID());
                action.setAccountId(binding.accountId());
                action.setActionScopeId(scope.getId());
                action.setActionType(payload.actionType().name());
                action.setSenderGroupKey(scope.getTargetSummary().senderAddress());
                action.setMessageIds(scope.getMessageIds());
                action.setInverseActionData(new HashMap<>());
                action.setTargetKind(scope.getTargetKind());
                action.setTargetIdJson(Map.of());
                action.setAffectedCount(scope.getMessageCount());
                action.setStatus(ActionStatus.QUEUED.name());
                action.setExecutionMode(mode.name());
                action.setDecisionSource(payload.source().name());
                action.setBatchPublicId(batchPublicId);
                action.setExpiresAt(null);
                action = actions.saveAndFlush(action);
                String safeLabel = scope.getTargetSummary().displayLabel();
                intents.save(intent(
                    action, scope, binding, safeLabel, now));
                events.save(ActionEvent.from(
                    action, null, "ACTION_QUEUED", null, now));
            }
            if (!mode.name().equals(action.getExecutionMode())) {
                throw stale();
            }
            command.consume(action.getId(), now);
            tokens.save(command);
            created.add(action);
        }
        return List.copyOf(created);
    }

    /** Builds the action intent appropriate to the action type. */
    private ActionIntent intent(
            UserAction action,
            ActionScope scope,
            ScopeBinding binding,
            String safeLabel,
            Instant now) {
        return switch (action.getActionType()) {
            case "PROTECT" -> ActionIntent.protection(
                action.getId(), action.getAccountId(), safeLabel,
                oneNormalizedSender(scope), now);
            case "REVIEW" -> ActionIntent.review(
                action.getId(), action.getAccountId(), safeLabel, safeLabel, now);
            case "UNSUBSCRIBE" -> ActionIntent.unsubscribe(
                action.getId(), action.getAccountId(), safeLabel,
                scope.getTargetSummary().senderAddress() == null
                    ? safeLabel : scope.getTargetSummary().senderAddress(),
                binding.unsubscribeCandidateFingerprint(),
                binding.unsubscribeDestinationFingerprint(),
                binding.unsubscribeMethod(),
                now);
            default -> ActionIntent.messages(
                action.getId(), action.getAccountId(), safeLabel, safeLabel, now);
        };
    }

    /** Resolves the single normalized sender of a protect scope, or fails if ambiguous. */
    private String oneNormalizedSender(ActionScope scope) {
        var rows = metadata.findByAccountIdAndProviderMessageIdIn(
            scope.getAccountId(), scope.getMessageIds());
        var senders = rows.stream()
            .map(com.tidymail.entity.MessageMetadata::getFromAddress)
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.strip().toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        if (rows.size() != scope.getMessageCount() || senders.size() != 1) {
            throw stale();
        }
        return senders.getFirst();
    }

    /** Cancels a queued action, recording a cancellation event. */
    @Transactional
    public void cancel(long accountId, UUID actionPublicId) {
        UserAction action = actions.findForUpdateByPublicIdAndAccountId(
            actionPublicId, accountId).orElseThrow(ActionCommandPersistenceService::notFound);
        if (ActionStatus.CANCELLED.name().equals(action.getStatus())) {
            return;
        }
        if (!ActionStatus.QUEUED.name().equals(action.getStatus())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "action_not_queued");
        }
        action.setStatus(ActionStatus.CANCELLED.name());
        actions.save(action);
        events.save(ActionEvent.from(
            action, null, "ACTION_CANCELLED", null, clock.instant()));
    }

    /** Consumes an unsubscribe approval token and returns the queued action. */
    @Transactional
    public UserAction consumeUnsubscribeApproval(
            Payload payload, String rawToken, UUID actionPublicId) {
        if (payload.actionType() != com.tidymail.action.ActionType.UNSUBSCRIBE
                || payload.scopes().size() != 1) {
            throw stale();
        }
        Instant now = clock.instant();
        ScopeBinding binding = payload.scopes().getFirst();
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(
                List.of(binding.accountId())).size() != 1) {
            throw stale();
        }
        UserAction action = actions.findForUpdateByPublicIdAndAccountId(
            actionPublicId, binding.accountId()).orElseThrow(
                ActionCommandPersistenceService::notFound);
        if (!"UNSUBSCRIBE".equals(action.getActionType())
                || !ActionStatus.QUEUED.name().equals(action.getStatus())
                || !ExecutionMode.STAGED.name().equals(action.getExecutionMode())) {
            throw stale();
        }
        ActionScope scope = scopes.findById(action.getActionScopeId()).orElseThrow(
            ActionCommandPersistenceService::stale);
        if (!scope.getAccountId().equals(binding.accountId())
                || !scope.getScopeHash().equals(binding.scopeHash())) {
            throw stale();
        }
        ActionIntent intent = intents.findByActionIdAndAccountId(
            action.getId(), binding.accountId()).orElseThrow(
                ActionCommandPersistenceService::stale);
        if (!java.util.Objects.equals(
                    intent.getUnsubscribeCandidateFingerprint(),
                    binding.unsubscribeCandidateFingerprint())
                || !java.util.Objects.equals(
                    intent.getUnsubscribeDestinationFingerprint(),
                    binding.unsubscribeDestinationFingerprint())
                || !java.util.Objects.equals(
                    intent.getUnsubscribeMethod(), binding.unsubscribeMethod())) {
            throw stale();
        }

        String fingerprint = ActionFingerprints.sha256(rawToken);
        ActionCommandToken command = tokens.findForConsume(
            binding.accountId(), fingerprint).orElseThrow(
                ActionCommandPersistenceService::stale);
        if (command.getConsumedAt() != null
                || !command.getOwnerFingerprint().equals(payload.ownerFingerprint())
                || !command.getSubjectFingerprint().equals(
                    ActionConfirmationIntentService.subjectFingerprint(payload.scopes()))
                || !command.getOperationFingerprint().equals(
                    ActionFingerprints.sha256("CREATE_UNSUBSCRIBE"))
                || !now.isBefore(command.getExpiresAt())) {
            throw stale();
        }
        command.consume(action.getId(), now);
        tokens.save(command);
        return action;
    }

    /** Throws a conflict when the confirmation token is stale or mismatched. */
    private static ResponseStatusException stale() {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "action_confirmation_stale");
    }

    /** Returns a not-found exception for a missing action. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
    }
}
