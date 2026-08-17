package com.tidymail.dto;

import java.time.Instant;
import java.util.Objects;

public record AnalyticsSenderScope(
    long accountId,
    String senderKey,
    Instant startInclusive,
    Instant endExclusive
) {
    public AnalyticsSenderScope {
        senderKey = Objects.requireNonNull(senderKey, "senderKey").trim().toLowerCase();
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (accountId <= 0 || senderKey.isBlank() || senderKey.length() > 320
                || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Invalid analytics sender scope");
        }
    }
}
