package com.tidymail.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row in the Review page's action-history view.
 *
 * <p>Product functionality supported: the <em>Review &gt; History</em> tab.
 * Describes a recorded action, its outcome, and links to retained follow-up details.</p>
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
    boolean unsubscribe,
    String linkedUnsubscribeOutcome,
    String externalOptionsUrl,
    UUID compensatesActionPublicId
) {
}
