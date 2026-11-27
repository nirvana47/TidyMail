package com.tidymail.dto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * View model for the Activity page's live status panel.
 *
 * <p>Product functionality supported: the <em>Activity</em> workspace tab's
 * status widget, which shows the current indexed count, scoped listing progress,
 * last completed check, current activity, recent events, and an optional action. Exposes helper predicates so the template can conditionally render
 * each section.</p>
 */
public record ActivityStatusModel(
    String stateClass,
    String headline,
    String phase,
    String guidance,
    long indexedMessages,
    ListingProgress listing,
    String initialImport,
    String freshness,
    Instant freshnessAt,
    String currentActivity,
    String connection,
    List<Event> events,
    Action action,
    Integer pollingSeconds,
    boolean alert,
    String announcement,
    String batchProgress,
    Instant refreshedAt
) {
    public ActivityStatusModel {
        events = List.copyOf(events);
    }

    public record ListingProgress(long indexed, Long total, String scope) {
        public String text() {
            if (total == null) return String.format(Locale.US, "Counting messages… %,d indexed", indexed);
            return String.format(Locale.US, "%,d of ~%,d indexed (estimated total)", indexed, total);
        }

        public Double barValue() {
            if (total == null) return null;
            return Math.min(99.99, 100.0 * indexed / total);
        }
    }

    public String formattedIndexedMessages() {
        return String.format(Locale.US, "%,d", indexedMessages);
    }

    public boolean hasAction() {
        return action != null;
    }

    public boolean polls() {
        return pollingSeconds != null;
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
