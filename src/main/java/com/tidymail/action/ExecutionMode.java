package com.tidymail.action;

/**
 * How a confirmed action is executed: staged (queued for later approval in the
 * Review queue) or immediate (executed as soon as it is confirmed).
 *
 * <p>Product functionality supported: the <em>Bulk Action</em> and <em>Review</em>
 * flows' staged vs. immediate execution choice.
 */
public enum ExecutionMode {
    STAGED,
    IMMEDIATE
}
