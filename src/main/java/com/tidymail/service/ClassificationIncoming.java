package com.tidymail.service;

import com.tidymail.repository.DashboardRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Metadata-commit outbox. No remote calls on the sync thread. Account locks match dispatch. */
@Service
public class ClassificationIncoming {
    private final JdbcTemplate jdbc;
    public ClassificationIncoming(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean enabled(long account){return jdbc.queryForObject("SELECT COALESCE((SELECT classification_automatic FROM dashboard_account_settings WHERE account_id=?),true)",Boolean.class,account);}
    public void enabled(long account,boolean enabled){if(enabled)jdbc.update("UPDATE classification_incoming SET retry_after=now() WHERE account_id=?",account);jdbc.update("INSERT INTO dashboard_account_settings(account_id,classification_automatic) VALUES(?,?) ON CONFLICT(account_id) DO UPDATE SET classification_automatic=EXCLUDED.classification_automatic",account,enabled);}
    public void enqueue() {
        var accounts=jdbc.queryForList("""
            SELECT DISTINCT q.account_id FROM classification_incoming q LEFT JOIN dashboard_account_settings s ON s.account_id=q.account_id
            WHERE COALESCE(s.classification_automatic,true) AND q.retry_after<=now() ORDER BY q.account_id LIMIT 10
            """,Long.class);
        for(long account:accounts){
            if(jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE SKIP LOCKED",Long.class,account).isEmpty())continue;
            if(!enabled(account))continue;
            // Retain entries during disabled processing; prune completed, invalid or deleted evidence after commit.
            jdbc.update("""
                DELETE FROM classification_incoming q WHERE q.account_id=? AND NOT EXISTS(
                  SELECT 1 FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                  WHERE m.account_id=q.account_id AND m.dashboard_message_id=q.message_id
                    AND m.classification_override IS NULL AND m.dashboard_from_address IS NOT NULL
                    AND m.dashboard_unit_kind IN ('ADDRESS','LIST') AND
                """+DashboardRepository.RECEIVED+"""
                    AND (m.classification_hash IS DISTINCT FROM m.classification_evidence_hash OR m.classification_version IS DISTINCT FROM ?))
                """,account,JevClassifier.VERSION);
            var ids=jdbc.queryForList("SELECT message_id FROM classification_incoming WHERE account_id=? AND retry_after<=now() ORDER BY queued_at DESC,message_id LIMIT 200",UUID.class,account);
            if(ids.isEmpty())continue;
            var active=jdbc.queryForList("SELECT public_id FROM dashboard_classification_jobs WHERE account_id=? AND status IN ('QUEUED','RUNNING') AND lease_token IS NULL",UUID.class,account);
            if(active.isEmpty() && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dashboard_classification_jobs WHERE account_id=? AND status IN ('QUEUED','RUNNING'))",Boolean.class,account))continue;
            UUID job=active.isEmpty()?UUID.randomUUID():active.getFirst();
            if(active.isEmpty())jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status,automatic) VALUES(?,?,'QUEUED',true)",job,account);
            var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("job",job).addValue("account",account).addValue("ids",ids);
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).update("""
                INSERT INTO dashboard_classification_targets(job_id,account_id,message_id,automatic,priority)
                SELECT :job,:account,message_id,true,1 FROM classification_incoming WHERE account_id=:account AND message_id IN (:ids)
                ON CONFLICT(job_id,message_id) DO UPDATE SET state='PENDING',priority=1
                WHERE dashboard_classification_targets.state<>'PENDING'
                """,params);
            jdbc.update("UPDATE dashboard_classification_jobs SET total=(SELECT count(*) FROM dashboard_classification_targets WHERE job_id=?),done=(SELECT count(*) FROM dashboard_classification_targets WHERE job_id=? AND state='DONE'),skipped=(SELECT count(*) FROM dashboard_classification_targets WHERE job_id=? AND state='SKIPPED') WHERE public_id=?",job,job,job,job);
        }
    }
}
