package com.tidymail.dto;

import java.util.List;

/**
 * A user-defined sender category with its initial decision page, used to group
 * senders for bulk cleanup.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's sender
 * categorization and category-based bulk actions.
 */
public record SenderCategoryGroup(
    String key,
    String title,
    String description,
    long senderCount,
    Long estimatedSize,
    boolean userGenerated,
    boolean other,
    CategoryPage<SenderDecisionRow> initialPage
) {
    public SenderCategoryGroup {
        if (key == null || key.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Category identity is required");
        }
        if (senderCount < 0 || estimatedSize != null && estimatedSize < 0) {
            throw new IllegalArgumentException("Category totals cannot be negative");
        }
        if (initialPage == null || !key.equals(initialPage.categoryKey())
                || initialPage.exactTotal() != senderCount
                || initialPage.loadedCount() > 5) {
            throw new IllegalArgumentException("Initial category page is inconsistent");
        }
    }

    /** Returns the rows of the category's initial page. */
    public List<SenderDecisionRow> rows() {
        return initialPage.rows();
    }

    /** Returns a human-readable label for the category's estimated size. */
    public String estimatedSizeLabel() {
        return EstimatedSizeLabel.format(estimatedSize);
    }
}
