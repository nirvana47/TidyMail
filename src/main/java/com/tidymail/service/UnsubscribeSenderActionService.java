package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionScopeDigest;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.SelectionScopePreview.State;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import com.tidymail.repository.UserAccountRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the preview for a bulk cleanup action (archive/trash) on a single
 * unsubscribe candidate sender, respecting protection and the exact-message cap.
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's sender
 * cleanup preview and execution entry point.
 */
@Service
public class UnsubscribeSenderActionService {
    private static final int SCOPE_LIMIT = 1000;

    private final UserAccountRepository accounts;
    private final UnsubscribeCandidateRepository candidates;
    private final ActionScopeService scopes;
    private final ActionConfirmationIntentService confirmations;

    public UnsubscribeSenderActionService(
            UserAccountRepository accounts,
            UnsubscribeCandidateRepository candidates,
            ActionScopeService scopes,
            ActionConfirmationIntentService confirmations) {
        this.accounts = accounts;
        this.candidates = candidates;
        this.scopes = scopes;
        this.confirmations = confirmations;
    }

    /** Previews the cleanup action for the sender's messages. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SelectionScopePreview preview(
            String ownerKey,
            List<Long> authorizedAccountIds,
            UnsubscribeQuery query,
            String rowKey,
            PreviewAction action) {
        if (ownerKey == null || ownerKey.isBlank() || authorizedAccountIds == null
                || authorizedAccountIds.isEmpty() || query == null
                || action != PreviewAction.ARCHIVE && action != PreviewAction.TRASH) {
            throw new IllegalArgumentException("Valid unsubscribe sender action is required");
        }
        DecisionRowKey.Locator locator =
            DecisionRowKey.decode(com.tidymail.dto.SelectionSurface.SENDERS, rowKey);
        if (!authorizedAccountIds.contains(locator.accountId())) {
            throw new IllegalArgumentException("Sender is outside the authorized account scope");
        }
        UserAccount account = accounts.findAllForUpdateByIdInOrderByIdAsc(
            List.of(locator.accountId())).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Owned account changed"));
        if (candidates.isProtectedSender(locator.accountId(), locator.target())) {
            return new SelectionScopePreview(
                State.PROTECTED_BLOCKED, List.of(), 0, 0, 1, 0, 0, 1, 0,
                null, 0, null, null);
        }
        List<String> messageIds = candidates.findSenderMessageIds(
            locator.accountId(), locator.target(),
            query.startInclusive(), query.endExclusive(), SCOPE_LIMIT + 1);
        if (messageIds.isEmpty()) {
            return new SelectionScopePreview(
                State.EMPTY, List.of(), 0, 0, 0, 0, 0, 0, 0,
                null, 0, null, null);
        }
        if (messageIds.size() > SCOPE_LIMIT) {
            return new SelectionScopePreview(
                State.CAP_EXCEEDED, List.of(), 1, 0, 0, 0, 0, 0, 0,
                account.getEmail() + " (Gmail)", messageIds.size(), null, null);
        }
        ActionType actionType = action == PreviewAction.ARCHIVE
            ? ActionType.ARCHIVE : ActionType.TRASH;
        var scope = scopes.createOrGetRequired(
            locator.accountId(),
            actionType.name(),
            "SENDER_SELECTION",
            messageIds,
            new ActionScopeTargetSummary(
                "1 selected sender", locator.target(), null, 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        String confirmation = confirmations.issue(
            ownerKey,
            actionType,
            DecisionSource.UNSUBSCRIBE,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                locator.accountId(), scope.scopeHash())));
        return new SelectionScopePreview(
            State.READY,
            List.of(new SelectionScopeDigest(
                account.getEmail() + " (Gmail)",
                scope.scopeHash(),
                scope.messageCount())),
            1,
            scope.messageCount(),
            0,
            0,
            0,
            0,
            0,
            null,
            0,
            null,
            confirmation);
    }
}
