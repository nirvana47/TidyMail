package com.tidymail.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The confirmation shown before executing staged actions on the Review page.
 *
 * <p>Product functionality supported: the <em>Review</em> approve step.
 * Summarizes how many actions/accounts/targets will be affected, which actions
 * are being approved, excluded-target reasons, a warning (with destructive
 * flag), and the intent token needed to actually execute.</p>
 */
public record ApprovalConfirmation(
    String kind,
    int actionCount,
    int accountCount,
    long targetCount,
    List<UUID> actionPublicIds,
    Map<String, Long> excludedReasonCounts,
    Warning warning,
    String intent,
    Map<String, String> filterParameters
) {
    public ApprovalConfirmation {
        actionPublicIds = List.copyOf(actionPublicIds);
        excludedReasonCounts = Map.copyOf(excludedReasonCounts);
        filterParameters = Map.copyOf(filterParameters);
        if (warning == null) {
            throw new IllegalArgumentException("Warning is required");
        }
    }

    public record Warning(String title, String message, boolean destructive) {
        public Warning {
            if (title == null || title.isBlank()
                    || message == null || message.isBlank()) {
                throw new IllegalArgumentException("Warning copy is required");
            }
        }
    }
}
