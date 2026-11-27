package com.tidymail.mail.gmail;

import java.io.IOException;

/** Safe metadata failure context, without provider text or mailbox contents. */
public final class GmailSyncReadException extends IOException {
    private final int status;
    private final String reason;

    public GmailSyncReadException(int status, String reason) {
        super("Gmail metadata read failed");
        this.status = status;
        this.reason = reason;
    }
    public int status() { return status; }
    public String reason() { return reason; }
}
