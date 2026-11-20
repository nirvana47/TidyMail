package com.tidymail.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.tidymail.entity.UserAccount;
import java.util.*;
import org.springframework.stereotype.Service;

/** Provider totals plus retained, explicitly incomplete observations from existing metadata sync. */
@Service
public class RuleResults {
    private final RuleStore store;
    public RuleResults(RuleStore store){this.store=store;}
    public String destination(RuleStore.Saved rule){
        var rows=store.jdbc().queryForList("SELECT destination_id FROM automation_rule_results WHERE rule_id=:rule AND account_id=:account",params(rule),String.class);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> params(RuleStore.Saved rule){return Map.of("rule",rule.id(),"account",rule.account());}
    public void baseline(UserAccount a,RuleProvider provider,RuleStore.Saved rule,JsonNode payload){
        if(!rule.definition().organize()||destination(rule)!=null)return;
        String destination=provider.destinationId(payload);
        if(destination==null||destination.isBlank())throw new IllegalStateException("Rule destination was not resolved.");
        long count=provider.destinationCount(a,destination);
        var p=new HashMap<>(params(rule));p.put("destination",destination);p.put("count",count);
        store.jdbc().update("INSERT INTO automation_rule_results(rule_id,account_id,destination_id,baseline_count,current_count) VALUES(:rule,:account,:destination,:count,:count) ON CONFLICT(rule_id) DO NOTHING",p);
    }
    public void observe(UserAccount a,RuleProvider provider,RuleStore.Saved rule,boolean refreshCount){
        if(!rule.definition().organize()||destination(rule)==null)return;
        var p=params(rule);
        // Retain observations even when a later sync moves/deletes the message. No new provider scan.
        store.jdbc().update("""
            INSERT INTO automation_rule_observations(rule_id,provider_message_id,received_at)
            SELECT r.rule_id,m.provider_message_id,m.received_at
            FROM automation_rule_results r JOIN message_metadata m ON m.account_id=r.account_id
            WHERE r.rule_id=:rule AND r.account_id=:account AND m.received_at>=r.started_at
              AND ((m.provider='google' AND r.destination_id=ANY(m.labels))
                OR (m.provider='microsoft' AND m.outlook_folder_id=r.destination_id))
            ON CONFLICT(rule_id,provider_message_id) DO NOTHING
            """,p);
        if(refreshCount)try{
            long count=provider.destinationCount(a,destination(rule));
            var update=new HashMap<>(p);update.put("count",count);
            store.jdbc().update("UPDATE automation_rule_results SET current_count=:count,counted_at=now(),count_error=false WHERE rule_id=:rule AND account_id=:account",update);
        }catch(RuntimeException unavailable){
            store.jdbc().update("UPDATE automation_rule_results SET count_error=true WHERE rule_id=:rule AND account_id=:account",p);
        }
    }
    public Map<String,Object> summary(RuleStore.Saved rule){
        var rows=store.jdbc().queryForList("""
            SELECT r.*, (now()>=r.started_at+interval '7 days') week_ready,
              (now()>=r.started_at+interval '14 days') fortnight_ready,
              (SELECT count(*) FROM automation_rule_observations o WHERE o.rule_id=r.rule_id) observed,
              (SELECT count(*) FROM automation_rule_observations o WHERE o.rule_id=r.rule_id AND o.received_at<r.started_at+interval '7 days') week_count,
              (SELECT count(*) FROM automation_rule_observations o WHERE o.rule_id=r.rule_id AND o.received_at<r.started_at+interval '14 days') fortnight_count,
              (SELECT max(m.last_synced_at) FROM message_metadata m WHERE m.account_id=r.account_id) metadata_synced
            FROM automation_rule_results r WHERE r.rule_id=:rule AND r.account_id=:account
            """,params(rule));
        if(rows.isEmpty())return null;
        var summary=rows.getFirst();
        for(String field:List.of("started_at","counted_at","metadata_synced"))
            if(summary.get(field) instanceof java.sql.Timestamp time)summary.put(field,time.toInstant());
        return summary;
    }
    public boolean countDue(RuleStore.Saved rule){return Boolean.TRUE.equals(store.jdbc().queryForObject(
        "SELECT EXISTS(SELECT 1 FROM automation_rule_results WHERE rule_id=:rule AND account_id=:account AND counted_at<now()-interval '1 hour')",params(rule),Boolean.class));}
}
