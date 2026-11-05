import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tidymail.service.*;
import com.tidymail.repository.DashboardRepository;
import static com.tidymail.service.RecommendationSemanticContract.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact local-index-only review packet. No migrations, workers, provider/model clients or retained writes. */
public final class LabelingTrialPreparation {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    static class Config {}
    record Bucket(String name,String predicate){}
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !args[0].equals("--prepare-local-only"))throw new IllegalArgumentException("Use --prepare-local-only NEW_OUTPUT_DIRECTORY");
        System.setProperty("spring.devtools.restart.enabled","false");
        Path output=Path.of(args[1]);if(Files.exists(output))throw new IllegalArgumentException("Never overwrite a review packet");
        var json=new ObjectMapper().findAndRegisterModules();var contract=new RecommendationSemanticContract(json);
        try(var ctx=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.main.banner-mode=off","--logging.level.root=OFF","--spring.sql.init.mode=never",
            "--spring.datasource.hikari.read-only=true","--spring.datasource.hikari.maximum-pool-size=1",
            "--spring.datasource.hikari.minimum-idle=0","--spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only=on")) {
            var jdbc=ctx.getBean(JdbcTemplate.class);
            if(!jdbc.queryForObject("SELECT current_setting('transaction_read_only')='on'",Boolean.class))throw new IllegalStateException("Read-only connection required");
            var accounts=jdbc.queryForList("SELECT id FROM user_accounts WHERE provider='google' AND oauth_status='ACTIVE' ORDER BY id",Long.class);
            if(accounts.size()!=1)throw new IllegalStateException("Exactly one active Google account required for this packet");
            long account=accounts.getFirst();
            var buckets=List.of(
                new Bucket("school_newsletter","m.subject_header ~* 'school newsletter|classroom|school district|PTA|ParentSquare'"),
                new Bucket("provider_newsletter","(m.from_domain ~* '(^|\\.)(kp.org|kaiserpermanente.org|sutterhealth.org|pamf.org|stanfordhealthcare.org)$') AND m.subject_header ~* 'news|tips|health|wellness'"),
                new Bucket("receipt_with_possible_offer","m.subject_header ~* 'order confirmation|your receipt|purchase confirmation'"),
                new Bucket("shipping","m.subject_header ~* 'shipped|shipment|delivered|delivery update'"),
                new Bucket("statement","m.subject_header ~* 'statement'"),
                new Bucket("bill","m.subject_header ~* 'your bill|invoice|payment due|past due'"),
                new Bucket("social_security","m.from_domain ~* 'facebookmail.com|linkedin.com|redditmail.com|nextdoor.com' AND m.subject_header ~* 'security|sign.in|password|login'"),
                new Bucket("human_support","m.subject_header ~* '^re:.*(support|ticket|case)'"),
                new Bucket("automated_support","m.subject_header ~* 'request received|ticket created|case created|thanks for contacting'"),
                new Bucket("contact_calendar_hint","COALESCE(h.sent_count,0)>0 AND m.subject_header ~* 'invitation|updated invitation|canceled event|accepted:'"),
                new Bucket("unclear_forward","m.subject_header ~* '^(fwd?|fw):'"),
                new Bucket("calendar_hint","m.subject_header ~* 'invitation|updated invitation|canceled event'"));
            var samples=json.createArrayNode();var gaps=json.createArrayNode();Set<UUID> seen=new HashSet<>();int inspected=0;
            Path previous=Path.of("docs/verification/2026-11-02/recommendation-snippet-pilot-scope-02/scope.json");
            if(!Files.exists(previous))throw new IllegalStateException("Frozen prior-sample exclusion list required");
            for(var old:json.readTree(previous.toFile()).path("samples"))seen.add(UUID.fromString(old.path("evidence").path("message").asText()));
            for(var bucket:buckets) {
                var rows=jdbc.queryForList("""
                    SELECT m.dashboard_message_id,m.provider_message_id,m.subject_header,m.dashboard_from_address,m.from_display_name,
                      m.label_evidence_known,m.labels,cardinality(m.references_values)>0 OR cardinality(m.in_reply_to_values)>0 reply,
                      cardinality(m.list_unsubscribe_values)>0 unsubscribe,COALESCE(h.sent_count,0) sent,COALESCE(h.received_count,0) received,
                      COALESCE(h.sent_reply_count,0) sent_replies,COALESCE(h.received_reply_count,0) received_replies,
                      h.last_sent_at,h.last_received_at
                    FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                    LEFT JOIN correspondent_summary h ON h.account_id=m.account_id AND h.address=m.dashboard_from_address
                    WHERE m.account_id=? AND m.provider='google' AND m.label_evidence_known AND
                    """+DashboardRepository.RECEIVED+" AND ("+bucket.predicate()+") ORDER BY m.received_at DESC NULLS LAST,m.id DESC LIMIT 8",account);
                inspected+=rows.size();boolean added=false;
                for(var r:rows) {
                    UUID id=(UUID)r.get("dashboard_message_id");if(!seen.add(id))continue;
                    var labels=Arrays.asList((String[])((java.sql.Array)r.get("labels")).getArray()).stream()
                        .filter(v->Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_UPDATES","CATEGORY_SOCIAL","CATEGORY_FORUMS").contains(v)).toList();
                    var evidence=new Evidence(id,"LOCAL_REVIEW_DRAFT",(String)r.get("dashboard_from_address"),(String)r.get("from_display_name"),(String)r.get("subject_header"),
                        (Boolean)r.get("reply"),(Boolean)r.get("unsubscribe"),labels,true,new SemanticQualificationContract.Text("",SemanticQualificationContract.State.UNKNOWN),
                        CorrespondentHistory.Context.unknown());
                    var sample=samples.addObject().put("bucket",bucket.name()).put("message",id.toString()).put("provider_message_id",r.get("provider_message_id").toString());
                    sample.set("request",contract.request(List.of(evidence),Variant.HEADERS));
                    sample.put("request_hash",CalendarEvidenceParser.hash(sample.path("request").toString()));
                    sample.put("expected","UNREVIEWED_SAMPLING_HINT_ONLY");added=true;break;
                }
                if(!added)gaps.add(bucket.name());
            }
            var packet=json.createObjectNode().put("status","PREPARED_NOT_AUTHORIZED").put("account",account).put("preparedAt",Instant.now().toString())
                .put("contract",VERSION).put("destination","https://openrouter.ai/api/alpha/decisions").put("model",JevClassifier.MODEL)
                .put("provider","TypeSafe only; no fallback; zero retention; data collection denied")
                .put("maxIncrementalSpend",new BigDecimal("0.20")).put("sharedNewLabelingAllowance",new BigDecimal("0.20")).put("priorSnippetPilotExcluded",true).put("sharedExperimentCap",new BigDecimal("0.25"))
                .put("reservationPerRequest",LabelingPipeline.RESERVATION).put("pricingStatus","PINNED_UPPER_INPUT_PRICE_REQUIRES_CURRENT_VERIFICATION_BEFORE_AUTHORIZATION")
                .put("gmailMaxRequests",samples.size()).put("gmailFields","id,payload(partId,mimeType,filename,headers(name,value),body(size,attachmentId),parts(...depth 6))")
                .put("gmailMaxResponseBytesPerMessage",1048576).put("gmailMaxTotalResponseBytes",8388608).put("gmailMaxCalendarAttachmentReads",0)
                .put("jevMaxRequests",samples.size()).put("jevBatchSize",1).put("localRowsInspected",inspected)
                .put("scopeNote","Exact development samples. Metadata selection hints are not reviewed truth or an independent accuracy gate. MIME discovery and headers-only labeling are separate approvals. No snippets, bodies, attachments or thread content sent to Jev.")
                .put("dispatchGate","Review exact payloads, verify price and remaining shared experiment allowance, migrate separately, freeze runtime manifest, approve its hash. No dispatch mode in this tool.");
            packet.set("samples",samples);packet.set("gaps",gaps);
            Files.createDirectories(output);json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("scope.json").toFile(),packet);
            String review="# Bounded labeling acceptance trial — prepared, not authorized\n\nExact requests and message identities are in the private `scope.json`. Selection used the local index only.\n\n"
                +"Proposed: "+samples.size()+" projected Gmail MIME reads and at most "+samples.size()+" single-message headers-only Jev requests, with a $0.20 incremental cap inside the $0.25 experiment limit. Each paid request reserves $"+LabelingPipeline.RESERVATION.toPlainString()+" before dispatch; the queue pauses when the remaining cap cannot cover that reservation.\n\n"
                +"Gmail reads have a cumulative 8 MiB cap, including conservative reservations for failed reads. Retries consume the same twelve-request cap. Gmail reads exclude snippet/body data and calendar attachment downloads. Jev gets sender address/name, subject, reply/unsubscribe flags, Gmail categories and an UNKNOWN relationship placeholder. Correspondent counts/dates stay local. Destination: OpenRouter alpha decisions, typesafe/jev-1.14, TypeSafe only, no fallback, zero retention, data collection denied.\n\n"
                +"Review references before scoring School/provider relationship claims and human support. Sampling gaps: "+gaps+". Check labels separately from recommendations. No mailbox actions. Frozen earlier experiments remain untouched.\n\n"
                +"Before dispatch, verify current pricing and shared liabilities and review the final persisted manifest hash. This packet is not authorization and does not start or migrate the installed app.\n";
            Files.writeString(output.resolve("REVIEW.md"),review,StandardOpenOption.CREATE_NEW);
            System.out.println("Prepared "+samples.size()+" exact local samples; Gmail/Jev calls=0; retained writes=0; sampling gaps="+gaps.size());
        }
    }
    static long number(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    static String date(Object value){return value==null?"":((java.sql.Timestamp)value).toInstant().toString();}
}
