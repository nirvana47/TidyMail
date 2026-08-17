package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

public record ActionHistoryRow(
    UUID actionPublicId,
    String accountLabel,
    String targetLabel,
    String action,
    String mode,
    String status,
    Instant occurredAt,
    String primaryTimeLabel,
    String detailTimeLabel,
    String countLabel,
    String availabilityLabel,
    ActionOutcomeSummary outcome,
    boolean legacy,
    boolean undoEligible,
    int undoAppliedCount,
    String undoUnavailableReason,
    boolean unsubscribe,
    String linkedUnsubscribeOutcome,
    String externalOptionsUrl,
    boolean protect,
    String compensationIntent,
    UUID compensatesActionPublicId
) {
}
