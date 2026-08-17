package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

public record ActivityStatusModel(
    String stateClass,
    String headline,
    String phase,
    String guidance,
    long indexedMessages,
    Long providerTotal,
    Instant providerTotalObservedAt,
    Long remainingMessages,
    String initialBatch,
    String freshness,
    Instant freshnessAt,
    String rescan,
    boolean showProgress,
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

    public boolean hasProviderTotal() {
        return providerTotal != null;
    }

    public boolean hasRemainingMessages() {
        return remainingMessages != null;
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
