package com.tidymail.unsubscribe;

/**
 * Lifecycle states of an unsubscribe attempt, from pending through accepted and
 * confirmed, to failed or manual completion.
 * <p>
 * Product functionality supported: outcome tracking in TidyMail's unsubscribe
 * lifecycle.
 */
public enum UnsubscribeOutcomeStatus {
    PENDING,
    ACCEPTED,
    CONFIRMED,
    FAILED,
    MANUAL_REQUIRED,
    VERIFICATION_PENDING
}
