package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.service.DashboardPreviewService;
import com.tidymail.service.DashboardService;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DashboardPerformanceTest extends PostgresJourneyTest {
    @Autowired DashboardService dashboard;
    @Autowired DashboardPreviewService previews;
    @Autowired com.tidymail.service.DashboardJobService jobs;
    @Autowired com.tidymail.job.DashboardCleanupWorker cleanup;
    @Autowired com.zaxxer.hikari.HikariDataSource dataSource;
    @Test void realDashboardPathsMeetPhaseTwoBudgetsDuringMetadataWrites() throws Exception {
        int priorPool=dataSource.getMaximumPoolSize();
        dataSource.setMaximumPoolSize(20); // Match the application's default, rather than the two-connection shared harness.
        try {
        String owner="dashboard-perf@example.test";long account=userAccounts.createAccount(owner);
        for(int size:new int[]{10000,Integer.getInteger("dashboard.performance.large-size",50000)}) {
            jdbc.update("DELETE FROM message_metadata WHERE account_id=?",account);
            jdbc.update("""
              INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,
                received_at,unread,labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,dashboard_identity_reason)
              SELECT ?,'google','synthetic-'||i,'sender'||(i%500)||'@example.test','Sender '||(i%500),
                'Synthetic subject '||i,TIMESTAMPTZ '2026-10-28 12:00:00+00'-(i%100)*interval '1 hour',i%2=0,
                CASE WHEN i%4=0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END,true,
                'sender'||(i%500)||'@example.test','ADDRESS','sender'||(i%500)||'@example.test','Exact sender address'
              FROM generate_series(1,?) i
              """,account,size);
            jdbc.execute("ANALYZE");
            var all=new DashboardFilter("all","","","","UTC",0);
            var filtered=new DashboardFilter("all","sender1","2026-10-22","2026-10-28","UTC",0);
            var later=new DashboardFilter("all","","","","UTC",9);
            var group=dashboard.page(account,all).groups().getFirst();
            assertThat(dashboard.page(account,all).summary().get("messages")).isEqualTo((long)size);
            for(boolean concurrent:new boolean[]{false,true}) {
                var stop=new AtomicBoolean();var writes=new AtomicInteger();
                var marked=ConcurrentHashMap.<String>newKeySet();
                var dispatched=new CountDownLatch(1);
                when(gmailProvider.getCleanupMetadata(any(),anyList())).thenAnswer(call->{
                    List<String> ids=call.getArgument(1);
                    return ids.stream().map(id->{
                        int index=Integer.parseInt(id.substring("synthetic-".length()));
                        var labels=new HashSet<String>();if(index%4!=0)labels.add("INBOX");
                        if(index%2==0&&!marked.contains(id))labels.add("UNREAD");
                        return new com.tidymail.mail.MailMessage(id,"synthetic-thread","google",java.time.Instant.now(),Map.of(),labels);
                    }).toList();
                });
                doAnswer(call->{dispatched.countDown();Thread.sleep(1000);marked.addAll(call.getArgument(1));return null;})
                    .when(gmailProvider).markReadOnce(any(),anyList());
                UUID job=concurrent?jobs.confirm(account,previews.create(account,new DashboardPreviewService.Request(all,
                    jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata WHERE account_id=? ORDER BY id LIMIT 5000",UUID.class,account),"MARK_READ",0)).id()).id():null;
                try(var executor=Executors.newFixedThreadPool(2)) {
                    var worker=executor.submit(()->{while(concurrent&&!stop.get()){jdbc.update("WITH locked AS MATERIALIZED (SELECT id FROM message_metadata WHERE account_id=? ORDER BY provider_message_id LIMIT 200 FOR UPDATE) UPDATE message_metadata m SET last_synced_at=clock_timestamp() FROM locked WHERE m.id=locked.id",account);writes.incrementAndGet();try{Thread.sleep(100);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}}});
                    var cleaner=executor.submit(()->{while(concurrent&&!stop.get()){jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=now() WHERE public_id=?",job);cleanup.runOnce();}});
                    try {
                        if(concurrent)assertThat(dispatched.await(10,TimeUnit.SECONDS)).isTrue();
                        double pageBudget=size==10000?500:2000, detailBudget=size==10000?250:500;
                        measure(size,concurrent,"summary_page",pageBudget,()->dashboard.page(account,all));
                        measure(size,concurrent,"search_date",pageBudget,()->dashboard.page(account,filtered));
                        measure(size,concurrent,"sender_page_10",pageBudget,()->dashboard.page(account,later));
                        measure(size,concurrent,"message_address_expansion",detailBudget,()->dashboard.details(account,all,group.id(),1));
                        measure(size,concurrent,"authenticated_html_and_api",size==10000?2000:3000,()->{try{mockMvc.perform(get("/dashboard").with(authenticatedAs(owner))).andExpect(status().isOk());mockMvc.perform(get("/dashboard/api/page").param("scope","all").with(authenticatedAs(owner))).andExpect(status().isOk());}catch(Exception e){throw new RuntimeException(e);}});
                        for(int count:new int[]{501,5000}) {
                            var ids=jdbc.queryForList("SELECT dashboard_message_id FROM message_metadata WHERE account_id=? ORDER BY id LIMIT ?",UUID.class,account,count);
                            long start=System.nanoTime();var preview=previews.create(account,new DashboardPreviewService.Request(all,ids,"MARK_READ",0));
                            assertThat(preview.selected()).isEqualTo(count);
                            System.out.printf(Locale.ROOT,"DASHBOARD_PERFORMANCE rows=%d concurrent=%s preview_ids=%d elapsed_ms=%.2f%n",size,concurrent,count,(System.nanoTime()-start)/1e6);
                        }
                        if(concurrent)assertThat(jobs.get(account,job).remaining()).as("Cleanup still active during measurements").isPositive();
                    } finally {stop.set(true);if(concurrent)jobs.cancel(account,job);worker.get(30,TimeUnit.SECONDS);cleaner.get(30,TimeUnit.SECONDS);}
                    if(concurrent)assertThat(writes.get()).isPositive();
                    if(concurrent){var result=jobs.get(account,job);assertThat(result.changed()).isPositive();assertThat(result.remaining()+result.failed()+result.unresolved()).isZero();assertThat(result.changed()+result.skipped()+result.cancelled()).isEqualTo(5000);System.out.printf("DASHBOARD_PERFORMANCE rows=%d cleanup_changed=%d cleanup_skipped=%d cleanup_cancelled=%d%n",size,result.changed(),result.skipped(),result.cancelled());}
                    System.out.printf("DASHBOARD_PERFORMANCE rows=%d concurrent=%s write_batches=%d%n",size,concurrent,writes.get());
                }
            }
            // Exercise category predicates over a fully classified index as well as the legacy/unclassified path.
            jdbc.update("UPDATE message_metadata SET classification_category=CASE WHEN id%5=0 THEN 'MARKETING' ELSE 'PERSONAL' END,classification_kind='PERSON',classification_stream='GENERAL',classification_confidence=.99,classification_hash=classification_evidence_hash,classification_version=?,classified_at=now() WHERE account_id=?",com.tidymail.service.JevClassifier.VERSION,account);
            jdbc.execute("ANALYZE message_metadata");
            var category=new DashboardFilter("all","","","","UTC",0,"MARKETING");
            assertThat(dashboard.page(account,category).summary().get("messages")).isEqualTo((long)size/5);
            measure(size,false,"classified_category_page",size==10000?500:2000,()->dashboard.page(account,category));
        }
        verifyNoInteractions(outlookProvider);
        } finally {dataSource.setMaximumPoolSize(priorPool);}
    }
    static void measure(int size,boolean concurrent,String operation,double budget,Runnable work) {
        work.run();double[] values=new double[10];
        for(int i=0;i<10;i++){long started=System.nanoTime();work.run();values[i]=(System.nanoTime()-started)/1e6;}
        Arrays.sort(values);
        System.out.printf(Locale.ROOT,"DASHBOARD_PERFORMANCE rows=%d concurrent=%s operation=%s samples=10 p50_ms=%.2f p95_ms=%.2f target_ms=%.0f%n",size,concurrent,operation,(values[4]+values[5])/2,values[9],budget);
        assertThat(values[9]).as("%s rows=%d concurrent=%s",operation,size,concurrent).isLessThanOrEqualTo(budget);
    }
}
