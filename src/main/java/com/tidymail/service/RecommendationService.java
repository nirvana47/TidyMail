package com.tidymail.service;

import static com.tidymail.service.MessageRecommendation.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded local composition. Never invokes Gmail/Jev or enqueues paid
 * classification.
 */
@Service
public class RecommendationService {
    public static final String ADAPTER_VERSION = "local-recommendation-evidence-v6";
    public static final int MAX_BATCH = 200;

    public enum Freshness {
        CURRENT, PENDING, STALE
    }

    /** Stale results deliberately contain no usable action. */
    public record Correction(Action action, Importance importance, long revision) {
    }

    public record View(UUID message, Freshness freshness, Result result, Correction correction, Set<String> labels,
            Map<String, String> labelReasons, List<String> providerLabelIds, List<String> relayRecipients,
            String shoppingBrand) {
    }

    /**
     * Local provenance/policy stay outside the model's allow-listed Evidence
     * payload.
     */
    public record SemanticPreparation(RecommendationSemanticContract.Evidence evidence, Input local, Policy policy) {
    }

    private record Sources(long metadataRevision, String accountStamp, long policyRevision, long historyRevision,
            long sent, long received, long sentReplies, long receivedReplies, String lastSentAt,
            String lastReceivedAt) {
    }

    private record Snapshot(long metadataId, Input input, Policy policy,
            CorrespondentHistory.Context relationship, String savedHash,
            String savedEngine, String savedAdapter, String savedResult, String sourceStamp, String savedStamp,
            Correction correction,
            RecommendationSemanticContract.Evidence semanticEvidence, Sources sources,
            Headers headers, JsonNode calendarFacts, Map<String, String> localReasons, Instant localCalendarEnd,
            List<String> providerLabelIds, List<String> recipients) {
    }

    private record Headers(String providerMessageId, String address, String name, String subject, String correction, boolean outlook) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MessageRecommendation engine = new MessageRecommendation();
    private final CalendarReconciler calendar = new CalendarReconciler();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.core.env.Environment environment;

    @Autowired
    public RecommendationService(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx) {
        this(jdbc, json, tx, Clock.systemUTC());
    }

    public RecommendationService(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.tx = tx;
        this.clock = Objects.requireNonNull(clock);
    }

    // One statement snapshot includes both relationship counts and their coverage.
    // All message reads are bounded.
    private static final String SOURCE = """
            WITH """ + RecommendationSql.ACCOUNT_CONTEXT
            + """
                    , requested AS MATERIALIZED (
                      SELECT m.* FROM message_metadata m WHERE m.account_id=:account AND m.dashboard_message_id IN (:ids)
                    )
                    SELECT m.*,a.email owner,a.aliases,a.coverage,a.account_stamp,COALESCE(v.revision,0) metadata_revision,COALESCE(h.revision,0) history_revision,
                      COALESCE(h.sent_count,0) history_sent,COALESCE(h.received_count,0) history_received,
                      COALESCE(h.sent_reply_count,0) history_sent_replies,COALESCE(h.received_reply_count,0) history_received_replies,
                      h.last_sent_at,h.last_received_at,e.facts::text evidence_facts,CASE WHEN sl.input_hash=labeling_input_hash(m,h,sl.variant) THEN sl.judgment::text END semantic_judgment,
                      COALESCE(p.revision,0) policy_revision,COALESCE(p.trash_statements,true) trash_statements,
                      COALESCE(p.attention_days,60) attention_days,COALESCE(p.trash_read_or_old_bills,true) trash_read_or_old_bills,
                      COALESCE(p.disposable_bulk,ARRAY['MARKETING','NEWSLETTER','SOCIAL']) disposable_bulk,
                      c.action correction_action,c.importance correction_importance,COALESCE(c.revision,0) correction_revision,
                      """
            + RecommendationSql.STAMP + """
                    AS source_stamp,saved.source_stamp saved_stamp,
                    """ + RecommendationSql.HASH + """
                     source_hash,
                    saved.source_hash saved_hash,saved.engine_version saved_engine,saved.adapter_version saved_adapter,
                    saved.result::text saved_result,
                    CASE WHEN saved.local_rules_version=""" + "'" + LocalMailLabels.VERSION + "'" + """
                          AND saved.metadata_revision=COALESCE(v.revision,0)
                      THEN saved.local_label_reasons::text END local_reasons,
                    CASE WHEN saved.local_rules_version=""" + "'" + LocalMailLabels.VERSION + "'" + """
                            AND saved.metadata_revision=COALESCE(v.revision,0)
                        THEN saved.local_calendar_end END local_calendar_end
                    FROM requested m JOIN account_context a ON a.id=m.account_id
                    """ + RecommendationSql.JOINS + """
                    ORDER BY m.id
                    """;

