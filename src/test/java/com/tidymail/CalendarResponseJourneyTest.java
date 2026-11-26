package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.testing.http.*;
import com.google.api.services.gmail.Gmail;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.gmail.*;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.CalendarResponseService;
import com.tidymail.support.PostgresJourneyTest;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Exercises real MIME extraction, Calendar transport, persistence and dashboard filtering. */
class CalendarResponseJourneyTest extends PostgresJourneyTest {
    static final String OWNER="calendar@example.test";
    @MockBean GmailClientFactory clients;
    @MockBean com.tidymail.mail.outlook.OutlookGraph graph;
    @Autowired CalendarResponseService calendar;
    @Autowired com.tidymail.service.CalendarLabelRefreshService calendarLabels;
    @Autowired DashboardRepository dashboard;
    @Autowired ObjectMapper json;
    long account; UUID message;
    String response="accepted", status="confirmed";
    boolean duplicate, expanded, forbidden;
    final List<String> requests=new ArrayList<>();

    @BeforeEach void setup() {
        account=userAccounts.createAccount(OWNER);
        jdbc.update("UPDATE user_accounts SET scopes=ARRAY[?] WHERE id=?",GoogleCalendarReader.SCOPE,account);
        message=jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                from_display_name,dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at)
            VALUES(?,'google','invite','organizer@example.test','organizer@example.test','Organizer','ADDRESS',
                'organizer@example.test','Updated invitation: [CANCELED] Planning',ARRAY['INBOX'],true,now())
            RETURNING dashboard_message_id
            """,UUID.class,account);
        var transport=new MockHttpTransport() {
            @Override public LowLevelHttpRequest buildRequest(String method,String url) {
                assertThat(method).isEqualTo("GET");
                return new MockLowLevelHttpRequest(url) {
                    @Override public LowLevelHttpResponse execute() throws IOException {
                        requests.add(URLDecoder.decode(url,StandardCharsets.UTF_8));
                        String body;
                        if(url.contains("/attachments/")) {
                            String ics="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:stable-uid@example.test\r\nORGANIZER:mailto:organizer@example.test\r\nRECURRENCE-ID:20261125T013000Z\r\nDTSTART:20261126T013000Z\r\nDTEND:20261126T020000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
                            body=json.writeValueAsString(Map.of("data",Base64.getUrlEncoder().withoutPadding().encodeToString(ics.getBytes(StandardCharsets.UTF_8))));
                        } else if(url.contains("gmail.googleapis.com")) {
                            body="""
                                {"id":"invite","payload":{"mimeType":"multipart/mixed","parts":[
                                  {"partId":"1","mimeType":"text/calendar","filename":"invite.ics","body":{"size":500,"attachmentId":"ics"}},
                                  {"partId":"2","mimeType":"application/pdf","filename":"notes.pdf","body":{"size":100,"attachmentId":"pdf"}}]}}
                                """;
                        } else if(forbidden) return new MockLowLevelHttpResponse().setStatusCode(403).setContentType("application/json").setContent("{\"error\":{\"code\":403}}");
                        else if(url.contains("/instances?")) body=json.writeValueAsString(Map.of("items",List.of(event(true))));
                        else {
                            var events=new ArrayList<Map<String,Object>>();
                            events.add(event(false));
                            if(!expanded)events.add(event(true));
                            if(duplicate)events.add(event(true));
                            var unrelated=new HashMap<>(event(true));
                            unrelated.put("originalStartTime",Map.of("dateTime","2026-11-17T17:30:00-08:00"));
                            unrelated.put("status","cancelled");events.add(unrelated);
                            body=json.writeValueAsString(Map.of("items",events));
                        }
                        return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json").setContent(body);
                    }
                };
            }
        };
        var gmail=new Gmail.Builder(transport,JacksonFactory.getDefaultInstance(),null).setApplicationName("Calendar journey").build();
        when(clients.buildFor(any())).thenReturn(gmail);
    }
    Map<String,Object> event(boolean occurrence) {
        var event=new HashMap<String,Object>();
        event.put("id",occurrence?"instance":"series");event.put("iCalUID","stable-uid@example.test");
        event.put("status",occurrence?status:"confirmed");
        event.put("organizer",Map.of("email","organizer@example.test"));
        event.put("attendees",List.of(Map.of("self",true,"responseStatus",response)));
        if(occurrence)event.put("originalStartTime",Map.of("dateTime","2026-11-24T17:30:00-08:00"));
        else event.put("recurrence",List.of("RRULE:FREQ=WEEKLY"));
        return event;
    }
    DashboardFilter filter(String label) {return DashboardFilter.from(Map.of("scope","inbox","labels",label));}
    long matching(String label) {return ((Number)dashboard.page(account,filter(label)).summary().get("messages")).longValue();}

    @Test void exactOccurrenceRsvpSurvivesCancelledSubjectAndAppearsInOwnedDashboard() throws Exception {
        mockMvc.perform(post("/dashboard/api/messages/{id}/calendar",message).with(authenticatedAs(OWNER)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESPONDED"))
            .andExpect(jsonPath("$.events[0].response").value("accepted"));
        assertThat(matching("CALENDAR_RESPONDED")).isEqualTo(1);
        assertThat(matching("CALENDAR_CANCELLED")).isZero();
        var page=dashboard.page(account,filter("CALENDAR_RESPONDED"));
        var detail=dashboard.details(account,filter("CALENDAR_RESPONDED"),page.groups().getFirst().id(),0);
        assertThat(detail.messages().getFirst().calendarResponse().path("events").get(0).path("scope").asText()).isEqualTo("occurrence");
        assertThat(requests).hasSize(3).allMatch(r->!r.contains("attachments/pdf"));
        assertThat(requests.getLast()).contains("iCalUID=stable-uid@example.test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT facts::text FROM calendar_response_checks",String.class))
            .doesNotContain("stable-uid", "BEGIN:VCALENDAR", "organizer@example.test");
        verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @Test void ordinaryOccurrenceIsExpandedAndAmbiguousOrExpiredEvidenceNeverQualifies() {
        expanded=true;
        assertThat(calendar.inspect(account,message).state()).isEqualTo("RESPONDED");
        assertThat(requests).anyMatch(r->r.contains("/instances?"));
        jdbc.update("UPDATE calendar_response_checks SET checked_at=now()-interval '2 hours'");
        assertThat(matching("CALENDAR_RESPONDED")).isZero();
        expanded=false;duplicate=true;
        assertThat(calendar.inspect(account,message).state()).isEqualTo("UNKNOWN");
        assertThat(matching("CALENDAR_RESPONDED")).isZero();
    }

    @Test void currentResponseAndCalendarCancellationComeFromCalendarNotSentMail() {
        response="needsAction";
        assertThat(calendar.inspect(account,message).state()).isEqualTo("NEEDS_RESPONSE");
        assertThat(matching("CALENDAR_NEEDS_RESPONSE")).isEqualTo(1);

        status="cancelled";
        assertThat(calendar.inspect(account,message).state()).isEqualTo("CANCELLED");
        assertThat(matching("CALENDAR_CANCELLED")).isEqualTo(1);
        assertThat(matching("CALENDAR_NEEDS_RESPONSE")).isZero();
        assertThat(matching("CALENDAR_RESPONDED")).isZero();

        forbidden=true;
        assertThat(calendar.inspect(account,message).state()).isEqualTo("UNKNOWN");
        assertThat(matching("CALENDAR_CANCELLED")).isZero();
    }

    @Test void calendarFilterChoicesExposeOnlyTheThreeStatusLabels() throws Exception {
        String html=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dashboard")
                .with(authenticatedAs(OWNER))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("value=\"CALENDAR_RESPONDED\"", "value=\"CALENDAR_NEEDS_RESPONSE\"", "value=\"CALENDAR_CANCELLED\"")
            .doesNotContain("value=\"CALENDAR_INVITE\"", "value=\"PAST_EVENT\"");
    }

    @Test void backgroundRefreshChecksOnlyKnownInvitesAndResumesWithoutReclassifyingMail() {
        jdbc.update("""
            INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts)
            VALUES(?,'invite','test','test',now(),'{"parts":[{"calendar":true}]}')
            """,account);
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at)
            VALUES(?,'google','ordinary','news@example.test','news@example.test','ADDRESS','news@example.test',
                'Ordinary email',ARRAY['INBOX'],true,now())
            """,account);
        calendarLabels.runOnce();
        assertThat(matching("CALENDAR_RESPONDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_response_checks",Long.class)).isEqualTo(1);
        int reads=requests.size();
        calendarLabels.runOnce();
        assertThat(requests).hasSize(reads);
        jdbc.update("UPDATE calendar_response_checks SET checked_at=now()-interval '55 minutes'");
        status="cancelled";
        calendarLabels.runOnce();
        assertThat(matching("CALENDAR_CANCELLED")).isEqualTo(1);
        assertThat(matching("CALENDAR_RESPONDED")).isZero();
        forbidden=true;
        jdbc.update("UPDATE calendar_response_checks SET checked_at=now()-interval '2 hours'");
        calendarLabels.runOnce();
        assertThat(matching("CALENDAR_CANCELLED")).isZero();
        reads=requests.size();
        calendarLabels.runOnce();
        assertThat(requests).hasSize(reads); // Unknown matches back off for a day.
        jdbc.update("UPDATE user_accounts SET scopes=ARRAY['openid'] WHERE id=?",account);
        jdbc.update("UPDATE calendar_response_checks SET checked_at=now()-interval '2 days'");
        calendarLabels.runOnce();
        assertThat(requests).hasSize(reads);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
        verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @Test void ownershipAndCsrfAreCheckedBeforeAnyProviderRead() throws Exception {
        userAccounts.createAccount("other@example.test");
        mockMvc.perform(post("/dashboard/api/messages/{id}/calendar",message).with(authenticatedAs("other@example.test")).with(csrf()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/dashboard/api/messages/{id}/calendar",message).with(authenticatedAs(OWNER)))
            .andExpect(status().isForbidden());
        assertThat(requests).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_response_checks",Long.class)).isZero();
    }
    @Test void outlookFollowsAssociatedEventAndPersistsItsCurrentResponse() throws Exception {
        long outlook=userAccounts.createAccount("outlook-calendar@example.test","microsoft");
        jdbc.update("UPDATE user_accounts SET scopes=ARRAY['Calendars.Read'] WHERE id=?",outlook);
        UUID meeting=jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at)
            VALUES(?,'microsoft','meeting','organizer@example.test','organizer@example.test','ADDRESS',
                'organizer@example.test','Updated invitation',ARRAY['INBOX'],true,now()) RETURNING dashboard_message_id
            """,UUID.class,outlook);
        when(graph.request(any(),eq(org.springframework.http.HttpMethod.GET),contains("/messages/"),isNull()))
            .thenReturn(json.readTree("{\"id\":\"meeting\",\"event\":{\"id\":\"event-one\"}}"));
        when(graph.request(any(),eq(org.springframework.http.HttpMethod.GET),contains("/events/"),isNull()))
            .thenReturn(json.readTree("{\"id\":\"event-one\",\"type\":\"exception\",\"isCancelled\":false,\"responseStatus\":{\"response\":\"accepted\"}}"));
        assertThat(calendar.inspect(outlook,meeting).events().getFirst().response()).isEqualTo("accepted");
        assertThat(dashboard.page(outlook,filter("CALENDAR_RESPONDED")).summary()).containsEntry("messages",1L);
        verify(graph).request(any(),eq(org.springframework.http.HttpMethod.GET),contains("microsoft.graph.eventMessage/event($select=id)"),isNull());
        verify(graph).request(any(),eq(org.springframework.http.HttpMethod.GET),contains("/events/event-one?"),isNull());
        verifyNoInteractions(clients);
        jdbc.update("UPDATE user_accounts SET scopes=ARRAY['Mail.ReadWrite'] WHERE id=?",outlook);
        clearInvocations(graph);
        assertThat(calendar.inspect(outlook,meeting).state()).isEqualTo("UNKNOWN");
        assertThat(dashboard.page(outlook,filter("CALENDAR_RESPONDED")).summary()).containsEntry("messages",0L);
        verifyNoInteractions(graph);
    }

}
