package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Details of a conflict between two staged cleanup actions that overlap on the same
 * messages (e.g. one action wants to archive while another wants to trash them).
 *
 * <p>Product functionality supported: the <em>Review</em> flow's conflict resolution,
 * which explains overlaps and lets the user decide how to resolve them.
 */
public record ReviewConflictDetail(
    UUID conflictPublicId,
    String accountLabel,
    String lowerActionLabel,
    String higherActionLabel,
    String lowerTargetLabel,
    String higherTargetLabel,
    int overlapCount,
    String explanation,
    Instant createdAt,
    boolean keepBothAllowed,
    String resolutionIntent
) {
}
