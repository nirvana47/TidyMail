package com.tidymail.mail;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** Sanitized provider status and retry guidance; never persist provider response bodies. */
public class CleanupProviderException extends RuntimeException {
    private final int status;
    private final Instant retryAt;
    public CleanupProviderException(int status,String retryAfter) {
        super("Cleanup provider request failed; label verification required");this.status=status;
        Instant at=null;
        if(retryAfter!=null)try{at=Instant.now().plusSeconds(Math.max(0,Long.parseLong(retryAfter)));}
        catch(RuntimeException ignored){try{at=ZonedDateTime.parse(retryAfter,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException invalid){}}
        this.retryAt=at;
    }
    public int status(){return status;}
    public Instant retryAt(){return retryAt;}
    public boolean rejected(){return status==400||status==401||status==403||status==404||status==429;}
}
