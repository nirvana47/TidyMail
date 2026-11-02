package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.*;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.repository.*;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class MessageEvidenceJourneyTest extends PostgresJourneyTest {
    static final String OWNER="evidence@example.test";
    @MockBean GmailEvidenceReader reader;
    @MockBean JevClassifier classifier;
    @Autowired DashboardRepository dashboard;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    @Autowired MessageEvidenceService evidence;
    @Autowired MessageMetadataSyncRepository metadata;
    @Autowired MessageMetadataMapper mapper;
    @Autowired DashboardGroupingService grouping;
    long account;
    @BeforeEach void setup(){account=userAccounts.createAccount(OWNER);reset(reader,classifier);}
    UUID mail(String id,String address,String labels,Long size,String kind,String unit) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,from_display_name,
            dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,size_estimate,received_at,first_seen_at,last_synced_at)
            VALUES(?,'google',?,?,?,'Received name',?,?,'Subject',CAST(? AS text[]),true,?,now(),now(),now()) RETURNING dashboard_message_id
            """,UUID.class,account,id,address,address,kind,unit,labels,size);
    }
    DashboardFilter filter(String suggestion) {return new DashboardFilter("all","","","","UTC",0,"","messages",suggestion);}
    @Test void largeSuggestionsUseWholeMessageSizeAndExactFrozenSelectionBytes() {
        UUID large=mail("large","a@quora.com","{INBOX}",5000001L,"ADDRESS","a@quora.com");
        UUID archived=mail("archived","b@quora.com","{}",9000000L,"ADDRESS","b@quora.com");
        mail("boundary","a@quora.com","{INBOX}",5000000L,"ADDRESS","a@quora.com");
        mail("below","a@quora.com","{INBOX}",4999999L,"ADDRESS","a@quora.com");
        mail("unknown","a@quora.com","{INBOX}",null,"ADDRESS","a@quora.com");
        mail("trash","a@quora.com","{TRASH}",99000000L,"ADDRESS","a@quora.com");
        mail("sent","a@quora.com","{SENT}",99000000L,"ADDRESS","a@quora.com");
        var page=dashboard.page(account,filter("large"));assertThat(page.summary()).containsEntry("messages",2L);
        var group=page.groups().getFirst().id();
        assertThat(dashboard.selection(account,filter("large"),List.of(group),null)).extracting(DashboardRepository.Selected::id).containsExactlyInAnyOrder(large,archived);
        var preview=previews.create(account,new DashboardPreviewService.Request(filter("large"),List.of(large,archived,large),"TRASH",page.revision()));
        assertThat(preview.selected()).isEqualTo(2);assertThat(preview.knownBytes()).isEqualTo(14000001);assertThat(preview.unknownSizes()).isZero();
        assertThat(previews.targets(account,preview.id(),0).rows()).allMatch(r->r.get("size_estimate")!=null);
        var job=jobs.confirm(account,preview.id());
        assertThat(jdbc.queryForObject("SELECT snapshot->'filter'->>'suggestion' FROM cleanup_jobs WHERE public_id=?",String.class,job.id())).isEqualTo("large");
        verifyNoInteractions(reader,classifier,gmailProvider);
    }
    @Test void checkingOneOwnedEmailIsReadOnlyCachedAndSurvivesMetadataSync() throws Exception {
        UUID id=mail("one","person@gmail.com","{INBOX}",6000000L,"ADDRESS","person@gmail.com");
        when(reader.read(any(),eq("one"))).thenReturn(new GmailEvidenceReader.Evidence(GmailEvidenceReader.VERSION,EmailSnippet.observed("Private preview"),"AVAILABLE","PRESENT",List.of(new GmailEvidenceReader.Part("1","text/calendar","invite.ics",123L,false,true)),List.of(),1,100));
        mockMvc.perform(post("/dashboard/api/messages/{id}/evidence",id).with(authenticatedAs(OWNER))).andExpect(status().isForbidden());
        long other=userAccounts.createAccount("other@example.test");
        assertThatThrownBy(()->evidence.inspect(other,id)).hasMessageContaining("unavailable");verifyNoInteractions(reader);
        mockMvc.perform(post("/dashboard/api/messages/{id}/evidence",id).with(authenticatedAs(OWNER)).with(csrfToken())).andExpect(status().isOk()).andExpect(jsonPath("$.snippet.text").value("Private preview"));
        evidence.inspect(account,id);verify(reader,times(1)).read(any(),eq("one"));
        var message=new MailMessage("one","thread","google",Instant.now(),Map.of("From",List.of("person@gmail.com")),Set.of("INBOX"));
        metadata.upsertMetadata(mapper.apply(account,message,null,Instant.now()));
        var row=jdbc.queryForMap("SELECT snippet_text,attachment_state,attachment_source FROM message_metadata WHERE account_id=? AND provider_message_id='one'",account);
        assertThat(row).containsEntry("snippet_text","Private preview").containsEntry("attachment_state","PRESENT").containsEntry("attachment_source","MIME_STRUCTURE_V1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_evidence WHERE account_id=?",Long.class,account)).isEqualTo(1);
        verifyNoInteractions(classifier,gmailProvider);
    }
    @Test void reviewedOrganizationMergesListVariantsButPreservesCorrectionsAndSecurityTypes() {
        UUID routine=mail("routine","digest@quora.com","{INBOX}",1L,"LIST","one.quora");
        UUID security=mail("security","access@quora.com","{INBOX}",2L,"LIST","two.quora");
        jdbc.update("UPDATE message_metadata SET classification_override=CASE WHEN dashboard_message_id=? THEN 'SECURITY' ELSE 'NEWSLETTER' END",security);
        var page=dashboard.page(account,filter(""));assertThat(page.groups()).hasSize(1);
        var details=dashboard.details(account,filter(""),page.groups().getFirst().id(),0);
        assertThat(details.messages()).extracting(DashboardRepository.Message::category).containsExactlyInAnyOrder("SECURITY","NEWSLETTER");
        var unit=details.units().getFirst().id();
        grouping.correct(account,new DashboardGroupingService.Correction("SEPARATE",List.of(unit),null,null,page.revision()),true);
        mail("another","digest@quora.com","{INBOX}",1L,"LIST","one.quora");
        assertThat(dashboard.page(account,filter("")).groups()).hasSize(2);
        assertThat(details.messages()).extracting(DashboardRepository.Message::id).contains(routine,security);
    }
    @Test void previewReportsUnknownSizesWithoutCountingThemAsZeroCoverage() {
        UUID a=mail("a","a@gmail.com","{INBOX}",null,"ADDRESS","a@gmail.com");
        var p=previews.create(account,new DashboardPreviewService.Request(filter(""),List.of(a),"TRASH",dashboard.revision(account)));
        assertThat(p.knownBytes()).isZero();assertThat(p.unknownSizes()).isEqualTo(1);
    }
    @Test void personalCorrespondenceAtAReviewedCompanyKeepsItsOwnIdentity() {
        UUID person=mail("person","employee@amazon.com","{INBOX}",1L,"ADDRESS","employee@amazon.com");
        mail("order","orders@amazon.com","{INBOX}",1L,"ADDRESS","orders@amazon.com");
        jdbc.update("UPDATE message_metadata SET classification_kind='PERSON' WHERE dashboard_message_id=?",person);
        assertThat(dashboard.page(account,filter("")).groups()).hasSize(2);
        jdbc.update("UPDATE message_metadata SET classification_override='PERSONAL' WHERE dashboard_message_id=?",person);
        mail("followup","employee@amazon.com","{INBOX}",1L,"ADDRESS","employee@amazon.com");
        assertThat(dashboard.page(account,filter("")).groups()).hasSize(2);
    }
}
