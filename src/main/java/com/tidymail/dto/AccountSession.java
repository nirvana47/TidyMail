package com.tidymail.dto;

/**
 * Identifies a signed-in user's session scope: the owner key (email:provider)
 * and the currently active mailbox account.
 *
 * <p>Product functionality supported: <em>session / account scoping</em>.
 * Used to bind an HTTP session to an owner and remember which account is
 * active, so multi-account users can switch without re-authenticating.</p>
 */
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
