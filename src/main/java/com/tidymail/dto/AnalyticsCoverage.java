package com.tidymail.dto;

import java.time.Instant;
import java.util.Objects;

public record AnalyticsCoverage(
    Instant receivedReliableFrom,
    Instant ledgerReliableFrom,
    Instant reliableThrough,
    AnalyticsAvailability receivedAvailability,
    AnalyticsAvailability ledgerAvailability,
    AnalyticsAvailability unsubscribeAvailability,
    long legacyActionsWithoutDetails,
    long manualRequiredOnlyUnsubscribe
) {
    public AnalyticsCoverage {
        Objects.requireNonNull(receivedAvailability, "receivedAvailability");
        Objects.requireNonNull(ledgerAvailability, "ledgerAvailability");
        Objects.requireNonNull(unsubscribeAvailability, "unsubscribeAvailability");
        if (legacyActionsWithoutDetails < 0 || manualRequiredOnlyUnsubscribe < 0) {
            throw new IllegalArgumentException("Coverage exclusions cannot be negative");
        }
    }
}
