package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

public record SenderCard(
    String fromAddress,
    String fromDisplayName,
    String fromDomain,
    int totalCount,
    int unreadCount,
    Instant oldestMessageAt,
    Instant newestMessageAt,
    int replyCount,
    int bulkMarketingScore,
    boolean hasListUnsubscribe,
    boolean hasPrecedenceBulk,
    List<String> sampleMessageIds
) {
}
