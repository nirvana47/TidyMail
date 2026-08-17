package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row in the Review page's action-history view.
 *
 * <p>Product functionality supported: the <em>Review &gt; History</em> tab.
 * Describes one past action: what it targeted, its outcome, whether it can be
 * undone (and by how many messages), unsubscribe/protect specifics, and links
 * to external options.</p>
 */
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
