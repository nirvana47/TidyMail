package com.tidymail.dto;

import java.util.Objects;

public record AnalyticsTopSender(
    long accountId,
    String senderKey,
    String accountLabel,
    String senderLabel,
    String senderAddress,
    String senderDomain,
    long receivedCount,
    String senderQueueUrl
) {
    public AnalyticsTopSender(
            String accountLabel,
            String senderLabel,
            String senderAddress,
            String senderDomain,
            long receivedCount,
            String senderQueueUrl) {
        this(1, senderAddress == null ? "~unknown" : senderAddress, accountLabel,
            senderLabel, senderAddress, senderDomain, receivedCount, senderQueueUrl);
    }

    public AnalyticsTopSender {
        if (accountId <= 0 || senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException("Ranked sender identity is required");
        }
        Objects.requireNonNull(accountLabel, "accountLabel");
        senderLabel = bounded(senderLabel, "Unknown sender", 512);
        senderAddress = bounded(senderAddress, null, 320);
        senderDomain = bounded(senderDomain, null, 253);
        senderQueueUrl = bounded(senderQueueUrl, null, 2048);
        if (receivedCount < 1) {
            throw new IllegalArgumentException("A ranked sender must have received rows");
        }
        if (senderAddress == null && senderQueueUrl != null) {
            throw new IllegalArgumentException("Unknown sender cannot have a locator");
        }
    }

    private static String bounded(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return safe.substring(0, Math.min(max, safe.length()));
    }
}
