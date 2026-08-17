package com.tidymail.dto;

import java.util.List;

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

    public long senderCount() {
        return exactSenderCount;
    }

    public long unreadCount() {
        return exactUnreadCount;
    }

    public UnreadCategoryGroup withoutRows() {
        return new UnreadCategoryGroup(
            key, title, description, List.of(), direct, other, dataAvailable,
            exactSenderCount, exactUnreadCount);
    }
}
