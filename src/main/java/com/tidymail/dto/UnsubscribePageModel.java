package com.tidymail.dto;

import java.util.Map;

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
