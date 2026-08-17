package com.tidymail.unsubscribe;

/**
 * Records how an unsubscribe confirmation was obtained: accepted by the sender
 * endpoint, confirmed by the user, or confirmed by the provider.
 * <p>
 * Product functionality supported: confirmation tracking in TidyMail's
 * unsubscribe lifecycle.
 */
public enum UnsubscribeConfirmationSource {
    ENDPOINT_ACCEPTED,
    USER_CONFIRMED,
    PROVIDER_CONFIRMED
}
