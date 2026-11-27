package com.tidymail.mail.gmail;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ephemeral read feedback only. Durable messages and cursors remain in PostgreSQL. */
public final class GmailSyncReadProgress {
    private final Map<Long, Map<Long, Snapshot>> accounts = new ConcurrentHashMap<>();

    public record Snapshot(int completed, int total, boolean retrying) {}

    public void begin(Long account, int total) {
        if (account != null) accounts.computeIfAbsent(account, ignored -> new ConcurrentHashMap<>())
            .put(Thread.currentThread().threadId(), new Snapshot(0, total, false));
    }

    public void update(Long account, int completed, boolean retrying) {
        if (account == null) return;
        var reads = accounts.get(account);
        if (reads != null) reads.computeIfPresent(Thread.currentThread().threadId(),
            (id, previous) -> new Snapshot(completed, previous.total(), retrying));
    }

    public void finish(Long account) {
        if (account == null) return;
        // Keep the map until account removal/restart so a concurrent begin cannot be lost.
        var reads = accounts.get(account);
        if (reads != null) reads.remove(Thread.currentThread().threadId());
    }

    public Snapshot snapshot(long account) {
        var reads = accounts.get(account);
        return reads == null ? null : reads.values().stream()
            .max(java.util.Comparator.comparingInt(Snapshot::total)).orElse(null);
    }
}
