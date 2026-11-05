package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tidymail.service.*;
import com.tidymail.mail.EmailSnippet;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.support.PostgresJourneyTest;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class LabelingPipelineJourneyTest extends PostgresJourneyTest {
    @Autowired LabelingPipeline pipeline;
    @Autowired RecommendationService recommendations;
    @Autowired RecommendationProcessingService processing;
    @Autowired DashboardRepository dashboard;
    @Autowired ObjectMapper json;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    @Autowired UserDataDeletionService deletion;
    @Autowired LabelingReviewService review;
    @Autowired com.tidymail.job.LabelingWorker labelingWorker;
    @MockBean GmailEvidenceReader reader;
    @MockBean JevClassifier jev;
    long account;
    @BeforeEach void setup(){
        reset(reader,jev);account=userAccounts.createAccount("labeling@example.test");
        jdbc.update("UPDATE classification_budget SET reported=0,checkpoint=1,labeling_reported=0,labeling_checkpoint=0.20,owner_account=?,generation=0 WHERE id=1",account);
        when(jev.configured()).thenReturn(true);
        when(jev.recommendationPricing()).thenReturn(json.createObjectNode().put("verified_fixture",true));
    }
    UUID mail(String name,String subject,String labels){return jdbc.queryForObject("""
        INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,from_display_name,
          dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,size_estimate,received_at)
        VALUES(?,'google',?,'sender@example.test','sender@example.test','Sender','ADDRESS','sender@example.test',?,?::text[],true,2000001,now())
        RETURNING dashboard_message_id
        """,UUID.class,account,name,subject,labels);}
    LabelingPipeline.Prepared prepare(UUID id,String kind){return pipeline.prepare(account,List.of(id),kind,Variant.HEADERS,kind.equals("MIME")?BigDecimal.ZERO:new BigDecimal("0.20"));}
    void start(LabelingPipeline.Prepared scope){pipeline.authorize(account,scope.id(),scope.hash());}
    String state(UUID scope){return jdbc.queryForObject("SELECT state FROM labeling_work WHERE scope_id=?",String.class,scope);}
    ObjectNode response(Purpose purpose,Property...present){
        var root=json.createObjectNode().put("model",JevClassifier.MODEL).put("provider","TypeSafe");root.putObject("usage").put("cost",new BigDecimal("0.001"));
        var a=root.putObject("answers");var p=a.putObject("email_0_purpose").put("type","choice").put("choice",purpose.name()).put("confidence",.99);
        var probs=p.putObject("probabilities");for(var option:Purpose.values())probs.put(option.name(),option==purpose?1:0);
        for(var property:Property.values())a.putObject("email_0_"+property.name().toLowerCase(Locale.ROOT)).put("type","noul").put("noul",Arrays.asList(present).contains(property)?.99:.01);
        return root;
    }
    Result result(UUID id){return recommendations.read(account,List.of(id)).getFirst().result();}
    @Test void gmailDeletionOfLabeledMailPreservesCleanupHistoryAndPaidAccounting(){
        UUID id=mail("deleted-at-provider","Newsletter","{INBOX}");
        var scope=prepare(id,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION));
        pipeline.runOnce();assertThat(state(scope.id())).isEqualTo("DONE");
        recommendations.correct(account,id,Action.REVIEW,null);
        var filter=new DashboardFilter("all","","","","UTC",0);
        var page=dashboard.page(account,filter);
        var preview=previews.create(account,new DashboardPreviewService.Request(filter,List.of(id),"TRASH",page.revision()));
        var job=jobs.confirm(account,preview.id());
        var claim=jobs.claim();var dispatch=jobs.dispatch(claim,Map.of());jobs.completeAcknowledged(dispatch,null);
        var history=jdbc.queryForObject("SELECT to_jsonb(j)::text FROM cleanup_jobs j WHERE public_id=?",String.class,job.id());
        var exposure=jdbc.queryForObject("SELECT to_jsonb(e)::text FROM labeling_exposures e WHERE scope_id=?",String.class,scope.id());
        var spend=jdbc.queryForObject("SELECT reported FROM classification_budget WHERE id=1",BigDecimal.class);
        assertThat(jdbc.update("DELETE FROM message_metadata WHERE account_id=? AND dashboard_message_id=?",account,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE message_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_message_versions WHERE message_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_jsonb(j)::text FROM cleanup_jobs j WHERE public_id=?",String.class,job.id())).isEqualTo(history);
        assertThat(jobs.get(account,job.id()).changed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(e)::text FROM labeling_exposures e WHERE scope_id=?",String.class,scope.id())).isEqualTo(exposure);
        assertThat(jdbc.queryForObject("SELECT reported FROM classification_budget WHERE id=1",BigDecimal.class)).isEqualByComparingTo(spend);
    }

    @Test void removingOnlySemanticLabelsStillInvalidatesTheSurvivingMessage(){
        UUID id=mail("remove-label-only","Update","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION));pipeline.runOnce();
        jdbc.update("DELETE FROM recommendation_dirty WHERE account_id=? AND message_id=?",account,id);
        long revision=jdbc.queryForObject("SELECT revision FROM recommendation_message_versions WHERE account_id=? AND message_id=?",Long.class,account,id);
        assertThat(jdbc.update("DELETE FROM message_semantic_labels WHERE account_id=? AND message_id=?",account,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE account_id=? AND message_id=?",Long.class,account,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM recommendation_message_versions WHERE account_id=? AND message_id=?",Long.class,account,id)).isGreaterThan(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE account_id=? AND dashboard_message_id=?",Long.class,account,id)).isEqualTo(1);
    }

    @Test void scopesAreInertExactAndAccountOwnedAndDoNotQueueEveryReview(){
        UUID id=mail("one","Hello","{INBOX}");recommendations.recompute(account,List.of(id));
        var scope=prepare(id,"JEV");assertThat(pipeline.runOnce()).isFalse();
        assertThatThrownBy(()->pipeline.authorize(account+1,scope.id(),scope.hash())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->pipeline.authorize(account,scope.id(),"different")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->prepare(mail("sent","large","{SENT}"),"JEV")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->pipeline.prepare(account,List.of(id,id),"JEV",Variant.HEADERS,new BigDecimal(".20"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline.coverage(account).semanticCurrent()).isZero();assertThat(result(id).action()).isEqualTo(Action.REVIEW);
        verify(jev,never()).submitRecommendation(any());verifyNoInteractions(reader);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }
    @Test void backgroundPresenceAddsCalendarAlongsideContactWithoutSnippetOrCalendarContentReads(){
        UUID id=mail("invite","Invitation","{INBOX}");
        jdbc.update("INSERT INTO correspondent_summary(account_id,address,sent_count,received_count,sent_reply_count,received_reply_count) VALUES(?,'sender@example.test',1,0,0,0)",account);
        jdbc.update("UPDATE message_metadata SET snippet_text='Existing saved snippet',snippet_state='AVAILABLE' WHERE dashboard_message_id=?",id);
        recommendations.recompute(account,List.of(id));assertThat(result(id).facets()).contains(Facet.CONTACT);
        var scope=prepare(id,"MIME");start(scope);
        when(reader.readPresence(any(),eq("invite"))).thenReturn(new GmailEvidenceReader.Evidence(GmailEvidenceReader.PRESENCE_VERSION,EmailSnippet.observed(null),"AVAILABLE","PRESENT",
            List.of(new GmailEvidenceReader.Part("1","text/calendar","",123L,false,true)),List.of(),1,100));
        pipeline.runOnce();assertThat(state(scope.id())).isEqualTo("DONE");
        assertThat(result(id).facets()).contains(Facet.CONTACT,Facet.CALENDAR_INVITE,Facet.HAS_ATTACHMENTS);
        assertThat(result(id).action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(jdbc.queryForObject("SELECT snippet_text FROM message_metadata WHERE dashboard_message_id=?",String.class,id)).isEqualTo("Existing saved snippet");
        var second=prepare(id,"MIME");start(second);pipeline.runOnce();verify(reader,times(1)).readPresence(any(),any());verify(reader,never()).read(any(),any());
        assertThat(processing.status(account).labeling().mimeObserved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT mime_requests FROM labeling_scopes WHERE id=?",Integer.class,scope.id())).isEqualTo(1);
        var labels=prepare(id,"JEV");
        assertThat(labels.manifest().path("messages").get(0).path("request").path("state").path("email_0").path("relationship").path("coverage").asText()).isEqualTo("UNKNOWN");
        start(labels);when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION,Property.SHIPPING));pipeline.runOnce();
        assertThat(result(id).facets()).contains(Facet.CONTACT,Facet.CALENDAR_INVITE,Facet.SHIPPING);
        assertThat(result(id).action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(dashboard.page(account,new DashboardFilter("all","","","","UTC",0,"","messages","","","","CALENDAR_INVITE")).summary()).containsEntry("messages",1L);
    }
    @Test void labelsAccumulateAcrossKeepReviewAndManualActionsAndFeedFilters(){
        record Case(Purpose purpose,Property[] labels,Facet[] facets,Action action){}
        var cases=List.of(
            new Case(Purpose.NEWSLETTER,new Property[]{Property.SCHOOL,Property.MARKETING},new Facet[]{Facet.SCHOOL,Facet.MARKETING},Action.KEEP_IN_INBOX),
            new Case(Purpose.NEWSLETTER,new Property[]{Property.HEALTHCARE,Property.MARKETING},new Facet[]{Facet.HEALTHCARE,Facet.MARKETING},Action.KEEP_IN_INBOX),
            new Case(Purpose.RECEIPT,new Property[]{Property.ORDER,Property.MARKETING},new Facet[]{Facet.ORDER,Facet.MARKETING},Action.KEEP_IN_INBOX),
            new Case(Purpose.SECURITY,new Property[]{Property.SECURITY,Property.SOCIAL},new Facet[]{Facet.SECURITY,Facet.SOCIAL},Action.KEEP_IN_INBOX),
            new Case(Purpose.PERSONAL,new Property[]{Property.HUMAN_CORRESPONDENCE},new Facet[]{Facet.HUMAN_CORRESPONDENCE},Action.KEEP_IN_INBOX),
            new Case(Purpose.NOTIFICATION,new Property[]{},new Facet[]{},Action.REVIEW),
            new Case(Purpose.NOTIFICATION,new Property[]{Property.SHIPPING},new Facet[]{Facet.SHIPPING},Action.REVIEW),
            new Case(Purpose.BILL,new Property[]{Property.BILL},new Facet[]{Facet.BILL},Action.REVIEW),
            new Case(Purpose.STATEMENT,new Property[]{Property.STATEMENT},new Facet[]{Facet.STATEMENT},Action.REVIEW));
        int i=0;
        for(var c:cases){
            UUID id=mail("case-"+i++,"Subject","{INBOX}");var scope=prepare(id,"JEV");start(scope);
            when(jev.submitRecommendation(any())).thenReturn(response(c.purpose(),c.labels()));
            pipeline.runOnce();assertThat(state(scope.id())).isEqualTo("DONE");
            assertThat(result(id).purpose()).isEqualTo(c.purpose());assertThat(result(id).facets()).containsAll(Arrays.asList(c.facets()));assertThat(result(id).action()).isEqualTo(c.action());
            recommendations.correct(account,id,Action.ARCHIVE,null);recommendations.recompute(account,List.of(id));
            assertThat(result(id).facets()).containsAll(Arrays.asList(c.facets()));assertThat(result(id).action()).isEqualTo(Action.ARCHIVE);
        }
        for(String label:List.of("SCHOOL","HEALTHCARE","ORDER","SHIPPING","STATEMENT","BILL","SOCIAL","RECEIPT"))
            assertThat(dashboard.page(account,new DashboardFilter("all","","","","UTC",0,"","messages","","","",label)).summary()).containsEntry("messages",1L);
        assertThat(dashboard.page(account,new DashboardFilter("all","","","","UTC",0,"RECEIPT","messages","")).summary()).containsEntry("messages",1L);
        assertThat(pipeline.coverage(account).semanticCurrent()).isEqualTo(cases.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }
    @Test void unknownPropertiesAndForwardsStillEarnIndependentLabelsButNeverDisposal(){
        UUID id=mail("forward","Fwd: Your shipment","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        var response=response(Purpose.UNKNOWN,Property.SHIPPING);
        ((ObjectNode)response.path("answers").path("email_0_school")).put("noul",.5);
        when(jev.submitRecommendation(any())).thenReturn(response);pipeline.runOnce();
        assertThat(result(id).facets()).contains(Facet.SHIPPING).doesNotContain(Facet.SCHOOL);assertThat(result(id).action()).isEqualTo(Action.REVIEW);
        assertThat(pipeline.coverage(account).unresolved()).isEqualTo(1);
    }
    @Test void staleBeforeDispatchDoesNotChargeAndStaleResponseIsAccountedButHidden(){
        UUID id=mail("stale","Statement","{INBOX}");var first=prepare(id,"JEV");start(first);
        jdbc.update("UPDATE message_metadata SET subject_header='Invoice' WHERE dashboard_message_id=?",id);
        pipeline.runOnce();assertThat(state(first.id())).isEqualTo("STALE");verify(jev,never()).submitRecommendation(any());
        var second=prepare(id,"JEV");start(second);
        when(jev.submitRecommendation(any())).thenAnswer(call->{jdbc.update("UPDATE message_metadata SET subject_header='Different message' WHERE dashboard_message_id=?",id);return response(Purpose.BILL,Property.BILL);});
        pipeline.runOnce();assertThat(state(second.id())).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_semantic_labels",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reported FROM classification_budget WHERE id=1",BigDecimal.class)).isEqualByComparingTo(".001");
    }
    @Test void policyAndCorrectionChangesReusePaidLabelsAndUnchangedReviewIsNotResubmitted(){
        UUID id=mail("one","A status update","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION));pipeline.runOnce();
        assertThatThrownBy(()->prepare(id,"JEV")).hasMessageContaining("already submitted");
        jdbc.update("INSERT INTO correspondent_summary(account_id,address,sent_count,received_count,sent_reply_count,received_reply_count) VALUES(?,'sender@example.test',1,1,0,0)",account);
        assertThat(pipeline.coverage(account).semanticCurrent()).isEqualTo(1);
        recommendations.configure(account,false,Set.of());recommendations.correct(account,id,Action.KEEP_IN_INBOX,null);
        assertThat(recommendations.read(account,List.of(id)).getFirst().labels()).contains("NOTIFICATION");
        assertThat(dashboard.page(account,new DashboardFilter("all","","","","UTC",0,"","messages","","","","NOTIFICATION")).summary()).containsEntry("messages",1L);
        recommendations.recompute(account,List.of(id));assertThat(result(id).purpose()).isEqualTo(Purpose.NOTIFICATION);
        assertThatThrownBy(()->prepare(id,"JEV")).hasMessageContaining("already submitted");
        jdbc.update("UPDATE message_metadata SET unread=true,last_synced_at=now() WHERE dashboard_message_id=?",id);
        recommendations.recompute(account,List.of(id));assertThat(result(id).purpose()).isEqualTo(Purpose.NOTIFICATION);
        verify(jev,times(1)).submitRecommendation(any());
        jdbc.update("UPDATE message_metadata SET subject_header='New subject' WHERE dashboard_message_id=?",id);
        assertThat(recommendations.read(account,List.of(id)).getFirst().freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        recommendations.recompute(account,List.of(id));assertThat(result(id).purpose()).isEqualTo(Purpose.UNKNOWN);
        assertThat(pipeline.coverage(account).semanticCurrent()).isZero();
    }
    @Test void recoveryNeverRetriesUnknownChargesAndRespectsReservationsAndMimeRetryLimits(){
        UUID id=mail("one","Hello","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenThrow(new JevClassifier.Unavailable("timeout",true));pipeline.runOnce();
        assertThat(state(scope.id())).isEqualTo("UNKNOWN");assertThat(pipeline.runOnce()).isFalse();
        assertThat(pipeline.scopeStatus(account,scope.id()).reserved()).isEqualByComparingTo(LabelingPipeline.RESERVATION);
        assertThat(pipeline.scopeStatus(account,scope.id()).reported()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(()->pipeline.retryMime(account,scope.id(),scope.hash())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->deletion.deleteOwnerData("labeling@example.test:google")).hasMessageContaining("Wait for active requests");
        assertThatThrownBy(()->prepare(id,"JEV")).hasMessageContaining("already submitted");verify(jev,times(1)).submitRecommendation(any());
        UUID other=mail("two","Hello","{INBOX}");var second=prepare(other,"JEV");start(second);pipeline.runOnce();assertThat(state(second.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM labeling_exposures",Long.class)).isEqualTo(1);
        var mime=prepare(other,"MIME");start(mime);when(reader.readPresence(any(),any())).thenThrow(new IllegalStateException("read failed"));
        for(int i=0;i<3;i++){jdbc.update("UPDATE labeling_work SET next_attempt_at=now()-interval '1 second' WHERE scope_id=?",mime.id());pipeline.runOnce();}
        assertThat(state(mime.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT paused FROM labeling_scopes WHERE id=?",Boolean.class,mime.id())).isTrue();
        verify(reader,times(1)).readPresence(any(),any());
    }
    @Test void persistedResponseRecoversWithoutAnotherTransportCallAndInvalidTypedAnswersAreBilled(){
        UUID id=mail("recovery","Hello","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.RECEIPT,Property.ORDER));pipeline.runOnce();
        jdbc.update("DELETE FROM message_semantic_labels WHERE message_id=?",id);
        jdbc.update("UPDATE labeling_work SET state='DISPATCHED' WHERE scope_id=?",scope.id());pipeline.runOnce();
        assertThat(result(id).facets()).contains(Facet.ORDER);verify(jev,times(1)).submitRecommendation(any());
        UUID bad=mail("bad","Hello","{INBOX}");var invalid=prepare(bad,"JEV");start(invalid);
        var malformed=response(Purpose.MARKETING);malformed.put("provider","Other");when(jev.submitRecommendation(any())).thenReturn(malformed);
        pipeline.runOnce();assertThat(state(invalid.id())).isEqualTo("FAILED");
        assertThat(pipeline.scopeStatus(account,invalid.id()).reported()).isEqualByComparingTo(".001");
        assertThat(pipeline.scopeStatus(account,invalid.id()).reserved()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("SELECT reported FROM classification_budget WHERE id=1",BigDecimal.class)).isEqualByComparingTo(".002");
        assertThat(jdbc.queryForObject("SELECT labeling_reported FROM classification_budget WHERE id=1",BigDecimal.class)).isEqualByComparingTo(".002");
    }
    @Test void financialHoldAndLabelFilteredPreviewStayIndependentOfDisposalQualification() {
        UUID id=mail("bill","Your invoice","{INBOX}");
        // Recent, without depending on subsecond agreement between the JVM and database clocks.
        jdbc.update("UPDATE message_metadata SET unread=true,received_at=now()-interval '1 day' WHERE dashboard_message_id=?",id);
        var scope=prepare(id,"JEV");start(scope);when(jev.submitRecommendation(any())).thenReturn(response(Purpose.BILL,Property.BILL));pipeline.runOnce();
        assertThat(result(id).action()).isEqualTo(Action.KEEP_IN_INBOX);assertThat(result(id).reason()).isEqualTo(Reason.RECENT_UNREAD_ACTION);
        var filter=new DashboardFilter("all","","","","UTC",0,"","messages","","","","BILL");var page=dashboard.page(account,filter);
        var preview=previews.create(account,new DashboardPreviewService.Request(filter,List.of(id),"ARCHIVE",page.revision()));
        jdbc.update("UPDATE message_metadata SET subject_header='Changed' WHERE dashboard_message_id=?",id);
        assertThat(dashboard.page(account,filter).summary()).containsEntry("messages",0L);
        assertThatThrownBy(()->jobs.confirm(account,preview.id())).hasMessageContaining("Recommendations or labels changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }
    @Test void duplicateScopesExpiredLeasesAndAllowanceExhaustionDoNotDuplicateExposure() {
        UUID id=mail("one","Hello","{INBOX}");var first=prepare(id,"JEV");var duplicate=prepare(id,"JEV");start(first);start(duplicate);
        jdbc.update("UPDATE labeling_work SET state='LEASED',lease_token=gen_random_uuid(),lease_until=now()-interval '1 second' WHERE scope_id=?",first.id());
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION));
        pipeline.runOnce();pipeline.runOnce();
        assertThat(List.of(state(first.id()),state(duplicate.id()))).containsExactlyInAnyOrder("DONE","STALE");
        verify(jev,times(1)).submitRecommendation(any());
        assertThatThrownBy(()->jdbc.update("UPDATE labeling_exposures SET content_hash='rewritten'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID other=mail("two","Hello","{INBOX}");var blocked=prepare(other,"JEV");start(blocked);
        jdbc.update("UPDATE classification_budget SET labeling_checkpoint=labeling_reported+0.01 WHERE id=1");pipeline.runOnce();
        assertThat(state(blocked.id())).isEqualTo("PENDING");verify(jev,times(1)).submitRecommendation(any());
    }
    @Test void missingPricingAndTruncatedMimeRemainExplicitAndWorkCanResumeAfterRestart() {
        UUID id=mail("one","Hello","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        when(jev.recommendationPricing()).thenThrow(new JevClassifier.Unavailable("price changed",false));pipeline.runOnce();
        assertThat(state(scope.id())).isEqualTo("PENDING");verify(jev,never()).submitRecommendation(any());
        var mime=prepare(id,"MIME");start(mime);
        when(reader.readPresence(any(),any())).thenReturn(new GmailEvidenceReader.Evidence(GmailEvidenceReader.PRESENCE_VERSION,EmailSnippet.observed(null),"TRUNCATED","UNKNOWN",List.of(),List.of(),1,100));
        pipeline.runOnce();assertThat(pipeline.coverage(account).mimeUnresolved()).isEqualTo(1);
        assertThat(result(id).facets()).doesNotContain(Facet.CALENDAR_INVITE);
    }

    @Test void labelingEndpointsRequireAuthenticationCsrfAndTheReviewedAccountHash() throws Exception {
        UUID id=mail("api","Hello","{INBOX}");String owner="labeling@example.test";
        String body=json.writeValueAsString(Map.of("messages",List.of(id),"kind","JEV","variant","HEADERS","maxSpend",new BigDecimal(".20")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dashboard/api/labeling/scopes")
            .with(authenticatedAs(owner)).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var created=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dashboard/api/labeling/scopes")
            .with(authenticatedAs(owner)).with(csrfToken()).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var packet=json.readTree(created.getResponse().getContentAsString());String scope=packet.path("id").asText();
        assertThat(pipeline.runOnce()).isFalse();
        userAccounts.createAccount("different@example.test");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dashboard/api/labeling/scopes/"+scope+"/authorize")
            .with(authenticatedAs("different@example.test")).with(csrfToken()).contentType("application/json").content(json.writeValueAsString(Map.of("hash",packet.path("hash").asText()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        verify(jev,never()).submitRecommendation(any());
    }

    @Test void contradictingPurposeDoesNotEraseAnIndependentShippingLabel() {
        UUID id=mail("conflict","Support update","{INBOX}");var scope=prepare(id,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.PERSONAL,Property.SHIPPING));pipeline.runOnce();
        assertThat(result(id).purpose()).isEqualTo(Purpose.UNKNOWN);assertThat(result(id).facets()).contains(Facet.SHIPPING);
        assertThat(result(id).action()).isEqualTo(Action.REVIEW);
    }

    @Test void coverageSeparatesApprovedWorkFromDraftsPausesFailuresAndCompletedScopes() throws Exception {
        UUID done=mail("completed","Shipped","{INBOX}");var completed=prepare(done,"JEV");start(completed);
        var answer=response(Purpose.NOTIFICATION,Property.SHIPPING);
        ((ObjectNode)answer.path("answers").path("email_0_context_sufficient")).put("noul",.5);
        when(jev.submitRecommendation(any())).thenReturn(answer);pipeline.runOnce();pipeline.pause(account,completed.id());
        var coverage=pipeline.coverage(account);
        assertThat(coverage.completed()).isEqualTo(1);assertThat(coverage.failed()).isZero();
        assertThat(coverage.pending()).isZero();assertThat(coverage.paused()).isZero();
        assertThat(coverage.unresolved()).isZero(); // Generic context is not an independently offered label.

        var draft=prepare(mail("draft","Hello","{INBOX}"),"MIME");
        var paused=prepare(mail("paused","Hello","{INBOX}"),"MIME");start(paused);pipeline.pause(account,paused.id());
        var ready=prepare(mail("ready","Hello","{INBOX}"),"MIME");start(ready);
        var active=prepare(mail("active","Hello","{INBOX}"),"MIME");start(active);pipeline.pause(account,active.id());
        jdbc.update("UPDATE labeling_work SET state='LEASED',lease_until=now()+interval '1 minute' WHERE scope_id=?",active.id());
        var failed=prepare(mail("failed","Hello","{INBOX}"),"MIME");start(failed);pipeline.pause(account,failed.id());
        jdbc.update("UPDATE labeling_work SET state='FAILED',error_code='MIME_READ_FAILED' WHERE scope_id=?",failed.id());
        long owner=account;account=userAccounts.createAccount("foreign@example.test");
        prepare(mail("foreign","Hello","{INBOX}"),"MIME");account=owner;
        coverage=pipeline.coverage(account);
        assertThat(coverage.completed()).isEqualTo(1);assertThat(coverage.failed()).isEqualTo(1);
        assertThat(coverage.pending()).isEqualTo(2);assertThat(coverage.awaitingApproval()).isEqualTo(1);
        assertThat(coverage.paused()).isEqualTo(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dashboard/api/recommendations")
            .with(authenticatedAs("labeling@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.labeling.completed").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.labeling.failed").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.labeling.pending").value(2))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.labeling.awaitingApproval").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.labeling.paused").value(1));
        verifyNoInteractions(reader);
        verify(jev,times(1)).submitRecommendation(any());
    }

    @Test void successfulMimeRetryClearsItsErrorAndAllAttemptsStillConsumeTheReviewedCap() {
        UUID first=mail("retry","Invitation","{INBOX}"),second=mail("remaining","Invitation","{INBOX}");
        var scope=pipeline.prepare(account,List.of(first,second),"MIME",Variant.HEADERS,BigDecimal.ZERO);start(scope);
        jdbc.update("UPDATE labeling_work SET next_attempt_at=now()-interval '1 minute' WHERE scope_id=? AND message_id=?",scope.id(),first);
        when(reader.readPresence(any(),eq("retry")))
            .thenThrow(new IllegalStateException("Temporary read failure"))
            .thenReturn(new GmailEvidenceReader.Evidence(GmailEvidenceReader.PRESENCE_VERSION,EmailSnippet.observed(null),"AVAILABLE","PRESENT",
                List.of(new GmailEvidenceReader.Part("1","text/calendar","",100L,false,true)),List.of(),1,100));
        pipeline.runOnce();
        assertThat(jdbc.queryForObject("SELECT error_code FROM labeling_work WHERE scope_id=? AND message_id=?",String.class,scope.id(),first))
            .isEqualTo("MIME_READ_FAILED");
        jdbc.update("UPDATE labeling_work SET next_attempt_at=now()-interval '1 minute' WHERE scope_id=? AND message_id=?",scope.id(),first);
        pipeline.runOnce();
        assertThat(jdbc.queryForMap("SELECT state,error_code FROM labeling_work WHERE scope_id=? AND message_id=?",scope.id(),first))
            .containsEntry("state","DONE").containsEntry("error_code",null);
        assertThat(result(first).facets()).contains(Facet.CALENDAR_INVITE);
        assertThat(jdbc.queryForObject("SELECT mime_bytes FROM labeling_scopes WHERE id=?",Long.class,scope.id())).isEqualTo(1048676);
        pipeline.runOnce(); // The remaining message cannot silently become a third read.
        assertThat(jdbc.queryForObject("SELECT mime_requests FROM labeling_scopes WHERE id=?",Integer.class,scope.id())).isEqualTo(2);
        assertThat(pipeline.coverage(account).paused()).isEqualTo(1);assertThat(pipeline.coverage(account).failed()).isZero();
        verify(reader,times(2)).readPresence(any(),eq("retry"));verify(reader,never()).readPresence(any(),eq("remaining"));
        verifyNoInteractions(jev);
    }

    @Test void persistedScopeCanBeReopenedAndRecoveredThroughOwnedHttpControlsWithoutResettingCaps() throws Exception {
        UUID id=mail("recover-api","Invitation","{INBOX}");var scope=prepare(id,"MIME");start(scope);pipeline.pause(account,scope.id());
        jdbc.update("UPDATE labeling_work SET state='FAILED',tries=3,error_code='MIME_READ_FAILED' WHERE scope_id=?",scope.id());
        jdbc.update("UPDATE labeling_scopes SET mime_requests=1,mime_bytes=1048576 WHERE id=?",scope.id());
        String path="/dashboard/api/labeling/scopes/"+scope.id();
        var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        mockMvc.perform(get.with(authenticatedAs("labeling@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.scope.hash").value(scope.hash()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.work[0].state").value("FAILED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.mimeRequests").value(1));
        userAccounts.createAccount("other-scope@example.test");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(authenticatedAs("other-scope@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        for(String operation:List.of("resume","retry-mime")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/"+operation)
                .with(authenticatedAs("labeling@example.test")).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/"+operation)
                .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content("{\"hash\":\"wrong\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/"+operation)
                .with(authenticatedAs("other-scope@example.test")).with(csrfToken()).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is4xxClientError());
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/retry-mime")
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(pipeline.scopeStatus(account,scope.id()).paused()).isTrue();assertThat(state(scope.id())).isEqualTo("PENDING");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/resume")
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        pipeline.runOnce();
        var saved=pipeline.scopeStatus(account,scope.id());
        assertThat(saved.paused()).isTrue();assertThat(saved.mimeRequests()).isEqualTo(1);assertThat(saved.mimeBytes()).isEqualTo(1048576);
        assertThat(saved.work().getFirst().error()).isEqualTo("MIME_SCOPE_LIMIT");
        verifyNoInteractions(reader,jev);
    }

    @Test void candidatePreviewFindsMissingAndOtherMailIndependentlyOfKeepAndNeverBlindlyRepaysUnknown() {
        UUID contact=mail("contact-candidate","Hello","{INBOX}");
        UUID other=mail("other-candidate","Unclear digest","{}");
        UUID marketing=mail("marketing-candidate","An offer","{INBOX}");
        jdbc.update("UPDATE message_metadata SET classification_override='MARKETING' WHERE dashboard_message_id=?",marketing);
        jdbc.update("UPDATE message_metadata SET classification_override='OTHER' WHERE dashboard_message_id=?",other);
        jdbc.update("INSERT INTO correspondent_summary(account_id,address,sent_count,received_count,sent_reply_count,received_reply_count) VALUES(?,'sender@example.test',1,0,0,0)",account);
        recommendations.recompute(account,List.of(contact));assertThat(result(contact).action()).isEqualTo(Action.KEEP_IN_INBOX);
        UUID paid=mail("paid-unknown","Fwd: Unclear","{INBOX}");var scope=prepare(paid,"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.UNKNOWN,Property.SHIPPING));pipeline.runOnce();
        mail("excluded-sent","Subject","{SENT}");mail("excluded-trash","Subject","{TRASH}");mail("excluded-draft","Subject","{DRAFT}");
        var all=review.preview(account,new LabelingReviewService.Request("MISSING","JEV",1000,List.of()));
        assertThat(all.matching()).isEqualTo(4);assertThat(all.eligible()).isEqualTo(3);assertThat(all.previouslySubmitted()).isEqualTo(1);
        assertThat(all.messages()).extracting(LabelingReviewService.Candidate::message).containsExactlyInAnyOrder(contact,other,marketing);
        var unknown=new LabelingReviewService.Request("OTHER","JEV",1000,List.of());
        var candidates=review.preview(account,unknown);
        assertThat(candidates.matching()).isEqualTo(3);assertThat(candidates.eligible()).isEqualTo(2);
        assertThat(candidates.messages()).extracting(LabelingReviewService.Candidate::message).containsExactlyInAnyOrder(contact,other);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM labeling_scopes",Long.class)).isEqualTo(1);
        var prepared=review.prepare(account,unknown,new BigDecimal(".20"));
        assertThat(prepared.manifest().path("maxRequests").asInt()).isEqualTo(2);
        assertThat(pipeline.scopeStatus(account,prepared.id()).authorized()).isFalse();
        assertThat(review.preview(account,new LabelingReviewService.Request("SELECTED","JEV",20,List.of(paid))).messages()).isEmpty();
        assertThatThrownBy(()->review.preview(account,new LabelingReviewService.Request("MISSING","JEV",1001,List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        verify(jev,times(1)).submitRecommendation(any());verifyNoInteractions(reader);
    }

    @Test void exactInAppStartWorksWithGlobalSchedulingOffAndCannotDispatchAnotherScope() throws Exception {
        UUID first=mail("app-first","Shipping","{INBOX}"),second=mail("app-second","Shipping","{}");
        UUID unrelated=mail("app-unrelated","School newsletter","{INBOX}");
        var scope=pipeline.prepare(account,List.of(first,second),"JEV",Variant.HEADERS,new BigDecimal(".20"));
        var foreignBatch=prepare(unrelated,"JEV");start(foreignBatch);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION,Property.SHIPPING));
        String path="/dashboard/api/labeling/scopes/"+scope.id()+"/start";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
            .with(authenticatedAs("labeling@example.test")).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content("{\"hash\":\"wrong\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        userAccounts.createAccount("foreign-start@example.test");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
            .with(authenticatedAs("foreign-start@example.test")).with(csrfToken()).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        verify(jev,never()).submitRecommendation(any());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content(json.writeValueAsString(Map.of("hash",scope.hash()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        awaitExplicitBatch(scope.id());
        assertThat(pipeline.scopeStatus(account,scope.id()).work()).allMatch(w->w.state().equals("DONE"));
        assertThat(state(foreignBatch.id())).isEqualTo("PENDING");
        assertThat(dashboard.page(account,new DashboardFilter("all","","","","UTC",0,"","messages","","","","SHIPPING")).summary()).containsEntry("messages",2L);
        assertThat(pipeline.scopeStatus(account,scope.id()).reported()).isEqualByComparingTo(".002");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dashboard/api/recommendations").with(authenticatedAs("labeling@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workerAvailable").value(false));
        labelingWorker.startReviewed(account,scope.id(),scope.hash());awaitExplicitBatch(scope.id());
        verify(jev,times(2)).submitRecommendation(any());verifyNoInteractions(reader);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }

    @Test void reviewSeparatesMailboxCoverageFromAnExactThousandMessageBatchWithoutStartingIt() {
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,from_display_name,
              dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at)
            SELECT ?,'google','bulk-'||i,'sender@example.test','sender@example.test','Sender','ADDRESS','sender@example.test',
              'Email '||i,ARRAY['INBOX'],true,now()-i*interval '1 minute' FROM generate_series(1,1005) i
            """,account);
        var request=new LabelingReviewService.Request("MISSING","JEV",1000,List.of());
        var preview=review.preview(account,request);
        assertThat(preview.matching()).isEqualTo(1005);assertThat(preview.eligible()).isEqualTo(1005);assertThat(preview.messages()).hasSize(1000);
        long before=System.nanoTime();var prepared=review.prepare(account,request,new BigDecimal(".20"));
        System.out.printf("Prepared exact 1000-message review in %.2f seconds%n",(System.nanoTime()-before)/1e9);
        assertThat(prepared.manifest().path("messages")).hasSize(1000);
        assertThat(prepared.manifest().path("maxRequests").asInt()).isEqualTo(1000);
        assertThat(pipeline.scopeStatus(account,prepared.id()).authorized()).isFalse();
        assertThat(pipeline.runOnce(prepared.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT labeling_checkpoint FROM classification_budget WHERE id=1",BigDecimal.class)).isEqualByComparingTo(".20");
        verifyNoInteractions(reader);verify(jev,never()).submitRecommendation(any());
    }

    @Test void browserPreviewAndPreparationAreInertOwnedAndProgressDoesNotReloadRequestPayloads() throws Exception {
        mail("http-candidate","School digest","{INBOX}");
        var selection=new LabelingReviewService.Request("OTHER","JEV",20,List.of());
        String body=json.writeValueAsString(selection);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dashboard/api/labeling/preview")
            .with(authenticatedAs("labeling@example.test")).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dashboard/api/labeling/preview")
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.matching").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.eligible").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM labeling_scopes",Long.class)).isZero();
        var result=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dashboard/api/labeling/prepare")
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("selection",selection,"maxSpend",new BigDecimal(".20")))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var scope=json.readTree(result.getResponse().getContentAsString());String id=scope.path("id").asText();
        assertThat(scope.path("manifest").path("messages").get(0).path("subject").asText()).isEqualTo("School digest");
        var progress=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dashboard/api/labeling/scopes/"+id+"/progress")
            .with(authenticatedAs("labeling@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.running").value(false))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status.authorized").value(false)).andReturn();
        assertThat(json.readTree(progress.getResponse().getContentAsString()).path("status").path("scope").path("manifest").isNull()).isTrue();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dashboard/api/labeling/scopes")
            .with(authenticatedAs("labeling@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(id));
        userAccounts.createAccount("foreign-preview@example.test");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dashboard/api/labeling/scopes/"+id+"/progress")
            .with(authenticatedAs("foreign-preview@example.test")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        verifyNoInteractions(reader,jev);
    }

    @Test void explicitBatchPauseAndResumeReuseSavedWorkWithoutReplayingCompletedMessages() throws Exception {
        UUID first=mail("pause-first","Shipped","{INBOX}"),second=mail("pause-second","Shipped","{INBOX}");
        var scope=pipeline.prepare(account,List.of(first,second),"JEV",Variant.HEADERS,new BigDecimal(".20"));
        when(jev.submitRecommendation(any())).thenAnswer(call->{pipeline.pause(account,scope.id());return response(Purpose.NOTIFICATION,Property.SHIPPING);});
        labelingWorker.startReviewed(account,scope.id(),scope.hash());awaitExplicitBatch(scope.id());
        var paused=pipeline.scopeStatus(account,scope.id());assertThat(paused.paused()).isTrue();
        assertThat(paused.work()).filteredOn(w->w.state().equals("DONE")).hasSize(1);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION,Property.SHIPPING));
        labelingWorker.startReviewed(account,scope.id(),scope.hash());awaitExplicitBatch(scope.id());
        assertThat(pipeline.scopeStatus(account,scope.id()).work()).allMatch(w->w.state().equals("DONE"));
        verify(jev,times(2)).submitRecommendation(any());
    }
    void awaitExplicitBatch(UUID scope) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while(labelingWorker.active(account,scope) && System.nanoTime()<until)Thread.sleep(25);
        assertThat(labelingWorker.active(account,scope)).as("Explicit reviewed batch finishes or pauses").isFalse();
    }

    @Test void reviewedAllowanceIncreaseRaisesBothLimitsPreservesCompletedWorkAndDoesNotResume() throws Exception {
        UUID first=mail("increase-first","Shipped","{INBOX}"),second=mail("increase-second","Shipped","{INBOX}");
        var scope=pipeline.prepare(account,List.of(first,second),"JEV",Variant.HEADERS,new BigDecimal(".20"));start(scope);
        jdbc.update("UPDATE classification_budget SET labeling_reported=.000994014,reported=.000994014 WHERE id=1");
        var expensive=response(Purpose.NOTIFICATION,Property.SHIPPING);((ObjectNode)expensive.path("usage")).put("cost",new BigDecimal(".066962196"));
        when(jev.submitRecommendation(any())).thenReturn(expensive);pipeline.runOnce(scope.id());pipeline.runOnce(scope.id());
        var paused=pipeline.scopeStatus(account,scope.id());assertThat(paused.paused()).isTrue();
        assertThat(paused.spending().canStart()).isFalse();assertThat(paused.spending().blockedReason()).isEqualTo("SHARED_LIMIT");
        assertThatThrownBy(()->labelingWorker.startReviewed(account,scope.id(),scope.hash())).hasMessageContaining("cannot be funded");
        var plan=pipeline.reviewAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE);
        assertThat(pipeline.scopeStatus(account,scope.id()).spending().sharedLimit()).isEqualByComparingTo(".20");
        assertThatThrownBy(()->pipeline.confirmAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE,"wrong"))
            .isInstanceOf(IllegalArgumentException.class);
        pipeline.confirmAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE,plan.hash());
        pipeline.confirmAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE,plan.hash()); // Idempotent replay.
        var updated=pipeline.scopeStatus(account,scope.id());
        assertThat(updated.spending().batchLimit()).isEqualByComparingTo("1");assertThat(updated.spending().sharedLimit()).isEqualByComparingTo("1");
        assertThat(updated.spending().canStart()).isTrue();assertThat(updated.paused()).isTrue();
        assertThat(updated.scope().hash()).isNotEqualTo(scope.hash());
        assertThat(updated.scope().manifest().path("messages")).isEqualTo(paused.scope().manifest().path("messages"));
        assertThat(updated.scope().manifest().path("allowanceApprovals")).hasSize(1);
        assertThat(updated.scope().manifest().path("allowanceApprovals").get(0).path("baseHash").asText()).isEqualTo(scope.hash());
        assertThat(updated.work()).filteredOn(w->w.state().equals("DONE")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT generation FROM classification_budget WHERE id=1",Long.class)).isEqualTo(1);
        verify(jev,times(1)).submitRecommendation(any());
        assertThatThrownBy(()->labelingWorker.startReviewed(account,scope.id(),scope.hash())).hasMessageContaining("did not match");
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION,Property.SHIPPING));
        labelingWorker.startReviewed(account,scope.id(),updated.scope().hash());awaitExplicitBatch(scope.id());
        assertThat(pipeline.progress(account,scope.id()).work()).allMatch(w->w.state().equals("DONE"));
        verify(jev,times(2)).submitRecommendation(any());
    }

    @Test void allowanceReviewsEnforceOwnershipOutstandingChargesAndFreshBudgetSnapshots() {
        UUID id=mail("budget-review","Digest","{INBOX}");var scope=prepare(id,"JEV");
        var plan=pipeline.reviewAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE);
        assertThatThrownBy(()->pipeline.reviewAllowance(account+1,scope.id(),scope.hash(),BigDecimal.ONE)).hasMessageContaining("unavailable");
        assertThatThrownBy(()->pipeline.reviewAllowance(account,scope.id(),scope.hash(),new BigDecimal("101"))).hasMessageContaining("$100");
        jdbc.update("UPDATE classification_budget SET generation=generation+1,checkpoint=2 WHERE id=1");
        assertThatThrownBy(()->pipeline.confirmAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE,plan.hash())).hasMessageContaining("Spending changed");
        assertThat(pipeline.scopeStatus(account,scope.id()).spending().sharedLimit()).isEqualByComparingTo(".20");
        jdbc.update("UPDATE classification_budget SET owner_account=? WHERE id=1",userAccounts.createAccount("budget-owner@example.test"));
        assertThatThrownBy(()->pipeline.reviewAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE)).hasMessageContaining("allowance owner");
        jdbc.update("UPDATE classification_budget SET owner_account=? WHERE id=1",account);start(scope);
        when(jev.submitRecommendation(any())).thenThrow(new JevClassifier.Unavailable("timeout",true));pipeline.runOnce(scope.id());pipeline.pause(account,scope.id());
        assertThatThrownBy(()->pipeline.reviewAllowance(account,scope.id(),scope.hash(),BigDecimal.ONE)).hasMessageContaining("unknown charges");
    }

    @Test void allowanceHttpReviewIsInertAndConfirmationRequiresCsrfAndTheExactReviewHash() throws Exception {
        var scope=prepare(mail("http-limit","Digest","{INBOX}"),"JEV");
        String path="/dashboard/api/labeling/scopes/"+scope.id();
        String body=json.writeValueAsString(Map.of("baseHash",scope.hash(),"newLimit",1));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/allowance-review")
            .with(authenticatedAs("labeling@example.test")).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var response=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/allowance-review")
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var plan=json.readTree(response.getResponse().getContentAsString());
        assertThat(pipeline.progress(account,scope.id()).spending().sharedLimit()).isEqualByComparingTo(".20");
        String confirmation=json.writeValueAsString(Map.of("baseHash",scope.hash(),"newLimit",1,"reviewHash",plan.path("hash").asText()));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/allowance")
            .with(authenticatedAs("labeling@example.test")).contentType("application/json").content(confirmation))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/allowance")
            .with(authenticatedAs("labeling@example.test")).with(csrfToken()).contentType("application/json").content(confirmation))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.spending.sharedLimit").value(1));
        assertThat(pipeline.progress(account,scope.id()).authorized()).isFalse();verifyNoInteractions(reader,jev);
    }

    @Test void fundingPauseDoesNotPreventRecoveringAnAlreadyStoredResponseWithoutAnotherPaidCall() throws Exception {
        var scope=prepare(mail("funding-recovery","Shipped","{INBOX}"),"JEV");start(scope);
        when(jev.submitRecommendation(any())).thenReturn(response(Purpose.NOTIFICATION,Property.SHIPPING));pipeline.runOnce(scope.id());
        jdbc.update("UPDATE labeling_work SET state='DISPATCHED' WHERE scope_id=?",scope.id());
        jdbc.update("UPDATE labeling_scopes SET paused=true WHERE id=?",scope.id());
        jdbc.update("UPDATE classification_budget SET labeling_checkpoint=labeling_reported+.01 WHERE id=1");
        var saved=pipeline.progress(account,scope.id());
        assertThat(saved.spending().recoveryAvailable()).isTrue();assertThat(saved.spending().canStart()).isTrue();
        labelingWorker.startReviewed(account,scope.id(),scope.hash());awaitExplicitBatch(scope.id());
        assertThat(pipeline.progress(account,scope.id()).work()).allMatch(w->w.state().equals("DONE"));
        verify(jev,times(1)).submitRecommendation(any());
    }

}
