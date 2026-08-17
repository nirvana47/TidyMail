package com.tidymail.service;

import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.dto.AnalyticsDelayDistribution;
import com.tidymail.dto.AnalyticsOutcomeSummary;
import com.tidymail.dto.AnalyticsRatio;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsMetricService {
    public static final String REVIEW_COVERAGE =
        "STAGED executed targets / all executed targets";
    public static final String CLEANUP_COVERAGE =
        "Received-cohort messages decided by range end / received cohort";
    public static final String UNSUBSCRIBE_COMPLETION =
        "Confirmed initiated unsubscribe targets / eligible initiated targets";
    public static final String INBOX_REDUCTION =
        "Successful archive and trash targets minus confirmed inverse successes / received";
    public static final String CLEANUP_RATE =
        "Distinct successfully cleaned triaged messages / distinct triaged messages";
    public static final String CONFIDENCE_RETENTION =
        "Succeeded, failed, and not-dispatched targets / requested targets";

    public AnalyticsOutcomeSummary validatePartition(AnalyticsOutcomeSummary outcomes) {
        return new AnalyticsOutcomeSummary(
            outcomes.requested(), outcomes.accepted(), outcomes.succeeded(),
            outcomes.failed(), outcomes.verificationPending(),
            outcomes.notDispatched());
    }

    public AnalyticsRatio reviewCoverage(
            long staged, long executed, AnalyticsAvailability availability) {
        return AnalyticsRatio.of(staged, executed, REVIEW_COVERAGE, availability, 0);
    }

    public AnalyticsRatio cleanupCoverage(
            long decided, long received, AnalyticsAvailability availability) {
        if (decided > received) {
            throw new IllegalArgumentException("Cleanup coverage cannot exceed its cohort");
        }
        return AnalyticsRatio.of(
            decided, received, CLEANUP_COVERAGE, availability, 0);
    }

    public AnalyticsRatio unsubscribeCompletion(
            long confirmed,
            long eligible,
            long manualOnly,
            AnalyticsAvailability availability) {
        if (confirmed > eligible) {
            throw new IllegalArgumentException("Confirmed cannot exceed eligible initiated");
        }
        return AnalyticsRatio.of(
            confirmed, eligible, UNSUBSCRIBE_COMPLETION, availability, manualOnly);
    }

    public AnalyticsRatio inboxReduction(
            long archive,
            long trash,
            long inverseSuccess,
            long received,
            AnalyticsAvailability availability) {
        long forward = Math.addExact(archive, trash);
        long net = Math.max(0, Math.subtractExact(forward, inverseSuccess));
        return AnalyticsRatio.of(net, received, INBOX_REDUCTION, availability, 0);
    }

    public AnalyticsRatio cleanupRate(
            long cleanedTriaged,
            long triaged,
            AnalyticsAvailability availability) {
        if (cleanedTriaged > triaged) {
            throw new IllegalArgumentException("Cleaned triaged cannot exceed triaged");
        }
        return AnalyticsRatio.of(
            cleanedTriaged, triaged, CLEANUP_RATE, availability, 0);
    }

    public AnalyticsRatio confidenceRetention(
            AnalyticsOutcomeSummary outcomes,
            AnalyticsAvailability availability) {
        AnalyticsOutcomeSummary valid = validatePartition(outcomes);
        return AnalyticsRatio.of(
            valid.retainedConfidence(), valid.requested(),
            CONFIDENCE_RETENTION, availability, valid.verificationPending());
    }

    public AnalyticsDelayDistribution delay(
            List<Long> eligibleSeconds,
            long excludedCount,
            AnalyticsAvailability availability) {
        return delay(
            eligibleSeconds, excludedCount, availability,
            median(eligibleSeconds.stream().sorted().toList()));
    }

    public AnalyticsDelayDistribution delay(
            List<Long> eligibleSeconds,
            long excludedCount,
            AnalyticsAvailability availability,
            BigDecimal authoritativeMedian) {
        List<Long> sample = eligibleSeconds.stream().sorted().toList();
        long[] counts = new long[5];
        for (long seconds : sample) {
            if (seconds < 0) {
                throw new IllegalArgumentException("Eligible delay cannot be negative");
            } else if (seconds < 900) {
                counts[0]++;
            } else if (seconds < 3_600) {
                counts[1]++;
            } else if (seconds < 86_400) {
                counts[2]++;
            } else if (seconds < 604_800) {
                counts[3]++;
            } else {
                counts[4]++;
            }
        }
        BigDecimal expectedMedian = median(sample);
        if (expectedMedian == null && authoritativeMedian != null
                || expectedMedian != null && (authoritativeMedian == null
                    || expectedMedian.compareTo(authoritativeMedian) != 0)) {
            throw new IllegalArgumentException(
                "Median must use the identical eligible delay sample");
        }
        AnalyticsAvailability resolved = sample.isEmpty()
            ? AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.NO_ELIGIBLE_ROWS,
                availability.coverageStart(), availability.coverageEnd(), excludedCount)
            : excludedCount == 0 ? availability
            : AnalyticsAvailability.partial(
                AnalyticsAvailability.Reason.CORRUPT_DELAY_PAIR,
                availability.coverageStart(), availability.coverageEnd(), excludedCount);
        return new AnalyticsDelayDistribution(
            authoritativeMedian, sample.size(), excludedCount,
            List.of(
                new AnalyticsDelayDistribution.Bucket("under_15m", 0, 900L, counts[0]),
                new AnalyticsDelayDistribution.Bucket("15m_to_1h", 900, 3_600L, counts[1]),
                new AnalyticsDelayDistribution.Bucket("1h_to_24h", 3_600, 86_400L, counts[2]),
                new AnalyticsDelayDistribution.Bucket("24h_to_7d", 86_400, 604_800L, counts[3]),
                new AnalyticsDelayDistribution.Bucket("7d_or_more", 604_800, null, counts[4])),
            resolved);
    }

    private static BigDecimal median(List<Long> sample) {
        if (sample.isEmpty()) return null;
        int middle = sample.size() / 2;
        if (sample.size() % 2 == 1) {
            return BigDecimal.valueOf(sample.get(middle));
        }
        return BigDecimal.valueOf(sample.get(middle - 1))
            .add(BigDecimal.valueOf(sample.get(middle)))
            .divide(BigDecimal.valueOf(2), 1, RoundingMode.UNNECESSARY)
            .stripTrailingZeros();
    }
}
