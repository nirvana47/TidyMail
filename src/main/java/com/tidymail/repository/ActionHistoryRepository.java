package com.tidymail.repository;

import com.tidymail.dto.ActionHistoryModel;
import com.tidymail.dto.HistoryQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActionHistoryRepository {
    private static final String PIPELINE = """
        WITH history_rows AS (
            SELECT a.id AS action_id, a.public_id AS action_public_id,
                   a.account_id, ua.email AS account_email, ua.provider,
                   a.action_type, a.execution_mode, a.decision_source, a.status,
                   a.action_scope_id, a.undo_eligible_until,
                   COALESCE(i.safe_target_label, a.sender_group_key, 'Legacy action')
                       AS safe_target_label,
                   i.compensates_action_id,
                   original.public_id AS compensates_action_public_id,
                    COALESCE(undo.id, e.id) AS execution_id,
                    COALESCE(undo.requested_count, e.requested_count) AS requested_count,
                    COALESCE(undo.accepted_count, e.accepted_count) AS accepted_count,
                    COALESCE(undo.succeeded_count, e.succeeded_count) AS succeeded_count,
                    COALESCE(undo.failed_count, e.failed_count) AS failed_count,
                    COALESCE(undo.verification_pending_count,
                             e.verification_pending_count) AS verification_pending_count,
                    COALESCE(undo.not_dispatched_count,
                             e.not_dispatched_count) AS not_dispatched_count,
                    (SELECT attempt.latest_status
                       FROM action_execution_targets linked_target
                       JOIN unsubscribe_attempts attempt
                         ON attempt.id = linked_target.unsubscribe_attempt_id
                        AND attempt.account_id = linked_target.account_id
                      WHERE linked_target.execution_id = e.id
                        AND linked_target.account_id = a.account_id
                      ORDER BY linked_target.scope_ordinal
                      LIMIT 1) AS unsubscribe_outcome,
                    COALESCE(undo.completed_at, undo.started_at, undo.reserved_at,
                             e.completed_at, a.completed_at, a.created_at)
                       AS occurred_at,
                   (SELECT COUNT(*) FROM action_execution_targets et
                     WHERE et.execution_id = e.id AND et.account_id = a.account_id
                       AND et.status = 'SUCCEEDED') AS applied_count,
                    CASE WHEN undo.id IS NULL THEN 0 ELSE 1 END AS undo_count
            FROM user_actions a
            JOIN user_accounts ua ON ua.id = a.account_id
            LEFT JOIN action_intents i
              ON i.action_id = a.id AND i.account_id = a.account_id
            LEFT JOIN user_actions original
              ON original.id = i.compensates_action_id
             AND original.account_id = a.account_id
            LEFT JOIN action_executions e
              ON e.action_id = a.id AND e.account_id = a.account_id
             AND e.operation_kind IN ('FORWARD', 'COMPENSATION')
              LEFT JOIN action_executions undo
                ON undo.action_id = a.id AND undo.account_id = a.account_id
               AND undo.operation_kind = 'UNDO'
            WHERE a.account_id IN (:account_ids)
              AND (CAST(:from_time AS TIMESTAMP WITH TIME ZONE) IS NULL OR
                    COALESCE(undo.completed_at, undo.started_at, undo.reserved_at,
                             e.completed_at, a.completed_at, a.created_at)
                        >= CAST(:from_time AS TIMESTAMP WITH TIME ZONE))
              AND (CAST(:to_time AS TIMESTAMP WITH TIME ZONE) IS NULL OR
                    COALESCE(undo.completed_at, undo.started_at, undo.reserved_at,
                             e.completed_at, a.completed_at, a.created_at)
                        < CAST(:to_time AS TIMESTAMP WITH TIME ZONE))
              AND (:action = 'all' OR a.action_type = UPPER(:action))
              AND (:target = '' OR LOWER(COALESCE(
                   i.target_search_key, i.safe_target_label, a.sender_group_key, ''))
                   LIKE :target_pattern ESCAPE '\\')
              AND (:status = 'all'
                   OR (:status = 'in_progress'
                       AND a.status IN ('QUEUED', 'BLOCKED', 'EXECUTING'))
                   OR (:status = 'applied' AND a.status = 'APPLIED')
                   OR (:status = 'partial'
                       AND a.status IN ('PARTIAL', 'PARTIALLY_UNDONE'))
                   OR (:status = 'verification_pending'
                       AND a.status = 'VERIFICATION_PENDING')
                   OR (:status = 'failed' AND a.status = 'FAILED')
                   OR (:status = 'cancelled' AND a.status = 'CANCELLED')
                   OR (:status = 'undone' AND a.status = 'UNDONE'))
        )
        """;
    private static final String ORDER =
        " ORDER BY occurred_at DESC, action_id DESC ";

    private final NamedParameterJdbcTemplate jdbc;

    public ActionHistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ActionHistoryModel.Metrics metrics(
            List<Long> accountIds, HistoryQuery query) {
        return jdbc.queryForObject(PIPELINE + """
            SELECT COUNT(*) AS actions,
                   COALESCE(SUM(CASE WHEN decision_source = 'LEGACY'
                                     THEN 0 ELSE succeeded_count END), 0)
                       AS succeeded,
                   COALESCE(SUM(CASE WHEN decision_source = 'LEGACY'
                                     THEN 0 ELSE failed_count END), 0)
                       AS failed,
                   COALESCE(SUM(CASE WHEN decision_source = 'LEGACY'
                                     THEN 0 ELSE verification_pending_count END), 0)
                       AS pending
              FROM history_rows
            """, parameters(accountIds, query), (result, row) ->
                new ActionHistoryModel.Metrics(
                    result.getLong("actions"), result.getLong("succeeded"),
                    result.getLong("failed"), result.getLong("pending")));
    }

    public long count(List<Long> accountIds, HistoryQuery query) {
        Long count = jdbc.queryForObject(
            PIPELINE + "SELECT COUNT(*) FROM history_rows",
            parameters(accountIds, query), Long.class);
        return count == null ? 0 : count;
    }

    public List<HistoryRecord> page(
            List<Long> accountIds, HistoryQuery query) {
        MapSqlParameterSource parameters = parameters(accountIds, query)
            .addValue("limit", HistoryQuery.PAGE_SIZE)
            .addValue("offset", (long) query.page() * HistoryQuery.PAGE_SIZE);
        return jdbc.query(
            PIPELINE + "SELECT * FROM history_rows" + ORDER
                + "LIMIT :limit OFFSET :offset",
            parameters, this::record);
    }

    public Stream<HistoryRecord> stream(
            List<Long> accountIds, HistoryQuery query) {
        return jdbc.queryForStream(
            PIPELINE + "SELECT * FROM history_rows" + ORDER,
            parameters(accountIds, query), this::record);
    }

    public Map<Long, List<EventRecord>> events(List<Long> actionIds) {
        if (actionIds.isEmpty()) return Map.of();
        LinkedHashMap<Long, List<EventRecord>> grouped = new LinkedHashMap<>();
        jdbc.query("""
            SELECT action_id, event_type, resulting_status, occurred_at
              FROM action_events
             WHERE action_id IN (:action_ids)
             ORDER BY action_id, occurred_at, id
            """, new MapSqlParameterSource("action_ids", actionIds), result -> {
                long actionId = result.getLong("action_id");
                grouped.computeIfAbsent(actionId, ignored -> new java.util.ArrayList<>())
                    .add(new EventRecord(
                        result.getString("event_type"),
                        result.getString("resulting_status"),
                        instant(result, "occurred_at")));
            });
        return Map.copyOf(grouped);
    }

    public Map<Long, Map<String, Integer>> reasons(List<Long> executionIds) {
        if (executionIds.isEmpty()) return Map.of();
        LinkedHashMap<Long, Map<String, Integer>> grouped = new LinkedHashMap<>();
        jdbc.query("""
            SELECT execution_id, reason_code, COUNT(*) AS reason_count
              FROM action_execution_targets
               WHERE execution_id IN (:execution_ids)
                 AND reason_code IS NOT NULL
                 AND reason_code <> 'NONE'
             GROUP BY execution_id, reason_code
             ORDER BY execution_id, reason_count DESC, reason_code
            """, new MapSqlParameterSource("execution_ids", executionIds), result -> {
                long executionId = result.getLong("execution_id");
                grouped.computeIfAbsent(executionId, ignored -> new LinkedHashMap<>())
                    .put(result.getString("reason_code"), result.getInt("reason_count"));
            });
        return Map.copyOf(grouped);
    }

    private HistoryRecord record(ResultSet result, int row) throws SQLException {
        return new HistoryRecord(
            result.getLong("action_id"), uuid(result, "action_public_id"),
            nullableLong(result, "execution_id"), result.getString("account_email"),
            result.getString("provider"), result.getString("safe_target_label"),
            result.getString("action_type"), result.getString("execution_mode"),
            result.getString("decision_source"), result.getString("status"),
            integer(result, "requested_count"), integer(result, "accepted_count"),
            integer(result, "succeeded_count"), integer(result, "failed_count"),
            integer(result, "verification_pending_count"),
            integer(result, "not_dispatched_count"),
            result.getString("unsubscribe_outcome"),
            instant(result, "occurred_at"), result.getTimestamp("undo_eligible_until") == null
                ? null : result.getTimestamp("undo_eligible_until").toInstant(),
            result.getInt("applied_count"), result.getInt("undo_count"),
            uuidOrNull(result, "compensates_action_public_id"));
    }

    private static MapSqlParameterSource parameters(
            List<Long> accountIds, HistoryQuery query) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("Owned accounts are required");
        }
        String escaped = query.target().toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        Instant from = query.from() == null ? null
            : query.from().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = query.to() == null ? null
            : query.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new MapSqlParameterSource()
            .addValue("account_ids", List.copyOf(accountIds))
            .addValue("from_time", from == null ? null : Timestamp.from(from))
            .addValue("to_time", to == null ? null : Timestamp.from(to))
            .addValue("action", query.action())
            .addValue("status", query.status())
            .addValue("target", query.target())
            .addValue("target_pattern", "%" + escaped + "%");
    }

    private static Integer integer(ResultSet result, String name) throws SQLException {
        int value = result.getInt(name);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String name) throws SQLException {
        long value = result.getLong(name);
        return result.wasNull() ? null : value;
    }

    private static UUID uuid(ResultSet result, String name) throws SQLException {
        Object value = result.getObject(name);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static UUID uuidOrNull(ResultSet result, String name) throws SQLException {
        Object value = result.getObject(name);
        return value == null ? null
            : value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static Instant instant(ResultSet result, String name) throws SQLException {
        Timestamp value = result.getTimestamp(name);
        return value.toInstant();
    }

    public record HistoryRecord(
        long actionId, UUID actionPublicId, Long executionId, String accountEmail,
        String provider, String targetLabel, String action, String mode,
        String source, String status, Integer requested, Integer accepted,
        Integer succeeded, Integer failed, Integer pending, Integer notDispatched,
        String unsubscribeOutcome, Instant occurredAt, Instant undoEligibleUntil, int appliedCount,
        int undoCount, UUID compensatesActionPublicId
    ) {
    }

    public record EventRecord(String type, String status, Instant occurredAt) {
    }
}
