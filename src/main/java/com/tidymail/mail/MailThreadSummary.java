package com.tidymail.mail;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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
