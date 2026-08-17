package com.tidymail.action;

/**
 * The cleanup action types TidyMail can stage and execute (archive, trash,
 * mark-read, keep, protect, review, unsubscribe, remove-protection).
 *
 * <p>Product functionality supported: bulk actions, protect/compensation, review,
 * and unsubscribe across all cleanup surfaces.
 */
public enum ActionType {
    ARCHIVE,
    TRASH,
    MARK_READ,
    KEEP,
    PROTECT,
    REVIEW,
    UNSUBSCRIBE,
    REMOVE_PROTECTION
}
