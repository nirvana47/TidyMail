package com.tidymail.mail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record BulkActionResult(
    int requestedCount,
    int succeededCount,
    int failedCount,
    List<String> succeededMessageIds,
    Map<String, String> failedMessageIdToReason,
    Instant completedAt
) {
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
