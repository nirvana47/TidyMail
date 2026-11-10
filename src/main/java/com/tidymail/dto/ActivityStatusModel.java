package com.tidymail.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * View model for the Activity page's live status panel.
 *
 * <p>Product functionality supported: the <em>Activity</em> workspace tab's
 * status widget, which shows the current indexed count, scoped listing progress,
 * freshness, per-lane work status, recent events, and an optional action. Exposes helper predicates so the template can conditionally render
 * each section.</p>
 */
public record ActivityStatusModel(
    String stateClass,
    String headline,
    String phase,
    String guidance,
    long indexedMessages,
    ListingProgress listing,
    String initialBatch,
    String freshness,
    Instant freshnessAt,
    String rescan,
    List<Lane> lanes,
    List<Event> events,
    Action action,
    Integer pollingSeconds,
    boolean alert,
    String announcement
) {
    public ActivityStatusModel {
        lanes = List.copyOf(lanes);
        events = List.copyOf(events);
    }

    public record ListingProgress(long indexed, Long total, boolean complete) {
        public String text() {
            if (total == null) return String.format(Locale.US, "Counting messages… %,d indexed", indexed);
            if (complete) return String.format(Locale.US, "%,d of %,d indexed · 100%%", indexed, total);
            return String.format(Locale.US, "%,d of ~%,d indexed (estimated total)", indexed, total);
        }

        public Double barValue() {
            if (total == null) return null;
            return complete ? 100.0 : Math.min(99.99, 100.0 * indexed / total);
        }
    }

    public boolean hasAction() {
        return action != null;
    }

    public boolean polls() {
        return pollingSeconds != null;
    }

    public record Lane(
        String name,
        String status,
        Instant lastActivityAt,
        String lastActivity,
        String work
    ) {
    }

    public record Event(
        Instant occurredAt,
        String occurred,
        String summary,
        String detail
    ) {
    }

    public record Action(
        String label,
        String href,
        boolean post,
        String accessibleName
    ) {
    }
}
