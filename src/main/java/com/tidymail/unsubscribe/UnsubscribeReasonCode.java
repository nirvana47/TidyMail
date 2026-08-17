package com.tidymail.unsubscribe;

/**
 * Categorized reasons for an unsubscribe attempt outcome, describing why a
 * candidate was rejected, skipped, or could not complete.
 * <p>
 * Product functionality supported: outcome classification in TidyMail's
 * unsubscribe lifecycle.
 */
public enum UnsubscribeReasonCode {
    NONE,
    NO_SAFE_METHOD,
    PROTECTED_SENDER,
    MALFORMED_HEADER,
    UNTRUSTED_AUTH,
    COVERAGE_MISSING,
    DESTINATION_BLOCKED,
    REDIRECT_BLOCKED,
    TIMEOUT_UNKNOWN_REMOTE_RESULT,
    NON_SUCCESS_RESPONSE,
    TRANSPORT_ERROR,
    STALE_INTENT,
    EXPIRED_INTENT,
    REPLAYED_INTENT,
    USER_COMPLETED
}
