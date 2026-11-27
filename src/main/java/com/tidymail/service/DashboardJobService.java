package com.tidymail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.mail.*;
import java.math.BigInteger;
import org.springframework.jdbc.support.SqlArrayValue;
import com.tidymail.repository.DashboardRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short database transactions only. No provider calls, waits, or mailbox identifiers in responses. */
@Service
public class DashboardJobService {
    static final Set<String> ACTIONS = Set.of("MARK_READ", "ARCHIVE", "TRASH", "MARK_READ_ARCHIVE", "ORGANIZE");
    public static final int BATCH_SIZE=50;
    public static final int MAX_READ_ATTEMPTS=6;
    public static final int RESPONSE_BATCH_SIZE=1000;
    @Value("${app.cleanup.execution-mode:RESPONSE}")
    private String executionMode;
    private final DashboardRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ActionScopeService scopes;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final com.tidymail.mail.outlook.OutlookMailService outlook;
    public DashboardJobService(DashboardRepository repository,ActionScopeService scopes,ObjectMapper json,PlatformTransactionManager manager,
            com.tidymail.mail.outlook.OutlookMailService outlook) {
        this.repository=repository;this.jdbc=repository.jdbc();this.scopes=scopes;this.json=json;this.tx=new TransactionTemplate(manager);this.outlook=outlook;
    }
    public record Progress(UUID id,String action,String status,int total,int changed,int failed,int skipped,
        int unresolved,int cancelled,int remaining,boolean cancelRequested,Instant createdAt,long version,String retryReason,boolean verificationPending,String executionMode,int partialRead,String destinationName) {}
    public record Target(int ordinal,String providerId,UUID messageId,boolean preChangeObserved) {}
    public record Claim(UUID job,long account,UUID worker,long generation,DashboardFilter filter,List<Target> targets,boolean verification,String action,String executionMode) {
        public boolean responseBased(){return "RESPONSE".equals(executionMode);}
        public boolean outlook(){return "OUTLOOK".equals(executionMode);}
        public boolean acknowledged(){return responseBased() || outlook();}
    }
    public record Dispatch(Claim claim,UUID batch,List<Target> targets,Set<String> outlookReadIds) {}

