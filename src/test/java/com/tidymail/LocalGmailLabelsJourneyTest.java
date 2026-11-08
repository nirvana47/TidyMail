package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.MailFolderOrLabel;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.gmail.relay-recipients.1=alias@relay.example.test")
class LocalGmailLabelsJourneyTest extends PostgresJourneyTest {
    private static final String OWNER = "local-labels@example.test";
    @Autowired RecommendationService recommendations;
    @Autowired RecommendationProcessingService processing;
    @Autowired DashboardRepository dashboard;
    @Autowired ObjectMapper json;
    long account;

    @BeforeEach void account() { account = userAccounts.createAccount(OWNER); }

    @Test void overlapsReasonsCorrectionsAndFiltersUseTheSameMessages() throws Exception {
        UUID receipt = mail("receipt", "billing@notify.amazon.com", "Billing", "Invoice payment has posted", "{INBOX,Label_12,CATEGORY_PROMOTIONS}");
        UUID order = mail("order", "orders@amazon.com", "Orders", "Your order is confirmed", "{INBOX}");
        UUID shipping = mail("shipping", "orders@amazon.com", "Orders", "Your order 12345 has shipped", "{}");
        UUID security = mail("security", "alerts@linkedin.com", "LinkedIn", "Password reset requested", "{INBOX}");
        UUID bill = mail("bill", "billpay@alerts.chase.com", "Chase", "Your payment is due", "{INBOX}");
        UUID statement = mail("statement", "billing@provider.example.test", "Billing", "Your current statement is available", "{}");
        UUID investing = mail("investing", "alerts@fidelity.com", "Fidelity", "New device signed in", "{INBOX}");
        UUID comment = mail("comment", "drive-shares-dm-noreply@google.com", "Google Sheets", "New comment in the worksheet", "{INBOX}");
        UUID unrelated = mail("unrelated", "retail@notamazon.com", "Sender", "Buy a new device and write your personal statement on LinkedIn", "{INBOX}");
        UUID spam = mail("spam", "receipts@amazon.com", "Receipt", "Your receipt", "{INBOX,SPAM}");
        mail("sent", "receipts@amazon.com", "Receipt", "Your receipt", "{SENT}");
        jdbc.update("UPDATE message_metadata SET received_at=now()-interval '31 days' WHERE dashboard_message_id=?",comment);
        jdbc.update("UPDATE message_metadata SET to_values=ARRAY['Alias <alias@relay.example.test>'] WHERE dashboard_message_id=?",receipt);

        assertThat(processing.status(account).enabled()).isTrue();
        assertThat(count("all","SHOPPING","all",false)).isZero();
        drain();

        assertThat(view(receipt).labelReasons()).containsKeys("RECEIPT","SHOPPING","GMAIL_PROMOTIONS").doesNotContainKey("BILL");
        assertThat(view(receipt).labelReasons().get("RECEIPT")).contains("payment has posted","subject");
        assertThat(view(receipt).shoppingBrand()).isEqualTo("Amazon");
        assertThat(view(receipt).relayRecipients()).containsExactly("alias@relay.example.test");
        assertThat(view(order).labelReasons()).containsKeys("SHOPPING","ORDER").doesNotContainKey("SHIPPING");
        assertThat(view(shipping).labelReasons().get("SHIPPING")).contains("subject").doesNotContain("12345");
        assertThat(view(security).labelReasons()).containsKeys("SOCIAL","SECURITY");
        assertThat(view(bill).labelReasons()).containsKeys("BILL","BANKING");
        assertThat(view(statement).labelReasons()).containsKey("STATEMENT").doesNotContainKey("BILL");
        assertThat(view(investing).labelReasons()).containsKeys("INVESTING","SECURITY");
        assertThat(view(comment).labelReasons()).containsKeys("DOCUMENT_COMMENT","OLDER_DOCUMENT_COMMENT");
        assertThat(view(unrelated).labelReasons()).isEmpty();
        assertThat(view(spam).labelReasons()).isEmpty();
        assertThat(count("all","SHOPPING,RECEIPT","all",false)).isEqualTo(1);
        assertThat(count("all","SHOPPING,SECURITY","any",false)).isEqualTo(5);
        assertThat(count("all","SHOPPING","all",true)).isEqualTo(2);
        assertThat(count("inbox","SHOPPING","all",true)).isEqualTo(1);
        assertThat(count("all","GMAIL_LABEL_12,GMAIL_PROMOTIONS","all",false)).isEqualTo(1);
        assertThat(count("all","OLDER_DOCUMENT_COMMENT","all",false)).isEqualTo(1);

        var page=dashboard.page(account,filter("all","SHOPPING,RECEIPT","all",false));
        mockMvc.perform(get("/dashboard/api/groups/{group}",page.groups().getFirst().id()).param("scope","all")
                .param("labels","SHOPPING,RECEIPT").with(authenticatedAs(OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.recommendations[0].labelReasons.RECEIPT").exists());

        jdbc.update("UPDATE message_metadata SET classification_override='PERSONAL' WHERE dashboard_message_id=?",receipt);
        assertThat(count("all","SHOPPING,RECEIPT","all",false)).isZero();
        drain();
        assertThat(view(receipt).labels()).contains("SHOPPING","PERSONAL").doesNotContain("RECEIPT");
        UUID incoming=mail("new","notices@example.test","Bill – Provider","Account update","{INBOX}");
        drain();
        assertThat(view(incoming).labelReasons().get("BILL")).contains("sender name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
        verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @Test void storedCalendarUpdatesInvalidateEarlierPastEventMatches() throws Exception {
        UUID original=mail("old-event","calendar-notification@google.com","Calendar","Invitation: Example","{INBOX}");
        calendar("old-event","series","DTSTART:20200101T090000Z\r\nRRULE:FREQ=WEEKLY;COUNT=2\r\nSEQUENCE:1");
        UUID mixed=mail("mixed-event","calendar-notification@google.com","Calendar","Invitation: Example","{INBOX}");
        calendar("mixed-event","mixed","DTSTART:20200101T090000Z\r\nRRULE:FREQ=YEARLY;INTERVAL=8;COUNT=2");
        drain();
        assertThat(view(original).labelReasons()).containsKeys("CALENDAR_INVITE","PAST_EVENT");
        assertThat(view(mixed).labelReasons()).containsKey("CALENDAR_INVITE").doesNotContainKey("PAST_EVENT");
        assertThat(count("all","PAST_EVENT","all",false)).isEqualTo(1);

        mail("updated-event","calendar-notification@google.com","Calendar","Updated invitation: Example","{INBOX}");
        calendar("updated-event","series","DTSTART:20300101T090000Z\r\nSEQUENCE:2");
        assertThat(count("all","PAST_EVENT","all",false)).isZero();
        drain();
        assertThat(view(original).labelReasons()).containsKey("CALENDAR_INVITE").doesNotContainKey("PAST_EVENT");
        assertThat(count("all","PAST_EVENT","all",false)).isZero();
        verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @Test void gmailLabelNamesAreAccountScopedAndFetchedOnceForAnOpenDashboard() throws Exception {
        long other=userAccounts.createAccount("other-labels@example.test");
        when(gmailProvider.listFoldersOrLabels(any())).thenAnswer(call -> {
            com.tidymail.entity.UserAccount owner=call.getArgument(0);
            return List.of(new MailFolderOrLabel("Label_12",owner.getId()==other?"Other label":"Local label","user-label",0,0),
                    new MailFolderOrLabel("INBOX","Inbox","system-label",0,0));
        });
        for(int i=0;i<2;i++)
            mockMvc.perform(get("/dashboard/api/gmail-labels").with(authenticatedAs(OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.Label_12").value("Local label")).andExpect(jsonPath("$.INBOX").doesNotExist());
        mockMvc.perform(get("/dashboard/api/gmail-labels").with(authenticatedAs("other-labels@example.test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.Label_12").value("Other label"));
        verify(gmailProvider,times(2)).listFoldersOrLabels(any());
    }

    private UUID mail(String id,String address,String name,String subject,String labels) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                dashboard_unit_kind,dashboard_unit_key,from_display_name,subject_header,labels,label_evidence_known,received_at)
            VALUES(?,'google',?,?,?,'ADDRESS',?,?,?,?::text[],true,now()) RETURNING dashboard_message_id
            """,UUID.class,account,id,address,address,address,name,subject,labels);
    }

    private void calendar(String id,String uid,String properties) throws Exception {
        var result=new CalendarEvidenceParser().parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:"+uid+
            "\r\nORGANIZER:mailto:organizer@example.test\r\nDURATION:PT1H\r\n"+properties+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
        assertThat(result.state()).isEqualTo("AVAILABLE");
        var facts=Map.of("version",GmailEvidenceReader.VERSION,"structureState","AVAILABLE",
            "calendars",List.of(Map.of("state","AVAILABLE","partId","1","events",result.events())));
        jdbc.update("""
            INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts)
            VALUES(?,?,?,?,now(),?::jsonb)
            """,account,id,GmailEvidenceReader.VERSION,id,json.writeValueAsString(facts));
    }

    private RecommendationService.View view(UUID message) { return recommendations.read(account,List.of(message)).getFirst(); }
    private DashboardFilter filter(String scope,String labels,String match,boolean exclusions) {
        return DashboardFilter.from(Map.of("scope",scope,"labels",labels,"match",match,"excludeProtectedShopping",String.valueOf(exclusions)));
    }
    private long count(String scope,String labels,String match,boolean exclusions) {
        return ((Number) dashboard.page(account,filter(scope,labels,match,exclusions)).summary().get("messages")).longValue();
    }
    private void drain() {
        for(int batch=0;batch<20;batch++) {
            processing.runOnce();
            var status=processing.status(account);
            assertThat(status.error()).isNull();
            if(status.historyComplete() && status.queued()==0) return;
        }
        fail("Local processing did not settle");
    }
}
