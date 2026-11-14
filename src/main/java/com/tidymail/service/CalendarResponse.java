package com.tidymail.service;

/** Provider-neutral, observed RSVP evidence; never a cleanup recommendation. */
public record CalendarResponse(String state,String response,String eventStatus,String scope,String reason) {
    public static CalendarResponse unknown(String reason) {
        return new CalendarResponse("UNKNOWN","unknown","unknown","unknown",reason);
    }
}