    @jakarta.annotation.PostConstruct
    void validateMode(){configuredMode();}
    private String configuredMode() {
        if(!Set.of("RESPONSE","VERIFIED").contains(executionMode))throw new IllegalStateException("Cleanup execution mode must be RESPONSE or VERIFIED");
        return executionMode;
    }
    private String storedMode(String snapshot) {
        try{String mode=json.readTree(snapshot).path("executionMode").asText();
            return Set.of("RESPONSE","OUTLOOK").contains(mode)?mode:"VERIFIED";}
        catch(Exception error){throw new IllegalStateException("Invalid persisted cleanup mode",error);}
    }

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
            if(!ACTIONS.contains(row.get("action_type"))||!Set.of("google","microsoft").contains(row.get("provider")))throw conflict("Cleanup is unavailable for this provider");
            if(((Number)row.get("grouping_revision")).longValue()!=repository.revision(account))throw conflict("Grouping changed. Create a new preview.");
            if(((Number)row.get("effective_count")).intValue()==0)throw conflict("No eligible changes. Create a new selection.");
            var targets=jdbc.queryForList("SELECT * FROM dashboard_preview_targets WHERE preview_id=:preview AND account_id=:account ORDER BY ordinal",p);
            DashboardFilter filter;
            try { filter=json.treeToValue(json.readTree(row.get("filter_snapshot").toString()).get("filter"),DashboardFilter.class); }
            catch(Exception invalid) { throw conflict("Preview filters are unavailable. Create a new preview."); }
            if(!filter.category().isEmpty() || !filter.recommendation().isEmpty() || !filter.importance().isEmpty() || !filter.label().isEmpty() || !filter.labels().isEmpty() || !filter.composition().isEmpty()) {
                var eligibleIds=targets.stream().filter(t->Boolean.TRUE.equals(t.get("effective"))).map(t->(UUID)t.get("message_id")).toList();
                var current=repository.parameters(account,filter).addValue("ids",new org.springframework.jdbc.support.SqlArrayValue("uuid",eligibleIds.toArray()));
                long matching=jdbc.queryForObject(repository.filtered(account, filter)+" SELECT count(*) FROM filtered WHERE dashboard_message_id=ANY(:ids)",current,Long.class);
                if(matching!=eligibleIds.size()) throw conflict("Labels changed. Create a new preview.");
            }
            var scope=scopes.requireScope(account,((Number)row.get("scope_id")).longValue());
            var ids=targets.stream().map(t->(String)t.get("provider_message_id")).toList();
            if(ids.contains(null)||targets.size()!=((Number)row.get("selected_count")).intValue()
                ||!new HashSet<>(ids).equals(new HashSet<>(scope.getMessageIds()))||ids.size()!=new HashSet<>(ids).size())throw conflict("Preview targets changed. Create a new preview.");
            p.addValue("ids",new org.springframework.jdbc.support.SqlArrayValue("text",ids.toArray()));
            if(jdbc.queryForObject("""
                SELECT count(*) FROM cleanup_active_targets c JOIN cleanup_job_targets t
                ON t.job_id=c.job_id AND t.account_id=c.account_id AND t.target_fingerprint=c.target_fingerprint
                WHERE c.account_id=:account AND t.provider_message_id=ANY(:ids)
                """,p,Long.class)>0)throw conflict("Selected messages already belong to active or unresolved work");
            UUID job=UUID.randomUUID();p.addValue("job",job).addValue("scope",row.get("scope_id"))
                .addValue("action",row.get("action_type")).addValue("total",targets.size()).addValue("snapshot",row.get("filter_snapshot").toString());
            jdbc.update("""
                INSERT INTO cleanup_jobs(public_id,account_id,preview_id,scope_id,action_type,selected_count,snapshot)
                VALUES(:job,:account,:preview,:scope,:action,:total,jsonb_set(CAST(:snapshot AS jsonb),'{executionMode}',to_jsonb(CAST(:mode AS text))))
                """,p.addValue("mode","microsoft".equals(row.get("provider"))?"OUTLOOK":configuredMode()));
            jdbc.update("""
                INSERT INTO cleanup_job_targets(job_id,account_id,ordinal,provider_message_id,message_id,target_fingerprint,state,reason,size_estimate)
                SELECT :job,account_id,ordinal,provider_message_id,message_id,encode(digest(convert_to(provider_message_id,'UTF8'),'sha256'),'hex'),
                CASE WHEN effective THEN 'QUEUED' ELSE 'SKIPPED' END,reason,size_estimate
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
            SELECT public_id FROM cleanup_jobs WHERE account_id=:account AND (
              public_id IN (SELECT public_id FROM cleanup_jobs WHERE account_id=:account ORDER BY created_at DESC,public_id LIMIT 30)
              OR EXISTS(SELECT 1 FROM cleanup_job_targets t WHERE t.job_id=cleanup_jobs.public_id AND t.state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION')))
            ORDER BY created_at DESC,public_id
            """,Map.of("account",account)).stream().map(row->progress(account,(UUID)row.get("public_id"))).toList());
    }
    public record History(List<Progress> rows,long total,int page) {}
    public History history(long account,int page) {
        if(page<0 || page>1_000_000) throw new IllegalArgumentException("Invalid history page");
        return transaction(()->{
            var p=new MapSqlParameterSource("account",account).addValue("offset",page*30L);
            long total=jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs WHERE account_id=:account",p,Long.class);
            var rows=jdbc.queryForList("SELECT public_id FROM cleanup_jobs WHERE account_id=:account ORDER BY created_at DESC,public_id LIMIT 30 OFFSET :offset",p)
                .stream().map(row->progress(account,(UUID)row.get("public_id"))).toList();
            return new History(rows,total,page);
        });
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
            String mode=storedMode(row.get("snapshot").toString());
            int limit = "OUTLOOK".equals(mode) ? 20 : "RESPONSE".equals(mode) ? RESPONSE_BATCH_SIZE : BATCH_SIZE;
            var targets=targets(job,"QUEUED",limit);boolean verification=targets.isEmpty();
            if(verification)targets=targets(job,"AWAITING_VERIFICATION",limit);
            DashboardFilter filter;
            try{filter=json.treeToValue(json.readTree(row.get("snapshot").toString()).get("filter"),DashboardFilter.class);}
            catch(Exception error){throw new IllegalStateException("Invalid persisted filter",error);}
            event(account,job);
            return new Claim(job,account,worker,generation,filter,targets,verification,row.get("action_type").toString(),mode);
        });
    }
    private List<Target> targets(UUID job,String state,int limit) {
        return jdbc.query("SELECT ordinal,provider_message_id,message_id,pre_change_observed FROM cleanup_job_targets WHERE job_id=:job AND state=:state AND (state<>'AWAITING_VERIFICATION' OR verification_attempts<6) ORDER BY observed_at NULLS FIRST,ordinal LIMIT "+limit,
            Map.of("job",job,"state",state),(r,n)->new Target(r.getInt(1),r.getString(2),r.getObject(3,UUID.class),r.getBoolean(4)));
    }
    public MailDestinationService.Choice destination(Claim claim) {return destination(claim.account(),claim.job());}
    private MailDestinationService.Choice destination(long account,UUID job) {
        var snapshot=jdbc.queryForObject("SELECT snapshot::text FROM cleanup_jobs WHERE account_id=:account AND public_id=:job",params(account,job),String.class);
        try {return json.treeToValue(json.readTree(snapshot).get("destination"),MailDestinationService.Choice.class);}
        catch(Exception e){throw new IllegalStateException("Destination unavailable",e);}
    }
    public String destinationId(Claim claim) {return destinationId(claim.job());}
    private String destinationId(UUID job) {
        var ids=jdbc.queryForList("SELECT destination_id FROM cleanup_job_destinations WHERE job_id=:job AND state='READY'",Map.of("job",job),String.class);
        return ids.isEmpty()?null:ids.getFirst();
    }
    /** Commit before provider creation. An abandoned intent is never replayed. */
    public boolean destinationIntent(Claim claim) {
        return transaction(()->{
            var row=lock(claim.account(),claim.job());if(!owns(row,claim)||row.get("cancel_requested_at")!=null)return false;
            return jdbc.update("INSERT INTO cleanup_job_destinations(job_id,state) VALUES(:job,'INTENT') ON CONFLICT DO NOTHING",Map.of("job",claim.job()))==1;
        });
    }
    public void destinationReady(Claim claim,String id) {
        if(id==null || id.isBlank())throw new IllegalStateException("Missing destination identity");
        transaction(()->{
            if(!owns(lock(claim.account(),claim.job()),claim))return null;
            jdbc.update("UPDATE cleanup_job_destinations SET state='READY',destination_id=:id WHERE job_id=:job AND state='INTENT'",Map.of("job",claim.job(),"id",id));
            if(claim.outlook()) {
                var choice=destination(claim);
                jdbc.update("INSERT INTO outlook_folders(account_id,folder_id,folder_path,role) VALUES(:account,:id,:name,'MAIL') ON CONFLICT(account_id,folder_id) DO NOTHING",
                    Map.of("account",claim.account(),"id",id,"name",choice.name()));
            }
            return null;
        });
    }
    public void destinationFailed(Claim claim) {
        transaction(()->{
            if(!owns(lock(claim.account(),claim.job()),claim))return null;
            jdbc.update("UPDATE cleanup_job_destinations SET state='UNKNOWN' WHERE job_id=:job AND state='INTENT'",Map.of("job",claim.job()));
            jdbc.update("UPDATE cleanup_job_targets SET state='FAILED',reason='Destination could not be confirmed; check mailbox destinations before retrying' WHERE job_id=:job AND state='QUEUED'",Map.of("job",claim.job()));
            release(claim);settle(claim.account(),claim.job());
            jdbc.update("UPDATE cleanup_jobs SET retry_reason='Destination could not be confirmed. Check mailbox destinations before retrying.' WHERE public_id=:job",Map.of("job",claim.job()));event(claim.account(),claim.job());return null;
        });
    }
    private boolean owns(Map<String,Object> row,Claim claim) {
        return claim.worker().equals(row.get("worker_id"))&&claim.generation()==((Number)row.get("generation")).longValue()
            &&((java.sql.Timestamp)row.get("lease_expires_at")).toInstant().isAfter(Instant.now());
    }
    /** Recheck current local predicate/protection under the cancellation lock, after provider preflight. */
    public Dispatch dispatch(Claim claim,Map<String,MailMessage> observations) {
        return transaction(()->{
            if (claim.outlook()) lockOutlookAccount(claim);
            var row=lock(claim.account(),claim.job());if(!owns(row,claim))return null;
            if(row.get("cancel_requested_at")!=null){release(claim);settle(claim.account(),claim.job());return null;}
            lockMetadata(claim.account(),claim.targets());
            var dispatchFilter = claim.filter().withoutComposition().cleanupScope();
            var p=repository.parameters(claim.account(),dispatchFilter).addValue("ids",claim.targets().stream().map(Target::messageId).toList()).addValue("action",claim.action());
            if(claim.action().equals("ORGANIZE")) {
                var choice=destination(claim);p.addValue("destination",destinationId(claim)).addValue("skipInbox",choice.skipInbox());
            } else p.addValue("destination","").addValue("skipInbox",false);
            Set<UUID> eligible=new HashSet<>(jdbc.queryForList(repository.filtered(claim.account(), dispatchFilter)+"""
                SELECT dashboard_message_id FROM filtered f WHERE dashboard_message_id IN (:ids)
                AND (:action<>'MARK_READ' OR unread)
                AND (:action<>'ORGANIZE' OR CASE WHEN provider='microsoft' THEN outlook_folder_id IS DISTINCT FROM :destination
                  ELSE NOT(:destination=ANY(labels)) OR (:skipInbox AND 'INBOX'=ANY(labels)) END)
                AND (:action NOT IN ('ARCHIVE','MARK_READ_ARCHIVE') OR (:action='MARK_READ_ARCHIVE' AND unread)
                  OR CASE WHEN provider='microsoft' THEN NOT('OUTLOOK_ARCHIVE'=ANY(labels)) ELSE 'INBOX'=ANY(labels) END)
                AND NOT EXISTS(SELECT 1 FROM protected_senders s WHERE s.account_id=f.account_id
                  AND s.sender_address=lower(COALESCE(f.dashboard_from_address,f.from_address,'')))
                """,p,UUID.class));
            Set<String> recipients=new HashSet<>(jdbc.queryForList("""
                SELECT lower(email) FROM user_accounts WHERE id=:account
                UNION SELECT lower(address) FROM dashboard_verified_aliases WHERE account_id=:account
                """,p,String.class));
            Set<String> unread = claim.outlook() && claim.action().equals("MARK_READ_ARCHIVE")
                ? new HashSet<>(jdbc.queryForList("SELECT provider_message_id FROM message_metadata WHERE account_id=:account AND dashboard_message_id IN (:ids) AND unread", p, String.class))
                : Set.of();
            List<Target> sending=new ArrayList<>();
            for(var target:claim.targets()) {
                var observed=observations.get(target.providerId());
                String reason=!eligible.contains(target.messageId())?"No longer eligible or protected":claim.acknowledged()?null:observed==null?"Message missing before dispatch":
                    desired(claim.job(),claim.action(),observed)?"Already in requested state before dispatch":!received(observed,claim.filter(),recipients)?"Provider labels no longer eligible":null;
                if(reason==null) {
                    sending.add(new Target(target.ordinal(),target.providerId(),target.messageId(),true));
                    if (unread.contains(target.providerId()))
                        jdbc.update("UPDATE cleanup_job_targets SET outlook_read_required=true WHERE job_id=:job AND ordinal=:ordinal",
                            params(claim.account(),claim.job()).addValue("ordinal",target.ordinal()));
                }
                else {outcome(claim,target,"SKIPPED",reason);if(observed!=null&&desired(claim.job(),claim.action(),observed))reconcile(claim.account(),claim.job(),observed,claim.action());}
            }
            if(sending.isEmpty()){release(claim);settle(claim.account(),claim.job());return null;}
            UUID batch=UUID.randomUUID();p=params(claim.account(),claim.job()).addValue("batch",batch).addValue("generation",claim.generation());
            jdbc.update("INSERT INTO cleanup_batches(public_id,job_id,account_id,generation,state) VALUES(:batch,:job,:account,:generation,'DISPATCH_INTENT')",p);
            p.addValue("ordinals",sending.stream().map(Target::ordinal).toList());
            jdbc.update("UPDATE cleanup_job_targets SET state='DISPATCH_INTENT',batch_id=:batch,pre_change_observed=true,reason='Request claimed for dispatch' WHERE job_id=:job AND ordinal IN (:ordinals) AND state='QUEUED'",p);
            event(claim.account(),claim.job());return new Dispatch(claim,batch,List.copyOf(sending),Set.copyOf(unread));
        });
    }
    private static boolean received(MailMessage message,DashboardFilter filter,Set<String> recipients) {
        boolean delivered=message.headers()!=null&&message.headers().entrySet().stream()
            .filter(header->header.getKey().equalsIgnoreCase("Delivered-To"))
            .flatMap(header->header.getValue().stream()).filter(Objects::nonNull)
            .anyMatch(value->recipients.contains(value.replaceAll("^[<> ]+|[<> ]+$", "").toLowerCase(Locale.ROOT)));
        var labels=message.labelIds();
        if ("microsoft".equals(message.provider()))
            return labels!=null&&Collections.disjoint(labels,Set.of("SPAM","TRASH","DRAFT","SENT","OUTLOOK_EXCLUDED"))
                &&(!filter.scope().equals("inbox")||labels.contains("INBOX"));
        return labels!=null&&Collections.disjoint(labels,Set.of("SPAM","TRASH","DRAFT"))
            &&(!filter.scope().equals("inbox")||labels.contains("INBOX"))&&(!labels.contains("SENT")||labels.contains("INBOX")||delivered||filter.suggestion().equals("large"));
    }

    /** Gmail's single result and Graph's individual results share the same completion path. */
    public void completeAcknowledged(Dispatch dispatch,RuntimeException failure) {
        Map<String,CleanupResult> results=new HashMap<>();
        dispatch.targets().forEach(t -> results.put(t.providerId(),new CleanupResult(null,failure)));
        completeAcknowledged(dispatch,results,false);
    }

    public void completeAcknowledged(Dispatch dispatch,Map<String,CleanupResult> results,boolean readStep) {
        var claim=dispatch.claim();
        if(!claim.acknowledged())throw new IllegalArgumentException("Acknowledgement-based cleanup required");
        transaction(()->{
            if(claim.outlook())lockOutlookAccount(claim);
            if(!owns(lock(claim.account(),claim.job()),claim)) {
                if(readStep)throw new IllegalStateException("Cleanup lease expired before the archive step");
                return null;
            }
            lockMetadata(claim.account(),dispatch.targets());
            Map<String,List<Integer>> states=new LinkedHashMap<>();
            Map<String,BigInteger> accepted=new LinkedHashMap<>();
            var unknown=new CleanupResult(null,new CleanupProviderException(0,null));
            for(var target:dispatch.targets()) {
                var result=results.getOrDefault(target.providerId(),unknown);
                if(readStep && !result.accepted())continue;
                var error=result.failure();
                String state=result.accepted()?(readStep?"DISPATCH_INTENT":"CHANGED"):
                    error instanceof CleanupProviderException provider && provider.retryableRejection()?"QUEUED":
                    error instanceof CleanupProviderException provider && provider.rejected()?"FAILED":"AWAITING_VERIFICATION";
                states.computeIfAbsent(state,ignored->new ArrayList<>()).add(target.ordinal());
                if(result.accepted())accepted.put(target.providerId(),result.revision());
            }
            applyLocal(claim.account(),claim.job(),readStep?"MARK_READ":claim.action(),accepted,true);
            for(var state:states.entrySet()) {
                var p=params(claim.account(),claim.job()).addValue("ordinals",state.getValue())
                    .addValue("state",state.getKey()).addValue("readStep",readStep);
                jdbc.update("""
                    UPDATE cleanup_job_targets SET
                    state=CASE WHEN :state='QUEUED' AND read_attempts+1>=6 THEN 'FAILED' ELSE :state END,
                    read_attempts=read_attempts+CASE WHEN :state='QUEUED' THEN 1 ELSE 0 END,
                    outlook_read_completed=outlook_read_completed OR :readStep,
                    reason=CASE :state WHEN 'CHANGED' THEN 'Provider accepted the requested change'
                      WHEN 'QUEUED' THEN 'Provider rejected the request for throttling'
                      WHEN 'FAILED' THEN 'Provider rejected the requested change'
                      ELSE 'Dispatch outcome awaiting reconciliation; mutation will not be replayed' END,
                    observed_at=now()
                    WHERE job_id=:job AND ordinal IN (:ordinals) AND state='DISPATCH_INTENT'
                    """,p);
                if(claim.outlook() && state.getKey().equals("AWAITING_VERIFICATION"))jdbc.update("""
                    INSERT INTO outlook_reconcile(account_id,message_id)
                    SELECT account_id,provider_message_id FROM cleanup_job_targets WHERE job_id=:job AND ordinal IN (:ordinals)
                    ON CONFLICT DO NOTHING
                    """,p);
            }
            if(readStep)event(claim.account(),claim.job());
            else {
                var retry=results.values().stream().map(CleanupResult::failure)
                    .filter(error->error instanceof CleanupProviderException provider && (provider.retryableRejection() || provider.status()==401)
                        || error instanceof com.tidymail.security.oauth2.ManagedGoogleCredentialService.CredentialAuthorizationException)
                    .max(Comparator.comparing(error->error instanceof CleanupProviderException provider && provider.retryAt()!=null?provider.retryAt():Instant.EPOCH)).orElse(null);
                jdbc.update("UPDATE cleanup_batches SET state='SETTLED',completed_at=now() WHERE public_id=:batch",Map.of("batch",dispatch.batch()));
                release(claim);scheduleRetry(claim,retry,false);settle(claim.account(),claim.job());
            }
            return null;
        });
    }

    /** Shared label/read projection for acknowledgements and revision-fenced Gmail recovery. One SQL update per batch. */
    private void applyLocal(long account,UUID job,String action,Map<String,BigInteger> revisions,boolean acknowledged) {
        if(revisions.isEmpty())return;
        var choice="ORGANIZE".equals(action)?destination(account,job):null;
        var p=new MapSqlParameterSource("account",account).addValue("action",action).addValue("ack",acknowledged)
            .addValue("destination",choice==null?"":destinationId(job)).addValue("skipInbox",choice!=null&&choice.skipInbox())
            .addValue("ids",new SqlArrayValue("text",revisions.keySet().toArray()))
            .addValue("revisions",new SqlArrayValue("numeric",revisions.values().stream()
                .map(r->r==null?null:new java.math.BigDecimal(r)).toArray()))
            .addValue("role",action.equals("TRASH")?"TRASH":"OUTLOOK_ARCHIVE");
        jdbc.update("""
            WITH changes AS (
              SELECT m.id,m.provider,r.revision,
                CASE WHEN :action IN ('MARK_READ','MARK_READ_ARCHIVE') THEN array_remove(m.labels,'UNREAD') ELSE m.labels END AS labels
              FROM message_metadata m JOIN unnest(CAST(:ids AS text[]),CAST(:revisions AS numeric[])) r(id,revision)
                ON r.id=m.provider_message_id
              WHERE m.account_id=:account AND ((:ack AND r.revision IS NULL) OR COALESCE(m.provider_revision,-1)<=COALESCE(r.revision,-1))
            )
            UPDATE message_metadata m SET
              unread=CASE WHEN :action IN ('MARK_READ','MARK_READ_ARCHIVE') THEN false ELSE m.unread END,
              labels=CASE WHEN :action='ORGANIZE' THEN
                CASE WHEN c.provider='microsoft' THEN array_remove(array_remove(c.labels,'INBOX'),'OUTLOOK_ARCHIVE')
                ELSE array_append(array_remove(CASE WHEN :skipInbox THEN array_remove(c.labels,'INBOX') ELSE c.labels END,:destination),:destination) END
                WHEN :action='MARK_READ' THEN c.labels
                WHEN c.provider='microsoft' THEN array_append(array_remove(array_remove(c.labels,'INBOX'),'OUTLOOK_ARCHIVE'),:role)
                WHEN :action='TRASH' THEN CASE WHEN 'TRASH'=ANY(c.labels) THEN c.labels ELSE array_append(c.labels,'TRASH') END
                ELSE array_remove(c.labels,'INBOX') END,
              outlook_folder_id=CASE WHEN c.provider='microsoft' AND :action='ORGANIZE' THEN :destination WHEN c.provider='microsoft' AND :action<>'MARK_READ' THEN COALESCE(
                (SELECT folder_id FROM outlook_folders WHERE account_id=:account AND role=:role LIMIT 1),m.outlook_folder_id) ELSE m.outlook_folder_id END,
              provider_revision=GREATEST(m.provider_revision,c.revision),
              cleanup_updated_at=CASE WHEN :ack THEN clock_timestamp() ELSE m.cleanup_updated_at END
            FROM changes c WHERE m.id=c.id
            """,p);
    }

    /** Called by the existing background sync after observing an uncertain cleanup target. */
    public void reconcileOutlookSync(long account, MailMessage message) {
        var p = new MapSqlParameterSource("account", account).addValue("id", message.id())
            .addValue("observed", DashboardRepository.timestamp(message.observationStartedAt()))
            .addValue("revision", new java.math.BigDecimal(message.providerRevision()));
        var rows = jdbc.queryForList("""
            SELECT DISTINCT j.public_id,j.action_type FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id
            WHERE j.account_id=:account AND t.provider_message_id=:id AND t.state='AWAITING_VERIFICATION'
              AND EXISTS(SELECT 1 FROM message_metadata m WHERE m.account_id=:account AND m.provider_message_id=:id
                AND COALESCE(m.provider_revision,-1)<=CAST(:revision AS numeric)
                AND (m.cleanup_updated_at IS NULL OR m.cleanup_updated_at<=:observed))
            """, p);
        for (var row : rows) {
            UUID job = (UUID)row.get("public_id");
            lock(account, job);
            p.addValue("job", job);
            if(observe(account,job,row.get("action_type").toString(),message,false))settle(account,job);
            else event(account,job);
        }
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
            if (claim.outlook()) lockOutlookAccount(claim);
            var row=lock(claim.account(),claim.job());if(!owns(row,claim))return null;
            lockMetadata(claim.account(),targets);
            for(var target:targets) {
                var observed=observations.get(target.providerId());
                if(observe(claim.account(),claim.job(),claim.action(),observed,true))continue;
                if(batch!=null && failure instanceof CleanupProviderException rejected && rejected.rejected()) {
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
        boolean exhausted=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='AWAITING_VERIFICATION' AND verification_attempts>=6) AND NOT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='AWAITING_VERIFICATION' AND verification_attempts<6)",p,Boolean.class);
        boolean queued=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='QUEUED')",p,Boolean.class);
        boolean waiting=queued || jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='AWAITING_VERIFICATION' AND verification_attempts<6)",p,Boolean.class);
        boolean unresolved=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cleanup_job_targets WHERE job_id=:job AND state='AWAITING_VERIFICATION')",p,Boolean.class);
        String providerName=claim.outlook()?"Outlook":"Gmail";
        String reason=exhausted?"Verification limit reached; unresolved outcomes retained"
            :waiting&&(error!=null||verification||unresolved)?"Waiting to retry provider reads":null;
        if(waiting && claim.acknowledged() && !verification
                && error instanceof CleanupProviderException provider && provider.retryableRejection())
            reason=provider.status()==429?"Waiting for "+providerName+" rate limit to clear"
                :"Waiting to retry "+providerName+" before dispatch";
        long delay=error==null&&!verification&&(queued||!unresolved)?0:
            Math.min(64000L,1000L<<Math.min(attempts,6))+java.util.concurrent.ThreadLocalRandom.current().nextLong(251);
        Instant next=Instant.now().plusMillis(delay);
        if(waiting && error instanceof com.tidymail.mail.CleanupProviderException provider) {
            if(provider.status()==401){reason="Reconnect "+providerName+" to continue; read retries are bounded";next=Instant.now().plusSeconds(60);}
            if(provider.retryAt()!=null&&provider.retryAt().isAfter(next))next=provider.retryAt();
        }
        if(waiting && error instanceof com.tidymail.security.oauth2.ManagedGoogleCredentialService.CredentialAuthorizationException credentials
            && credentials.getCode()==com.tidymail.security.oauth2.ManagedGoogleCredentialService.FailureCode.REAUTH_REQUIRED) {
            reason="Reconnect "+providerName+" to continue; read retries are bounded";next=Instant.now().plusSeconds(60);
        }
        if(earlier instanceof com.tidymail.mail.CleanupProviderException prior && prior.retryAt()!=null&&prior.retryAt().isAfter(next))next=prior.retryAt();
        p.addValue("next",DashboardRepository.timestamp(next)).addValue("reason",reason);
        jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=:next,retry_reason=:reason WHERE public_id=:job",p);
    }
    /** One observation path for cleanup recovery and the existing Outlook background sync. */
    private boolean observe(long account,UUID job,String action,MailMessage message,boolean save) {
        if(message==null)return false;
        boolean changed=desired(job,action,message);
        if(save && (changed || "microsoft".equals(message.provider())))reconcile(account,job,message,action);
        var p=params(account,job).addValue("id",message.id());
        if(!message.isUnread())jdbc.update("""
            UPDATE cleanup_job_targets SET outlook_read_completed=true
            WHERE job_id=:job AND provider_message_id=:id AND outlook_read_required
            """,p);
        if(changed)jdbc.update("""
            UPDATE cleanup_job_targets SET state=CASE WHEN pre_change_observed THEN 'CHANGED' ELSE 'SKIPPED' END,
            reason='Requested state confirmed by provider observation',observed_at=now()
            WHERE job_id=:job AND provider_message_id=:id AND state IN ('DISPATCH_INTENT','AWAITING_VERIFICATION')
            """,p);
        return changed;
    }

    private boolean desired(UUID job,String action,MailMessage observed) {
        if(observed.labelIds()==null)return false;
        if(action.equals("ORGANIZE")) {
            String id=destinationId(job);if(id==null)return false;
            if("microsoft".equals(observed.provider()))return observed.outlook()!=null&&id.equals(observed.outlook().folderId());
            var choice=jdbc.queryForObject("SELECT (snapshot->'destination'->>'skipInbox')::boolean FROM cleanup_jobs WHERE public_id=:job",Map.of("job",job),Boolean.class);
            return observed.labelIds().contains(id)&&(!Boolean.TRUE.equals(choice)||!observed.labelIds().contains("INBOX"));
        }
        boolean archived = "microsoft".equals(observed.provider())
            ? observed.labelIds().contains("OUTLOOK_ARCHIVE") : !observed.labelIds().contains("INBOX");
        return switch(action){case "MARK_READ"->!observed.isUnread();case "ARCHIVE"->archived;case "MARK_READ_ARCHIVE"->!observed.isUnread()&&archived;case "TRASH"->observed.labelIds().contains("TRASH");default->false;};
    }
    private void outcome(Claim claim,Target target,String state,String reason) {
        var p=params(claim.account(),claim.job()).addValue("ordinal",target.ordinal()).addValue("state",state).addValue("reason",reason);
        jdbc.update("UPDATE cleanup_job_targets SET state=:state,reason=:reason,observed_at=now() WHERE job_id=:job AND ordinal=:ordinal AND state IN ('QUEUED','DISPATCH_INTENT','AWAITING_VERIFICATION')",p);
    }
    private void lockOutlookAccount(Claim claim) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=:account FOR UPDATE",
            params(claim.account(),claim.job()), Long.class);
    }
    private void lockMetadata(long account,List<Target> targets) {
        if(targets.isEmpty())return;
        jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=:account AND provider_message_id IN (:ids) ORDER BY provider_message_id FOR UPDATE",
            new MapSqlParameterSource("account",account).addValue("ids",targets.stream().map(Target::providerId).toList()),Long.class);
    }
    private void reconcile(long account,UUID job,MailMessage observed,String action) {
        if ("microsoft".equals(observed.provider())) {
            // The target already exists or was removed by sync. Never recreate a missing cleanup target.
            var p = new MapSqlParameterSource("account", account).addValue("id", observed.id());
            if (!jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=:account AND provider_message_id=:id", p).isEmpty())
                outlook.save(account, observed, null);
            return;
        }
        Map<String,BigInteger> revisions=new HashMap<>();
        revisions.put(observed.id(),observed.providerRevision());
        applyLocal(account,job,action,revisions,false);
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
            progress.remaining()>0?"QUEUED":progress.verificationPending()?"VERIFYING":
            progress.failed()>0||progress.skipped()>0||progress.unresolved()>0?"COMPLETED_WITH_EXCEPTIONS":"COMPLETED";
        p.addValue("status",status).addValue("settled",progress.remaining()==0&&!progress.verificationPending());
        jdbc.update("UPDATE cleanup_jobs SET status=:status,settled_at=CASE WHEN :settled THEN COALESCE(settled_at,now()) ELSE NULL END WHERE public_id=:job",p);
        event(account,job);
    }
    private Progress progress(long account,UUID job) {
        return jdbc.queryForObject("""
            SELECT j.*,count(*) FILTER(WHERE t.state='CHANGED') changed,count(*) FILTER(WHERE t.state='FAILED') failed,
            count(*) FILTER(WHERE t.outlook_read_completed AND t.state<>'CHANGED') partial_read,
            count(*) FILTER(WHERE t.state='SKIPPED') skipped,count(*) FILTER(WHERE t.state='AWAITING_VERIFICATION') unresolved,
            count(*) FILTER(WHERE t.state='CANCELLED_BEFORE_DISPATCH') cancelled,
            count(*) FILTER(WHERE t.state='AWAITING_VERIFICATION' AND t.verification_attempts<6) verification_pending,
            count(*) FILTER(WHERE t.state IN ('QUEUED','DISPATCH_INTENT')) remaining,count(t.ordinal) actual,
            (SELECT COALESCE(max(e.id),0) FROM cleanup_job_events e WHERE e.job_id=j.public_id) version
            FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id
            WHERE j.account_id=:account AND j.public_id=:job GROUP BY j.public_id
            """,params(account,job),(r,n)->{
                int total=r.getInt("selected_count");
                if(total!=r.getInt("actual"))throw new IllegalStateException("Fixed cleanup target count violated");
                boolean verificationPending=r.getInt("verification_pending")>0;
                // Normalize older saved jobs too; exhausted unknown outcomes are retained, not active.
                String status=r.getString("status");
                if(r.getInt("remaining")==0 && r.getInt("unresolved")>0 && !verificationPending)
                    status=r.getTimestamp("cancel_requested_at")!=null?"CANCELLED":"COMPLETED_WITH_EXCEPTIONS";
                return new Progress(job,r.getString("action_type"),status,total,r.getInt("changed"),r.getInt("failed"),r.getInt("skipped"),r.getInt("unresolved"),r.getInt("cancelled"),r.getInt("remaining"),r.getTimestamp("cancel_requested_at")!=null,r.getTimestamp("created_at").toInstant(),r.getLong("version"),r.getString("retry_reason"),verificationPending,storedMode(r.getString("snapshot")),r.getInt("partial_read"),destinationName(r.getString("snapshot")));
            });
    }
    private String destinationName(String snapshot) {
        try{return json.readTree(snapshot).path("destination").path("name").asText("");}
        catch(Exception e){throw new IllegalStateException("Invalid job snapshot",e);}
    }
    private void event(long account,UUID job) {
        var p=progress(account,job);String counts;
        try{counts=json.writeValueAsString(p);}catch(Exception error){throw new IllegalStateException(error);}
        jdbc.update("INSERT INTO cleanup_job_events(job_id,account_id,status,counts) VALUES(:job,:account,:status,CAST(:counts AS jsonb))",params(account,job).addValue("status",p.status()).addValue("counts",counts));
    }
}
