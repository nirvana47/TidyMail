package com.tidymail.service;

import com.tidymail.dto.AnalyticsDashboard;
import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.AnalyticsTopSender;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AnalyticsLinkService {
    private final AnalyticsDrilldownKeyService drilldownKeys;

    public AnalyticsLinkService(AnalyticsDrilldownKeyService drilldownKeys) {
        this.drilldownKeys = drilldownKeys;
    }

    public AnalyticsPageModel attach(
            AnalyticsPageModel page, OAuth2AuthenticationToken authentication) {
        AnalyticsDashboard dashboard = page.dashboard();
        if (dashboard != null) {
            var senders = dashboard.topSenders().stream()
                .map(sender -> sender.senderAddress() == null ? sender
                    : withQueueUrl(sender, page.query(), authentication))
                .toList();
            dashboard = new AnalyticsDashboard(
                dashboard.metrics(), dashboard.outcomes(), dashboard.reviewCoverage(),
                dashboard.cleanupCoverage(), dashboard.unsubscribeCompletion(),
                dashboard.inboxReduction(), dashboard.cleanupRate(),
                dashboard.confidenceRetention(), dashboard.triageDelay(),
                dashboard.unsubscribeAcceptedOrPending(), senders,
                dashboard.coverage());
        }
        return new AnalyticsPageModel(
            page.workspace(), page.unreadScopeCounts(),
            page.query(), page.received(), page.trend(), dashboard,
            page.availability(), page.canonicalParameters(), page.canonicalUrl(),
              page.clearUrl(), page.rangeSummary(),
            detailUrls(page.query()), page.mailbox(), page.loadingCopy());
    }

    private AnalyticsTopSender withQueueUrl(
            AnalyticsTopSender sender,
            AnalyticsQuery query,
            OAuth2AuthenticationToken authentication) {
        AnalyticsSenderScope scope = new AnalyticsSenderScope(
            sender.accountId(), sender.senderKey(),
            query.range().startInclusive(), query.range().endExclusive());
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("account", Long.toString(sender.accountId()));
        parameters.put("search", "");
        parameters.put("window", "all");
        parameters.put("type", "all");
        parameters.put("risk", "all");
        parameters.put("unread", "all");
        parameters.put("queue", "all");
        parameters.put("protected", "include");
        parameters.put("sort", "volume_desc");
        parameters.put("page", "0");
        parameters.put("analyticsKey", drilldownKeys.issue(authentication, scope));
        return new AnalyticsTopSender(
            sender.accountId(), sender.senderKey(), sender.accountLabel(),
            sender.senderLabel(), sender.senderAddress(), sender.senderDomain(),
            sender.receivedCount(), url("/senders", parameters));
    }

    private static Map<String, String> detailUrls(AnalyticsQuery query) {
        LinkedHashMap<String, String> links = new LinkedHashMap<>();
        links.put("RECEIVED", source(query, "received"));
        links.put("TRIAGED", source(query, "decision"));
        links.put("ARCHIVED", source(query, "target_archived"));
        links.put("TRASHED", source(query, "target_trashed"));
        links.put("MARKED_READ", source(query, "target_marked_read"));
        links.put("UNSUBSCRIBED", source(query, "target_unsubscribe_confirmed"));
        links.put(
            "UNSUB_ACCEPTED_OR_PENDING",
            source(query, "unsubscribe_accepted_or_pending"));
        links.put("OUTCOME_REQUESTED", source(query, "outcome_requested"));
        links.put("OUTCOME_ACCEPTED", source(query, "outcome_accepted"));
        links.put("OUTCOME_SUCCEEDED", source(query, "outcome_succeeded"));
        links.put("OUTCOME_FAILED", source(query, "outcome_failed"));
        links.put("OUTCOME_PENDING", source(query, "outcome_pending"));
        links.put(
            "OUTCOME_NOT_DISPATCHED", source(query, "outcome_not_dispatched"));
        links.put("REVIEW_STAGED", source(query, "review_staged"));
        links.put("REVIEW_EXECUTED", source(query, "review_executed"));
        links.put("COHORT_DECIDED", source(query, "cohort_decided"));
        links.put("COHORT_RECEIVED", source(query, "received"));
        links.put("INBOX_ARCHIVED", source(query, "target_archived"));
        links.put("INBOX_TRASHED", source(query, "target_trashed"));
        links.put("INBOX_INVERSE", source(query, "inverse_inbox_removal"));
        links.put("INBOX_RECEIVED", source(query, "received"));
        links.put("CLEANUP_CLEANED", source(query, "cleaned_triaged"));
        links.put("CLEANUP_TRIAGED", source(query, "decision"));
        links.put("CONFIDENCE_RETAINED", source(query, "outcome_retained"));
        links.put("CONFIDENCE_REQUESTED", source(query, "outcome_requested"));
        links.put("DELAY_SAMPLE", source(query, "delay_sample"));
        links.put("DELAY_UNDER_15M", source(query, "delay_under_15m"));
        links.put("DELAY_15M_TO_1H", source(query, "delay_15m_to_1h"));
        links.put("DELAY_1H_TO_24H", source(query, "delay_1h_to_24h"));
        links.put("DELAY_24H_TO_7D", source(query, "delay_24h_to_7d"));
        links.put("DELAY_7D_OR_MORE", source(query, "delay_7d_or_more"));
        links.put(
            "UNSUB_CONFIRMED",
            source(query, "unsubscribe_confirmed"));
        links.put(
            "UNSUB_ELIGIBLE",
            source(query, "unsubscribe_eligible"));
        if (query.comparison() == AnalyticsQuery.Comparison.PRIOR) {
            AnalyticsQuery prior = withRange(query, query.priorRange());
            links.put("RECEIVED_PRIOR", source(prior, "received"));
            links.put("TRIAGED_PRIOR", source(prior, "decision"));
            links.put("ARCHIVED_PRIOR", source(prior, "target_archived"));
            links.put("TRASHED_PRIOR", source(prior, "target_trashed"));
            links.put("MARKED_READ_PRIOR", source(prior, "target_marked_read"));
            links.put(
                "UNSUBSCRIBED_PRIOR",
                source(prior, "target_unsubscribe_confirmed"));
        }
        return Map.copyOf(links);
    }

    private static AnalyticsQuery withRange(
            AnalyticsQuery query, AnalyticsQuery.DateRange range) {
        return new AnalyticsQuery(
            range.startInclusive().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
            range.endExclusive().minusNanos(1)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
            query.account(), query.action(), query.grouping(), query.comparison());
    }

    private static String source(AnalyticsQuery sourceQuery, String series) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/analytics/source");
        sourceQuery.canonicalParameters().forEach(builder::queryParam);
        builder.queryParam("series", series);
        builder.queryParam("bucketStart", sourceQuery.range().startInclusive());
        builder.queryParam("bucketEnd", sourceQuery.range().endExclusive());
        builder.queryParam("page", 0);
        return builder.build().encode().toUriString();
    }

    public static String history(AnalyticsQuery query, String action, String status) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/review")
            .queryParam("view", "history")
            .queryParam("account", query.account())
            .queryParam("from", query.from())
            .queryParam("to", query.to())
            .queryParam("action", action)
            .queryParam("status", status)
            .queryParam("target", "")
            .queryParam("page", 0);
        return builder.build().encode().toUriString();
    }

    private static String url(String path, Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }
}
