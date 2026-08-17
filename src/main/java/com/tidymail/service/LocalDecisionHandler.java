package com.tidymail.service;

import com.tidymail.action.ActionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LocalDecisionHandler implements ActionHandler {
    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.KEEP || actionType == ActionType.REVIEW;
    }

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
