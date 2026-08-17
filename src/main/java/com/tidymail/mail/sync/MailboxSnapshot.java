package com.tidymail.mail.sync;

public record MailboxSnapshot(
    String cursor,
    SyncCapabilities.ChangeFeedKind cursorKind,
    Long reliableTotal
) {
    public MailboxSnapshot {
        if (cursorKind == null) {
            throw new IllegalArgumentException("Cursor kind is required");
        }
        if (cursor != null && cursor.isBlank()) {
            throw new IllegalArgumentException("Cursor must be null or non-blank");
        }
        if (cursorKind == SyncCapabilities.ChangeFeedKind.NONE && cursor != null) {
            throw new IllegalArgumentException("Providers without a change feed cannot expose a cursor");
        }
        if (reliableTotal != null && reliableTotal < 0) {
            throw new IllegalArgumentException("Reliable total must be non-negative");
        }
    }
}
