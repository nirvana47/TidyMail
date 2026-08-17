package com.tidymail.service;

import com.tidymail.dto.ApprovalConfirmation;
import com.tidymail.dto.BatchExecutionReceipt;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.dto.ReviewUndoConfirmation;
import com.tidymail.action.OperationKind;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ReviewActionQueryRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates approving and executing queued cleanup actions from the Review page:
 * confirming selected or all eligible actions, executing them, undoing them, and
 * cancelling them, all guarded by operation intents and conflict checks.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's approve, execute,
 * undo, and cancel capabilities.
 */
@Service
public class ActionApprovalService {
    private static final int MAX_SELECTED = 100;
    private final ReviewActionQueryRepository review;
    private final ReviewOperationIntentService intents;
    private final ExactScopeExecutor executor;
    private final UserAccountRepository accounts;
    private final UserActionRepository actions;
    private final ActionExecutionRepository executions;
    private final ActionConflictService conflicts;
    private final int maximumAllActions;

    public ActionApprovalService(
            ReviewActionQueryRepository review,
            ReviewOperationIntentService intents,
            ExactScopeExecutor executor,
            UserAccountRepository accounts,
            UserActionRepository actions,
            ActionExecutionRepository executions,
            ActionConflictService conflicts,
            @Value("${tidymail.review.max-actions:100}") int maximumAllActions) {
        this.review = review;
        this.intents = intents;
        this.executor = executor;
        this.accounts = accounts;
        this.actions = actions;
        this.executions = executions;
        this.conflicts = conflicts;
        this.maximumAllActions = maximumAllActions;
    }

    /** Builds an approval confirmation for the selected queued actions. */
    public ApprovalConfirmation confirmSelected(
            String ownerKey,
            List<Long> authorizedAccountIds,
            ReviewQuery query,
            List<UUID> selectedIds) {
        List<UUID> selected = uniqueActions(selectedIds, MAX_SELECTED);
        List<ReviewActionQueryRepository.ApprovalCandidate> candidates =
            selectedCandidates(authorizedAccountIds, query, selected);
        if (candidates.size() != selected.size()) stale();
        requireEligible(candidates);
        return confirmation(ownerKey, "selected", query, candidates, Map.of());
    }

