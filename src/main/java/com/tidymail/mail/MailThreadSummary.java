package com.tidymail.mail;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Summarizes metadata about a mail thread.
 * <p>
 * Product functionality supported: thread-level views in metadata-only sync,
 * reporting participants, senders, message counts, and date ranges.
 */
public record MailThreadSummary(
    String threadId,
    List<String> participantEmails,
    Set<String> uniqueSenderDomains,
    int messageCount,
    Instant oldestMessageAt,
    Instant newestMessageAt,
    List<String> messageIdsOrderedChronological
) {
}
