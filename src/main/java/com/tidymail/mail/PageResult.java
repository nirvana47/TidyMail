package com.tidymail.mail;

import java.util.List;

public record PageResult<T>(
    List<T> items,
    String nextPageToken,
    int requestedMax,
    boolean hasMore
) {
    public static <T> PageResult<T> of(List<T> items, String nextPageToken, int requestedMax) {
        return new PageResult<>(items, nextPageToken, requestedMax, nextPageToken != null);
    }
}
