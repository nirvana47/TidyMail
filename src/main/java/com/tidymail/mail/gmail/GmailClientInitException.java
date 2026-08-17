package com.tidymail.mail.gmail;

public class GmailClientInitException extends RuntimeException {

    public GmailClientInitException(String message) {
        super(message);
    }

    public GmailClientInitException(String message, Throwable cause) {
        super(message, cause);
    }
}
