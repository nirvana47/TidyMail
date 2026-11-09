package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.service.*;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.*;

/** Fully populated synthetic label/recommendation cache. No model-quality or live throughput claim. */
class LabelingScaleJourneyTest extends PostgresJourneyTest {
    @Autowired DashboardRepository dashboard;
    @Autowired LabelingPipeline pipeline;
    @Autowired RecommendationService recommendations;
    @Autowired ObjectMapper json;
    @MockBean JevClassifier jev;
    @Test void full115kLabelCacheRemainsBrowsableDuringLocalComposition() throws Exception {
        long account=userAccounts.createAccount("scale@example.test");int size=115000;
        jdbc.update("UPDATE classification_budget SET reported=0,checkpoint=1,labeling_reported=0,labeling_checkpoint=0.20 WHERE id=1");
        for(int first=1;first<=size;first+=1000)jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,from_display_name,
              dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,size_estimate,received_at)
            SELECT ?,'google','scale-'||i,'sender'||(i%500)||'@example.test','sender'||(i%500)||'@example.test','Sender',
              'ADDRESS','sender'||(i%500)||'@example.test','School newsletter',ARRAY['INBOX'],true,1000,now()
            FROM generate_series(?,?) i
            """,account,first,Math.min(size,first+999));
        UUID id=jdbc.queryForObject("SELECT dashboard_message_id FROM message_metadata WHERE account_id=? ORDER BY id LIMIT 1",UUID.class,account);
        var scope=pipeline.prepare(account,List.of(id),"JEV",RecommendationSemanticContract.Variant.HEADERS,new BigDecimal(".20"));pipeline.authorize(account,scope.id(),scope.hash());
        when(jev.configured()).thenReturn(true);when(jev.recommendationPricing()).thenReturn(json.createObjectNode().put("synthetic",true));
        var response=json.createObjectNode().put("model",JevClassifier.MODEL).put("provider","TypeSafe");response.putObject("usage").put("cost",.001);
        var answers=response.putObject("answers");var purpose=answers.putObject("email_0_purpose").put("type","choice").put("choice","NEWSLETTER").put("confidence",.99);
        var probs=purpose.putObject("probabilities");for(var p:MessageRecommendation.Purpose.values())probs.put(p.name(),p==MessageRecommendation.Purpose.NEWSLETTER?1:0);
        for(var p:RecommendationSemanticContract.Property.values())answers.putObject("email_0_"+p.name().toLowerCase(Locale.ROOT)).put("type","noul").put("noul",p==RecommendationSemanticContract.Property.SCHOOL || p==RecommendationSemanticContract.Property.MARKETING?.99:.01);
        when(jev.submitRecommendation(any())).thenReturn(response);pipeline.runOnce();
        // Synthetic bulk fixtures preserve owned exposure FKs and realistic JSON sizes. Only the first row traverses transport.
        UUID job=jdbc.queryForObject("SELECT job_id FROM classification_attempts LIMIT 1",UUID.class);
        jdbc.update("""
            INSERT INTO classification_attempts(id,job_id,account_id,state,cost)
            SELECT m.dashboard_message_id,?,?,'REPORTED',0 FROM message_metadata m WHERE m.account_id=? AND m.dashboard_message_id<>?
            """,job,account,account,id);
        jdbc.update("""
            INSERT INTO labeling_exposures(attempt_id,scope_id,account_id,message_id,content_hash,input_hash,request,reserved,pricing)
            SELECT m.dashboard_message_id,?,m.account_id,m.dashboard_message_id,'synthetic',labeling_input_hash(m,NULL::correspondent_summary,'HEADERS'),
              e.request,0,e.pricing FROM message_metadata m CROSS JOIN labeling_exposures e
            WHERE m.account_id=? AND m.dashboard_message_id<>? AND e.message_id=?
            """,scope.id(),account,id,id);
        jdbc.update("""
            INSERT INTO message_semantic_labels(account_id,message_id,input_hash,variant,contract_version,judgment,attempt_id,labels,purpose)
            SELECT m.account_id,m.dashboard_message_id,labeling_input_hash(m,NULL::correspondent_summary,'HEADERS'),'HEADERS',s.contract_version,
              jsonb_set(s.judgment,'{message}',to_jsonb(m.dashboard_message_id)),m.dashboard_message_id,s.labels,s.purpose
            FROM message_metadata m CROSS JOIN message_semantic_labels s
            WHERE m.account_id=? AND m.dashboard_message_id<>? AND s.message_id=?
            """,account,id,id);
        var params=new MapSqlParameterSource("account",account).addValue("asOf",java.sql.Timestamp.from(java.time.Instant.now())).addValue("prototype",id);
        new NamedParameterJdbcTemplate(jdbc).update("""
            WITH """+RecommendationSql.ACCOUNT_CONTEXT+"""
            INSERT INTO message_recommendations(account_id,message_id,source_hash,policy_revision,engine_version,adapter_version,result,assessment,relationship,source_stamp,
              metadata_revision,account_stamp,policy_source_revision,history_revision,history_sent,history_received,history_sent_replies,history_received_replies,history_last_sent,history_last_received,attention_source)
            SELECT m.account_id,m.dashboard_message_id,"""+RecommendationSql.HASH+"""
              ,prototype.policy_revision,prototype.engine_version,prototype.adapter_version,
              jsonb_set(jsonb_set(prototype.result,'{message}',to_jsonb(m.dashboard_message_id)),'{evidenceRevision}',to_jsonb(("""+RecommendationSql.HASH+"""
              )::text)),prototype.assessment,prototype.relationship,
            """+RecommendationSql.STAMP+"""
            ,COALESCE(v.revision,0),a.account_stamp,COALESCE(p.revision,0),0,0,0,0,0,NULL,NULL,'READ'
            FROM message_metadata m JOIN account_context a ON a.id=m.account_id
            """+RecommendationSql.JOINS+"""
            CROSS JOIN message_recommendations prototype WHERE m.account_id=:account AND prototype.message_id=:prototype AND m.dashboard_message_id<>:prototype
            """,params);
        jdbc.execute("ANALYZE");
        var filter=new DashboardFilter("all","","","","UTC",0,"","messages","","KEEP_IN_INBOX","","SCHOOL");
        assertThat(dashboard.page(account,filter).summary()).containsEntry("messages",(long)size);
        var named=new NamedParameterJdbcTemplate(jdbc);
        long current=named.queryForObject("WITH "+RecommendationSql.ACCOUNT_CONTEXT+" SELECT count(*) FROM message_metadata m JOIN account_context a ON a.id=m.account_id "+RecommendationSql.JOINS+" WHERE m.account_id=:account AND "+RecommendationSql.CURRENT,params,Long.class);
        assertThat(current).isEqualTo(size);
        long idleStart=System.nanoTime();dashboard.page(account,filter);double idleMs=(System.nanoTime()-idleStart)/1_000_000.;
        System.out.printf(Locale.ROOT,"LABELING_SCALE_IDLE rows=%d page_ms=%.2f%n",size,idleMs);
        if(Boolean.getBoolean("labeling.scale.diagnostic")) {
            named.queryForList("EXPLAIN (ANALYZE,BUFFERS,TIMING OFF) "+dashboard.filtered(account, filter)+" SELECT count(*) FROM filtered",dashboard.parameters(account,filter),String.class).forEach(System.out::println);
        }
        if(Boolean.getBoolean("labeling.scale.hold")) {
            System.out.println("LABELING_FIXTURE_READY "+jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getURL()));
            Thread.sleep(60000); // Explicit diagnostic mode only; keeps this temporary fixture available for read-only profiling.
        }
        assertThat(recommendations.read(account,List.of(id)).getFirst().freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
        var labelOnly=new DashboardFilter("all","","","","UTC",0,"","messages","","","","SCHOOL");
        AtomicBoolean stop=new AtomicBoolean();AtomicInteger composed=new AtomicInteger();var executor=Executors.newSingleThreadExecutor();
        var future=executor.submit(()->{while(!stop.get()) {
            composed.addAndGet(recommendations.processBatch(account,100));
            try {Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        }});
        try {
            for(var measured:List.of(filter,labelOnly)) {
                dashboard.page(account,measured); // Warmup outside the ten measured calls.
                double[] times=new double[10];
                for(int i=0;i<times.length;i++) {long start=System.nanoTime();assertThat(dashboard.page(account,measured).summary()).containsEntry("messages",(long)size);times[i]=(System.nanoTime()-start)/1_000_000.;}
                Arrays.sort(times);System.out.printf(Locale.ROOT,"LABELING_SCALE rows=%d full_cache=true concurrent=true action=%s samples=10 label_filter_p95_ms=%.2f local_compositions=%d%n",size,measured.recommendation(),times[9],composed.get());
                assertThat(times[9]).isLessThan(2000);assertThat(composed.get()).isPositive();
            }
        } finally {stop.set(true);future.get(20,TimeUnit.SECONDS);executor.shutdownNow();}
        verify(jev,times(1)).submitRecommendation(any());assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }
}
