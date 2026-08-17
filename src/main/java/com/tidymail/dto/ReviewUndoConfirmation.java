package com.tidymail.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Confirmation payload for undoing a batch of review actions, capturing how many
 * actions/accounts/messages were affected and the filter context they were undone in.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's undo capability,
 * which reverses previously approved cleanup actions.
 */
public record ReviewUndoConfirmation(
    int actionCount,
    int accountCount,
    long affectedCount,
    List<UUID> actionPublicIds,
    String intent,
    Map<String, String> filterParameters
) {
    public ReviewUndoConfirmation {
        if (actionCount <= 0 || accountCount <= 0 || affectedCount < 0
                || intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("Invalid Review undo confirmation");
        }
        actionPublicIds = List.copyOf(actionPublicIds);
        filterParameters = Map.copyOf(filterParameters);
    }
}
