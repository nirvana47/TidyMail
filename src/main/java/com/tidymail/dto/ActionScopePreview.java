package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

public record ActionScopePreview(
        Long scopeId,
        String scopeHash,
        int messageCount,
        List<String> messageIds,
        String actionType,
        String targetKind,
        ActionScopeTargetSummary targetSummary,
        ActionScopeRiskSummary riskSummary,
        Instant createdAt) {

    public ActionScopePreview {
        messageIds = List.copyOf(messageIds);
    }

    @Override
    public List<String> messageIds() {
        return List.copyOf(messageIds);
    }
}
