package com.tidymail.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewActionRow(
    UUID actionPublicId,
    UUID conflictPublicId,
    ReviewActionGroup.Kind group,
    String actionLabel,
    String accountLabel,
    String sourceLabel,
    String targetLabel,
    int targetCount,
    String mode,
    String status,
    Instant queuedAt,
    boolean eligible,
    int conflictCount,
    int overlapCount,
    List<String> reasons,
    String cancellationIntent
) {
    public ReviewActionRow {
        reasons = List.copyOf(reasons);
        if (actionPublicId == null || group == null || actionLabel == null
                || accountLabel == null || sourceLabel == null
                || targetLabel == null || targetCount < 0
                || mode == null || status == null || queuedAt == null
                || conflictCount < 0 || overlapCount < 0) {
            throw new IllegalArgumentException("Invalid review action row");
        }
    }

    public ReviewActionRow withCancellationIntent(String intent) {
        return new ReviewActionRow(
            actionPublicId, conflictPublicId, group, actionLabel, accountLabel,
            sourceLabel, targetLabel, targetCount, mode, status, queuedAt, eligible,
            conflictCount, overlapCount, reasons, intent);
    }
}
