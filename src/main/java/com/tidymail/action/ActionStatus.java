package com.tidymail.action;

/**
 * The lifecycle status of a cleanup action (queued, blocked, executing, applied,
 * failed, cancelled, undone, compensated, etc.). Governed by {@code ActionStateRules}.
 *
 * <p>Product functionality supported: the action execution engine's state machine
 * and the Review/history status display.
 */
public enum ActionStatus {
    QUEUED,
    BLOCKED,
    EXECUTING,
    APPLIED,
    PARTIAL,
    VERIFICATION_PENDING,
    FAILED,
    CANCELLED,
    UNDONE,
    PARTIALLY_UNDONE,
    COMPENSATED
}
