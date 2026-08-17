package com.tidymail.mail;

/**
 * Tracks whether a message is known to have attachments.
 * <p>
 * Product functionality supported: metadata-only mailbox sync, reporting
 * attachment presence without exposing message bodies.
 */
public enum AttachmentState {
    PRESENT,
    ABSENT,
    UNKNOWN
}
