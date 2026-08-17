package com.tidymail.repository;

import com.tidymail.dto.ReviewActionGroup;
import com.tidymail.dto.ReviewActionRow;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.dto.ReviewQueueModel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.tidymail.dto.EmailStatsModal;
import com.tidymail.mail.AttachmentState;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Queries the review queue: metrics, counts, group totals, paged rows,
 * approval candidates, and email stats/sender history for a given action.
 * <p>
 * Product functionality: the Review decision surface and its email-stats
 * modal.
 */
@Repository
public class ReviewActionQueryRepository {
    private static final String PIPELINE = """
        WITH conflict_rows AS (
            SELECT account_id, lower_action_id AS action_id, id, public_id, overlap_count
            FROM action_conflicts WHERE status = 'OPEN'
            UNION ALL
            SELECT account_id, higher_action_id AS action_id, id, public_id, overlap_count
            FROM action_conflicts WHERE status = 'OPEN'
        ),
        conflict_facts AS (
            SELECT account_id, action_id, COUNT(*) AS conflict_count,
                   SUM(overlap_count) AS overlap_count,
                   MIN(CAST(public_id AS VARCHAR)) AS conflict_public_id
            FROM conflict_rows GROUP BY account_id, action_id
        ),
        materialized_counts AS (
            SELECT scope_id, account_id, COUNT(*) AS affected_count
            FROM action_scope_targets
            GROUP BY scope_id, account_id
        ),
        authorized_rows AS (
            SELECT a.id AS action_id, a.public_id AS action_public_id,
                   a.account_id, ua.email AS account_email,
                   a.action_type, a.execution_mode, a.decision_source, a.status,
                   materialized.affected_count,
                   a.created_at, a.queued_at, a."sequence" AS queue_sequence,
                   i.safe_target_label, i.target_search_key,
                   COALESCE(c.conflict_count, 0) AS conflict_count,
                   COALESCE(c.overlap_count, 0) AS overlap_count,
                   c.conflict_public_id,
                   CASE WHEN a.status = 'QUEUED'
                             AND a.action_type NOT IN ('REVIEW', 'UNSUBSCRIBE')
                             AND COALESCE(c.conflict_count, 0) = 0
                        THEN 1 ELSE 0 END AS eligible,
                   CASE WHEN a.action_type = 'REVIEW'
                        THEN 'NEEDS_DECISION' ELSE a.action_type END AS group_kind
            FROM user_actions a
            JOIN user_accounts ua ON ua.id = a.account_id
            JOIN action_intents i
              ON i.action_id = a.id AND i.account_id = a.account_id
            JOIN materialized_counts materialized
              ON materialized.scope_id = a.action_scope_id
             AND materialized.account_id = a.account_id
            LEFT JOIN conflict_facts c
              ON c.action_id = a.id AND c.account_id = a.account_id
            WHERE a.account_id IN (:account_ids)
              AND a.status IN ('QUEUED', 'BLOCKED')
        ),
        filtered_rows AS (
            SELECT * FROM authorized_rows
            WHERE (:action = 'all'
                   OR (:action = 'needs_decision' AND action_type = 'REVIEW')
                   OR action_type = UPPER(:action))
              AND (:state = 'all'
                   OR (:state = 'eligible' AND eligible = 1)
                   OR (:state = 'conflicted' AND conflict_count > 0)
                   OR (:state = 'needs_decision' AND action_type = 'REVIEW'))
              AND (:target = '' OR LOWER(target_search_key)
                   LIKE :target_pattern ESCAPE '\\')
        )
        """;
    private static final String ORDER = """
        ORDER BY queued_at DESC, queue_sequence DESC
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewActionQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReviewQueueModel.Metrics metrics(
            List<Long> accountIds, ReviewQuery query) {
        return jdbc.queryForObject(PIPELINE + """
            SELECT COUNT(*) AS queued_actions,
                   COALESCE(SUM(affected_count), 0) AS exact_targets,
                   (SELECT COUNT(DISTINCT conflict.id)
                      FROM conflict_rows conflict
                     WHERE EXISTS (
                         SELECT 1 FROM filtered_rows filtered
                          WHERE filtered.account_id = conflict.account_id
                            AND filtered.action_id = conflict.action_id
                     )) AS open_conflicts,
                   COALESCE(SUM(eligible), 0) AS eligible_actions
            FROM filtered_rows
            """, parameters(accountIds, query), (result, row) ->
                new ReviewQueueModel.Metrics(
                    result.getLong("queued_actions"),
                    result.getLong("exact_targets"),
                    result.getLong("open_conflicts"),
                    result.getLong("eligible_actions")));
    }

    public long count(List<Long> accountIds, ReviewQuery query) {
        Long count = jdbc.queryForObject(
            PIPELINE + "SELECT COUNT(*) FROM filtered_rows",
            parameters(accountIds, query), Long.class);
        return count == null ? 0 : count;
    }

    public List<GroupTotal> groupTotals(
            List<Long> accountIds, ReviewQuery query) {
        return jdbc.query(PIPELINE + """
            SELECT group_kind, COUNT(*) AS action_count,
                   COUNT(DISTINCT account_id) AS account_count,
                   COALESCE(SUM(affected_count), 0) AS target_count,
                   COALESCE(SUM(conflict_count), 0) AS conflict_count,
                   COALESCE(SUM(eligible), 0) AS eligible_count
            FROM filtered_rows GROUP BY group_kind
            """, parameters(accountIds, query), (result, row) -> new GroupTotal(
                kind(result.getString("group_kind")),
                result.getLong("action_count"),
                result.getLong("account_count"),
                result.getLong("target_count"),
                result.getLong("conflict_count"),
                result.getLong("eligible_count")));
    }

    public List<ReviewActionRow> page(
            List<Long> accountIds, ReviewQuery query) {
        MapSqlParameterSource parameters = parameters(accountIds, query)
            .addValue("limit", ReviewQuery.PAGE_SIZE)
            .addValue("offset", (long) query.page() * ReviewQuery.PAGE_SIZE);
        return jdbc.query(PIPELINE + """
            SELECT * FROM filtered_rows
            """ + ORDER + " LIMIT :limit OFFSET :offset", parameters, this::row);
    }

    public List<ApprovalCandidate> approvalCandidates(
            List<Long> accountIds, ReviewQuery query) {
        return jdbc.query(PIPELINE + """
            SELECT action_id, action_public_id, account_id, action_type,
                   affected_count, eligible, conflict_count, status,
                   safe_target_label, queued_at, queue_sequence
            FROM filtered_rows
            """ + ORDER, parameters(accountIds, query), (result, row) ->
                new ApprovalCandidate(
                    result.getLong("action_id"),
                    uuid(result, "action_public_id"),
                    result.getLong("account_id"),
                    result.getString("action_type"),
                    result.getInt("affected_count"),
                    result.getInt("eligible") == 1,
                    result.getInt("conflict_count"),
                    result.getString("status"),
                    result.getString("safe_target_label"),
                    instant(result, "queued_at"),
                    result.getLong("queue_sequence")));
    }

    public EmailStatsSource emailStatsSource(
            List<Long> accountIds, UUID actionPublicId) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("Owned accounts are required");
        }
        List<EmailStatsSource> rows = jdbc.query("""
            SELECT a.public_id, a.account_id, a.action_scope_id, a.action_type,
                   a.decision_source, a.status, i.safe_target_label,
                   COALESCE(i.normalized_sender_address, a.sender_group_key)
                       AS normalized_sender_address,
                   scope.message_ids
              FROM user_actions a
              JOIN action_intents i
                ON i.action_id = a.id AND i.account_id = a.account_id
              JOIN action_scopes scope
                ON scope.id = a.action_scope_id AND scope.account_id = a.account_id
             WHERE a.account_id IN (:account_ids)
               AND a.public_id = :action_public_id
            """, new MapSqlParameterSource()
                .addValue("account_ids", List.copyOf(accountIds))
                .addValue("action_public_id", actionPublicId),
            (result, row) -> new EmailStatsSource(
                uuid(result, "public_id"),
                result.getLong("account_id"),
                result.getLong("action_scope_id"),
                result.getString("action_type"),
                result.getString("decision_source"),
                result.getString("status"),
                result.getString("safe_target_label"),
                result.getString("normalized_sender_address"),
                stringList(result.getArray("message_ids"))));
        if (rows.size() != 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "action_not_found");
        }
        EmailStatsSource source = rows.getFirst();
        List<Integer> ordinals = jdbc.queryForList("""
            SELECT scope_ordinal
              FROM action_scope_targets
             WHERE scope_id = :scope_id AND account_id = :account_id
             ORDER BY scope_ordinal
            """, new MapSqlParameterSource()
                .addValue("scope_id", source.scopeId())
                .addValue("account_id", source.accountId()), Integer.class);
        List<String> materializedIds = ordinals.stream()
            .map(ordinal -> {
                if (ordinal < 0 || ordinal >= source.messageIds().size()) {
                    throw new IllegalStateException("Invalid materialized scope ordinal");
                }
                return source.messageIds().get(ordinal);
            }).toList();
        return source.withMessageIds(materializedIds);
    }

    public List<EmailStatsModal.EmailRow> affectedEmails(
            EmailStatsSource source) {
        if (source.messageIds().isEmpty()) return List.of();
        return jdbc.query("""
            SELECT COALESCE(NULLIF(from_display_name, ''), from_address) AS sender,
                   subject_header, received_at, unread, attachment_state
              FROM message_metadata
             WHERE account_id = :account_id
               AND provider_message_id IN (:message_ids)
             ORDER BY CASE WHEN received_at IS NULL THEN 1 ELSE 0 END,
                      received_at DESC, id DESC
            """, new MapSqlParameterSource()
                .addValue("account_id", source.accountId())
                .addValue("message_ids", source.messageIds()),
            (result, row) -> emailRow(result));
    }

    public List<EmailStatsModal.EmailRow> senderHistory(
            EmailStatsSource source) {
        if (source.normalizedSenderAddress() == null
                || source.normalizedSenderAddress().isBlank()) {
            return List.of();
        }
        return jdbc.query("""
            SELECT COALESCE(NULLIF(from_display_name, ''), from_address) AS sender,
                   subject_header, received_at, unread, attachment_state
              FROM message_metadata
             WHERE account_id = :account_id
               AND LOWER(from_address) = LOWER(:sender_address)
             ORDER BY CASE WHEN received_at IS NULL THEN 1 ELSE 0 END,
                      received_at DESC, id DESC
            """, new MapSqlParameterSource()
                .addValue("account_id", source.accountId())
                .addValue("sender_address", source.normalizedSenderAddress()),
            (result, row) -> emailRow(result));
    }

    private ReviewActionRow row(ResultSet result, int rowNumber) throws SQLException {
        String actionType = result.getString("action_type");
        int conflicts = result.getInt("conflict_count");
        List<String> reasons = new ArrayList<>();
        if ("REVIEW".equals(actionType)) reasons.add("NEEDS_DECISION");
        if ("UNSUBSCRIBE".equals(actionType)) reasons.add("FRESH_CONSENT_REQUIRED");
        if (conflicts > 0) reasons.add("CONFLICT");
        return new ReviewActionRow(
            uuid(result, "action_public_id"),
            uuidOrNull(result, "conflict_public_id"),
            kind(result.getString("group_kind")),
            label(actionType),
            result.getString("account_email") + " (Gmail)",
            sourceLabel(result.getString("decision_source")),
            result.getString("safe_target_label"),
            result.getInt("affected_count"),
            title(result.getString("execution_mode")),
            title(result.getString("status")),
            instant(result, "created_at"),
            result.getInt("eligible") == 1,
            conflicts,
            result.getInt("overlap_count"),
            reasons,
            null);
    }

    private static MapSqlParameterSource parameters(
            List<Long> accountIds, ReviewQuery query) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("Owned accounts are required");
        }
        String escaped = query.target().toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return new MapSqlParameterSource()
            .addValue("account_ids", List.copyOf(accountIds))
            .addValue("action", query.action())
            .addValue("state", query.state())
            .addValue("target", query.target())
            .addValue("target_pattern", "%" + escaped + "%");
    }

    private static ReviewActionGroup.Kind kind(String value) {
        return ReviewActionGroup.Kind.valueOf(value);
    }

    private static String label(String value) {
        return switch (value) {
            case "MARK_READ" -> "Mark read";
            case "REVIEW" -> "Needs decision";
            default -> title(value);
        };
    }

    public static String sourceLabel(String value) {
        return switch (value) {
            case "SENDERS" -> "By Sender";
            case "UNREAD" -> "Unread";
            case "UNSUBSCRIBE" -> "Unsubscribe";
            case "REVIEW" -> "Review";
            case "LEGACY" -> "Legacy";
            default -> throw new IllegalArgumentException("Unknown decision source");
        };
    }

    private static EmailStatsModal.EmailRow emailRow(ResultSet result)
            throws SQLException {
        Timestamp received = result.getTimestamp("received_at");
        return new EmailStatsModal.EmailRow(
            result.getString("sender"),
            result.getString("subject_header"),
            received == null ? null : received.toInstant(),
            result.getBoolean("unread"),
            AttachmentState.valueOf(result.getString("attachment_state")));
    }

    private static List<String> stringList(java.sql.Array array) throws SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (raw instanceof Object[] values) {
            return java.util.Arrays.stream(values)
                .map(String::valueOf).toList();
        }
        throw new SQLException("Unsupported message_ids array");
    }

    private static String title(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static UUID uuid(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static UUID uuidOrNull(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null
            : value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value.toInstant();
    }

    public record GroupTotal(
        ReviewActionGroup.Kind kind,
        long actionCount,
        long accountCount,
        long targetCount,
        long conflictCount,
        long eligibleCount
    ) {
    }

    public record ApprovalCandidate(
        long actionId,
        UUID actionPublicId,
        long accountId,
        String actionType,
        int targetCount,
        boolean eligible,
        int conflictCount,
        String status,
        String targetLabel,
        Instant queuedAt,
        long sequence
    ) {
    }

    public record EmailStatsSource(
        UUID actionPublicId,
        long accountId,
        long scopeId,
        String actionType,
        String decisionSource,
        String status,
        String targetLabel,
        String normalizedSenderAddress,
        List<String> messageIds
    ) {
        public EmailStatsSource {
            messageIds = List.copyOf(messageIds);
        }

        public EmailStatsSource withMessageIds(List<String> values) {
            return new EmailStatsSource(
                actionPublicId, accountId, scopeId, actionType, decisionSource,
                status, targetLabel, normalizedSenderAddress, values);
        }
    }
}
