package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

/**
 * Shared workspace context rendered on every page: the active route, the selected
 * account, queued-action and unread counts, sync/indexing status, and whether a
 * sync action is available.
 *
 * <p>Product functionality supported: the workspace shell shared across the
 * Senders, Unread, Unsubscribe, Review, Activity, and Analytics pages.
 */
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

    /** Returns true when the queued-action count is available. */
    public boolean queueCountAvailable() {
        return queuedActionCount != null;
    }

    /** Returns true when the inbox unread count is available. */
    public boolean inboxUnreadCountAvailable() {
        return unreadScopeCounts.available();
    }

    /** Returns the inbox unread count, or null when unavailable. */
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

    /** Returns true when a manual sync action is available given the current state. */
    public boolean syncActionAvailable() {
        return !syncActive && (state == WorkspaceState.EMPTY
            || state == WorkspaceState.STALE
            || state == WorkspaceState.ERROR
            || state == WorkspaceState.PARTIAL);
    }
}
