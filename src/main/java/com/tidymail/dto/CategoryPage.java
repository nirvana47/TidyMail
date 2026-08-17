package com.tidymail.dto;

import java.util.List;

public record CategoryPage<T>(
    String categoryKey,
    List<T> rows,
    long loadedCount,
    long exactTotal,
    boolean hasMore,
    boolean loadAllAvailable
) {
    public CategoryPage {
        if (categoryKey == null || categoryKey.isBlank()) {
            throw new IllegalArgumentException("Category key is required");
        }
        rows = List.copyOf(rows);
        if (loadedCount < 0 || exactTotal < 0 || loadedCount > exactTotal) {
            throw new IllegalArgumentException("Category counts are inconsistent");
        }
        if (hasMore != (loadedCount < exactTotal)) {
            throw new IllegalArgumentException("Category continuation state is inconsistent");
        }
        if (loadAllAvailable && (!hasMore || loadedCount < 10 || exactTotal > 1_000)) {
            throw new IllegalArgumentException("Load all availability is inconsistent");
        }
    }
}
