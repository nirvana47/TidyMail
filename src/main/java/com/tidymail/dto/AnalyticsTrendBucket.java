package com.tidymail.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * A single time bucket in the Analytics trend chart.
 *
 * <p>Product functionality supported: the <em>Analytics</em> trend
 * visualization. Holds the received/cleaned counts for a time range, their
 * availability, and links to the per-bucket source drill-downs.</p>
 */
public record AnalyticsTrendBucket(
    Instant startInclusive,
    Instant endExclusive,
    long received,
    long cleaned,
    AnalyticsAvailability receivedAvailability,
    AnalyticsAvailability cleanedAvailability,
    String sourceUrl,
    String cleanedSourceUrl
) {
    public AnalyticsTrendBucket(
            Instant startInclusive,
            Instant endExclusive,
            long received,
            AnalyticsAvailability availability,
            String sourceUrl) {
        this(startInclusive, endExclusive, received, 0, availability, availability,
            sourceUrl, null);
    }

    public AnalyticsTrendBucket {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        Objects.requireNonNull(receivedAvailability, "receivedAvailability");
        Objects.requireNonNull(cleanedAvailability, "cleanedAvailability");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        if (!startInclusive.isBefore(endExclusive) || received < 0 || cleaned < 0) {
            throw new IllegalArgumentException("Valid bucket facts are required");
        }
    }

    public AnalyticsAvailability availability() {
        return receivedAvailability;
    }
}
