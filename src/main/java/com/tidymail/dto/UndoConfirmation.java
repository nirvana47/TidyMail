package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

public record UndoConfirmation(
    UUID actionPublicId,
    long accountId,
    String actionLabel,
    String accountLabel,
    int appliedCount,
    int excludedCount,
    String inverseLabel,
    Instant expiresAt,
    String intent
) {
}
