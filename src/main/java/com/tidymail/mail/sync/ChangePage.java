package com.tidymail.mail.sync;

import java.util.Set;
import java.util.List;
import java.time.Instant;
import java.math.BigInteger;

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
    boolean hasMore,
    List<LabelChange> labelChanges,
    Set<String> metadataRequiredIds,
    Instant observedAt
) {
    public ChangePage(Set<String> changedIds,Set<String> deletedIds,String complete,String cursor,String next,boolean more) {
        this(changedIds,deletedIds,complete,cursor,next,more,List.of(),changedIds,null);
    }
    public record LabelChange(String messageId,BigInteger revision,Set<String> added,Set<String> removed) {
        public LabelChange {
            if(messageId==null || messageId.isBlank() || revision==null || revision.signum()<0)
                throw new IllegalArgumentException("Owned revisioned label change required");
            added=immutableIds(added,"Added labels");removed=immutableIds(removed,"Removed labels");
            if(!java.util.Collections.disjoint(added,removed))throw new IllegalArgumentException("Conflicting label change");
        }
    }
    public ChangePage {
        labelChanges=labelChanges==null?List.of():List.copyOf(labelChanges);
        metadataRequiredIds=immutableIds(metadataRequiredIds,"Metadata IDs");
        if(!labelChanges.isEmpty() && (observedAt==null || hasMore))throw new IllegalArgumentException("Labels require a complete observed history window");
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
