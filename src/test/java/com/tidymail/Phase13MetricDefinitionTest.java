package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.dto.AnalyticsOutcomeSummary;
import com.tidymail.service.AnalyticsMetricService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase13MetricDefinitionTest {
    private final AnalyticsMetricService metrics = new AnalyticsMetricService();
    private final AnalyticsAvailability available = AnalyticsAvailability.available(
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-09-01T00:00:00Z"), 0);

    @Test
    void requestedPartitionIsExactAndAcceptedRemainsOverlapping() {
        AnalyticsOutcomeSummary outcomes =
            new AnalyticsOutcomeSummary(10, 7, 4, 2, 1, 3);

        assertThat(outcomes.retainedConfidence()).isEqualTo(9);
        assertThat(metrics.confidenceRetention(outcomes, available).numerator())
            .isEqualTo(9);
        assertThat(metrics.confidenceRetention(outcomes, available).denominator())
            .isEqualTo(10);
        assertThat(metrics.confidenceRetention(outcomes, available).excludedCount())
            .isEqualTo(1);
        assertThatThrownBy(() ->
            new AnalyticsOutcomeSummary(10, 7, 4, 2, 1, 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denominatorZeroIsUnavailableAndNeverZeroPercent() {
        var ratio = metrics.reviewCoverage(0, 0, available);

        assertThat(ratio.percent()).isNull();
        assertThat(ratio.availability().state())
            .isEqualTo(AnalyticsAvailability.State.UNAVAILABLE);
        assertThat(ratio.availability().reason())
            .isEqualTo(AnalyticsAvailability.Reason.NO_ELIGIBLE_ROWS);
    }

    @Test
    void inboxReductionFloorsAtZeroButCanExceedOneHundredPercent() {
        var over = metrics.inboxReduction(8, 4, 1, 5, available);
        var floor = metrics.inboxReduction(1, 1, 9, 5, available);

        assertThat(over.numerator()).isEqualTo(11);
        assertThat(over.percent()).isEqualByComparingTo("220.0");
        assertThat(floor.numerator()).isZero();
        assertThat(floor.percent()).isEqualByComparingTo("0.0");
    }

    @Test
    void cleanupCoverageCannotExceedReceivedCohort() {
        assertThat(metrics.cleanupCoverage(3, 4, available).percent())
            .isEqualByComparingTo("75.0");
        assertThatThrownBy(() -> metrics.cleanupCoverage(5, 4, available))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delayMedianAndFiveHalfOpenBucketsUseIdenticalSample() {
        var distribution = metrics.delay(
            List.of(0L, 899L, 900L, 3_599L, 3_600L, 86_399L,
                86_400L, 604_799L, 604_800L),
            2,
            available);

        assertThat(distribution.sampleSize()).isEqualTo(9);
        assertThat(distribution.medianSeconds())
            .isEqualByComparingTo(BigDecimal.valueOf(3_600));
        assertThat(distribution.buckets()).extracting(bucket -> bucket.count())
            .containsExactly(2L, 2L, 2L, 2L, 1L);
        assertThat(distribution.buckets().stream()
            .mapToLong(bucket -> bucket.count()).sum())
            .isEqualTo(distribution.sampleSize());
        assertThat(distribution.excludedCount()).isEqualTo(2);
        assertThat(distribution.availability().reason())
            .isEqualTo(AnalyticsAvailability.Reason.CORRUPT_DELAY_PAIR);
    }

    @Test
    void evenDelayMedianMatchesPercentileContInterpolation() {
        var distribution = metrics.delay(List.of(10L, 11L), 0, available);
        assertThat(distribution.medianSeconds()).isEqualByComparingTo("10.5");
    }

    @Test
    void unsubscribeManualOnlyIsExcludedAndReported() {
        var ratio = metrics.unsubscribeCompletion(2, 4, 3, available);
        assertThat(ratio.numerator()).isEqualTo(2);
        assertThat(ratio.denominator()).isEqualTo(4);
        assertThat(ratio.excludedCount()).isEqualTo(3);
        assertThat(ratio.percent()).isEqualByComparingTo("50.0");
    }
}
