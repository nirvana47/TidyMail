package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.job.DashboardCleanupWorker;
import com.tidymail.mail.MailMessage;
import com.tidymail.service.*;
import com.tidymail.service.DashboardJobService.*;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DashboardJobJourneyTest extends PostgresJourneyTest {
    static final String OWNER="cleanup@example.test";
    @Autowired DashboardJobService jobs;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardCleanupWorker worker;
    @Autowired UserDataDeletionService deletion;
    @Autowired com.tidymail.repository.MessageMetadataRepository metadata;
    @Autowired com.tidymail.repository.MessageMetadataSyncRepository syncMetadata;
    long account;
    final Set<String> unread=ConcurrentHashMap.newKeySet();
    @BeforeEach void setupAccount() {
        account=userAccounts.createAccount(OWNER);unread.clear();
        when(gmailProvider.getCurrentMetadata(any(),anyList())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> ids=call.getArgument(1);
            return ids.stream().map(id->mail(id,unread.contains(id))).toList();
        });
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();unread.removeAll(call.getArgument(1));return null;})
            .when(gmailProvider).markReadOnce(any(),anyList());
    }
    MailMessage mail(String id,boolean isUnread) {return new MailMessage(id,"thread-"+id,"google",Instant.now(),Map.of(),isUnread?Set.of("INBOX","UNREAD"):Set.of("INBOX"));}
    DashboardPreviewService.Preview preview(int count) {return preview(count,0);}
    DashboardPreviewService.Preview preview(int count,int readCount) {
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<count;i++) {
            String provider="job-"+i;UUID id=UUID.randomUUID();ids.add(id);if(i>=readCount)unread.add(provider);
            jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,dashboard_message_id,
                from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,labels,unread,label_evidence_known)
                VALUES(?,'google',?,?,'sender@example.test','sender@example.test','ADDRESS','sender@example.test',now(),?, ?,true)
                """,account,provider,id,i>=readCount?new String[]{"INBOX","UNREAD"}:new String[]{"INBOX"},i>=readCount);
        }
        return previews.create(account,new DashboardPreviewService.Request(new DashboardFilter("inbox","","","","UTC",0),ids,"MARK_READ",0));
    }
    void ready(UUID job) {jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=now()-interval '1 second' WHERE public_id=?",job);}
    Progress invariant(UUID id) {
        var p=jobs.get(account,id);
        assertThat(p.changed()+p.failed()+p.skipped()+p.unresolved()+p.cancelled()+p.remaining()).isEqualTo(p.total());
        return p;
    }
    Map<String,MailMessage> before(Claim claim) {Map<String,MailMessage> result=new HashMap<>();claim.targets().forEach(t->result.put(t.providerId(),mail(t.providerId(),true)));return result;}
    @Test void fiveHundredAndOneTargetsRunInBoundedBatchesAndRestoreProgressWithoutBrowser() {
        var preview=preview(501);var job=jobs.confirm(account,preview.id());
        for(int i=0;i<11;i++){ready(job.id());worker.runOnce();invariant(job.id());}
        var result=invariant(job.id());assertThat(result.changed()).isEqualTo(501);assertThat(result.remaining()).isZero();assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(jobs.list(account)).contains(result);
        var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(gmailProvider,times(11)).markReadOnce(any(),capture.capture());
        assertThat(capture.getAllValues()).allSatisfy(batch->assertThat(batch.size()).isBetween(1,50));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE unread",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_active_targets",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_job_events",Long.class)).isGreaterThan(20);
    }
    @Test void concurrentDuplicateConfirmationReturnsOneJobAndOtherOverlappingPreviewConflicts() throws Exception {
        var preview=preview(3);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->jobs.confirm(account,preview.id()));var b=pool.submit(()->jobs.confirm(account,preview.id()));
            assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(b.get(10,TimeUnit.SECONDS).id());
        } finally {pool.shutdownNow();}
        var ids=jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata",UUID.class);
        var another=previews.create(account,new DashboardPreviewService.Request(new DashboardFilter("inbox","","","","UTC",0),ids,"MARK_READ",0));
        assertThatThrownBy(()->jobs.confirm(account,another.id())).hasMessageContaining("active or unresolved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isEqualTo(1);
    }
    @Test void cancelBeforeStartAndDuplicateCancelPreventAllDispatch() {
        var preview=preview(4);var job=jobs.confirm(account,preview.id());jobs.cancel(account,job.id());jobs.cancel(account,job.id());
        ready(job.id());worker.runOnce();
        var result=invariant(job.id());assertThat(result.cancelled()).isEqualTo(4);assertThat(result.status()).isEqualTo("CANCELLED");assertThat(result.remaining()).isZero();
        verify(gmailProvider,never()).markReadOnce(any(),anyList());assertThat(jobs.confirm(account,preview.id()).id()).isEqualTo(job.id());
    }
    @Test void cancellationDuringProviderCallLetsClaimedBatchFinishButClaimsNoMore() throws Exception {
        var preview=preview(70);var job=jobs.confirm(account,preview.id());
        var dispatched=new CountDownLatch(1);var finish=new CountDownLatch(1);
        doAnswer(call->{dispatched.countDown();assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();unread.removeAll(call.getArgument(1));return null;}).when(gmailProvider).markReadOnce(any(),anyList());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var active=pool.submit(worker::runOnce);assertThat(dispatched.await(10,TimeUnit.SECONDS)).isTrue();
            var cancelling=jobs.cancel(account,job.id());assertThat(cancelling.status()).isEqualTo("CANCELLING");assertThat(cancelling.cancelled()).isEqualTo(20);assertThat(cancelling.remaining()).isEqualTo(50);
            finish.countDown();active.get(10,TimeUnit.SECONDS);
        } finally {finish.countDown();pool.shutdownNow();}
        ready(job.id());worker.runOnce();var result=invariant(job.id());assertThat(result.changed()).isEqualTo(50);assertThat(result.cancelled()).isEqualTo(20);assertThat(result.status()).isEqualTo("CANCELLED");
        verify(gmailProvider,times(1)).markReadOnce(any(),anyList());
    }
    @Test void cancellationAfterPreflightBeforeIntentStopsTheBatch() {
        var preview=preview(2);var job=jobs.confirm(account,preview.id());var claim=jobs.claim();
        jobs.cancel(account,job.id());assertThat(jobs.dispatch(claim,before(claim))).isNull();
        assertThat(invariant(job.id()).cancelled()).isEqualTo(2);verify(gmailProvider,never()).markReadOnce(any(),anyList());
    }
    @Test void timeoutAfterSendUsesReadOnlyVerificationEvenAfterCancellation() {
        var preview=preview(5);var job=jobs.confirm(account,preview.id());
        doAnswer(call->{unread.remove("job-0");throw new IllegalStateException("simulated timeout");}).when(gmailProvider).markReadOnce(any(),anyList());
        worker.runOnce();var unknown=invariant(job.id());assertThat(unknown.changed()).isEqualTo(1);assertThat(unknown.unresolved()).isEqualTo(4);assertThat(unknown.status()).isEqualTo("VERIFYING");
        jobs.cancel(account,job.id());unread.clear();ready(job.id());worker.runOnce();
        var resolved=invariant(job.id());assertThat(resolved.changed()).isEqualTo(5);assertThat(resolved.remaining()).isZero();assertThat(resolved.unresolved()).isZero();assertThat(resolved.status()).isEqualTo("CANCELLED");
        verify(gmailProvider,times(1)).markReadOnce(any(),anyList());
    }
    @Test void restartRecoversQueuedWorkButNeverReplaysAnExpiredDispatchAndFencesLateResults() {
        var preview=preview(60);var job=jobs.confirm(account,preview.id());var old=jobs.claim();var dispatch=jobs.dispatch(old,before(old));
        jdbc.update("UPDATE cleanup_jobs SET lease_expires_at=now()-interval '1 second' WHERE public_id=?",job.id());
        var newClaim=jobs.claim();assertThat(newClaim.generation()).isGreaterThan(old.generation());
        jobs.complete(old,dispatch.batch(),dispatch.targets(),Map.of());
        assertThat(invariant(job.id()).unresolved()).isEqualTo(50);assertThat(invariant(job.id()).remaining()).isEqualTo(10);
        jobs.defer(newClaim);ready(job.id());worker.runOnce();
        var result=invariant(job.id());assertThat(result.changed()).isEqualTo(10);assertThat(result.unresolved()).isEqualTo(50);
        ready(job.id());worker.runOnce();verify(gmailProvider,times(1)).markReadOnce(any(),anyList());
        assertThat(invariant(job.id()).unresolved()).isEqualTo(50);
        unread.clear();ready(job.id());worker.runOnce();assertThat(invariant(job.id()).changed()).isEqualTo(60);
    }
    @Test void eligibilityAndProtectionAreRecheckedAndKnownNoOpsStayInFixedTotal() {
        var preview=preview(6,1);
        var job=jobs.confirm(account,preview.id());
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['INBOX','TRASH'] WHERE provider_message_id='job-1'");
        unread.remove("job-2");
        worker.runOnce();var result=invariant(job.id());assertThat(result.changed()).isEqualTo(3);assertThat(result.skipped()).isEqualTo(3);assertThat(result.total()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT unread FROM message_metadata WHERE provider_message_id='job-2'",Boolean.class)).isFalse();
    }
    @Test void allReceivedDispatchRetainsArchivedDeliveredSentMailButExcludesSentOnlyMail() {
        preview(2);
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['SENT','UNREAD'],delivered_to_values=ARRAY[?] WHERE provider_message_id='job-0'",OWNER);
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['SENT','UNREAD'] WHERE provider_message_id='job-1'");
        var ids=jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata",UUID.class);
        var preview=previews.create(account,new DashboardPreviewService.Request(new DashboardFilter("all","","","","UTC",0),ids,"MARK_READ",0));
        assertThat(preview.effective()).isEqualTo(1);
        var job=jobs.confirm(account,preview.id());var claim=jobs.claim();
        var observed=new MailMessage("job-0","synthetic-thread","google",Instant.now(),Map.of("Delivered-To",List.of("<"+OWNER+">")),Set.of("SENT","UNREAD"));
        var dispatch=jobs.dispatch(claim,Map.of("job-0",observed));
        assertThat(dispatch.targets()).extracting(Target::providerId).containsExactly("job-0");
        jobs.complete(claim,dispatch.batch(),dispatch.targets(),Map.of("job-0",new MailMessage("job-0","synthetic-thread","google",Instant.now(),Map.of(),Set.of("SENT"))));
        var result=invariant(job.id());assertThat(result.changed()).isEqualTo(1);assertThat(result.skipped()).isEqualTo(1);
    }
    @Test void protectionAddedAfterPreflightPreventsDispatchAndKeepsAllTargets() {
        var preview=preview(3);var job=jobs.confirm(account,preview.id());var claim=jobs.claim();
        jdbc.update("INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,'sender@example.test','USER')",account);
        assertThat(jobs.dispatch(claim,before(claim))).isNull();
        var result=invariant(job.id());assertThat(result.skipped()).isEqualTo(3);assertThat(result.changed()).isZero();
        verify(gmailProvider,never()).markReadOnce(any(),anyList());
    }
    @Test void readFailureDefersWithoutMutationAndCancellationBypassesTheDelay() {
        var preview=preview(2);var job=jobs.confirm(account,preview.id());
        when(gmailProvider.getCurrentMetadata(any(),anyList())).thenThrow(new IllegalStateException("simulated unavailable"));
        worker.runOnce();assertThat(invariant(job.id()).remaining()).isEqualTo(2);
        jobs.cancel(account,job.id());assertThat(invariant(job.id()).remaining()).isZero();assertThat(invariant(job.id()).cancelled()).isEqualTo(2);
        verify(gmailProvider,never()).markReadOnce(any(),anyList());
    }
    @Test void ownershipCsrfExpiredPreviewUnsupportedActionsAndReloadAreEnforced() throws Exception {
        var preview=preview(2);var body="{\"preview\":\""+preview.id()+"\"}";
        mockMvc.perform(post("/dashboard/api/jobs").with(authenticatedAs(OWNER)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/dashboard/api/jobs").with(authenticatedAs(OWNER)).with(csrfToken()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        var job=jobs.confirm(account,preview.id());userAccounts.createAccount("other@example.test");
        mockMvc.perform(get("/dashboard/api/jobs/{id}",job.id()).with(authenticatedAs("other@example.test"))).andExpect(status().isNotFound());
        mockMvc.perform(post("/dashboard/api/jobs/{id}/cancel",job.id()).with(authenticatedAs("other@example.test")).with(csrfToken()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mockMvc.perform(get("/dashboard/api/jobs").with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$[0].id").value(job.id().toString()));
        jobs.cancel(account,job.id());
        var ids=jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata",UUID.class);
        var archive=previews.create(account,new DashboardPreviewService.Request(new DashboardFilter("inbox","","","","UTC",0),ids,"ARCHIVE",0));
        assertThatThrownBy(()->jobs.confirm(account,archive.id())).hasMessageContaining("Only Gmail Mark as Read");
        var expired=previews.create(account,new DashboardPreviewService.Request(new DashboardFilter("inbox","","","","UTC",0),ids,"MARK_READ",0));
        jdbc.update("UPDATE dashboard_previews SET expires_at=now()-interval '1 second' WHERE public_id=?",expired.id());
        assertThatThrownBy(()->jobs.confirm(account,expired.id())).hasMessageContaining("expired or unavailable");
    }
    @Test void deletionCancelsWorkAndWaitsForActiveRequestsButAllowsExplicitPurgeAfterLeasesSettle() {
        var preview=preview(2);var job=jobs.confirm(account,preview.id());
        var claim=jobs.claim();jobs.dispatch(claim,before(claim));
        assertThatThrownBy(()->deletion.deleteOwnerData(OWNER+":google")).hasMessageContaining("Wait for active requests");
        assertThat(invariant(job.id()).cancelRequested()).isTrue();
        jobs.complete(claim,null,claim.targets(),Map.of());
        assertThat(invariant(job.id()).unresolved()).isEqualTo(2);
        // Explicit DELETE may discard unresolved history once no request/verification worker is active.
        assertThat(deletion.deleteOwnerData(OWNER+":google")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }
    @Test void reconciliationRejectsStaleReadsAndBlocksOlderSyncSnapshots() {
        var preview=preview(2);var job=jobs.confirm(account,preview.id());var claim=jobs.claim();var dispatch=jobs.dispatch(claim,before(claim));
        jdbc.update("UPDATE message_metadata SET provider_revision=30 WHERE provider_message_id='job-0'");
        var observed=new HashMap<String,MailMessage>();
        for(var target:dispatch.targets())observed.put(target.providerId(),new MailMessage(target.providerId(),"synthetic-thread","google",Instant.now(),Map.of(),Set.of("INBOX"),null,java.math.BigInteger.valueOf(20),com.tidymail.mail.AttachmentState.UNKNOWN));
        jobs.complete(claim,dispatch.batch(),dispatch.targets(),observed);
        assertThat(invariant(job.id()).changed()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT unread FROM message_metadata WHERE provider_message_id='job-0'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT unread FROM message_metadata WHERE provider_message_id='job-1'",Boolean.class)).isFalse();
        var cached=metadata.findByAccountIdAndProviderMessageIdIn(account,List.of("job-1")).getFirst();
        cached.setProviderRevision(java.math.BigInteger.valueOf(15));cached.setUnread(true);cached.setLabels(List.of("INBOX","UNREAD"));
        assertThat(syncMetadata.upsertMetadata(cached)).isZero();
        assertThat(jdbc.queryForObject("SELECT unread FROM message_metadata WHERE provider_message_id='job-1'",Boolean.class)).isFalse();
    }
    @Test void confirmedTargetAndJobIdentitiesCannotBeChangedInTheDatabase() {
        var preview=preview(2);var job=jobs.confirm(account,preview.id());
        assertThatThrownBy(()->jdbc.update("UPDATE cleanup_job_targets SET provider_message_id='replacement' WHERE job_id=? AND ordinal=0",job.id())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE cleanup_jobs SET selected_count=3 WHERE public_id=?",job.id())).hasMessageContaining("immutable");
        invariant(job.id());
    }
}
