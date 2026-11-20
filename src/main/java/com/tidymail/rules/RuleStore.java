package com.tidymail.rules;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Repository
public class RuleStore {
    public record Saved(UUID id,long account,RuleDefinition definition,long version,String state,String remoteId,
                        JsonNode remoteSnapshot,String pending,JsonNode operation,String detail,Instant checked) {}
    final NamedParameterJdbcTemplate jdbc;final ObjectMapper json;final TransactionTemplate tx;
    public RuleStore(NamedParameterJdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager){this.jdbc=jdbc;this.json=json;tx=new TransactionTemplate(manager);}
    public NamedParameterJdbcTemplate jdbc(){return jdbc;}
    public ObjectMapper json(){return json;}
    public String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    public <T>T decode(String s,Class<T> type){try{return json.readValue(s,type);}catch(Exception e){throw new IllegalStateException("Stored rule could not be read.",e);}}
    public List<Saved> list(long account){return query("SELECT * FROM automation_rules WHERE account_id=:account AND state<>'REMOVED' ORDER BY updated_at DESC",Map.of("account",account));}
    public Saved get(long account,UUID id){return query("SELECT * FROM automation_rules WHERE account_id=:account AND id=:id",Map.of("account",account,"id",id))
        .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Rule not found in this mailbox."));}
    private List<Saved> query(String sql,Map<String,?> p){return jdbc.query(sql,p,(r,n)->new Saved(r.getObject("id",UUID.class),r.getLong("account_id"),
        decode(r.getString("definition"),RuleDefinition.class),r.getLong("version"),r.getString("state"),r.getString("remote_id"),
        decode(Objects.toString(r.getString("remote_snapshot"),"null"),JsonNode.class),r.getString("pending_operation"),
        decode(Objects.toString(r.getString("operation_snapshot"),"null"),JsonNode.class),r.getString("detail"),r.getTimestamp("checked_at")==null?null:r.getTimestamp("checked_at").toInstant()));}
    public UUID save(long account,UUID id,long version,RuleDefinition d){return tx.execute(status->{
        var p=new HashMap<String,Object>();p.put("account",account);p.put("definition",encode(d));
        if(id==null){idHolder(p);jdbc.update("INSERT INTO automation_rules(id,account_id,definition) VALUES(:id,:account,CAST(:definition AS jsonb))",p);}
        else {p.put("id",id);p.put("version",version);
            int changed=jdbc.update("UPDATE automation_rules SET definition=CAST(:definition AS jsonb),version=version+1,updated_at=now(),state='DRAFT',remote_id=NULL,remote_snapshot=NULL,detail=NULL WHERE id=:id AND account_id=:account AND version=:version AND state IN ('DRAFT','PAUSED') AND remote_id IS NULL AND pending_operation IS NULL",p);
            if(changed!=1)conflict("Pause and remove the native rule before editing, or reload the newer draft.");}
        jdbc.update("DELETE FROM automation_rule_results WHERE rule_id=:id AND account_id=:account",p);
        event(account,(UUID)p.get("id"),"Draft saved",d);return (UUID)p.get("id");});}
    private void idHolder(Map<String,Object> p){p.put("id",UUID.randomUUID());}
    void event(long account,UUID id,String event,RuleDefinition d){jdbc.update("INSERT INTO automation_rule_events(rule_id,account_id,event,definition) VALUES(:id,:account,:event,CAST(:definition AS jsonb))",Map.of("id",id,"account",account,"event",event,"definition",encode(d)));}
    public void claim(Saved r,String op,JsonNode payload){tx.executeWithoutResult(status->{
        var owned=jdbc.queryForList("SELECT id FROM user_accounts WHERE id=:account FOR UPDATE",Map.of("account",r.account()));
        if(owned.isEmpty())conflict("Mailbox was removed before the rule operation started.");
        int n=jdbc.update("UPDATE automation_rules SET pending_operation=:op,operation_snapshot=CAST(:payload AS jsonb),state='ATTENTION',detail='Waiting for provider confirmation',updated_at=now() WHERE id=:id AND account_id=:account AND version=:version AND pending_operation IS NULL",Map.of("id",r.id(),"account",r.account(),"version",r.version(),"op",op,"payload",encode(payload)));
        if(n!=1)conflict("A rule operation is already pending. Refresh before trying again.");});}
    public void prepared(Saved r,JsonNode payload){
        int changed=jdbc.update("UPDATE automation_rules SET operation_snapshot=CAST(:payload AS jsonb) WHERE id=:id AND account_id=:account AND version=:version AND pending_operation='CREATE'",Map.of("id",r.id(),"account",r.account(),"version",r.version(),"payload",encode(payload)));
        if(changed!=1)conflict("Rule changed while preparing its destination. Refresh before continuing.");
    }
    public void recordCreated(Saved r,String remoteId){
        if(remoteId==null||remoteId.isBlank())conflict("Provider returned no rule identity. Refresh before retrying.");
        int n=jdbc.update("UPDATE automation_rules SET remote_id=:remote WHERE id=:id AND account_id=:account AND version=:version AND pending_operation='CREATE'",
            Map.of("remote",remoteId,"id",r.id(),"account",r.account(),"version",r.version()));
        if(n!=1)conflict("The creation receipt could not be recorded. Refresh rules before retrying.");
    }
    public void settle(Saved r,String state,String remoteId,JsonNode snapshot,String detail){tx.executeWithoutResult(status->{var p=new HashMap<String,Object>();
        p.put("version",r.version());p.put("pending",r.pending());
        p.put("id",r.id());p.put("account",r.account());p.put("state",state);p.put("remote",remoteId);p.put("snapshot",encode(snapshot));p.put("detail",detail);
        int changed=jdbc.update("UPDATE automation_rules SET state=:state,remote_id=:remote,remote_snapshot=CAST(:snapshot AS jsonb),pending_operation=NULL,operation_snapshot=NULL,detail=:detail,checked_at=now(),updated_at=now() WHERE id=:id AND account_id=:account AND version=:version AND pending_operation IS NOT DISTINCT FROM :pending",p);
        if(changed!=1)conflict("The rule changed while verifying the provider operation. Refresh rules.");
        event(r.account(),r.id(),state+": "+detail,r.definition());});}
    public void attention(Saved r,String detail){jdbc.update("UPDATE automation_rules SET state='ATTENTION',detail=:detail,checked_at=now() WHERE id=:id AND account_id=:account",Map.of("id",r.id(),"account",r.account(),"detail",detail));}
    static void conflict(String text){throw new ResponseStatusException(HttpStatus.CONFLICT,text);}
}
