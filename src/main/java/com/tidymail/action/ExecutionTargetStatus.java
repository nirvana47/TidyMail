package com.tidymail.action;

/**
 * The status of a single message target within an execution (pending, succeeded,
 * failed, verification-pending, reverted by undo, or compensated).
 *
 * <p>Product functionality supported: the action execution engine's per-target
 * outcome accounting and undo/compensation tracking.
 */
public enum ExecutionTargetStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    VERIFICATION_PENDING,
    REVERTED,
    COMPENSATED
}
