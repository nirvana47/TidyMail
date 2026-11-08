package com.tidymail.service;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Account-scoped, pausable local work. No mail/model transports or cleanup
 * dispatch.
 */
@Service
public class RecommendationProcessingService {
    private final JdbcTemplate jdbc;
    private final RecommendationService recommendations;
    private final LabelingPipeline labeling;
    @org.springframework.beans.factory.annotation.Value("${spring.task.scheduling.enabled:true}")
    private boolean scheduling = true;
    @org.springframework.beans.factory.annotation.Value("${app.recommendations.worker.enabled:true}")
    private boolean workerEnabled = true;

    public RecommendationProcessingService(JdbcTemplate jdbc, RecommendationService recommendations,
            LabelingPipeline labeling) {
        this.jdbc = jdbc;
        this.recommendations = recommendations;
        this.labeling = labeling;
    }

    public record Status(boolean enabled, long assessed, long indexed, long localChecked, long queued,
            boolean historyComplete, Instant lastRun, String error, LabelingPipeline.Coverage labeling,
            boolean workerAvailable) {
    }

    public Status status(long account) {
        var coverage = labeling.coverage(account);
        return jdbc.queryForObject("""
                SELECT COALESCE(w.enabled,false) enabled,w.last_run_at,w.error_code,
                  (SELECT count(*) FROM message_metadata WHERE account_id=a.id) indexed,
                  counts.assessed,counts.local_checked,
                  (SELECT count(*) FROM recommendation_dirty WHERE account_id=a.id)
                    +(SELECT count(*) FROM recommendation_address_dirty WHERE account_id=a.id) queued,
                  COALESCE(b.complete,false) complete
                FROM user_accounts a LEFT JOIN recommendation_processing w ON w.account_id=a.id
                LEFT JOIN recommendation_backfill b ON b.account_id=a.id
                CROSS JOIN LATERAL (
                  SELECT count(*) assessed,count(*) FILTER (WHERE local_rules_version=?) local_checked
                  FROM message_recommendations WHERE account_id=a.id
                ) counts WHERE a.id=?
                """,
                (r, n) -> new Status(r.getBoolean("enabled"), r.getLong("assessed"), r.getLong("indexed"),
                        r.getLong("local_checked"), r.getLong("queued"),
                        r.getBoolean("complete"),
                        r.getTimestamp("last_run_at") == null ? null : r.getTimestamp("last_run_at").toInstant(),
                        r.getString("error_code"), coverage, scheduling && workerEnabled),
                LocalMailLabels.VERSION, account);
    }

    public Status enable(long account, boolean enabled) {
        jdbc.update("""
                INSERT INTO recommendation_processing(account_id,enabled) VALUES(?,?)
                ON CONFLICT(account_id) DO UPDATE SET enabled=EXCLUDED.enabled,error_code=NULL
                """, account, enabled);
        return status(account);
    }

    /**
     * One small account batch per invocation; SKIP LOCKED permits multiple app
     * instances.
     */
    public boolean runOnce() {
        var token = java.util.UUID.randomUUID();
        var accounts = jdbc.queryForList("""
                UPDATE recommendation_processing SET lease_token=?,lease_until=now()+interval '2 minutes'
                WHERE account_id=(SELECT account_id FROM recommendation_processing
                  WHERE enabled AND (lease_until IS NULL OR lease_until<now())
                  ORDER BY last_run_at NULLS FIRST,account_id FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING account_id
                """, Long.class, token);
        if (accounts.isEmpty())
            return false;
        long account = accounts.getFirst();
        try {
            recommendations.processBatch(account, 100);
            jdbc.update(
                    "UPDATE recommendation_processing SET last_run_at=now(),lease_until=NULL,lease_token=NULL,error_code=NULL WHERE account_id=? AND lease_token=?",
                    account, token);
        } catch (RuntimeException failure) {
            jdbc.update(
                    "UPDATE recommendation_processing SET enabled=false,last_run_at=now(),lease_until=NULL,lease_token=NULL,error_code='REFRESH_FAILED' WHERE account_id=? AND lease_token=?",
                    account, token);
        }
        return true;
    }
}
