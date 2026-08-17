package com.tidymail.dto;

import java.util.List;
import java.util.Map;

public record SelectionRequest(
    SelectionSurface surface,
    SelectionMode mode,
    SenderQuery senderQuery,
    UnreadQuery unreadQuery,
    List<String> rowKeys,
    PreviewAction action,
    boolean includeProtected,
    OverrideReason reason,
    AnalyticsSenderScope analyticsSenderScope
) {
    public SelectionRequest(
            SelectionSurface surface,
            SelectionMode mode,
            SenderQuery senderQuery,
            UnreadQuery unreadQuery,
            List<String> rowKeys,
            PreviewAction action,
            boolean includeProtected,
            OverrideReason reason) {
        this(surface, mode, senderQuery, unreadQuery, rowKeys, action,
            includeProtected, reason, null);
    }

    public SelectionRequest {
        if (surface == null || mode == null || action == null) {
            throw new IllegalArgumentException("Surface, mode, and action are required");
        }
        boolean senderRequest = surface == SelectionSurface.SENDERS
            && senderQuery != null && unreadQuery == null;
        boolean unreadRequest = surface == SelectionSurface.UNREAD
            && unreadQuery != null && senderQuery == null;
        if (!senderRequest && !unreadRequest) {
            throw new IllegalArgumentException("Exactly one matching surface query is required");
        }
        rowKeys = rowKeys == null ? List.of() : List.copyOf(rowKeys);
        if (rowKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("Row keys must not be blank");
        }
        if (mode == SelectionMode.INDIVIDUAL && rowKeys.isEmpty()) {
            throw new IllegalArgumentException("Individual selection requires row keys");
        }
        if (mode != SelectionMode.INDIVIDUAL && !rowKeys.isEmpty()) {
            throw new IllegalArgumentException("Only individual selection accepts row keys");
        }
        if (includeProtected != (reason != null)) {
            throw new IllegalArgumentException(
                "Protected override requires both inclusion and a reason");
        }
        if (analyticsSenderScope != null
                && (surface != SelectionSurface.SENDERS
                    || senderQuery.analyticsKey().isBlank())) {
            throw new IllegalArgumentException(
                "Analytics sender scope requires a sender drilldown query");
        }
    }

    @Override
    public List<String> rowKeys() {
        return List.copyOf(rowKeys);
    }

    public String accountSelection() {
        return surface == SelectionSurface.SENDERS
            ? senderQuery.account() : unreadQuery.account();
    }

    public Map<String, String> canonicalQuery() {
        return surface == SelectionSurface.SENDERS
            ? senderQuery.canonicalParameters() : unreadQuery.canonicalParameters();
    }

    public SelectionRequest withAnalyticsSenderScope(AnalyticsSenderScope scope) {
        return new SelectionRequest(
            surface, mode, senderQuery, unreadQuery, rowKeys, action,
            includeProtected, reason, scope);
    }

    public enum PreviewAction {
        ARCHIVE,
        TRASH,
        MARK_READ,
        KEEP,
        PROTECT,
        REVIEW
    }

    public enum OverrideReason {
        FALSE_POSITIVE,
        BUSINESS_REQUIRED,
        USER_CONFIRMED,
        OTHER_REVIEWED
    }
}
