package com.tidymail.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Summarizes the data coverage that backs the Analytics dashboard.
 *
 * <p>Product functionality supported: the <em>Analytics</em> dashboard's
 * coverage disclosure. Tracks the reliable time ranges for received-metadata,
 * the action ledger, and unsubscribe outcomes, plus counts of rows excluded
 * from analysis (legacy actions, manual-only unsubscribes).</p>
 */
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
