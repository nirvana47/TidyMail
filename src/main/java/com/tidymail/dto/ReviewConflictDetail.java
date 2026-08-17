package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

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
