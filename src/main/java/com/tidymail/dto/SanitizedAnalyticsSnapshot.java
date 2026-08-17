package com.tidymail.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A sanitized, self-describing snapshot of analytics evidence (metrics, trends,
 * outcomes, ratios, delay distribution, coverage, and top senders) used to render
 * the Analytics dashboard without exposing raw mailbox data.
 *
 * <p>Product functionality supported: the <em>Analytics</em> flow, which presents
 * received-evidence metrics with explicit historical availability.
 */
public record SanitizedAnalyticsSnapshot(
    int schemaVersion,
    Instant generatedAt,
    Filter filter,
    AccountScope accountScope,
    List<Metric> metrics,
    List<Trend> trend,
    Outcomes outcomes,
    List<Ratio> ratios,
    DelayDistribution delayDistribution,
    Coverage coverage,
    List<SenderRank> senderRanks
) {
    public SanitizedAnalyticsSnapshot {
        metrics = List.copyOf(metrics);
        trend = List.copyOf(trend);
        ratios = List.copyOf(ratios);
        senderRanks = List.copyOf(senderRanks);
    }

    /** The date range and grouping filters the snapshot was generated for. */
    public record Filter(
        LocalDate from,
        LocalDate to,
        String group,
        String action,
        String compare
    ) {
    }

    /** Describes which accounts the snapshot covers. */
    public record AccountScope(String kind, int count) {
    }

    /** Availability state of a metric or comparison, with a reason when unavailable. */
    public record Availability(
        String state,
        String reason,
        Instant coverageStart,
        Instant coverageEnd,
        long excludedCount
    ) {
    }

    /** A single analytics metric with its definition, value, and prior-period delta. */
    public record Metric(
        String name,
        String definition,
        long value,
        Long priorValue,
        long absoluteDelta,
        BigDecimal percentDelta,
        Availability availability,
        Availability comparisonAvailability
    ) {
    }

    /** A time bucket of received vs. cleaned messages for trend charts. */
    public record Trend(
        Instant start,
        Instant end,
        long received,
        long cleaned,
        Availability receivedAvailability,
        Availability cleanedAvailability
    ) {
    }

    /** Outcome counts for cleanup actions in the snapshot period. */
    public record Outcomes(
        long requested,
        long accepted,
        long succeeded,
        long failed,
        long verificationPending,
        long notDispatched
    ) {
    }

    /** A computed ratio (e.g. cleanup rate) with its definition and availability. */
    public record Ratio(
        String name,
        String definition,
        long numerator,
        long denominator,
        BigDecimal percent,
        Availability availability,
        long excludedCount
    ) {
    }

    /** Distribution of action execution delays, used for latency analysis. */
    public record DelayDistribution(
        BigDecimal medianSeconds,
        long sampleSize,
        long excludedCount,
        List<DelayBucket> buckets,
        Availability availability
    ) {
        public DelayDistribution {
            buckets = List.copyOf(buckets);
        }
    }

    /** A single bucket in the delay distribution histogram. */
    public record DelayBucket(
        String name,
        long lowerSeconds,
        Long upperSeconds,
        long count
    ) {
    }

    /** Data-coverage bounds describing how reliable the snapshot's evidence is. */
    public record Coverage(
        Instant receivedReliableFrom,
        Instant ledgerReliableFrom,
        Instant reliableThrough,
        Availability received,
        Availability ledger,
        Availability unsubscribe,
        long legacyActionsWithoutDetails,
        long manualRequiredOnlyUnsubscribe
    ) {
    }

    /** A top-sender entry by received message count. */
    public record SenderRank(String alias, long receivedCount) {
    }
}
