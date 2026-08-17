package com.tidymail.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AnalyticsSourcePage(
    AnalyticsSourceQuery query,
    AnalyticsAvailability availability,
    long total,
    List<SourceRow> rows,
    String backUrl,
    String previousUrl,
    String nextUrl
) {
    public AnalyticsSourcePage {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(availability, "availability");
        rows = List.copyOf(rows);
        if (total < 0 || rows.size() > AnalyticsSourceQuery.PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid source page");
        }
    }

    public int totalPages() {
        return total == 0 ? 0
            : Math.toIntExact((total + AnalyticsSourceQuery.PAGE_SIZE - 1)
                / AnalyticsSourceQuery.PAGE_SIZE);
    }

    public record SourceRow(
        String accountLabel,
        Instant receivedAt,
        String senderDisplay,
        String senderAddress,
        String senderDomain,
        String providerLink,
        Instant factAt,
        String action,
        String outcome
    ) {
        public SourceRow(
                String accountLabel,
                Instant receivedAt,
                String senderDisplay,
                String senderAddress,
                String senderDomain,
                String providerLink) {
            this(accountLabel, receivedAt, senderDisplay, senderAddress, senderDomain,
                providerLink, receivedAt, null, null);
        }

        public SourceRow {
            Objects.requireNonNull(accountLabel, "accountLabel");
            Objects.requireNonNull(receivedAt, "receivedAt");
            Objects.requireNonNull(factAt, "factAt");
            senderDisplay = safe(senderDisplay, "Unknown sender", 512);
            senderAddress = safe(senderAddress, null, 320);
            senderDomain = safe(senderDomain, null, 253);
            providerLink = safe(providerLink, null, 2048);
            action = safe(action, null, 32);
            outcome = safe(outcome, null, 32);
        }

        private static String safe(String value, String fallback, int max) {
            if (value == null || value.isBlank()) return fallback;
            String compact = value.replaceAll("[\\p{Cntrl}]", " ").trim();
            return compact.substring(0, Math.min(compact.length(), max));
        }
    }
}
