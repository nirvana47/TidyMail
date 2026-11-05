package com.tidymail.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.tidymail.service.RecommendationSemanticContract.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded reviewed work, independent of local recommendation actions. No cleanup transport. */
@Service
public class LabelingPipeline {
    public static final int MAX_SCOPE=1000;
    public static final BigDecimal MAX_REVIEWED_SPEND=new BigDecimal("100.00");
    // Same conservative full-context-per-question reservation as the frozen pilot.
    public static final BigDecimal INPUT_PRICE=new BigDecimal("0.000000042");
    public static final BigDecimal RESERVATION=INPUT_PRICE.multiply(BigDecimal.valueOf(262144L*(1+Property.values().length)));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final RecommendationService recommendations;
    private final MessageEvidenceService mime;
    private final JevClassifier jev;
    private final ClassificationBudget budget;
    private final RecommendationSemanticContract contract;
    public LabelingPipeline(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,RecommendationService recommendations,
                            MessageEvidenceService mime,JevClassifier jev,ClassificationBudget budget) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.recommendations=recommendations;this.mime=mime;this.jev=jev;this.budget=budget;
        contract=new RecommendationSemanticContract(json);
    }
    public record Prepared(UUID id,String hash,JsonNode manifest) {}
    public record WorkStatus(UUID message,String state,int tries,String error,java.time.Instant nextAttempt,boolean recoverable) {}
    public record ScopeStatus(Prepared scope,boolean authorized,boolean paused,long mimeRequests,long mimeBytes,
                              BigDecimal reported,BigDecimal reserved,List<WorkStatus> work,Spending spending) {}
    public record Spending(BigDecimal batchLimit,BigDecimal sharedReported,BigDecimal sharedLimit,BigDecimal sharedReserved,
                           BigDecimal reservation,boolean canIncrease,boolean canStart,String blockedReason,boolean recoveryAvailable) {}
    /** Reopen the exact durable review and progress after restart; no provider work or writes. */
    public ScopeStatus scopeStatus(long account,UUID scope) {
        return scopeStatus(account,scope,true);
    }
    public ScopeStatus progress(long account,UUID scope) {return scopeStatus(account,scope,false);}
    private ScopeStatus scopeStatus(long account,UUID scope,boolean includeManifest) {
        var rows=jdbc.query("""
            SELECT s.kind,s.max_spend,s.manifest_hash,s.authorized,s.paused,s.mime_requests,s.mime_bytes,CASE WHEN ? THEN s.manifest::text END manifest_text,
              COALESCE((SELECT sum(a.cost) FROM labeling_exposures e JOIN classification_attempts a ON a.id=e.attempt_id
                WHERE e.scope_id=s.id AND a.state='REPORTED'),0) reported,
              COALESCE((SELECT sum(e.reserved) FROM labeling_exposures e JOIN classification_attempts a ON a.id=e.attempt_id
                WHERE e.scope_id=s.id AND a.state<>'REPORTED'),0) reserved
            FROM labeling_scopes s WHERE s.account_id=? AND s.id=?
            """,(r,n)->new ScopeStatus(new Prepared(scope,r.getString("manifest_hash"),r.getString("manifest_text")==null?null:decode(r.getString("manifest_text"))),
                r.getBoolean("authorized"),r.getBoolean("paused"),r.getLong("mime_requests"),r.getLong("mime_bytes"),
                r.getBigDecimal("reported"),r.getBigDecimal("reserved"),List.of(),
                "JEV".equals(r.getString("kind"))?new Spending(r.getBigDecimal("max_spend"),null,null,null,RESERVATION,false,false,null,false):null),includeManifest,account,scope);
        if(rows.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Labeling scope unavailable for this account");
        var saved=rows.getFirst();
        var work=jdbc.query("""
            SELECT message_id,state,tries,error_code,next_attempt_at,state='DISPATCHED' AND response IS NOT NULL recoverable FROM labeling_work WHERE account_id=? AND scope_id=? ORDER BY message_id
            """,(r,n)->new WorkStatus(r.getObject("message_id",UUID.class),r.getString("state"),r.getInt("tries"),r.getString("error_code"),
                r.getTimestamp("next_attempt_at").toInstant(),r.getBoolean("recoverable")),account,scope);
        return new ScopeStatus(saved.scope(),saved.authorized(),saved.paused(),saved.mimeRequests(),saved.mimeBytes(),saved.reported(),saved.reserved(),work,spending(account,saved,work));
    }
    private Spending spending(long account,ScopeStatus saved,List<WorkStatus> work) {
        if(saved.spending()==null)return null;
        var view=budget.view(account);
        var b=jdbc.queryForMap("""
            SELECT labeling_reported,labeling_checkpoint,COALESCE((SELECT sum(e.reserved) FROM labeling_exposures e
              JOIN classification_attempts a ON a.id=e.attempt_id WHERE a.state<>'REPORTED'),0) reserved
            FROM classification_budget WHERE id=1
            """);
        BigDecimal shared=(BigDecimal)b.get("labeling_checkpoint"),reported=(BigDecimal)b.get("labeling_reported"),reserved=(BigDecimal)b.get("reserved"),cap=saved.spending().batchLimit();
        String reason=view.unknown()>0?"UNKNOWN_CHARGES":view.inFlight()>0?"IN_FLIGHT":
            reported.add(reserved).add(RESERVATION).compareTo(shared)>0?"SHARED_LIMIT":
            saved.reported().add(saved.reserved()).add(RESERVATION).compareTo(cap)>0?"BATCH_LIMIT":
            view.reported().add(RESERVATION).compareTo(view.checkpoint())>0?"DEPLOYMENT_LIMIT":null;
        boolean pending=work.stream().anyMatch(w->w.state().equals("PENDING"));
        boolean recovery=work.stream().anyMatch(WorkStatus::recoverable);
        return new Spending(cap,reported,shared,reserved,RESERVATION,
            view.canAuthorize() && view.inFlight()==0 && view.unknown()==0 && pending && (!saved.authorized() || saved.paused()),reason==null || recovery,reason,recovery);
    }
    public record AllowanceReview(String hash,JsonNode change) {}
    /** Inert financial revision of the same messages and requests. No provider work or limit changes. */
    public AllowanceReview reviewAllowance(long account,UUID scope,String baseHash,BigDecimal newLimit) {
        return tx.execute(status->{budget.lock();return allowancePlan(account,scope,baseHash,newLimit);});
    }
    private AllowanceReview allowancePlan(long account,UUID scope,String baseHash,BigDecimal newLimit) {
        var saved=scopeStatus(account,scope);var view=budget.view(account);
        if(!view.canAuthorize())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Only the deployment allowance owner can increase spending");
        if(!saved.scope().hash().equals(baseHash))throw new IllegalArgumentException("This review changed. Reopen the batch before changing its limit");
        if(saved.spending()==null || !validCap(newLimit))throw new IllegalArgumentException("Choose a Jev limit between $0.14 and $100, with at most two decimal places");
        if(!saved.spending().canIncrease() || saved.work().stream().anyMatch(w->Set.of("LEASED","DISPATCHED").contains(w.state())))
            throw new IllegalArgumentException("Pause this batch and wait for requests and unknown charges to settle before increasing its allowance");
        BigDecimal next=newLimit.stripTrailingZeros(),old=saved.spending().batchLimit(),shared=saved.spending().sharedLimit(),nextShared=shared.max(next);
        if(next.compareTo(old)<0 || next.compareTo(old)==0 && nextShared.compareTo(shared)==0)
            throw new IllegalArgumentException("Enter a higher total limit for this batch or its shared labeling allowance");
        BigDecimal nextDeployment=view.checkpoint().max(view.reported().add(nextShared.subtract(saved.spending().sharedReported()).max(BigDecimal.ZERO)));
        var change=json.createObjectNode().put("account",account).put("scope",scope.toString()).put("baseHash",baseHash)
            .put("batchBefore",old.toPlainString()).put("sharedBefore",shared.toPlainString()).put("deploymentBefore",view.checkpoint().toPlainString())
            .put("batchSpent",saved.reported().toPlainString()).put("sharedSpent",saved.spending().sharedReported().toPlainString()).put("deploymentSpent",view.reported().toPlainString())
            .put("newBatchLimit",next.toPlainString()).put("newSharedLimit",nextShared.toPlainString()).put("newDeploymentLimit",nextDeployment.toPlainString()).put("generation",view.generation());
        return new AllowanceReview(CalendarEvidenceParser.hash(change.toString()),change);
    }
    /** Applies an explicitly reviewed revision, preserving paid work, requests and prior approval history. Does not resume. */
    public ScopeStatus confirmAllowance(long account,UUID scope,String baseHash,BigDecimal newLimit,String reviewedHash) {
        return tx.execute(status->{
            budget.lock();
            jdbc.queryForList("SELECT id FROM labeling_scopes WHERE id=? AND account_id=? FOR UPDATE",UUID.class,scope,account);
            var saved=scopeStatus(account,scope);
            for(var previous:saved.scope().manifest().path("allowanceApprovals"))if(previous.path("reviewHash").asText().equals(reviewedHash)) {
                if(newLimit==null || new BigDecimal(previous.path("newBatchLimit").asText()).compareTo(newLimit)!=0)throw new IllegalArgumentException("Reviewed allowance did not match");
                return progress(account,scope); // Idempotent confirmation never grants money twice or resumes work.
            }
            var plan=allowancePlan(account,scope,baseHash,newLimit);
            if(!plan.hash().equals(reviewedHash))throw new IllegalArgumentException("Spending changed since this review. Review the new limit again");
            var change=(ObjectNode)plan.change();var manifest=((ObjectNode)saved.scope().manifest()).deepCopy();
            manifest.put("maxSpend",change.path("newBatchLimit").asText()).put("sharedNewLabelingAllowance",change.path("newSharedLimit").asText());
            var approvals=manifest.withArray("allowanceApprovals");var audit=change.deepCopy().put("reviewHash",reviewedHash).put("approvedAt",java.time.Instant.now().toString());approvals.add(audit);
            String hash=CalendarEvidenceParser.hash(manifest.toString());
            jdbc.update("UPDATE classification_budget SET labeling_checkpoint=?,checkpoint=?,generation=generation+1 WHERE id=1",
                new BigDecimal(change.path("newSharedLimit").asText()),new BigDecimal(change.path("newDeploymentLimit").asText()));
            jdbc.update("UPDATE labeling_scopes SET manifest=?::jsonb,manifest_hash=?,max_spend=?,paused=authorized WHERE id=? AND account_id=? AND manifest_hash=?",
                manifest.toString(),hash,newLimit,scope,account,baseHash);
            jdbc.update("UPDATE labeling_work SET next_attempt_at=now(),error_code=NULL WHERE scope_id=? AND account_id=? AND state='PENDING' AND error_code='ALLOWANCE_OR_ROUTE_PAUSED'",scope,account);
            return progress(account,scope);
        });
    }
    private static boolean validCap(BigDecimal cap) {return cap!=null && cap.compareTo(RESERVATION)>=0 && cap.compareTo(MAX_REVIEWED_SPEND)<=0 && cap.stripTrailingZeros().scale()<=2;}
    public record Coverage(long indexed,long mimeObserved,long mimeUnresolved,long semanticCurrent,long pending,long unresolved,long failed,
                           long awaitingApproval,long paused,long completed,java.time.Instant updatedAt) {}
    public Coverage coverage(long account) {
        return jdbc.queryForObject("""
            SELECT GREATEST(max(s.saved_at),max(e.observed_at)) updated_at,count(*) indexed,count(*) FILTER(WHERE e.evidence_version IN ('mime-presence-v1','mime-ics-v3')) observed,
              count(*) FILTER(WHERE e.evidence_version IS NULL OR e.evidence_version NOT IN ('mime-presence-v1','mime-ics-v3') OR COALESCE(e.facts->>'structureState','UNKNOWN')<>'AVAILABLE') mime_unresolved,
              count(*) FILTER(WHERE s.input_hash=labeling_input_hash(m,h,s.variant)) labeled,
              (SELECT count(*) FROM labeling_work w JOIN labeling_scopes q ON q.id=w.scope_id WHERE w.account_id=?
                AND (w.state IN ('LEASED','DISPATCHED') OR (w.state='PENDING' AND q.authorized AND NOT q.paused))) pending,
              count(*) FILTER(WHERE s.input_hash=labeling_input_hash(m,h,s.variant) AND
                (s.judgment->'purpose'->>'selected'='UNKNOWN'
                  OR (s.judgment->'purpose'->>'confidence')::numeric<0.85
                  OR (s.judgment->'purpose'->'probabilities'->>(s.judgment->'purpose'->>'selected'))::numeric<0.85
                  OR (s.judgment->'properties'->>CASE s.judgment->'purpose'->>'selected'
                    WHEN 'PERSONAL' THEN 'HUMAN_CORRESPONDENCE' WHEN 'RECEIPT' THEN 'ORDER' WHEN 'SECURITY' THEN 'SECURITY'
                    WHEN 'BILL' THEN 'BILL' WHEN 'STATEMENT' THEN 'STATEMENT' END)::numeric<=0.1
                  OR EXISTS(
                  SELECT 1 FROM jsonb_each_text(s.judgment->'properties') p
                    WHERE p.key<>'CONTEXT_SUFFICIENT' AND p.value::numeric>0.1 AND p.value::numeric<0.9))) unresolved,
              (SELECT count(*) FROM labeling_work WHERE account_id=? AND state IN ('FAILED','UNKNOWN','STALE')) failed,
              (SELECT count(*) FROM labeling_work w JOIN labeling_scopes q ON q.id=w.scope_id WHERE w.account_id=?
                AND w.state='PENDING' AND NOT q.authorized) awaiting_approval,
              (SELECT count(*) FROM labeling_work w JOIN labeling_scopes q ON q.id=w.scope_id WHERE w.account_id=?
                AND w.state='PENDING' AND q.authorized AND q.paused) paused,
              (SELECT count(*) FROM labeling_work WHERE account_id=? AND state='DONE') completed
            FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id LEFT JOIN message_evidence e ON e.account_id=m.account_id AND e.provider_message_id=m.provider_message_id
            LEFT JOIN correspondent_summary h ON h.account_id=m.account_id AND h.address=m.dashboard_from_address
            LEFT JOIN message_semantic_labels s ON s.account_id=m.account_id AND s.message_id=m.dashboard_message_id
            WHERE m.account_id=? AND
            """+com.tidymail.repository.DashboardRepository.RECEIVED+"""
            """,(r,n)->new Coverage(r.getLong("indexed"),r.getLong("observed"),r.getLong("mime_unresolved"),r.getLong("labeled"),r.getLong("pending"),r.getLong("unresolved"),r.getLong("failed"),
                r.getLong("awaiting_approval"),r.getLong("paused"),r.getLong("completed"),r.getTimestamp("updated_at")==null?null:r.getTimestamp("updated_at").toInstant()),account,account,account,account,account,account);
    }
    /** Creates an inert review packet. No Gmail/model calls, and no action==REVIEW admission rule. */
    public Prepared prepare(long account,Collection<UUID> messages,String kind,Variant variant,BigDecimal cap) {
        if(messages==null || messages.isEmpty() || messages.size()>MAX_SCOPE || messages.stream().anyMatch(Objects::isNull) || new HashSet<>(messages).size()!=messages.size()
            || kind==null || !Set.of("MIME","JEV").contains(kind) || variant==null || cap==null || cap.signum()<0
            || (kind.equals("MIME") && cap.signum()!=0)
            || (kind.equals("JEV") && !validCap(cap)))
            throw new IllegalArgumentException("Review 1–1,000 exact messages with a bounded allowance");
        return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account).isEmpty())throw new IllegalArgumentException("Account unavailable");
            var ordered=new ArrayList<>(messages);ordered.sort(Comparator.naturalOrder());
            for(UUID message:ordered)if(jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=? AND dashboard_message_id=? FOR UPDATE",Long.class,account,message).isEmpty())throw new IllegalArgumentException("Owned message unavailable");
            var rows=json.createArrayNode();
            for(UUID message:messages) {
                // Shared ownership/mailbox admission; Sent-only, excluded and unknown copies cannot enter paid work.
                var source=recommendations.prepareSemantic(account,List.of(message));
                if(source.size()!=1)throw new IllegalArgumentException("Message is unavailable or outside received labeling scope");
                var e=evidence(account,message,variant,source.getFirst().evidence());
                var row=rows.addObject().put("message",message.toString()).put("sender",e.address()).put("subject",e.subject());
                if(kind.equals("JEV")) {
                    if(variant==Variant.SNIPPET && (e.snippet().text()==null || e.snippet().text().isBlank()))
                        throw new IllegalArgumentException("No saved snippet evidence; use the headers scope");
                    ObjectNode request=contract.request(List.of(e),variant);
                    String content=contentHash(request);
                    boolean seen=alreadyExposed(account,message,content,request,variant);
                    if(seen)throw new IllegalArgumentException("Unchanged evidence was already submitted; reuse its result or review its unresolved exposure");
                    row.put("inputHash",e.sourceRevision()).put("contentHash",content).put("variant",variant.name());
                    row.set("evidence",json.valueToTree(e));row.set("request",request);
                }
            }
            var manifest=json.createObjectNode().put("account",account).put("kind",kind).put("maxSpend",cap.toPlainString())
                .put("destination",kind.equals("JEV")?"https://openrouter.ai/api/alpha/decisions":"Gmail messages.get projected MIME structure")
                .put("maxRequests",messages.size()).put("maxTotalMimeBytes",Math.min(8388608L,1048576L*messages.size())).put("maxResponseBytes",kind.equals("MIME")?1048576:262144)
                .put("contract",VERSION).put("sharedNewLabelingAllowance",jdbc.queryForObject("SELECT labeling_checkpoint FROM classification_budget WHERE id=1",BigDecimal.class).toPlainString()).put("reservationPerRequest",RESERVATION.toPlainString());
            manifest.set("messages",rows);
            String hash=CalendarEvidenceParser.hash(manifest.toString());UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO labeling_scopes(id,account_id,kind,manifest_hash,manifest,max_spend) VALUES(?,?,?,?,?::jsonb,?)",id,account,kind,hash,manifest.toString(),cap);
            for(var row:rows) jdbc.update("""
                INSERT INTO labeling_work(id,scope_id,account_id,message_id,input_hash,content_hash,evidence,request)
                VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb)
                """,UUID.randomUUID(),id,account,UUID.fromString(row.path("message").asText()),nullable(row,"inputHash"),nullable(row,"contentHash"),
                row.has("evidence")?row.path("evidence").toString():null,row.has("request")?row.path("request").toString():null);
            return new Prepared(id,hash,manifest);
        });
    }
    /** Only call after approval of this exact manifest. No broad account enrollment exists. */
    public void authorize(long account,UUID scope,String reviewedHash) {
        if(jdbc.update("UPDATE labeling_scopes SET authorized=true WHERE id=? AND account_id=? AND manifest_hash=? AND NOT paused",scope,account,reviewedHash)!=1)
            throw new IllegalArgumentException("Reviewed scope did not match");
    }
    public void pause(long account,UUID scope) { jdbc.update("UPDATE labeling_scopes SET paused=true WHERE id=? AND account_id=?",scope,account); }
    public boolean runOnce() {
        return runOnce(null);
    }
    /** An explicit in-app batch never claims work from another reviewed scope. */
    public boolean runOnce(UUID scope) {
        // Persisted responses can settle after process death without another disclosure or charge.
        var recover=jdbc.queryForList("SELECT id FROM labeling_work WHERE state='DISPATCHED' AND response IS NOT NULL AND (?::uuid IS NULL OR scope_id=?) ORDER BY id LIMIT 1",UUID.class,scope,scope);
        if(!recover.isEmpty()){settle(recover.getFirst());return true;}
        tx.executeWithoutResult(s->{budget.recover();jdbc.update("""
            UPDATE labeling_work w SET state='UNKNOWN',error_code='CHARGE_UNRESOLVED'
            FROM classification_attempts a WHERE w.attempt_id=a.id AND w.state='DISPATCHED' AND a.state='UNKNOWN'
            """);jdbc.update("""
            UPDATE labeling_work w SET state='FAILED',error_code='SETTLED_RESPONSE_MISSING'
            FROM classification_attempts a WHERE w.attempt_id=a.id AND w.state='DISPATCHED'
              AND w.response IS NULL AND a.state IN ('REPORTED','ACKNOWLEDGED')
            """);});
        UUID lease=UUID.randomUUID();
        var claims=jdbc.queryForList("""
            UPDATE labeling_work w SET state='LEASED',lease_token=?,lease_until=now()+interval '2 minutes',tries=tries+1
            WHERE w.id=(SELECT q.id FROM labeling_work q JOIN labeling_scopes s ON s.id=q.scope_id
              WHERE s.authorized AND NOT s.paused AND q.next_attempt_at<=now() AND (?::uuid IS NULL OR q.scope_id=?)
                AND (q.state='PENDING' OR (q.state='LEASED' AND q.lease_until<now()))
              ORDER BY q.next_attempt_at,q.id FOR UPDATE OF q SKIP LOCKED LIMIT 1)
            RETURNING w.id
            """,UUID.class,lease,scope,scope);
        if(claims.isEmpty())return false;
        UUID work=claims.getFirst();var row=work(work);long account=((Number)row.get("account_id")).longValue();UUID message=(UUID)row.get("message_id");
        if("MIME".equals(row.get("kind"))) {
            try {
                // Reserve at the service's actual I/O boundary, after its cache lookup.
                // A separately observed cache hit must never allow an unreserved read.
                var reserved=new java.util.concurrent.atomic.AtomicBoolean();
                var facts=mime.inspectPresence(account,message,()->{
                    boolean accepted=reserveMime(work,lease);reserved.set(accepted);return accepted;
                });
                if(reserved.get())jdbc.update("UPDATE labeling_scopes SET mime_bytes=mime_bytes-1048576+? WHERE id=?",
                    Math.max(0,Math.min(1048576,facts.path("responseBytes").asLong(1048576))),row.get("scope_id"));
                jdbc.update("UPDATE labeling_work SET state='DONE',lease_until=NULL,error_code=NULL WHERE id=? AND lease_token=? AND state='LEASED'",work,lease);
                recommendations.recompute(account,List.of(message));
            } catch(RuntimeException error) {
                jdbc.update("""
                    UPDATE labeling_work SET state=CASE WHEN tries>=3 THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at=now()+interval '1 minute'*tries,lease_until=NULL,error_code='MIME_READ_FAILED'
                    WHERE id=? AND lease_token=? AND state='LEASED'
                    """,work,lease);
            }
            return true;
        }
        var request=(ObjectNode)decode(Objects.toString(row.get("request"),null));
        JsonNode prices;
        try {prices=jev.recommendationPricing();if(prices==null)throw new IllegalStateException();}
        catch(RuntimeException unavailable) {
            jdbc.update("UPDATE labeling_work SET state='PENDING',lease_until=NULL,next_attempt_at=now()+interval '1 minute',error_code='PRICING_UNVERIFIED' WHERE id=? AND lease_token=?",work,lease);return true;
        }
        UUID attempt=reserve(work,lease,request,prices);
        if(attempt==null)return true;
        try {
            var response=jev.submitRecommendation(request);
            jdbc.update("UPDATE labeling_work SET response=?::jsonb WHERE id=? AND attempt_id=? AND state='DISPATCHED'",response.toString(),work,attempt);
            settle(work);
        } catch(JevClassifier.Unavailable error) {
            if(error.reportedCost()!=null)budget.report(attempt,error.reportedCost());else budget.unknown(attempt);
            fail(work,error.reportedCost()!=null?"FAILED":"UNKNOWN","JEV_RESPONSE_UNAVAILABLE");
        } catch(RuntimeException error) {
            // A stored response remains recoverable if settlement/database persistence fails.
            if(jdbc.queryForObject("SELECT response IS NULL FROM labeling_work WHERE id=?",Boolean.class,work)) {
                budget.unknown(attempt);fail(work,"UNKNOWN","JEV_RESPONSE_UNAVAILABLE");
            }
        }
        return true;
    }
    private boolean reserveMime(UUID work,UUID lease) {
        return Boolean.TRUE.equals(tx.execute(status->{
            var row=work(work);
            jdbc.queryForList("SELECT id FROM labeling_scopes WHERE id=? FOR UPDATE",UUID.class,row.get("scope_id"));
            var current=jdbc.queryForList("""
                SELECT id FROM labeling_work WHERE id=? AND lease_token=? AND state='LEASED' AND lease_until>now() FOR UPDATE
                """,UUID.class,work,lease);
            if(current.isEmpty())return false;
            var manifest=decode(Objects.toString(row.get("manifest"),null));
            int reserved=jdbc.update("""
                UPDATE labeling_scopes SET mime_requests=mime_requests+1,mime_bytes=mime_bytes+1048576
                WHERE id=? AND authorized AND NOT paused AND mime_requests<? AND mime_bytes+1048576<=?
                """,row.get("scope_id"),manifest.path("maxRequests").asInt(),manifest.path("maxTotalMimeBytes").asLong());
            if(reserved==1)return true;
            jdbc.update("UPDATE labeling_scopes SET paused=true WHERE id=?",row.get("scope_id"));
            jdbc.update("UPDATE labeling_work SET state='PENDING',lease_until=NULL,error_code='MIME_SCOPE_LIMIT' WHERE id=? AND lease_token=?",work,lease);
            return false;
        }));
    }
    private UUID reserve(UUID work,UUID lease,ObjectNode request,JsonNode pricing) {
        return tx.execute(status->{
            budget.lock();
            var row=work(work);long account=((Number)row.get("account_id")).longValue();
            // Scope lock serializes cap checks and pause with dispatch.
            jdbc.queryForList("SELECT id FROM labeling_scopes WHERE id=? FOR UPDATE",UUID.class,row.get("scope_id"));
            var active=jdbc.queryForObject("SELECT authorized AND NOT paused FROM labeling_scopes WHERE id=?",Boolean.class,row.get("scope_id"));
            if(!active || !lease.equals(row.get("lease_token")) || !"LEASED".equals(row.get("state"))) return null;
            Variant variant=variant(row);UUID message=(UUID)row.get("message_id");
            jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
            jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=? AND dashboard_message_id=? FOR UPDATE",Long.class,account,message);
            JsonNode reviewed=null;
            for(var entry:decode(Objects.toString(row.get("manifest"),null)).path("messages"))
                if(entry.path("message").asText().equals(message.toString()))reviewed=entry;
            if(reviewed==null || !reviewed.path("request").equals(request)
                || !reviewed.path("inputHash").asText().equals(row.get("input_hash"))
                || !reviewed.path("contentHash").asText().equals(row.get("content_hash"))) {
                fail(work,"STALE","REVIEWED_MANIFEST_CHANGED");return null;
            }
            Evidence frozen=json.convertValue(decode(Objects.toString(row.get("evidence"),null)),Evidence.class);
            if(!request.equals(decode(contract.request(List.of(frozen),variant).toString())) || !Objects.equals(row.get("content_hash"),contentHash(request))) {
                fail(work,"STALE","CONTRACT_OR_REQUEST_CHANGED");return null;
            }
            if(!Objects.equals(row.get("input_hash"),inputHash(account,message,variant))) {fail(work,"STALE","INPUT_CHANGED");return null;}
            if(alreadyExposed(account,message,row.get("content_hash").toString(),request,variant)) {
                fail(work,"STALE","EVIDENCE_ALREADY_SUBMITTED");return null;
            }
            BigDecimal liability=jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN a.state='REPORTED' THEN a.cost ELSE e.reserved END),0)
                FROM labeling_exposures e JOIN classification_attempts a ON a.id=e.attempt_id WHERE e.scope_id=?
                """,BigDecimal.class,row.get("scope_id"));
            BigDecimal cap=(BigDecimal)row.get("max_spend");var view=budget.view(account);
            boolean sharedRoom=jdbc.queryForObject("""
                SELECT labeling_reported+COALESCE((SELECT sum(e.reserved) FROM labeling_exposures e
                  JOIN classification_attempts a ON a.id=e.attempt_id WHERE a.state<>'REPORTED'),0)+?<=labeling_checkpoint
                FROM classification_budget WHERE id=1
                """,Boolean.class,RESERVATION);
            if(!sharedRoom || !jev.configured() || !budget.available() || view.reported().add(RESERVATION).compareTo(view.checkpoint())>0
                || liability.add(RESERVATION).compareTo(cap)>0) {
                if(view.inFlight()==0 && (!sharedRoom || liability.add(RESERVATION).compareTo(cap)>0
                    || view.reported().add(RESERVATION).compareTo(view.checkpoint())>0))
                    jdbc.update("UPDATE labeling_scopes SET paused=true WHERE id=?",row.get("scope_id"));
                jdbc.update("UPDATE labeling_work SET state='PENDING',next_attempt_at=now()+interval '1 minute',lease_until=NULL,error_code='ALLOWANCE_OR_ROUTE_PAUSED' WHERE id=? AND lease_token=?",work,lease);return null;
            }
            UUID attempt=UUID.randomUUID(),job=UUID.randomUUID();
            jdbc.update("INSERT INTO dashboard_classification_jobs(public_id,account_id,status,error_message) VALUES(?,?,'COMPLETE','Reviewed independent labeling scope')",job,account);
            budget.dispatch(attempt,job,account,1);
            jdbc.update("INSERT INTO labeling_exposures(attempt_id,scope_id,account_id,message_id,content_hash,input_hash,request,reserved,pricing) VALUES(?,?,?,?,?,?,?::jsonb,?,?::jsonb)",
                attempt,row.get("scope_id"),account,message,row.get("content_hash"),row.get("input_hash"),request.toString(),RESERVATION,pricing.toString());
            jdbc.update("UPDATE labeling_work SET state='DISPATCHED',attempt_id=?,lease_until=NULL WHERE id=? AND lease_token=?",attempt,work,lease);
            return attempt;
        });
    }
    private void settle(UUID work) {
        var row=work(work);var response=decode(Objects.toString(row.get("response"),null));UUID attempt=(UUID)row.get("attempt_id");
        var cost=response.path("usage").path("cost");
        if(!cost.isNumber() || cost.decimalValue().signum()<0 || cost.decimalValue().scale()>12 || cost.decimalValue().compareTo(new BigDecimal("100"))>0) {
            budget.unknown(attempt);fail(work,"UNKNOWN","CHARGE_UNRESOLVED");return;
        }
        budget.report(attempt,cost.decimalValue()); // charge survives a typed parse/freshness failure
        if(cost.decimalValue().compareTo(RESERVATION)>0) {
            jdbc.update("UPDATE labeling_scopes SET paused=true WHERE id=?",row.get("scope_id"));fail(work,"FAILED","RESERVATION_EXCEEDED");return;
        }
        Response parsed;
        try {parsed=contract.parse(response,List.of(json.convertValue(decode(Objects.toString(row.get("evidence"),null)),Evidence.class)),variant(row));}
        catch(RuntimeException error){fail(work,"FAILED","INVALID_TYPED_RESPONSE");return;}
        tx.executeWithoutResult(status->{
            jdbc.queryForList("SELECT id FROM labeling_work WHERE id=? FOR UPDATE",UUID.class,work);
            if(!"DISPATCHED".equals(work(work).get("state")))return;
            long account=((Number)row.get("account_id")).longValue();UUID message=(UUID)row.get("message_id");
            // Shared account lock serializes alias/history changes with local composition; never held over I/O.
            jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
            jdbc.queryForList("SELECT id FROM message_metadata WHERE account_id=? AND dashboard_message_id=? FOR UPDATE",Long.class,account,message);
            if(!Objects.equals(row.get("input_hash"),inputHash(account,message,variant(row)))) {fail(work,"STALE","INPUT_CHANGED");return;}
            var independent=SemanticLabelComposition.merge(new MessageRecommendation.Assessment(MessageRecommendation.Purpose.UNKNOWN,
                MessageRecommendation.Importance.UNKNOWN,Set.of(),MessageRecommendation.Signal.UNKNOWN,MessageRecommendation.Signal.UNKNOWN,
                MessageRecommendation.Qualification.UNRESOLVED,false),parsed.judgments().getFirst(),false);
            var labels=new TreeSet<String>();independent.facets().forEach(f->labels.add(f.name()));
            if(independent.purpose()!=MessageRecommendation.Purpose.UNKNOWN)labels.add(independent.purpose().name());
            if(independent.purpose()==MessageRecommendation.Purpose.PERSONAL)labels.add("HUMAN_CORRESPONDENCE");
            jdbc.update("""
                INSERT INTO message_semantic_labels(account_id,message_id,input_hash,variant,contract_version,judgment,attempt_id,labels,purpose)
                VALUES(?,?,?,?,?,?::jsonb,?,?::text[],?) ON CONFLICT(account_id,message_id) DO UPDATE SET input_hash=EXCLUDED.input_hash,
                  variant=EXCLUDED.variant,contract_version=EXCLUDED.contract_version,judgment=EXCLUDED.judgment,attempt_id=EXCLUDED.attempt_id,labels=EXCLUDED.labels,purpose=EXCLUDED.purpose,saved_at=now()
                """,account,message,row.get("input_hash"),variant(row).name(),VERSION,json.valueToTree(parsed.judgments().getFirst()).toString(),attempt,"{"+String.join(",",labels)+"}",independent.purpose().name());
            jdbc.update("UPDATE labeling_work SET state='DONE',error_code=NULL WHERE id=? AND state='DISPATCHED'",work);
        });
        if("DONE".equals(work(work).get("state")))recommendations.recompute(((Number)row.get("account_id")).longValue(),List.of((UUID)row.get("message_id")));
    }
    private Evidence evidence(long account,UUID message,Variant variant,Evidence e) {
        // Contact is composed from current local history. It neither discloses counts nor causes paid label invalidation.
        return new Evidence(message,inputHash(account,message,variant),e.address(),e.name(),e.subject(),e.reply(),e.unsubscribe(),e.gmailCategories(),e.labelsKnown(),e.snippet(),
            CorrespondentHistory.Context.unknown());
    }
    private String inputHash(long account,UUID message,Variant variant) {
        return jdbc.query("""
            SELECT labeling_input_hash(m,h,?) FROM message_metadata m JOIN user_accounts a ON a.id=m.account_id LEFT JOIN correspondent_summary h
              ON h.account_id=m.account_id AND h.address=m.dashboard_from_address
            WHERE m.account_id=? AND m.dashboard_message_id=? AND m.provider='google'
              AND m.label_evidence_known AND
            """+com.tidymail.repository.DashboardRepository.RECEIVED+"""
            """,(r,n)->r.getString(1),variant.name(),account,message).stream().findFirst().orElse(null);
    }
    private Map<String,Object> work(UUID id) {
        return jdbc.queryForMap("SELECT w.*,w.evidence::text evidence,w.request::text request,w.response::text response,s.kind,s.max_spend,s.manifest::text manifest FROM labeling_work w JOIN labeling_scopes s ON s.id=w.scope_id WHERE w.id=?",id);
    }
    private Variant variant(Map<String,Object> row) {return Variant.valueOf(decode(Objects.toString(row.get("manifest"),null)).path("messages").get(0).path("variant").asText());}
    private boolean alreadyExposed(long account,UUID message,String hash,ObjectNode request,Variant variant) {
        return jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM labeling_exposures WHERE account_id=? AND message_id=? AND
              (content_hash=? OR (? AND (request->'state'->'email_0')-'snippet'=(?::jsonb->'state'->'email_0')-'snippet')))
            """,Boolean.class,account,message,hash,variant==Variant.HEADERS,request.toString());
    }
    /** Read failures may be retried explicitly; paid exposures never enter this retry path. */
    public void retryMime(long account,UUID scope) {
        jdbc.update("""
            UPDATE labeling_work w SET state='PENDING',tries=0,error_code=NULL,next_attempt_at=now()
            FROM labeling_scopes s WHERE w.scope_id=s.id AND s.id=? AND s.account_id=? AND s.kind='MIME' AND w.state='FAILED'
            """,scope,account);
    }
    public void retryMime(long account,UUID scope,String reviewedHash) {
        var saved=scopeStatus(account,scope);
        if(!saved.authorized() || !saved.scope().hash().equals(reviewedHash) || !saved.scope().manifest().path("kind").asText().equals("MIME"))
            throw new IllegalArgumentException("Reviewed MIME scope did not match");
        retryMime(account,scope); // Does not resume a paused scope or replenish its original caps.
    }
    public void resume(long account,UUID scope,String reviewedHash) {
        if(jdbc.update("UPDATE labeling_scopes SET paused=false WHERE id=? AND account_id=? AND manifest_hash=? AND authorized",scope,account,reviewedHash)!=1)
            throw new IllegalArgumentException("Reviewed scope did not match");
    }
    private String contentHash(ObjectNode request) {return CalendarEvidenceParser.hash(canonical(request.path("state")).toString());}
    private JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            var sorted=json.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);
            for(String key:keys)sorted.set(key,canonical(value.get(key)));return sorted;
        }
        if(value.isArray()) {var array=json.createArrayNode();value.forEach(v->array.add(canonical(v)));return array;}
        return value;
    }
    private static String nullable(JsonNode row,String key){return row.has(key)?row.path(key).asText():null;}
    private JsonNode decode(String encoded){try{return json.readTree(encoded);}catch(Exception e){throw new IllegalStateException("Saved labeling data invalid");}}
    private void fail(UUID work,String state,String code){jdbc.update("UPDATE labeling_work SET state=?,error_code=?,lease_until=NULL WHERE id=?",state,code,work);}
}
