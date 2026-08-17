package com.tidymail.service;

import com.tidymail.dto.AnalyticsMailboxDashboard.SizeMetrics;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Optional provider/data-foundation facts that cannot be derived honestly from
 * the current local message metadata schema.
 */
public interface AnalyticsMailboxCapability {

    default OptionalLong reliableMailboxMessageTotal(long accountId) {
        return OptionalLong.empty();
    }

    /**
     * Full message size includes the body and all attachments. The three facts
     * are scoped to messages whose receipt time is within {@code range}.
     */
    default Optional<SizeMetrics> sizeMetrics(long accountId, DateRange range) {
        return Optional.empty();
    }
}
