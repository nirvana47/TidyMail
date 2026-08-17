package com.tidymail.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Distribution of message triage delays (time-to-decision), used by the
 * Analytics dashboard.
 *
 * <p>Product functionality supported: the <em>Analytics</em> &quot;triage
 * delay&quot; visualization. Holds the median delay, sample size, excluded
 * count, and exactly five time buckets, enforcing that the buckets sum to the
 * sample and that a median is present iff the sample is non-empty.</p>
 */
public record AnalyticsDelayDistribution(
    BigDecimal medianSeconds,
    long sampleSize,
    long excludedCount,
    List<Bucket> buckets,
    AnalyticsAvailability availability
) {
    public AnalyticsDelayDistribution {
        if (medianSeconds != null && medianSeconds.signum() < 0
                || sampleSize < 0 || excludedCount < 0) {
            throw new IllegalArgumentException("Delay facts cannot be negative");
        }
        buckets = List.copyOf(buckets);
        long bucketTotal = buckets.stream().mapToLong(Bucket::count).sum();
        if (buckets.size() != 5 || bucketTotal != sampleSize
                || sampleSize == 0 && medianSeconds != null
                || sampleSize > 0 && medianSeconds == null) {
            throw new IllegalArgumentException(
                "Median and five buckets must share one eligible sample");
        }
    }

    public record Bucket(String key, long lowerSeconds, Long upperSeconds, long count) {
        public Bucket {
            if (key == null || key.isBlank() || lowerSeconds < 0 || count < 0
                    || upperSeconds != null && upperSeconds <= lowerSeconds) {
                throw new IllegalArgumentException("Invalid delay bucket");
            }
        }
    }
}
