package com.tidymail.mail.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.tidymail.entity.UserAccount;
import com.tidymail.service.CalendarEvidenceParser.Identity;
import com.tidymail.service.CalendarResponse;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bounded GET-only matching against the connected account's primary calendar. */
@Component
public class GoogleCalendarReader {
    public static final String SCOPE = "https://www.googleapis.com/auth/calendar.events.readonly";
    private static final String ROOT = "https://www.googleapis.com/calendar/v3/calendars/primary/events";
    private static final String FIELDS = "nextPageToken,items(id,iCalUID,status,recurrence,originalStartTime,organizer(email,self),attendees(email,self,responseStatus))";
    private final GmailClientFactory clients;
    private final ObjectMapper json;
    public GoogleCalendarReader(GmailClientFactory clients, ObjectMapper json) { this.clients=clients; this.json=json; }

    public static boolean hasAccess(UserAccount account) {
        return account.getScopes()!=null && account.getScopes().stream().anyMatch(s -> Set.of(SCOPE,
            "https://www.googleapis.com/auth/calendar.readonly", "https://www.googleapis.com/auth/calendar").contains(s));
    }

    public List<CalendarResponse> read(UserAccount account, List<Identity> identities) {
        if (!hasAccess(account))
            return List.of(CalendarResponse.unknown("Reconnect Google to grant read-only Calendar access."));
        if (identities.isEmpty() || identities.size()>8)
            return List.of(CalendarResponse.unknown("A complete calendar identity could not be read from this invitation."));
        try {
            var reads=new Reads(account);
            var results=new ArrayList<CalendarResponse>();
            for (var identity:identities) results.add(match(reads, identity));
            return List.copyOf(results);
        } catch (Exception failure) {
            return List.of(CalendarResponse.unknown("Calendar could not be checked. Retry or reconnect Google if needed."));
        }
    }

    private CalendarResponse match(Reads reads, Identity identity) throws IOException {
        if (identity.unsupported() || identity.uid()==null || identity.uid().isBlank() || identity.uid().length()>4096 || identity.organizer()==null)
            return CalendarResponse.unknown("Event identity, organizer or occurrence is missing or unsupported.");
        String organizer=identity.organizer().replaceFirst("(?i)^mailto:", "");
        if (organizer.isBlank()) return CalendarResponse.unknown("The organizer is missing.");
        var query=new GenericUrl(ROOT);
        query.set("iCalUID", identity.uid()); query.set("showDeleted", true);
        query.set("showHiddenInvitations", true); query.set("maxResults", 100); query.set("fields", FIELDS);
        var page=reads.get(query);
        if (page.has("nextPageToken")) return CalendarResponse.unknown("Calendar match limit reached; the result is incomplete.");
        var candidates=new ArrayList<JsonNode>();
        var masters=new ArrayList<JsonNode>();
        for (var event:page.path("items")) {
            if (!identity.uid().equals(event.path("iCalUID").asText())) continue;
            // Never match a subject or a schedule alone, or silently choose a duplicate.
            if (!organizer.equalsIgnoreCase(event.path("organizer").path("email").asText())) continue;
            if (event.has("recurrence")) masters.add(event);
            if (identity.instance()==null ? !event.has("originalStartTime") : sameOccurrence(identity,event.path("originalStartTime")))
                candidates.add(event);
        }
        if (candidates.isEmpty() && identity.instance()!=null && masters.size()==1
                && !"cancelled".equals(masters.getFirst().path("status").asText())) {
            // Non-exception occurrences aren't listed beside the series master. Expand a
            // bounded window, then compare originalStartTime, never the changed start.
            Instant anchor=instanceInstant(identity);
            if (anchor==null) return CalendarResponse.unknown("The occurrence timezone could not be established.");
            String eventId=masters.getFirst().path("id").asText();
            if (!eventId.matches("[a-zA-Z0-9_-]{1,1024}")) return CalendarResponse.unknown("Invalid calendar event identifier.");
            var instances=new GenericUrl(ROOT+"/"+eventId+"/instances");
            instances.set("timeMin",anchor.minusSeconds(86400).toString());
            instances.set("timeMax",anchor.plusSeconds(86400).toString());
            instances.set("showDeleted",true); instances.set("maxResults",100); instances.set("fields",FIELDS);
            var expanded=reads.get(instances);
            if (expanded.has("nextPageToken")) return CalendarResponse.unknown("Occurrence match limit reached.");
            for (var event:expanded.path("items"))
                if (identity.uid().equals(event.path("iCalUID").asText())
                        && organizer.equalsIgnoreCase(event.path("organizer").path("email").asText())
                        && sameOccurrence(identity,event.path("originalStartTime"))) candidates.add(event);
        }
        if (candidates.size()!=1) return CalendarResponse.unknown(candidates.isEmpty()
            ? "No exact match on the primary calendar. The event may be missing or on another calendar."
            : "Multiple calendar events match; no response was inferred.");
        var event=candidates.getFirst();
        String scope=identity.instance()!=null ? "occurrence" : event.has("recurrence") ? "series" : "event";
        String status=event.path("status").asText();
        if ("cancelled".equals(status)) return new CalendarResponse("MATCHED","cancelled",status,scope,"Calendar marks this event as cancelled.");
        if (!Set.of("confirmed","tentative").contains(status)) return CalendarResponse.unknown("Calendar event status is unavailable.");
        if (event.path("organizer").path("self").asBoolean())
            return new CalendarResponse("MATCHED","organizer",status,scope,"You organize this event; an attendee RSVP is not required.");
        var self=new ArrayList<JsonNode>();
        for(var attendee:event.path("attendees")) if(attendee.path("self").asBoolean()) self.add(attendee);
        if(self.size()!=1) return CalendarResponse.unknown("Your attendee response could not be identified uniquely.");
        String response=self.getFirst().path("responseStatus").asText();
        if(!Set.of("accepted","declined","tentative","needsAction").contains(response))
            return CalendarResponse.unknown("Your calendar response is unavailable.");
        return new CalendarResponse("MATCHED",response,status,scope,"Matched by event identity, organizer and occurrence. RSVP does not establish whether an update was reviewed.");
    }

