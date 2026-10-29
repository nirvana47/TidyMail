package com.tidymail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.MailMessage;
import com.tidymail.repository.DashboardRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short database transactions only. No provider calls, waits, or mailbox identifiers in responses. */
@Service
public class DashboardJobService {
    public static final int BATCH_SIZE=50;
    public static final int MAX_READ_ATTEMPTS=6;
    private final DashboardRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ActionScopeService scopes;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public DashboardJobService(DashboardRepository repository,ActionScopeService scopes,ObjectMapper json,PlatformTransactionManager manager) {
        this.repository=repository;this.jdbc=repository.jdbc();this.scopes=scopes;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    public record Progress(UUID id,String action,String status,int total,int changed,int failed,int skipped,
        int unresolved,int cancelled,int remaining,boolean cancelRequested,Instant createdAt,long version,String retryReason) {}
    public record Target(int ordinal,String providerId,UUID messageId,boolean preChangeObserved) {}
    public record Claim(UUID job,long account,UUID worker,long generation,DashboardFilter filter,List<Target> targets,boolean verification,String action) {}
    public record Dispatch(Claim claim,UUID batch,List<Target> targets) {}

    private <T> T transaction(Supplier<T> work) {return tx.execute(status->work.get());}
    private MapSqlParameterSource params(long account,UUID job) {return new MapSqlParameterSource("account",account).addValue("job",job);}
    private static ResponseStatusException conflict(String text) {return new ResponseStatusException(HttpStatus.CONFLICT,text);}
    private Map<String,Object> lock(long account,UUID job) {
        var rows=jdbc.queryForList("SELECT * FROM cleanup_jobs WHERE public_id=:job AND account_id=:account FOR UPDATE",params(account,job));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Job unavailable for this account");
        return rows.getFirst();
    }

    /** The preview UUID is the single-use confirmation token; repeats always return the same job. */
    public Progress confirm(long account,UUID preview) {
        return transaction(()->{
            var p=new MapSqlParameterSource("account",account).addValue("preview",preview);
            jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=:account FOR UPDATE",p,Long.class);
            var existing=jdbc.queryForList("SELECT public_id FROM cleanup_jobs WHERE account_id=:account AND preview_id=:preview",p);
            if(!existing.isEmpty())return progress(account,(UUID)existing.getFirst().get("public_id"));
            var rows=jdbc.queryForList("""
                SELECT p.*,s.action_type,a.provider FROM dashboard_previews p
                JOIN action_scopes s ON s.id=p.scope_id AND s.account_id=p.account_id
                JOIN user_accounts a ON a.id=p.account_id
                WHERE p.public_id=:preview AND p.account_id=:account AND p.expires_at>now() AND p.invalidated_at IS NULL FOR UPDATE OF p
                """,p);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Preview expired or unavailable");
            var row=rows.getFirst();
            if(!Set.of("MARK_READ","ARCHIVE","TRASH").contains(row.get("action_type"))||!row.get("provider").equals("google"))throw conflict("Only supported Gmail cleanup actions are available");
            if(((Number)row.get("grouping_revision")).longValue()!=repository.revision(account))throw conflict("Grouping changed. Create a new preview.");
            if(((Number)row.get("effective_count")).intValue()==0)throw conflict("No eligible changes. Create a new selection.");
            var targets=jdbc.queryForList("SELECT * FROM dashboard_preview_targets WHERE preview_id=:preview AND account_id=:account ORDER BY ordinal",p);
            var scope=scopes.requireScope(account,((Number)row.get("scope_id")).longValue());
            var ids=targets.stream().map(t->(String)t.get("provider_message_id")).toList();
            if(ids.contains(null)||targets.size()!=((Number)row.get("selected_count")).intValue()
                ||!new HashSet<>(ids).equals(new HashSet<>(scope.getMessageIds()))||ids.size()!=new HashSet<>(ids).size())throw conflict("Preview targets changed. Create a new preview.");
            p.addValue("ids",ids);
            if(jdbc.queryForObject("""
                SELECT count(*) FROM cleanup_active_targets c JOIN cleanup_job_targets t
                ON t.job_id=c.job_id AND t.account_id=c.account_id AND t.target_fingerprint=c.target_fingerprint
                WHERE c.account_id=:account AND t.provider_message_id IN (:ids)
                """,p,Long.class)>0)throw conflict("Selected messages already belong to active or unresolved work");
            UUID job=UUID.randomUUID();p.addValue("job",job).addValue("scope",row.get("scope_id"))
                .addValue("action",row.get("action_type")).addValue("total",targets.size()).addValue("snapshot",row.get("filter_snapshot").toString());
            jdbc.update("""
                INSERT INTO cleanup_jobs(public_id,account_id,preview_id,scope_id,action_type,selected_count,snapshot)
                VALUES(:job,:account,:preview,:scope,:action,:total,CAST(:snapshot AS jsonb))
                """,p);
            jdbc.update("""
                INSERT INTO cleanup_job_targets(job_id,account_id,ordinal,provider_message_id,message_id,target_fingerprint,state,reason)
                SELECT :job,account_id,ordinal,provider_message_id,message_id,encode(digest(convert_to(provider_message_id,'UTF8'),'sha256'),'hex'),
                CASE WHEN effective THEN 'QUEUED' ELSE 'SKIPPED' END,reason
                FROM dashboard_preview_targets WHERE account_id=:account AND preview_id=:preview
                """,p);
            jdbc.update("""
                INSERT INTO cleanup_active_targets SELECT account_id,target_fingerprint,job_id FROM cleanup_job_targets
                WHERE job_id=:job AND state='QUEUED'
                """,p);
            event(account,job);return progress(account,job);
        });
    }

    public List<Progress> list(long account) {
        return transaction(()->jdbc.queryForList("""
            SELECT public_id FROM cleanup_jobs WHERE account_id=:account
            ORDER BY CASE WHEN EXISTS(SELECT 1 FROM cleanup_job_targets t WHERE t.job_id=cleanup_jobs.public_id AND t.state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION')) THEN 0 ELSE 1 END,created_at DESC LIMIT 30
            """,Map.of("account",account)).stream().map(row->progress(account,(UUID)row.get("public_id"))).toList());
    }
    public Progress get(long account,UUID job) {return transaction(()->{lock(account,job);return progress(account,job);});}
    public Progress cancel(long account,UUID job) {
        return transaction(()->{
            var row=lock(account,job);
            if(Set.of("COMPLETED","COMPLETED_WITH_EXCEPTIONS").contains(row.get("status")))return progress(account,job);
            var p=params(account,job);
            jdbc.update("UPDATE cleanup_jobs SET cancel_requested_at=COALESCE(cancel_requested_at,now()) WHERE public_id=:job",p);
            jdbc.update("UPDATE cleanup_job_targets SET state='CANCELLED_BEFORE_DISPATCH',reason='Cancelled before dispatch' WHERE job_id=:job AND state='QUEUED'",p);
            settle(account,job);return progress(account,job);
        });
    }

    public void cancelOwner(String owner) {
        var rows=jdbc.queryForList("SELECT j.account_id,j.public_id FROM cleanup_jobs j JOIN user_accounts a ON a.id=j.account_id WHERE a.owner_key=:owner ORDER BY j.created_at",Map.of("owner",owner));
        for(var row:rows)cancel(((Number)row.get("account_id")).longValue(),(UUID)row.get("public_id"));
    }

    /** A lost dispatch is never replayed. Lease generations fence every later write. */
    public Claim claim() {
        return transaction(()->{
            var rows=jdbc.queryForList("""
                SELECT * FROM cleanup_jobs j WHERE next_attempt_at<=now() AND (worker_id IS NULL OR lease_expires_at<now())
                AND EXISTS(SELECT 1 FROM cleanup_job_targets t WHERE t.job_id=j.public_id AND (t.state IN ('QUEUED','DISPATCH_INTENT') OR (t.state='AWAITING_VERIFICATION' AND t.verification_attempts<6)))
                ORDER BY next_attempt_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """,Map.of());
            if(rows.isEmpty())return null;
            var row=rows.getFirst();UUID job=(UUID)row.get("public_id"),worker=UUID.randomUUID();long account=((Number)row.get("account_id")).longValue();
            var p=params(account,job).addValue("worker",worker);
            jdbc.update("UPDATE cleanup_job_targets SET state='AWAITING_VERIFICATION',reason='Worker lease lost after dispatch intent' WHERE job_id=:job AND state='DISPATCH_INTENT'",p);
            jdbc.update("UPDATE cleanup_batches SET state='AWAITING_VERIFICATION' WHERE job_id=:job AND state='DISPATCH_INTENT'",p);
            if(row.get("cancel_requested_at")!=null)jdbc.update("UPDATE cleanup_job_targets SET state='CANCELLED_BEFORE_DISPATCH',reason='Cancelled before dispatch' WHERE job_id=:job AND state='QUEUED'",p);
            long generation=((Number)row.get("generation")).longValue()+1;p.addValue("generation",generation);
            jdbc.update("UPDATE cleanup_jobs SET worker_id=:worker,generation=:generation,lease_expires_at=now()+interval '5 minutes',status=CASE WHEN cancel_requested_at IS NULL THEN 'RUNNING' ELSE 'CANCELLING' END WHERE public_id=:job",p);
            var targets=targets(job,"QUEUED");boolean verification=targets.isEmpty();
            if(verification)targets=targets(job,"AWAITING_VERIFICATION");
            DashboardFilter filter;
            try{filter=json.treeToValue(json.readTree(row.get("snapshot").toString()).get("filter"),DashboardFilter.class);}
            catch(Exception error){throw new IllegalStateException("Invalid persisted filter",error);}
            event(account,job);
            return new Claim(job,account,worker,generation,filter,targets,verification,row.get("action_type").toString());
        });
    }
    private List<Target> targets(UUID job,String state) {
        return jdbc.query("SELECT ordinal,provider_message_id,message_id,pre_change_observed FROM cleanup_job_targets WHERE job_id=:job AND state=:state AND (state<>'AWAITING_VERIFICATION' OR verification_attempts<6) ORDER BY observed_at NULLS FIRST,ordinal LIMIT "+BATCH_SIZE,
            Map.of("job",job,"state",state),(r,n)->new Target(r.getInt(1),r.getString(2),r.getObject(3,UUID.class),r.getBoolean(4)));
    }
    private boolean owns(Map<String,Object> row,Claim claim) {
        return claim.worker().equals(row.get("worker_id"))&&claim.generation()==((Number)row.get("generation")).longValue()
            &&((java.sql.Timestamp)row.get("lease_expires_at")).toInstant().isAfter(Instant.now());
    }
    /** Recheck current local predicate/protection under the cancellation lock, after provider preflight. */
    public Dispatch dispatch(Claim claim,Map<String,MailMessage> observations) {
        return transaction(()->{
            var row=lock(claim.account(),claim.job());if(!owns(row,claim))return null;
            if(row.get("cancel_requested_at")!=null){release(claim);settle(claim.account(),claim.job());return null;}
            var p=repository.parameters(claim.account(),claim.filter()).addValue("ids",claim.targets().stream().map(Target::messageId).toList()).addValue("action",claim.action());
            Set<UUID> eligible=new HashSet<>(jdbc.queryForList(DashboardRepository.FILTERED+"""
                SELECT dashboard_message_id FROM filtered f WHERE dashboard_message_id IN (:ids)
                AND (:action<>'MARK_READ' OR unread) AND (:action<>'ARCHIVE' OR 'INBOX'=ANY(labels))
                AND NOT EXISTS(SELECT 1 FROM protected_senders s WHERE s.account_id=f.account_id AND s.sender_address=lower(f.address))
                """,p,UUID.class));
            Set<String> recipients=new HashSet<>(jdbc.queryForList("""
                SELECT lower(email) FROM user_accounts WHERE id=:account
                UNION SELECT lower(address) FROM dashboard_verified_aliases WHERE account_id=:account
                """,p,String.class));
            List<Target> sending=new ArrayList<>();
            for(var target:claim.targets()) {
                var observed=observations.get(target.providerId());
                String reason=!eligible.contains(target.messageId())?"No longer eligible or protected":observed==null?"Message missing before dispatch":
                    desired(claim.action(),observed)?"Already in requested state before dispatch":!received(observed,claim.filter(),recipients)?"Provider labels no longer eligible":null;
                if(reason==null)sending.add(new Target(target.ordinal(),target.providerId(),target.messageId(),true));
                else {outcome(claim,target,"SKIPPED",reason);if(observed!=null&&desired(claim.action(),observed))reconcile(claim.account(),observed,claim.action());}
            }
            if(sending.isEmpty()){release(claim);settle(claim.account(),claim.job());return null;}
            UUID batch=UUID.randomUUID();p=params(claim.account(),claim.job()).addValue("batch",batch).addValue("generation",claim.generation());
            jdbc.update("INSERT INTO cleanup_batches(public_id,job_id,account_id,generation,state) VALUES(:batch,:job,:account,:generation,'DISPATCH_INTENT')",p);
            p.addValue("ordinals",sending.stream().map(Target::ordinal).toList());
            jdbc.update("UPDATE cleanup_job_targets SET state='DISPATCH_INTENT',batch_id=:batch,pre_change_observed=true,reason='Request claimed for dispatch' WHERE job_id=:job AND ordinal IN (:ordinals) AND state='QUEUED'",p);
            event(claim.account(),claim.job());return new Dispatch(claim,batch,List.copyOf(sending));
        });
    }
    private static boolean received(MailMessage message,DashboardFilter filter,Set<String> recipients) {
        boolean delivered=message.headers()!=null&&message.headers().entrySet().stream()
            .filter(header->header.getKey().equalsIgnoreCase("Delivered-To"))
            .flatMap(header->header.getValue().stream()).filter(Objects::nonNull)
            .anyMatch(value->recipients.contains(value.replaceAll("^[<> ]+|[<> ]+$", "").toLowerCase(Locale.ROOT)));
        var labels=message.labelIds();return labels!=null&&Collections.disjoint(labels,Set.of("SPAM","TRASH","DRAFT"))
            &&(!filter.scope().equals("inbox")||labels.contains("INBOX"))&&(!labels.contains("SENT")||labels.contains("INBOX")||delivered);
    }
    /** Post-dispatch labels, never an HTTP acknowledgement alone, determine changed counts. */
    public void complete(Claim claim,UUID batch,List<Target> targets,Map<String,MailMessage> observations) {
        complete(claim,batch,targets,observations,null);
    }
    public void complete(Claim claim,UUID batch,List<Target> targets,Map<String,MailMessage> observations,RuntimeException failure) {
        complete(claim,batch,targets,observations,failure,null);
    }
    public void complete(Claim claim,UUID batch,List<Target> targets,Map<String,MailMessage> observations,RuntimeException failure,RuntimeException readFailure) {
        transaction(()->{
            var row=lock(claim.account(),claim.job());if(!owns(row,claim))return null;
            for(var target:targets) {
                var observed=observations.get(target.providerId());
                if(observed!=null&&observed.labelIds()!=null&&desired(claim.action(),observed)) {
                    outcome(claim,target,target.preChangeObserved()?"CHANGED":"SKIPPED",target.preChangeObserved()?"Requested label change confirmed afterward":"Requested state observed without change attribution");
                    reconcile(claim.account(),observed,claim.action());
                } else if(batch!=null && failure instanceof com.tidymail.mail.CleanupProviderException rejected && rejected.rejected()) {
                    outcome(claim,target,"FAILED","Provider rejected mutation; requested change not confirmed");
                } else {
                    outcome(claim,target,"AWAITING_VERIFICATION","Dispatch outcome unresolved; mutation will not be replayed");
                    jdbc.update("UPDATE cleanup_job_targets SET verification_attempts=verification_attempts+1 WHERE job_id=:job AND ordinal=:ordinal",
                        params(claim.account(),claim.job()).addValue("ordinal",target.ordinal()));
                }
            }
            if(batch!=null)jdbc.update("UPDATE cleanup_batches SET state='SETTLED',completed_at=now() WHERE public_id=:batch",Map.of("batch",batch));
            release(claim);
            scheduleRetry(claim,readFailure==null?failure:readFailure,claim.verification(),failure);
            settle(claim.account(),claim.job());return null;
        });
    }
    /** Failed read before dispatch leaves queued work intact, with a persisted delay; cancel bypasses it. */
    public void defer(Claim claim) {defer(claim,null);}
    public void defer(Claim claim,RuntimeException error) {
        if(claim.verification()){complete(claim,null,claim.targets(),Map.of(),error);return;}
        transaction(()->{
            var row=lock(claim.account(),claim.job());if(!owns(row,claim))return null;
            var p=params(claim.account(),claim.job()).addValue("ordinals",claim.targets().stream().map(Target::ordinal).toList());
            if(!claim.targets().isEmpty()) {
                jdbc.update("UPDATE cleanup_job_targets SET read_attempts=read_attempts+1 WHERE job_id=:job AND ordinal IN (:ordinals) AND state='QUEUED'",p);
                jdbc.update("UPDATE cleanup_job_targets SET state='FAILED',reason='Read retry budget exhausted before dispatch' WHERE job_id=:job AND state='QUEUED' AND read_attempts>=6",p);
            }
            release(claim);scheduleRetry(claim,error,false);settle(claim.account(),claim.job());return null;
        });
    }
    /** Persist the wait, release all capacity, and let cancellation bypass it. No sleeping worker. */
    private void scheduleRetry(Claim claim,RuntimeException error,boolean verification) {scheduleRetry(claim,error,verification,null);}
    private void scheduleRetry(Claim claim,RuntimeException error,boolean verification,RuntimeException earlier) {
        var p=params(claim.account(),claim.job());
        int attempts=jdbc.queryForObject("SELECT COALESCE(max(GREATEST(read_attempts,verification_attempts)),0) FROM cleanup_job_targets WHERE job_id=:job",p,Integer.class);
        boolean exhausted=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='AWAITING_VERIFICATION' AND verification_attempts>=6)",p,Boolean.class);
        boolean waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state IN ('QUEUED','AWAITING_VERIFICATION'))",p,Boolean.class);
        boolean unresolved=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='AWAITING_VERIFICATION')",p,Boolean.class);
        String reason=exhausted?"Verification limit reached; unresolved outcomes retained":waiting&&(error!=null||verification||unresolved)?"Waiting to retry provider reads":null;
        long delay=Math.min(64000L,1000L<<Math.min(attempts,6))+java.util.concurrent.ThreadLocalRandom.current().nextLong(251);
        Instant next=Instant.now().plusMillis(delay);
        if(error instanceof com.tidymail.mail.CleanupProviderException provider) {
            if(provider.status()==401){reason="Reconnect Gmail to continue; read retries are bounded";next=Instant.now().plusSeconds(60);}
            if(provider.retryAt()!=null&&provider.retryAt().isAfter(next))next=provider.retryAt();
        }
        if(error instanceof com.tidymail.security.oauth2.ManagedGoogleCredentialService.CredentialAuthorizationException credentials
            && credentials.getCode()==com.tidymail.security.oauth2.ManagedGoogleCredentialService.FailureCode.REAUTH_REQUIRED) {
            reason="Reconnect Gmail to continue; read retries are bounded";next=Instant.now().plusSeconds(60);
        }
        if(earlier instanceof com.tidymail.mail.CleanupProviderException prior && prior.retryAt()!=null&&prior.retryAt().isAfter(next))next=prior.retryAt();
        p.addValue("next",DashboardRepository.timestamp(next)).addValue("reason",reason);
        jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=:next,retry_reason=:reason WHERE public_id=:job",p);
    }
    private static boolean desired(String action,MailMessage observed) {
        if(observed.labelIds()==null)return false;
        return switch(action){case "MARK_READ"->!observed.isUnread();case "ARCHIVE"->!observed.labelIds().contains("INBOX");case "TRASH"->observed.labelIds().contains("TRASH");default->false;};
    }
    private void outcome(Claim claim,Target target,String state,String reason) {
        var p=params(claim.account(),claim.job()).addValue("ordinal",target.ordinal()).addValue("state",state).addValue("reason",reason);
        jdbc.update("UPDATE cleanup_job_targets SET state=:state,reason=:reason,observed_at=now() WHERE job_id=:job AND ordinal=:ordinal AND state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION')",p);
    }
    private void reconcile(long account,MailMessage observed,String action) {
        // Fence an older read against concurrent sync using Gmail's message history revision.
        var p=new MapSqlParameterSource("account",account).addValue("id",observed.id()).addValue("action",action)
            .addValue("revision",observed.providerRevision()==null?null:new java.math.BigDecimal(observed.providerRevision()));
        jdbc.update("""
            UPDATE message_metadata SET unread=CASE WHEN :action='MARK_READ' THEN false ELSE unread END,
            labels=CASE :action WHEN 'MARK_READ' THEN array_remove(labels,'UNREAD')
              WHEN 'ARCHIVE' THEN array_remove(labels,'INBOX')
              WHEN 'TRASH' THEN CASE WHEN 'TRASH'=ANY(labels) THEN labels ELSE array_append(labels,'TRASH') END END,
            provider_revision=GREATEST(provider_revision,CAST(:revision AS numeric))
            WHERE account_id=:account AND provider_message_id=:id
            AND COALESCE(provider_revision,-1)<=COALESCE(CAST(:revision AS numeric),-1)
            """,p);
    }
    private void release(Claim claim) {
        jdbc.update("UPDATE cleanup_jobs SET worker_id=NULL,lease_expires_at=NULL,next_attempt_at=now()+interval '10 seconds' WHERE public_id=:job",Map.of("job",claim.job()));
    }
    private void settle(long account,UUID job) {
        var p=params(account,job);
        jdbc.update("""
            DELETE FROM cleanup_active_targets a USING cleanup_job_targets t
            WHERE a.job_id=:job AND t.job_id=a.job_id AND t.target_fingerprint=a.target_fingerprint
            AND t.state NOT IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION')
            """,p);
        var progress=progress(account,job);
        String status=progress.cancelRequested()?(progress.remaining()>0?"CANCELLING":"CANCELLED"):
            progress.remaining()>0?"QUEUED":progress.unresolved()>0?"VERIFYING":
            progress.failed()>0||progress.skipped()>0?"COMPLETED_WITH_EXCEPTIONS":"COMPLETED";
        p.addValue("status",status).addValue("settled",progress.remaining()==0);
        jdbc.update("UPDATE cleanup_jobs SET status=:status,settled_at=CASE WHEN :settled THEN COALESCE(settled_at,now()) ELSE NULL END WHERE public_id=:job",p);
        event(account,job);
    }
    private Progress progress(long account,UUID job) {
        return jdbc.queryForObject("""
            SELECT j.*,count(*) FILTER(WHERE t.state='CHANGED') changed,count(*) FILTER(WHERE t.state='FAILED') failed,
            count(*) FILTER(WHERE t.state='SKIPPED') skipped,count(*) FILTER(WHERE t.state='AWAITING_VERIFICATION') unresolved,
            count(*) FILTER(WHERE t.state='CANCELLED_BEFORE_DISPATCH') cancelled,
            count(*) FILTER(WHERE t.state IN ('QUEUED','DISPATCH_INTENT')) remaining,count(t.ordinal) actual,
            (SELECT COALESCE(max(e.id),0) FROM cleanup_job_events e WHERE e.job_id=j.public_id) version
            FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id
            WHERE j.account_id=:account AND j.public_id=:job GROUP BY j.public_id
            """,params(account,job),(r,n)->{
                int total=r.getInt("selected_count");
                if(total!=r.getInt("actual"))throw new IllegalStateException("Fixed cleanup target count violated");
                return new Progress(job,r.getString("action_type"),r.getString("status"),total,r.getInt("changed"),r.getInt("failed"),r.getInt("skipped"),r.getInt("unresolved"),r.getInt("cancelled"),r.getInt("remaining"),r.getTimestamp("cancel_requested_at")!=null,r.getTimestamp("created_at").toInstant(),r.getLong("version"),r.getString("retry_reason"));
            });
    }
    private void event(long account,UUID job) {
        var p=progress(account,job);String counts;
        try{counts=json.writeValueAsString(p);}catch(Exception error){throw new IllegalStateException(error);}
        jdbc.update("INSERT INTO cleanup_job_events(job_id,account_id,status,counts) VALUES(:job,:account,:status,CAST(:counts AS jsonb))",params(account,job).addValue("status",p.status()).addValue("counts",counts));
    }
}
