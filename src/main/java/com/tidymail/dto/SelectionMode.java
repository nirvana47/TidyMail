package com.tidymail.dto;

/**
 * How a bulk action selection is scoped: a single row, the visible page, or every
 * row matching the current filters.
 *
 * <p>Product functionality supported: bulk action selection across the Senders and
 * Unread cleanup surfaces.
 */
public enum SelectionMode {
    INDIVIDUAL,
    VISIBLE_PAGE,
    ALL_MATCHING
}
