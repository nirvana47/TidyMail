package com.tidymail.action;

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
