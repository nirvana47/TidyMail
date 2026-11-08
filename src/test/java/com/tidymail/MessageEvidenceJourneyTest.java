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
    @Autowired CorrespondentHistory history;
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
        UUID large=mail("large","a@quora.com","{INBOX}",1000001L,"ADDRESS","a@quora.com");
        UUID archived=mail("archived","b@quora.com","{}",9000000L,"ADDRESS","b@quora.com");
        mail("boundary","a@quora.com","{INBOX}",1000000L,"ADDRESS","a@quora.com");
        mail("below","a@quora.com","{INBOX}",999999L,"ADDRESS","a@quora.com");
        mail("unknown","a@quora.com","{INBOX}",null,"ADDRESS","a@quora.com");
        mail("trash","a@quora.com","{TRASH}",99000000L,"ADDRESS","a@quora.com");
        UUID sent=mail("sent","sent@example.test","{SENT}",2000001L,"ADDRESS","sent@example.test");
        var page=dashboard.page(account,filter("large"));assertThat(page.summary()).containsEntry("messages",3L);
        assertThat(dashboard.selection(account,filter("large"),page.groups().stream().map(DashboardRepository.Group::id).toList(),null)).extracting(DashboardRepository.Selected::id).containsExactlyInAnyOrder(large,archived,sent);
        assertThat(dashboard.page(account,filter("")).summary()).containsEntry("messages",5L);
        assertThat(dashboard.details(account,filter("large"),page.groups().stream().filter(g->"sent@example.test".equals(g.address())).findFirst().orElseThrow().id(),0).messages().getFirst().sent()).isTrue();
        var preview=previews.create(account,new DashboardPreviewService.Request(filter("large"),List.of(large,archived,sent,large),"TRASH",page.revision()));
        assertThat(preview.selected()).isEqualTo(3);assertThat(preview.knownBytes()).isEqualTo(12000002);assertThat(preview.unknownSizes()).isZero();
        assertThat(previews.targets(account,preview.id(),0).rows()).allMatch(r->r.get("size_estimate")!=null);
        var job=jobs.confirm(account,preview.id());
        assertThat(jdbc.queryForObject("SELECT snapshot->'filter'->>'suggestion' FROM cleanup_jobs WHERE public_id=?",String.class,job.id())).isEqualTo("large");
        var claim=jobs.claim();
        var verified=new DashboardJobService.Claim(claim.job(),claim.account(),claim.worker(),claim.generation(),claim.filter(),claim.targets(),false,claim.action(),"VERIFIED");
        var observations=new HashMap<String,MailMessage>();
        for(var target:claim.targets()) observations.put(target.providerId(),new MailMessage(target.providerId(),"thread","google",Instant.now(),Map.of(),target.messageId().equals(sent)?Set.of("SENT"):target.messageId().equals(large)?Set.of("INBOX"):Set.of()));
        assertThat(jobs.dispatch(verified,observations).targets()).extracting(DashboardJobService.Target::messageId).containsExactlyInAnyOrder(large,archived,sent);
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
        UUID outgoing=mail("outgoing",OWNER,"{SENT}",100L,"ADDRESS",OWNER);
        jdbc.update("UPDATE message_metadata SET to_values=ARRAY['person@gmail.com'] WHERE dashboard_message_id=?",outgoing);
        history.processBatch(200);
        var details=dashboard.details(account,filter(""),dashboard.page(account,filter("")).groups().getFirst().id(),0);
        assertThat(details.messages().getFirst().contact()).isTrue();
        assertThat(details.messages().getFirst().calendarInvite()).isTrue();
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
        var unit=dashboard.grouping(account,page.groups().getFirst().id()).units().getFirst().id();
        grouping.correct(account,new DashboardGroupingService.Correction("SEPARATE",List.of(unit),null,null,page.revision()),true);
        mail("another","digest@quora.com","{INBOX}",1L,"LIST","one.quora");
        assertThat(dashboard.page(account,filter("")).groups()).hasSize(2);
        assertThat(details.messages()).extracting(DashboardRepository.Message::id).contains(routine,security);
    }
    @Test void scopedReadPermissionIsCheckedOnlyAtTheActualUncachedRead() {
        UUID id=mail("scoped","person@gmail.com","{INBOX}",100L,"ADDRESS","person@gmail.com");
        var reservations=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->evidence.inspectPresence(account,id,()->{reservations.incrementAndGet();return false;}))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(reader);assertThat(reservations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_evidence",Long.class)).isZero();

        when(reader.readPresence(any(),eq("scoped"))).thenReturn(new GmailEvidenceReader.Evidence(
            GmailEvidenceReader.PRESENCE_VERSION,EmailSnippet.observed(null),"AVAILABLE","ABSENT",List.of(),List.of(),0,100));
        evidence.inspectPresence(account,id,()->{reservations.incrementAndGet();return true;});
        evidence.inspectPresence(account,id,()->{throw new AssertionError("Cached evidence must not reserve a read");});
        assertThat(reservations.get()).isEqualTo(2);verify(reader,times(1)).readPresence(any(),eq("scoped"));

        // If a previously cached observation disappears, a subsequent read must reserve again.
        jdbc.update("DELETE FROM message_evidence WHERE account_id=?",account);
        assertThatThrownBy(()->evidence.inspectPresence(account,id,()->false))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(reader,times(1)).readPresence(any(),eq("scoped"));
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
