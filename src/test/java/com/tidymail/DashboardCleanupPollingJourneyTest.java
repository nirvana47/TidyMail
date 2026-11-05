package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.BulkActionType;
import com.tidymail.service.DashboardJobService;
import com.tidymail.service.DashboardPreviewService;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Cleanup must finish user-confirmed work with global/paid scheduling disabled. */
@TestPropertySource(properties={"app.cleanup.worker.enabled=true","app.cleanup.poll-ms=25","app.cleanup.startup-delay-ms=0"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardCleanupPollingJourneyTest extends PostgresJourneyTest {
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    @Autowired Environment environment;

    @Test void confirmedIndexedMailCleanupRunsWithoutTheGlobalScheduler() throws Exception {
        assertThat(environment.getProperty("spring.task.scheduling.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.classification.worker.enabled")).isEqualTo("false");
        long account=userAccounts.createAccount("cleanup-poll@example.test");
        UUID received=mail(account,"received","{INBOX}"),excluded=mail(account,"excluded","{SPAM}");
        var filter=new DashboardFilter("indexed","newsletter","","","UTC",0);
        var preview=previews.create(account,new DashboardPreviewService.Request(filter,List.of(received,excluded),"TRASH",0));
        assertThat(preview.effective()).isEqualTo(1);
        var confirmed=jobs.confirm(account,preview.id());
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        DashboardJobService.Progress result;
        do {
            result=jobs.get(account,confirmed.id());
            if(result.remaining()==0)break;
            Thread.sleep(25);
        } while(System.nanoTime()<deadline);
        assertThat(result.remaining()).isZero();
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("COMPLETED_WITH_EXCEPTIONS");
        verify(gmailProvider,times(1)).cleanupOnce(any(),eq(List.of("received")),eq(BulkActionType.TRASH));
        verify(gmailProvider,never()).getCleanupMetadata(any(),anyList());
        assertThat(jdbc.queryForObject("SELECT 'SPAM'=ANY(labels) FROM message_metadata WHERE dashboard_message_id=?",Boolean.class,excluded)).isTrue();
    }
    UUID mail(long account,String id,String labels) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
              dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known)
            VALUES(?,'google',?,'sender@example.test','sender@example.test','ADDRESS','sender@example.test',
              'newsletter',?::text[],true) RETURNING dashboard_message_id
            """,UUID.class,account,id,labels);
    }
}
