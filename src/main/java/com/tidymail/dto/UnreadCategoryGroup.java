package com.tidymail.dto;

import java.util.List;

/**
 * A category grouping unread senders (e.g. direct, other) with its rows and exact
 * sender/unread totals, used to organize the Unread page.
 *
 * <p>Product functionality supported: the <em>Unread</em> flow's category grouping
 * and bulk cleanup.
 */
public record UnreadCategoryGroup(
    String key,
    String title,
    String description,
    List<UnreadSenderRow> rows,
    boolean direct,
    boolean other,
    boolean dataAvailable,
    long exactSenderCount,
    long exactUnreadCount
) {
    public UnreadCategoryGroup(
            String key,
            String title,
            String description,
            List<UnreadSenderRow> rows,
            boolean direct,
            boolean other,
            boolean dataAvailable) {
        this(
            key,
            title,
            description,
            rows,
            direct,
            other,
            dataAvailable,
            rows.size(),
            rows.stream().mapToLong(UnreadSenderRow::unreadCount).sum());
    }

    public UnreadCategoryGroup {
        if (key == null || key.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Category identity is required");
        }
        rows = List.copyOf(rows);
        if (exactSenderCount < rows.size() || exactUnreadCount < 0) {
            throw new IllegalArgumentException("Category totals are inconsistent");
        }
    }

    /** Returns the exact number of senders in this category. */
    public long senderCount() {
        return exactSenderCount;
    }

    /** Returns the exact number of unread messages in this category. */
    public long unreadCount() {
        return exactUnreadCount;
    }

    /** Returns a copy of this group with its rows removed (for collapsed display). */
    public UnreadCategoryGroup withoutRows() {
        return new UnreadCategoryGroup(
            key, title, description, List.of(), direct, other, dataAvailable,
            exactSenderCount, exactUnreadCount);
    }
}
