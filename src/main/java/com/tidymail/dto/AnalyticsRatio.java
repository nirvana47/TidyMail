package com.tidymail.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A ratio (numerator/denominator/percent) used by the Analytics dashboard
 * (e.g. review coverage, cleanup rate).
 *
 * <p>Product functionality supported: the <em>Analytics</em> ratio cards.
 * Computes the percentage, and marks the ratio unavailable when there is no
 * denominator (no eligible rows).</p>
 */
public record AnalyticsRatio(
    long numerator,
    long denominator,
    BigDecimal percent,
    String definition,
    AnalyticsAvailability availability,
    long excludedCount
) {
    public AnalyticsRatio {
        if (numerator < 0 || denominator < 0 || excludedCount < 0) {
            throw new IllegalArgumentException("Ratio counts cannot be negative");
        }
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(availability, "availability");
        if (denominator == 0) {
            percent = null;
        } else {
            percent = BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
        }
    }

    /**
     * Factory that computes the percentage and downgrades availability to
     * unavailable when the denominator is zero.
     */
    public static AnalyticsRatio of(
            long numerator,
            long denominator,
            String definition,
            AnalyticsAvailability availability,
            long excludedCount) {
        AnalyticsAvailability resolved = denominator == 0
            ? AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.NO_ELIGIBLE_ROWS,
                availability.coverageStart(), availability.coverageEnd(), excludedCount)
            : availability;
        return new AnalyticsRatio(
            numerator, denominator, null, definition, resolved, excludedCount);
    }
}
