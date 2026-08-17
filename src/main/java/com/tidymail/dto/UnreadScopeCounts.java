package com.tidymail.dto;

import java.util.Objects;

/**
 * Unread-message counts for the inbox and all-mail scopes, or a pending marker
 * when the counts are not yet available.
 *
 * <p>Product functionality supported: the workspace header and the <em>Unread</em>
 * flow, which surface unread totals across scopes.
 */
public record UnreadScopeCounts(
    Long inboxUnreadCount,
    Long allMailUnreadCount,
    Availability availability
) {
    public UnreadScopeCounts {
        Objects.requireNonNull(availability, "availability");
        if (availability == Availability.AVAILABLE) {
            if (inboxUnreadCount == null || allMailUnreadCount == null
                    || inboxUnreadCount < 0 || allMailUnreadCount < 0) {
                throw new IllegalArgumentException(
                    "Available unread scope counts must be non-negative");
            }
        } else if (inboxUnreadCount != null || allMailUnreadCount != null) {
            throw new IllegalArgumentException(
                "Pending unread scope counts must not expose numeric values");
        }
    }

    /** Creates an available count pair. */
    public static UnreadScopeCounts available(long inboxUnreadCount, long allMailUnreadCount) {
        return new UnreadScopeCounts(
            inboxUnreadCount, allMailUnreadCount, Availability.AVAILABLE);
    }

    /** Creates a pending marker (counts not yet available). */
    public static UnreadScopeCounts pending() {
        return new UnreadScopeCounts(null, null, Availability.PENDING);
    }

    /** Returns true when the counts are available. */
    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    /** Whether the unread counts are available or still pending. */
    public enum Availability {
        AVAILABLE,
        PENDING
    }
}
