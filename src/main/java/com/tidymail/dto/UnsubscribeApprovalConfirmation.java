package com.tidymail.dto;

import java.util.UUID;

/**
 * Confirmation payload for approving an unsubscribe action, with the chosen
 * method and target details.
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's approval
 * step before an unsubscribe request is sent.
 */
public record UnsubscribeApprovalConfirmation(
    UUID actionPublicId,
    String targetLabel,
    int targetCount,
    String method,
    String confirmation
) {
}
