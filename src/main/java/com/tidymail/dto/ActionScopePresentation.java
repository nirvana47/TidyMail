package com.tidymail.dto;

public record ActionScopePresentation(
    int messageCount,
    String actionType,
    ActionScopeTargetSummary targetSummary,
    ActionScopeRiskSummary riskSummary,
    String comparisonHash
) {
    public static ActionScopePresentation from(ActionScopePreview preview) {
        return new ActionScopePresentation(
            preview.messageCount(),
            preview.actionType(),
            preview.targetSummary(),
            preview.riskSummary(),
            preview.scopeHash().substring(0, 12));
    }
}
