package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.job.DashboardCleanupWorker;
import com.tidymail.mail.*;
import com.tidymail.rules.*;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/** Fixed selections, real persistence, simulated provider writes and restart recovery. */
class ManualFilingJourneyTest extends PostgresJourneyTest {
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    @Autowired DashboardCleanupWorker worker;
    @SpyBean GmailRuleProvider labels;
    long account;
    final DashboardFilter filter=new DashboardFilter("inbox","","","","UTC",0);
    @BeforeEach void setup() {
        account=userAccounts.createAccount("filing@example.test");
        doReturn(List.of(new GmailRuleProvider.Destination("Label_offers","Offers"))).when(labels).destinations(any());
        doAnswer(i->labels.useDestination(i.getArgument(1),((com.fasterxml.jackson.databind.JsonNode)i.getArgument(1)).path("_destinationMode").asText().equals("NEW")?"Label_new":"Label_offers")).when(labels).prepare(any(),any());
    }
    UUID mail(String id,String sender,String[] labels) {
        UUID uuid=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,dashboard_message_id,
            from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,labels,unread,label_evidence_known,provider_revision)
            VALUES(?,'google',?,?,?,?,'ADDRESS',?,now(),?,true,true,10)
            """,account,id,uuid,sender,sender,sender,labels);
        return uuid;
    }
    DashboardPreviewService.Preview preview(List<UUID> ids,MailDestinationService.Choice choice) {
        return previews.create(account,new DashboardPreviewService.Request(filter,ids,"ORGANIZE",0,null,choice));
    }
    MailDestinationService.Choice existing(boolean skip){return new MailDestinationService.Choice("EXISTING","Label_offers","Untrusted browser name",skip);}

    @Test void exactPreviewCancellationAndConfirmedFilingRetainOtherLabelsAndDoNotCreateRules() {
        UUID a=mail("one","offers@example.test",new String[]{"INBOX","UNREAD","CUSTOM"});
        UUID b=mail("two","offers@example.test",new String[]{"INBOX","UNREAD","Label_offers"});
        UUID protectedMail=mail("protected","protected@example.test",new String[]{"INBOX","UNREAD"});
        UUID untouched=mail("untouched","offers@example.test",new String[]{"INBOX","UNREAD"});
        jdbc.update("INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,'protected@example.test','USER')",account);
        var cancelled=preview(List.of(a,b,protectedMail),new MailDestinationService.Choice("NEW","","New offers",false));
        assertThat(cancelled.destination().name()).isEqualTo("New offers");
        verify(labels,never()).prepare(any(),any());
        jobs.cancel(account,jobs.confirm(account,cancelled.id()).id());worker.runOnce();
        verify(labels,never()).prepare(any(),any());verify(gmailProvider,never()).fileOnce(any(),anyList(),anyString(),anyBoolean());
        var p=preview(List.of(a,b,protectedMail),existing(false));
        assertThat(p.destination().name()).isEqualTo("Offers");assertThat(p.effective()).isEqualTo(1);assertThat(p.skipped()).isEqualTo(2);
        var job=jobs.confirm(account,p.id());assertThat(jobs.confirm(account,p.id()).id()).isEqualTo(job.id());
        worker.runOnce();
        verify(gmailProvider).fileOnce(any(),eq(List.of("one")),eq("Label_offers"),eq(false));
        assertThat(jobs.get(account,job.id()).changed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT labels @> ARRAY['INBOX','UNREAD','CUSTOM','Label_offers'] FROM message_metadata WHERE dashboard_message_id=?",Boolean.class,a)).isTrue();
        assertThat(jdbc.queryForObject("SELECT 'Label_offers'=ANY(labels) FROM message_metadata WHERE dashboard_message_id=?",Boolean.class,untouched)).isFalse();
        var newPreview=preview(List.of(untouched),new MailDestinationService.Choice("NEW","","New filing label",false));
        verify(labels,times(1)).prepare(any(),any()); // saving another preview is still read-only
        var newJob=jobs.confirm(account,newPreview.id());worker.runOnce();
        verify(gmailProvider).fileOnce(any(),eq(List.of("untouched")),eq("Label_new"),eq(false));
        assertThat(jobs.get(account,newJob.id()).destinationName()).isEqualTo("New filing label");
        assertThat(jobs.get(account,newJob.id()).changed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_rules",Integer.class)).isZero();
    }

    @Test void lostMutationResponseIsVerifiedAfterLeaseRecoveryWithoutReplayingAndDestinationIntentNeverRepeats() {
        UUID a=mail("uncertain","offers@example.test",new String[]{"INBOX","UNREAD","CUSTOM"});
        var p=preview(List.of(a),existing(true));var job=jobs.confirm(account,p.id());
        doThrow(new IllegalStateException("simulated lost response")).when(gmailProvider).fileOnce(any(),anyList(),anyString(),anyBoolean());
        when(gmailProvider.getCleanupMetadata(any(),anyList())).thenReturn(List.of(new MailMessage("uncertain","thread","google",java.time.Instant.now(),Map.of(),Set.of("UNREAD","CUSTOM","Label_offers"),null,java.math.BigInteger.valueOf(11),AttachmentState.UNKNOWN)));
        worker.runOnce();assertThat(jobs.get(account,job.id()).unresolved()).isEqualTo(1);
        jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=now() WHERE public_id=?",job.id());worker.runOnce();
        assertThat(jobs.get(account,job.id()).changed()).isEqualTo(1);
        verify(gmailProvider,times(1)).fileOnce(any(),anyList(),anyString(),anyBoolean());
        assertThat(jdbc.queryForObject("SELECT 'INBOX'=ANY(labels) FROM message_metadata WHERE dashboard_message_id=?",Boolean.class,a)).isFalse();
        var another=mail("new-target","offers@example.test",new String[]{"INBOX"});
        var newJob=jobs.confirm(account,preview(List.of(another),new MailDestinationService.Choice("NEW","","Brand new",false)).id());
        var abandoned=jobs.claim();assertThat(abandoned.job()).isEqualTo(newJob.id());assertThat(jobs.destinationIntent(abandoned)).isTrue();
        jdbc.update("UPDATE cleanup_jobs SET lease_expires_at=now()-interval '1 second',next_attempt_at=now() WHERE public_id=?",newJob.id());
        worker.runOnce();assertThat(jobs.get(account,newJob.id()).failed()).isEqualTo(1);
        verify(labels,times(1)).prepare(any(),any()); // only the first job resolved; abandoned create is never retried
    }
}
