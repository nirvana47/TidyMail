package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

/**
 * A compact card describing a sender aggregated from indexed metadata, used for
 * quick scanning in the Senders surface.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's sender overview
 * and bulk cleanup decision-making.
 */
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
