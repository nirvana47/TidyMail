package com.tidymail.service;

import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.JevClassifier.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit one-shot benchmark in the approved running app, using its existing provider configuration.
 * No classifications or cleanup targets are overwritten. Reports contain aggregate metrics only. */
@Component
@ConditionalOnProperty(name="app.classification.benchmark.enabled",havingValue="true")
public class ClassificationBenchmark implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ClassificationBudget budget;
    private final JevClassifier classifier;
    private final com.tidymail.job.DashboardClassificationWorker worker;
    private final DashboardClassificationService classification;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> observations=new java.util.concurrent.ConcurrentLinkedQueue<>();
    public ClassificationBenchmark(JdbcTemplate jdbc,TransactionTemplate tx,ClassificationBudget budget,JevClassifier classifier,com.tidymail.job.DashboardClassificationWorker worker,DashboardClassificationService classification){this.jdbc=jdbc;this.tx=tx;this.budget=budget;this.classifier=classifier;this.worker=worker;this.classification=classification;}
    private record Sample(Evidence evidence,String category,String kind) {}
    private record Variant(String name,int batch,int concurrency,boolean compact) {}
    private record Measurement(int messages,int failed,int uncertain,int labeled,int correct,double seconds,long requestMs,long databaseMs,BigDecimal cost) {}
    @Override public void run(ApplicationArguments args) throws Exception {
        if(!classifier.configured()){worker.finishBenchmark();return;}
        Long account=jdbc.queryForObject("SELECT min(id) FROM user_accounts",Long.class);
        if(account==null){worker.finishBenchmark();return;}
        List<Sample> sample=samples(account);
        StringBuilder report=new StringBuilder("# Classification benchmark\n\nActual Jev requests, "+java.time.Instant.now()+". Bounded replay of "+sample.size()+" metadata samples per variant; includes labeled synthetic cases and retained-mail metadata. No saved classifications are overwritten. No automatic budget continuation.\n\n| Variant | Messages | Messages/min | Mean request ms | Database ms | Reported $/1000 | Failures | Uncertain | Labeled category+kind correct |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        var measurements=new LinkedHashMap<String,Measurement>();
        for(var variant:List.of(new Variant("baseline",5,1,false),new Variant("short instructions",5,1,true),new Variant("batch 10",10,1,false),new Variant("parallel 2",5,2,false),new Variant("combined",10,2,true))) {
            Measurement m=measure(account,sample,variant);measurements.put(variant.name(),m);
            report.append(String.format(Locale.ROOT,"| %s | %d | %.1f | %d | %d | %s | %d | %d | %d/%d |%n",variant.name(),m.messages(),m.messages()*60/Math.max(.001,m.seconds()),m.requestMs()/Math.max(1,(m.messages()+variant.batch()-1)/variant.batch()),m.databaseMs(),m.messages()==0?"—":m.cost().multiply(new BigDecimal("1000")).divide(BigDecimal.valueOf(m.messages()),8,java.math.RoundingMode.HALF_UP),m.failed(),m.uncertain(),m.correct(),m.labeled()));
            Files.writeString(Path.of("docs/CLASSIFICATION-BENCHMARK.md"),report.toString());
            if(budget.view(account).paused())break;
        }
        var baseline=measurements.get("baseline");var larger=measurements.get("batch 10");
        boolean adopt=larger!=null && baseline!=null && larger.failed()==0 && larger.messages()==sample.size()
            && larger.correct()>=baseline.correct() && larger.uncertain()<=baseline.uncertain()
            && larger.cost().compareTo(baseline.cost().multiply(new BigDecimal("1.15")))<=0
            && larger.seconds()<baseline.seconds()*.95;
        if(adopt)classification.benchmarkBatchSize(10);
        report.append("\nProduction choice for this process: "+(adopt?"10":"5")+" messages/request, one request in flight, original three-dimension instructions and metadata-v1 semantics. A transport-only change does not invalidate saved classifications. Shorter instructions and parallel normal dispatch are not adopted.\n");
        report.append("\nSynthetic quality observations (case numbers follow the benchmark fixture; no retained-mail contents):\n");
        for(String observation:observations)report.append(observation).append("\n");
        report.append("\nSynthetic labels assess message purpose and sender kind; they do not establish real-mail accuracy or stream/grouping accuracy. Review ambiguous outputs before adopting semantic prompt changes. Times include provider and database overhead; reported costs exclude unknown charges. Parallel requests do not imply lower cost. Production concurrency remains one until distinct normal batch claims are implemented.\n");
        Files.writeString(Path.of("docs/CLASSIFICATION-BENCHMARK.md"),report.toString());
        if(!budget.view(account).paused()) {
            UUID validation=classification.startBenchmarkSample(account,40);
            if(validation!=null) {
                long began=System.nanoTime();
                for(int i=0;i<20 && !budget.view(account).paused();i++) {
                    if(!classification.runOnce())break;
                    String state=jdbc.queryForObject("SELECT status FROM dashboard_classification_jobs WHERE public_id=?",String.class,validation);
                    if(!Set.of("QUEUED","RUNNING").contains(state))break;
                }
                double seconds=(System.nanoTime()-began)/1e9;
                var result=jdbc.queryForMap("SELECT total,done,skipped,cost_usd,status FROM dashboard_classification_jobs WHERE public_id=?",validation);
                report.append("\nEnd-to-end retained-mail validation (new/changed evidence only; includes classification storage and bounded grouping): "+result.get("done")+" saved, "+result.get("skipped")+" skipped, "+String.format(Locale.ROOT,"%.2f",seconds)+" seconds, reported $"+result.get("cost_usd")+", state "+result.get("status")+".\n");
                Files.writeString(Path.of("docs/CLASSIFICATION-BENCHMARK.md"),report.toString());
            }
        }
        worker.finishBenchmark();
    }
    private Measurement measure(long account,List<Sample> samples,Variant variant) throws Exception {
        UUID job=UUID.randomUUID();jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status,error_message) VALUES(?,?,'COMPLETE','Benchmark only; saved classifications unchanged')",job,account);
        long began=System.nanoTime();List<Future<Measurement>> futures=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(variant.concurrency())) {
            for(int i=0;i<samples.size();i+=variant.batch()) {
                var batch=List.copyOf(samples.subList(i,Math.min(samples.size(),i+variant.batch())));
                futures.add(pool.submit(()->request(account,job,batch,variant)));
            }
            int count=0,failed=0,uncertain=0,labeled=0,correct=0;long request=0,database=0;BigDecimal cost=BigDecimal.ZERO;
            for(var f:futures){var m=f.get();count+=m.messages();failed+=m.failed();uncertain+=m.uncertain();labeled+=m.labeled();correct+=m.correct();request+=m.requestMs();database+=m.databaseMs();cost=cost.add(m.cost());}
            return new Measurement(count,failed,uncertain,labeled,correct,(System.nanoTime()-began)/1e9,request,database,cost);
        }
    }
    private Measurement request(long account,UUID job,List<Sample> samples,Variant variant) {
        UUID attempt=UUID.randomUUID();long begin=System.nanoTime();
        boolean dispatched=Boolean.TRUE.equals(tx.execute(s->{
            budget.recover();var v=budget.view(account);
            if(v.paused() || v.inFlight()>=variant.concurrency())return false;
            budget.dispatch(attempt,job,account,samples.size());
            jdbc.update("UPDATE classification_attempts SET benchmark_variant=? WHERE id=?",variant.name(),attempt);return true;
        }));
        if(!dispatched)return new Measurement(0,0,0,0,0,0,0,0,BigDecimal.ZERO);
        long sent=System.nanoTime(),received=sent;BigDecimal cost=BigDecimal.ZERO;int failed=0,uncertain=0,labeled=0,correct=0;
        try {
            var result=classifier.classify(samples.stream().map(Sample::evidence).toList(),variant.compact());received=System.nanoTime();
            cost=result.cost();budget.report(attempt,cost);
            for(int i=0;i<samples.size();i++) {
                var d=result.decisions().get(i);var sample=samples.get(i);
                if(d.category().equals("UNCERTAIN") || d.kind().equals("UNKNOWN"))uncertain++;
                if(sample.category()!=null){labeled++;if(sample.category().equals(d.category()) && sample.kind().equals(d.kind()))correct++;
                    else observations.add("- "+variant.name()+": expected "+sample.category()+"/"+sample.kind()+"; returned "+d.category()+"/"+d.kind()+" (service "+d.stream()+").");}
            }
        }catch(Unavailable safe){received=System.nanoTime();failed++;if(safe.reportedCost()!=null){cost=safe.reportedCost();budget.report(attempt,cost);}else budget.unknown(attempt);}
        catch(RuntimeException suppressed){received=System.nanoTime();failed++;budget.unknown(attempt);}
        long request=(received-sent)/1_000_000,database=(sent-begin+System.nanoTime()-received)/1_000_000;
        budget.metrics(attempt,request,database,uncertain);
        return new Measurement(samples.size(),failed,uncertain,labeled,correct,0,request,database,cost);
    }
    private List<Sample> samples(long account) {
        List<Sample> samples=new ArrayList<>();
        // Deliberately labeled cases, including work correspondence, relay identities, mixed-purpose retail and unfamiliar service.
        String[][] labeled={
            {"alex@gmail.com","Alex","Dinner on Saturday?","PERSONAL","PERSON"},
            {"sam@gene.com","Sam","Can we review the draft together tomorrow?","PERSONAL","PERSON"},
            {"orders@amazon.com","Amazon","Your order confirmation and receipt","RECEIPT","AUTOMATED"},
            {"offers@amazon.com","Amazon","Save 30% in today's sale","MARKETING","AUTOMATED"},
            {"security@google.com","Google","Security alert: new sign-in","SECURITY","AUTOMATED"},
            {"news@substack.com","Weekly Journal","Your weekly editorial newsletter","NEWSLETTER","AUTOMATED"},
            {"opaque1@txt.voice.google.com","Alex","New text message: are you coming tonight?","PERSONAL","RELAY"},
            {"opaque2@txt.voice.google.com","Alex","New text message: see you at six","PERSONAL","RELAY"},
            {"alerts@chase.com","Chase","Your monthly statement is ready","NOTIFICATION","AUTOMATED"},
            {"billing@unknown-service.example","Example Service","Payment received: invoice 204","RECEIPT","AUTOMATED"},
            {"noreply@github.com","GitHub","Your workflow run completed","NOTIFICATION","AUTOMATED"},
            {"device-support@google.com","Google","Your device support request has been updated","NOTIFICATION","AUTOMATED"}
        };
        for(var row:labeled)samples.add(new Sample(new Evidence(UUID.randomUUID(),"benchmark",row[0],row[1],row[2],"ADDRESS",List.of(),row[4].equals("PERSON"),row[3].equals("MARKETING")||row[3].equals("NEWSLETTER")),row[3],row[4]));
        // Sample several evidence strata. Private fields remain only in memory and the authorized provider request.
        samples.addAll(jdbc.query("""
            SELECT * FROM (SELECT m.*,row_number() OVER(PARTITION BY COALESCE(classification_kind,'UNCLASSIFIED'),COALESCE(classification_category,'UNCLASSIFIED') ORDER BY dashboard_message_id) position
              FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
              WHERE m.account_id=? AND m.dashboard_from_address IS NOT NULL AND m.dashboard_unit_kind IN ('ADDRESS','LIST') AND
            """+DashboardRepository.RECEIVED+"""
            ) evidence ORDER BY position,dashboard_message_id LIMIT 28
            """,(r,n)->new Sample(new Evidence(r.getObject("dashboard_message_id",UUID.class),r.getString("classification_evidence_hash"),r.getString("dashboard_from_address"),r.getString("from_display_name"),r.getString("subject_header"),r.getString("dashboard_unit_kind"),Arrays.stream((String[])r.getArray("labels").getArray()).filter(l->Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_UPDATES","CATEGORY_SOCIAL","CATEGORY_FORUMS").contains(l)).toList(),((String[])r.getArray("references_values").getArray()).length>0||((String[])r.getArray("in_reply_to_values").getArray()).length>0,((String[])r.getArray("list_unsubscribe_values").getArray()).length>0),null,null),account));
        return samples;
    }
}
