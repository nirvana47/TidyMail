package com.tidymail.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single staged cleanup action shown in the Review queue, describing the target
 * (sender or unread message), its account/source, eligibility, and any conflicts.
 *
 * <p>Product functionality supported: the <em>Review</em> flow, which lists queued
 * exact-scope actions and lets the user approve or cancel them.
 */
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

    /** Returns a copy of this row carrying the user's cancellation intent. */
    public ReviewActionRow withCancellationIntent(String intent) {
        return new ReviewActionRow(
            actionPublicId, conflictPublicId, group, actionLabel, accountLabel,
            sourceLabel, targetLabel, targetCount, mode, status, queuedAt, eligible,
            conflictCount, overlapCount, reasons, intent);
    }
}
