import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local-index selection only. No component scan, migrations, crypto, Gmail transport, model or database writes. */
public final class RecommendationPilotPreparation {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    static class Config {}
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final String COLUMNS="""
        SELECT m.account_id,m.provider_message_id,m.dashboard_message_id,m.provider_thread_id,
          m.dashboard_from_address address,m.from_display_name,m.subject_header,m.received_at::text received_at,
          m.unread,m.label_evidence_known,m.size_estimate,array_to_json(m.labels)::text labels_json,
          cardinality(m.in_reply_to_values)>0 OR cardinality(m.references_values)>0 is_reply,
          cardinality(m.list_unsubscribe_values)>0 has_unsubscribe
        FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
        WHERE m.account_id=? AND m.provider='google' AND
        """+DashboardRepository.RECEIVED;
    record Bucket(String name,String predicate,int count) {}
    public static void main(String[] args) {
        System.setProperty("spring.devtools.restart.enabled","false");
        if(args.length!=3 || !args[0].equals("--select-local-only")) throw new IllegalArgumentException("Use --select-local-only reference-manifest output-directory");
        try { run(Path.of(args[1]),Path.of(args[2])); }
        catch(Exception error) { System.err.println("Local preparation failed: "+error.getClass().getSimpleName());System.exit(2); }
    }
    static void run(Path references,Path output) throws Exception {
        if(Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite a pilot packet");
        var reference=JSON.readTree(references.toFile());
        var selected=new LinkedHashMap<String,ObjectNode>();
        int inspected=0;
        try(var ctx=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.main.banner-mode=off","--logging.level.root=OFF","--spring.sql.init.mode=never",
            "--spring.datasource.hikari.read-only=true","--spring.datasource.hikari.maximum-pool-size=1",
            "--spring.datasource.hikari.minimum-idle=0","--spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only=on")) {
            var jdbc=ctx.getBean(JdbcTemplate.class);
            if(!jdbc.queryForObject("SELECT current_setting('transaction_read_only')='on'",Boolean.class)) throw new IllegalStateException("Read-only connection required");
            var accounts=jdbc.queryForList("SELECT id FROM user_accounts WHERE provider='google' AND oauth_status='ACTIVE' ORDER BY id",Long.class);
            if(accounts.size()!=1) throw new IllegalStateException("Pilot requires one explicitly scoped active Google account");
            long account=accounts.getFirst();
            for(var referenceId:List.of("N02","N06")) {
                JsonNode found=null;
                for(var row:reference.path("review_queue")) if(row.path("review_id").asText().equals(referenceId)) found=row;
                if(found==null || found.path("source_message").path("account_id").asLong()!=account) throw new IllegalStateException("Reviewed regression unavailable");
                var rows=jdbc.queryForList(COLUMNS+" AND m.provider_message_id=?",account,found.path("source_message").path("provider_message_id").asText());
                inspected+=rows.size();
                if(rows.size()!=1) throw new IllegalStateException("Reviewed regression no longer eligible");
                add(selected,rows.getFirst(),"reviewed_forward_"+referenceId,found.path("expected"));
            }
            var buckets=List.of(
                new Bucket("recent_unread_statement","m.subject_header ~* 'statement' AND m.label_evidence_known AND m.unread AND m.received_at>=CURRENT_TIMESTAMP-interval '60 days'",1),
                new Bucket("read_or_older_statement","m.subject_header ~* 'statement' AND m.label_evidence_known AND (NOT m.unread OR m.received_at<CURRENT_TIMESTAMP-interval '60 days')",1),
                new Bucket("recent_unread_bill_or_overdue","m.subject_header ~* 'invoice|your bill|bill is|payment due|overdue|past due' AND m.label_evidence_known AND m.unread AND m.received_at>=CURRENT_TIMESTAMP-interval '60 days'",1),
                new Bucket("read_or_older_bill_or_overdue","m.subject_header ~* 'invoice|your bill|bill is|payment due|overdue|past due' AND m.label_evidence_known AND (NOT m.unread OR m.received_at<CURRENT_TIMESTAMP-interval '60 days')",1),
                new Bucket("healthcare_hint","(m.from_domain ~* '(^|\\.)(kp.org|kaiserpermanente.org|sutterhealth.org|pamf.org|stanfordhealthcare.org)$' OR m.subject_header ~* 'visit summary|test results|patient portal|medical appointment')",1),
                new Bucket("school_hint","(m.from_domain ~* '(^|\\.)(parentsquare.com|schoolmessenger.com|sfusd.edu)$' OR m.subject_header ~* 'school newsletter|classroom|school district|PTA')",1),
                new Bucket("routine_promotion_hint","m.subject_header ~* 'sale|save [0-9]|special offer|promotion'",1),
                new Bucket("social_hint","m.from_domain ~* '(^|\\.)(nextdoor.com|quora.com|redditmail.com|facebookmail.com)$'",1),
                new Bucket("receipt_hint","m.subject_header ~* 'order confirmation|purchase confirmation|payment receipt|your receipt'",1),
                new Bucket("security_hint","m.subject_header ~* 'new sign.?in|new login|security alert|password reset|verification code'",1));
            var gaps=JSON.createArrayNode();
            for(var bucket:buckets) {
                var rows=jdbc.queryForList(COLUMNS+" AND ("+bucket.predicate()+") ORDER BY m.received_at DESC NULLS LAST,m.id DESC LIMIT 24",account);
                inspected+=rows.size();int added=0;
                for(var row:rows) if(add(selected,row,bucket.name(),null) && ++added>=bucket.count()) break;
                if(added<bucket.count()) gaps.add(bucket.name());
            }
            var manifest=JSON.createObjectNode();
            manifest.put("schema","recommendation-snippet-development-pilot-v1").put("status","SCOPE_PREPARED_NOT_APPROVED")
                .put("scope","DEVELOPMENT_ONLY").put("prepared_at",Instant.now().toString()).put("account_id",account)
                .put("contract_version",VERSION).put("max_incremental_usd",new BigDecimal("0.20"))
                .put("shared_pilot_allowance_usd",new BigDecimal("0.25")).put("snippet_cap_utf8_bytes",1024)
                .put("planned_gmail_snippet_gets",selected.size()).put("planned_paired_model_requests",2*selected.size())
                .put("batch_size",1).put("local_metadata_rows_inspected",inspected).put("model_calls_made",0)
                .put("gmail_calls_made",0).put("production_adoption_enabled",false).put("body_fallback_enabled",false);
            manifest.set("sampling_gaps",gaps);
            manifest.put("reference_note","Sampling hints are not reference truth. Prior forwarded references are regressions; new action/protected-content references require review. This is not an independent held-back gate.");
            manifest.put("next_step","Review exact scope and cap before dispatch. Acquire only scoped id/snippet fields locally, freeze paired payloads and verify current pricing/shared liabilities before any paid request.");
            manifest.set("thresholds",JSON.valueToTree(Thresholds.diagnostic()));
            manifest.put("max_questions_per_request",1+Property.values().length).put("reservation_bound_usd_at_existing_pinned_price",new BigDecimal("0.143130624"));
            manifest.put("budget_tradeoff","One message per request fits the existing conservative per-question reservation. Verify current endpoint pricing, shared liabilities and checkpoint capacity before dispatch; do not weaken reservation logic to fit a cheaper-looking cap.");
            var sourcePins=manifest.putObject("source_pins");
            for(var path:List.of("src/main/java/com/tidymail/service/RecommendationSemanticContract.java",
                    "src/main/java/com/tidymail/service/MessageRecommendation.java","tools/verification/RecommendationPilotPreparation.java"))
                sourcePins.put(path,CalendarEvidenceParser.hash(Files.readString(Path.of(path))));
            var samples=manifest.putArray("samples");selected.values().forEach(samples::add);
            Files.createDirectory(output);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("scope.json").toFile(),manifest);
            var contract=new RecommendationSemanticContract(JSON);int batch=0;
            var list=new ArrayList<>(selected.values());
            for(int start=0;start<list.size();start++) {
                var evidence=new ArrayList<Evidence>();
                evidence.add(JSON.treeToValue(list.get(start).path("evidence"),Evidence.class));
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("headers-draft-"+batch+".json").toFile(),contract.request(evidence,Variant.HEADERS));
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("snippet-draft-"+batch+".json").toFile(),contract.request(evidence,Variant.SNIPPET));
                batch++;
            }
            var review=new StringBuilder("# Bounded snippet development pilot — scope for review\n\nNo Gmail/model requests made. Up to "+selected.size()+" snippet GETs and "+(2*batch)+" paired model requests; incremental spend capped at $0.20 within the shared $0.25 pilot allowance. Snippets are capped at 1,024 UTF-8 bytes; no full body, attachments or thread content. TypeSafe-only/ZDR; no provider fallback. Existing classifications and mailbox state stay unchanged.\n\nOne message per request is necessary for the existing conservative reservation: 12 questions fit within the conservative $0.143130624 bound at the previously pinned price, before any dispatch. The earlier proposed $0.05 cap/three-message batching was incompatible with that guard. Pricing, shared outstanding liabilities and the deployment checkpoint must be rechecked; this is a maximum allowance, not a claimed price or spend forecast.\n\nHeader/snippet request drafts currently contain UNKNOWN snippets and cannot be presented as a completed comparison. Actual content must be acquired and payloads frozen within this exact reviewed scope.\n\n| ID | Sampling hint | Read state / received | Sender | Subject |\n|---|---|---|---|---|\n");
            for(var s:selected.values()) review.append("| ").append(s.path("review_id").asText()).append(" | ").append(s.path("sampling_hint").asText())
                .append(" | ").append(s.path("metadata").path("unread").asBoolean()?"Unread":"Read").append(" / ").append(cell(s.path("metadata").path("received_at").asText()))
                .append(" | ").append(cell(s.path("metadata").path("address").asText())).append(" | ").append(cell(s.path("metadata").path("subject_header").asText())).append(" |\n");
            review.append("\nHints are not labels. New expected actions and protected-content answers remain unknown until reviewed; existing N02/N06 purpose/security/human answers remain recorded separately. All samples are development/regression evidence, not a fresh held-back set.\n");
            Files.writeString(output.resolve("REVIEW.md"),review.toString());
            System.out.println("Prepared "+selected.size()+" exact-message scope entries; gaps="+gaps.size()+"; local rows inspected="+inspected+"; Gmail/model calls=0.");
        }
    }
    static boolean add(Map<String,ObjectNode> selected,Map<String,Object> metadata,String hint,JsonNode expected) throws Exception {
        String key=metadata.get("account_id")+":"+metadata.get("provider_message_id");if(selected.containsKey(key)) return false;
        var labels=JSON.readTree((String)metadata.get("labels_json"));var categories=new ArrayList<String>();
        boolean known=Boolean.TRUE.equals(metadata.get("label_evidence_known"));
        if(known) for(var label:labels) if(Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_UPDATES","CATEGORY_SOCIAL","CATEGORY_FORUMS").contains(label.asText())) categories.add(label.asText());
        var data=JSON.valueToTree(metadata);String source=CalendarEvidenceParser.hash(data.toString());
        var e=new Evidence((UUID)metadata.get("dashboard_message_id"),source,(String)metadata.get("address"),(String)metadata.get("from_display_name"),
            (String)metadata.get("subject_header"),Boolean.TRUE.equals(metadata.get("is_reply")),Boolean.TRUE.equals(metadata.get("has_unsubscribe")),categories,known,
            new SemanticQualificationContract.Text("",SemanticQualificationContract.State.UNKNOWN),CorrespondentHistory.Context.unknown());
        var row=JSON.createObjectNode();row.put("review_id",String.format(Locale.ROOT,"P%02d",selected.size()+1)).put("sampling_hint",hint).put("metadata_sha256",source);
        row.set("metadata",data);row.set("evidence",JSON.valueToTree(e));row.set("prior_reviewed_reference",expected==null?JSON.nullNode():expected);
        row.putNull("reference_action");row.putNull("reference_healthcare");row.putNull("reference_school");row.putNull("reference_attention_required");
        selected.put(key,row);return true;
    }
    static String cell(String value) {
        return value.replace("\\","\\\\").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("[","\\[").replace("]","\\]").replace("`","\\`").replace("*","\\*")
            .replace("|","\\|").replace("\n"," ").replace("\r"," ");
    }
}
