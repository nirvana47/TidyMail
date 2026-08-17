package com.tidymail.dto;

import java.util.List;
import java.util.Map;

/**
 * View model for the Review page: the workspace context, the active query, the
 * queued action rows, their grouped presentation, and pagination/URL metadata.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's queue listing,
 * grouping, filtering, and pagination.
 */
public record ReviewQueueModel(
    WorkspacePageModel workspace,
    ReviewQuery query,
    Metrics metrics,
    List<ReviewActionRow> rows,
    List<ReviewActionGroup> groups,
    long filteredCount,
    int totalPages,
    String canonicalUrl,
    String clearUrl,
    String previousUrl,
    String nextUrl,
    String operationIntent
) {
    public ReviewQueueModel {
        rows = List.copyOf(rows);
        groups = List.copyOf(groups);
    }

    /** Aggregate counts summarizing the current review queue. */
    public record Metrics(
        long queuedActions,
        long exactTargets,
        long openConflicts,
        long eligibleActions
    ) {
    }

    /** Builds a URL-encoded query string from the given parameters. */
    public static String queryString(Map<String, String> parameters) {
        return parameters.entrySet().stream()
            .map(entry -> org.springframework.web.util.UriUtils.encodeQueryParam(
                entry.getKey(), java.nio.charset.StandardCharsets.UTF_8)
                + "=" + org.springframework.web.util.UriUtils.encodeQueryParam(
                    entry.getValue(), java.nio.charset.StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    }
}
