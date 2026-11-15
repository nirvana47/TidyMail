package com.tidymail.mail.outlook;

import com.tidymail.entity.UserAccount;
import com.tidymail.service.CalendarResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Follow Graph's native meeting-to-event relationship, then read current RSVP. */
@Component
public class OutlookCalendarReader {
    private final OutlookGraph graph;
    public OutlookCalendarReader(OutlookGraph graph) { this.graph=graph; }
    public static boolean hasAccess(UserAccount account) {
        return account.getScopes()!=null && account.getScopes().stream().anyMatch(s ->
            Set.of("Calendars.ReadBasic","Calendars.Read","Calendars.ReadWrite").contains(s.substring(s.lastIndexOf('/')+1)));
    }
    public List<CalendarResponse> read(UserAccount account,String messageId) {
        if(!hasAccess(account))
            return List.of(CalendarResponse.unknown("Reconnect Outlook to grant read-only Calendar access."));
        try {
            String message=URLEncoder.encode(messageId,StandardCharsets.UTF_8).replace("+","%20");
            var associated=graph.request(account,HttpMethod.GET,"/v1.0/me/messages/"+message
                +"?$select=id&$expand=microsoft.graph.eventMessage/event($select=id)",null);
            String eventId=associated.path("event").path("id").asText();
            if(eventId.isBlank())return List.of(CalendarResponse.unknown("No calendar event is associated with this Outlook email."));
            String id=URLEncoder.encode(eventId,StandardCharsets.UTF_8).replace("+","%20");
            var event=graph.request(account,HttpMethod.GET,"/v1.0/me/events/"+id
                +"?$select=id,isCancelled,responseStatus,isOrganizer,type",null);
            if(!eventId.equals(event.path("id").asText()))return List.of(CalendarResponse.unknown("The associated event identity could not be verified."));
            String type=event.path("type").asText();
            String scope=switch(type){case "seriesMaster" -> "series";case "occurrence","exception" -> "occurrence";case "singleInstance" -> "event";default -> "unknown";};
            if(scope.equals("unknown"))return List.of(CalendarResponse.unknown("The calendar event type is unavailable."));
            if(event.path("isCancelled").asBoolean())return List.of(new CalendarResponse("MATCHED","cancelled","cancelled",scope,"Outlook Calendar marks this event as cancelled."));
            if(event.path("isOrganizer").asBoolean())return List.of(new CalendarResponse("MATCHED","organizer","confirmed",scope,"You organize this event; an attendee RSVP is not required."));
            String response=switch(event.path("responseStatus").path("response").asText()){
                case "accepted" -> "accepted";case "declined" -> "declined";case "tentativelyAccepted" -> "tentative";
                case "notResponded" -> "needsAction";default -> "unknown";
            };
            if(response.equals("unknown"))return List.of(CalendarResponse.unknown("Your current Outlook Calendar response is unavailable."));
            return List.of(new CalendarResponse("MATCHED",response,"confirmed",scope,"Matched using Outlook's associated calendar event. RSVP does not establish whether an update was reviewed."));
        } catch(com.tidymail.mail.CleanupProviderException denied) {
            return List.of(CalendarResponse.unknown(denied.status()==403
                ? "Microsoft denied Calendar access. Reconnect Outlook with read-only Calendars.Read access."
                : "Outlook Calendar could not be checked. Retry later."));
        } catch(RuntimeException unavailable){
            return List.of(CalendarResponse.unknown("Outlook Calendar could not be checked. Retry or reconnect Outlook if needed."));
        }
    }
}
