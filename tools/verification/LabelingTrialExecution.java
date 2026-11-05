import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tidymail.InboxCleanerApplication;
import com.tidymail.service.*;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import static com.tidymail.service.RecommendationSemanticContract.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact approved scope-05 trial driver. Uses production queues, never mailbox mutation APIs. */
public final class LabelingTrialExecution {
 @Configuration(proxyBeanMethods=false)
 @EnableAutoConfiguration(excludeName={"org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
  "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration","org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
  "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration","org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
 static class ReadConfig {}
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 static final BigDecimal CAP=new BigDecimal("0.20"),SHARED=new BigDecimal("0.25");
 static final RecommendationSemanticContract CONTRACT=new RecommendationSemanticContract(JSON);
 static Path scope,output,ledgerPath;
 static JsonNode approved;
 public static void main(String[] args) throws Exception {
  System.setProperty("spring.devtools.restart.enabled","false");
  if(args.length!=3)throw new IllegalArgumentException("Use explicit mode scope.json approved-run-directory");
  scope=Path.of(args[1]);output=Path.of(args[2]);approved=JSON.readTree(scope.toFile());validate();
  ledgerPath=Path.of("docs/verification/2026-11-02/labeling-pipeline-2026-11-03-run-01/ledger.json");
  switch(args[0]) {
   case "--offline-check" -> System.out.println("Exact 12-message header/MIME scope and disclosure caps verified; no I/O.");
   case "--preflight-local-only" -> preflight();
   case "--audit-read-only" -> audit();
   case "--prepare-runtime" -> runtime(false);
   case "--execute-reviewed" -> runtime(true);
   default -> throw new IllegalArgumentException("Unknown explicit mode");
  }
 }
 static void validate() {
  if(approved.path("samples").size()!=12 || approved.path("gmailMaxRequests").asInt()!=12 || approved.path("jevMaxRequests").asInt()!=12
   || approved.path("gmailMaxTotalResponseBytes").asLong()!=8388608 || approved.path("maxIncrementalSpend").decimalValue().compareTo(CAP)!=0
   || !approved.path("contract").asText().equals(VERSION) || approved.path("gaps").size()!=0)throw new IllegalArgumentException("Approved scope differs");
  Set<String> ids=new HashSet<>();
  for(var sample:approved.path("samples")) {
   if(!ids.add(sample.path("message").asText()) || sample.path("request").path("questions").size()!=12
    || !CalendarEvidenceParser.hash(sample.path("request").toString()).equals(sample.path("request_hash").asText()))throw new IllegalArgumentException("Frozen request mismatch");
   var state=sample.path("request").path("state").path("email_0");
   Set<String> fields=new HashSet<>();state.fieldNames().forEachRemaining(fields::add);
   if(!fields.equals(Set.of("address","sender_name","subject","is_reply","has_unsubscribe","gmail_categories_known","gmail_categories","relationship"))
    || !state.path("relationship").equals(normalize(JSON.valueToTree(CorrespondentHistory.Context.unknown()))))throw new IllegalArgumentException("Unexpected disclosure");
  }
 }
 static JsonNode normalize(JsonNode value){try{return JSON.readTree(value.toString());}catch(Exception e){throw new IllegalArgumentException("Invalid JSON");}}
 static long account(){return approved.path("account").asLong();}
 static List<UUID> ids(){var ids=new ArrayList<UUID>();approved.path("samples").forEach(s->ids.add(UUID.fromString(s.path("message").asText())));return ids;}
 static BigDecimal previousSpend() throws Exception {
  BigDecimal total=BigDecimal.ZERO;
  try(var paths=Files.walk(Path.of("docs/verification"),7)) {
   for(var p:paths.filter(p->p.getFileName().toString().equals("ledger.json") && !p.equals(ledgerPath)).toList()) {
    var l=JSON.readTree(p.toFile());var amount=l.path("reported_usd");
    if(!amount.isNumber() || amount.decimalValue().signum()<0)throw new IllegalStateException("Invalid prior experiment ledger");
    total=total.add(amount.decimalValue());
    for(var a:l.path("attempts"))if(Set.of("RESERVED","DISPATCHED","UNKNOWN").contains(a.path("state").asText()))throw new IllegalStateException("Prior experiment liability unresolved");
   }
  }
  if(total.add(CAP).compareTo(SHARED)>0)throw new IllegalStateException("Shared experiment allowance cannot cover approved incremental cap");
  return total;
 }
 static void quiet(JdbcTemplate jdbc) {
  if(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs WHERE status IN ('QUEUED','RUNNING') OR worker_id IS NOT NULL",Long.class)!=0
   || jdbc.queryForObject("SELECT count(*) FROM dashboard_classification_jobs WHERE status IN ('QUEUED','RUNNING')",Long.class)!=0
   || jdbc.queryForObject("SELECT count(*) FROM classification_attempts WHERE state IN ('DISPATCHED','UNKNOWN')",Long.class)!=0)
   throw new IllegalStateException("Unrelated live work or unresolved charge exists");
 }
 static void headers(JdbcTemplate jdbc) {
  for(var sample:approved.path("samples")) {
   var rows=jdbc.queryForList("""
    SELECT m.*,cardinality(m.references_values)>0 OR cardinality(m.in_reply_to_values)>0 reply,
      cardinality(m.list_unsubscribe_values)>0 unsubscribe
    FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
    WHERE m.account_id=? AND m.dashboard_message_id=? AND m.provider_message_id=? AND m.provider='google' AND
    """+DashboardRepository.RECEIVED,account(),UUID.fromString(sample.path("message").asText()),sample.path("provider_message_id").asText());
   if(rows.size()!=1)throw new IllegalStateException("Reviewed message no longer eligible");
   var r=rows.getFirst();
   try {
    var cats=Arrays.asList((String[])((java.sql.Array)r.get("labels")).getArray()).stream().filter(v->Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_UPDATES","CATEGORY_SOCIAL","CATEGORY_FORUMS").contains(v)).toList();
    var e=new Evidence(UUID.fromString(sample.path("message").asText()),"LOCAL_REVIEW_DRAFT",(String)r.get("dashboard_from_address"),(String)r.get("from_display_name"),(String)r.get("subject_header"),
     (Boolean)r.get("reply"),(Boolean)r.get("unsubscribe"),cats,(Boolean)r.get("label_evidence_known"),new SemanticQualificationContract.Text("",SemanticQualificationContract.State.UNKNOWN),CorrespondentHistory.Context.unknown());
    if(!JSON.readTree(CONTRACT.request(List.of(e),Variant.HEADERS).toString()).equals(sample.path("request")))throw new IllegalStateException("Reviewed headers changed; no replacement permitted");
   }catch(java.sql.SQLException|java.io.IOException failure){throw new IllegalStateException("Could not verify exact headers");}
  }
 }
 static ObjectNode invariant(JdbcTemplate jdbc) {
  var result=JSON.createObjectNode();
  result.set("mailbox_and_legacy",JSON.valueToTree(jdbc.queryForList("""
   SELECT dashboard_message_id,labels,unread,classification_category,classification_override,classification_kind,classification_stream,
     classification_confidence,classification_hash,classification_evidence_hash,classification_version,classified_at
   FROM message_metadata WHERE account_id=? ORDER BY id
   """,account()).stream().map(r->{var out=new TreeMap<String,Object>();r.forEach((k,v)->{try{out.put(k,v instanceof java.sql.Array a?Arrays.asList((String[])a.getArray()):v);}catch(Exception e){throw new IllegalStateException();}});return out;}).toList()));
  result.put("cleanup_jobs",jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class));
  return result;
 }
 static void preflight() throws Exception {
  if(Files.exists(output.resolve("preflight.json")))throw new IllegalStateException("Preflight output already exists");
  try(var ctx=new SpringApplicationBuilder(ReadConfig.class).web(WebApplicationType.NONE).run("--spring.main.banner-mode=off","--logging.level.root=OFF","--spring.sql.init.mode=never",
    "--spring.datasource.hikari.read-only=true","--spring.datasource.hikari.maximum-pool-size=1","--spring.datasource.hikari.minimum-idle=0","--spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only=on")) {
   var jdbc=ctx.getBean(JdbcTemplate.class);if(!jdbc.queryForObject("SELECT current_setting('transaction_read_only')='on'",Boolean.class))throw new IllegalStateException("Read-only preflight required");
   quiet(jdbc);headers(jdbc);BigDecimal prior=previousSpend();
   var jev=new JevClassifier(ctx.getEnvironment().getProperty("app.classification.api-key",""),JSON);if(!jev.configured())throw new IllegalStateException("Configured key unavailable");
   writeNew(output.resolve("pricing.json"),jev.recommendationPricing());
   var p=JSON.createObjectNode().put("scope_sha256",CalendarEvidenceParser.hash(Files.readString(scope))).put("prior_experiment_reported_usd",prior).put("incremental_cap_usd",CAP)
    .put("headers_exact",true).put("gmail_calls",0).put("model_calls",0).put("retained_writes",0).put("version",jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class));
   writeNew(output.resolve("preflight.json"),p);writeNew(output.resolve("invariant-before.json"),invariant(jdbc));
   System.out.println("Read-only preflight passed: exact 12-message payloads, verified pricing, prior experiment spend="+prior+"; Gmail/model calls=0.");
  }
 }
 static void audit() throws Exception {
  try(var ctx=new SpringApplicationBuilder(ReadConfig.class).web(WebApplicationType.NONE).run("--spring.main.banner-mode=off","--logging.level.root=OFF","--spring.sql.init.mode=never",
   "--spring.datasource.hikari.read-only=true","--spring.datasource.hikari.maximum-pool-size=1","--spring.datasource.hikari.minimum-idle=0","--spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only=on")) {
   var jdbc=ctx.getBean(JdbcTemplate.class);quiet(jdbc);headers(jdbc);
   if(!JSON.readTree(output.resolve("invariant-before.json").toFile()).equals(normalize(invariant(jdbc))))throw new IllegalStateException("Mailbox/legacy invariant mismatch");
   var ledger=JSON.readTree(ledgerPath.toFile());BigDecimal sum=BigDecimal.ZERO;int count=0;
   for(var a:ledger.path("attempts")) {
    var rows=jdbc.queryForList("SELECT state,cost FROM classification_attempts WHERE id=?",UUID.fromString(a.path("id").asText()));
    if(rows.size()!=1 || !rows.getFirst().get("state").equals("REPORTED") || ((BigDecimal)rows.getFirst().get("cost")).compareTo(a.path("cost").decimalValue())!=0)throw new IllegalStateException("Independent charge mismatch");
    sum=sum.add((BigDecimal)rows.getFirst().get("cost"));count++;
   }
   if(count!=12 || sum.compareTo(ledger.path("reported_usd").decimalValue())!=0 || sum.compareTo(CAP)>0)throw new IllegalStateException("Scope accounting mismatch");
   var budget=jdbc.queryForMap("SELECT reported,labeling_reported FROM classification_budget WHERE id=1");
   if(((BigDecimal)budget.get("labeling_reported")).compareTo(sum)!=0)throw new IllegalStateException("New-route budget mismatch");
   var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);var repo=new DashboardRepository(named);
   var filters=JSON.createObjectNode();
   for(String label:List.of("SCHOOL","HEALTHCARE","SHIPPING","ORDER","BILL","STATEMENT","CALENDAR_INVITE","RECEIPT","MARKETING","SOCIAL","SECURITY"))
    filters.put(label,((Number)repo.page(account(),new DashboardFilter("all","","","","UTC",0,"","messages","","","",label)).summary().get("messages")).longValue());
   var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ctx.getBean(javax.sql.DataSource.class)));
   var rec=new RecommendationService(jdbc,JSON,tx);
   var views=rec.read(account(),ids());if(views.stream().anyMatch(v->v.freshness()!=RecommendationService.Freshness.CURRENT))throw new IllegalStateException("Saved recommendations stale");
   var models=new TreeSet<String>();var providers=new TreeSet<String>();
   for(String raw:jdbc.queryForList("SELECT response::text FROM labeling_work WHERE response IS NOT NULL",String.class)){var response=JSON.readTree(raw);models.add(response.path("model").asText());providers.add(response.path("provider").asText());}
   var audit=JSON.createObjectNode().put("status","COMPLETE").put("attempts_independently_matched",count).put("incremental_cost_usd",sum)
    .put("cumulative_experiment_usd",previousSpend().add(sum)).put("current_recommendations",views.size()).put("mailbox_legacy_invariant_unchanged",true).put("mailbox_mutation_requests",0);
   audit.set("providers",JSON.valueToTree(providers));audit.set("models",JSON.valueToTree(models));audit.set("filter_counts",filters);
   audit.set("scope_counters",JSON.valueToTree(jdbc.queryForList("SELECT kind,paused,mime_requests,mime_bytes FROM labeling_scopes ORDER BY created_at")));
   writeNew(output.resolve("independent-audit.json"),audit);writeNew(output.resolve("views.json"),JSON.valueToTree(views));
   System.out.println("Independent audit passed: "+count+" charges matched; current recommendations="+views.size()+"; cost="+sum+"; filter counts="+filters);
  }
 }
 static void runtime(boolean execute) throws Exception {
  var preflight=JSON.readTree(output.resolve("preflight.json").toFile());if(!preflight.path("scope_sha256").asText().equals(CalendarEvidenceParser.hash(Files.readString(scope))))throw new IllegalStateException("Approved scope changed");
  if(execute && Files.exists(output.resolve("execution-intent.json")))throw new IllegalStateException("Execution replay refused; audit existing durable work");
  if(!execute && Files.exists(output.resolve("runtime-scopes.json")))throw new IllegalStateException("Runtime scope preparation replay refused");
  try(var ctx=new SpringApplicationBuilder(InboxCleanerApplication.class).web(WebApplicationType.SERVLET).run("--server.port=0","--spring.main.banner-mode=off","--spring.task.scheduling.enabled=false",
   "--app.classification.benchmark.enabled=false","--app.classification.worker.enabled=false","--app.labeling.worker.enabled=false","--app.recommendations.worker.enabled=false",
   "--logging.level.root=ERROR","--logging.level.com.tidymail=ERROR","--logging.level.org.springframework.web.client=OFF")) {
   var jdbc=ctx.getBean(JdbcTemplate.class);quiet(jdbc);headers(jdbc);var pipeline=ctx.getBean(LabelingPipeline.class);
   if(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)!=37)throw new IllegalStateException("Expected V37");
   if(!execute) {
    LabelingPipeline.Prepared mime,jev;
    if(jdbc.queryForObject("SELECT count(*) FROM labeling_scopes",Long.class)==0) {
     mime=pipeline.prepare(account(),ids(),"MIME",Variant.HEADERS,BigDecimal.ZERO);
     jev=pipeline.prepare(account(),ids(),"JEV",Variant.HEADERS,CAP);
    } else {
     if(jdbc.queryForObject("SELECT count(*) FROM labeling_scopes WHERE NOT authorized AND NOT paused AND mime_requests=0 AND account_id=?",Long.class,account())!=2
       || jdbc.queryForObject("SELECT count(*) FROM labeling_work WHERE state='PENDING' AND attempt_id IS NULL AND error_code IS NULL",Long.class)!=24
       || jdbc.queryForObject("SELECT count(*) FROM labeling_exposures",Long.class)!=0)throw new IllegalStateException("Existing work cannot be prepared again");
     mime=loadPrepared(jdbc,"MIME");jev=loadPrepared(jdbc,"JEV");
    }
    verifyPrepared(jev.manifest());
    var frozen=JSON.createObjectNode();frozen.set("mime",JSON.valueToTree(mime));frozen.set("jev",JSON.valueToTree(jev));
    writeNew(output.resolve("runtime-scopes.json"),frozen);writeNew(output.resolve("invariant-after-migration.json"),invariant(jdbc));
    if(!JSON.readTree(output.resolve("invariant-before.json").toFile()).equals(normalize(invariant(jdbc))))throw new IllegalStateException("Migration changed mailbox/legacy invariants");
    System.out.println("V37 migrated and exact runtime manifests frozen; scopes remain unauthorized; no Gmail/model calls.");return;
   }
   var frozen=JSON.readTree(output.resolve("runtime-scopes.json").toFile());verifyPrepared(frozen.path("jev").path("manifest"));
   for(String kind:List.of("mime","jev")) {
    var f=frozen.path(kind);var db=jdbc.queryForMap("SELECT manifest_hash,manifest::text FROM labeling_scopes WHERE id=? AND account_id=?",UUID.fromString(f.path("id").asText()),account());
    if(!f.path("hash").asText().equals(db.get("manifest_hash")) || !f.path("manifest").equals(JSON.readTree(db.get("manifest").toString())))throw new IllegalStateException("Persisted manifest differs");
   }
   previousSpend();
   try(var channel=FileChannel.open(Path.of("docs/verification/2026-11-02/phase3-pilot.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
    if(lock==null)throw new IllegalStateException("Another experiment is active");
    Files.createDirectory(ledgerPath.getParent());writeNew(ledgerPath,JSON.createObjectNode().put("status","PREPARING").put("reported_usd",BigDecimal.ZERO).put("scope_sha256",preflight.path("scope_sha256").asText()).set("attempts",JSON.createArrayNode()));
    writeNew(output.resolve("execution-intent.json"),JSON.createObjectNode().put("authorization","User approved exact scope-05 with sure").put("at",Instant.now().toString()).put("scope_sha256",preflight.path("scope_sha256").asText()));
    for(String kind:List.of("mime","jev")){var f=frozen.path(kind);pipeline.authorize(account(),UUID.fromString(f.path("id").asText()),f.path("hash").asText());}
    try {
     for(int i=0;i<24;i++) {
      var totals=jdbc.queryForMap("SELECT count(*) FILTER(WHERE state='DONE') done,count(*) FILTER(WHERE error_code IS NOT NULL) errors FROM labeling_work");
      if(((Number)totals.get("done")).intValue()==24)break;
      if(((Number)totals.get("errors")).intValue()>0)throw new IllegalStateException("A scoped check needs review; no automatic replay");
      BigDecimal paid=jdbc.queryForObject("SELECT COALESCE(sum(cost),0) FROM classification_attempts WHERE id IN (SELECT attempt_id FROM labeling_exposures)",BigDecimal.class);
      if(preflight.path("prior_experiment_reported_usd").decimalValue().add(paid).add(LabelingPipeline.RESERVATION).compareTo(SHARED)>0)throw new IllegalStateException("Shared experiment cap cannot cover reservation");
      if(!pipeline.runOnce())throw new IllegalStateException("No runnable reviewed work");
      checkpoint(jdbc,frozen,"RUNNING");System.out.println("Scoped processing step "+(i+1)+" settled.");
     }
     if(jdbc.queryForObject("SELECT count(*) FROM labeling_work WHERE state='DONE'",Integer.class)!=24)throw new IllegalStateException("Reviewed scope incomplete");
     if(!JSON.readTree(output.resolve("invariant-before.json").toFile()).equals(normalize(invariant(jdbc))))throw new IllegalStateException("Mailbox/legacy invariant changed");
     checkpoint(jdbc,frozen,"COMPLETE");System.out.println("Reviewed scope complete: twelve MIME checks and twelve settled label requests; no mailbox mutations.");
    } finally {for(String kind:List.of("mime","jev"))pipeline.pause(account(),UUID.fromString(frozen.path(kind).path("id").asText()));}
   }
  }
 }
 static LabelingPipeline.Prepared loadPrepared(JdbcTemplate jdbc,String kind) throws Exception {
  var row=jdbc.queryForMap("SELECT id,manifest_hash,manifest::text manifest FROM labeling_scopes WHERE account_id=? AND kind=?",account(),kind);
  var manifest=JSON.readTree(row.get("manifest").toString());var actual=new HashSet<String>();manifest.path("messages").forEach(m->actual.add(m.path("message").asText()));
  if(!actual.equals(new HashSet<>(ids().stream().map(UUID::toString).toList())))throw new IllegalStateException("Existing IDs differ from approval");
  return new LabelingPipeline.Prepared((UUID)row.get("id"),row.get("manifest_hash").toString(),manifest);
 }
 static void verifyPrepared(JsonNode manifest) {
  if(manifest.path("messages").size()!=12 || manifest.path("maxRequests").asInt()!=12 || new BigDecimal(manifest.path("maxSpend").asText()).compareTo(CAP)!=0)throw new IllegalStateException("Runtime bounds differ");
  for(var m:manifest.path("messages")) {
   JsonNode expected=null;for(var s:approved.path("samples"))if(s.path("message").asText().equals(m.path("message").asText()))expected=s.path("request");
   if(expected==null || !expected.equals(normalize(m.path("request"))))throw new IllegalStateException("Runtime request differs from approved payload");
  }
 }
 static void checkpoint(JdbcTemplate jdbc,JsonNode frozen,String status) throws Exception {
  var attempts=jdbc.queryForList("SELECT a.id,a.state,a.cost,e.message_id,e.reserved FROM classification_attempts a JOIN labeling_exposures e ON e.attempt_id=a.id ORDER BY a.dispatched_at");
  BigDecimal cost=jdbc.queryForObject("SELECT COALESCE(sum(a.cost),0) FROM classification_attempts a JOIN labeling_exposures e ON e.attempt_id=a.id",BigDecimal.class);
  var ledger=JSON.createObjectNode().put("status",status).put("reported_usd",cost).put("scope_sha256",CalendarEvidenceParser.hash(Files.readString(scope))).put("updated_at",Instant.now().toString());
  ledger.set("attempts",JSON.valueToTree(attempts));replace(ledgerPath,ledger);
  var report=JSON.createObjectNode().put("status",status).put("incremental_cost_usd",cost);
  report.set("scopes",JSON.valueToTree(jdbc.queryForList("SELECT id,kind,authorized,paused,mime_requests,mime_bytes FROM labeling_scopes ORDER BY created_at")));
  report.set("work",JSON.valueToTree(jdbc.queryForList("SELECT message_id,state,error_code,attempt_id FROM labeling_work ORDER BY message_id")));
  var rec=ctxRecommendations(jdbc);report.set("recommendations",JSON.valueToTree(rec));
  replace(output.resolve("result.json"),report);
  replace(output.resolve("responses.json"),JSON.valueToTree(jdbc.queryForList("SELECT message_id,response::text response FROM labeling_work WHERE response IS NOT NULL ORDER BY message_id")));
 }
 static List<Map<String,Object>> ctxRecommendations(JdbcTemplate jdbc){return jdbc.queryForList("SELECT message_id,result::text,assessment::text FROM message_recommendations WHERE account_id=? AND message_id IN (SELECT message_id FROM labeling_work) ORDER BY message_id",account());}
 static void writeNew(Path p,JsonNode node) throws Exception {Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node),StandardOpenOption.CREATE_NEW);}
 static void replace(Path p,JsonNode node) throws Exception {Path tmp=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(tmp,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
}
