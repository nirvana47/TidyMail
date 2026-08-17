package com.tidymail.dto;

public record SelectionScopeDigest(
    String accountLabel,
    String scopeHash,
    int messageCount
) {
    public SelectionScopeDigest {
        accountLabel = accountLabel == null ? "" : accountLabel.strip();
        if (accountLabel.isEmpty() || accountLabel.length() > 512) {
            throw new IllegalArgumentException("Safe account label is required");
        }
        if (scopeHash == null || !scopeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Scope hash must be lowercase SHA-256");
        }
        if (messageCount < 1 || messageCount > 1000) {
            throw new IllegalArgumentException("Scope message count must be between 1 and 1000");
        }
    }
}
