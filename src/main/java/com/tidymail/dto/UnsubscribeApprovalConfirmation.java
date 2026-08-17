package com.tidymail.dto;

import java.util.UUID;

public record UnsubscribeApprovalConfirmation(
    UUID actionPublicId,
    String targetLabel,
    int targetCount,
    String method,
    String confirmation
) {
}
