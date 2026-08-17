package com.tidymail.service;

import com.tidymail.action.ActionStatus;
import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.action.ExecutionMode;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.entity.ActionCommandToken;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionCommandTokenRepository;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ProtectedSenderRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompensationService {
    private static final Duration INTENT_TTL = Duration.ofMinutes(5);

    private final UserActionRepository actions;
    private final UserAccountRepository accounts;
    private final ActionIntentRepository intents;
    private final ActionCommandTokenRepository tokens;
    private final ActionEventRepository events;
    private final ProtectedSenderRepository protectedSenders;
    private final ActionScopeService scopes;
    private final ExactScopeExecutor executor;
    private final ActionConflictService conflicts;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    public CompensationService(
            UserActionRepository actions,
            UserAccountRepository accounts,
            ActionIntentRepository intents,
            ActionCommandTokenRepository tokens,
            ActionEventRepository events,
            ProtectedSenderRepository protectedSenders,
            ActionScopeService scopes,
            ExactScopeExecutor executor,
            ActionConflictService conflicts,
            TransactionTemplate transactions) {
        this.actions = actions;
        this.accounts = accounts;
        this.intents = intents;
        this.tokens = tokens;
        this.events = events;
        this.protectedSenders = protectedSenders;
        this.scopes = scopes;
        this.executor = executor;
        this.conflicts = conflicts;
        this.transactions = transactions;
    }

    public CompensationConfirmation prepare(
            String ownerKey, long accountId, UUID originalPublicId) {
        return transactions.execute(status -> {
            Original original = original(accountId, originalPublicId);
            String raw = UUID.randomUUID().toString();
            Instant now = clock.instant();
            tokens.save(ActionCommandToken.issue(
                accountId, ActionFingerprints.sha256(raw),
                ActionFingerprints.sha256(ownerKey),
                ActionFingerprints.sha256("REMOVE_PROTECTION"),
                subject(original.action(), original.intent()), now,
                now.plus(INTENT_TTL)));
            return new CompensationConfirmation(
                originalPublicId, accountId, original.intent().getSafeTargetLabel(),
                original.intent().getNormalizedSenderAddress(), raw,
                now.plus(INTENT_TTL));
        });
    }

    public CompensationResult submit(
            String ownerKey,
            long accountId,
            UUID originalPublicId,
            String rawIntent,
            String modeValue,
            boolean immediateAcknowledged) {
        ExecutionMode mode = mode(modeValue);
        if (mode == ExecutionMode.IMMEDIATE && !immediateAcknowledged) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "immediate_acknowledgement_required");
        }
        UserAction compensation = transactions.execute(status ->
            create(ownerKey, accountId, originalPublicId, rawIntent, mode));
        conflicts.recompute(List.of(accountId));
        ExactScopeExecutor.ExecutionReceipt receipt = mode == ExecutionMode.IMMEDIATE
            ? executor.execute(accountId, compensation.getPublicId(),
                UUID.nameUUIDFromBytes(rawIntent.getBytes(StandardCharsets.UTF_8)))
            : null;
        return new CompensationResult(
            compensation.getPublicId(), mode, compensation.getStatus(), receipt);
    }

    private UserAction create(
            String ownerKey, long accountId, UUID originalPublicId,
            String rawIntent, ExecutionMode mode) {
        Instant now = clock.instant();
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(List.of(accountId)).size() != 1) {
            throw notFound();
        }
        Original original = original(accountId, originalPublicId);
        ActionCommandToken token = tokens.findForConsume(
            accountId, ActionFingerprints.sha256(rawIntent)).orElseThrow(
                CompensationService::stale);
        if (token.getConsumedAt() != null || !now.isBefore(token.getExpiresAt())
                || !token.getOwnerFingerprint().equals(
                    ActionFingerprints.sha256(ownerKey))
                || !token.getOperationFingerprint().equals(
                    ActionFingerprints.sha256("REMOVE_PROTECTION"))
                || !token.getSubjectFingerprint().equals(
                    subject(original.action(), original.intent()))) {
            throw stale();
        }
        String sender = original.intent().getNormalizedSenderAddress();
        if (protectedSenders.findByAccountIdAndSenderAddress(
                accountId, sender).isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "protection_already_removed");
        }
        ActionScope originalScope = scopes.requireScope(
            accountId, original.action().getActionScopeId());
        conflicts.assertNoConflict(
            accountId, originalScope.getId(), ActionType.REMOVE_PROTECTION);
        var preview = scopes.createOrGetRequired(
            accountId, ActionType.REMOVE_PROTECTION.name(),
            originalScope.getTargetKind(), originalScope.getMessageIds(),
            originalScope.getTargetSummary(),
            new ActionScopeRiskSummary(0, 0, List.of()));
        ActionScope compensationScope = scopes.requireScope(
            accountId, preview.scopeId());
        UserAction action = new UserAction();
        action.setPublicId(UUID.randomUUID());
        action.setAccountId(accountId);
        action.setActionScopeId(compensationScope.getId());
        action.setActionType(ActionType.REMOVE_PROTECTION.name());
        action.setSenderGroupKey(sender);
        action.setMessageIds(compensationScope.getMessageIds());
        action.setInverseActionData(new HashMap<>());
        action.setTargetKind(compensationScope.getTargetKind());
        action.setTargetIdJson(Map.of());
        action.setAffectedCount(compensationScope.getMessageCount());
        action.setStatus(ActionStatus.QUEUED.name());
        action.setExecutionMode(mode.name());
        action.setDecisionSource(DecisionSource.REVIEW.name());
        action.setExpiresAt(null);
        action = actions.saveAndFlush(action);
        intents.save(ActionIntent.compensation(
            action.getId(), accountId, original.intent().getSafeTargetLabel(),
            sender, original.action().getId(), now));
        events.save(ActionEvent.from(
            action, null, "COMPENSATION_CREATED", null, now));
        token.consume(action.getId(), now);
        tokens.save(token);
        return action;
    }

    private Original original(long accountId, UUID publicId) {
        UserAction action = actions.findForUpdateByPublicIdAndAccountId(
            publicId, accountId).orElseThrow(CompensationService::notFound);
        if (!ActionType.PROTECT.name().equals(action.getActionType())
                || action.getActionScopeId() == null
                || !List.of("APPLIED", "PARTIAL").contains(action.getStatus())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "protection_compensation_unavailable");
        }
        ActionIntent intent = intents.findByActionIdAndAccountId(
            action.getId(), accountId).orElseThrow(CompensationService::stale);
        if (intent.getNormalizedSenderAddress() == null) throw stale();
        scopes.assertCompatible(action);
        return new Original(action, intent);
    }

    private static String subject(UserAction action, ActionIntent intent) {
        return ActionFingerprints.bind(
            "compensation-subject-v1", action.getPublicId(),
            intent.getNormalizedSenderAddress());
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

    private static ResponseStatusException stale() {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "compensation_intent_stale");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(
            HttpStatus.NOT_FOUND, "action_not_found");
    }

    private record Original(UserAction action, ActionIntent intent) {
    }

    public record CompensationConfirmation(
        UUID originalActionPublicId,
        long accountId,
        String targetLabel,
        String normalizedSender,
        String intent,
        Instant expiresAt
    ) {
    }

    public record CompensationResult(
        UUID actionPublicId,
        ExecutionMode mode,
        String status,
        ExactScopeExecutor.ExecutionReceipt receipt
    ) {
    }
}