    public List<View> read(long account, Collection<UUID> messages) {
        var ids = bounded(account, messages);
        if (ids.isEmpty())
            return List.of();
        return load(account, ids).stream().map(s -> {
            Freshness freshness = s.savedHash() == null ? Freshness.PENDING
                    : s.savedHash().equals(s.input().evidenceRevision()) && VERSION.equals(s.savedEngine())
                            && ADAPTER_VERSION.equals(s.savedAdapter()) && s.sourceStamp().equals(s.savedStamp())
                                    ? Freshness.CURRENT
                                    : Freshness.STALE;
            var reasons = new TreeMap<>(s.localReasons());
            if (s.localCalendarEnd() != null && !s.localCalendarEnd().isAfter(s.input().evaluatedAt()))
                reasons.put("PAST_EVENT", "The latest observed schedule in stored calendar evidence has ended.");
            if (reasons.containsKey("DOCUMENT_COMMENT") && s.input().receivedAt() != null
                    && s.input().receivedAt().isBefore(s.input().evaluatedAt().minus(java.time.Duration.ofDays(30))))
                reasons.put("OLDER_DOCUMENT_COMMENT", "The email was received more than 30 days ago.");
            if (s.providerLabelIds().contains("CATEGORY_PROMOTIONS"))
                reasons.put("GMAIL_PROMOTIONS", "Gmail assigned the Promotions category.");
            if (s.input().retention().sentToExactSender() > 0)
                reasons.put("CONTACT", "An outgoing email to this exact address was observed.");
            if (s.input().retention().calendarPartObserved())
                reasons.putIfAbsent("CALENDAR_INVITE", "A calendar part was observed in stored evidence.");
            var labels = new TreeSet<>(labels(s.input()));
            labels.addAll(reasons.keySet());
            return new View(s.input().message(), freshness,
                    freshness == Freshness.CURRENT ? decode(s.savedResult(), Result.class) : null, s.correction(),
                    labels, reasons, s.providerLabelIds(), relayRecipients(account, s.recipients()),
                    reasons.containsKey("SHOPPING") ? LocalMailLabels.shoppingBrand(s.headers().address()) : null);
        }).toList();
    }

    /**
     * Explicit local-only preparation. Saved snippets are never fetched or
     * submitted by this method.
     */
    public List<SemanticPreparation> prepareSemantic(long account, Collection<UUID> messages) {
        var ids = bounded(account, messages);
        if (ids.size() > RecommendationSemanticContract.MAX_MESSAGES)
            throw new IllegalArgumentException("Semantic batch contains at most 3 messages");
        if (ids.isEmpty())
            return List.of();
        // Exclusions and unknown mailbox state cannot silently enter an experimental
        // paid scope.
        return load(account, ids, true).stream()
                .filter(s -> s.semanticEvidence() != null && s.input().mailbox() != Mailbox.EXCLUDED && s.input().mailbox() != Mailbox.UNKNOWN
                        && s.input().mailbox() != Mailbox.SENT_ONLY)
                .map(s -> new SemanticPreparation(s.semanticEvidence(), s.input(), s.policy())).toList();
    }

    /**
     * Explicit scoped entry point. Row locks never span provider I/O. Busy messages
     * remain queued.
     */
    public List<Result> recompute(long account, Collection<UUID> messages) {
        var ids = bounded(account, messages);
        if (ids.isEmpty())
            return List.of();
        return Objects.requireNonNull(tx.execute(status -> {
            lockAccount(account);
            ensureState(account);
            return composeLocked(account, ids);
        }));
    }

    /**
     * Replace independent action/importance corrections; null/null clears via a
     * versioned tombstone.
     */
    public void correct(long account, UUID message, Action action, Importance importance) {
        correct(account, message, action, importance, null);
    }

    public void correct(long account, UUID message, Action action, Importance importance, Long expectedRevision) {
        bounded(account, Collections.singletonList(message));
        if (action == Action.NONE)
            throw new IllegalArgumentException("No-action is not a manual correction");
        tx.executeWithoutResult(status -> {
            lockAccount(account);
            if (jdbc.queryForList(
                    "SELECT id FROM message_metadata WHERE account_id=? AND dashboard_message_id=? FOR UPDATE",
                    Long.class, account, message).isEmpty())
                throw missing();
            long revision = jdbc.queryForObject(
                    "SELECT COALESCE((SELECT revision FROM recommendation_corrections WHERE account_id=? AND message_id=?),0)",
                    Long.class, account, message);
            if (expectedRevision != null && expectedRevision != revision)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This correction changed. Refresh the email before saving again.");
            jdbc.update(
                    """
                            INSERT INTO recommendation_corrections(account_id,message_id,action,importance) VALUES(?,?,?,?)
                            ON CONFLICT(account_id,message_id) DO UPDATE SET action=EXCLUDED.action,importance=EXCLUDED.importance
                            """,
                    account, message, action == null ? null : action.name(),
                    importance == null ? null : importance.name());
        });
    }

