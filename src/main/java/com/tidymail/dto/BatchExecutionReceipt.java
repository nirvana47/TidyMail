package com.tidymail.dto;

import java.util.List;
import com.tidymail.service.ExactScopeExecutor;

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
