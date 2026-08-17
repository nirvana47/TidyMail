package com.tidymail.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

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

    public static AnalyticsMetric received(
            long current,
            Long prior,
            AnalyticsAvailability currentAvailability,
            AnalyticsAvailability priorAvailability) {
        return count(
            MetricId.RECEIVED, current, prior, currentAvailability, priorAvailability);
    }

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
