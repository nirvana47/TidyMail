package com.tidymail.repository;

import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsTopSender;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsCoverageRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsCoverageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, Instant> earliestLedgerByAccount(Collection<Long> accountIds) {
        List<Long> authorized = authorized(accountIds);
        return jdbc.query("""
            SELECT account_id, MIN(occurred_at) AS earliest
              FROM action_events
             WHERE account_id IN (:account_ids)
             GROUP BY account_id
             ORDER BY account_id
            """, Map.of("account_ids", authorized), result -> {
                LinkedHashMap<Long, Instant> values = new LinkedHashMap<>();
                while (result.next()) {
                    values.put(
                        result.getLong("account_id"),
                        result.getTimestamp("earliest").toInstant());
                }
                return Map.copyOf(values);
            });
    }

    public long legacyGapCount(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        MapSqlParameterSource parameters = parameters(accountIds, range, action);
        Number count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM user_actions action
             WHERE action.account_id IN (:account_ids)
               AND (:action='ALL' OR action.action_type=:action)
               AND (
                   action.decision_source='LEGACY'
                   OR NOT EXISTS (
                       SELECT 1 FROM action_events event
                        WHERE event.action_id=action.id
                          AND event.account_id=action.account_id
                   )
                   OR NOT EXISTS (
                       SELECT 1
                         FROM action_executions execution
                         JOIN action_execution_targets target
                           ON target.execution_id=execution.id
                          AND target.account_id=execution.account_id
                        WHERE execution.action_id=action.id
                          AND execution.account_id=action.account_id
                   )
               )
               AND (
                   action.applied_at>=:start AND action.applied_at<:end
                   OR action.completed_at>=:start AND action.completed_at<:end
                   OR action.undone_at>=:start AND action.undone_at<:end
                   OR (
                       action.applied_at IS NULL
                       AND action.completed_at IS NULL
                       AND action.undone_at IS NULL
                       AND action.created_at<:end
                   )
               )
            """, parameters, Number.class);
        return count == null ? 0 : count.longValue();
    }

    public List<Long> legacyGapCountByBuckets(
            Collection<Long> accountIds,
            List<AnalyticsQuery.DateRange> buckets,
            AnalyticsQuery.AnalyticsAction action) {
        validateBuckets(buckets);
        MapSqlParameterSource parameters = parameters(
            accountIds,
            new AnalyticsQuery.DateRange(
                buckets.getFirst().startInclusive(), buckets.getLast().endExclusive()),
            action);
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < buckets.size(); index++) {
            if (index > 0) values.append(" UNION ALL ");
            values.append("SELECT ").append(index)
                .append(" AS bucket_index, CAST(:bucket_start_").append(index)
                .append(" AS TIMESTAMP WITH TIME ZONE) AS bucket_start, CAST(:bucket_end_")
                .append(index)
                .append(" AS TIMESTAMP WITH TIME ZONE) AS bucket_end");
            parameters
                .addValue("bucket_start_" + index,
                    Timestamp.from(buckets.get(index).startInclusive()))
                .addValue("bucket_end_" + index,
                    Timestamp.from(buckets.get(index).endExclusive()));
        }
        return bucketCounts(buckets.size(), jdbc.query("""
            WITH buckets AS (%s)
            SELECT bucket.bucket_index, COUNT(action.id) AS gap_count
              FROM buckets bucket
              LEFT JOIN user_actions action
                ON action.account_id IN (:account_ids)
               AND (:action='ALL' OR action.action_type=:action)
               AND (
                   action.decision_source='LEGACY'
                   OR NOT EXISTS (
                       SELECT 1 FROM action_events event
                        WHERE event.action_id=action.id
                          AND event.account_id=action.account_id
                   )
                   OR NOT EXISTS (
                       SELECT 1
                         FROM action_executions execution
                         JOIN action_execution_targets target
                           ON target.execution_id=execution.id
                          AND target.account_id=execution.account_id
                        WHERE execution.action_id=action.id
                          AND execution.account_id=action.account_id
                   )
               )
               AND (
                   action.applied_at>=bucket.bucket_start
                   AND action.applied_at<bucket.bucket_end
                   OR action.completed_at>=bucket.bucket_start
                   AND action.completed_at<bucket.bucket_end
                   OR action.undone_at>=bucket.bucket_start
                   AND action.undone_at<bucket.bucket_end
                   OR (
                       action.applied_at IS NULL
                       AND action.completed_at IS NULL
                       AND action.undone_at IS NULL
                       AND action.created_at<bucket.bucket_end
                   )
               )
             GROUP BY bucket.bucket_index
             ORDER BY bucket.bucket_index
            """.formatted(values), parameters, result -> {
                LinkedHashMap<Integer, Long> rows = new LinkedHashMap<>();
                while (result.next()) {
                    rows.put(result.getInt("bucket_index"), result.getLong("gap_count"));
                }
                return rows;
            }));
    }

    public long unsubscribeLinkageGapCount(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range) {
        MapSqlParameterSource parameters = parameters(
            accountIds, range, AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE);
        Number count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM action_execution_targets target
              JOIN action_executions execution
                ON execution.id=target.execution_id
               AND execution.account_id=target.account_id
              JOIN user_actions action
                ON action.id=execution.action_id
               AND action.account_id=execution.account_id
             WHERE target.account_id IN (:account_ids)
               AND action.action_type='UNSUBSCRIBE'
               AND execution.reserved_at>=:start AND execution.reserved_at<:end
               AND (
                   target.unsubscribe_attempt_id IS NULL
                   OR NOT EXISTS (
                       SELECT 1 FROM unsubscribe_outcome_events outcome
                        WHERE outcome.attempt_id=target.unsubscribe_attempt_id
                          AND outcome.account_id=target.account_id
                   )
               )
            """, parameters, Number.class);
        return count == null ? 0 : count.longValue();
    }

    public List<Long> unsubscribeLinkageGapCountByBuckets(
            Collection<Long> accountIds,
            List<AnalyticsQuery.DateRange> buckets) {
        validateBuckets(buckets);
        MapSqlParameterSource parameters = parameters(
            accountIds,
            new AnalyticsQuery.DateRange(
                buckets.getFirst().startInclusive(), buckets.getLast().endExclusive()),
            AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE);
        StringBuilder bucketCase = new StringBuilder("CASE");
        for (int index = 0; index < buckets.size(); index++) {
            parameters
                .addValue("bucket_start_" + index,
                    Timestamp.from(buckets.get(index).startInclusive()))
                .addValue("bucket_end_" + index,
                    Timestamp.from(buckets.get(index).endExclusive()));
            bucketCase.append(" WHEN execution.reserved_at>=:bucket_start_")
                .append(index)
                .append(" AND execution.reserved_at<:bucket_end_")
                .append(index)
                .append(" THEN ")
                .append(index);
        }
        bucketCase.append(" END");
        Map<Integer, Long> rows = jdbc.query("""
            SELECT bucket_index, COUNT(*) AS gap_count
              FROM (
                  SELECT %s AS bucket_index
                    FROM action_execution_targets target
                    JOIN action_executions execution
                      ON execution.id=target.execution_id
                     AND execution.account_id=target.account_id
                    JOIN user_actions action
                      ON action.id=execution.action_id
                     AND action.account_id=execution.account_id
                   WHERE target.account_id IN (:account_ids)
                     AND action.action_type='UNSUBSCRIBE'
                     AND execution.reserved_at>=:start AND execution.reserved_at<:end
                     AND (
                         target.unsubscribe_attempt_id IS NULL
                         OR NOT EXISTS (
                             SELECT 1 FROM unsubscribe_outcome_events outcome
                              WHERE outcome.attempt_id=target.unsubscribe_attempt_id
                                AND outcome.account_id=target.account_id
                         )
                     )
              ) gaps
             WHERE bucket_index IS NOT NULL
             GROUP BY bucket_index
             ORDER BY bucket_index
            """.formatted(bucketCase), parameters, result -> {
                LinkedHashMap<Integer, Long> counts = new LinkedHashMap<>();
                while (result.next()) {
                    counts.put(result.getInt("bucket_index"), result.getLong("gap_count"));
                }
                return counts;
            });
        return bucketCounts(buckets.size(), rows);
    }

    public List<AnalyticsTopSender> topSenders(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Top sender limit must be between 1 and 100");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_ids", authorized(accountIds))
            .addValue("start", Timestamp.from(range.startInclusive()))
            .addValue("end", Timestamp.from(range.endExclusive()))
            .addValue("limit", limit);
        return jdbc.query("""
            SELECT metadata.account_id,
                   CASE WHEN account.email IS NULL OR trim(account.email)=''
                        THEN 'Gmail account'
                        ELSE account.email || ' (Gmail)' END AS account_label,
                   COALESCE(NULLIF(lower(trim(metadata.from_address)), ''),
                            '~unknown') AS sender_key,
                   MIN(COALESCE(NULLIF(trim(metadata.from_display_name), ''),
                                'Unknown sender')) AS sender_label,
                   NULLIF(lower(trim(metadata.from_address)), '') AS sender_address,
                   MIN(NULLIF(lower(trim(metadata.from_domain)), '')) AS sender_domain,
                   COUNT(DISTINCT metadata.id) AS received_count
              FROM message_metadata metadata
              JOIN user_accounts account ON account.id=metadata.account_id
             WHERE metadata.account_id IN (:account_ids)
               AND metadata.received_at>=:start AND metadata.received_at<:end
             GROUP BY metadata.account_id, account.email,
                      COALESCE(NULLIF(lower(trim(metadata.from_address)), ''),
                               '~unknown'),
                      NULLIF(lower(trim(metadata.from_address)), '')
             ORDER BY received_count DESC, sender_key, metadata.account_id
             LIMIT :limit
            """, parameters, (row, index) -> new AnalyticsTopSender(
                row.getLong("account_id"),
                row.getString("sender_key"),
                row.getString("account_label"),
                row.getString("sender_label"),
                row.getString("sender_address"),
                row.getString("sender_domain"),
                row.getLong("received_count"),
                null));
    }

    private static MapSqlParameterSource parameters(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        return new MapSqlParameterSource()
            .addValue("account_ids", authorized(accountIds))
            .addValue("start", Timestamp.from(range.startInclusive()))
            .addValue("end", Timestamp.from(range.endExclusive()))
            .addValue("action", action.name());
    }

    private static List<Long> authorized(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        return accountIds.stream().distinct().sorted().toList();
    }

    private static void validateBuckets(List<AnalyticsQuery.DateRange> buckets) {
        if (buckets == null || buckets.isEmpty()
                || buckets.size() > AnalyticsQuery.MAX_DATES) {
            throw new IllegalArgumentException("A bounded bucket set is required");
        }
        for (int index = 1; index < buckets.size(); index++) {
            if (!buckets.get(index - 1).endExclusive()
                    .equals(buckets.get(index).startInclusive())) {
                throw new IllegalArgumentException("Buckets must be contiguous");
            }
        }
    }

    private static List<Long> bucketCounts(int size, Map<Integer, Long> rows) {
        List<Long> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(rows.getOrDefault(index, 0L));
        }
        return List.copyOf(result);
    }
}
