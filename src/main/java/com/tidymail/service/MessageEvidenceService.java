package com.tidymail.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.mail.gmail.GmailEvidenceReader;
import com.tidymail.repository.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded explicit/scoped background reads, durable facts and no model or
 * mailbox mutation.
 */
@Service
public class MessageEvidenceService {
    private final JdbcTemplate jdbc;
    private final UserAccountRepository accounts;
    private final GmailEvidenceReader reader;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final CalendarReconciler reconciler;
    private final Set<String> reading = ConcurrentHashMap.newKeySet();

    public MessageEvidenceService(JdbcTemplate jdbc, UserAccountRepository accounts, GmailEvidenceReader reader,
            ObjectMapper json, TransactionTemplate tx, CalendarReconciler reconciler) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.reader = reader;
        this.json = json;
        this.tx = tx;
        this.reconciler = reconciler;
    }

    public JsonNode inspect(long account, UUID message) {
        return inspect(account, message, false, () -> true);
    }

    public JsonNode inspectPresence(long account, UUID message) {
        return inspectPresence(account, message, () -> true);
    }

    /**
     * Scoped workers reserve their read allowance here; cached observations consume
     * no new read.
     */
    public JsonNode inspectPresence(long account, UUID message, java.util.function.BooleanSupplier beforeRead) {
        return inspect(account, message, true, Objects.requireNonNull(beforeRead));
    }

    private JsonNode inspect(long account, UUID message, boolean presenceOnly,
            java.util.function.BooleanSupplier beforeRead) {
        String provider = jdbc.query(
                "SELECT provider_message_id FROM message_metadata WHERE account_id=? AND dashboard_message_id=? AND provider='google'",
                (r, n) -> r.getString(1), account, message).stream().findFirst().orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Email unavailable for this account"));
        var saved = jdbc.query(
                "SELECT facts::text FROM message_evidence WHERE account_id=? AND provider_message_id=? AND (evidence_version=? OR (? AND evidence_version=?))",
                (r, n) -> r.getString(1), account, provider, GmailEvidenceReader.VERSION, presenceOnly,
                GmailEvidenceReader.PRESENCE_VERSION);
        try {
            if (!saved.isEmpty()) {
                var previous = json.readTree(saved.getFirst());
                boolean retryable = false;
                for (var calendar : previous.path("calendars"))
                    if (calendar.path("state").asText().equals("FAILED"))
                        retryable = true;
                if (presenceOnly || !retryable)
                    return reconcile(account, provider, previous);
            }
        } catch (Exception ignored) {
            throw new IllegalStateException("Saved evidence could not be read");
        }
        String key = account + ":" + provider;
        if (!reading.add(key))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already being checked");
        try {
            if (!beforeRead.getAsBoolean())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The reviewed read allowance is unavailable");
            var owner = accounts.findById(account).orElseThrow();
            var evidence = presenceOnly ? reader.readPresence(owner, provider) : reader.read(owner, provider);
            var facts = json.valueToTree(evidence);
            ((com.fasterxml.jackson.databind.node.ObjectNode) facts).put("observedAt", Instant.now().toString());
            String encoded = json.writeValueAsString(facts);
            // Do not hold a transaction/row lock during provider I/O. A deleted message
            // stays deleted.
            tx.executeWithoutResult(status -> {
                // Same account-before-message lock order as local composition; never held over
                // Gmail I/O.
                jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR KEY SHARE", Long.class, account);
                var rows = jdbc.queryForList(
                        "SELECT id FROM message_metadata WHERE account_id=? AND provider_message_id=? FOR UPDATE",
                        Long.class, account, provider);
                if (rows.isEmpty())
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email no longer indexed");
                jdbc.update(
                        """
                                INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts)
                                VALUES(?,?,?,?,?,CAST(? AS jsonb)) ON CONFLICT(account_id,provider_message_id) DO UPDATE
                                SET evidence_version=EXCLUDED.evidence_version,evidence_hash=EXCLUDED.evidence_hash,observed_at=EXCLUDED.observed_at,facts=EXCLUDED.facts
                                """,
                        account, provider, evidence.version(), CalendarEvidenceParser.hash(encoded),
                        Timestamp.from(Instant.now()), encoded);
                jdbc.update(
                        """
                                UPDATE message_metadata SET snippet_text=CASE WHEN ? THEN snippet_text ELSE ? END,snippet_state=CASE WHEN ? THEN snippet_state ELSE ? END,
                                attachment_state=CASE WHEN ?<>'UNKNOWN' THEN ? ELSE attachment_state END,
                                has_attachments=CASE WHEN ?<>'UNKNOWN' THEN (?='PRESENT') ELSE has_attachments END,
                                attachment_source=CASE WHEN ?<>'UNKNOWN' THEN 'MIME_STRUCTURE_V1' ELSE attachment_source END,
                                attachment_observed_at=CASE WHEN ?<>'UNKNOWN' THEN now() ELSE attachment_observed_at END
                                WHERE account_id=? AND provider_message_id=?
                                """,
                        presenceOnly, evidence.snippet().text(), presenceOnly, evidence.snippet().state(),
                        evidence.attachmentState(), evidence.attachmentState(), evidence.attachmentState(),
                        evidence.attachmentState(), evidence.attachmentState(), evidence.attachmentState(), account,
                        provider);
            });
            return reconcile(account, provider, json.readTree(encoded));
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not check this email. Reconnect Gmail if needed, then try again.");
        } finally {
            reading.remove(key);
        }
    }

    /**
     * Indexed, bounded lookup of already-observed facts only; no background Gmail
     * reads.
     */
    private JsonNode reconcile(long account, String provider, JsonNode facts) {
        var output = ((com.fasterxml.jackson.databind.node.ObjectNode) facts).deepCopy();
        output.put("reconciliationVersion", CalendarReconciler.VERSION);
        output.set("reconciledEvents",
                json.valueToTree(reconcileStored(jdbc, json, reconciler, account, provider, facts, Instant.now())));
        return output;
    }

    static List<CalendarReconciler.Result> reconcileStored(JdbcTemplate jdbc, ObjectMapper json,
            CalendarReconciler reconciler,
            long account, String provider, JsonNode facts, Instant now) {
        var observations = new ArrayList<CalendarReconciler.Observation>();
        var uids = new LinkedHashSet<String>();
        for (var calendar : facts.path("calendars"))
            for (var event : calendar.path("events"))
                if (event.path("uidHash").isTextual())
                    uids.add(event.path("uidHash").asText());
        boolean complete = uids.size() <= 64;
        var sources = new LinkedHashMap<String, JsonNode>();
        sources.put(provider, facts);
        try {
            for (String uid : uids.stream().limit(64).toList()) {
                String predicate = json.writeValueAsString(
                        Map.of("calendars", List.of(Map.of("events", List.of(Map.of("uidHash", uid))))));
                var rows = jdbc.query(
                        "SELECT provider_message_id,facts::text FROM message_evidence WHERE account_id=? AND facts @> CAST(? AS jsonb) ORDER BY provider_message_id LIMIT 257",
                        (r, n) -> Map.entry(r.getString(1), r.getString(2)), account, predicate);
                if (rows.size() > 256)
                    complete = false;
                for (var row : rows) {
                    if (sources.size() >= 256 && !sources.containsKey(row.getKey())) {
                        complete = false;
                        break;
                    }
                    sources.put(row.getKey(), json.readTree(row.getValue()));
                }
            }
            for (var source : sources.entrySet()) {
                if (!source.getValue().path("structureState").asText().equals("AVAILABLE")
                        || !source.getValue().path("version").asText().equals(GmailEvidenceReader.VERSION))
                    complete = false;
                for (var calendar : source.getValue().path("calendars")) {
                    if (!calendar.path("state").asText().equals("AVAILABLE") || !calendar.path("events").isArray())
                        complete = false;
                    for (var event : calendar.path("events")) {
                        if (!uids.contains(event.path("uidHash").asText()))
                            continue;
                        if (observations.size() >= 1024) {
                            complete = false;
                            break;
                        }
                        observations.add(
                                new CalendarReconciler.Observation(source.getKey(), calendar.path("partId").asText(),
                                        json.treeToValue(event, CalendarEvidenceParser.Event.class)));
                    }
                }
            }
            return reconciler.reconcile(observations, now, complete);
        } catch (Exception error) {
            throw new IllegalStateException("Saved calendar evidence could not be reconciled", error);
        }
    }
}
