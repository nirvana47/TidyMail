package com.tidymail.dto;

import java.util.List;

/**
 * API response for a page of sender cards, including cursor-based pagination and
 * domain rollups.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's sender listing
 * and domain aggregation.
 */
public record SenderPageResponse(
    List<SenderCard> items,
    String nextCursor,
    int totalUniqueSenders,
    int totalMessagesScanned,
    List<DomainRollup> domainRollups
) {
}
