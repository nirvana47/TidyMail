package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import com.tidymail.service.MessageRecommendation.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class RecommendationDashboardJourneyTest extends PostgresJourneyTest {
    static final String OWNER="recommendation-ui@example.test";
    @MockBean JevClassifier classifier;
    @MockBean GmailEvidenceReader reader;
    @Autowired RecommendationService recommendations;
    @Autowired LabelingPipeline labeling;
    @Autowired RecommendationProcessingService processing;
    @Autowired DashboardRepository dashboard;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    @Autowired ObjectMapper json;
    long account;
    @BeforeEach void setup() {account=userAccounts.createAccount(OWNER);reset(classifier,reader);}
    UUID mail(String id,String labels,long bytes,String purpose) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
              dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,size_estimate,received_at,classification_override)
            VALUES(?,'google',?,'person@example.test','person@example.test','ADDRESS','person@example.test',?,?::text[],true,?,now(),?) RETURNING dashboard_message_id
            """,UUID.class,account,id,"Email "+id,labels,bytes,purpose);
    }
    DashboardFilter filter(String action,String importance,String label) {
        return new DashboardFilter("all","","","","UTC",0,"","messages","",action,importance,label);
    }
    long count(DashboardFilter filter) {return ((Number)dashboard.page(account,filter).summary().get("messages")).longValue();}
    void drain() {
        for(int i=0;i<30;i++) {processing.runOnce();if(processing.status(account).historyComplete() && processing.status(account).queued()==0)return;}
        fail("Local history build did not finish");
    }
    @Test void allIndexedMailIncludesUnassessedAndExcludedFoldersWithoutExpandingCleanup() throws Exception {
        var ids=new ArrayList<UUID>();
        for(int i=0;i<12;i++)ids.add(mail("indexed-inbox-"+i,"{INBOX}",1000,null));
        ids.add(mail("indexed-archive","{}",1000,null));
        for(String label:List.of("SENT","SPAM","TRASH","DRAFT"))ids.add(mail("indexed-"+label,"{"+label+"}",1000,null));
        UUID unknown=mail("indexed-unknown","{}",1000,null);ids.add(unknown);
        jdbc.update("UPDATE message_metadata SET label_evidence_known=false WHERE dashboard_message_id=?",unknown);
        var indexed=new DashboardFilter("indexed","","","","UTC",0);
        var before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM message_metadata m",String.class);
        assertThat(count(filter("","",""))).isEqualTo(13);
        var page=dashboard.page(account,indexed);
        assertThat(page.summary()).containsEntry("messages",18L);
        assertThat(page.coverage()).containsEntry("indexed",18L);
        var group=page.groups().getFirst().id();
        assertThat(dashboard.details(account,indexed,group,0).messages()).hasSize(10);
        assertThat(dashboard.details(account,indexed,group,1).messages()).hasSize(8);
        assertThat(dashboard.selection(account,indexed,List.of(group),null)).extracting(DashboardRepository.Selected::id)
            .containsExactlyInAnyOrderElementsOf(ids);
        mockMvc.perform(get("/dashboard/api/page").param("scope","indexed").param("recommendation","PENDING")
            .with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.summary.messages").value(18));
        mockMvc.perform(get("/dashboard/api/page").param("scope","indexed").param("category","UNCLASSIFIED")
            .with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.summary.messages").value(18));
        userAccounts.createAccount("indexed-other@example.test");
        mockMvc.perform(get("/dashboard/api/page").param("scope","indexed").with(authenticatedAs("indexed-other@example.test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.messages").value(0));
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM message_metadata m",String.class)).isEqualTo(before);
        var preview=previews.create(account,new DashboardPreviewService.Request(indexed,ids,"TRASH",page.revision()));
        assertThat(preview.selected()).isEqualTo(18);
        assertThat(preview.effective()).isEqualTo(13);
        assertThat(preview.skipped()).isEqualTo(5);
        recommendations.recompute(account,List.of(ids.getFirst()));
        assertThat(count(indexed)).isEqualTo(18);
        mockMvc.perform(get("/dashboard/api/page").param("scope","indexed").param("recommendation","PENDING")
            .with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.summary.messages").value(17));
        jdbc.update("UPDATE message_metadata SET size_estimate=2000 WHERE dashboard_message_id=?",ids.getFirst());
        mockMvc.perform(get("/dashboard/api/page").param("scope","indexed").param("recommendation","STALE")
            .with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.summary.messages").value(1));
        assertThat(count(indexed)).isEqualTo(18);
        verifyNoInteractions(classifier,reader,gmailProvider,outlookProvider);
    }

    @Test void browsingIsReadOnlyAndOptInProcessingSupportsPagedFilteredReviewAndExactBytes() throws Exception {
        for(int i=0;i<13;i++)mail("personal-"+i,"{INBOX}",1000,"PERSONAL");
        mail("unknown","{INBOX}",500,null);
        var before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM message_metadata m",String.class);
        mockMvc.perform(get("/dashboard/api/recommendations").with(authenticatedAs(OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.assessed").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_processing",Long.class)).isZero();
        assertThat(count(filter("PENDING","",""))).isEqualTo(14);
        mockMvc.perform(post("/dashboard/api/recommendations/processing").with(authenticatedAs(OWNER)).with(csrfToken())
            .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk());
        drain();
        assertThat(count(filter("KEEP_IN_INBOX","UNKNOWN","HUMAN_CORRESPONDENCE"))).isEqualTo(13);
        var f=filter("KEEP_IN_INBOX","","");var page=dashboard.page(account,f);var group=page.groups().getFirst().id();
        mockMvc.perform(get("/dashboard/api/groups/{group}",group).param("scope","all").param("recommendation","KEEP_IN_INBOX")
            .with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(10))
            .andExpect(jsonPath("$.recommendations.length()").value(10)).andExpect(jsonPath("$.recommendations[0].freshness").value("CURRENT"))
            .andExpect(jsonPath("$.recommendations[0].result.reason").value("PROTECTED_CONTENT"));
        assertThat(dashboard.details(account,f,group,1).messages()).hasSize(3);
        var ids=dashboard.selection(account,f,List.of(group),null).stream().map(DashboardRepository.Selected::id).toList();
        var preview=previews.create(account,new DashboardPreviewService.Request(f,ids,"ARCHIVE",page.revision()));
        assertThat(preview.selected()).isEqualTo(13);assertThat(preview.knownBytes()).isEqualTo(13000);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM message_metadata m",String.class)).isEqualTo(before);
        verifyNoInteractions(classifier,reader,gmailProvider);
    }
    @Test void evidenceChangesImmediatelyRemoveFilteredRecommendationsAndInvalidateAReviewedPreview() {
        UUID id=mail("changing","{INBOX}",2000,"PERSONAL");recommendations.recompute(account,List.of(id));
        var f=filter("KEEP_IN_INBOX","","");var page=dashboard.page(account,f);
        var preview=previews.create(account,new DashboardPreviewService.Request(f,List.of(id),"ARCHIVE",page.revision()));
        jdbc.update("UPDATE message_metadata SET size_estimate=3000 WHERE dashboard_message_id=?",id);
        assertThat(count(f)).isZero();assertThat(count(filter("STALE","","") )).isEqualTo(1);
        assertThatThrownBy(()->jobs.confirm(account,preview.id())).hasMessageContaining("Recommendations or labels changed");
        recommendations.recompute(account,List.of(id));assertThat(count(f)).isEqualTo(1);
        // A second update while already dirty must still change the compact source revision.
        jdbc.update("UPDATE message_metadata SET size_estimate=4000 WHERE dashboard_message_id=?",id);
        long version=jdbc.queryForObject("SELECT revision FROM recommendation_message_versions WHERE message_id=?",Long.class,id);
        jdbc.update("UPDATE message_metadata SET size_estimate=5000 WHERE dashboard_message_id=?",id);
        assertThat(jdbc.queryForObject("SELECT revision FROM recommendation_message_versions WHERE message_id=?",Long.class,id)).isGreaterThan(version);
        verifyNoInteractions(classifier,reader,gmailProvider);
    }
    @Test void correctionsArePerMessageAccountScopedCsrfProtectedAndRejectLostUpdates() throws Exception {
        UUID id=mail("correct","{INBOX}",1000,null),other=mail("other","{INBOX}",1000,null);
        String body="{\"action\":\"REVIEW\",\"importance\":\"IMPORTANT\",\"revision\":0}";
        mockMvc.perform(post("/dashboard/api/messages/{id}/recommendation",id).with(authenticatedAs(OWNER))
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
        userAccounts.createAccount("different@example.test");
        mockMvc.perform(post("/dashboard/api/messages/{id}/recommendation",id).with(authenticatedAs("different@example.test")).with(csrfToken())
            .contentType("application/json").content(body)).andExpect(status().isNotFound());
        mockMvc.perform(post("/dashboard/api/messages/{id}/recommendation",id).with(authenticatedAs(OWNER)).with(csrfToken())
            .contentType("application/json").content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.result.action").value("REVIEW")).andExpect(jsonPath("$.result.importance").value("IMPORTANT"));
        assertThat(count(filter("REVIEW","IMPORTANT",""))).isEqualTo(1);
        assertThat(recommendations.read(account,List.of(other)).getFirst().freshness()).isEqualTo(RecommendationService.Freshness.PENDING);
        mockMvc.perform(post("/dashboard/api/messages/{id}/recommendation",id).with(authenticatedAs(OWNER)).with(csrfToken())
            .contentType("application/json").content(body)).andExpect(status().isConflict());
        long version=recommendations.read(account,List.of(id)).getFirst().correction().revision();
        mockMvc.perform(post("/dashboard/api/messages/{id}/recommendation",id).with(authenticatedAs(OWNER)).with(csrfToken())
            .contentType("application/json").content("{\"action\":null,\"importance\":null,\"revision\":"+version+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.importance").value("UNKNOWN"));
        assertThat(jdbc.queryForObject("SELECT labels FROM message_metadata WHERE dashboard_message_id=?",String.class,id)).contains("INBOX");
        verifyNoInteractions(classifier,reader,gmailProvider);
    }
    @Test void pauseResumeAndExpiredWorkerLeaseRetainDurableProgress() {
        for(int i=0;i<230;i++)mail("history-"+i,"{INBOX}",100,null);
        processing.enable(account,true);assertThat(processing.runOnce()).isTrue();
        long assessed=processing.status(account).assessed();assertThat(assessed).isBetween(1L,100L);
        processing.enable(account,false);assertThat(processing.runOnce()).isFalse();assertThat(processing.status(account).assessed()).isEqualTo(assessed);
        processing.enable(account,true);
        jdbc.update("UPDATE recommendation_processing SET lease_token=?,lease_until=now()+interval '1 minute' WHERE account_id=?",UUID.randomUUID(),account);
        var restarted=new RecommendationProcessingService(jdbc,recommendations,labeling);assertThat(restarted.runOnce()).isFalse();
        jdbc.update("UPDATE recommendation_processing SET lease_until=now()-interval '1 second' WHERE account_id=?",account);
        assertThat(restarted.runOnce()).isTrue();drain();assertThat(processing.status(account).assessed()).isEqualTo(230);
        assertThat(processing.status(account).error()).isNull();
        var failedEngine=mock(RecommendationService.class);
        when(failedEngine.processBatch(account,100)).thenThrow(new IllegalStateException("Synthetic local failure"));
        assertThat(new RecommendationProcessingService(jdbc,failedEngine,labeling).runOnce()).isTrue();
        assertThat(processing.status(account).enabled()).isFalse();assertThat(processing.status(account).error()).isEqualTo("REFRESH_FAILED");
        processing.enable(account,true);assertThat(processing.status(account).error()).isNull();assertThat(processing.runOnce()).isTrue();
        verifyNoInteractions(classifier,reader,gmailProvider);
    }
}
