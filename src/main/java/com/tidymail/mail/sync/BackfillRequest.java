package com.tidymail.mail.sync;

public record BackfillRequest(String pageToken, int maxResults) {
    public BackfillRequest {
        if (pageToken != null && pageToken.isBlank()) {
            throw new IllegalArgumentException("Page token must be null or non-blank");
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("Backfill max results must be positive");
        }
    }
}