    public Policy configure(long account, boolean trashStatements, Set<Purpose> disposableBulk) {
        return configure(account, trashStatements, disposableBulk, Policy.initial().attentionDays(),
                Policy.initial().trashReadOrOldBills());
    }

    public Policy configure(long account, boolean trashStatements, Set<Purpose> disposableBulk, int attentionDays,
            boolean trashBills) {
        if (account <= 0)
            throw new IllegalArgumentException("Account required");
        var checked = new Policy("validation", trashStatements, disposableBulk, attentionDays, trashBills);
        return Objects.requireNonNull(tx.execute(status -> {
            lockAccount(account);
            jdbc.update(
                    """
                            INSERT INTO recommendation_policies(account_id,trash_statements,disposable_bulk,attention_days,trash_read_or_old_bills) VALUES(?,?,?::text[],?,?)
                            ON CONFLICT(account_id) DO UPDATE SET trash_statements=EXCLUDED.trash_statements,disposable_bulk=EXCLUDED.disposable_bulk,
                              attention_days=EXCLUDED.attention_days,trash_read_or_old_bills=EXCLUDED.trash_read_or_old_bills
                            """,
                    account, checked.trashStatements(),
                    array(checked.disposableBulk().stream().map(Enum::name).sorted().toList()), attentionDays,
                    trashBills);
            return jdbc.queryForObject(
                    "SELECT revision,trash_statements,disposable_bulk,attention_days,trash_read_or_old_bills FROM recommendation_policies WHERE account_id=?",
                    (r, n) -> policy(r.getLong("revision"), r.getBoolean("trash_statements"),
                            strings(r.getArray("disposable_bulk")),
                            r.getInt("attention_days"), r.getBoolean("trash_read_or_old_bills")),
                    account);
        }));
    }