    /** Builds an approval confirmation for all eligible actions under the query. */
    public ApprovalConfirmation confirmAll(
            String ownerKey,
            List<Long> authorizedAccountIds,
            ReviewQuery query) {
        List<ReviewActionQueryRepository.ApprovalCandidate> all =
            review.approvalCandidates(authorizedAccountIds, query);
        List<ReviewActionQueryRepository.ApprovalCandidate> eligible =
            all.stream().filter(
                ReviewActionQueryRepository.ApprovalCandidate::eligible).toList();
        if (eligible.size() > maximumAllActions) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "approval_set_requires_narrower_filters");
        }
        if (eligible.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "no_eligible_actions");
        }
        return confirmation(ownerKey, "all", query, eligible, exclusions(all));
    }

    /** Executes the selected actions, verifying the operation intent. */
    public BatchExecutionReceipt executeSelected(
            String ownerKey,
            List<Long> authorizedAccountIds,
            ReviewQuery query,
            List<UUID> selectedIds,
            String intent) {
        List<UUID> selected = uniqueActions(selectedIds, MAX_SELECTED);
        List<ReviewActionQueryRepository.ApprovalCandidate> candidates =
            selectedCandidates(authorizedAccountIds, query, selected);
        if (candidates.size() == selected.size()) {
            requireEligible(candidates);
        } else {
            candidates = replayCandidates(authorizedAccountIds, selected, false);
        }
        return execute(ownerKey, "selected", query, candidates, intent);
    }

    /** Executes all eligible actions, verifying the operation intent and set. */
    public BatchExecutionReceipt executeAll(
            String ownerKey,
            List<Long> authorizedAccountIds,
            ReviewQuery query,
            List<UUID> confirmedIds,
            String intent) {
        List<UUID> confirmed = uniqueActions(confirmedIds, maximumAllActions);
        List<ReviewActionQueryRepository.ApprovalCandidate> all =
            review.approvalCandidates(authorizedAccountIds, query);
        List<ReviewActionQueryRepository.ApprovalCandidate> eligible =
            all.stream().filter(
                ReviewActionQueryRepository.ApprovalCandidate::eligible).toList();
        if (eligible.size() > maximumAllActions) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "approval_set_requires_narrower_filters");
        }
        List<UUID> currentIds = eligible.stream()
            .map(ReviewActionQueryRepository.ApprovalCandidate::actionPublicId).toList();
        if (!currentIds.equals(confirmed)) {
            eligible = replayCandidates(authorizedAccountIds, confirmed, true);
        }
        return execute(ownerKey, "all", query, eligible, intent);
    }

    /** Builds an undo confirmation for the selected actions. */
    public ReviewUndoConfirmation confirmUndoSelected(
            String ownerKey,
            List<Long> authorizedAccountIds,
            ReviewQuery query,
            List<UUID> selectedIds) {
        List<UUID> selected = uniqueActions(selectedIds, MAX_SELECTED);
        List<ReviewActionQueryRepository.ApprovalCandidate> candidates =
            selectedCandidates(authorizedAccountIds, query, selected);
        if (candidates.size() != selected.size()) stale();
        String subject = undoSubject(query, candidates);
        return new ReviewUndoConfirmation(
            candidates.size(),
            Math.toIntExact(candidates.stream()
                .map(ReviewActionQueryRepository.ApprovalCandidate::accountId)
                .distinct().count()),
            candidates.stream().mapToLong(
                ReviewActionQueryRepository.ApprovalCandidate::targetCount).sum(),
            candidates.stream().map(
                ReviewActionQueryRepository.ApprovalCandidate::actionPublicId).toList(),
            intents.issue(ownerKey, "UNDO_SELECTED", subject),
            query.withoutPage());
    }

    /** Undoes the selected actions, cancelling them and resolving open conflicts. */
    @Transactional
    public void undoSelected(
            String ownerKey,
            List<Long> authorizedAccountIds,
            ReviewQuery query,
            List<UUID> selectedIds,
            String intent) {
        List<UUID> selected = uniqueActions(selectedIds, MAX_SELECTED);
        List<ReviewActionQueryRepository.ApprovalCandidate> candidates =
            selectedCandidates(authorizedAccountIds, query, selected);
        if (candidates.size() != selected.size()) stale();
        intents.verify(ownerKey, intent, "UNDO_SELECTED", undoSubject(query, candidates));

        List<Long> orderedAccounts = candidates.stream()
            .map(ReviewActionQueryRepository.ApprovalCandidate::accountId)
            .distinct().sorted().toList();
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(orderedAccounts).size()
                != orderedAccounts.size()) {
            throw notFound();
        }
        for (ReviewActionQueryRepository.ApprovalCandidate candidate : candidates) {
            UserAction action = actions.findForUpdateByPublicIdAndAccountId(
                candidate.actionPublicId(), candidate.accountId())
                .orElseThrow(ActionApprovalService::notFound);
            if (!Set.of("QUEUED", "BLOCKED").contains(action.getStatus())) stale();
            conflicts.cancel(action);
            conflicts.resolveOpenForCancelled(action);
        }
    }

    /** Cancels a single action after verifying the operation intent. */
    @Transactional
    public void cancel(
            String ownerKey,
            List<Long> authorizedAccountIds,
            UUID actionPublicId,
            String intent) {
        List<Long> ordered = authorizedAccountIds.stream().distinct().sorted().toList();
        if (accounts.findAllForUpdateByIdInOrderByIdAsc(ordered).size() != ordered.size()) {
            throw notFound();
        }
        UserAction action = ordered.stream()
            .map(accountId -> actions.findForUpdateByPublicIdAndAccountId(
                actionPublicId, accountId).orElse(null))
            .filter(value -> value != null)
            .findFirst().orElseThrow(ActionApprovalService::notFound);
        this.intents.verify(
            ownerKey, intent, "CANCEL",
            ReviewOperationIntentService.subject(actionPublicId));
        if ("CANCELLED".equals(action.getStatus())) return;
        conflicts.cancel(action);
        conflicts.resolveOpenForCancelled(action);
    }

    /** Executes a batch of candidates and summarizes the receipts. */
    private BatchExecutionReceipt execute(
            String ownerKey,
            String kind,
            ReviewQuery query,
            List<ReviewActionQueryRepository.ApprovalCandidate> candidates,
            String intent) {
        String subject = subject(kind, query, candidates);
        this.intents.verify(
            ownerKey, intent,
            "all".equals(kind) ? "APPROVE_ALL" : "APPROVE_SELECTED",
            subject);
        List<ExactScopeExecutor.BatchAction> batch = candidates.stream()
            .map(candidate -> new ExactScopeExecutor.BatchAction(
                candidate.accountId(), candidate.actionPublicId(),
                UUID.nameUUIDFromBytes((intent + ":" + candidate.actionPublicId())
                    .getBytes(StandardCharsets.UTF_8))))
            .toList();
        List<ExactScopeExecutor.ExecutionReceipt> receipts =
            executor.executeBatch(batch);
        return new BatchExecutionReceipt(
            receipts.size(),
            Math.toIntExact(candidates.stream()
                .map(ReviewActionQueryRepository.ApprovalCandidate::accountId)
                .distinct().count()),
            receipts.stream().mapToLong(
                ExactScopeExecutor.ExecutionReceipt::requested).sum(),
            receipts.stream().mapToLong(
                ExactScopeExecutor.ExecutionReceipt::succeeded).sum(),
            receipts.stream().mapToLong(
                ExactScopeExecutor.ExecutionReceipt::failed).sum(),
            receipts.stream().mapToLong(
                ExactScopeExecutor.ExecutionReceipt::verificationPending).sum(),
            receipts);
    }

    /** Builds an approval confirmation with warnings and an operation intent. */
    private ApprovalConfirmation confirmation(
            String ownerKey,
            String kind,
            ReviewQuery query,
            List<ReviewActionQueryRepository.ApprovalCandidate> candidates,
            Map<String, Long> excluded) {
        String operation = "all".equals(kind)
            ? "APPROVE_ALL" : "APPROVE_SELECTED";
        String subject = subject(kind, query, candidates);
        return new ApprovalConfirmation(
            kind,
            candidates.size(),
            Math.toIntExact(candidates.stream()
                .map(ReviewActionQueryRepository.ApprovalCandidate::accountId)
                .distinct().count()),
            candidates.stream().mapToLong(
                ReviewActionQueryRepository.ApprovalCandidate::targetCount).sum(),
            candidates.stream().map(
                ReviewActionQueryRepository.ApprovalCandidate::actionPublicId).toList(),
            excluded,
            warning(candidates),
            intents.issue(ownerKey, operation, subject),
            query.withoutPage());
    }

    /** Produces a user-facing warning appropriate to the action types being approved. */
    private static ApprovalConfirmation.Warning warning(
            List<ReviewActionQueryRepository.ApprovalCandidate> candidates) {
        Set<String> types = candidates.stream()
            .map(ReviewActionQueryRepository.ApprovalCandidate::actionType)
            .collect(java.util.stream.Collectors.toSet());
        if (types.contains("TRASH") || types.contains("DELETE")) {
            return new ApprovalConfirmation.Warning(
                "Trash and provider recovery window",
                "Trash moves emails to the provider Trash. The provider controls "
                    + "the recovery window before permanent deletion. Messages moved "
                    + "to Gmail Trash can generally be restored for up to 30 days "
                    + "before permanent deletion.",
                true);
        }
        if (types.equals(Set.of("UNSUBSCRIBE"))) {
            return new ApprovalConfirmation.Warning(
                "Unsubscribe warning",
                "Unsubscribe sends a request to the sender. Future delivery may "
                    + "not stop immediately.",
                false);
        }
        if (types.equals(Set.of("ARCHIVE"))) {
            return new ApprovalConfirmation.Warning(
                "Archive warning",
                "Archive removes emails from the inbox without deleting them.",
                false);
        }
        return new ApprovalConfirmation.Warning(
            "Execute selected actions",
            "Only the exact materialized email scopes shown here will be executed.",
            false);
    }

    /** Loads the selected candidates from the review query, or empty if any is missing. */
    private List<ReviewActionQueryRepository.ApprovalCandidate> selectedCandidates(
            List<Long> authorizedAccountIds,
            ReviewQuery query,
            List<UUID> selected) {
        Map<UUID, ReviewActionQueryRepository.ApprovalCandidate> byId =
            new LinkedHashMap<>();
        review.approvalCandidates(authorizedAccountIds, query)
            .forEach(candidate -> byId.put(candidate.actionPublicId(), candidate));
        List<ReviewActionQueryRepository.ApprovalCandidate> result = new ArrayList<>();
        for (UUID id : selected) {
            ReviewActionQueryRepository.ApprovalCandidate candidate = byId.get(id);
            if (candidate == null) return List.of();
            result.add(candidate);
        }
        return result.stream()
            .sorted(java.util.Comparator.comparingLong(
                    ReviewActionQueryRepository.ApprovalCandidate::accountId)
                .thenComparingLong(
                    ReviewActionQueryRepository.ApprovalCandidate::actionId))
            .toList();
    }

    /** Rebuilds candidates from already-executed actions for idempotent replay. */
    private List<ReviewActionQueryRepository.ApprovalCandidate> replayCandidates(
            List<Long> authorizedAccountIds,
            List<UUID> selected,
            boolean strictLifo) {
        List<ReviewActionQueryRepository.ApprovalCandidate> result = new ArrayList<>();
        for (UUID publicId : selected) {
            UserAction action = authorizedAccountIds.stream()
                .map(accountId -> actions.findByPublicIdAndAccountId(
                    publicId, accountId).orElse(null))
                .filter(value -> value != null)
                .findFirst().orElseThrow(ActionApprovalService::notFound);
            if (!Set.of("EXECUTING", "APPLIED", "PARTIAL",
                    "VERIFICATION_PENDING", "FAILED").contains(action.getStatus())
                    || Set.of("REVIEW", "UNSUBSCRIBE").contains(action.getActionType())
                    || executions.findByActionIdAndOperationKind(
                        action.getId(), OperationKind.FORWARD.name()).isEmpty()) {
                stale();
            }
            result.add(new ReviewActionQueryRepository.ApprovalCandidate(
                action.getId(), action.getPublicId(), action.getAccountId(),
                action.getActionType(), action.getAffectedCount(), true, 0,
                "QUEUED", "", action.getQueuedAt(), action.getSequence()));
        }
        if (strictLifo) {
            return result.stream().sorted(
                java.util.Comparator.comparing(
                        ReviewActionQueryRepository.ApprovalCandidate::queuedAt)
                    .thenComparingLong(
                        ReviewActionQueryRepository.ApprovalCandidate::sequence)
                    .reversed())
                .toList();
        }
        return result.stream().sorted(
            java.util.Comparator.comparingLong(
                    ReviewActionQueryRepository.ApprovalCandidate::accountId)
                .thenComparingLong(
                    ReviewActionQueryRepository.ApprovalCandidate::actionId))
            .toList();
    }

    /** Rejects the set if any candidate is no longer eligible. */
    private static void requireEligible(
            List<ReviewActionQueryRepository.ApprovalCandidate> candidates) {
        if (candidates.stream().anyMatch(candidate -> !candidate.eligible())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "selected_action_not_eligible");
        }
    }

    /** Validates a bounded, unique list of action public IDs. */
    private static List<UUID> uniqueActions(List<UUID> values, int maximum) {
        if (maximum <= 0 || values == null || values.isEmpty()
                || values.size() > maximum
                || values.stream().anyMatch(value -> value == null)
                || new HashSet<>(values).size() != values.size()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "invalid_selected_actions");
        }
        return List.copyOf(values);
    }

    /** Counts why actions were excluded from an all-approval set. */
    private static Map<String, Long> exclusions(
            List<ReviewActionQueryRepository.ApprovalCandidate> all) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("CONFLICT", all.stream().filter(
            candidate -> candidate.conflictCount() > 0).count());
        result.put("NEEDS_DECISION", all.stream().filter(
            candidate -> "REVIEW".equals(candidate.actionType())).count());
        result.put("FRESH_CONSENT_REQUIRED", all.stream().filter(
            candidate -> "UNSUBSCRIBE".equals(candidate.actionType())).count());
        result.values().removeIf(value -> value == 0);
        return result;
    }

    /** Builds the operation-intent subject for an approval. */
    private static String subject(
            String kind,
            ReviewQuery query,
            List<ReviewActionQueryRepository.ApprovalCandidate> candidates) {
        List<Object> values = new ArrayList<>();
        values.add(kind);
        values.add(query.view());
        values.add(query.account());
        values.add(query.action());
        values.add(query.state());
        values.add(query.target());
        candidates.forEach(candidate -> {
            values.add(candidate.accountId());
            values.add(candidate.actionPublicId());
            values.add(candidate.actionType());
            values.add(candidate.targetCount());
            values.add(candidate.status());
            values.add(candidate.conflictCount());
        });
        return ReviewOperationIntentService.subject(values.toArray());
    }

    /** Builds the operation-intent subject for an undo. */
    private static String undoSubject(
            ReviewQuery query,
            List<ReviewActionQueryRepository.ApprovalCandidate> candidates) {
        List<Object> values = new ArrayList<>();
        values.add("undo-selected");
        values.add(query.withoutPage());
        candidates.stream()
            .sorted(java.util.Comparator.comparing(
                ReviewActionQueryRepository.ApprovalCandidate::actionPublicId))
            .forEach(candidate -> {
                values.add(candidate.actionPublicId());
                values.add(candidate.accountId());
                values.add(candidate.targetCount());
                values.add(candidate.status());
            });
        return ActionFingerprints.bind("review-undo-selected-v1", values.toArray());
    }

    /** Throws a conflict when the action set changed underneath the request. */
    private static void stale() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "action_set_changed");
    }

    /** Returns a not-found exception for a missing action. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
    }
}
