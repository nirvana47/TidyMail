package com.tidymail.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Deployment-wide response-only accounting. Lock budget before account in dispatch and settlement. */
@Service
public class ClassificationBudget {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ClassificationBudget(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public record View(BigDecimal reported, BigDecimal checkpoint, long generation, long inFlight,
                       long unknown, boolean paused, boolean canContinue, boolean canAuthorize) {}
    public View view(long account) {
        return jdbc.queryForObject("""
            SELECT b.*, (SELECT count(*) FROM classification_attempts WHERE state='DISPATCHED') flying,
              (SELECT count(*) FROM classification_attempts WHERE state='UNKNOWN') unknown
            FROM classification_budget b WHERE id=1
            """, (r,n)-> {
                long flying=r.getLong("flying"), unknown=r.getLong("unknown");
                boolean paused=unknown>0 || r.getBigDecimal("reported").compareTo(r.getBigDecimal("checkpoint"))>=0;
                boolean owner=r.getObject("owner_account")!=null && r.getLong("owner_account")==account;
                return new View(r.getBigDecimal("reported"),r.getBigDecimal("checkpoint"),r.getLong("generation"),
                    flying,unknown,paused,paused && flying==0 && owner,owner);
            });
    }
    public void lock() { jdbc.queryForObject("SELECT id FROM classification_budget WHERE id=1 FOR UPDATE",Integer.class); }
    public void recover() {
        lock();
        // A missing response is never silently retried as a free request.
        jdbc.update("UPDATE classification_attempts SET state='UNKNOWN',settled_at=now() WHERE state='DISPATCHED' AND dispatched_at<now()-interval '90 seconds'");
    }
    public boolean available() {
        return jdbc.queryForObject("""
            SELECT reported<checkpoint AND NOT EXISTS(SELECT 1 FROM classification_attempts WHERE state IN ('UNKNOWN','DISPATCHED'))
            FROM classification_budget WHERE id=1
            """,Boolean.class);
    }
    public void dispatch(UUID attempt, UUID job, long account,int count) {
        jdbc.update("UPDATE classification_budget SET owner_account=COALESCE(owner_account,?) WHERE id=1",account);
        jdbc.update("INSERT INTO classification_attempts(id,job_id,account_id,state,message_count) VALUES(?,?,?,'DISPATCHED',?)",attempt,job,account,count);
    }
    public void report(UUID attempt, BigDecimal cost) {
        if(cost==null || cost.signum()<0 || cost.compareTo(new BigDecimal("100"))>0 || cost.scale()>12) { unknown(attempt); return; }
        tx.executeWithoutResult(s->{
            lock();
            var jobs=jdbc.queryForList("UPDATE classification_attempts SET state='REPORTED',cost=?,settled_at=now() WHERE id=? AND cost IS NULL AND state IN ('DISPATCHED','UNKNOWN','ACKNOWLEDGED') RETURNING job_id",UUID.class,cost,attempt);
            if(jobs.isEmpty())return;
            jdbc.update("UPDATE classification_budget SET reported=reported+? WHERE id=1",cost);
            jdbc.update("UPDATE dashboard_classification_jobs SET cost_usd=cost_usd+? WHERE public_id=?",cost,jobs.getFirst());
        });
    }
    public void metrics(UUID attempt,long requestMs,long databaseMs,int uncertain) {
        jdbc.update("UPDATE classification_attempts SET request_ms=?,database_ms=?,uncertain_count=? WHERE id=?",requestMs,databaseMs,uncertain,attempt);
    }
    public void unknown(UUID attempt) {
        tx.executeWithoutResult(s->{lock();jdbc.update("UPDATE classification_attempts SET state='UNKNOWN',settled_at=now() WHERE id=? AND state='DISPATCHED'",attempt);});
    }
    public View continueSpending(long account,long generation,boolean acknowledge) {
        tx.executeWithoutResult(s->{
            lock(); View v=view(account);
            if(!v.canAuthorize())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the deployment allowance owner can continue spending.");
            if(v.generation()!=generation)return; // replay of an already settled continuation
            if(v.inFlight()>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for in-flight requests to settle.");
            if(!v.paused())return;
            if(v.unknown()>0 && !acknowledge)throw new ResponseStatusException(HttpStatus.CONFLICT,"Check OpenRouter activity and acknowledge unknown charges first.");
            if(acknowledge)jdbc.update("UPDATE classification_attempts SET state='ACKNOWLEDGED',acknowledged_by=? WHERE state='UNKNOWN'",account);
            jdbc.update("UPDATE classification_budget SET checkpoint=reported+1,generation=generation+1 WHERE id=1");
        });
        return view(account);
    }
}
