package com.tidymail.unsubscribe;

/**
 * The ways an unsubscribe may be performed: automated RFC 8058 POST, legacy
 * HTTPS GET, or non-automated (mailto, account page, manual).
 * <p>
 * Product functionality supported: method selection in TidyMail's unsubscribe
 * flow.
 */
public enum UnsubscribeMethod {
    RFC8058_POST,
    LEGACY_HTTPS_GET,
    MAILTO,
    ACCOUNT_PAGE,
    MANUAL;

    /**
     * Returns whether the method performs an explicit outbound request.
     */
    public boolean automated() {
        return this == RFC8058_POST || this == LEGACY_HTTPS_GET;
    }

    public boolean manual() {
        return !automated();
    }
}
