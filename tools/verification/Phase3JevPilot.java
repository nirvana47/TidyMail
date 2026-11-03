package com.tidymail.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import static com.tidymail.service.SemanticQualificationContract.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit one-shot, development-only experiment. No component scan, Gmail client or category writes. */
public class Phase3JevPilot {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(excludeName={
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
    static class Config {}
    static final ObjectMapper JSON = new ObjectMapper();
    static final BigDecimal ALLOWANCE = new BigDecimal("0.25");
    static final BigDecimal INPUT_PRICE = new BigDecimal("0.000000042");
    static final SemanticQualificationContract CONTRACT = new SemanticQualificationContract(JSON);
    record Example(String reviewId, long account, Evidence evidence, JsonNode expected, boolean suppliedHeaders,
                   List<String> gmailCategories, boolean labelsKnown) {}
    record Wire(int status, byte[] body, long elapsedMs) {}

    public static void main(String[] args) throws Exception {
        if(args.length==3 && args[0].equals("--prepare-correspondence-only")) { prepareCorrespondence(Path.of(args[1]),Path.of(args[2]));return; }
        if (args.length != 3 || !Set.of("--paid-development-only", "--paid-heldback-once").contains(args[0])) throw new IllegalArgumentException("Explicit paid experiment mode required");
        boolean heldback = args[0].equals("--paid-heldback-once");
        Path manifestPath = Path.of(args[1]), output = Path.of(args[2]);
        if (heldback) {
            var manifest = JSON.readTree(manifestPath.toFile());
            Path policyPath = manifestPath.resolveSibling("phase3-heldback-policy-freeze.json");
            var policy = JSON.readTree(policyPath.toFile());
            if (!manifest.path("scope").asText().equals("HELD_BACK_ONCE")
                    || !CalendarEvidenceParser.hash(Files.readString(policyPath)).equals(manifest.path("policy_sha256").asText())
                    || !CalendarEvidenceParser.hash(Files.readString(Path.of("src/main/java/com/tidymail/service/SemanticQualificationContract.java"))).equals(policy.path("contract_source_sha256").asText()))
                throw new IllegalArgumentException("Frozen held-back policy mismatch");
            var expected = new HashSet<String>(); policy.path("review_ids").forEach(id -> expected.add(id.asText()));
            var actual = new HashSet<String>(); manifest.path("heldback").forEach(row -> actual.add(row.path("review_id").asText()));
            if (!expected.equals(actual) || actual.size() != 16 || manifest.path("heldback").size() != 16)
                throw new IllegalArgumentException("Held-back membership mismatch");
            try (var prior = Files.walk(output.getParent(), 2)) {
                for (Path path : prior.filter(p -> p.getFileName().toString().equals("ledger.json")).toList())
                    if (JSON.readTree(path.toFile()).path("experiment_scope").asText().equals("HELD_BACK_ONCE"))
                        throw new IllegalStateException("A held-back run already exists; never replay it as independent evidence");
            }
        }
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to replay an existing pilot");
        Files.createDirectory(output);
        try (var lock = java.nio.channels.FileChannel.open(output.getParent().resolve("phase3-pilot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var acquired = lock.tryLock()) {
            if (acquired == null) throw new IllegalStateException("Another pilot is active");
            // A crash leaves a durable unresolved intent; another directory must not evade it.
            try (var prior = Files.walk(output.getParent(), 2)) {
                for (Path p : prior.filter(p -> p.getFileName().toString().equals("ledger.json")).toList())
                    for (var attempt : JSON.readTree(p.toFile()).path("attempts"))
                        if (Set.of("RESERVED", "DISPATCHED", "UNKNOWN").contains(attempt.path("state").asText()))
                            throw new IllegalStateException("Prior pilot liability requires reconciliation");
            }
            run(manifestPath, output);
        }
    }
    static void run(Path manifestPath, Path output) throws Exception {
        var manifest = JSON.readTree(manifestPath.toFile());
        if(manifest.path("comparison").asText().equals("CORRESPONDENCE_ON_OFF")) {
            for(var pin:Map.of("contract_source_sha256","src/main/java/com/tidymail/service/SemanticQualificationContract.java",
                "runner_source_sha256","tools/verification/Phase3JevPilot.java").entrySet())
                if(!CalendarEvidenceParser.hash(Files.readString(Path.of(pin.getValue()))).equals(manifest.path(pin.getKey()).asText()))
                    throw new IllegalArgumentException("Frozen correspondence source mismatch");
        }
        BigDecimal priorReported = BigDecimal.ZERO;
        try (var prior = Files.walk(output.getParent(), 2)) {
            for (Path path : prior.filter(p -> p.getFileName().toString().equals("ledger.json")).toList())
                priorReported = priorReported.add(JSON.readTree(path.toFile()).path("reported_usd").decimalValue());
        }
        var ledger = JSON.createObjectNode();
        ledger.put("status", "PREPARING").put("allowance_usd", ALLOWANCE).put("reported_usd", BigDecimal.ZERO)
                .put("manifest_hash", CalendarEvidenceParser.hash(Files.readString(manifestPath)))
                .put("contract_version", VERSION).put("started_at", Instant.now().toString());
        ledger.putArray("attempts");
        ledger.put("experiment_scope", manifest.path("scope").asText("DEVELOPMENT"));
        ledger.put("prior_experiment_reported_usd", priorReported);
        save(output.resolve("ledger.json"), ledger);
        try (var ctx = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off", "--logging.level.root=OFF", "--spring.sql.init.mode=never",
                    "--spring.datasource.hikari.maximum-pool-size=1", "--spring.datasource.hikari.minimum-idle=0")) {
            // Application property resolution loads .env. Never print configuration or credentials.
            String key = ctx.getEnvironment().getProperty("app.classification.api-key", "").strip();
            if (key.isBlank()) throw new IllegalStateException("Configured classification key is unavailable");
            var jdbc = ctx.getBean(JdbcTemplate.class);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ctx.getBean(javax.sql.DataSource.class)));
            var budget = new ClassificationBudget(jdbc, tx);
            var priceWire = request("GET", "https://openrouter.ai/api/v1/models/typesafe/jev-1.14/endpoints", null, null);
            if (priceWire.status() != 200) throw new IllegalStateException("Cannot verify pinned pricing");
            var prices = JSON.readTree(priceWire.body());
            boolean found = false;
            for (var endpoint : prices.path("data").path("endpoints")) {
                if (!endpoint.path("provider_name").asText().equalsIgnoreCase("TypeSafe")) continue;
                var p = endpoint.path("pricing");
                if (new BigDecimal(p.path("prompt").asText()).compareTo(INPUT_PRICE) > 0
                        || new BigDecimal(p.path("completion").asText()).signum() != 0)
                    throw new IllegalStateException("Pinned pricing changed");
                found = true;
            }
            if (!found) throw new IllegalStateException("TypeSafe endpoint pricing unavailable");
            save(output.resolve("pricing.json"), prices);
            var examples = new ArrayList<Example>();
            for (var row : manifest.path("heldback")) examples.add(example(row, jdbc));
            for (var row : manifest.path("development")) examples.add(example(row, jdbc));
            for (var row : manifest.path("review_queue"))
                if (row.path("split_disposition").asText().startsWith("DEVELOPMENT")) examples.add(example(row, jdbc));
            if (manifest.path("admission_policy").asText().equals(HeaderSemanticAdmission.VERSION)) {
                if (manifest.path("scope").asText().equals("HELD_BACK_ONCE"))
                    throw new IllegalArgumentException("Post-heldback admission changes require a fresh policy and reference set");
                var deferred = JSON.createArrayNode();
                for (var e : examples) {
                    var admission = HeaderSemanticAdmission.inspect(e.evidence());
                    if (!admission.eligible()) deferred.addObject().put("review_id", e.reviewId())
                            .put("reason", admission.reason()).put("model_request_made", false);
                }
                save(output.resolve("context-review-required.json"), deferred);
                examples.removeIf(e -> !HeaderSemanticAdmission.inspect(e.evidence()).eligible());
                ledger.put("admission_policy", HeaderSemanticAdmission.VERSION);
            }
            boolean correspondence = manifest.path("comparison").asText().equals("CORRESPONDENCE_ON_OFF");
            if(correspondence && (heldbackScope(manifest) || examples.size()>40 || examples.stream().anyMatch(e->e.evidence().relationship().coverage().equals("UNKNOWN"))))
                throw new IllegalArgumentException("Correspondence comparison requires bounded frozen development summaries");
            boolean comparison = correspondence || manifest.path("comparison").asText().equals("GMAIL_CATEGORIES_ON_OFF");
            var variants = correspondence ? List.of("without_correspondence","with_correspondence") : comparison ? List.of("without_gmail_categories", "with_gmail_categories")
                    : manifest.path("scope").asText().equals("HELD_BACK_ONCE") ? List.of("with_gmail_categories") : List.of("headers_only");
            var frozen = JSON.createObjectNode();
            frozen.put("contract_version", VERSION).put("manifest_hash", ledger.path("manifest_hash").asText())
                    .put("contract_source_hash", CalendarEvidenceParser.hash(Files.readString(Path.of("src/main/java/com/tidymail/service/SemanticQualificationContract.java"))))
                    .put("input_tier", correspondence ? "FROZEN_HEADERS_PLUS_ACCOUNT_ADDRESS_HISTORY" : "FROZEN_HEADERS_PLUS_LOCAL_REPLY_UNSUBSCRIBE_PRESENCE")
                    .put("adoption_enabled", false).put("pass2_enabled", false).put("purpose_min_probability", .85)
                    .put("purpose_min_confidence", .85).put("signal_yes", .9).put("signal_no", .1);
            var ids = frozen.putArray("development_review_ids"); examples.forEach(e -> ids.add(e.reviewId()));
            frozen.set("variants", JSON.valueToTree(variants));
            frozen.put("experiment_scope", manifest.path("scope").asText("DEVELOPMENT"));
            frozen.put("policy_sha256", manifest.path("policy_sha256").asText(""));
            frozen.put("batch_size", 3).put("variant_order", "Alternating first variant by batch; identical evidence snapshot and questions");
            save(output.resolve("freeze.json"), frozen);
            save(output.resolve("evidence-snapshot.json"), JSON.valueToTree(examples));
            BigDecimal total = BigDecimal.ZERO;
            int requestIndex = 0;
            for (int start = 0; start < examples.size(); start += 3) {
                var group = examples.subList(start, Math.min(examples.size(), start + 3));
                var batch = group.stream().map(Example::evidence).toList();
                var orderedVariants = new ArrayList<>(variants);
                if (comparison && (start / 3) % 2 == 1) Collections.reverse(orderedVariants);
                for (String variant : orderedVariants) {
                int sequence = requestIndex++;
                var payload = correspondence ? correspondencePayload(group,variant.equals("with_correspondence")) : CONTRACT.headerOnlyRequest(batch);
                for (int i = 0; i < group.size(); i++) if (group.get(i).suppliedHeaders())
                    ((ObjectNode) payload.path("state").path("email_" + i)).remove(List.of("is_reply", "has_unsubscribe"));
                if (variant.equals("with_gmail_categories") || correspondence) {
                    for (int i = 0; i < group.size(); i++) {
                        var e = group.get(i);
                        var state = (ObjectNode) payload.path("state").path("email_" + i);
                        if (e.labelsKnown()) state.set("gmail_categories", JSON.valueToTree(e.gmailCategories()));
                    }
                }
                // Independent final allow-list: the transport cannot submit optional text evidence.
                for (var state : payload.path("state")) {
                    var allowed = correspondence ? Set.of("address", "sender_name", "subject", "is_reply", "has_unsubscribe", "gmail_categories", "relationship")
                        : Set.of("address", "sender_name", "subject", "is_reply", "has_unsubscribe", "gmail_categories");
                    if(state.has("relationship")) {
                        var relation=state.path("relationship");
                        var fields=new HashSet<String>();relation.fieldNames().forEachRemaining(fields::add);
                        if(!fields.equals(Set.of("coverage","sent","received","sentReplies","receivedReplies","lastSentAt","lastReceivedAt")))
                            throw new IllegalStateException("Unexpected relationship disclosure");
                    }
                    if (!state.has("address") || !state.has("sender_name") || !state.has("subject")) throw new IllegalStateException("Header fields missing");
                    state.fieldNames().forEachRemaining(field -> {
                        if (!allowed.contains(field)) throw new IllegalStateException("Non-header field rejected");
                    });
                }
                save(output.resolve(String.format(Locale.ROOT, "request-%02d.json", sequence)), payload);
                // At most a full 262,144-token context per atomic question, output free.
                BigDecimal reserve = INPUT_PRICE.multiply(BigDecimal.valueOf(262144L * payload.path("questions").size()));
                if (priorReported.add(total).add(reserve).compareTo(ALLOWANCE) > 0) throw new IllegalStateException("Shared pilot allowance cannot cover conservative reservation");
                long account = group.getFirst().account();
                if (group.stream().anyMatch(e -> e.account() != account)) throw new IllegalStateException("Mixed-account batch rejected");
                UUID job = UUID.randomUUID(), attempt = UUID.randomUUID();
                var entry = ((ArrayNode) ledger.path("attempts")).addObject();
                entry.put("attempt", attempt.toString()).put("job", job.toString()).put("state", "RESERVED")
                        .put("variant", variant).put("sequence", sequence)
                        .put("reserved_usd", reserve).put("request_hash", CalendarEvidenceParser.hash(VERSION + payload))
                        .put("request_bytes", payload.toString().getBytes(StandardCharsets.UTF_8).length);
                var reviews = entry.putArray("review_ids"); group.forEach(e -> reviews.add(e.reviewId()));
                save(output.resolve("ledger.json"), ledger);
                tx.executeWithoutResult(s -> {
                    budget.lock();
                    if (!budget.available()) throw new IllegalStateException("Existing classification allowance is paused or busy");
                    var view = budget.view(account);
                    if (view.reported().add(reserve).compareTo(view.checkpoint()) > 0)
                        throw new IllegalStateException("Deployment checkpoint cannot cover reservation");
                    jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status,error_message) VALUES(?,?,'COMPLETE','Phase 3 development experiment; no saved categories changed')", job, account);
                    budget.dispatch(attempt, job, account, group.size());
                    jdbc.update("UPDATE classification_attempts SET benchmark_variant='phase3-development-v1' WHERE id=?", attempt);
                });
                entry.put("state", "DISPATCHED");
                save(output.resolve("ledger.json"), ledger);
                BigDecimal charge = null;
                try {
                    var wire = request("POST", "https://openrouter.ai/api/alpha/decisions", payload.toString(), key);
                    entry.put("http_status", wire.status()).put("request_ms", wire.elapsedMs()).put("response_bytes", wire.body().length);
                    var response = JSON.readTree(wire.body());
                    var cost = response.path("usage").path("cost");
                    if (!cost.isNumber() || cost.decimalValue().signum() < 0 || cost.decimalValue().scale() > 12
                            || cost.decimalValue().compareTo(new BigDecimal("100")) > 0) throw new IllegalStateException("Unknown charge");
                    charge = cost.decimalValue();
                    budget.report(attempt, charge); total = total.add(charge);
                    entry.put("state", "REPORTED").put("reported_usd", charge);
                    ledger.put("reported_usd", total);
                    save(output.resolve("ledger.json"), ledger);
                    if (wire.status() != 200 || charge.compareTo(reserve) > 0) throw new IllegalStateException("Unexpected HTTP status or reservation overrun");
                    // Preserve the billed raw typed response privately before strict decoding.
                    save(output.resolve(String.format(Locale.ROOT, "response-%02d.json", sequence)), response);
                    var result = CONTRACT.parse(response, batch);
                    var saved = JSON.createObjectNode(); saved.put("request_hash", entry.path("request_hash").asText());
                    saved.put("variant", variant).put("sequence", sequence);
                    var judgments = saved.putArray("judgments");
                    for (int i = 0; i < group.size(); i++) {
                        var row = judgments.addObject(); row.put("review_id", group.get(i).reviewId());
                        row.set("judgment", JSON.valueToTree(result.judgments().get(i)));
                        row.set("expected", group.get(i).expected());
                    }
                    save(output.resolve(String.format(Locale.ROOT, "result-%02d.json", sequence)), saved);
                    entry.put("validated", true);
                    budget.metrics(attempt, wire.elapsedMs(), 0, 0);
                    save(output.resolve("ledger.json"), ledger);
                    System.out.println("Validated development request " + (sequence + 1) + " (" + variant + "): " + group.size() + " messages; cumulative reported $" + total.toPlainString());
                } catch (Exception suppressed) {
                    if (charge == null) { budget.unknown(attempt); entry.put("state", "UNKNOWN"); }
                    ledger.put("status", "STOPPED_NEEDS_REVIEW"); save(output.resolve("ledger.json"), ledger);
                    throw new IllegalStateException("Pilot stopped; inspect bounded local diagnostics and billing before any new submission");
                }
                }
            }
            ledger.put("status", "DEVELOPMENT_RUN_COMPLETE"); save(output.resolve("ledger.json"), ledger);
        }
    }
    static Example example(JsonNode row, JdbcTemplate jdbc) {
        var source = row.path("source_message");
        long account = source.path("account_id").asLong();
        String provider = source.path("provider_message_id").asText();
        boolean supplied = source.path("evidence_source").asText().equals("USER_SUPPLIED_HEADERS")
                || source.path("evidence_source").asText().equals("SYNTHETIC_HEADERS");
        var frozen=source.path("frozen_evidence");
        var flags = supplied ? Map.<String,Object>of() : source.has("frozen_evidence")
            ? Map.<String,Object>of("reply",frozen.path("reply").asBoolean(),"unsubscribe",frozen.path("unsubscribe").asBoolean(),"label_evidence_known",frozen.path("labels_known").asBoolean()) : jdbc.queryForMap("SELECT cardinality(in_reply_to_values)>0 OR cardinality(references_values)>0 reply,cardinality(list_unsubscribe_values)>0 unsubscribe,labels,label_evidence_known FROM message_metadata WHERE account_id=? AND provider_message_id=? AND provider='google'", account, provider);
        List<String> categories = List.of();
        if(source.has("frozen_evidence")) {
            var values=new ArrayList<String>(); frozen.path("gmail_categories").forEach(label->values.add(label.asText()));categories=List.copyOf(values);
        }
        else if (!supplied) try {
            var allowed = Set.of("CATEGORY_PERSONAL", "CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_UPDATES", "CATEGORY_FORUMS");
            categories = Arrays.stream((String[]) ((java.sql.Array) flags.get("labels")).getArray()).filter(allowed::contains).sorted().toList();
        } catch (java.sql.SQLException error) { throw new IllegalStateException("Unable to snapshot category labels"); }
        var evidence = new Evidence(UUID.nameUUIDFromBytes((account + ":" + provider).getBytes(StandardCharsets.UTF_8)),
                CalendarEvidenceParser.hash(source.toString()), "frozen-header-plus-presence-v1", source.path("address").asText(),
                source.path("from_display_name").asText(""), source.path("subject_header").asText(),
                Boolean.TRUE.equals(flags.get("reply")), Boolean.TRUE.equals(flags.get("unsubscribe")),
                new Text("", State.UNKNOWN), new Text("", State.UNKNOWN), new Facts(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, false),relationship(source));
        return new Example(row.path("review_id").asText(), account, evidence, row.path("expected"), supplied,
                categories, Boolean.TRUE.equals(flags.get("label_evidence_known")));
    }
    /** Fully offline preflight. No Spring context, credentials, database, transport or budget operations. */
    static void prepareCorrespondence(Path manifestPath,Path output) throws Exception {
        var manifest=JSON.readTree(manifestPath.toFile());
        if(!manifest.path("comparison").asText().equals("CORRESPONDENCE_ON_OFF")||heldbackScope(manifest)||Files.exists(output))
            throw new IllegalArgumentException("New bounded development preflight required");
        var examples=new ArrayList<Example>();
        for(var row:manifest.path("development")) {
            var source=row.path("source_message");
            if(!source.has("frozen_evidence")&&!source.path("evidence_source").asText().equals("SYNTHETIC_HEADERS"))
                throw new IllegalArgumentException("Offline preflight requires frozen evidence");
            examples.add(example(row,null));
        }
        if(examples.isEmpty()||examples.size()>40)throw new IllegalArgumentException("Bounded sample required");
        Files.createDirectory(output); int requests=0;
        for(int start=0;start<examples.size();start+=3) {
            var group=examples.subList(start,Math.min(examples.size(),start+3));
            var batch=group.stream().map(Example::evidence).toList();
            var without=correspondencePayload(group,false);var with=correspondencePayload(group,true);
            var stripped=with.deepCopy();stripped.path("state").forEach(node->((ObjectNode)node).remove("relationship"));
            if(!stripped.equals(without))throw new IllegalStateException("Unmatched comparison");
            save(output.resolve(String.format(Locale.ROOT,"without-%02d.json",start/3)),without);
            save(output.resolve(String.format(Locale.ROOT,"with-%02d.json",start/3)),with);requests+=2;
        }
        var summary=JSON.createObjectNode();summary.put("status","OFFLINE_PREFLIGHT_PASSED_NO_DISPATCH")
            .put("messages",examples.size()).put("prepared_requests",requests).put("model_calls",0).put("retained_writes",0)
            .put("matched_pairs_verified",true).put("manifest_sha256",CalendarEvidenceParser.hash(Files.readString(manifestPath)));
        save(output.resolve("preflight.json"),summary);System.out.println(summary);
    }
    static ObjectNode correspondencePayload(List<Example> group,boolean include) {
        var payload=CONTRACT.correspondenceRequest(group.stream().map(Example::evidence).toList(),include);
        for(int i=0;i<group.size();i++) {
            var e=group.get(i);var state=(ObjectNode)payload.path("state").path("email_"+i);
            if(e.suppliedHeaders())state.remove(List.of("is_reply","has_unsubscribe"));
            if(e.labelsKnown())state.set("gmail_categories",JSON.valueToTree(e.gmailCategories()));
        }
        return payload;
    }
    static boolean heldbackScope(JsonNode manifest) { return manifest.path("scope").asText().equals("HELD_BACK_ONCE") || !manifest.path("heldback").isMissingNode()&&!manifest.path("heldback").isEmpty(); }
    static CorrespondentHistory.Context relationship(JsonNode source) {
        if(!source.has("relationship"))return CorrespondentHistory.Context.unknown();
        var r=source.path("relationship");
        for(String field:List.of("sent","received","sentReplies","receivedReplies"))
            if(!r.path(field).isIntegralNumber()||!r.path(field).canConvertToLong())throw new IllegalArgumentException("Invalid frozen counts");
        return new CorrespondentHistory.Context(r.path("coverage").asText(),r.path("sent").longValue(),r.path("received").longValue(),
            r.path("sentReplies").longValue(),r.path("receivedReplies").longValue(),r.path("lastSentAt").asText(),r.path("lastReceivedAt").asText());
    }
    static Wire request(String method, String url, String payload, String key) throws Exception {
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(40000);
        connection.setRequestMethod(method); connection.setRequestProperty("X-OpenRouter-Cache", "false");
        if (key != null) connection.setRequestProperty("Authorization", "Bearer " + key);
        long began = System.nanoTime();
        try {
            if (payload != null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (var stream = connection.getOutputStream()) { stream.write(payload.getBytes(StandardCharsets.UTF_8)); } }
            int status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            byte[] bytes;
            try (stream) { bytes = stream == null ? new byte[0] : stream.readNBytes(262145); }
            if (bytes.length > 262144) throw new IllegalStateException("Response cap exceeded");
            return new Wire(status, bytes, (System.nanoTime() - began) / 1_000_000);
        } finally { connection.disconnect(); }
    }
    static void save(Path path, JsonNode value) throws Exception {
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        Files.writeString(pending, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
