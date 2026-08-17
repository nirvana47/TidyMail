package com.tidymail.action;

/**
 * The lifecycle status of a single execution attempt of an action (reserved,
 * running, applied, partial, verification-pending, failed).
 *
 * <p>Product functionality supported: the action execution engine's per-attempt
 * state machine and outcome tracking.
 */
public enum ExecutionStatus {
    RESERVED,
    RUNNING,
    APPLIED,
    PARTIAL,
    VERIFICATION_PENDING,
    FAILED
}
