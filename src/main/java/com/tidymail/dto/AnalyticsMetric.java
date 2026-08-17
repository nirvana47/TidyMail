package com.tidymail.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A single headline analytics metric with its prior-period comparison.
 *
 * <p>Product functionality supported: the <em>Analytics</em> headline metric
 * cards (received, triaged, archived, etc.). Holds the current value, the
 * prior value, the absolute and percent deltas, and the availability states
 * that govern whether the number (and its comparison) is trustworthy.</p>
 */
public record AnalyticsMetric(
    MetricId id,
    long value,
    Long priorValue,
    long absoluteDelta,
    BigDecimal percentDelta,
    AnalyticsAvailability availability,
    AnalyticsAvailability comparisonAvailability
) {
    public AnalyticsMetric {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(comparisonAvailability, "comparisonAvailability");
        if (value < 0 || priorValue != null && priorValue < 0) {
            throw new IllegalArgumentException("Metric counts cannot be negative");
        }
        if (percentDelta != null) percentDelta = percentDelta.setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * Convenience factory for the &quot;received&quot; metric.
     */
    public static AnalyticsMetric received(
            long current,
            Long prior,
            AnalyticsAvailability currentAvailability,
            AnalyticsAvailability priorAvailability) {
        return count(
            MetricId.RECEIVED, current, prior, currentAvailability, priorAvailability);
    }

    /**
     * Builds a metric from current/prior values, computing the absolute and
     * percent deltas and combining the availability of both periods when a
     * comparison exists.
     */
    public static AnalyticsMetric count(
            MetricId id,
            long current,
            Long prior,
            AnalyticsAvailability currentAvailability,
            AnalyticsAvailability priorAvailability) {
        AnalyticsAvailability combined = prior == null
            ? currentAvailability : currentAvailability.combine(priorAvailability);
        long delta = prior == null ? 0 : Math.subtractExact(current, prior);
        BigDecimal percent = null;
        if (prior != null && prior != 0 && combined.available()) {
            percent = BigDecimal.valueOf(delta)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(prior), 1, RoundingMode.HALF_UP);
        }
        return new AnalyticsMetric(
            id, current, prior, delta, percent,
            currentAvailability, combined);
    }

    public enum MetricId {
        RECEIVED,
        TRIAGED,
        ARCHIVED,
        TRASHED,
        MARKED_READ,
        UNSUBSCRIBED
    }
}