    /**
     * Restartable local work. Each tick reserves capacity for dirty messages,
     * exact-address repairs and history.
     */
    public int processBatch(long account, int limit) {
        if (account <= 0 || limit < 4 || limit > MAX_BATCH)
            throw new IllegalArgumentException("Batch must contain 4–200 messages");
        try {
            return Objects.requireNonNull(tx.execute(status -> {
                // Queue briefly behind continuous shared relationship locks instead of starving
                // with SKIP LOCKED.
                // No message locks are held while waiting. A busy foreground transaction wins
                // after this timeout.
                jdbc.execute("SET LOCAL lock_timeout='250ms'");
                if (jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account)
                        .isEmpty())
                    return 0;
                ensureState(account);
                // Coverage changes invalidate negative relationship evidence even when counts
                // do not change.
                String coverage = jdbc.queryForObject(
                        """
                                SELECT md5(jsonb_build_array(b.complete,s.backfill_status,s.rescan_status,
                                  EXISTS(SELECT 1 FROM correspondent_dirty d WHERE d.account_id=a.id),CAST(? AS text),CAST(? AS text),CAST(? AS text))::text)
                                FROM user_accounts a LEFT JOIN correspondent_backfill b ON b.account_id=a.id
                                LEFT JOIN mailbox_sync_state s ON s.account_id=a.id WHERE a.id=?
                                """,
                        String.class, VERSION, ADAPTER_VERSION, LocalMailLabels.VERSION, account);
                jdbc.update("""
                        UPDATE recommendation_backfill SET last_message_id=0,complete=false,coverage_hash=?
                        WHERE account_id=? AND coverage_hash IS DISTINCT FROM ?
                        """, coverage, account, coverage);
                var ids = new LinkedHashSet<>(jdbc.queryForList(
                        """
                                SELECT message_id FROM recommendation_dirty WHERE account_id=? ORDER BY queued_at,message_id LIMIT ?
                                """,
                        UUID.class, account, limit / 2));
                var addresses = jdbc.queryForList(
                        "SELECT address FROM recommendation_address_dirty WHERE account_id=? ORDER BY queued_at,address LIMIT 1",
                        String.class, account);
                String address = addresses.isEmpty() ? null : addresses.getFirst();
                var addressed = address == null ? List.<Map<String, Object>>of()
                        : jdbc.queryForList(
                                """
                                        SELECT id,dashboard_message_id FROM message_metadata WHERE account_id=? AND dashboard_from_address=?
                                        AND id>(SELECT last_message_id FROM recommendation_address_dirty WHERE account_id=? AND address=?) ORDER BY id LIMIT ?
                                        """,
                                account, address, account, address, Math.max(1, limit / 4));
                addressed.forEach(r -> ids.add((UUID) r.get("dashboard_message_id")));
                if (address != null && addressed.isEmpty())
                    jdbc.update("DELETE FROM recommendation_address_dirty WHERE account_id=? AND address=?", account,
                            address);
                int dueLimit = Math.min(Math.max(1, limit / 8), Math.max(0, limit - ids.size() - 1));
                if (dueLimit > 0)
                    ids.addAll(jdbc.queryForList("""
                            SELECT message_id FROM message_recommendations WHERE account_id=? AND next_evaluation_at<=?
                            ORDER BY next_evaluation_at,message_id LIMIT ?
                            """, UUID.class, account, java.sql.Timestamp.from(clock.instant()), dueLimit));
                var historical = jdbc.queryForList("""
                        SELECT id,dashboard_message_id FROM message_metadata WHERE account_id=? AND id>(
                          SELECT last_message_id FROM recommendation_backfill WHERE account_id=? AND NOT complete)
                        ORDER BY id LIMIT ?
                        """, account, account, limit - ids.size());
                historical.forEach(r -> ids.add((UUID) r.get("dashboard_message_id")));
                var accepted = composeLocked(account, ids);
                var acceptedIds = new HashSet<>(accepted.stream().map(Result::message).toList());
                advance(account, null, historical, acceptedIds);
                if (address != null)
                    advance(account, address, addressed, acceptedIds);
                return accepted.size();
            }));
        } catch (DataAccessException failure) {
            // PostgreSQL's 55P03 is not consistently translated by Spring's SQL-state
            // fallback.
            // Only the bounded lock timeout is a deferral; deadlocks and other database
            // failures must surface.
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState()))
                    return 0;
            throw failure;
        }
    }

    private void advance(long account, String address, List<Map<String, Object>> rows, Set<UUID> accepted) {
        String table = address == null ? "recommendation_backfill" : "recommendation_address_dirty";
        if (rows.isEmpty()) {
            if (address == null)
                jdbc.update("UPDATE recommendation_backfill SET complete=true WHERE account_id=?", account);
            return;
        }
        long cursor = ((Number) rows.getLast().get("id")).longValue();
        for (var row : rows)
            if (!accepted.contains((UUID) row.get("dashboard_message_id")))
                cursor = Math.min(cursor, ((Number) row.get("id")).longValue() - 1);
        if (address == null)
            jdbc.update("UPDATE " + table + " SET last_message_id=? WHERE account_id=?", cursor, account);
        else
            jdbc.update("UPDATE " + table + " SET last_message_id=?,queued_at=now() WHERE account_id=? AND address=?",
                    cursor, account, address);
    }

    private List<Result> composeLocked(long account, Collection<UUID> ids) {
        if (ids.isEmpty())
            return List.of();
        var params = sourceParameters(account, ids, clock.instant());
        var locked = named.queryForList(
                """
                        SELECT dashboard_message_id FROM message_metadata WHERE account_id=:account AND dashboard_message_id IN (:ids)
                        ORDER BY id FOR UPDATE SKIP LOCKED
                        """,
                params, UUID.class);
        if (locked.isEmpty())
            return List.of();
        var snapshots = load(account, locked);
        var writes = json.createArrayNode();
        var results = new LinkedHashMap<UUID, Result>();
        for (var s : snapshots) {
            var result = engine.evaluate(s.input(), s.policy());
            results.put(result.message(), result);
            var row = writes.addObject();
            row.put("message_id", result.message().toString()).put("source_hash", result.evidenceRevision())
                    .put("policy_revision", result.policyRevision()).put("engine_version", VERSION)
                    .put("adapter_version", ADAPTER_VERSION).put("source_stamp", s.sourceStamp());
            row.set("sources", json.valueToTree(s.sources()));
            row.put("attention_source", result.attention().name());
            row.set("result", json.valueToTree(result));
            row.set("assessment", json.valueToTree(s.input().assessment()));
            row.set("relationship", json.valueToTree(s.relationship()));
            row.put("local_rules_version", LocalMailLabels.VERSION);
            var headers = s.headers();
            var local = headers == null ? Map.<String, String>of()
                    : LocalMailLabels.classify(headers.address(), headers.name(), headers.subject(),
                            headers.correction(), headers.outlook());
            row.set("local_label_reasons", json.valueToTree(local));
            Instant end = calendarEnd(account, s);
            if (end != null)
                row.put("local_calendar_end", end.toString());
            if (result.nextEvaluationAt() == null)
                row.putNull("next_evaluation_at");
            else
                row.put("next_evaluation_at", result.nextEvaluationAt().toString());
        }
        params.addValue("ids", locked).addValue("writes", writes.toString()).addValue("asOf",
                java.sql.Timestamp.from(clock.instant()));
        // A single statement checks the full current source again before committing any
        // candidate.
        // Identical replay does not rewrite evaluated_at. Dequeue only candidates
        // accepted by that check.
        var accepted = named.queryForList(
                """
                        WITH current_inputs AS (
                        """ + SOURCE
                        + """
                                ), candidates AS (
                                  SELECT c.* FROM jsonb_to_recordset(CAST(:writes AS jsonb)) AS c(message_id uuid,source_hash text,
                                    policy_revision text,engine_version text,adapter_version text,source_stamp text,result jsonb,assessment jsonb,relationship jsonb,next_evaluation_at timestamptz,sources jsonb,attention_source text,
                                    local_rules_version text,local_label_reasons jsonb,local_calendar_end timestamptz)
                                  JOIN current_inputs i ON i.dashboard_message_id=c.message_id AND i.source_hash=c.source_hash AND i.source_stamp=c.source_stamp
                                ), label_change AS (
                                  SELECT 1 FROM candidates c LEFT JOIN message_recommendations old
                                    ON old.account_id=:account AND old.message_id=c.message_id
                                  WHERE ROW(COALESCE(old.local_label_reasons,'{}'::jsonb),old.local_calendar_end)
                                    IS DISTINCT FROM ROW(c.local_label_reasons,c.local_calendar_end)
                                    OR (old.metadata_revision IS DISTINCT FROM (c.sources->>'metadataRevision')::bigint
                                        AND (c.local_label_reasons<>'{}'::jsonb OR c.local_calendar_end IS NOT NULL))
                                  LIMIT 1
                                ), revision AS (
                                  INSERT INTO dashboard_account_settings(account_id,classification_revision)
                                    SELECT :account,1 FROM label_change
                                  ON CONFLICT(account_id) DO UPDATE SET classification_revision=dashboard_account_settings.classification_revision+1
                                ), written AS (
                                  INSERT INTO message_recommendations(account_id,message_id,source_hash,policy_revision,engine_version,adapter_version,result,assessment,relationship,next_evaluation_at,source_stamp,
                                    metadata_revision,account_stamp,policy_source_revision,history_revision,history_sent,history_received,history_sent_replies,history_received_replies,history_last_sent,history_last_received,attention_source,
                                    local_rules_version,local_label_reasons,local_calendar_end)
                                  SELECT :account,message_id,source_hash,policy_revision,engine_version,adapter_version,result,assessment,relationship,next_evaluation_at,source_stamp,
                                    (sources->>'metadataRevision')::bigint,sources->>'accountStamp',(sources->>'policyRevision')::bigint,(sources->>'historyRevision')::bigint,
                                    (sources->>'sent')::bigint,(sources->>'received')::bigint,(sources->>'sentReplies')::bigint,(sources->>'receivedReplies')::bigint,
                                    NULLIF(sources->>'lastSentAt','')::timestamptz,NULLIF(sources->>'lastReceivedAt','')::timestamptz,attention_source,
                                    local_rules_version,local_label_reasons,local_calendar_end FROM candidates
                                  ON CONFLICT(account_id,message_id) DO UPDATE SET source_hash=EXCLUDED.source_hash,policy_revision=EXCLUDED.policy_revision,
                                    engine_version=EXCLUDED.engine_version,adapter_version=EXCLUDED.adapter_version,source_stamp=EXCLUDED.source_stamp,result=EXCLUDED.result,
                                    assessment=EXCLUDED.assessment,relationship=EXCLUDED.relationship,next_evaluation_at=EXCLUDED.next_evaluation_at,evaluated_at=now(),
                                    metadata_revision=EXCLUDED.metadata_revision,account_stamp=EXCLUDED.account_stamp,policy_source_revision=EXCLUDED.policy_source_revision,
                                    history_revision=EXCLUDED.history_revision,history_sent=EXCLUDED.history_sent,history_received=EXCLUDED.history_received,
                                    history_sent_replies=EXCLUDED.history_sent_replies,history_received_replies=EXCLUDED.history_received_replies,
                                    history_last_sent=EXCLUDED.history_last_sent,history_last_received=EXCLUDED.history_last_received,attention_source=EXCLUDED.attention_source,
                                    local_rules_version=EXCLUDED.local_rules_version,local_label_reasons=EXCLUDED.local_label_reasons,local_calendar_end=EXCLUDED.local_calendar_end
                                  WHERE ROW(message_recommendations.source_hash,message_recommendations.engine_version,message_recommendations.adapter_version,message_recommendations.source_stamp,
                                        message_recommendations.local_rules_version,message_recommendations.local_label_reasons,message_recommendations.local_calendar_end)
                                    IS DISTINCT FROM ROW(EXCLUDED.source_hash,EXCLUDED.engine_version,EXCLUDED.adapter_version,EXCLUDED.source_stamp,
                                        EXCLUDED.local_rules_version,EXCLUDED.local_label_reasons,EXCLUDED.local_calendar_end)
                                  RETURNING message_id
                                ), dequeued AS (
                                  DELETE FROM recommendation_dirty q USING candidates c WHERE q.account_id=:account AND q.message_id=c.message_id
                                  RETURNING q.message_id
                                ) SELECT message_id FROM candidates
                                """,
                params, UUID.class);
        return accepted.stream().map(results::get).toList();
    }

    private List<Snapshot> load(long account, Collection<UUID> ids) {
        return load(account, ids, false);
    }

    private List<Snapshot> load(long account, Collection<UUID> ids, boolean includeSemantic) {
        Instant asOf = clock.instant();
        return named.query(SOURCE, sourceParameters(account, ids, asOf), (r, n) -> {
            UUID id = r.getObject("dashboard_message_id", UUID.class);
            var labels = strings(r.getArray("labels"));
            var owners = new HashSet<>(strings(r.getArray("aliases")));
            owners.add(r.getString("owner"));
            var delivered = strings(r.getArray("delivered_to_values"));
            var mailbox = mailbox(labels, r.getBoolean("label_evidence_known"), delivered, owners);
            boolean gmail = "google".equals(r.getString("provider"));
            if (!gmail && labels.contains("SENT")) mailbox = Mailbox.SENT_ONLY;
            boolean received = mailbox != Mailbox.EXCLUDED && mailbox != Mailbox.SENT_ONLY
                    && mailbox != Mailbox.UNKNOWN;
            String override = r.getString("classification_override");
            boolean current = Objects.equals(r.getString("classification_hash"),
                    r.getString("classification_evidence_hash"))
                    && JevClassifier.VERSION.equals(r.getString("classification_version"));
            Purpose purpose = purpose(
                    override != null ? override : current ? r.getString("classification_category") : null);
            // Existing category corrections describe purpose; they do not qualify
            // independent disposal judgments.
            var qualification = override != null || current ? Qualification.UNRESOLVED
                    : r.getString("classification_category") != null ? Qualification.STALE : Qualification.UNRESOLVED;
            if (override != null && Set.of(Purpose.PERSONAL, Purpose.RECEIPT, Purpose.SECURITY).contains(purpose))
                qualification = Qualification.QUALIFIED; // The user's exact-message correction is enough to preserve
                                                         // it.
            if (override == null && current && r.getDouble("classification_confidence") >= .85
                    && Set.of(Purpose.PERSONAL, Purpose.RECEIPT, Purpose.SECURITY).contains(purpose))
                qualification = Qualification.QUALIFIED; // Preservation only; no negative protection flags or disposal
                                                         // permission inferred.
            var facets = EnumSet.noneOf(Facet.class);
            if ("PRESENT".equals(r.getString("attachment_state")))
                facets.add(Facet.HAS_ATTACHMENTS);
            var facts = r.getString("evidence_facts") == null ? null
                    : decode(r.getString("evidence_facts"), JsonNode.class);
            if (facts != null) {
                for (var part : facts.path("parts"))
                    if (part.path("calendar").asBoolean())
                        facets.add(Facet.CALENDAR_INVITE);
                if (facts.path("calendars").isArray() && !facts.path("calendars").isEmpty())
                    facets.add(Facet.CALENDAR_INVITE);
            }
            Importance importance = r.getString("correction_importance") == null ? Importance.UNKNOWN
                    : Importance.valueOf(r.getString("correction_importance"));
            boolean reply = !strings(r.getArray("references_values")).isEmpty()
                    || !strings(r.getArray("in_reply_to_values")).isEmpty();
            var assessment = new Assessment(purpose, importance, facets, Signal.UNKNOWN, Signal.UNKNOWN, qualification,
                    !HeaderSemanticAdmission.inspect(r.getString("subject_header"), reply).eligible());
            if (r.getString("semantic_judgment") != null)
                assessment = SemanticLabelComposition.merge(assessment,
                        decode(r.getString("semantic_judgment"), RecommendationSemanticContract.Judgment.class),
                        override != null);
            Action action = r.getString("correction_action") == null ? null
                    : Action.valueOf(r.getString("correction_action"));
            if (action == null && importance == Importance.IMPORTANT)
                action = mailbox == Mailbox.ARCHIVED || mailbox == Mailbox.SENT_ONLY
                        || mailbox == Mailbox.SENT_AND_ARCHIVED
                                ? Action.KEEP_STORED
                                : Action.KEEP_IN_INBOX;
            var manual = action == null ? null : new ManualDecision(action, importance);
            var input = new Input(id, r.getString("source_hash"), mailbox, r.getObject("size_estimate", Long.class),
                    assessment, manual,
                    !r.getBoolean("label_evidence_known") ? ReadState.UNKNOWN
                            : r.getBoolean("unread") ? ReadState.UNREAD : ReadState.READ,
                    r.getTimestamp("received_at") == null ? null : r.getTimestamp("received_at").toInstant(), asOf,
                    new RetentionEvidence(r.getLong("history_sent"), facets.contains(Facet.CALENDAR_INVITE)));
            var relationship = new CorrespondentHistory.Context(
                    "UNKNOWN".equals(r.getString("coverage"))
                            && r.getLong("history_sent") + r.getLong("history_received") > 0 ? "PARTIAL"
                                    : r.getString("coverage"),
                    r.getLong("history_sent"),
                    r.getLong("history_received"), r.getLong("history_sent_replies"),
                    r.getLong("history_received_replies"),
                    date(r.getTimestamp("last_sent_at")), date(r.getTimestamp("last_received_at")));
            RecommendationSemanticContract.Evidence semantic = null;
            if (includeSemantic && gmail) {
                boolean known = r.getBoolean("label_evidence_known");
                var categories = known
                        ? labels.stream()
                                .filter(v -> Set.of("CATEGORY_PERSONAL", "CATEGORY_PROMOTIONS", "CATEGORY_UPDATES",
                                        "CATEGORY_SOCIAL", "CATEGORY_FORUMS").contains(v))
                                .sorted().toList()
                        : List.<String>of();
                semantic = new RecommendationSemanticContract.Evidence(id, input.evidenceRevision(),
                        r.getString("dashboard_from_address"),
                        r.getString("from_display_name"), r.getString("subject_header"), reply,
                        !strings(r.getArray("list_unsubscribe_values")).isEmpty(), categories, known,
                        new SemanticQualificationContract.Text(r.getString("snippet_text"),
                                SemanticQualificationContract.State.valueOf(r.getString("snippet_state"))),
                        relationship);
            }
            return new Snapshot(r.getLong("id"), input,
                    policy(r.getLong("policy_revision"), r.getBoolean("trash_statements"),
                            strings(r.getArray("disposable_bulk")), r.getInt("attention_days"),
                            r.getBoolean("trash_read_or_old_bills")),
                    relationship, r.getString("saved_hash"), r.getString("saved_engine"),
                    r.getString("saved_adapter"), r.getString("saved_result"), r.getString("source_stamp"),
                    r.getString("saved_stamp"),
                    new Correction(
                            r.getString("correction_action") == null ? null
                                    : Action.valueOf(r.getString("correction_action")),
                            r.getString("correction_importance") == null ? null
                                    : Importance.valueOf(r.getString("correction_importance")),
                            r.getLong("correction_revision")),
                    semantic,
                    new Sources(r.getLong("metadata_revision"), r.getString("account_stamp"),
                            r.getLong("policy_revision"), r.getLong("history_revision"),
                            r.getLong("history_sent"), r.getLong("history_received"), r.getLong("history_sent_replies"),
                            r.getLong("history_received_replies"), date(r.getTimestamp("last_sent_at")),
                            date(r.getTimestamp("last_received_at"))),
                    received ? new Headers(r.getString("provider_message_id"), r.getString("dashboard_from_address"),
                            r.getString("from_display_name"), r.getString("subject_header"), override, !gmail) : null,
                    received ? facts : null, received ? localReasons(r.getString("local_reasons")) : Map.of(),
                    !received || r.getTimestamp("local_calendar_end") == null ? null
                            : r.getTimestamp("local_calendar_end").toInstant(),
                    gmail && r.getBoolean("label_evidence_known") ? labels.stream()
                            .filter(label -> label.startsWith("Label_") || label.equals("CATEGORY_PROMOTIONS")).toList()
                            : List.of(),
                    received ? java.util.stream.Stream
                            .concat(delivered.stream(), strings(r.getArray("to_values")).stream()).toList()
                            : List.of());
        });
    }

    private Map<String, String> localReasons(String encoded) {
        var reasons = new TreeMap<String, String>();
        if (encoded != null)
            decode(encoded, JsonNode.class).fields()
                    .forEachRemaining(e -> reasons.put(e.getKey(), e.getValue().asText()));
        return reasons;
    }

    private List<String> relayRecipients(long account, List<String> observed) {
        if (environment == null || observed.isEmpty())
            return List.of();
        String configured = environment.getProperty("app.gmail.relay-recipients." + account, "");
        if (configured.isBlank())
            return List.of();
        var exact = new HashSet<String>();
        for (String value : configured.split(",")) {
            String address = DashboardIdentity.mailbox(value);
            if (address != null)
                exact.add(address);
        }
        return observed.stream().map(DashboardIdentity::mailbox).filter(Objects::nonNull)
                .filter(exact::contains).distinct().sorted().toList();
    }

    private Instant calendarEnd(long account, Snapshot snapshot) {
        var facts = snapshot.calendarFacts();
        if (facts == null || facts.path("calendars").isEmpty())
            return null;
        String provider = snapshot.headers().providerMessageId();
        for (var part : facts.path("calendars")) {
            if (!"AVAILABLE".equals(part.path("state").asText()) || part.path("events").isEmpty())
                return null;
            for (var event : part.path("events"))
                if (!event.path("uidHash").isTextual() || !event.path("organizerHash").isTextual())
                    return null;
        }
        var events = MessageEvidenceService.reconcileStored(jdbc, json, calendar, account, provider, facts,
                snapshot.input().evaluatedAt());
        Instant end = null;
        for (var event : events) {
            if (!event.messages().contains(provider))
                continue;
            Instant observed = event.latestObservedEnd();
            // An all-day date is over everywhere once the exclusive end passes in UTC−12.
            if (event.latestObservedEndDate() != null)
                observed = event.latestObservedEndDate().atStartOfDay().toInstant(java.time.ZoneOffset.ofHours(-12));
            if (observed == null)
                return null;
            if (end == null || observed.isAfter(end))
                end = observed;
        }
        return end;
    }

    private Set<String> labels(Input input) {
        var labels = new TreeSet<String>();
        input.assessment().facets().forEach(f -> labels.add(f.name()));
        if (input.assessment().purpose() != Purpose.UNKNOWN)
            labels.add(input.assessment().purpose().name());
        if (input.retention().sentToExactSender() > 0)
            labels.add("CONTACT");
        if (input.sizeEstimate() != null && input.sizeEstimate() > LARGE_MESSAGE_BYTES)
            labels.add("LARGE_MESSAGE");
        return Set.copyOf(labels);
    }

    private void lockAccount(long account) {
        if (jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account).isEmpty())
            throw missing();
    }

    private void ensureState(long account) {
        jdbc.update("INSERT INTO recommendation_policies(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
        jdbc.update("INSERT INTO recommendation_backfill(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
    }

    private static Policy policy(long revision, boolean trash, List<String> bulk, int days, boolean trashBills) {
        var purposes = EnumSet.noneOf(Purpose.class);
        bulk.forEach(p -> purposes.add(Purpose.valueOf(p)));
        return new Policy(Policy.initial().revision() + ":" + revision, trash, purposes, days, trashBills);
    }

    private static MapSqlParameterSource sourceParameters(long account, Collection<UUID> ids, Instant asOf) {
        return new MapSqlParameterSource("account", account).addValue("ids", ids).addValue("asOf",
                java.sql.Timestamp.from(asOf));
    }

    private static Mailbox mailbox(List<String> labels, boolean known, List<String> delivered, Set<String> owners) {
        if (!Collections.disjoint(labels, List.of("DRAFT", "SPAM", "TRASH", "OUTLOOK_EXCLUDED")))
            return Mailbox.EXCLUDED;
        if (!known && labels.isEmpty())
            return Mailbox.UNKNOWN;
        if (labels.contains("INBOX"))
            return labels.contains("SENT") ? Mailbox.SENT_AND_INBOX : Mailbox.INBOX;
        var self = new HashSet<String>();
        owners.stream().map(DashboardIdentity::mailbox).filter(Objects::nonNull).forEach(self::add);
        boolean received = delivered.stream().map(DashboardIdentity::mailbox).filter(Objects::nonNull)
                .anyMatch(self::contains);
        return labels.contains("SENT") ? received ? Mailbox.SENT_AND_ARCHIVED : Mailbox.SENT_ONLY : Mailbox.ARCHIVED;
    }

    private static Purpose purpose(String value) {
        if (value == null || value.equals("UNCERTAIN"))
            return Purpose.UNKNOWN;
        try {
            return Purpose.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return Purpose.UNKNOWN;
        }
    }

    private static List<UUID> bounded(long account, Collection<UUID> messages) {
        if (account <= 0 || messages == null || messages.size() > MAX_BATCH
                || messages.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Owned batch must contain at most 200 message IDs");
        return List.copyOf(new LinkedHashSet<>(messages));
    }

    private static List<String> strings(java.sql.Array value) throws java.sql.SQLException {
        return value == null ? List.of() : Arrays.asList((String[]) value.getArray());
    }

    private static String array(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static String date(java.sql.Timestamp value) {
        return value == null ? "" : value.toInstant().toString();
    }

    private <T> T decode(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception ignored) {
            throw new IllegalStateException("Stored recommendation evidence could not be read");
        }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Email or account unavailable");
    }
}
