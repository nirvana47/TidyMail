package com.tidymail.dto;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-mailbox analytics used by the Analytics page's charts and rankings.
 *
 * <p>Product functionality supported: the <em>Analytics</em> charts (received /
 * sent / read / archived-deleted totals and time series), top senders/domains,
 * and mailbox size metrics. Enforces complete, non-negative facts and bounds
 * rankings to 10 rows.</p>
 */
public record AnalyticsMailboxDashboard(
    Map<MetricTab, Long> totals,
    Map<MetricTab, List<SeriesBucket>> series,
    List<RankedValue> topReceivedEmails,
    List<RankedValue> topReceivedDomains,
    List<RankedValue> topSentEmails,
    SizeMetrics sizes,
    long archived,
    long deleted
) {
    private static final DateTimeFormatter CHART_DATE_FORMAT =
        DateTimeFormatter.ofPattern("MMM d").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CHART_TOOLTIP_DATE_FORMAT =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    public AnalyticsMailboxDashboard {
        totals = Map.copyOf(totals);
        series = Map.copyOf(series);
        topReceivedEmails = bounded(topReceivedEmails);
        topReceivedDomains = bounded(topReceivedDomains);
        topSentEmails = bounded(topSentEmails);
        Objects.requireNonNull(sizes, "sizes");
        if (!totals.keySet().containsAll(List.of(MetricTab.values()))
                || !series.keySet().containsAll(List.of(MetricTab.values()))
                || archived < 0 || deleted < 0) {
            throw new IllegalArgumentException("Complete non-negative analytics facts are required");
        }
    }

    private static List<RankedValue> bounded(List<RankedValue> values) {
        List<RankedValue> copy = List.copyOf(values);
        if (copy.size() > 10) {
            throw new IllegalArgumentException("Analytics rankings are limited to 10 rows");
        }
        return copy;
    }

    /**
     * Looks up a metric total by its string name.
     */
    public long total(String metric) {
        return totals.get(MetricTab.valueOf(metric));
    }

    /**
     * Looks up a metric's time-series buckets by its string name.
     */
    public List<SeriesBucket> buckets(String metric) {
        return series.get(MetricTab.valueOf(metric));
    }

    public enum MetricTab {
        RECEIVED("Received"),
        SENT("Sent"),
        READ("Read"),
        ARCHIVED_DELETED("Archived/Deleted");

        private final String label;

        MetricTab(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record SeriesBucket(
        AnalyticsQuery.DateRange range,
        long value
    ) {
        public SeriesBucket {
            Objects.requireNonNull(range, "range");
            if (value < 0) throw new IllegalArgumentException("Bucket value cannot be negative");
        }

        public String chartLabel() {
            return CHART_DATE_FORMAT.format(range.startInclusive());
        }

        public String tooltipDate() {
            return CHART_TOOLTIP_DATE_FORMAT.format(range.startInclusive());
        }
    }

    public record RankedValue(String label, long count) {
        public RankedValue {
            if (label == null || label.isBlank() || count < 1) {
                throw new IllegalArgumentException("Ranked analytics value is required");
            }
        }
    }

    public record SizeMetrics(
        Long unreadInboxBytes,
        Long inboxOverTenMb,
        Long sentOverTenMb,
        String unavailableReason
    ) {
        public SizeMetrics {
            if (unreadInboxBytes != null && unreadInboxBytes < 0
                    || inboxOverTenMb != null && inboxOverTenMb < 0
                    || sentOverTenMb != null && sentOverTenMb < 0) {
                throw new IllegalArgumentException("Size metrics cannot be negative");
            }
            boolean complete = unreadInboxBytes != null
                && inboxOverTenMb != null && sentOverTenMb != null;
            if (complete == (unavailableReason != null)) {
                throw new IllegalArgumentException(
                    "Size metrics must be complete or carry an unavailable reason");
            }
        }

        public static SizeMetrics unavailable(String reason) {
            return new SizeMetrics(null, null, null, Objects.requireNonNull(reason));
        }

        public boolean available() {
            return unavailableReason == null;
        }
    }
}
