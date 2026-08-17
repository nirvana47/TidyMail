package com.tidymail.service;

import com.tidymail.dto.AnalyticsMailboxDashboard.SizeMetrics;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Optional provider/data-foundation facts that cannot be derived honestly from
 * the current local message metadata schema.
 */
/**
 * Optional provider/data-foundation facts that cannot be derived honestly from
 * the current local message metadata schema. Implementations may supply a
 * reliable mailbox total or full message-size metrics.
 *
 * <p>Product functionality supported: the <em>Analytics</em> flow's optional
 * mailbox-level metrics.
 */
public interface AnalyticsMailboxCapability {

    /** Returns a reliable total message count for the account, if available. */
    default OptionalLong reliableMailboxMessageTotal(long accountId) {
        return OptionalLong.empty();
    }

    /**
     * Full message size includes the body and all attachments. The three facts
     * are scoped to messages whose receipt time is within {@code range}.
     */
    /** Returns full message-size metrics for the range, if available. */
    default Optional<SizeMetrics> sizeMetrics(long accountId, DateRange range) {
        return Optional.empty();
    }
}
