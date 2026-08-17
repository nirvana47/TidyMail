package com.tidymail.dto;

import java.time.Instant;

/**
 * A single item in the Reply Zero queue.
 *
 * <p>Product functionality supported: the <em>Reply Zero</em> feature. Represents
 * a personal-looking thread the user hasn't replied to yet, with the sender,
 * subject, last-message time, age, participant count, and whether it looks like
 * a bulk sender.</p>
 */
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
