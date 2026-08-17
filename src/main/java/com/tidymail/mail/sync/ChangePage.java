package com.tidymail.mail.sync;

import java.util.Set;

/**
 * A page of mailbox changes (added/modified vs deleted message IDs) plus the
 * sync cursors needed to resume change-feed iteration.
 *
 * <p>Product functionality supported: provider-agnostic change-feed sync.
 */
public record ChangePage(
    Set<String> changedIds,
    Set<String> deletedIds,
    String lastCompletelyAppliedRevision,
    String returnedCursor,
    String nextPageToken,
    boolean hasMore
) {
    public ChangePage {
        changedIds = immutableIds(changedIds, "Changed IDs");
        deletedIds = immutableIds(deletedIds, "Deleted IDs");
        if (lastCompletelyAppliedRevision != null && lastCompletelyAppliedRevision.isBlank()) {
            throw new IllegalArgumentException("Complete-unit revision must be null or non-blank");
        }
        if (returnedCursor == null || returnedCursor.isBlank()) {
            throw new IllegalArgumentException("Returned cursor is required");
        }
        if (nextPageToken != null && nextPageToken.isBlank()) {
            throw new IllegalArgumentException("Next page token must be null or non-blank");
        }
        if (hasMore != (nextPageToken != null)) {
            throw new IllegalArgumentException("hasMore must match next-page-token presence");
        }
    }

    private static Set<String> immutableIds(Set<String> ids, String label) {
        if (ids == null) {
            return Set.of();
        }
        if (ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return Set.copyOf(ids);
    }
}
