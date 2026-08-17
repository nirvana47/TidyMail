package com.tidymail.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
