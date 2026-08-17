package com.tidymail.dto;

import java.util.Map;

/**
 * View model for the Unsubscribe page: workspace context, the active query,
 * candidate results, filter summary, sort label, and pagination URLs.
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's candidate
 * list, filtering, sorting, and pagination.
 */
public record UnsubscribePageModel(
    WorkspacePageModel workspace,
    UnsubscribeQuery query,
    DecisionPage<UnsubscribeCandidateRow> results,
    Map<String, String> canonicalParameters,
    String filterSummary,
    String activeSortLabel,
    String canonicalUrl,
    String clearUrl,
    String previousUrl,
    String nextUrl
) {
    public UnsubscribePageModel {
        canonicalParameters = Map.copyOf(canonicalParameters);
    }
}
