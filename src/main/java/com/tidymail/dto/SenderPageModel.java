package com.tidymail.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SenderPageModel(
    WorkspacePageModel workspace,
    UnreadScopeCounts unreadScopeCounts,
    SenderQuery query,
    DecisionPage<SenderDecisionRow> results,
    Map<String, String> canonicalQuery,
    String selectedFilterCopy,
    String currentUrl,
    String clearUrl,
    String previousUrl,
    String nextUrl,
    List<SelectionRequest.PreviewAction> previewActions,
    String analyticsOriginCopy,
    CleanupAction cleanupAction,
    List<SenderCategoryGroup> categories,
    List<SenderCategoryOption> categoryOptions,
    long categoryAccountId
) {
    public SenderPageModel(
            WorkspacePageModel workspace,
            SenderQuery query,
            DecisionPage<SenderDecisionRow> results,
            Map<String, String> canonicalQuery,
            String selectedFilterCopy,
            String currentUrl,
            String clearUrl,
            String previousUrl,
            String nextUrl,
            List<SelectionRequest.PreviewAction> previewActions) {
        this(workspace, workspace.unreadScopeCounts(), query, results,
              canonicalQuery, selectedFilterCopy, currentUrl,
              clearUrl, previousUrl, nextUrl, previewActions, null,
            CleanupAction.ARCHIVE, List.of(), List.of(), 1);
    }

    public SenderPageModel {
        if (workspace == null || unreadScopeCounts == null
                || query == null || results == null || cleanupAction == null) {
            throw new IllegalArgumentException("Complete sender page state is required");
        }
        canonicalQuery = orderedCopy(canonicalQuery);
        previewActions = List.copyOf(previewActions);
        categories = List.copyOf(categories);
        categoryOptions = List.copyOf(categoryOptions);
    }

    private static Map<String, String> orderedCopy(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
