package com.tidymail.dto;

/**
 * A selectable sender-category option (id, account, name) for the Senders page.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's category
 * selection and management.
 */
public record SenderCategoryOption(long id, long accountId, String name) {
    public SenderCategoryOption {
        if (id <= 0 || accountId <= 0 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Valid sender category option is required");
        }
    }
}
