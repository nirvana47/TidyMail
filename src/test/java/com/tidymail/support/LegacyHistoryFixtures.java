package com.tidymail.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Seeds the saved ledger directly, without running a retired action. */
public final class LegacyHistoryFixtures {
    private LegacyHistoryFixtures() {}

    public record SavedAction(long id, long accountId, long scopeId) {}

    public static SavedAction action(JdbcTemplate jdbc, long account, String type,
            String label, List<String> messages, String status) {
        String[] ids = messages.toArray(String[]::new);
        long scope = jdbc.queryForObject("""
            INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,scope_hash,message_count)
            VALUES (?,?,'UNREAD_SELECTION',?::text[],
              encode(digest(gen_random_uuid()::text,'sha256'),'hex'),?) RETURNING id
            """, Long.class, account, type, ids, ids.length);
        jdbc.update("""
            INSERT INTO action_scope_targets(scope_id,account_id,scope_ordinal,target_fingerprint)
            SELECT ?,?,ordinal-1,encode(digest(message,'sha256'),'hex')
              FROM unnest(?::text[]) WITH ORDINALITY AS t(message,ordinal)
            """, scope, account, ids);
        long id = jdbc.queryForObject("""
            INSERT INTO user_actions(account_id,action_scope_id,action_type,message_ids,affected_count,
                                     status,execution_mode,decision_source)
            VALUES (?,?,?,?::text[],?,?,'STAGED','UNREAD') RETURNING id
            """, Long.class, account, scope, type, ids, ids.length, status);
        jdbc.update("""
            INSERT INTO action_intents(action_id,account_id,subject_kind,safe_target_label,target_search_key)
            VALUES (?,?,'MESSAGES',?,lower(?))
            """, id, account, label, label);
        return new SavedAction(id, account, scope);
    }

    public static long outcome(JdbcTemplate jdbc, SavedAction action, String status,
            List<String> targetStatuses, List<String> reasons, Long unsubscribeAttempt) {
        long succeeded = targetStatuses.stream().filter("SUCCEEDED"::equals).count();
        long failed = targetStatuses.stream().filter("FAILED"::equals).count();
        long pending = targetStatuses.stream().filter("VERIFICATION_PENDING"::equals).count();
        long execution = jdbc.queryForObject("""
            INSERT INTO action_executions(account_id,action_id,operation_kind,status,idempotency_fingerprint,
               requested_count,accepted_count,succeeded_count,failed_count,verification_pending_count,
               not_dispatched_count,reserved_at,started_at,completed_at)
            VALUES (?,?,'FORWARD',?,encode(digest(gen_random_uuid()::text,'sha256'),'hex'),
               ?,?,?,?,?,0,now(),now(),now()) RETURNING id
            """, Long.class, action.accountId(), action.id(), status, targetStatuses.size(),
            succeeded, succeeded, failed, pending);
        for (int ordinal = 0; ordinal < targetStatuses.size(); ordinal++) {
            jdbc.update("""
                INSERT INTO action_execution_targets(execution_id,account_id,scope_id,scope_ordinal,
                   target_fingerprint,dispatch_state,status,dispatched_at,completed_at,reason_code,unsubscribe_attempt_id)
                SELECT ?,account_id,scope_id,scope_ordinal,target_fingerprint,'DISPATCHED',?,now(),now(),?,?
                FROM action_scope_targets WHERE scope_id=? AND scope_ordinal=?
                """, execution, targetStatuses.get(ordinal), reasons.get(ordinal), unsubscribeAttempt, action.scopeId(), ordinal);
        }
        jdbc.update("""
            UPDATE user_actions SET status=?,completed_at=now() WHERE id=?
            """, status, action.id());
        jdbc.update("""
            INSERT INTO action_events(account_id,action_id,execution_id,event_type,action_type,execution_mode,
               decision_source,resulting_status,requested_count,accepted_count,succeeded_count,failed_count,
               verification_pending_count)
            SELECT a.account_id,a.id,e.id,'EXECUTION_COMPLETED',a.action_type,a.execution_mode,
               a.decision_source,a.status,e.requested_count,e.accepted_count,e.succeeded_count,e.failed_count,
               e.verification_pending_count
            FROM user_actions a JOIN action_executions e ON e.action_id=a.id WHERE e.id=?
            """, execution);
        return execution;
    }
}
