package com.tidymail.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * View model for the Unread page: workspace context, the active query, decision
 * results, pagination/filter URLs, preview actions, and unread categories.
 *
 * <p>Product functionality supported: the <em>Unread</em> flow's list, filtering,
 * pagination, and bulk action entry points.
 */
public record UnreadPageModel(
    WorkspacePageModel workspace,
    UnreadQuery query,
    DecisionPage<UnreadDecisionRow> results,
    Map<String, String> canonicalQuery,
    String selectedFilterCopy,
    String currentUrl,
    String clearUrl,
    String previousUrl,
    String nextUrl,
    List<SelectionRequest.PreviewAction> previewActions,
    CleanupAction cleanupAction,
    List<UnreadCategoryGroup> categories,
    Map<String, String> categoryUrls,
    List<SenderCategoryOption> categoryOptions,
    long categoryAccountId
) {
    public UnreadPageModel {
        if (workspace == null || query == null || results == null || cleanupAction == null) {
            throw new IllegalArgumentException("Complete unread page state is required");
        }
        canonicalQuery = orderedCopy(canonicalQuery);
        previewActions = List.copyOf(previewActions);
        categories = List.copyOf(categories);
        categoryUrls = orderedCopy(categoryUrls);
        categoryOptions = List.copyOf(categoryOptions);
    }

    /** Returns an unmodifiable, insertion-ordered copy of the given map. */
    private static Map<String, String> orderedCopy(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
