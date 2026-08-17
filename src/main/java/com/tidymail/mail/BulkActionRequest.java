package com.tidymail.mail;

import java.util.List;

public record BulkActionRequest(
    BulkActionType actionType,
    List<String> messageIds,
    String sourceLabelId,
    String targetLabelId
) {
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

    public static BulkActionRequest of(BulkActionType action, List<String> messageIds) {
        return new BulkActionRequest(action, messageIds, null, null);
    }
}
