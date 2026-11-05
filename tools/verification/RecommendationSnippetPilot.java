package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;
import static com.tidymail.service.RecommendationSemanticContract.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.tidymail.config.OAuthProviderAvailability;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.security.crypto.*;
import com.tidymail.mail.EmailSnippet;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.transaction.support.TransactionTemplate;

/** One explicitly reviewed development run. No component scan, migration, category write or mailbox mutation. */
public final class RecommendationSnippetPilot {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    @Import({CryptoProperties.class,CryptoUtil.class,OAuthProviderAvailability.class})
    static class Config {}
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final RecommendationSemanticContract CONTRACT=new RecommendationSemanticContract(JSON);
    static final BigDecimal CAP=new BigDecimal("0.20"),SHARED=new BigDecimal("0.25");
    static final String METADATA="""
        SELECT m.account_id,m.provider_message_id,m.dashboard_message_id,m.provider_thread_id,
          m.dashboard_from_address address,m.from_display_name,m.subject_header,m.received_at::text received_at,
          m.unread,m.label_evidence_known,m.size_estimate,array_to_json(m.labels)::text labels_json,
          cardinality(m.in_reply_to_values)>0 OR cardinality(m.references_values)>0 is_reply,
          cardinality(m.list_unsubscribe_values)>0 has_unsubscribe
        FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
        WHERE m.account_id=? AND m.provider='google' AND
        """+DashboardRepository.RECEIVED+" AND m.provider_message_id=?";

