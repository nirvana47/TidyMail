package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

/**
 * An immutable snapshot of a staged bulk-action scope: the exact set of
 * message IDs an action would affect, plus its target/risk summaries.
 *
 * <p>Product functionality supported: the <em>Bulk Actions</em> staging flow.
 * The scope is identified by a hash so the same selection can be reused, and
 * its message IDs are what later get executed against the mailbox.</p>
 */
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
