package com.tidymail.dto;

public record AccountSession(
    String ownerKey,
    Long activeAccountId
) {
    public AccountSession {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("Owner key is required");
        }
        if (activeAccountId == null || activeAccountId <= 0) {
            throw new IllegalArgumentException("Active account ID is required");
        }
    }
}
