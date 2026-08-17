package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

public record WorkspacePageModel(
    WorkspaceRoute route,
    String displayName,
    String email,
    List<AccountScopeOption> accountOptions,
    List<Long> selectedAccountIds,
    String selectedAccountQuery,
    String selectedAccountLabel,
    Long queuedActionCount,
    UnreadScopeCounts unreadScopeCounts,
    long indexedCount,
    long scannedCount,
    long pagesCompleted,
    String safeSyncDetail,
    Instant lastSuccessfulAt,
    WorkspaceState state,
    boolean syncActive,
    boolean neverStarted,
    boolean completedEmpty
) {
    public WorkspacePageModel {
        accountOptions = List.copyOf(accountOptions);
        selectedAccountIds = List.copyOf(selectedAccountIds);
        if (unreadScopeCounts == null) {
            throw new IllegalArgumentException("Unread scope counts are required");
        }
    }

    public boolean queueCountAvailable() {
        return queuedActionCount != null;
    }

    public boolean inboxUnreadCountAvailable() {
        return unreadScopeCounts.available();
    }

    public Long inboxUnreadCount() {
        return unreadScopeCounts.inboxUnreadCount();
    }

    public WorkspacePageModel(
            WorkspaceRoute route,
            String displayName,
            String email,
            List<AccountScopeOption> accountOptions,
            List<Long> selectedAccountIds,
            String selectedAccountQuery,
            String selectedAccountLabel,
            Long queuedActionCount,
            long indexedCount,
            long scannedCount,
            long pagesCompleted,
            String safeSyncDetail,
            Instant lastSuccessfulAt,
            WorkspaceState state,
            boolean syncActive,
            boolean neverStarted,
            boolean completedEmpty) {
        this(
            route, displayName, email, accountOptions, selectedAccountIds,
            selectedAccountQuery, selectedAccountLabel, queuedActionCount,
            UnreadScopeCounts.pending(),
            indexedCount, scannedCount, pagesCompleted, safeSyncDetail,
            lastSuccessfulAt, state, syncActive, neverStarted, completedEmpty);
    }

    public boolean syncActionAvailable() {
        return !syncActive && (state == WorkspaceState.EMPTY
            || state == WorkspaceState.STALE
            || state == WorkspaceState.ERROR
            || state == WorkspaceState.PARTIAL);
    }
}
