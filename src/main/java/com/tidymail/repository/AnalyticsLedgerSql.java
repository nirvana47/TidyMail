package com.tidymail.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds shared raw-SQL fragments and predicates for the analytics ledger
 * queries.
 * <p>
 * Product functionality: analytics dashboards and drilldowns over the action
 * ledger.
 */
public final class AnalyticsLedgerSql {
    private static final List<String> DECISIONS = List.of(
        "KEEP", "REVIEW", "PROTECT", "ARCHIVE", "TRASH", "MARK_READ", "UNSUBSCRIBE");

    private AnalyticsLedgerSql() {
    }

    static FactPredicate predicate(
            Collection<Long> accountIds,
            Instant startInclusive,
            Instant endExclusive,
            String action) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)
                || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)
                || action == null || action.isBlank()) {
            throw new IllegalArgumentException("A bounded authorized fact predicate is required");
        }
        return new FactPredicate(
            accountIds.stream().distinct().sorted().toList(),
            startInclusive, endExclusive, action.toUpperCase());
    }

    static String resolvedTargets(String arrayValueExpression) {
        return """
            WITH resolved_targets AS (
                SELECT t.id AS target_id, t.execution_id, t.account_id,
                       t.scope_ordinal, t.status AS target_status,
                       t.dispatch_state, t.accepted_at, t.dispatched_at,
                       t.completed_at, t.unsubscribe_attempt_id,
                       e.operation_kind, e.reserved_at,
                       a.id AS action_id, a.action_type, a.execution_mode,
                       %s AS message_key
                  FROM action_execution_targets t
                  JOIN action_executions e
                    ON e.id=t.execution_id AND e.account_id=t.account_id
                  JOIN user_actions a
                    ON a.id=e.action_id AND a.account_id=e.account_id
                  JOIN action_scopes s
                    ON s.id=t.scope_id AND s.account_id=t.account_id
                 WHERE t.account_id IN (:account_ids)
            )
            """.formatted(arrayValueExpression);
    }

    static String firstDecisions(String arrayValueExpression) {
        return resolvedTargets(arrayValueExpression) + """
            , decision_candidates AS (
                SELECT r.account_id, r.message_key, event.action_type,
                       event.occurred_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY r.account_id, r.message_key
                           ORDER BY event.occurred_at, event.id, r.target_id
                       ) AS decision_rank
                  FROM resolved_targets r
                  JOIN action_events event
                    ON event.action_id=r.action_id
                   AND event.account_id=r.account_id
                   AND event.event_type='ACTION_QUEUED'
                 WHERE event.action_type IN (
                    'KEEP','REVIEW','PROTECT','ARCHIVE','TRASH','MARK_READ','UNSUBSCRIBE'
                 )
            ),
            first_decisions AS (
                SELECT account_id, message_key, action_type, occurred_at
                  FROM decision_candidates
                 WHERE decision_rank=1
            )
            """;
    }

    static String cleanedFacts(String arrayValueExpression) {
        return cleanedCandidates(arrayValueExpression) + """
            , cleaned_ranked AS (
                SELECT account_id, message_key, action_type, occurred_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY account_id, message_key
                           ORDER BY occurred_at, tie_id
                       ) AS cleanup_rank
                  FROM cleanup_candidates
            ),
            cleaned_with_metadata AS (
                SELECT ranked.account_id, ranked.message_key, ranked.action_type,
                       ranked.occurred_at, metadata.id AS metadata_id,
                       metadata.received_at
                  FROM cleaned_ranked ranked
                  LEFT JOIN message_metadata metadata
                    ON metadata.account_id=ranked.account_id
                   AND metadata.provider_message_id=ranked.message_key
                 WHERE ranked.cleanup_rank=1
            ),
            cleaned_facts AS (
                SELECT account_id, message_key, action_type, occurred_at, metadata_id
                  FROM cleaned_with_metadata
                 WHERE received_at IS NOT NULL
            )
            """;
    }

    static String cleanedCandidates(String arrayValueExpression) {
        return resolvedTargets(arrayValueExpression) + """
            , cleanup_candidates AS (
                SELECT r.account_id, r.message_key, r.action_type,
                       r.completed_at AS occurred_at, r.target_id AS tie_id
                  FROM resolved_targets r
                 WHERE r.operation_kind='FORWARD'
                   AND r.target_status='SUCCEEDED'
                   AND r.action_type IN ('ARCHIVE','TRASH','MARK_READ')
                   AND r.completed_at IS NOT NULL
                   AND r.completed_at>=:start AND r.completed_at<:end
                   AND (:action='ALL' OR r.action_type=:action)
                UNION ALL
                SELECT r.account_id, r.message_key, 'UNSUBSCRIBE',
                       outcome.occurred_at, outcome.id
                  FROM resolved_targets r
                  JOIN unsubscribe_outcome_events outcome
                    ON outcome.attempt_id=r.unsubscribe_attempt_id
                   AND outcome.account_id=r.account_id
                   AND outcome.status='CONFIRMED'
                 WHERE r.operation_kind='FORWARD'
                   AND r.action_type='UNSUBSCRIBE'
                   AND outcome.occurred_at>=:start AND outcome.occurred_at<:end
                   AND (:action IN ('ALL','UNSUBSCRIBE'))
            )
            """;
    }

    static List<String> decisionActions() {
        return DECISIONS;
    }
}

record FactPredicate(
    List<Long> accountIds,
    Instant startInclusive,
    Instant endExclusive,
    String action
) {
    Map<String, Object> parameters() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("account_ids", accountIds);
        values.put("start", Timestamp.from(startInclusive));
        values.put("end", Timestamp.from(endExclusive));
        values.put("action", action);
        return Map.copyOf(values);
    }
}
