package com.tidymail.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record AnalyticsPageModel(
    WorkspacePageModel workspace,
    UnreadScopeCounts unreadScopeCounts,
    AnalyticsQuery query,
    AnalyticsMetric received,
    List<AnalyticsTrendBucket> trend,
    AnalyticsDashboard dashboard,
    AnalyticsAvailability availability,
    Map<String, String> canonicalParameters,
    String canonicalUrl,
    String clearUrl,
    String rangeSummary,
    Map<String, String> detailUrls,
    AnalyticsMailboxDashboard mailbox,
    String loadingCopy
) {
    public AnalyticsPageModel(
            WorkspacePageModel workspace,
            AnalyticsQuery query,
            AnalyticsMetric received,
            List<AnalyticsTrendBucket> trend,
            AnalyticsAvailability availability,
            Map<String, String> canonicalParameters,
            String canonicalUrl,
            String clearUrl,
            String rangeSummary) {
        this(workspace, workspace.unreadScopeCounts(), query, received, trend,
              null, availability,
              canonicalParameters, canonicalUrl, clearUrl, rangeSummary,
              Map.of(), null, null);
    }

    public AnalyticsPageModel(
            WorkspacePageModel workspace,
            AnalyticsQuery query,
            AnalyticsMetric received,
            List<AnalyticsTrendBucket> trend,
            AnalyticsDashboard dashboard,
            AnalyticsAvailability availability,
            Map<String, String> canonicalParameters,
            String canonicalUrl,
            String clearUrl,
            String rangeSummary) {
        this(workspace, workspace.unreadScopeCounts(), query, received, trend,
              dashboard, availability,
              canonicalParameters, canonicalUrl, clearUrl, rangeSummary,
              Map.of(), null, null);
    }

    public AnalyticsPageModel(
            WorkspacePageModel workspace,
            UnreadScopeCounts unreadScopeCounts,
            AnalyticsQuery query,
            AnalyticsMetric received,
            List<AnalyticsTrendBucket> trend,
            AnalyticsDashboard dashboard,
            AnalyticsAvailability availability,
            Map<String, String> canonicalParameters,
            String canonicalUrl,
            String clearUrl,
            String rangeSummary) {
        this(workspace, unreadScopeCounts, query, received, trend, dashboard, availability,
              canonicalParameters, canonicalUrl, clearUrl, rangeSummary,
              Map.of(), null, null);
    }

    public AnalyticsPageModel {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(unreadScopeCounts, "unreadScopeCounts");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(received, "received");
        trend = List.copyOf(trend);
        Objects.requireNonNull(availability, "availability");
        canonicalParameters = Map.copyOf(canonicalParameters);
        detailUrls = Map.copyOf(detailUrls);
    }

    public AnalyticsPageModel withMailbox(
            AnalyticsMailboxDashboard mailbox, String loadingCopy) {
        return new AnalyticsPageModel(
            workspace, unreadScopeCounts, query, received, trend, dashboard, availability,
              canonicalParameters, canonicalUrl, clearUrl, rangeSummary,
              detailUrls, mailbox, loadingCopy);
    }

    public String bytesValue(Long bytes) {
        if (bytes == null) return "Unavailable";
        double value = bytes;
        String unit = "B";
        for (String candidate : List.of("KB", "MB", "GB", "TB")) {
            if (value < 1024) break;
            value /= 1024;
            unit = candidate;
        }
        return BigDecimal.valueOf(value).setScale(value < 10 ? 1 : 0, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString() + " " + unit;
    }

    public String metricLabel(AnalyticsMetric.MetricId id) {
        return switch (id) {
            case RECEIVED -> "Received";
            case TRIAGED -> "Triaged";
            case ARCHIVED -> "Archived";
            case TRASHED -> "Trashed";
            case MARKED_READ -> "Marked read";
            case UNSUBSCRIBED -> "Unsubscribed";
        };
    }

    public String metricDefinition(AnalyticsMetric.MetricId id) {
        return switch (id) {
            case RECEIVED ->
                "Distinct indexed metadata rows placed by message receipt time.";
            case TRIAGED ->
                "Distinct messages with a first durable decision in this range.";
            case ARCHIVED ->
                "Forward archive targets first proven succeeded in this range.";
            case TRASHED ->
                "Forward trash targets first proven succeeded in this range.";
            case MARKED_READ ->
                "Forward mark-read targets first proven succeeded in this range.";
            case UNSUBSCRIBED ->
                "Targets with a linked confirmed unsubscribe completion event.";
        };
    }

    public String metricValue(AnalyticsMetric metric) {
        return switch (metric.availability().state()) {
            case UNAVAILABLE -> "Unavailable";
            case PARTIAL -> "Partial: " + metric.value();
            case AVAILABLE -> Long.toString(metric.value());
        };
    }

    public String comparisonCopy(AnalyticsMetric metric) {
        if (metric.priorValue() == null) return "No prior comparison requested.";
        if (!metric.comparisonAvailability().available()) {
            return "Comparison unavailable because current or prior history is incomplete.";
        }
        if (metric.percentDelta() == null) {
            return metric.absoluteDelta()
                + " more than prior period; percent unavailable because prior was zero.";
        }
        String direction = metric.absoluteDelta() > 0 ? "up "
            : metric.absoluteDelta() < 0 ? "down " : "unchanged at ";
        return direction + metric.percentDelta().abs() + "% versus the prior equivalent period.";
    }

    public String ratioValue(AnalyticsRatio ratio) {
        if (!ratio.availability().available()) return "Unavailable - no eligible rows.";
        return ratio.numerator() + " / " + ratio.denominator() + " = "
            + ratio.percent() + "%";
    }

    public String bucketPercent(long count, long total) {
        if (total == 0) return "Unavailable";
        return BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP) + "%";
    }

    public String delayMedianCopy() {
        if (dashboard == null || dashboard.triageDelay().medianSeconds() == null) {
            return "Unavailable - no eligible rows.";
        }
        long seconds = dashboard.triageDelay().medianSeconds()
            .setScale(0, RoundingMode.HALF_UP).longValue();
        if (seconds < 60) return seconds + " seconds";
        if (seconds < 3600) return (seconds / 60) + " minutes";
        if (seconds < 86400) return (seconds / 3600) + " hours";
        return (seconds / 86400) + " days";
    }
}
