package com.tidymail.dto;

import java.util.List;
import java.util.Map;

/**
 * The full Analytics dashboard view model.
 *
 * <p>Product functionality supported: the <em>Analytics</em> workspace tab.
 * Bundles all headline metrics, the action out.Com/, coverage ratios (review,
 * cleanup, unsubscribe completion, inbox reduction, cleanup rate, confidence
 * retention), the triage-delay distribution, top senders, and overall
 * coverage. Enforces that every required headline metric is present.</p>
 */
public record AnalyticsDashboard(
    Map<AnalyticsMetric.MetricId, AnalyticsMetric> metrics,
    AnalyticsOutcomeSummary outcomes,
    AnalyticsRatio reviewCoverage,
    AnalyticsRatio cleanupCoverage,
    AnalyticsRatio unsubscribeCompletion,
    AnalyticsRatio inboxReduction,
    AnalyticsRatio cleanupRate,
    AnalyticsRatio confidenceRetention,
    AnalyticsDelayDistribution triageDelay,
    long unsubscribeAcceptedOrPending,
    List<AnalyticsTopSender> topSenders,
    AnalyticsCoverage coverage
) {
    public AnalyticsDashboard {
        metrics = Map.copyOf(metrics);
        topSenders = List.copyOf(topSenders);
        if (unsubscribeAcceptedOrPending < 0) {
            throw new IllegalArgumentException(
                "Unsubscribe accepted-or-pending count cannot be negative");
        }
        if (!metrics.keySet().containsAll(List.of(AnalyticsMetric.MetricId.values()))) {
            throw new IllegalArgumentException("All headline metrics are required");
        }
    }
}
