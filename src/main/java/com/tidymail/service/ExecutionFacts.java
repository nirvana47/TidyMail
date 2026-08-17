package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ExecutionStatus;

public record ExecutionFacts(
    int requested,
    int accepted,
    int succeeded,
    int failed,
    int verificationPending,
    int notDispatched,
    ActionReasonCode reason
) {
    public ExecutionFacts {
        if (requested <= 0 || accepted < 0 || succeeded < 0 || failed < 0
                || verificationPending < 0 || notDispatched < 0
                || accepted > requested || succeeded > requested || failed > requested
                || verificationPending > requested || notDispatched > requested
                || requested != succeeded + failed + verificationPending + notDispatched) {
            throw new IllegalArgumentException("Invalid execution count partition");
        }
    }

    public ExecutionStatus status() {
        if (succeeded == requested) return ExecutionStatus.APPLIED;
        if (succeeded > 0 || (failed > 0 && verificationPending > 0)) {
            return ExecutionStatus.PARTIAL;
        }
        if (verificationPending > 0) return ExecutionStatus.VERIFICATION_PENDING;
        return ExecutionStatus.FAILED;
    }
}
