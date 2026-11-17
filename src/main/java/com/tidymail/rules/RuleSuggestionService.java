package com.tidymail.rules;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Suggestions cite successful recorded actions; native deletions are never treated as inferred intent. */
@Service
public class RuleSuggestionService {
    public record Suggestion(String sender,String action,String actionLabel,long count,long available) {}
    private final NamedParameterJdbcTemplate jdbc;
    public RuleSuggestionService(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Suggestion> list(long account){
        return jdbc.query("""
            WITH latest AS (
              SELECT DISTINCT ON(p.sender_address,t.provider_message_id)
                p.sender_address,t.provider_message_id,j.action_type,j.created_at
              FROM cleanup_jobs j JOIN cleanup_job_targets t ON t.job_id=j.public_id AND t.account_id=j.account_id
              JOIN dashboard_preview_targets p ON p.preview_id=j.preview_id AND p.account_id=j.account_id
                AND p.provider_message_id=t.provider_message_id
              WHERE j.account_id=:account AND t.state='CHANGED' AND j.created_at>now()-interval '180 days'
              AND p.sender_address IS NOT NULL AND p.sender_address<>''
              ORDER BY p.sender_address,t.provider_message_id,j.created_at DESC,j.public_id DESC
            ), consistent AS (
              SELECT sender_address,min(action_type) action,count(*) evidence_count,max(created_at) latest_at
              FROM latest GROUP BY sender_address HAVING count(*)>=5 AND count(DISTINCT action_type)=1
            )
            SELECT c.*,(SELECT count(*) FROM message_metadata m WHERE m.account_id=:account
                AND lower(COALESCE(m.dashboard_from_address,m.from_address,''))=c.sender_address) available
            FROM consistent c
            LEFT JOIN automation_rule_suggestion_dismissals d ON d.account_id=:account
                AND d.sender_address=c.sender_address AND d.action=c.action
            WHERE (d.evidence_count IS NULL OR c.evidence_count>=d.evidence_count+10)
            AND NOT EXISTS(SELECT 1 FROM protected_senders s WHERE s.account_id=:account AND s.sender_address=c.sender_address)
            AND NOT EXISTS(SELECT 1 FROM automation_rules r WHERE r.account_id=:account AND r.state<>'REMOVED'
                AND r.definition->'senders' @> jsonb_build_array(c.sender_address))
            ORDER BY c.evidence_count DESC,c.latest_at DESC,c.sender_address LIMIT 10
            """,Map.of("account",account),(r,n)->{
                String action=switch(r.getString("action")){case "MARK_READ"->"READ";case "MARK_READ_ARCHIVE"->"READ_ARCHIVE";default->r.getString("action");};
                return new Suggestion(r.getString("sender_address"),action,RuleDefinition.ACTIONS.get(action),r.getLong("evidence_count"),r.getLong("available"));
            });
    }
    public void dismiss(long account,String sender,String action){
        var suggestion=list(account).stream().filter(s->s.sender().equals(sender)&&s.action().equals(action)).findFirst().orElse(null);
        if(suggestion==null)return;
        String recorded=switch(action){case "READ"->"MARK_READ";case "READ_ARCHIVE"->"MARK_READ_ARCHIVE";default->action;};
        jdbc.update("""
            INSERT INTO automation_rule_suggestion_dismissals(account_id,sender_address,action,evidence_count)
            VALUES(:account,:sender,:action,:count) ON CONFLICT(account_id,sender_address,action)
            DO UPDATE SET evidence_count=EXCLUDED.evidence_count,dismissed_at=now()
            """,Map.of("account",account,"sender",sender,"action",recorded,"count",suggestion.count()));
    }
}
