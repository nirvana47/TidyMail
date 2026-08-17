package com.tidymail.mail;

import java.util.OptionalLong;

/**
 * Tracks live progress while loading messages during a metadata-only sync.
 * <p>
 * Product functionality supported: progress reporting for mailbox sync,
 * using an optional denominator only when the provider total is reliable.
 */
public record LoadedMessageProgress(long loadedCount, Long reliableDenominator) {

    /**
     * Validates that counts are non-negative and the denominator is reliable.
     */
    public LoadedMessageProgress {
        if (loadedCount < 0) throw new IllegalArgumentException("loadedCount must be non-negative");
        if (reliableDenominator != null && reliableDenominator < loadedCount) {
            throw new IllegalArgumentException("denominator cannot be below loadedCount");
        }
    }

    public boolean hasReliableDenominator() {
        return reliableDenominator != null;
    }

    public OptionalLong denominator() {
        return reliableDenominator == null
            ? OptionalLong.empty() : OptionalLong.of(reliableDenominator);
    }

    /**
     * Formats a human-readable progress line, with or without a total.
     */
    public String statusText() {
        return reliableDenominator == null
            ? "Loaded " + loadedCount + " messages"
            : "Loaded " + loadedCount + " out of " + reliableDenominator + " messages";
    }
}
