package com.tidymail.mail;

import java.util.OptionalLong;

public record LoadedMessageProgress(long loadedCount, Long reliableDenominator) {

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

    public String statusText() {
        return reliableDenominator == null
            ? "Loaded " + loadedCount + " messages"
            : "Loaded " + loadedCount + " out of " + reliableDenominator + " messages";
    }
}
