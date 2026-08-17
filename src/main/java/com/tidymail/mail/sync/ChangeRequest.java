package com.tidymail.mail.sync;

/**
 * A paging request for pulling changes from a provider's change feed.
 *
 * <p>Product functionality supported: provider-agnostic change-feed sync.
 */
public record ChangeRequest(
    String cursor,
    SyncCapabilities.ChangeFeedKind cursorKind,
    String pageToken,
    int maxResults
) {
    public ChangeRequest {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("Change cursor is required");
        }
        if (cursorKind == null || cursorKind == SyncCapabilities.ChangeFeedKind.NONE) {
            throw new IllegalArgumentException("A supported change cursor kind is required");
        }
        if (pageToken != null && pageToken.isBlank()) {
            throw new IllegalArgumentException("Page token must be null or non-blank");
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("Change page max results must be positive");
        }
    }
}
