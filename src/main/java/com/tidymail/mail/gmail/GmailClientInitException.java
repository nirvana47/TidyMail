package com.tidymail.mail.gmail;

/**
 * Thrown when a Gmail API client cannot be created or initialized for a user
 * account (for example, an unsupported provider or a transport failure).
 *
 * <p>Product functionality supported: Gmail provider OAuth client creation.
 */
public class GmailClientInitException extends RuntimeException {

    public GmailClientInitException(String message) {
        super(message);
    }

    public GmailClientInitException(String message, Throwable cause) {
        super(message, cause);
    }
}
