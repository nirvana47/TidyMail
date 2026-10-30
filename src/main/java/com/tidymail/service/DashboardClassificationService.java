package com.tidymail.service;

import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.JevClassifier.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Durable metadata snapshots; remote I/O is deliberately outside all database transactions. */
@Service
public class DashboardClassificationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JevClassifier classifier;
    private final DashboardGroupingService grouping;
    private final ClassificationBudget budget;
    private final ClassificationIncoming incoming;
    @org.springframework.beans.factory.annotation.Value("${app.classification.batch-size:5}")
    private volatile int batchSize=5;
    public DashboardClassificationService(JdbcTemplate jdbc,TransactionTemplate tx,JevClassifier classifier,DashboardGroupingService grouping,ClassificationBudget budget,ClassificationIncoming incoming) {
        this.jdbc=jdbc;this.tx=tx;this.classifier=classifier;this.grouping=grouping;this.budget=budget;this.incoming=incoming;
    }
    public record Status(boolean configured,UUID id,String state,int total,int done,int skipped,BigDecimal cost,String message,ClassificationBudget.View budget,boolean automatic,boolean automaticRun) {}
    private record Claim(UUID id,long account,UUID token,List<Evidence> evidence) {}
    public Status status(long account) {
        var rows=jdbc.query("SELECT * FROM dashboard_classification_jobs WHERE account_id=? ORDER BY created_at DESC,public_id DESC LIMIT 1",
            (r,n)->new Status(classifier.configured(),r.getObject("public_id",UUID.class),r.getString("status"),r.getInt("total"),r.getInt("done"),r.getInt("skipped"),r.getBigDecimal("cost_usd"),r.getString("error_message"),budget.view(account),incoming.enabled(account),r.getBoolean("automatic")),account);
        return rows.isEmpty()?new Status(classifier.configured(),null,"NONE",0,0,0,BigDecimal.ZERO,null,budget.view(account),incoming.enabled(account),false):rows.getFirst();
    }
    public Status start(long account) {
        if(!classifier.configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Add OPENROUTER_API_KEY to .env and restart TidyMail.");
        tx.executeWithoutResult(s->{
            lockAccount(account);
            jdbc.update("UPDATE classification_incoming SET retry_after=now() WHERE account_id=?",account);
            var active=jdbc.queryForList("SELECT public_id FROM dashboard_classification_jobs WHERE account_id=? AND status IN ('QUEUED','RUNNING')",UUID.class,account);
            UUID job=active.isEmpty()?UUID.randomUUID():active.getFirst();
            if(active.isEmpty())jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status) VALUES(?,?,'QUEUED')",job,account);
            else if(jdbc.update("UPDATE dashboard_classification_jobs SET automatic=false WHERE public_id=? AND automatic",job)==0)return;
            jdbc.update("""
              INSERT INTO dashboard_classification_targets(job_id,account_id,message_id)
              SELECT ?,m.account_id,m.dashboard_message_id FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
              WHERE m.account_id=? AND m.dashboard_from_address IS NOT NULL AND m.dashboard_unit_kind IN ('ADDRESS','LIST') AND
              """+DashboardRepository.RECEIVED+"""
              AND m.classification_override IS NULL AND (m.classification_hash IS DISTINCT FROM m.classification_evidence_hash OR m.classification_version IS DISTINCT FROM ?)
              ON CONFLICT(job_id,message_id) DO NOTHING
              """,job,account,JevClassifier.VERSION);
            jdbc.update("UPDATE dashboard_classification_jobs SET total=(SELECT count(*) FROM dashboard_classification_targets WHERE job_id=?),status=CASE WHEN lease_token IS NOT NULL THEN status WHEN EXISTS(SELECT 1 FROM dashboard_classification_targets WHERE job_id=? AND state='PENDING') THEN 'QUEUED' ELSE 'COMPLETE' END WHERE public_id=?",job,job,job);
        });
        return status(account);
    }
    public void correctCategory(long account,List<UUID> ids,String category,long revision) {
        if(ids==null||ids.isEmpty()||ids.size()>5000||category==null||!JevClassifier.CATEGORIES.contains(category)&&!category.equals("RESET"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose messages and a valid category");
        var unique=ids.stream().distinct().toList();
        tx.executeWithoutResult(s->{lockAccount(account);
            long current=jdbc.queryForObject("SELECT COALESCE((SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=?),0)",Long.class,account);
            if(current!=revision)throw new ResponseStatusException(HttpStatus.CONFLICT,"Grouping or categories changed. Reload and select again.");
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("account",account).addValue("ids",unique).addValue("category",category.equals("RESET")?null:category);
            long owned=named.queryForObject("SELECT count(*) FROM message_metadata WHERE account_id=:account AND dashboard_message_id IN (:ids)",params,Long.class);
            if(owned!=unique.size())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Selected messages are unavailable for this account");
            named.update("UPDATE message_metadata SET classification_override_at=now(),classification_override_by=:account,classification_override=:category WHERE account_id=:account AND dashboard_message_id IN (:ids)",params);
            bumpRevision(account);
        });
    }
    UUID startBenchmarkSample(long account,int limit) {
        return tx.execute(s->{
            lockAccount(account);
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dashboard_classification_jobs WHERE account_id=? AND status IN ('RUNNING','QUEUED'))",Boolean.class,account))return null;
            UUID job=UUID.randomUUID();
            jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status,error_message) VALUES(?,?,'QUEUED','Bounded end-to-end validation sample')",job,account);
            jdbc.update("""
                INSERT INTO dashboard_classification_targets(job_id,account_id,message_id)
                SELECT ?,m.account_id,m.dashboard_message_id FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                WHERE m.account_id=? AND m.classification_override IS NULL AND m.dashboard_from_address IS NOT NULL
                  AND m.dashboard_unit_kind IN ('ADDRESS','LIST') AND
                """+DashboardRepository.RECEIVED+"""
                AND (m.classification_hash IS DISTINCT FROM m.classification_evidence_hash OR m.classification_version IS DISTINCT FROM ?)
                ORDER BY m.dashboard_message_id LIMIT ?
                """,job,account,JevClassifier.VERSION,Math.clamp(limit,1,40));
            jdbc.update("UPDATE dashboard_classification_jobs SET total=(SELECT count(*) FROM dashboard_classification_targets WHERE job_id=?) WHERE public_id=?",job,job);
            return job;
        });
    }
    void benchmarkBatchSize(int size){batchSize=Math.clamp(size,2,20);}
    public void automatic(long account,boolean enabled){tx.executeWithoutResult(s->{lockAccount(account);incoming.enabled(account,enabled);});}
    public void continueSpending(long account,long generation,boolean acknowledge) { budget.continueSpending(account,generation,acknowledge); }
    public Status cancel(long account) {
        tx.executeWithoutResult(s->{lockAccount(account);
            jdbc.update("UPDATE classification_incoming q SET retry_after=NULL WHERE q.account_id=? AND EXISTS(SELECT 1 FROM dashboard_classification_targets t JOIN dashboard_classification_jobs j ON j.public_id=t.job_id WHERE t.message_id=q.message_id AND j.account_id=q.account_id AND j.status IN ('QUEUED','RUNNING'))",account);
            jdbc.update("UPDATE dashboard_classification_jobs SET status='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=now(),error_message='Stopped. Saved categories remain; no further grouping changes will be applied.' WHERE account_id=? AND status IN ('QUEUED','RUNNING')",account);});
        return status(account);
    }
    public boolean runOnce() {
        if(!classifier.configured())return false;
        long began=System.nanoTime();
        Claim claim=tx.execute(s->claim());if(claim==null)return false;
        long claimed=System.nanoTime();
        if(claim.evidence().isEmpty()){finish(claim);return true;}
        long received=claimed;int uncertain=0;
        try {
            Result result=classifier.classify(claim.evidence());received=System.nanoTime();
            uncertain=(int)result.decisions().stream().filter(d->d.category().equals("UNCERTAIN") || d.kind().equals("UNKNOWN")).count();
            budget.report(claim.token(),result.cost());save(claim,result);
        }
        catch(Unavailable safe){received=System.nanoTime();if(safe.reportedCost()!=null)budget.report(claim.token(),safe.reportedCost());else budget.unknown(claim.token());fail(claim,safe.getMessage(),safe.reportedCost()!=null && safe.retryable());}
        catch(RuntimeException suppressed){budget.unknown(claim.token());fail(claim,"Classification could not be saved. Check billing before retrying; existing mailbox actions are unchanged.",false);}
        finally {budget.metrics(claim.token(),(received-claimed)/1_000_000,(claimed-began+System.nanoTime()-received)/1_000_000,uncertain);}
        return true;
    }
    private Claim claim() {
        budget.recover();
        if(!budget.available())return null;
        incoming.enqueue();
        // Account lock ordering matches corrections, sync commits, job confirmation and cancellation.
        var candidates=jdbc.queryForList("SELECT account_id FROM dashboard_classification_jobs WHERE status IN ('QUEUED','RUNNING') AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now()) AND (NOT automatic OR COALESCE((SELECT classification_automatic FROM dashboard_account_settings s WHERE s.account_id=dashboard_classification_jobs.account_id),true)) ORDER BY created_at LIMIT 10",Long.class);
        for(long account:candidates) {
            if(jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE SKIP LOCKED",Long.class,account).isEmpty())continue;
            var ids=jdbc.queryForList("SELECT public_id FROM dashboard_classification_jobs WHERE account_id=? AND status IN ('QUEUED','RUNNING') AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now()) FOR UPDATE",UUID.class,account);
            if(ids.isEmpty())continue;
            UUID job=ids.getFirst(),token=UUID.randomUUID();
            // Billing recovery above blocks abandoned dispatches until explicitly reviewed.
            int attempts=jdbc.queryForObject("SELECT attempts FROM dashboard_classification_jobs WHERE public_id=?",Integer.class,job);
            if(attempts>=3){jdbc.update("UPDATE dashboard_classification_jobs SET status='FAILED',error_message='Classification stopped after repeated interrupted attempts. Retry to continue.',lease_token=NULL,lease_until=NULL WHERE public_id=?",job);continue;}
            jdbc.update("UPDATE dashboard_classification_jobs SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=now()+interval '60 seconds',updated_at=now() WHERE public_id=?",token,job);
            var targets=jdbc.queryForList("SELECT message_id FROM (SELECT *,row_number() OVER(PARTITION BY priority ORDER BY message_id) position FROM dashboard_classification_targets WHERE job_id=? AND state='PENDING') candidates WHERE true AND (NOT automatic OR ?) ORDER BY CASE WHEN priority=0 THEN position*? ELSE position+floor((position-1)/?) END,priority DESC,message_id LIMIT ?",UUID.class,job,incoming.enabled(account),Math.clamp(batchSize,2,20),Math.clamp(batchSize,2,20)-1,Math.clamp(batchSize,2,20));
            List<Evidence> evidence=new ArrayList<>();
            if(!targets.isEmpty()) {
                var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("account",account).addValue("ids",targets);
                var rows=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                  SELECT m.* FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id
                  WHERE m.account_id=:account AND m.dashboard_message_id IN (:ids) AND m.classification_override IS NULL
                    AND m.dashboard_from_address IS NOT NULL AND m.dashboard_unit_kind IN ('ADDRESS','LIST') AND
                  """+DashboardRepository.RECEIVED,params,
                    (r,n)->{
                        var labels=Arrays.asList((String[])r.getArray("labels").getArray());
                        var categories=labels.stream().filter(v->Set.of("CATEGORY_PERSONAL","CATEGORY_PROMOTIONS","CATEGORY_UPDATES","CATEGORY_SOCIAL","CATEGORY_FORUMS").contains(v)).sorted().toList();
                        return new Evidence(r.getObject("dashboard_message_id",UUID.class),r.getString("classification_evidence_hash"),r.getString("dashboard_from_address"),r.getString("from_display_name"),r.getString("subject_header"),r.getString("dashboard_unit_kind"),categories,
                            ((String[])r.getArray("references_values").getArray()).length>0||((String[])r.getArray("in_reply_to_values").getArray()).length>0,
                            ((String[])r.getArray("list_unsubscribe_values").getArray()).length>0);
                    });
                var byId=new HashMap<UUID,Evidence>(); rows.forEach(e->byId.put(e.id(),e));
                int skipped=0;
                for(UUID id:targets) {
                    if(byId.containsKey(id))evidence.add(byId.get(id));
                    else {jdbc.update("UPDATE dashboard_classification_targets SET state='SKIPPED' WHERE job_id=? AND message_id=?",job,id);skipped++;}
                }
                if(skipped>0)jdbc.update("UPDATE dashboard_classification_jobs SET skipped=skipped+? WHERE public_id=?",skipped,job);
            }
            if(!evidence.isEmpty())budget.dispatch(token,job,account,evidence.size());
            return new Claim(job,account,token,evidence);
        }
        return null;
    }
    private void save(Claim claim,Result result) {
        tx.executeWithoutResult(s->{
            lockAccount(claim.account());if(!ownedLease(claim))return;
            if(result.decisions().size()!=claim.evidence().size())throw new IllegalArgumentException("Decision count mismatch");
            var values=new ArrayList<Object[]>();
            for(int i=0;i<claim.evidence().size();i++) {
                var e=claim.evidence().get(i);var d=result.decisions().get(i);
                values.add(new Object[]{d.category(),d.kind(),d.stream(),d.confidence(),e.hash(),JevClassifier.VERSION,claim.account(),e.id(),e.hash()});
            }
            int[] savedCounts=jdbc.batchUpdate("""
                UPDATE message_metadata m SET classification_category=?,classification_kind=?,classification_stream=?,classification_confidence=?,
                    classification_hash=?,classification_version=?,classified_at=now()
                FROM user_accounts a WHERE a.id=m.account_id AND m.account_id=? AND m.dashboard_message_id=? AND m.classification_evidence_hash=? AND
                """+DashboardRepository.RECEIVED,values);
            int completed=0;var outcomes=new ArrayList<Object[]>();
            for(int i=0;i<savedCounts.length;i++) {
                if(savedCounts[i]!=0 && savedCounts[i]!=1)throw new IllegalStateException("Unverified classification save count");
                completed+=savedCounts[i];
                outcomes.add(new Object[]{savedCounts[i]==1?"DONE":"SKIPPED",claim.id(),claim.evidence().get(i).id()});
            }
            jdbc.batchUpdate("UPDATE dashboard_classification_targets SET state=? WHERE job_id=? AND message_id=?",outcomes);
            jdbc.update("UPDATE dashboard_classification_jobs SET done=done+?,skipped=skipped+? WHERE public_id=?",completed,claim.evidence().size()-completed,claim.id());
            jdbc.update("UPDATE dashboard_previews p SET invalidated_at=now() WHERE account_id=? AND invalidated_at IS NULL AND COALESCE(filter_snapshot->'filter'->>'category','')<>'' AND NOT EXISTS(SELECT 1 FROM cleanup_jobs j WHERE j.preview_id=p.public_id)",claim.account());
            // Unfiltered draft IDs remain stable; category-filtered drafts use a separate revision.
            jdbc.update("INSERT INTO dashboard_account_settings(account_id,classification_revision) VALUES(?,1) ON CONFLICT(account_id) DO UPDATE SET classification_revision=dashboard_account_settings.classification_revision+1",claim.account());
            jdbc.update("UPDATE dashboard_classification_jobs SET attempts=0,error_message=NULL WHERE public_id=?",claim.id());
            settle(claim);
        });
    }
    private void finish(Claim claim) {
        try{tx.executeWithoutResult(s->{lockAccount(claim.account());if(ownedLease(claim))settle(claim);});}
        catch(RuntimeException suppressed){fail(claim,"Categories saved, but grouping needs review. Saved corrections were preserved.",false);}
    }
    private void settle(Claim claim) {
        jdbc.update("UPDATE dashboard_classification_jobs SET updated_at=now() WHERE public_id=?",claim.id());
        if(!incoming.enabled(claim.account())) {
            int deferred=jdbc.update("UPDATE dashboard_classification_targets SET state='SKIPPED' WHERE job_id=? AND automatic AND state='PENDING'",claim.id());
            if(deferred>0)jdbc.update("UPDATE dashboard_classification_jobs SET skipped=skipped+? WHERE public_id=?",deferred,claim.id());
        }
        boolean pending=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dashboard_classification_targets WHERE job_id=? AND state='PENDING')",Boolean.class,claim.id());
        int done=jdbc.queryForObject("SELECT done FROM dashboard_classification_jobs WHERE public_id=?",Integer.class,claim.id());
        int grouped=jdbc.queryForObject("SELECT grouped_done FROM dashboard_classification_jobs WHERE public_id=?",Integer.class,claim.id());
        if(!pending || budget.view(claim.account()).paused() || done-grouped>=500) {
            regroup(claim.account());
            jdbc.update("UPDATE dashboard_classification_jobs SET grouped_done=? WHERE public_id=?",done,claim.id());
        }
        jdbc.update("UPDATE dashboard_classification_jobs SET status=?,lease_token=NULL,lease_until=NULL,attempts=0 WHERE public_id=?",pending?"QUEUED":"COMPLETE",claim.id());
    }
    private void regroup(long account) {
        var units=jdbc.queryForList("""
          SELECT u.id,u.canonical_key,dashboard_organization_domain(split_part(u.canonical_key,'@',2)) organization,
            bool_and(COALESCE(m.classification_hash=m.classification_evidence_hash AND m.classification_version=?,false)) fresh,
            bool_and(COALESCE(m.classification_kind='AUTOMATED' AND m.classification_confidence>=0.85,false)) automated,
            count(DISTINCT m.classification_stream) streams,min(m.classification_stream) stream
          FROM sender_units u JOIN message_sender_units link ON link.account_id=u.account_id AND link.unit_id=u.id
          JOIN message_metadata m ON m.account_id=link.account_id AND m.id=link.metadata_id
          WHERE u.account_id=? AND u.kind='ADDRESS'
          GROUP BY u.id HAVING count(m.classified_at)>0
          """,JevClassifier.VERSION,account);
        var updates=new ArrayList<Object[]>();
        for(var unit:units) {
            String address=(String)unit.get("canonical_key"),domain=(String)unit.get("organization"),stream=(String)unit.get("stream");
            boolean automated=Boolean.TRUE.equals(unit.get("fresh"))&&Boolean.TRUE.equals(unit.get("automated"))&&((Number)unit.get("streams")).intValue()==1;
            // Google Voice's encoded local part is a correspondent, never an organization identity.
            String host=address.substring(address.indexOf('@')+1);
            boolean relay=host.equals("txt.voice.google.com")||host.endsWith(".txt.voice.google.com");
            String key="address:"+address,label=address,reason="Individual or uncertain sender; exact address preserved";
            if(automated&&domain!=null&&!relay&&!"VOICE".equals(stream)) {
                key="classified-domain:"+domain+":"+stream;
                label=domain+("GENERAL".equals(stream)||"BANKING".equals(stream)||"SHOPPING".equals(stream)?"":" · "+stream.toLowerCase(Locale.ROOT).replace('_',' '));
                reason="Jev metadata classification; organization and service stream";
            }
            updates.add(new Object[]{key,label,reason,account,unit.get("id"),key,label});
        }
        int[][] counts=jdbc.batchUpdate("UPDATE sender_units SET automatic_group_key=?,automatic_label=?,evidence_reason=? WHERE account_id=? AND id=? AND (automatic_group_key IS DISTINCT FROM ? OR automatic_label IS DISTINCT FROM ?)",updates,500,(ps,row)->{for(int i=0;i<row.length;i++)ps.setObject(i+1,row[i]);});
        if(Arrays.stream(counts).flatMapToInt(Arrays::stream).anyMatch(count->count!=0))grouping.rebuild(account);
    }
    private void fail(Claim claim,String message,boolean retryable) {
        tx.executeWithoutResult(s->{lockAccount(claim.account());if(!ownedLease(claim))return;
            jdbc.update("""
              UPDATE dashboard_classification_jobs SET status=CASE WHEN ? AND attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,
                error_message=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval '30 seconds',updated_at=now()
              WHERE public_id=?
              """,retryable,message,claim.id());
            jdbc.update("UPDATE classification_incoming q SET retry_after=NULL WHERE q.account_id=? AND EXISTS(SELECT 1 FROM dashboard_classification_jobs WHERE public_id=? AND status='FAILED')",claim.account(),claim.id());
        });
    }
    private boolean ownedLease(Claim c){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dashboard_classification_jobs WHERE public_id=? AND account_id=? AND lease_token=? AND status='RUNNING' AND lease_until>now())",Boolean.class,c.id(),c.account(),c.token());}
    private void lockAccount(long account){jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);}
    private void bumpRevision(long account){jdbc.update("INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(?,1) ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1",account);}
}
