package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.EmailSnippet;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class Phase2IdentificationJourneyTest extends PostgresJourneyTest {
    static final String OWNER="phase2@example.test";
    @MockBean GmailEvidenceReader reader;
    @MockBean JevClassifier classifier;
    @Autowired MessageEvidenceService evidence;
    @Autowired DashboardRepository dashboard;
    @Autowired ReviewedPatternService patterns;
    @Autowired DashboardGroupingService grouping;
    @Autowired ObjectMapper json;
    long account;
    @BeforeEach void setup(){account=userAccounts.createAccount(OWNER);reset(reader,classifier);}
    UUID mail(String id,String address,String subject,String name,String category) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
            from_display_name,dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,
            received_at,first_seen_at,last_synced_at,classification_override)
            VALUES(?,'google',?,?,?,?,'ADDRESS',?,?,ARRAY['INBOX'],true,now(),now(),now(),?) RETURNING dashboard_message_id
            """,UUID.class,account,id,address,address,name,address,subject,category);
    }
    DashboardFilter filter(String query,String category) {return new DashboardFilter("all",query,"","","UTC",0,category);}
    GmailEvidenceReader.Evidence calendar(String sequence,String start) {
        var facts=new CalendarEvidenceParser().parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:shared-event\r\nORGANIZER:mailto:spouse@example.test\r\nDTSTART:"+start+"\r\nDURATION:PT1H\r\nSEQUENCE:"+sequence+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").source("1","content-hash-"+sequence);
        return new GmailEvidenceReader.Evidence(GmailEvidenceReader.VERSION,EmailSnippet.observed("Invitation"),"AVAILABLE","PRESENT",List.of(),List.of(facts),1,100);
    }
    @Test void cachedInspectionReconcilesNewUpdatesWithoutReadingOldMessageAgainAndExcludesOtherAccounts() {
        UUID old=mail("old","spouse@gmail.com","Invitation","Spouse",null);
        UUID update=mail("new","spouse@gmail.com","Rescheduled","Spouse",null);
        when(reader.read(any(),eq("old"))).thenReturn(calendar("1","20200101T090000Z"));
        when(reader.read(any(),eq("new"))).thenReturn(calendar("2","20990101T090000Z"));
        assertThat(evidence.inspect(account,old).path("reconciledEvents").get(0).path("state").asText()).isEqualTo("OBSERVED_ENDED");
        evidence.inspect(account,update);
        var result=evidence.inspect(account,old).path("reconciledEvents").get(0);
        assertThat(result.path("state").asText()).isEqualTo("OBSERVED_UPCOMING");
        assertThat(result.path("historyComplete").asBoolean()).isFalse();
        assertThat(result.path("messages")).hasSize(2);
        long other=userAccounts.createAccount("other@example.test");
        assertThatThrownBy(()->evidence.inspect(other,old)).hasMessageContaining("unavailable");
        verify(reader,times(1)).read(any(),eq("old"));verify(reader,times(1)).read(any(),eq("new"));
        verifyNoInteractions(classifier,gmailProvider);
    }
    @Test void stableNamesReadAllReceivedEvidenceAndOtherFilterIncludesUncertainty() {
        mail("a","person@gmail.com","one","Alpha","OTHER");
        mail("b","person@gmail.com","two","Zulu","UNCERTAIN");
        assertThat(dashboard.page(account,filter("one","")).groups().getFirst().label()).isEqualTo("person@gmail.com");
        assertThat(dashboard.page(account,filter("two","")).groups().getFirst().label()).isEqualTo("person@gmail.com");
        var other=dashboard.page(account,filter("","OTHER"));
        assertThat(other.summary()).containsEntry("messages",2L);
        assertThat(dashboard.selection(account,filter("","OTHER"),List.of(other.groups().getFirst().id()),null)).hasSize(2);
        assertThat(dashboard.details(account,filter("","OTHER"),other.groups().getFirst().id(),0).messages()).allMatch(m->m.category().equals("OTHER"));
        assertThat(jdbc.queryForObject("SELECT classification_override FROM message_metadata WHERE provider_message_id='b'",String.class)).isEqualTo("UNCERTAIN");
    }
    @Test void reviewedReuseIsScopedExplicitInspectableAndInvalidatedByChangedEvidence() throws Exception {
        UUID source=mail("source","orders@amazon.com","Order 123456 confirmed","Amazon","RECEIPT");
        UUID match=mail("match","orders@amazon.com","Order 987654 confirmed","Amazon",null);
        mail("security","orders@amazon.com","Account security notice","Amazon","SECURITY");
        mail("security-text","orders@amazon.com","Security: Order 777777 confirmed","Amazon",null);
        mail("different-sender","other@amazon.com","Order 777777 confirmed","Amazon",null);
        mail("costco","orders@costco.com","Order 777777 confirmed","Costco",null);
        mail("nextdoor","orders@nextdoor.com","Order 777777 confirmed","Nextdoor",null);
        var preview=patterns.preview(account,24);
        assertThat(preview.matches()).extracting(ReviewedPatternService.Match::id).containsExactly(match);
        assertThat(preview.matches().getFirst().sourceId()).isEqualTo(source);
        assertThat(preview.matches().getFirst().importance()).isEqualTo("NOT_INFERRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_pattern_labels",Long.class)).isZero();
        mockMvc.perform(post("/dashboard/api/patterns/{id}/apply",preview.id()).with(authenticatedAs(OWNER))
            .contentType("application/json").content(json.writeValueAsString(new ReviewedPatternService.Apply(List.of(match)))))
            .andExpect(status().isForbidden());
        long other=userAccounts.createAccount("other@example.test");
        assertThatThrownBy(()->patterns.apply(other,preview.id(),List.of(match))).hasMessageContaining("expired");
        assertThat(patterns.apply(account,preview.id(),List.of(match))).isEqualTo(1);
        var group=dashboard.page(account,filter("","")).groups().stream().filter(g->g.label().equals("amazon.com")).findFirst().orElseThrow();
        assertThat(dashboard.details(account,filter("",""),group.id(),0).messages().stream().filter(m->m.id().equals(match)).findFirst().orElseThrow().reviewedPattern()).isEqualTo("RECEIPT");
        assertThat(jdbc.queryForObject("SELECT classification_override FROM message_metadata WHERE dashboard_message_id=?",String.class,match)).isNull();
        var beforeCalendar=patterns.preview(account,24);
        jdbc.update("""
            INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts)
            VALUES(?,'source',?,'hash',now(),'{"calendars":[{"state":"AVAILABLE","events":[]}]}')
            """,account,GmailEvidenceReader.VERSION);
        assertThatThrownBy(()->patterns.apply(account,beforeCalendar.id(),List.of(match))).hasMessageContaining("changed");
        assertThat(dashboard.details(account,filter("",""),group.id(),0).messages().stream().filter(m->m.id().equals(match)).findFirst().orElseThrow().reviewedPattern()).isNull();
        jdbc.update("DELETE FROM message_evidence WHERE account_id=? AND provider_message_id='source'",account);
        jdbc.update("UPDATE message_metadata SET subject_header='Security alert' WHERE dashboard_message_id=?",match);
        assertThat(dashboard.details(account,filter("",""),group.id(),0).messages().stream().filter(m->m.id().equals(match)).findFirst().orElseThrow().reviewedPattern()).isNull();
        verifyNoInteractions(reader,classifier,gmailProvider);
    }
    @Test void changedPreviewAndConflictingReviewedExamplesCannotApply() {
        mail("source","digest@quora.com","Weekly digest","Quora","NEWSLETTER");
        UUID match=mail("match","digest@quora.com","Weekly digest","Quora",null);
        var preview=patterns.preview(account,24);
        jdbc.update("UPDATE message_metadata SET snippet_text='New security notice' WHERE dashboard_message_id=?",match);
        assertThatThrownBy(()->patterns.apply(account,preview.id(),List.of(match))).hasMessageContaining("changed");
        mail("conflicting-source","digest@quora.com","Weekly digest","Quora","MARKETING");
        assertThat(patterns.preview(account,24).matches()).isEmpty();
    }
    @Test void allDayUpdatesStayDateOnlyAndOlderRelatedEvidenceMustBeExplicitlyRefreshed() throws Exception {
        UUID master=mail("master","spouse@gmail.com","All-day plan","Spouse","PERSONAL");
        UUID update=mail("update","spouse@gmail.com","Updated plan","Spouse","PERSONAL");
        var parser=new CalendarEvidenceParser();
        String header="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:all-day-series\r\nORGANIZER:mailto:spouse@example.test\r\n";
        String footer="\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        var masterFacts=parser.parse(header+"DTSTART;VALUE=DATE:20260930\r\nRRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=2\r\nSEQUENCE:1"+footer).source("1","master-hash");
        var updatedFacts=parser.parse(header+"RECURRENCE-ID;VALUE=DATE:20261030\r\nDTSTART;VALUE=DATE:20261107\r\nDURATION:P2D\r\nSEQUENCE:2"+footer).source("2","update-hash");
        var original=new GmailEvidenceReader.Evidence(GmailEvidenceReader.VERSION,EmailSnippet.observed("All-day plan"),"AVAILABLE","PRESENT",List.of(),List.of(masterFacts),1,100);
        var changed=new GmailEvidenceReader.Evidence(GmailEvidenceReader.VERSION,EmailSnippet.observed("Updated plan"),"AVAILABLE","PRESENT",List.of(),List.of(updatedFacts),1,100);
        when(reader.read(any(),eq("master"))).thenReturn(original);
        when(reader.read(any(),eq("update"))).thenReturn(changed);
        assertThat(evidence.inspect(account,master).path("reconciledEvents").get(0).path("latestObservedEndDate").asText()).isEqualTo("2026-10-31");
        var old=json.valueToTree(changed);
        ((com.fasterxml.jackson.databind.node.ObjectNode)old).put("version","mime-ics-v2");
        jdbc.update("INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts) VALUES(?,'update','mime-ics-v2','old',now(),CAST(? AS jsonb))",account,json.writeValueAsString(old));
        assertThat(evidence.inspect(account,master).path("reconciledEvents").get(0).path("state").asText()).isEqualTo("UNKNOWN");
        evidence.inspect(account,update); // Explicitly refresh this one old evidence record.
        var result=evidence.inspect(account,master).path("reconciledEvents").get(0);
        assertThat(result.path("state").asText()).isEqualTo("OBSERVED_DATE_RANGE");
        assertThat(result.path("latestObservedEndDate").asText()).isEqualTo("2026-11-09");
        assertThat(result.path("latestObservedEnd").isNull()).isTrue();
        assertThat(result.path("sources")).hasSize(2);
        assertThat(result.path("historyComplete").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT classification_override FROM message_metadata WHERE dashboard_message_id=?",String.class,master)).isEqualTo("PERSONAL");
        verify(reader,times(1)).read(any(),eq("master"));verify(reader,times(1)).read(any(),eq("update"));
        verifyNoInteractions(classifier,gmailProvider);
    }
    @Test void reviewExplainsReplyAndChangedTemplateExclusionsAndOldPolicyLabelsDisappear() {
        mail("reviewed","digest@quora.com","Weekly summary","Quora","NEWSLETTER");
        UUID ordinary=mail("ordinary","digest@quora.com","Weekly summary","Quora",null);
        UUID reply=mail("reply","digest@quora.com","Weekly summary","Quora",null);
        UUID changed=mail("changed","digest@quora.com","A changed subject","Quora",null);
        jdbc.update("UPDATE message_metadata SET in_reply_to_values=ARRAY['<conversation@example.test>'] WHERE dashboard_message_id=?",reply);
        var preview=patterns.preview(account,24);
        assertThat(preview.matches()).extracting(ReviewedPatternService.Match::id).containsExactly(ordinary);
        assertThat(preview.nearMisses().stream().filter(m->m.id().equals(reply)).findFirst().orElseThrow().reviewCode()).isEqualTo("CORRESPONDENCE_CONTEXT");
        assertThat(preview.nearMisses().stream().filter(m->m.id().equals(changed)).findFirst().orElseThrow().reviewCode()).isEqualTo("UNFAMILIAR_TEMPLATE");
        patterns.apply(account,preview.id(),List.of(ordinary));
        var group=dashboard.page(account,filter("","")).groups().getFirst();
        assertThat(dashboard.details(account,filter("",""),group.id(),0).messages().stream().filter(m->m.id().equals(ordinary)).findFirst().orElseThrow().reviewedPattern()).isEqualTo("NEWSLETTER");
        jdbc.update("UPDATE dashboard_pattern_reviews SET version='reviewed-subject-v2' WHERE id=?",preview.id());
        assertThat(dashboard.details(account,filter("",""),group.id(),0).messages().stream().filter(m->m.id().equals(ordinary)).findFirst().orElseThrow().reviewedPattern()).isNull();
        verifyNoInteractions(reader,classifier,gmailProvider);
    }
    @Test void personalCompanyAndPersonalMailboxIdentitiesSurviveMetadataRefreshAndSavedMerges() {
        UUID work=mail("work","alex@gene.com","Family invitation","Alex","PERSONAL");
        mail("coworker","taylor@gene.com","Team update","Taylor",null);
        mail("home","alex@gmail.com","Family update","Alex","PERSONAL");
        var page=dashboard.page(account,filter("",""));
        assertThat(page.groups()).hasSize(3);
        jdbc.update("UPDATE message_metadata SET raw_from_values=ARRAY['Alex <alex@gene.com>'] WHERE dashboard_message_id=?",work);
        assertThat(dashboard.page(account,filter("","")).groups()).hasSize(3);
        var unit=jdbc.queryForObject("SELECT public_id FROM sender_units WHERE account_id=? AND canonical_key='alex@gene.com'",UUID.class,account);
        var home=jdbc.queryForObject("SELECT public_id FROM sender_units WHERE account_id=? AND canonical_key='alex@gmail.com'",UUID.class,account);
        grouping.correct(account,new DashboardGroupingService.Correction("MERGE",List.of(unit,home),"Explicit Alex link",null,dashboard.revision(account)),true);
        assertThat(dashboard.page(account,filter("","")).groups()).hasSize(2);
        jdbc.update("UPDATE message_metadata SET classification_override='PERSONAL' WHERE dashboard_message_id=?",work);
        grouping.rebuild(account);
        assertThat(dashboard.page(account,filter("","")).groups()).hasSize(2);
        UUID correction=jdbc.queryForObject("SELECT public_id FROM sender_group_corrections WHERE account_id=? AND kind='MERGE' AND active",UUID.class,account);
        grouping.correct(account,new DashboardGroupingService.Correction("RESET",List.of(),null,correction,dashboard.revision(account)),true);
        assertThat(dashboard.page(account,filter("","")).groups()).hasSize(3);
        verifyNoInteractions(reader,classifier,gmailProvider);
    }
    @Test void marketplaceSellerRelaysNeverJoinAutomatedAmazonOrInheritItsPatterns() {
        mail("amazon","orders@amazon.com","Order 123456 confirmed","Amazon","NOTIFICATION");
        mail("seller-a","alias-a@marketplace.amazon.com","Order 123457 confirmed","Seller",null);
        mail("seller-b","alias-b@marketplace.amazon.com","Order 123458 confirmed","Seller",null);
        assertThat(dashboard.page(account,filter("","")).groups()).hasSize(3);
        jdbc.update("UPDATE message_metadata SET classification_kind='AUTOMATED' WHERE provider_message_id LIKE 'seller-%'");
        assertThat(dashboard.page(account,filter("","")).groups()).hasSize(3);
        assertThat(patterns.preview(account,24).matches()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT dashboard_reviewed_organization('alias-a@marketplace.amazon.com')",String.class)).isNull();
        verifyNoInteractions(reader,classifier,gmailProvider);
    }
}
