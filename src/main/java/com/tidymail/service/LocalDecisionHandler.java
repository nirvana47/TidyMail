package com.tidymail.service;

import com.tidymail.action.ActionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Handles local (non-provider) decision actions: KEEP and REVIEW. REVIEW results
 * block the action pending review; KEEP is recorded as a local success.
 *
 * <p>Product functionality supported: the action execution engine's local
 * keep/review handling.
 */
@Service
public class LocalDecisionHandler implements ActionHandler {
    /** Returns true for KEEP and REVIEW actions. */
    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.KEEP || actionType == ActionType.REVIEW;
    }

    /** Executes the local decision, blocking REVIEW actions. */
    @Override
    public HandlerResult execute(ExecutionContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Local handler entry must be transaction-free");
        }
        if (!supports(context.actionType())) {
            throw new IllegalArgumentException("Unsupported local decision");
        }
        var result = ProviderResultClassifier.allSucceeded(context.targetOrdinals());
        return context.actionType() == ActionType.REVIEW
            ? HandlerResult.review(result)
            : HandlerResult.local(result);
    }
}
