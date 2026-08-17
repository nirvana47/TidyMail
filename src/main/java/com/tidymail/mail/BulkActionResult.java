package com.tidymail.mail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reports the exact per-message outcome of a bulk mailbox mutation.
 * <p>
 * Product functionality supported: bulk archive/trash/mark-read follow-up,
 * distinguishing succeeded messages from failed ones with a reason.
 */
public record BulkActionResult(
    int requestedCount,
    int succeededCount,
    int failedCount,
    List<String> succeededMessageIds,
    Map<String, String> failedMessageIdToReason,
    Instant completedAt
) {
    /**
     * Builds a result where every requested message succeeded.
     */
    public static BulkActionResult ok(int requested, List<String> succeeded) {
        return new BulkActionResult(
            requested,
            succeeded.size(),
            0,
            new ArrayList<>(succeeded),
            Collections.emptyMap(),
            Instant.now()
        );
    }

    /**
     * Builds a result with exact success and failure details.
     */
    public static BulkActionResult withPartialFailures(
        int requested,
        List<String> succeeded,
        Map<String, String> failed
    ) {
        return new BulkActionResult(
            requested,
            succeeded.size(),
            failed.size(),
            new ArrayList<>(succeeded),
            Map.copyOf(failed),
            Instant.now()
        );
    }
}
