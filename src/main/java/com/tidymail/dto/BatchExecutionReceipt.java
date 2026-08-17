package com.tidymail.dto;

import java.util.List;
import com.tidymail.service.ExactScopeExecutor;

/**
 * The receipt returned after executing a batch of staged actions.
 *
 * <p>Product functionality supported: the <em>Review</em> execution outcome.
 * Aggregates the counts across all executed actions and carries the per-action
 * execution receipts so the UI can show exactly what happened.</p>
 */
public record BatchExecutionReceipt(
    int actionCount,
    int accountCount,
    long requested,
    long succeeded,
    long failed,
    long verificationPending,
    List<ExactScopeExecutor.ExecutionReceipt> actions
) {
    public BatchExecutionReceipt {
        actions = List.copyOf(actions);
    }
}
