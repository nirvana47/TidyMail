package com.tidymail.dto;

import java.util.List;

public record SenderPageResponse(
    List<SenderCard> items,
    String nextCursor,
    int totalUniqueSenders,
    int totalMessagesScanned,
    List<DomainRollup> domainRollups
) {
}
