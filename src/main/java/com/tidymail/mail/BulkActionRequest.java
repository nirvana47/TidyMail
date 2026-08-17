package com.tidymail.mail;

import java.util.List;

/**
 * Describes a bulk mailbox mutation to apply to a set of message ids.
 * <p>
 * Product functionality supported: bulk archive/trash/mark-read (and
 * mark-unread) cleanup of the inbox.
 * <p>
 * Enforces the Gmail {@code batchModify} limit of 1000 ids per call.
 */
public record BulkActionRequest(
    BulkActionType actionType,
    List<String> messageIds,
    String sourceLabelId,
    String targetLabelId
) {
    /**
     * Validates the request fields, enforcing the per-call batch limit.
     */
    public BulkActionRequest {
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
        if (messageIds == null) {
            throw new IllegalArgumentException("messageIds must not be null");
        }
        if (messageIds.size() > 1000) {
            throw new IllegalArgumentException("Gmail batchModify JSON API max is 1000 ids per call; split larger batches.");
        }
    }

    /**
     * Builds a label-only action with no source/target label context.
     */
    public static BulkActionRequest of(BulkActionType action, List<String> messageIds) {
        return new BulkActionRequest(action, messageIds, null, null);
    }
}
