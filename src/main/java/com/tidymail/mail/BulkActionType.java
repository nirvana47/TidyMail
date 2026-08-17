package com.tidymail.mail;

/**
 * The kinds of bulk mailbox mutations TidyMail can perform.
 * <p>
 * Product functionality supported: bulk archive/trash and mark-read/unread
 * inbox cleanup.
 */
public enum BulkActionType {
    ARCHIVE,
    TRASH,
    MARK_READ,
    MARK_UNREAD
}
