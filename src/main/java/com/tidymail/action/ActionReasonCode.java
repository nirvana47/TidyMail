package com.tidymail.action;

/**
 * The reasons an action's execution could fail or be left in a non-applied state,
 * used to populate failure details and analytics.
 *
 * <p>Product functionality supported: the action execution engine's failure
 * accounting, undo/verification status, and analytics outcome reporting.
 */
public enum ActionReasonCode {
    NONE,
    PROVIDER_REJECTED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_RESULT_INVALID,
    RESULT_UNKNOWN_AFTER_DISPATCH,
    NOT_DISPATCHED,
    STALE_ACTION,
    CONFLICT,
    PROTECTED_TARGET,
    FRESH_CONSENT_REQUIRED,
    LEASE_EXPIRED
}
