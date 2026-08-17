package com.tidymail.dto;

public record SenderCategoryOption(long id, long accountId, String name) {
    public SenderCategoryOption {
        if (id <= 0 || accountId <= 0 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Valid sender category option is required");
        }
    }
}
