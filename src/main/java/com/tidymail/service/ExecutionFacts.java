package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ExecutionStatus;

/**
 * The count partition of an execution's outcome (requested, accepted, succeeded,
 * failed, verification-pending, not-dispatched) with a derived overall status.
 *
 * <p>Product functionality supported: the action execution engine's outcome
 * accounting and status derivation.
 */
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

    /** Derives the overall execution status from the count partition. */
    public ExecutionStatus status() {
        if (succeeded == requested) return ExecutionStatus.APPLIED;
        if (succeeded > 0 || (failed > 0 && verificationPending > 0)) {
            return ExecutionStatus.PARTIAL;
        }
        if (verificationPending > 0) return ExecutionStatus.VERIFICATION_PENDING;
        return ExecutionStatus.FAILED;
    }
}
