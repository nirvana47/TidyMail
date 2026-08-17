package com.tidymail.dto;

import java.time.Instant;

public record ReplyZeroItem(
    String threadId,
    String fromAddress,
    String fromDisplayName,
    String fromDomain,
    String subject,
    Instant lastMessageAt,
    long ageDays,
    int participantCount,
    boolean bulkSender
) {
}
