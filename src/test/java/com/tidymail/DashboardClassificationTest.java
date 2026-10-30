package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import com.tidymail.service.JevClassifier.*;
import com.tidymail.support.PostgresJourneyTest;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.MediaType;

class DashboardClassificationTest extends PostgresJourneyTest {
    static final String OWNER="classification@example.test";
    @MockBean JevClassifier classifier;
    @Autowired DashboardClassificationService classification;
    @Autowired DashboardRepository dashboard;
    @Autowired DashboardGroupingService grouping;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    long account;
    @BeforeEach void setup(){reset(classifier);when(classifier.configured()).thenReturn(true);account=userAccounts.createAccount(OWNER);classification.automatic(account,false);
        when(classifier.classify(anyList())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();List<Evidence> items=call.getArgument(0);return result(items);});}
    Result result(List<Evidence> items){return new Result(items.stream().map(e->new Decision(e.subject().contains("offer")?"MARKETING":e.address().contains("amazon")?"RECEIPT":"PERSONAL",
        e.address().contains("amazon")||e.address().contains("chase")||e.address().endsWith("@google.com")||e.address().contains("voice.google.com")?"AUTOMATED":"PERSON",
        e.address().contains("amazon")?"SHOPPING":e.address().contains("chase")?"BANKING":e.address().startsWith("bard")?"AI_PRODUCT":e.address().startsWith("device")?"DEVICE_SUPPORT":"GENERAL",.98)).toList(),new BigDecimal("0.0001"));}
    UUID mail(String address,String name,String subject){
        var identity=DashboardIdentity.parse(List.of(address),List.of(),address+subject);
        return jdbc.queryForObject("""
          INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,labels,unread,label_evidence_known,
            dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at)
          VALUES(?,'google',?,?,?,?,ARRAY['INBOX','CATEGORY_PERSONAL'],true,true,?,'ADDRESS',?,now()) RETURNING dashboard_message_id
          """,UUID.class,account,UUID.randomUUID().toString(),address,name,subject,identity.address(),identity.key());
    }
    DashboardFilter filter(String category){return new DashboardFilter("all","","","","UTC",0,category);}
    void finish(){classification.start(account);for(int i=0;i<20&&Set.of("RUNNING","QUEUED").contains(classification.status(account).state());i++)classification.runOnce();assertThat(classification.status(account).state()).isEqualTo("COMPLETE");}
    @Test void personalAddressesStayDistinctWhileAmazonPurposesShareFixedCategories() {
        mail("shivani.tewari@gene.com","Shivani Tewari","Dinner tonight?");mail("shivani.tewari2016@gmail.com","Shivani Tewari","Our plans");mail("shivani.tewari20166@gmail.com","Shivani Tewari","A different address");
        UUID receipt=mail("orders@amazon.com","Amazon","Your receipt");UUID offer=mail("orders@amazon.com","Amazon","Special offer");finish();
        var page=dashboard.page(account,filter(""));assertThat(page.summary()).containsEntry("groups",4L).containsEntry("messages",5L);
        assertThat(page.groups()).extracting(DashboardRepository.Group::label).contains("Shivani Tewari","amazon.com");
        assertThat(dashboard.page(account,filter("PERSONAL")).summary().get("messages")).isEqualTo(3L);
        var marketing=dashboard.page(account,filter("MARKETING"));assertThat(marketing.summary().get("messages")).isEqualTo(1L);
        var selected=dashboard.selection(account,filter("MARKETING"),List.of(marketing.groups().getFirst().id()),null);assertThat(selected).extracting(DashboardRepository.Selected::id).containsExactly(offer);
        assertThat(dashboard.details(account,filter("RECEIPT"),marketing.groups().getFirst().id(),0).messages()).extracting(DashboardRepository.Message::id).containsExactly(receipt);
        var preview=previews.create(account,new DashboardPreviewService.Request(filter("MARKETING"),List.of(receipt,offer),"TRASH",dashboard.revision(account)));
        assertThat(preview.effective()).isEqualTo(1);assertThat(preview.skipped()).isEqualTo(1);
        verifyNoInteractions(gmailProvider,outlookProvider);
    }
    @Test void confirmedCleanupTargetsStayFixedWhenClassificationChangesGroupsAndCategories() {
        UUID id=mail("a@gene.com","Shivani Tewari","Hello");
        var preview=previews.create(account,new DashboardPreviewService.Request(filter(""),List.of(id),"MARK_READ",0));
        var job=jobs.confirm(account,preview.id());
        var before=jdbc.queryForList("SELECT message_id,provider_message_id FROM cleanup_job_targets WHERE job_id=? ORDER BY ordinal",job.id());
        mail("b@gene.com","Someone else","Another message");finish();
        assertThat(jdbc.queryForList("SELECT message_id,provider_message_id FROM cleanup_job_targets WHERE job_id=? ORDER BY ordinal",job.id())).isEqualTo(before);
        assertThat(before).hasSize(1);assertThat(jobs.get(account,job.id()).remaining()).isEqualTo(1);
        assertThatThrownBy(()->previews.targets(account,preview.id(),0)).hasMessageContaining("Grouping changed");
        verifyNoInteractions(gmailProvider,outlookProvider);
    }
    @Test void metadataIsSentOutsideTransactionsAndUnchangedMailIsCached() {
        mail("a@gene.com","A Person","Hello");finish();
        var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(classifier).classify(capture.capture());
        Evidence e=(Evidence)capture.getValue().getFirst();assertThat(e.name()).isEqualTo("A Person");assertThat(e.subject()).isEqualTo("Hello");assertThat(e.gmailCategories()).containsExactly("CATEGORY_PERSONAL");
        clearInvocations(classifier);classification.start(account);assertThat(classification.status(account).total()).isZero();classification.runOnce();verify(classifier,never()).classify(anyList());
    }
    @Test void googleStreamsStayApartVoiceCorrespondentsNeverCollapseAndChaseStillGroups() {
        mail("bard-noreply@google.com","Google","Product news");mail("device-support@google.com","Google","Device notice");
        mail("opaque1@txt.voice.google.com","Someone","Hi");mail("opaque2@txt.voice.google.com","Someone else","Hello");
        mail("alerts@chase.com","Chase","Bank notice");mail("alerts@e.chase.com","Chase","Bank notice");finish();
        assertThat(dashboard.page(account,filter("")).summary().get("groups")).isEqualTo(5L);
        assertThat(dashboard.page(account,filter("")).groups()).extracting(DashboardRepository.Group::label).contains("google.com · ai product","google.com · device support","Someone","Someone else","chase.com");
    }
    @Test void manualGroupingAndCategoryCorrectionsWinAndStaleInputsAreReclassified() {
        UUID first=mail("a@chase.com","Chase","offer"),second=mail("b@e.chase.com","Chase","offer");
        UUID unit=jdbc.queryForObject("SELECT public_id FROM sender_units WHERE account_id=? AND canonical_key='a@chase.com'",UUID.class,account);
        grouping.correct(account,new DashboardGroupingService.Correction("SEPARATE",List.of(unit),null,null,0),true);
        classification.correctCategory(account,List.of(first),"PERSONAL",dashboard.revision(account));finish();
        assertThat(dashboard.page(account,filter("")).summary().get("groups")).isEqualTo(2L);
        assertThat(dashboard.page(account,filter("PERSONAL")).summary().get("messages")).isEqualTo(1L);
        jdbc.update("UPDATE message_metadata SET subject_header='Changed offer' WHERE dashboard_message_id=?",second);
        assertThat(dashboard.page(account,filter("UNCLASSIFIED")).summary().get("messages")).isEqualTo(1L);finish();
        assertThat(classification.status(account).total()).isEqualTo(1);
        assertThat(dashboard.page(account,filter("PERSONAL")).summary().get("messages")).isEqualTo(1L);
        assertThatThrownBy(()->classification.correctCategory(account,List.of(first),"MARKETING",0)).hasMessageContaining("changed");
    }
    @Test void cancellationDuringRemoteCallDiscardsLateAnswers() {
        mail("a@gene.com","A","Hello");classification.start(account);
        when(classifier.classify(anyList())).thenAnswer(call->{classification.cancel(account);return result(call.getArgument(0));});classification.runOnce();
        assertThat(classification.status(account).state()).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE classified_at IS NOT NULL",Long.class)).isZero();
    }
    @Test void changedMetadataDuringRequestIsSkippedAndRetriedOnlyOnNextRun() {
        UUID id=mail("a@gene.com","A","Hello");classification.start(account);
        when(classifier.classify(anyList())).thenAnswer(call->{jdbc.update("UPDATE message_metadata SET subject_header='Updated' WHERE dashboard_message_id=?",id);return result(call.getArgument(0));});classification.runOnce();
        assertThat(classification.status(account).state()).isEqualTo("COMPLETE");assertThat(classification.status(account).skipped()).isEqualTo(1);assertThat(classification.status(account).done()).isZero();
        classification.start(account);assertThat(classification.status(account).total()).isEqualTo(1);
    }
    @Test void unknownBillingPausesInsteadOfBlindlyRetrying() {
        mail("a@gene.com","A","Hello");classification.start(account);
        when(classifier.classify(anyList())).thenThrow(new Unavailable("Required zero-retention route unavailable",true));
        for(int i=0;i<3;i++){jdbc.update("UPDATE dashboard_classification_jobs SET next_attempt_at=now()");classification.runOnce();}
        assertThat(classification.status(account).state()).isEqualTo("FAILED");assertThat(dashboard.page(account,filter("")).groups().getFirst().label()).isEqualTo("gene.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE classified_at IS NOT NULL",Long.class)).isZero();
        classification.runOnce();verify(classifier,times(1)).classify(anyList());
        assertThat(classification.status(account).budget().unknown()).isEqualTo(1);
    }
    @Test void expiredLeasesRecoverAndDeletedTargetsDoNotBlockProgress() {
        UUID id=mail("a@gene.com","A","Hello");classification.start(account);
        jdbc.update("UPDATE dashboard_classification_jobs SET status='RUNNING',lease_token=gen_random_uuid(),lease_until=now()-interval '1 second',attempts=1");
        jdbc.update("DELETE FROM message_metadata WHERE dashboard_message_id=?",id);classification.runOnce();
        assertThat(classification.status(account).state()).isEqualTo("COMPLETE");assertThat(classification.status(account).skipped()).isEqualTo(1);verify(classifier,never()).classify(anyList());
    }
    @Test void routesEnforceOwnershipCsrfAndDoNotExposeKey() throws Exception {
        UUID id=mail("a@gene.com","A","Hello");
        mockMvc.perform(post("/dashboard/api/classification/start").with(authenticatedAs(OWNER))).andExpect(status().isForbidden());
        mockMvc.perform(post("/dashboard/api/classification/start").with(authenticatedAs(OWNER)).with(csrfToken())).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
        mockMvc.perform(get("/dashboard/api/classification").with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.key").doesNotExist());
        long other=userAccounts.createAccount("other-classification@example.test");
        assertThatThrownBy(()->classification.correctCategory(other,List.of(id),"PERSONAL",0)).hasMessageContaining("unavailable");
        when(classifier.configured()).thenReturn(false);assertThatThrownBy(()->classification.start(other)).hasMessageContaining("OPENROUTER_API_KEY");
    }
    @Autowired ClassificationBudget budget;
    @Test void recurringCheckpointSurvivesNewJobsAndContinuationIsIdempotent() {
        for(int i=0;i<12;i++)mail("a@gene.com","A","Hello "+i);
        when(classifier.classify(anyList())).thenAnswer(c->new Result(result(c.getArgument(0)).decisions(),new BigDecimal("0.60")));
        classification.start(account);classification.runOnce();classification.runOnce();
        assertThat(budget.view(account).reported()).isEqualByComparingTo("1.20");
        assertThat(budget.view(account).paused()).isTrue();assertThat(classification.runOnce()).isFalse();
        long generation=budget.view(account).generation();
        budget.continueSpending(account,generation,false);budget.continueSpending(account,generation,false);
        assertThat(budget.view(account).checkpoint()).isEqualByComparingTo("2.20");
        assertThat(budget.view(account).generation()).isEqualTo(generation+1);
        classification.cancel(account);classification.start(account);classification.runOnce();
        assertThat(budget.view(account).reported()).isEqualByComparingTo("1.80");
    }
    @Test void lateCancelledResponsesStillChargeExactlyOnce() {
        mail("a@gene.com","A","Hello");classification.start(account);
        when(classifier.classify(anyList())).thenAnswer(c->{classification.cancel(account);return result(c.getArgument(0));});classification.runOnce();
        UUID attempt=jdbc.queryForObject("SELECT id FROM classification_attempts",UUID.class);
        budget.report(attempt,new BigDecimal("0.0001"));
        assertThat(budget.view(account).reported()).isEqualByComparingTo("0.0001");
        assertThat(classification.status(account).cost()).isEqualByComparingTo("0.0001");
        assertThat(classification.status(account).done()).isZero();
    }
    @Test void expiredDispatchRequiresExplicitBillingReviewAndOwnerContinuation() {
        mail("a@gene.com","A","Hello");classification.start(account);
        UUID job=classification.status(account).id(),attempt=UUID.randomUUID();
        jdbc.update("UPDATE classification_budget SET owner_account=?",account);
        jdbc.update("INSERT INTO classification_attempts(id,job_id,account_id,state,dispatched_at) VALUES(?,?,?,'DISPATCHED',now()-interval '2 minutes')",attempt,job,account);
        assertThat(classification.runOnce()).isFalse();assertThat(budget.view(account).unknown()).isEqualTo(1);
        assertThatThrownBy(()->budget.continueSpending(account,0,false)).hasMessageContaining("acknowledge");
        long other=userAccounts.createAccount("other@example.test");
        assertThatThrownBy(()->budget.continueSpending(other,0,true)).hasMessageContaining("owner");
        budget.continueSpending(account,0,true);assertThat(budget.view(account).unknown()).isZero();
        budget.report(attempt,new BigDecimal("0.25"));assertThat(budget.view(account).reported()).isEqualByComparingTo("0.25");
    }
    @Test void inFlightBlocksContinuationAndZeroCostsAreValid() {
        mail("a@gene.com","A","Hello");classification.start(account);
        when(classifier.classify(anyList())).thenAnswer(c->{
            assertThat(budget.view(account).inFlight()).isEqualTo(1);
            assertThatThrownBy(()->budget.continueSpending(account,0,false)).hasMessageContaining("in-flight");
            return new Result(result(c.getArgument(0)).decisions(),BigDecimal.ZERO);
        });classification.runOnce();
        assertThat(budget.view(account).reported()).isEqualByComparingTo("0");
        assertThat(budget.view(account).unknown()).isZero();assertThat(classification.status(account).done()).isEqualTo(1);
    }
    @Test void automaticIncomingDeduplicatesAndRespectsSwitchAndSharedPause() {
        mail("a@gene.com","A","Hello");assertThat(classification.runOnce()).isFalse();
        classification.automatic(account,true);classification.runOnce();
        verify(classifier,times(1)).classify(anyList());
        classification.runOnce();verify(classifier,times(1)).classify(anyList());
        classification.automatic(account,false);mail("b@gene.com","B","New arrival");classification.runOnce();
        verify(classifier,times(1)).classify(anyList());
        classification.automatic(account,true);jdbc.update("UPDATE classification_budget SET reported=1");
        assertThat(classification.runOnce()).isFalse();verify(classifier,times(1)).classify(anyList());
        budget.continueSpending(account,0,false);classification.runOnce();verify(classifier,times(2)).classify(anyList());
    }
    @Test void exactAddressDetailsRetainCategoryFilterAndIndependentPagination() {
        for(int i=0;i<12;i++)mail("orders@amazon.com","Amazon","receipt "+i);
        mail("offers@amazon.com","Amazon","offer");finish();
        var group=dashboard.page(account,filter("")).groups().getFirst().id();
        assertThat(dashboard.details(account,filter(""),group,0,"orders@amazon.com").messages()).hasSize(10).allMatch(m->m.address().equals("orders@amazon.com"));
        assertThat(dashboard.details(account,filter(""),group,1,"orders@amazon.com").messages()).hasSize(2);
        assertThat(dashboard.details(account,filter("MARKETING"),group,0,"orders@amazon.com").messages()).isEmpty();
        assertThat(dashboard.details(account,filter(""),group,0,null).total()).isEqualTo(13);
    }
    @Test void batchProgressLeavesUnfilteredDraftRevisionStableAndInvalidatesCategoryPreviews() {
        for(int i=0;i<6;i++)mail("a@gene.com","A","Hello "+i);
        classification.start(account);long revision=dashboard.revision(account);classification.runOnce();
        assertThat(dashboard.revision(account)).isEqualTo(revision);
        assertThat(dashboard.classificationRevision(account)).isPositive();
        var group=dashboard.page(account,filter("PERSONAL")).groups().getFirst().id();
        var ids=dashboard.details(account,filter("PERSONAL"),group,0).messages().stream().map(DashboardRepository.Message::id).toList();
        var preview=previews.create(account,new DashboardPreviewService.Request(filter("PERSONAL"),ids,"MARK_READ",revision));
        classification.runOnce();
        assertThatThrownBy(()->jobs.confirm(account,preview.id())).hasMessageContaining("unavailable");
    }
    @Test void concurrentResponseReplaysCannotLoseOrDoubleCountSpend() throws Exception {
        mail("a@gene.com","A","Hello");classification.start(account);
        UUID job=classification.status(account).id(),a=UUID.randomUUID(),b=UUID.randomUUID();
        jdbc.update("INSERT INTO classification_attempts(id,job_id,account_id,state) VALUES(?,?,?,'DISPATCHED'),(?,?,?,'DISPATCHED')",a,job,account,b,job,account);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{budget.report(a,new BigDecimal("0.65"));budget.report(b,new BigDecimal("0.55"));});
            var second=executor.submit(()->{budget.report(b,new BigDecimal("0.55"));budget.report(a,new BigDecimal("0.65"));});
            first.get(10,java.util.concurrent.TimeUnit.SECONDS);second.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(budget.view(account).reported()).isEqualByComparingTo("1.20");
        assertThat(budget.view(account).paused()).isTrue();
    }
    @Test void automaticArrivalsGetFourSlotsAndHistoricalBacklogKeepsOne() {
        for(int i=0;i<10;i++)mail("old@gene.com","Old","History "+i);
        jdbc.update("DELETE FROM classification_incoming");classification.start(account);classification.automatic(account,true);
        for(int i=0;i<8;i++)mail("new@gene.com","New","Arrival "+i);
        classification.runOnce();
        var captured=org.mockito.ArgumentCaptor.forClass(List.class);verify(classifier).classify(captured.capture());
        List<Evidence> evidence=captured.getValue();
        assertThat(evidence).filteredOn(e->e.address().equals("new@gene.com")).hasSize(4);
        assertThat(evidence).filteredOn(e->e.address().equals("old@gene.com")).hasSize(1);
    }
    @Test void cancellingAutomaticWorkDoesNotImmediatelyRecreateIt() {
        classification.automatic(account,true);mail("a@gene.com","A","Hello");
        when(classifier.classify(anyList())).thenAnswer(c->{classification.cancel(account);return result(c.getArgument(0));});
        classification.runOnce();classification.runOnce();verify(classifier,times(1)).classify(anyList());
    }

    @Test void disablingAutomaticLetsManualCatchupFinishAndRetainsDeferredArrivals() {
        for(int i=0;i<2;i++)mail("old@gene.com","Old","History "+i);
        jdbc.update("DELETE FROM classification_incoming");classification.start(account);classification.automatic(account,true);
        for(int i=0;i<8;i++)mail("new@gene.com","New","Arrival "+i);
        classification.runOnce();classification.automatic(account,false);classification.runOnce();
        assertThat(classification.status(account).state()).isEqualTo("COMPLETE");
        assertThat(classification.status(account).done()).isEqualTo(6);
        classification.automatic(account,true);classification.runOnce();
        assertThat(classification.status(account).done()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE classified_at IS NOT NULL",Long.class)).isEqualTo(10);
    }
}
