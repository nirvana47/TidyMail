package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Confirmation payload for undoing a previously executed cleanup action, with the
 * affected counts, the inverse label, and the undo window expiry.
 *
 * <p>Product functionality supported: the <em>Undo</em> capability, which reverses
 * recently applied cleanup actions within a bounded window.
 */
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
