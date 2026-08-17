package com.tidymail.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * A bounded, normalized sender scope used to restrict analytics to a single
 * sender over a time window.
 *
 * <p>Product functionality supported: the <em>Analytics</em> sender drill-down
 * (e.g. &quot;show me exactly what this sender sent between these dates&quot;),
 * which later feeds the Senders page's exact-scope view.</p>
 */
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