    static boolean sameOccurrence(Identity identity, JsonNode original) {
        if (identity.instance()==null) return false;
        try {
            if (identity.instance().matches("[0-9]{8}"))
                return LocalDate.parse(identity.instance(),DateTimeFormatter.BASIC_ISO_DATE).toString().equals(original.path("date").asText());
            Instant expected=instanceInstant(identity);
            return expected!=null && expected.equals(OffsetDateTime.parse(original.path("dateTime").asText()).toInstant());
        } catch (RuntimeException invalid) { return false; }
    }

    private static Instant instanceInstant(Identity identity) {
        try {
            String value=identity.instance();
            if(value.endsWith("Z")) return LocalDateTime.parse(value.substring(0,value.length()-1),
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC);
            if(value.length()==8 || identity.timezone()==null) return null;
            var local=LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            var zone=ZoneId.of(identity.timezone());
            if(zone.getRules().getValidOffsets(local).size()!=1) return null;
            return local.atZone(zone).toInstant();
        } catch(RuntimeException invalid) { return null; }
    }

    private class Reads {
        final com.google.api.services.gmail.Gmail client;
        int count; long bytes;
        Reads(UserAccount account) { client=clients.buildFor(account); }
        JsonNode get(GenericUrl url) throws IOException {
            if(++count>12) throw new IOException("Calendar read limit reached");
            var request=client.getRequestFactory().buildGetRequest(url);
            request.setFollowRedirects(false);request.setNumberOfRetries(0);request.setLoggingEnabled(false);
            request.setUnsuccessfulResponseHandler(null);request.setIOExceptionHandler(null);
            request.setConnectTimeout(5000);request.setReadTimeout(10000);
            var response=request.execute();
            try {
                byte[] data=response.getContent().readNBytes(1048577);bytes+=data.length;
                if(data.length>1048576 || bytes>4194304)throw new IOException("Calendar response limit reached");
                return json.readTree(data);
            } finally { response.disconnect(); }
        }
    }
}
