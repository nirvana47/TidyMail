package com.tidymail.mail;

import java.util.List;

/**
 * A page of paginated results with a token for the next page.
 * <p>
 * Product functionality supported: paged metadata-only mailbox sync.
 */
public record PageResult<T>(
    List<T> items,
    String nextPageToken,
    int requestedMax,
    boolean hasMore
) {
    /**
     * Builds a page result, flagging {@code hasMore} when a token is present.
     */
    public static <T> PageResult<T> of(List<T> items, String nextPageToken, int requestedMax) {
        return new PageResult<>(items, nextPageToken, requestedMax, nextPageToken != null);
    }
}
