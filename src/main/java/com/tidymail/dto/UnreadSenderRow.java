package com.tidymail.dto;

import com.tidymail.service.DecisionRuleSet.UnreadCategory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record UnreadSenderRow(
    long accountId,
    String senderAddress,
    String displayName,
    String domain,
    long unreadCount,
    Long estimatedSize,
    UnreadCategory inferredType,
    boolean protectedSender,
    List<String> rowKeys,
    Map<String, String> actionQuery
) {
    public UnreadSenderRow {
        if (accountId <= 0 || senderAddress == null || displayName == null
                || unreadCount < 0 || inferredType == null) {
            throw new IllegalArgumentException("Valid unread sender row is required");
        }
        if (estimatedSize != null && estimatedSize < 0) {
            throw new IllegalArgumentException("Estimated size cannot be negative");
        }
        rowKeys = List.copyOf(rowKeys);
        actionQuery = Collections.unmodifiableMap(new LinkedHashMap<>(actionQuery));
    }

    public String estimatedSizeLabel() {
        return EstimatedSizeLabel.format(estimatedSize);
    }
}
