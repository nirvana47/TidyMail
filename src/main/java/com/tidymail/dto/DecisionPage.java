package com.tidymail.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DecisionPage<T>(
    List<T> rows,
    long filteredCount,
    Metrics metrics,
    int page,
    int pageSize,
    int totalPages,
    Map<String, String> previousQuery,
    Map<String, String> nextQuery
) {
    public DecisionPage {
        rows = List.copyOf(rows);
        metrics = metrics == null ? Metrics.EMPTY : metrics;
        previousQuery = orderedCopy(previousQuery);
        nextQuery = orderedCopy(nextQuery);
        if (filteredCount < 0 || page < 0 || pageSize <= 0 || totalPages < 0) {
            throw new IllegalArgumentException("Invalid decision page");
        }
    }

    private static Map<String, String> orderedCopy(Map<String, String> values) {
        return values == null || values.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public record Metrics(
        long totalRows,
        long primaryCount,
        long secondaryCount,
        long protectedCount
    ) {
        public static final Metrics EMPTY = new Metrics(0, 0, 0, 0);

        public Metrics {
            if (totalRows < 0 || primaryCount < 0 || secondaryCount < 0
                    || protectedCount < 0) {
                throw new IllegalArgumentException("Decision metrics cannot be negative");
            }
        }
    }
}
