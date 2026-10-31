package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.job.DashboardCleanupWorker;
import com.tidymail.mail.*;
import com.tidymail.repository.*;
import com.tidymail.service.*;
import com.tidymail.service.DashboardJobService.*;
import com.tidymail.support.PostgresJourneyTest;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real PostgreSQL and HTTP job API; every mailbox mutation is synthetic. */
@TestPropertySource(properties="app.cleanup.execution-mode=RESPONSE")
class DashboardResponseCleanupJourneyTest extends PostgresJourneyTest {
    static final String OWNER="response-cleanup@example.test";
    @Autowired DashboardJobService jobs;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardCleanupWorker worker;
    @Autowired DashboardRepository dashboard;
    @Autowired MessageMetadataRepository metadata;
    @Autowired MessageMetadataSyncRepository syncMetadata;
    long account;
    final List<List<String>> sends=new ArrayList<>();
    @BeforeEach void setup(){account=userAccounts.createAccount(OWNER);sends.clear();}
    DashboardFilter filter(){return new DashboardFilter("inbox","","","","UTC",0);}
    Progress seed(int count,BulkActionType action) {
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<count;i++) {
            UUID id=UUID.randomUUID();ids.add(id);
            jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,dashboard_message_id,
                from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,
                labels,unread,label_evidence_known,provider_revision)
                VALUES(?,'google',?,?,'sender@example.test','sender@example.test','ADDRESS','sender@example.test',now(),
                ARRAY['INBOX','UNREAD','CUSTOM'],true,true,10)
                """,account,"response-"+i,id);
        }
        var preview=previews.create(account,new DashboardPreviewService.Request(filter(),ids,action.name(),0));
        return jobs.confirm(account,preview.id());
    }
    void provider(BulkActionType action,java.util.function.Consumer<List<String>> behavior) {
        org.mockito.stubbing.Answer<Void> answer=call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> ids=call.getArgument(1);sends.add(List.copyOf(ids));behavior.accept(ids);return null;
        };
        if(action==BulkActionType.MARK_READ)doAnswer(answer).when(gmailProvider).markReadOnce(any(),anyList());
        else doAnswer(answer).when(gmailProvider).cleanupOnce(any(),anyList(),eq(action));
    }
    void ready(UUID job){jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=now()-interval '1 second' WHERE public_id=?",job);}
    Progress result(UUID job) {
        var p=jobs.get(account,job);
        assertThat(p.changed()+p.failed()+p.skipped()+p.cancelled()+p.remaining()+p.unresolved()).isEqualTo(p.total());return p;
    }
    @ParameterizedTest @EnumSource(value=BulkActionType.class,names={"MARK_READ","ARCHIVE","TRASH"})
    void twoThousandOneHundredSixtyFiveTargetsNeedThreeRequestsAndNoVerificationReads(BulkActionType action) throws Exception {
        provider(action,ids->{});var job=seed(2165,action);
        for(int i=0;i<3;i++){ready(job.id());worker.runOnce();}
        var result=result(job.id());assertThat(result.changed()).isEqualTo(2165);
        assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.executionMode()).isEqualTo("RESPONSE");
        assertThat(sends.stream().map(List::size)).containsExactly(1000,1000,165);
        assertThat(sends.stream().flatMap(List::stream).toList()).doesNotHaveDuplicates().hasSize(2165);
        verify(gmailProvider,never()).getCleanupMetadata(any(),anyList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_active_targets",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE 'CUSTOM'=ANY(labels)",Long.class)).isEqualTo(2165);
        long inbox=((Number)dashboard.page(account,filter()).summary().get("messages")).longValue();
        assertThat(inbox).isEqualTo(action==BulkActionType.MARK_READ?2165:0);
        if(action==BulkActionType.MARK_READ)assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE unread",Long.class)).isZero();
        mockMvc.perform(get("/dashboard/api/jobs/{id}",job.id()).with(authenticatedAs(OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.executionMode").value("RESPONSE"))
            .andExpect(jsonPath("$.changed").value(2165));
    }
    @Test void localProtectionAndEligibilityAreRecheckedWithoutProviderReads() {
        provider(BulkActionType.TRASH,ids->{});var job=seed(3,BulkActionType.TRASH);
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['TRASH'] WHERE provider_message_id='response-0'");
        jdbc.update("UPDATE message_metadata SET dashboard_from_address='protected@example.test' WHERE provider_message_id='response-1'");
        jdbc.update("INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,'protected@example.test','USER')",account);
        worker.runOnce();assertThat(result(job.id()).changed()).isEqualTo(1);assertThat(result(job.id()).skipped()).isEqualTo(2);
        assertThat(sends).containsExactly(List.of("response-2"));verify(gmailProvider,never()).getCleanupMetadata(any(),anyList());
    }
    @Test void cancellationAfterClaimKeepsThatBatchButStopsTheNextOne() {
        var job=seed(1005,BulkActionType.ARCHIVE);var claim=jobs.claim();var dispatch=jobs.dispatch(claim,Map.of());
        jobs.cancel(account,job.id());jobs.completeAcknowledged(dispatch,null);
        var p=result(job.id());assertThat(p.changed()).isEqualTo(1000);assertThat(p.cancelled()).isEqualTo(5);
        assertThat(p.status()).isEqualTo("CANCELLED");ready(job.id());assertThat(jobs.claim()).isNull();
    }
    @Test void explicitThrottlingCanRetryButAnAmbiguousDispatchCanOnlyBeRead() {
        provider(BulkActionType.TRASH,ids->{throw new CleanupProviderException(429,"120");});var job=seed(2,BulkActionType.TRASH);
        worker.runOnce();assertThat(result(job.id()).remaining()).isEqualTo(2);assertThat(result(job.id()).unresolved()).isZero();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>now()+interval '110 seconds' FROM cleanup_jobs",Boolean.class)).isTrue();
        provider(BulkActionType.TRASH,ids->{throw new IllegalStateException("timeout after dispatch");});
        ready(job.id());worker.runOnce();assertThat(result(job.id()).unresolved()).isEqualTo(2);
        when(gmailProvider.getCleanupMetadata(any(),anyList())).thenReturn(List.of(
            new MailMessage("response-0","thread","google",Instant.now(),Map.of(),Set.of("TRASH"))));
        for(int i=0;i<6;i++){ready(job.id());worker.runOnce();}
        assertThat(sends).hasSize(2);assertThat(result(job.id()).changed()).isEqualTo(1);
        assertThat(result(job.id()).unresolved()).isEqualTo(1);assertThat(result(job.id()).verificationPending()).isFalse();
    }
    @Test void lostWorkerNeverReplaysAndLateAcknowledgementIsFenced() {
        var job=seed(2,BulkActionType.MARK_READ);var old=jobs.claim();var dispatch=jobs.dispatch(old,Map.of());
        jdbc.update("UPDATE cleanup_jobs SET lease_expires_at=now()-interval '1 second' WHERE public_id=?",job.id());
        var recovery=jobs.claim();assertThat(recovery.verification()).isTrue();
        jobs.completeAcknowledged(dispatch,null);assertThat(result(job.id()).changed()).isZero();
        jobs.complete(recovery,null,recovery.targets(),Map.of());assertThat(result(job.id()).unresolved()).isEqualTo(2);
        verify(gmailProvider,never()).markReadOnce(any(),anyList());
    }
    @Test void staleSyncCannotUndoAcknowledgedLabelsButLaterGmailChangesRemainAuthoritative() {
        provider(BulkActionType.TRASH,ids->{});var job=seed(1,BulkActionType.TRASH);
        Instant before=Instant.now().minusSeconds(1);worker.runOnce();
        var row=metadata.findByAccountIdAndProviderMessageIdIn(account,List.of("response-0")).getFirst();
        row.setLabels(List.of("INBOX","UNREAD","CUSTOM"));row.setUnread(true);
        syncMetadata.upsertMetadata(row,before);
        assertThat(jdbc.queryForObject("SELECT 'TRASH'=ANY(labels) FROM message_metadata",Boolean.class)).isTrue();
        row.setProviderRevision(BigInteger.valueOf(11));syncMetadata.upsertMetadata(row,Instant.now());
        assertThat(jdbc.queryForObject("SELECT 'TRASH'=ANY(labels) FROM message_metadata",Boolean.class)).isFalse();
    }
}
