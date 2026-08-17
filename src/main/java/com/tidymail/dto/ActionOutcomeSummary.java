package com.tidymail.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Summarizes the outcome of a bulk action execution.
 *
 * <p>Product functionality supported: the <em>Review</em> outcome display and
 * <em>action history</em>. Tracks how many messages were requested, accepted,
 * succeeded, failed, pending verification, or not dispatched, plus a
 * human-readable status and per-reason failure counts. Enforces internal
 * consistency (e.g. requested == succeeded + failed + verificationPending +
 * notDispatched).</p>
 */
public record ActionOutcomeSummary(
    int requested,
    int accepted,
    int succeeded,
    int failed,
    int verificationPending,
    int notDispatched,
    String status,
    String source,
    Map<String, Integer> reasonCounts,
    List<Event> events
) {
    public static final int MAX_REASONS = 8;

    public ActionOutcomeSummary {
        if (requested < 0 || accepted < 0 || accepted > requested
                || succeeded < 0 || failed < 0 || verificationPending < 0
                || notDispatched < 0
                || requested != succeeded + failed + verificationPending + notDispatched
                || status == null || status.isBlank()
                || source == null || source.isBlank()
                || reasonCounts == null || reasonCounts.size() > MAX_REASONS
                || reasonCounts.values().stream().anyMatch(count -> count == null || count <= 0)
                || events == null) {
            throw new IllegalArgumentException("Invalid action outcome summary");
        }
        reasonCounts = Map.copyOf(reasonCounts);
        events = List.copyOf(events);
    }

    /**
     * Derives a human-readable status label from the outcome counts (e.g.
     * &quot;Applied&quot;, &quot;Partially applied&quot;, &quot;Verification
     * pending&quot;, &quot;Undone&quot;).
     */
    public String visibleStatus() {
        if ("UNDONE".equals(status)) {
            return "Undone";
        }
        if ("PARTIALLY_UNDONE".equals(status)) {
            return "Partially undone";
        }
        if (succeeded == requested && requested > 0) {
            return "Applied";
        }
        if (succeeded > 0) {
            return "Partially applied";
        }
        if (verificationPending > 0 || accepted > 0) {
            return "Verification pending";
        }
        if (failed > 0) {
            return "Failed";
        }
        return "In progress";
    }

    public record Event(
        String type,
        String status,
        Instant occurredAt
    ) {
        public Event {
            if (type == null || type.isBlank() || status == null || status.isBlank()
                    || occurredAt == null) {
                throw new IllegalArgumentException("Invalid outcome event");
            }
        }
    }
}
