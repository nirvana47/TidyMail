package com.tidymail.mail.sync;

import java.util.Set;

public record AttachmentMembershipPage(
    Set<String> messageIds,
    String nextPageToken,
    boolean hasMore
) {
    public AttachmentMembershipPage {
        if (messageIds == null) {
            messageIds = Set.of();
        } else {
            if (messageIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("Attachment membership IDs must be non-blank");
            }
            messageIds = Set.copyOf(messageIds);
        }
        if (nextPageToken != null && nextPageToken.isBlank()) {
            throw new IllegalArgumentException("Next page token must be null or non-blank");
        }
        if (hasMore != (nextPageToken != null)) {
            throw new IllegalArgumentException("hasMore must match next-page-token presence");
        }
    }

    public static AttachmentMembershipPage of(Set<String> messageIds, String nextPageToken) {
        return new AttachmentMembershipPage(messageIds, nextPageToken, nextPageToken != null);
    }
}
