package com.tidymail.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    public record Filter(
        LocalDate from,
        LocalDate to,
        String group,
        String action,
        String compare
    ) {
    }

    public record AccountScope(String kind, int count) {
    }

    public record Availability(
        String state,
        String reason,
        Instant coverageStart,
        Instant coverageEnd,
        long excludedCount
    ) {
    }

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

    public record Trend(
        Instant start,
        Instant end,
        long received,
        long cleaned,
        Availability receivedAvailability,
        Availability cleanedAvailability
    ) {
    }

    public record Outcomes(
        long requested,
        long accepted,
        long succeeded,
        long failed,
        long verificationPending,
        long notDispatched
    ) {
    }

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

    public record DelayBucket(
        String name,
        long lowerSeconds,
        Long upperSeconds,
        long count
    ) {
    }

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

    public record SenderRank(String alias, long receivedCount) {
    }
}