    public static void main(String[] args) throws Exception {
        System.setProperty("spring.devtools.restart.enabled","false");
        if(args.length<2)throw new IllegalArgumentException("Explicit mode and reviewed scope required");
        Path scope=Path.of(args[1]);var manifest=verified(scope);
        if(args[0].equals("--offline-check") && args.length==2){offline(manifest);return;}
        if(args.length!=3)throw new IllegalArgumentException("Explicit output directory required");
        switch(args[0]) {
            case "--collect-reviewed"->collect(scope,manifest,Path.of(args[2]));
            case "--paid-reviewed"->paid(scope,manifest,Path.of(args[2]));
            case "--audit-read-only"->audit(scope,manifest,Path.of(args[2]));
            default->throw new IllegalArgumentException("Unknown mode");
        }
    }
    static JsonNode verified(Path scope) throws Exception {
        var m=JSON.readTree(scope.toFile());
        if(!m.path("schema").asText().equals("recommendation-snippet-development-pilot-v1")
                || !m.path("scope").asText().equals("DEVELOPMENT_ONLY") || m.path("samples").size()!=11
                || m.path("batch_size").asInt()!=1 || m.path("planned_paired_model_requests").asInt()!=22
                || m.path("max_incremental_usd").decimalValue().compareTo(CAP)!=0
                || m.path("shared_pilot_allowance_usd").decimalValue().compareTo(SHARED)!=0
                || !m.path("contract_version").asText().equals(RecommendationSemanticContract.VERSION))throw new IllegalArgumentException("Reviewed scope mismatch");
        var ids=new HashSet<String>();
        for(var s:m.path("samples"))if(s.path("metadata").path("account_id").asLong()!=m.path("account_id").asLong()
                || !ids.add(s.path("metadata").path("provider_message_id").asText()))throw new IllegalArgumentException("Owned unique scope required");
        var pins=m.path("source_pins").fields();
        while(pins.hasNext()){var pin=pins.next();if(!hash(Files.readString(Path.of(pin.getKey()))).equals(pin.getValue().asText()))throw new IllegalArgumentException("Reviewed source changed");}
        return m;
    }
    static org.springframework.context.ConfigurableApplicationContext context(boolean readOnly) {
        return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.main.banner-mode=off","--logging.level.root=OFF","--logging.level.com.tidymail=OFF","--spring.sql.init.mode=never",
            "--spring.datasource.hikari.maximum-pool-size=1","--spring.datasource.hikari.minimum-idle=0",
            "--spring.datasource.hikari.read-only="+readOnly,
            "--spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only="+(readOnly?"on":"off"));
    }
    static void offline(JsonNode manifest) throws Exception {
        int requests=0;
        if(!same(JSON.valueToTree(1L),JSON.readTree("1")))throw new IllegalStateException("Numeric metadata normalization failed");
        for(var sample:manifest.path("samples")) {
            var dated=((ObjectNode)sample).deepCopy();dated.put("captured_at",Instant.now().toString());local(dated);
            var original=JSON.treeToValue(sample.path("evidence"),Evidence.class);
            var e=new Evidence(original.message(),original.sourceRevision(),original.address(),original.name(),original.subject(),original.reply(),
                original.unsubscribe(),original.gmailCategories(),original.labelsKnown(),new SemanticQualificationContract.Text("Synthetic bounded snippet",SemanticQualificationContract.State.AVAILABLE),original.relationship());
            var a=CONTRACT.request(List.of(e),Variant.HEADERS);var b=CONTRACT.request(List.of(e),Variant.SNIPPET);
            allowed(a,Variant.HEADERS);allowed(b,Variant.SNIPPET);
            var stripped=b.deepCopy();((ObjectNode)stripped.path("state").path("email_0")).remove("snippet");
            if(!stripped.equals(a)||reserve(a).compareTo(CAP)>0||reserve(a).compareTo(SHARED)>0)throw new IllegalStateException("Preflight failed");
            var malformed=b.deepCopy();((ObjectNode)malformed.path("state").path("email_0")).put("body","must never be sent");
            boolean rejected=false;try{allowed(malformed,Variant.SNIPPET);}catch(IllegalArgumentException expected){rejected=true;}
            if(!rejected)throw new IllegalStateException("Disclosure guard failed");
            requests+=2;
        }
        System.out.println("Offline guard checks passed: "+requests+" paired requests, no transport/database/credentials.");
    }
    static void collect(Path scope,JsonNode manifest,Path output) throws Exception {
        if(Files.exists(output))throw new IllegalArgumentException("Collection replay refused");
        var intent=scope.resolveSibling("snippet-collection-intent.json");
        var intentText=JSON.writeValueAsString(Map.of("scope_sha256",hash(Files.readString(scope)),"authorization","User approved reviewed scope: go ahead","state","COLLECTING","output",output.toString()));
        if(Files.exists(intent)) {
            if(!JSON.readTree(intent.toFile()).path("scope_sha256").asText().equals(hash(Files.readString(scope))))throw new IllegalStateException("Prior collection scope differs");
            int stopped=0;
            try(var previous=Files.walk(scope.getParent(),2)) {
                for(var path:previous.filter(p->p.getFileName().toString().equals("collection.json")).toList()) {
                    var prior=JSON.readTree(path.toFile());
                    if(!prior.path("status").asText().equals("STOPPED")||prior.path("gmail_gets").asInt(-1)!=0)throw new IllegalStateException("Prior Gmail intent cannot be replayed");
                    stopped++;
                }
            }
            if(stopped==0)throw new IllegalStateException("Prior collection intent is unresolved");
            Files.writeString(intent,intentText,StandardOpenOption.TRUNCATE_EXISTING);
        } else Files.writeString(intent,intentText,StandardOpenOption.CREATE_NEW);
        Files.createDirectory(output);
        var report=JSON.createObjectNode();report.put("scope_sha256",hash(Files.readString(scope))).put("gmail_gets",0).put("model_calls",0).put("retained_writes",0);
        var frozen=JSON.createArrayNode();var attempts=report.putArray("requests");
        try(var ctx=context(true)) {
            var jdbc=ctx.getBean(JdbcTemplate.class);long account=manifest.path("account_id").asLong();
            if(!jdbc.queryForObject("SELECT current_setting('transaction_read_only')='on'",Boolean.class))throw new IllegalStateException("Read-only mode required");
            var snapshots=new ArrayList<JsonNode>();
            for(var sample:manifest.path("samples")) {
                var rows=jdbc.queryForList(METADATA,account,sample.path("metadata").path("provider_message_id").asText());
                if(rows.size()!=1)throw new IllegalStateException("Scoped message no longer eligible");
                var current=JSON.valueToTree(rows.getFirst());
                for(var field:List.of("account_id","provider_message_id","dashboard_message_id","provider_thread_id","address","from_display_name","subject_header","received_at","is_reply","has_unsubscribe"))
                    if(!same(current.path(field),sample.path("metadata").path(field)))throw new IllegalStateException("Reviewed headers changed: "+field);
                snapshots.add(current);
            }
            var gmail=gmail(ctx,jdbc,account);int index=0;
            for(var sample:manifest.path("samples")) {
                var metadata=snapshots.get(index++);String id=metadata.path("provider_message_id").asText();
                var entry=attempts.addObject();entry.put("review_id",sample.path("review_id").asText()).put("state","GET_INTENT");
                report.put("gmail_gets",index);save(output.resolve("collection.json"),report);
                var request=gmail.users().messages().get("me",id).setFormat("METADATA").setFields("id,snippet").buildHttpRequest();
                request.setNumberOfRetries(0);request.setConnectTimeout(5000);request.setReadTimeout(10000);
                request.setFollowRedirects(false);request.setLoggingEnabled(false);request.setUnsuccessfulResponseHandler(null);request.setIOExceptionHandler(null);
                if(!request.getRequestMethod().equals("GET")||!request.getUrl().getHost().equals("gmail.googleapis.com"))throw new IllegalStateException("Only scoped Gmail GET allowed");
                long start=System.nanoTime();var response=request.execute();JsonNode root;
                try{byte[] bytes=response.getContent().readNBytes(16385);if(bytes.length>16384)throw new IllegalStateException("Snippet response cap exceeded");
                    root=JSON.readTree(bytes);entry.put("response_bytes",bytes.length).put("http_status",response.getStatusCode());}
                finally{response.disconnect();}
                if(!root.path("id").asText().equals(id)||root.has("payload")||root.has("raw")||root.size()>2
                        ||(root.has("snippet")&&!root.path("snippet").isTextual()))throw new IllegalStateException("Unexpected projection");
                var observed=EmailSnippet.observed(root.has("snippet")?root.path("snippet").asText():null);
                var text=new SemanticQualificationContract.Text(observed.text(),root.has("snippet")?SemanticQualificationContract.State.valueOf(observed.state()):SemanticQualificationContract.State.UNKNOWN);
                var original=JSON.treeToValue(sample.path("evidence"),Evidence.class);
                var categories=new ArrayList<String>();boolean labelsKnown=metadata.path("label_evidence_known").asBoolean();
                if(labelsKnown)for(var label:JSON.readTree(metadata.path("labels_json").asText()))if(Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_UPDATES","CATEGORY_SOCIAL","CATEGORY_FORUMS").contains(label.asText()))categories.add(label.asText());
                var evidence=new Evidence(original.message(),hash(metadata.toString()),original.address(),original.name(),original.subject(),original.reply(),original.unsubscribe(),categories,labelsKnown,text,original.relationship());
                var row=frozen.addObject();row.put("review_id",sample.path("review_id").asText()).put("captured_at",Instant.now().toString());
                row.set("metadata",metadata);row.set("evidence",JSON.valueToTree(evidence));row.set("prior_reviewed_reference",sample.path("prior_reviewed_reference"));
                entry.put("state","COLLECTED").put("request_ms",(System.nanoTime()-start)/1_000_000).put("snippet_state",text.state().name())
                    .put("snippet_utf8_bytes",text.text().getBytes(StandardCharsets.UTF_8).length);
                save(output.resolve("samples.json"),frozen);save(output.resolve("collection.json"),report);
                System.out.println("Collected bounded snippet "+index+"/11.");
            }
            var freeze=JSON.createObjectNode();freeze.put("scope_sha256",hash(Files.readString(scope))).put("samples_sha256",hash(Files.readString(output.resolve("samples.json"))))
                .put("runner_sha256",hash(Files.readString(Path.of("tools/verification/RecommendationSnippetPilot.java"))))
                .put("helper_sha256",hash(Files.readString(Path.of("tools/verification/Phase3JevPilot.java")))).put("status","FROZEN_NO_MODEL_DISPATCH");
            for(int i=0;i<frozen.size();i++)for(var variant:Variant.values()) {
                var e=JSON.treeToValue(frozen.get(i).path("evidence"),Evidence.class);var payload=CONTRACT.request(List.of(e),variant);allowed(payload,variant);
                save(output.resolve("request-"+i+"-"+variant.name().toLowerCase(Locale.ROOT)+".json"),payload);
            }
            save(output.resolve("freeze.json"),freeze);report.put("status","COMPLETE");save(output.resolve("collection.json"),report);
        }catch(Exception failure){report.put("status","STOPPED").put("safe_error_class",failure.getClass().getSimpleName());
            if(failure instanceof IllegalStateException)report.put("safe_reason",failure.getMessage());
            save(output.resolve("collection.json"),report);throw new IllegalStateException("Snippet collection stopped; do not replay requests");}
    }
    static Gmail gmail(org.springframework.context.ConfigurableApplicationContext ctx,JdbcTemplate jdbc,long account) throws Exception {
        var row=jdbc.queryForMap("SELECT scopes,encrypted_access_token,encrypted_refresh_token,token_issued_at,access_expires_at FROM user_accounts WHERE id=? AND provider='google' AND oauth_status='ACTIVE'",account);
        var scopes=new HashSet<>(Arrays.asList((String[])((java.sql.Array)row.get("scopes")).getArray()));
        if(!scopes.contains("https://www.googleapis.com/auth/gmail.modify")&&!scopes.contains("https://www.googleapis.com/auth/gmail.readonly"))throw new IllegalStateException("Existing read grant required");
        var crypto=ctx.getBean(CryptoUtil.class);String token=new String(crypto.decrypt((byte[])row.get("encrypted_access_token")),StandardCharsets.UTF_8);
        Instant expiry=((java.sql.Timestamp)row.get("access_expires_at")).toInstant();
        if(expiry.isBefore(Instant.now().plusSeconds(90))) {
            var props=ctx.getBean(OAuthProviderAvailability.class);
            var registration=ClientRegistration.withRegistrationId("google").clientId(props.getGoogleClientId()).clientSecret(props.getGoogleClientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://localhost/unused")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth").tokenUri("https://oauth2.googleapis.com/token").scope(scopes).build();
            var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,token,((java.sql.Timestamp)row.get("token_issued_at")).toInstant(),expiry,scopes);
            var refresh=new OAuth2RefreshToken(new String(crypto.decrypt((byte[])row.get("encrypted_refresh_token")),StandardCharsets.UTF_8),access.getIssuedAt());
            token=new DefaultRefreshTokenTokenResponseClient().getTokenResponse(new OAuth2RefreshTokenGrantRequest(registration,access,refresh)).getAccessToken().getTokenValue();
        }
        final String bearer=token;
        return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(),JacksonFactory.getDefaultInstance(),req->{req.getHeaders().setAuthorization("Bearer "+bearer);req.setLoggingEnabled(false);req.setNumberOfRetries(0);})
            .setApplicationName("TidyMail-reviewed-snippet-pilot").build();
    }
    static void paid(Path scope,JsonNode manifest,Path collection) throws Exception {
        var freeze=JSON.readTree(collection.resolve("freeze.json").toFile());
        if(!freeze.path("scope_sha256").asText().equals(hash(Files.readString(scope)))
                ||!freeze.path("samples_sha256").asText().equals(hash(Files.readString(collection.resolve("samples.json"))))
                ||!freeze.path("runner_sha256").asText().equals(hash(Files.readString(Path.of("tools/verification/RecommendationSnippetPilot.java"))))
                ||!freeze.path("helper_sha256").asText().equals(hash(Files.readString(Path.of("tools/verification/Phase3JevPilot.java")))))throw new IllegalStateException("Frozen input/code changed");
        var samples=JSON.readTree(collection.resolve("samples.json").toFile());if(samples.size()!=11)throw new IllegalStateException("Frozen membership changed");
        for(int i=0;i<11;i++) {
            var actual=samples.get(i);var expected=manifest.path("samples").get(i);
            if(!actual.path("review_id").equals(expected.path("review_id")))throw new IllegalStateException("Frozen identity changed");
            for(var field:List.of("account_id","provider_message_id","dashboard_message_id","address","subject_header","received_at"))
                if(!actual.path("metadata").path(field).equals(expected.path("metadata").path(field)))throw new IllegalStateException("Frozen source changed");
            if(!actual.path("evidence").path("message").equals(expected.path("evidence").path("message")))throw new IllegalStateException("Frozen message changed");
        }
        Path output=paidOutput(scope,collection);if(Files.exists(output))throw new IllegalStateException("Paid replay refused");
        Path dateRoot=scope.toAbsolutePath().getParent().getParent();
        try(var lock=java.nio.channels.FileChannel.open(dateRoot.resolve("phase3-pilot.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var acquired=lock.tryLock()) {
            if(acquired==null)throw new IllegalStateException("Another pilot is active");
            BigDecimal prior=BigDecimal.ZERO;
            try(var files=Files.walk(dateRoot,5)) {
                for(var path:files.filter(p->p.getFileName().toString().equals("ledger.json")).toList()) {
                    var ledger=JSON.readTree(path.toFile());
                    if(!ledger.path("reported_usd").isNumber()||ledger.path("reported_usd").decimalValue().signum()<0)throw new IllegalStateException("Invalid prior charge ledger");
                    prior=prior.add(ledger.path("reported_usd").decimalValue());
                    for(var a:ledger.path("attempts"))if(Set.of("RESERVED","DISPATCHED","UNKNOWN").contains(a.path("state").asText()))throw new IllegalStateException("Prior liability requires reconciliation");
                }
            }
            Files.writeString(scope.resolveSibling("snippet-paid-dispatch-intent.json"),JSON.writeValueAsString(Map.of("scope_sha256",hash(Files.readString(scope)),"authorization","User go ahead on reviewed 11-message/$0.20 scope")),StandardOpenOption.CREATE_NEW);
            Files.createDirectory(output);var ledger=JSON.createObjectNode();ledger.put("status","PREPARING").put("scope_sha256",hash(Files.readString(scope)))
                .put("prior_shared_reported_usd",prior).put("reported_usd",BigDecimal.ZERO).put("max_incremental_usd",CAP).put("experiment_scope","RECOMMENDATION_SNIPPET_DEVELOPMENT");
            var attempts=ledger.putArray("attempts");save(output.resolve("ledger.json"),ledger);
            try(var ctx=context(false)) {
                var jdbc=ctx.getBean(JdbcTemplate.class);var tx=new TransactionTemplate(new DataSourceTransactionManager(ctx.getBean(javax.sql.DataSource.class)));
                var budget=new ClassificationBudget(jdbc,tx);String key=ctx.getEnvironment().getProperty("app.classification.api-key","").strip();
                if(key.isEmpty())throw new IllegalStateException("Configured key unavailable");
                var durablePrior=jdbc.queryForObject("SELECT COALESCE(sum(cost),0) FROM classification_attempts WHERE benchmark_variant IN ('phase3-development-v1','recommendation-snippet-v1')",BigDecimal.class);
                if(durablePrior.compareTo(prior)!=0)throw new IllegalStateException("Shared experiment ledger does not match durable charges");
                var price=Phase3JevPilot.request("GET","https://openrouter.ai/api/v1/models/typesafe/jev-1.14/endpoints",null,null);
                if(price.status()!=200)throw new IllegalStateException("Cannot verify pricing");var prices=JSON.readTree(price.body());boolean found=false;
                for(var e:prices.path("data").path("endpoints"))if(e.path("provider_name").asText().equalsIgnoreCase("TypeSafe")) {
                    if(new BigDecimal(e.path("pricing").path("prompt").asText()).compareTo(Phase3JevPilot.INPUT_PRICE)>0
                        ||new BigDecimal(e.path("pricing").path("completion").asText()).signum()!=0)throw new IllegalStateException("Pinned upper pricing changed");found=true;
                }
                if(!found)throw new IllegalStateException("TypeSafe route unavailable");save(output.resolve("pricing.json"),prices);
                save(output.resolve("metadata-before.json"),metadataSnapshot(jdbc,manifest));
                BigDecimal total=BigDecimal.ZERO;int sequence=0;
                for(int i=0;i<samples.size();i++) {
                    var sample=samples.get(i);var e=JSON.treeToValue(sample.path("evidence"),Evidence.class);
                    if(e.snippet().text().isBlank())continue;
                    for(var variant:i%2==0?List.of(Variant.HEADERS,Variant.SNIPPET):List.of(Variant.SNIPPET,Variant.HEADERS)) {
                        var payload=CONTRACT.request(List.of(e),variant);allowed(payload,variant);var reservation=reserve(payload);
                        if(total.add(reservation).compareTo(CAP)>0||prior.add(total).add(reservation).compareTo(SHARED)>0)throw new IllegalStateException("Reservation exceeds approved allowance");
                        UUID job=UUID.randomUUID(),attempt=UUID.randomUUID();var entry=attempts.addObject();
                        entry.put("sequence",sequence).put("review_id",sample.path("review_id").asText()).put("variant",variant.name()).put("attempt",attempt.toString())
                            .put("job",job.toString()).put("state","RESERVED").put("reserved_usd",reservation).put("request_sha256",hash(payload.toString()));
                        save(output.resolve("ledger.json"),ledger);save(output.resolve("request-"+sequence+".json"),payload);
                        try {
                            tx.executeWithoutResult(s->{budget.lock();if(!budget.available()||budget.view(manifest.path("account_id").asLong()).reported().add(reservation).compareTo(budget.view(manifest.path("account_id").asLong()).checkpoint())>0)throw new IllegalStateException("Deployment allowance paused or busy");
                                jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status,error_message) VALUES(?,?,'COMPLETE','Reviewed snippet development experiment; no saved categories changed')",job,manifest.path("account_id").asLong());
                                budget.dispatch(attempt,job,manifest.path("account_id").asLong(),1);
                                jdbc.update("UPDATE classification_attempts SET benchmark_variant='recommendation-snippet-v1' WHERE id=?",attempt);});
                        } catch(Exception notSent){entry.put("state","NOT_DISPATCHED");save(output.resolve("ledger.json"),ledger);throw notSent;}
                        entry.put("state","DISPATCHED");save(output.resolve("ledger.json"),ledger);BigDecimal charge=null;
                        try {
                            var wire=Phase3JevPilot.request("POST","https://openrouter.ai/api/alpha/decisions",payload.toString(),key);
                            entry.put("http_status",wire.status()).put("request_ms",wire.elapsedMs());var root=JSON.readTree(wire.body());var cost=root.path("usage").path("cost");
                            if(!cost.isNumber()||!Double.isFinite(cost.doubleValue())||cost.decimalValue().signum()<0||cost.decimalValue().scale()>12||cost.decimalValue().compareTo(new BigDecimal("100"))>0)throw new IllegalStateException("Unknown charge");
                            charge=cost.decimalValue();budget.report(attempt,charge);total=total.add(charge);entry.put("state","REPORTED").put("reported_usd",charge);ledger.put("reported_usd",total);save(output.resolve("ledger.json"),ledger);
                            save(output.resolve("response-"+sequence+".json"),root);
                            if(wire.status()!=200||charge.compareTo(reservation)>0)throw new IllegalStateException("Unexpected status or reservation overrun");
                            var parsed=CONTRACT.parse(root,List.of(e),variant);
                            var diagnostic=CONTRACT.diagnose(e,variant,parsed.judgments().getFirst(),local(sample),Policy.initial(),Thresholds.diagnostic());
                            var result=JSON.createObjectNode();result.put("review_id",sample.path("review_id").asText()).put("variant",variant.name());
                            result.set("judgment",JSON.valueToTree(parsed.judgments().getFirst()));result.set("diagnostic",JSON.valueToTree(diagnostic));result.set("prior_reviewed_reference",sample.path("prior_reviewed_reference"));
                            save(output.resolve("result-"+sequence+".json"),result);entry.put("validated",true);budget.metrics(attempt,wire.elapsedMs(),0,diagnostic.purposeResolved()?0:1);save(output.resolve("ledger.json"),ledger);
                        }catch(Exception failure){if(charge==null){budget.unknown(attempt);entry.put("state","UNKNOWN");}save(output.resolve("ledger.json"),ledger);throw failure;}
                        sequence++;System.out.println("Validated request "+sequence+"/22; reported $"+total.toPlainString()+".");
                    }
                }
                save(output.resolve("metadata-after.json"),metadataSnapshot(jdbc,manifest));ledger.put("status","COMPLETE");save(output.resolve("ledger.json"),ledger);
            }catch(Exception failure){ledger.put("status","STOPPED_NEEDS_REVIEW").put("safe_error_class",failure.getClass().getSimpleName());
                if(failure instanceof IllegalStateException)ledger.put("safe_reason",failure.getMessage());
                save(output.resolve("ledger.json"),ledger);throw new IllegalStateException("Paid pilot stopped; do not replay; inspect billing and saved diagnostics");}
        }
    }
    static Input local(JsonNode sample) throws Exception {
        var e=JSON.treeToValue(sample.path("evidence"),Evidence.class);var m=sample.path("metadata");var labels=JSON.readTree(m.path("labels_json").asText());
        var set=new HashSet<String>();labels.forEach(l->set.add(l.asText()));boolean inbox=set.contains("INBOX"),sent=set.contains("SENT");
        var mailbox=inbox?(sent?Mailbox.SENT_AND_INBOX:Mailbox.INBOX):(sent?Mailbox.SENT_AND_ARCHIVED:Mailbox.ARCHIVED);
        Instant received=m.path("received_at").isTextual()?OffsetDateTime.parse(m.path("received_at").asText().replace(' ','T')).toInstant():null;
        return new Input(e.message(),e.sourceRevision(),mailbox,m.path("size_estimate").isNumber()?m.path("size_estimate").asLong():null,
            new Assessment(Purpose.UNKNOWN,Importance.UNKNOWN,Set.of(),Signal.UNKNOWN,Signal.UNKNOWN,Qualification.UNRESOLVED,false),null,
            !m.path("label_evidence_known").asBoolean()?ReadState.UNKNOWN:m.path("unread").asBoolean()?ReadState.UNREAD:ReadState.READ,received,Instant.parse(sample.path("captured_at").asText()));
    }
    static JsonNode metadataSnapshot(JdbcTemplate jdbc,JsonNode manifest) {
        var result=JSON.createArrayNode();for(var s:manifest.path("samples"))result.add(jdbc.queryForObject("SELECT to_jsonb(m)::text FROM message_metadata m WHERE account_id=? AND provider_message_id=?",String.class,
            manifest.path("account_id").asLong(),s.path("metadata").path("provider_message_id").asText()));return result;
    }
    static void audit(Path scope,JsonNode manifest,Path collection) throws Exception {
        var output=paidOutput(scope,collection);var ledger=JSON.readTree(output.resolve("ledger.json").toFile());BigDecimal total=BigDecimal.ZERO;int checked=0;
        try(var ctx=context(true)) {
            var jdbc=ctx.getBean(JdbcTemplate.class);
            for(var entry:ledger.path("attempts")) {
                var row=jdbc.queryForMap("SELECT state,cost FROM classification_attempts WHERE id=? AND account_id=?",UUID.fromString(entry.path("attempt").asText()),manifest.path("account_id").asLong());
                if(!row.get("state").equals("REPORTED")||!entry.path("state").asText().equals("REPORTED")
                    ||((BigDecimal)row.get("cost")).compareTo(entry.path("reported_usd").decimalValue())!=0)throw new IllegalStateException("Independent settlement mismatch");
                total=total.add((BigDecimal)row.get("cost"));checked++;
            }
            if(total.compareTo(ledger.path("reported_usd").decimalValue())!=0)throw new IllegalStateException("Settlement total mismatch");
            var audit=JSON.createObjectNode();audit.put("attempts_checked",checked).put("reported_usd",total).put("all_charges_match",true)
                .put("metadata_unchanged",JSON.readTree(output.resolve("metadata-before.json").toFile()).equals(metadataSnapshot(jdbc,manifest)))
                .put("retained_writes",0).put("model_calls",0);save(output.resolve("independent-settlement-audit.json"),audit);
            System.out.println("Independent read-only audit matched "+checked+" charges, total $"+total.toPlainString()+".");
        }
    }
    static void allowed(ObjectNode payload,Variant variant) {
        var p=payload.path("provider");if(!payload.path("model").asText().equals(JevClassifier.MODEL)||!p.path("only").equals(JSON.valueToTree(List.of("typesafe")))
            ||p.path("allow_fallbacks").asBoolean(true)||!p.path("zdr").asBoolean()||!p.path("data_collection").asText().equals("deny"))throw new IllegalArgumentException("Route mismatch");
        if(payload.path("state").size()!=1||payload.path("questions").size()!=1+Property.values().length||payload.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("Bounded single-message request required");
        var fields=new HashSet<>(Set.of("address","sender_name","subject","is_reply","has_unsubscribe","gmail_categories_known","gmail_categories","relationship"));
        if(variant==Variant.SNIPPET)fields.add("snippet");var actual=new HashSet<String>();payload.path("state").path("email_0").fieldNames().forEachRemaining(actual::add);
        if(!fields.equals(actual))throw new IllegalArgumentException("Unexpected model disclosure");
        if(variant==Variant.SNIPPET&&payload.path("state").path("email_0").path("snippet").path("text").asText().getBytes(StandardCharsets.UTF_8).length>1024)throw new IllegalArgumentException("Snippet cap exceeded");
    }
    static BigDecimal reserve(JsonNode payload){return Phase3JevPilot.INPUT_PRICE.multiply(BigDecimal.valueOf(262144L*payload.path("questions").size()));}
    /** Keep the paid ledger at the date root so legacy shared-allowance scanners include this run exactly once. */
    static Path paidOutput(Path scope,Path collection) {
        return scope.toAbsolutePath().getParent().getParent().resolve(scope.getParent().getFileName()+"-"+collection.getFileName()+"-paid");
    }
    static String hash(String text){return CalendarEvidenceParser.hash(text);}
    static boolean same(JsonNode a,JsonNode b){return a.toString().equals(b.toString());}
    static void save(Path path,JsonNode value) throws Exception {Phase3JevPilot.save(path,value);}
}
