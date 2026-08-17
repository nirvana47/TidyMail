package com.tidymail.dto;

import java.util.Objects;

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

    public static UnreadScopeCounts available(long inboxUnreadCount, long allMailUnreadCount) {
        return new UnreadScopeCounts(
            inboxUnreadCount, allMailUnreadCount, Availability.AVAILABLE);
    }

    public static UnreadScopeCounts pending() {
        return new UnreadScopeCounts(null, null, Availability.PENDING);
    }

    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    public enum Availability {
        AVAILABLE,
        PENDING
    }
}
