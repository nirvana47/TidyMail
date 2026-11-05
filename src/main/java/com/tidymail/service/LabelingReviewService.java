package com.tidymail.service;

import com.tidymail.repository.DashboardRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;

/** Local candidate discovery. No provider calls, enrollment or automatic resubmission. */
@Service
public class LabelingReviewService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final LabelingPipeline pipeline;
    public LabelingReviewService(JdbcTemplate jdbc,LabelingPipeline pipeline) {
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.pipeline=pipeline;
    }
    public record Request(String mode,String kind,int limit,List<UUID> messages) {}
    public record Candidate(UUID message,String sender,String subject,Instant receivedAt) {}
    public record Preview(long matching,long eligible,long previouslySubmitted,List<Candidate> messages,
                          BigDecimal reported,BigDecimal allowance,BigDecimal reservation) {}
    public record Recent(UUID id,String kind,boolean authorized,boolean paused,long total,long completed,long failed) {}
    private static final String SOURCE="""
        WITH matching AS MATERIALIZED (
          SELECT m.dashboard_message_id message,m.dashboard_from_address sender,m.subject_header subject,m.received_at,
            EXISTS(SELECT 1 FROM labeling_exposures x WHERE x.account_id=m.account_id AND x.message_id=m.dashboard_message_id) exposed,
            EXISTS(SELECT 1 FROM labeling_work w JOIN labeling_scopes q ON q.id=w.scope_id
              WHERE w.account_id=m.account_id AND w.message_id=m.dashboard_message_id AND q.kind=:kind AND q.authorized
                AND w.state IN ('PENDING','LEASED','DISPATCHED')) queued,
            CASE WHEN :kind='MIME' THEN NOT EXISTS(SELECT 1 FROM message_evidence e
              WHERE e.account_id=m.account_id AND e.provider_message_id=m.provider_message_id AND e.evidence_version IN ('mime-presence-v1','mime-ics-v3'))
              ELSE sl.message_id IS NULL OR sl.input_hash<>labeling_input_hash(m,h,sl.variant) END missing
          FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
          LEFT JOIN correspondent_summary h ON h.account_id=m.account_id AND h.address=m.dashboard_from_address
          LEFT JOIN message_semantic_labels sl ON sl.account_id=m.account_id AND sl.message_id=m.dashboard_message_id
          WHERE m.account_id=:account AND m.provider='google' AND m.label_evidence_known AND
        """+DashboardRepository.RECEIVED+"""
          AND (:mode<>'SELECTED' OR m.dashboard_message_id IN (:ids))
          AND (:mode<>'OTHER' OR COALESCE(m.classification_override,
            CASE WHEN sl.input_hash=labeling_input_hash(m,h,sl.variant) THEN sl.purpose END,
            CASE WHEN m.classification_hash=m.classification_evidence_hash AND m.classification_version=:legacy THEN m.classification_category END,
            'UNKNOWN') IN ('OTHER','UNKNOWN','UNCERTAIN','UNCLASSIFIED'))
        ), eligible AS MATERIALIZED (
          SELECT * FROM matching WHERE NOT queued AND (:kind='MIME' AND missing OR :kind='JEV' AND missing AND NOT exposed)
        )
        """;
    public Preview preview(long account,Request request) {
        if(request==null || !Set.of("MISSING","OTHER","SELECTED").contains(Objects.toString(request.mode(),""))
            || !Set.of("MIME","JEV").contains(Objects.toString(request.kind(),"")) || request.limit()<1 || request.limit()>LabelingPipeline.MAX_SCOPE)
            throw new IllegalArgumentException("Choose missing labels, Other / Unknown, or selected mail, and a batch of 1–1,000");
        List<UUID> ids=request.messages()==null?List.of():request.messages();
        if(ids.size()>LabelingPipeline.MAX_SCOPE || ids.stream().anyMatch(Objects::isNull) || request.mode().equals("SELECTED") && ids.isEmpty())
            throw new IllegalArgumentException("Select between 1 and 1,000 emails");
        var parameters=new MapSqlParameterSource("account",account).addValue("mode",request.mode()).addValue("kind",request.kind())
            .addValue("ids",ids.isEmpty()?List.of(new UUID(0,0)):ids).addValue("limit",request.limit()).addValue("legacy",JevClassifier.VERSION);
        // Counts and the next exact batch share one database snapshot.
        var rows=named.queryForList(SOURCE+"""
            SELECT (SELECT count(*) FROM matching) matching,(SELECT count(*) FROM eligible) eligible,
              (SELECT count(*) FROM matching WHERE exposed) submitted,c.message,c.sender,c.subject,c.received_at
            FROM (SELECT 1) singleton LEFT JOIN LATERAL (
              SELECT * FROM eligible ORDER BY received_at DESC NULLS LAST,message LIMIT :limit
            ) c ON true ORDER BY c.received_at DESC NULLS LAST,c.message
            """,parameters);
        var first=rows.getFirst();
        var candidates=new ArrayList<Candidate>();
        for(var row:rows)if(row.get("message")!=null)candidates.add(new Candidate((UUID)row.get("message"),(String)row.get("sender"),
            (String)row.get("subject"),row.get("received_at")==null?null:((java.sql.Timestamp)row.get("received_at")).toInstant()));
        var allowance=jdbc.queryForMap("SELECT labeling_reported,labeling_checkpoint FROM classification_budget WHERE id=1");
        return new Preview(((Number)first.get("matching")).longValue(),((Number)first.get("eligible")).longValue(),((Number)first.get("submitted")).longValue(),
            List.copyOf(candidates),(BigDecimal)allowance.get("labeling_reported"),(BigDecimal)allowance.get("labeling_checkpoint"),LabelingPipeline.RESERVATION);
    }
    public LabelingPipeline.Prepared prepare(long account,Request request,BigDecimal maxSpend) {
        var preview=preview(account,request);
        if(preview.messages().isEmpty())throw new IllegalArgumentException("No unsubmitted messages are eligible. Existing paid results are reused; uncertainty alone does not trigger another paid request.");
        return pipeline.prepare(account,preview.messages().stream().map(Candidate::message).toList(),request.kind(),
            RecommendationSemanticContract.Variant.HEADERS,maxSpend);
    }
    public List<Recent> recent(long account) {
        return jdbc.query("""
            SELECT s.id,s.kind,s.authorized,s.paused,count(w.id) total,count(*) FILTER(WHERE w.state='DONE') completed,
              count(*) FILTER(WHERE w.state IN ('FAILED','UNKNOWN','STALE')) failed
            FROM (SELECT * FROM labeling_scopes WHERE account_id=? ORDER BY created_at DESC,id DESC LIMIT 10) s
            LEFT JOIN labeling_work w ON w.scope_id=s.id GROUP BY s.id,s.kind,s.authorized,s.paused,s.created_at ORDER BY s.created_at DESC,s.id DESC
            """,(r,n)->new Recent(r.getObject("id",UUID.class),r.getString("kind"),r.getBoolean("authorized"),r.getBoolean("paused"),
                r.getLong("total"),r.getLong("completed"),r.getLong("failed")),account);
    }
}
