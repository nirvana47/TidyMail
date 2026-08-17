package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.dto.ReviewConflictDetail;
import com.tidymail.entity.ActionConflict;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionConflictRepository;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeTargetRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActionConflictService {
    private final UserAccountRepository accounts;
    private final UserActionRepository actions;
    private final ActionIntentRepository actionIntents;
    private final ActionScopeTargetRepository scopeTargets;
    private final ActionConflictRepository conflicts;
    private final ActionEventRepository events;
    private final ReviewOperationIntentService operationIntents;
    private final Clock clock = Clock.systemUTC();

    public ActionConflictService(
            UserAccountRepository accounts,
            UserActionRepository actions,
            ActionIntentRepository actionIntents,
            ActionScopeTargetRepository scopeTargets,
            ActionConflictRepository conflicts,
            ActionEventRepository events,
            ReviewOperationIntentService operationIntents) {
        this.accounts = accounts;
        this.actions = actions;
        this.actionIntents = actionIntents;
        this.scopeTargets = scopeTargets;
        this.conflicts = conflicts;
        this.events = events;
        this.operationIntents = operationIntents;
    }

    @Transactional
    public void recompute(List<Long> authorizedAccountIds) {
        List<Long> ordered = authorizedAccountIds.stream().distinct().sorted().toList();
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(ordered).size() != ordered.size()) {
            throw notFound();
        }
        for (Long accountId : ordered) {
            List<UserAction> candidates = actions.findConflictCandidates(accountId);
            for (int leftIndex = 0; leftIndex < candidates.size(); leftIndex++) {
                for (int rightIndex = leftIndex + 1;
                        rightIndex < candidates.size(); rightIndex++) {
                    UserAction left = candidates.get(leftIndex);
                    UserAction right = candidates.get(rightIndex);
                    ActionType leftType = ActionType.valueOf(left.getActionType());
                    ActionType rightType = ActionType.valueOf(right.getActionType());
                    if (ActionCompatibility.compatible(leftType, rightType)) continue;
                    long overlap = scopeTargets.countExactOverlap(
                        accountId, left.getActionScopeId(), right.getActionScopeId());
                    if (overlap == 0) continue;
                    String reason = leftType == ActionType.REVIEW
                            || rightType == ActionType.REVIEW
                        ? "REVIEW_BLOCKS_EXECUTION" : "INCOMPATIBLE_ACTIONS";
                    ActionConflict conflict = conflicts
                        .findByAccountIdAndLowerActionIdAndHigherActionId(
                            accountId, left.getId(), right.getId())
                        .orElse(null);
                    if (conflict == null) {
                        conflicts.save(ActionConflict.open(
                            accountId, left.getId(), right.getId(),
                            reason, Math.toIntExact(overlap), clock.instant()));
                    } else if ("OPEN".equals(conflict.getStatus())) {
                        conflict.refresh(reason, Math.toIntExact(overlap));
                        conflicts.save(conflict);
                    }
                    block(left);
                    block(right);
                }
            }
        }
    }

    public void assertNoConflict(
            long accountId, long scopeId, ActionType actionType) {
        for (UserAction candidate : actions.findConflictCandidates(accountId)) {
            ActionType candidateType = ActionType.valueOf(candidate.getActionType());
            if (!ActionCompatibility.compatible(actionType, candidateType)
                    && scopeTargets.countExactOverlap(
                        accountId, scopeId, candidate.getActionScopeId()) > 0) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "compensation_conflict");
            }
        }
    }

    @Transactional(readOnly = true)
    public ReviewConflictDetail detail(
            String ownerKey, List<Long> authorizedAccountIds, UUID conflictPublicId) {
        ActionConflict conflict = authorizedAccountIds.stream()
            .map(accountId -> conflicts.findByPublicIdAndAccountId(
                conflictPublicId, accountId).orElse(null))
            .filter(value -> value != null)
            .findFirst().orElseThrow(ActionConflictService::notFound);
        if (!"OPEN".equals(conflict.getStatus())) throw stale();
        UserAction lower = actions.findByIdAndAccountId(
            conflict.getLowerActionId(), conflict.getAccountId()).orElseThrow(
                ActionConflictService::notFound);
        UserAction higher = actions.findByIdAndAccountId(
            conflict.getHigherActionId(), conflict.getAccountId()).orElseThrow(
                ActionConflictService::notFound);
        ActionIntent lowerIntent = actionIntents.findByActionIdAndAccountId(
            lower.getId(), lower.getAccountId()).orElseThrow();
        ActionIntent higherIntent = actionIntents.findByActionIdAndAccountId(
            higher.getId(), higher.getAccountId()).orElseThrow();
        UserAccount account = accounts.findById(conflict.getAccountId())
            .orElseThrow(ActionConflictService::notFound);
        String subject = subject(conflict);
        return new ReviewConflictDetail(
            conflict.getPublicId(),
            account.getEmail() + " (Gmail)",
            label(lower.getActionType()),
            label(higher.getActionType()),
            lowerIntent.getSafeTargetLabel(),
            higherIntent.getSafeTargetLabel(),
            conflict.getOverlapCount(),
            "These actions overlap and cannot execute together.",
            conflict.getCreatedAt(),
            ActionCompatibility.compatible(
                ActionType.valueOf(lower.getActionType()),
                ActionType.valueOf(higher.getActionType())),
            operationIntents.issue(ownerKey, "RESOLVE_CONFLICT", subject));
    }

    @Transactional
    public Resolution resolve(
            String ownerKey,
            List<Long> authorizedAccountIds,
            UUID conflictPublicId,
            String command,
            String intent) {
        List<Long> ordered = authorizedAccountIds.stream().distinct().sorted().toList();
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(ordered).size() != ordered.size()) {
            throw notFound();
        }
        ActionConflict conflict = conflicts.findForUpdateByPublicIdAndAccountIdIn(
            conflictPublicId, ordered).orElseThrow(ActionConflictService::notFound);
        if (!"OPEN".equals(conflict.getStatus())) throw stale();
        operationIntents.verify(
            ownerKey, intent, "RESOLVE_CONFLICT", subject(conflict));

        UserAction lower = actions.findForUpdateByIdAndAccountId(
            conflict.getLowerActionId(), conflict.getAccountId()).orElseThrow(
                ActionConflictService::notFound);
        UserAction higher = actions.findForUpdateByIdAndAccountId(
            conflict.getHigherActionId(), conflict.getAccountId()).orElseThrow(
                ActionConflictService::notFound);
        return switch (command == null ? "" : command) {
            case "CANCEL_LOWER" -> {
                cancel(lower);
                resolveOpenForCancelled(lower);
                yield new Resolution("LOWER_CANCELLED", lower.getPublicId());
            }
            case "CANCEL_HIGHER" -> {
                cancel(higher);
                resolveOpenForCancelled(higher);
                yield new Resolution("HIGHER_CANCELLED", higher.getPublicId());
            }
            case "KEEP_BOTH" -> {
                if (!ActionCompatibility.compatible(
                        ActionType.valueOf(lower.getActionType()),
                        ActionType.valueOf(higher.getActionType()))) {
                    throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "incompatible_actions_cannot_be_kept");
                }
                conflict.resolve("KEEP_BOTH_COMPATIBLE", clock.instant());
                conflicts.saveAndFlush(conflict);
                restoreIfClear(lower);
                restoreIfClear(higher);
                yield new Resolution("KEEP_BOTH_COMPATIBLE", null);
            }
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "invalid_conflict_resolution");
        };
    }

    void cancel(UserAction action) {
        if ("CANCELLED".equals(action.getStatus())) return;
        if (!"QUEUED".equals(action.getStatus()) && !"BLOCKED".equals(action.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "action_not_cancellable");
        }
        action.setStatus("CANCELLED");
        actions.save(action);
        events.save(ActionEvent.from(
            action, null, "ACTION_CANCELLED", null, clock.instant()));
    }

    void resolveOpenForCancelled(UserAction cancelled) {
        Instant now = clock.instant();
        for (ActionConflict conflict : conflicts.findOpenByAction(
                cancelled.getAccountId(), cancelled.getId())) {
            boolean lower = conflict.getLowerActionId().equals(cancelled.getId());
            conflict.resolve(lower ? "LOWER_CANCELLED" : "HIGHER_CANCELLED", now);
            conflicts.saveAndFlush(conflict);
            long survivorId = lower
                ? conflict.getHigherActionId() : conflict.getLowerActionId();
            actions.findByIdAndAccountId(survivorId, cancelled.getAccountId())
                .ifPresent(this::restoreIfClear);
        }
    }

    private void restoreIfClear(UserAction action) {
        if ("BLOCKED".equals(action.getStatus())
                && conflicts.findOpenByAction(
                    action.getAccountId(), action.getId()).isEmpty()
                && !"REVIEW".equals(action.getActionType())) {
            action.setStatus("QUEUED");
            actions.save(action);
        }
    }

    private void block(UserAction action) {
        if ("QUEUED".equals(action.getStatus())) {
            action.setStatus("BLOCKED");
            actions.save(action);
        }
    }

    private static String subject(ActionConflict conflict) {
        return ReviewOperationIntentService.subject(
            conflict.getPublicId(), conflict.getLowerActionId(),
            conflict.getHigherActionId(), conflict.getOverlapCount(), conflict.getStatus());
    }

    private static String label(String actionType) {
        String value = actionType.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "conflict_not_found");
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "conflict_changed");
    }

    public record Resolution(String code, UUID cancelledActionPublicId) {
    }
}
