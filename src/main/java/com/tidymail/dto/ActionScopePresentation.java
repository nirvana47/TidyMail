package com.tidymail.dto;

/**
 * Presentation-safe view of an action scope preview, for the bulk-action
 * confirmation UI.
 *
 * <p>Product functionality supported: the <em>Bulk Actions</em> preview
 * fragment. Exposes the message count, action type, target/risk summaries, and
 * a short (12-char) comparison hash so the user can confirm what will be
 * affected before committing.</p>
 */
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
