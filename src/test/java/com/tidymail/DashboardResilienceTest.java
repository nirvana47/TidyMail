package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.job.DashboardCleanupWorker;
import com.tidymail.mail.*;
import com.tidymail.service.*;
import com.tidymail.service.DashboardJobService.*;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 501 fixed synthetic targets per scenario; no OAuth account or real Gmail requests. */
@org.springframework.test.context.TestPropertySource(properties="app.cleanup.execution-mode=VERIFIED")
class DashboardResilienceTest extends PostgresJourneyTest {
    enum Action {MARK_READ,ARCHIVE,TRASH}
    @Autowired DashboardJobService jobs;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardCleanupWorker worker;
    @Autowired com.tidymail.repository.DashboardRepository dashboard;
    long account;
    final Map<String,Set<String>> labels=new ConcurrentHashMap<>();
    final AtomicInteger sends=new AtomicInteger();
    final List<Integer> sizes=new ArrayList<>();
    @BeforeEach void setup() {
        account=userAccounts.createAccount("resilience@example.test");labels.clear();sends.set(0);sizes.clear();
        when(gmailProvider.getCleanupMetadata(any(),anyList())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> ids=call.getArgument(1);return ids.stream().map(this::mail).toList();
        });
    }
    MailMessage mail(String id) {return new MailMessage(id,"synthetic-thread","google",Instant.now(),Map.of(),labels.get(id));}
    void mutate(List<String> ids,Action action) {
        for(String id:ids)labels.compute(id,(key,value)->{var next=new HashSet<>(value);switch(action){case MARK_READ->next.remove("UNREAD");case ARCHIVE->next.remove("INBOX");case TRASH->next.add("TRASH");}return Set.copyOf(next);});
    }
    void provider(Action action,java.util.function.Consumer<List<String>> behavior) {
        org.mockito.stubbing.Answer<Void> answer=call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> ids=call.getArgument(1);sends.incrementAndGet();sizes.add(ids.size());behavior.accept(ids);return null;
        };
        if(action==Action.MARK_READ)doAnswer(answer).when(gmailProvider).markReadOnce(any(),anyList());
        else doAnswer(answer).when(gmailProvider).cleanupOnce(any(),anyList(),eq(BulkActionType.valueOf(action.name())));
    }
    Progress seed(Action action) {
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<501;i++) {
            String id="synthetic-"+i;UUID uuid=UUID.randomUUID();ids.add(uuid);labels.put(id,Set.of("INBOX","UNREAD","CUSTOM"));
            jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,dashboard_message_id,from_address,dashboard_from_address,
                dashboard_unit_kind,dashboard_unit_key,received_at,labels,unread,label_evidence_known)
                VALUES(?,'google',?,?,'sender@example.test','sender@example.test','ADDRESS','sender@example.test',now(),ARRAY['INBOX','UNREAD','CUSTOM'],true,true)
                """,account,id,uuid);
        }
        var preview=previews.create(account,new DashboardPreviewService.Request(filter("inbox"),ids,action.name(),0));
        return jobs.confirm(account,preview.id());
    }
    DashboardFilter filter(String scope){return new DashboardFilter(scope,"","","","UTC",0);}
    void ready(UUID job){jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=now()-interval '1 second' WHERE public_id=?",job);}
    Progress check(UUID job) {
        var p=jobs.get(account,job);assertThat(p.total()).isEqualTo(501);
        assertThat(p.changed()+p.failed()+p.skipped()+p.cancelled()+p.remaining()+p.unresolved()).isEqualTo(501);return p;
    }
    void run(UUID job,int times){for(int i=0;i<times;i++){ready(job);worker.runOnce();check(job);}}

    @ParameterizedTest @EnumSource(Action.class)
    void multiBatchSuccessReconcilesScopesAndPreservesOtherLabels(Action action) {
        provider(action,ids->mutate(ids,action));var job=seed(action);run(job.id(),11);
        var result=check(job.id());assertThat(result.changed()).isEqualTo(501);assertThat(result.remaining()).isZero();
        assertThat(sends.get()).isEqualTo(11);assertThat(sizes).allSatisfy(n->assertThat(n).isBetween(1,50));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE 'CUSTOM'=ANY(labels)",Integer.class)).isEqualTo(501);
        var inbox=dashboard.page(account,filter("inbox"));var all=dashboard.page(account,filter("all"));
        assertThat(((Number)inbox.summary().get("messages")).intValue()).isEqualTo(action==Action.MARK_READ?501:0);
        assertThat(((Number)all.summary().get("messages")).intValue()).isEqualTo(action==Action.TRASH?0:501);
    }
    @ParameterizedTest @EnumSource(Action.class)
    void partialTimeoutIsVerifiedWithoutReplayingAndExhaustionStaysUnresolved(Action action) {
        provider(action,ids->{mutate(ids.subList(0,1),action);throw new IllegalStateException("synthetic timeout after send");});
        var job=seed(action);run(job.id(),11);var partial=check(job.id());
        assertThat(partial.changed()).isEqualTo(11);assertThat(partial.unresolved()).isEqualTo(490);
        run(job.id(),70);assertThat(sends.get()).isEqualTo(11);assertThat(jobs.claim()).isNull();
        assertThat(check(job.id()).unresolved()).isEqualTo(490);assertThat(check(job.id()).retryReason()).contains("limit reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_active_targets",Integer.class)).isEqualTo(490);
    }
    @ParameterizedTest @EnumSource(Action.class)
    void throttledReadHonorsRetryAfterThenRecoversAndCancelledWaitDoesNotSend(Action action) {
        provider(action,ids->mutate(ids,action));var job=seed(action);
        when(gmailProvider.getCleanupMetadata(any(),anyList())).thenThrow(new CleanupProviderException(429,"120"));
        worker.runOnce();assertThat(sends.get()).isZero();assertThat(check(job.id()).remaining()).isEqualTo(501);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>now()+interval '110 seconds' FROM cleanup_jobs",Boolean.class)).isTrue();
        worker.runOnce();assertThat(sends.get()).isZero();
        doAnswer(call->{List<String> ids=call.getArgument(1);return ids.stream().map(this::mail).toList();}).when(gmailProvider).getCleanupMetadata(any(),anyList());
        run(job.id(),1);assertThat(check(job.id()).changed()).isEqualTo(50);
        jobs.cancel(account,job.id());run(job.id(),12);assertThat(sends.get()).isEqualTo(1);assertThat(check(job.id()).cancelled()).isEqualTo(451);
    }
    @ParameterizedTest @EnumSource(Action.class)
    void reconnectBeforeDispatchCanRecoverAndRepeatedReadFailuresHaveBoundedBudget(Action action) {
        provider(action,ids->mutate(ids,action));var job=seed(action);
        when(gmailProvider.getCleanupMetadata(any(),anyList())).thenThrow(new com.tidymail.security.oauth2.ManagedGoogleCredentialService.CredentialAuthorizationException(com.tidymail.security.oauth2.ManagedGoogleCredentialService.FailureCode.REAUTH_REQUIRED));
        worker.runOnce();assertThat(check(job.id()).retryReason()).contains("Reconnect");assertThat(sends.get()).isZero();
        doAnswer(call->{List<String> ids=call.getArgument(1);return ids.stream().map(this::mail).toList();}).when(gmailProvider).getCleanupMetadata(any(),anyList());
        run(job.id(),1);assertThat(check(job.id()).changed()).isEqualTo(50);
        doThrow(new CleanupProviderException(503,null)).when(gmailProvider).getCleanupMetadata(any(),anyList());
        run(job.id(),66);var result=check(job.id());assertThat(result.failed()).isEqualTo(451);assertThat(result.remaining()).isZero();assertThat(sends.get()).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Action.class)
    void replacementLeaseNeverReplaysLostIntentOrAcceptsOldWorkerResults(Action action) {
        provider(action,ids->mutate(ids,action));var job=seed(action);var old=jobs.claim();
        var observations=new HashMap<String,MailMessage>();old.targets().forEach(t->observations.put(t.providerId(),mail(t.providerId())));
        var dispatch=jobs.dispatch(old,observations);mutate(dispatch.targets().stream().map(Target::providerId).toList(),action);
        jdbc.update("UPDATE cleanup_jobs SET lease_expires_at=now()-interval '1 second' WHERE public_id=?",job.id());
        var replacement=jobs.claim();jobs.complete(old,dispatch.batch(),dispatch.targets(),Map.of());assertThat(check(job.id()).unresolved()).isEqualTo(50);
        jobs.defer(replacement);run(job.id(),12);assertThat(check(job.id()).changed()).isEqualTo(501);assertThat(sends.get()).isEqualTo(10);
    }
    @ParameterizedTest @EnumSource(Action.class)
    void cancelDuringDispatchSettlesClaimedChangesAndPreventsNewIntents(Action action) throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        provider(action,ids->{entered.countDown();try{assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}mutate(ids,action);});
        var job=seed(action);var pool=Executors.newSingleThreadExecutor();
        try{var running=pool.submit(worker::runOnce);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();jobs.cancel(account,job.id());assertThat(check(job.id()).cancelled()).isEqualTo(451);finish.countDown();running.get(10,TimeUnit.SECONDS);}
        finally{finish.countDown();pool.shutdownNow();}
        run(job.id(),12);var result=check(job.id());assertThat(result.changed()).isEqualTo(50);assertThat(result.remaining()).isZero();assertThat(sends.get()).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Action.class)
    void explicitProviderRejectionProducesFailuresWithoutGuessedChanges(Action action) {
        provider(action,ids->{throw new CleanupProviderException(403,null);});var job=seed(action);run(job.id(),11);
        assertThat(check(job.id()).failed()).isEqualTo(501);assertThat(check(job.id()).changed()).isZero();assertThat(check(job.id()).unresolved()).isZero();
    }
    @ParameterizedTest @EnumSource(Action.class)
    void verificationOutagePreservesRetryGuidanceAndCancelledWorkOnlyVerifiesAfterReconnect(Action action) {
        provider(action,ids->mutate(ids,action));var job=seed(action);
        var blocked=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(gmailProvider.getCleanupMetadata(any(),anyList())).thenAnswer(call->{
            if(sends.get()>0&&blocked.get())throw new CleanupProviderException(429,"120");
            List<String> ids=call.getArgument(1);return ids.stream().map(this::mail).toList();
        });
        worker.runOnce();assertThat(check(job.id()).unresolved()).isEqualTo(50);assertThat(check(job.id()).changed()).isZero();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>now()+interval '110 seconds' FROM cleanup_jobs",Boolean.class)).isTrue();
        jobs.cancel(account,job.id());
        doThrow(new CleanupProviderException(401,null)).when(gmailProvider).getCleanupMetadata(any(),anyList());
        run(job.id(),1);assertThat(check(job.id()).unresolved()).isEqualTo(50);assertThat(check(job.id()).failed()).isZero();
        assertThat(check(job.id()).retryReason()).contains("Reconnect");
        blocked.set(false);doAnswer(call->{List<String> ids=call.getArgument(1);return ids.stream().map(this::mail).toList();}).when(gmailProvider).getCleanupMetadata(any(),anyList());
        run(job.id(),1);assertThat(check(job.id()).changed()).isEqualTo(50);assertThat(check(job.id()).cancelled()).isEqualTo(451);assertThat(sends.get()).isEqualTo(1);
    }

}
