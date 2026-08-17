package com.tidymail.repository;

import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.UnreadQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds shared raw-SQL fragments for the Senders/Unread decision-surface
 * queries.
 * <p>
 * Product functionality: the Senders and Unread decision surfaces.
 */
public final class DecisionSurfaceSql {
    private DecisionSurfaceSql() {
    }

    public static String unreadScopeCounts() {
        return """
            SELECT COUNT(m.id) FILTER (
                       WHERE m.unread = TRUE AND 'INBOX'=ANY(m.labels)
                   ) AS inbox_unread_count,
                   COUNT(m.id) FILTER (
                       WHERE m.unread = TRUE
                   ) AS all_mail_unread_count,
                   COUNT(DISTINCT a.id) = :account_count
                       AND COUNT(DISTINCT CASE
                           WHEN state.initial_batch_completed_at IS NOT NULL THEN a.id
                       END) = :account_count AS available
              FROM user_accounts a
              LEFT JOIN mailbox_sync_state state ON state.account_id = a.id
              LEFT JOIN message_metadata m ON m.account_id = a.id
             WHERE a.id IN (:account_ids)
            """;
    }

    public static QuerySpec sender(
            Collection<Long> accountIds, SenderQuery query, Instant now) {
        return sender(accountIds, query, now, null);
    }

    public static QuerySpec sender(
            Collection<Long> accountIds,
            SenderQuery query,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        LinkedHashMap<String, Object> parameters = common(accountIds, now);
        String windowPredicate = switch (query.window()) {
            case D7 -> " AND m.received_at >= :seven_days";
            case D30 -> " AND m.received_at >= :thirty_days";
            case D90 -> " AND m.received_at >= :ninety_days";
            case ALL -> "";
        };
        String analyticsPredicate = "";
        if (analyticsScope != null) {
            List<Long> authorized = accountIds.stream().distinct().sorted().toList();
            if (authorized.size() != 1
                    || authorized.getFirst().longValue() != analyticsScope.accountId()
                    || !query.account().equals(Long.toString(analyticsScope.accountId()))
                    || query.analyticsKey().isBlank()) {
                throw new IllegalArgumentException(
                    "Analytics sender scope must match one selected account");
            }
            parameters.put("analytics_sender", analyticsScope.senderKey());
            parameters.put(
                "analytics_start", Timestamp.from(analyticsScope.startInclusive()));
            parameters.put(
                "analytics_end", Timestamp.from(analyticsScope.endExclusive()));
            analyticsPredicate = """
                 AND COALESCE(NULLIF(lower(trim(m.from_address)), ''), '__missing__')
                     = :analytics_sender
                 AND m.received_at >= :analytics_start
                 AND m.received_at < :analytics_end
                """;
        } else if (!query.analyticsKey().isBlank()) {
            throw new IllegalArgumentException("Analytics sender scope was not resolved");
        }
        String cte = """
            WITH thread_counts AS (
                SELECT account_id, provider_thread_id, COUNT(*) AS message_count
                  FROM message_metadata
                 WHERE account_id IN (:account_ids)
                   AND provider_thread_id IS NOT NULL
                 GROUP BY account_id, provider_thread_id
            ),
            base_rows AS (
                SELECT m.id AS metadata_id, m.account_id, a.email AS account_email,
                       m.provider_message_id,
                       COALESCE(NULLIF(lower(trim(m.from_address)), ''), '__missing__') AS sender_key,
                       m.from_display_name, m.from_domain, m.received_at, m.unread,
                       m.size_estimate,
                       CASE WHEN CARDINALITY(m.list_id_values) > 0
                                  OR CARDINALITY(m.list_unsubscribe_values) > 0
                            THEN 1 ELSE 0 END AS list_signal,
                       CASE WHEN
                            (CASE WHEN CARDINALITY(m.auto_submitted_values) > 0
                                  THEN CASE WHEN lower(trim(m.auto_submitted_values[1])) <> 'no'
                                            THEN 1 ELSE 0 END
                                  ELSE 0 END) = 1
                            OR CARDINALITY(m.feedback_id_values) > 0
                            OR (CASE WHEN CARDINALITY(m.precedence_values) > 0
                                  THEN CASE WHEN lower(trim(m.precedence_values[1]))
                                                   IN ('bulk', 'list', 'junk')
                                            THEN 1 ELSE 0 END
                                  ELSE 0 END) = 1
                            THEN 1 ELSE 0 END AS automated_signal,
                       CASE WHEN CARDINALITY(m.return_path_values) > 0
                            THEN 1 ELSE 0 END AS return_path_signal,
                       CASE WHEN CARDINALITY(m.message_id_values) > 0
                            THEN 1 ELSE 0 END AS message_id_signal,
                       CASE WHEN CARDINALITY(m.references_values) > 0
                                  OR CARDINALITY(m.in_reply_to_values) > 0
                                  OR COALESCE(tc.message_count, 0) > 1
                            THEN 1 ELSE 0 END AS thread_signal,
                       CASE WHEN m.from_address IS NOT NULL AND EXISTS (
                            SELECT 1 FROM protected_senders ps
                            WHERE ps.account_id = m.account_id
                              AND ps.sender_address = lower(trim(m.from_address))
                       ) THEN 1 ELSE 0 END AS protected_sender
                FROM message_metadata m
                JOIN user_accounts a ON a.id = m.account_id
                LEFT JOIN thread_counts tc
                  ON tc.account_id = m.account_id
                 AND tc.provider_thread_id = m.provider_thread_id
                WHERE m.account_id IN (:account_ids)%s%s
            ),
            sender_facts AS (
                SELECT account_id, account_email, sender_key,
                       COALESCE(MAX(NULLIF(trim(from_display_name), '')), 'Unknown sender')
                           AS display_name,
                       MAX(NULLIF(lower(trim(from_domain)), '')) AS domain,
                       COUNT(*) AS total_count,
                       SUM(CASE WHEN unread THEN 1 ELSE 0 END) AS unread_count,
                       CASE WHEN COUNT(size_estimate) = COUNT(*)
                            THEN SUM(size_estimate) ELSE NULL END AS estimated_size,
                       MIN(received_at) AS oldest_received_at,
                       MAX(received_at) AS newest_received_at,
                       MAX(list_signal) AS list_signal,
                       MAX(automated_signal) AS automated_signal,
                       MAX(return_path_signal) AS return_path_signal,
                       MAX(message_id_signal) AS message_id_signal,
                       MAX(thread_signal) AS thread_signal,
                       MAX(protected_sender) AS protected_sender,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM user_actions ua
                           WHERE ua.account_id = base_rows.account_id
                             AND ua.sender_group_key = base_rows.sender_key
                             AND ua.status = 'QUEUED'
                       ) THEN 1 ELSE 0 END AS queued
                FROM base_rows
                GROUP BY account_id, account_email, sender_key
            ),
            inferred_facts AS (
                SELECT sf.*,
                       CASE WHEN message_id_signal = 1 AND thread_signal = 1
                                THEN 'PERSONAL'
                            WHEN return_path_signal = 1 AND thread_signal = 0
                                 AND automated_signal = 0
                                THEN 'TRANSACTIONAL'
                            WHEN list_signal = 1 THEN 'LIST'
                            WHEN automated_signal = 1 THEN 'AUTOMATED'
                            WHEN return_path_signal = 1 AND thread_signal = 0
                                THEN 'TRANSACTIONAL'
                            ELSE 'UNKNOWN' END AS inferred_sender_type
                FROM sender_facts sf
            ),
            typed_facts AS (
                SELECT inf.*,
                       COALESCE(sto.sender_type, inf.inferred_sender_type) AS sender_type,
                       sto.sender_type AS manual_sender_type
                FROM inferred_facts inf
                LEFT JOIN sender_type_overrides sto
                  ON sto.account_id = inf.account_id
                 AND sto.sender_address = inf.sender_key
            ),
            risked_facts AS (
                SELECT tf.*,
                       CASE WHEN protected_sender = 1
                                  OR sender_type = 'PERSONAL' OR thread_signal = 1 THEN 'HIGH'
                            WHEN sender_type IN ('TRANSACTIONAL', 'UNKNOWN')
                                  OR newest_received_at >= :seven_days THEN 'MEDIUM'
                            ELSE 'LOW' END AS sender_risk
                FROM typed_facts tf
            ),
            classified_facts AS (
                SELECT rf.*,
                       CASE WHEN protected_sender = 1 THEN 'KEEP'
                            WHEN sender_risk = 'HIGH'
                                  OR sender_type IN ('PERSONAL', 'TRANSACTIONAL', 'UNKNOWN')
                                THEN 'REVIEW'
                            WHEN sender_type IN ('LIST', 'AUTOMATED')
                                  AND newest_received_at < :ninety_days THEN 'TRASH'
                            WHEN total_count > 0 AND unread_count = total_count THEN 'MARK_READ'
                            ELSE 'ARCHIVE' END AS recommendation
                FROM risked_facts rf
            ),
            filtered_facts AS (
                SELECT * FROM classified_facts WHERE 1 = 1%s
            )
            """.formatted(windowPredicate, analyticsPredicate, senderFilters(query, parameters));
        return new QuerySpec(cte, senderOrder(query.sort()), Map.copyOf(parameters));
    }

    public static QuerySpec unread(
            Collection<Long> accountIds, UnreadQuery query, Instant now) {
        LinkedHashMap<String, Object> parameters = common(accountIds, now);
        parameters.put("today", Timestamp.from(now.atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant()));
        String cte = """
            WITH unread_base AS (
                SELECT m.id AS metadata_id, m.account_id, m.provider_message_id,
                       m.provider_thread_id, a.email AS account_email,
                       COALESCE(NULLIF(lower(trim(m.from_address)), ''), '__missing__') AS sender_key,
                       COALESCE(NULLIF(trim(m.from_display_name), ''), 'Unknown sender')
                           AS display_name,
                       NULLIF(lower(trim(m.from_domain)), '') AS domain,
                       m.subject_header, m.received_at, m.provider,
                       m.size_estimate, m.attachment_state,
                       CASE WHEN m.attachment_state = 'PRESENT'
                            THEN TRUE ELSE FALSE END AS has_attachments,
                       sto.sender_type AS manual_sender_type,
                       CASE WHEN CARDINALITY(m.list_id_values) > 0
                                  OR CARDINALITY(m.list_unsubscribe_values) > 0
                            THEN 1 ELSE 0 END AS list_signal,
                       CASE WHEN
                            (CASE WHEN CARDINALITY(m.auto_submitted_values) > 0
                                  THEN CASE WHEN lower(trim(m.auto_submitted_values[1])) <> 'no'
                                            THEN 1 ELSE 0 END
                                  ELSE 0 END) = 1
                            OR CARDINALITY(m.feedback_id_values) > 0
                            OR (CASE WHEN CARDINALITY(m.precedence_values) > 0
                                  THEN CASE WHEN lower(trim(m.precedence_values[1]))
                                                   IN ('bulk', 'list', 'junk')
                                            THEN 1 ELSE 0 END
                                  ELSE 0 END) = 1
                            THEN 1 ELSE 0 END AS automated_signal,
                       CASE WHEN CARDINALITY(m.references_values) > 0
                            THEN 1 ELSE 0 END AS references_signal,
                       CASE WHEN CARDINALITY(m.in_reply_to_values) > 0
                            THEN 1 ELSE 0 END AS in_reply_to_signal,
                       CASE WHEN m.provider_thread_id IS NULL THEN 0 ELSE (
                           SELECT COUNT(*) FROM message_metadata mt
                           WHERE mt.account_id = m.account_id
                             AND mt.provider_thread_id = m.provider_thread_id
                       ) END AS authorized_thread_count,
                       CASE WHEN m.from_address IS NOT NULL AND EXISTS (
                           SELECT 1 FROM protected_senders ps
                           WHERE ps.account_id = m.account_id
                             AND ps.sender_address = lower(trim(m.from_address))
                       ) THEN 1 ELSE 0 END AS protected_sender
                FROM message_metadata m
                JOIN user_accounts a ON a.id = m.account_id
                LEFT JOIN sender_type_overrides sto
                  ON sto.account_id = m.account_id
                 AND sto.sender_address =
                     COALESCE(NULLIF(lower(trim(m.from_address)), ''), '__missing__')
                WHERE m.account_id IN (:account_ids)
                  AND m.unread = true
                  AND %s
            ),
            categorized_facts AS (
                SELECT ub.*,
                       CASE WHEN manual_sender_type = 'LIST' THEN 'LIST'
                            WHEN manual_sender_type = 'AUTOMATED' THEN 'AUTOMATED'
                            WHEN manual_sender_type IN ('TRANSACTIONAL', 'PERSONAL')
                                THEN 'ONE_TO_ONE'
                            WHEN list_signal = 1 THEN 'LIST'
                            WHEN automated_signal = 1 THEN 'AUTOMATED'
                            ELSE 'ONE_TO_ONE' END AS category
                FROM unread_base ub
            ),
            threaded_facts AS (
                SELECT cf.*,
                       CASE WHEN authorized_thread_count > 1
                                  OR references_signal = 1 OR in_reply_to_signal = 1
                            THEN 'THREADED' ELSE 'SINGLE' END AS thread_state
                FROM categorized_facts cf
            ),
            waiting_facts AS (
                SELECT tf.*,
                       CASE WHEN category = 'ONE_TO_ONE' AND thread_state = 'THREADED'
                                  AND (references_signal = 1 OR in_reply_to_signal = 1)
                            THEN 1 ELSE 0 END AS waiting_on_me
                FROM threaded_facts tf
            ),
            classified_facts AS (
                SELECT wf.*,
                       CASE WHEN waiting_on_me = 1 THEN 'REPLY_REQUIRED'
                            WHEN category = 'ONE_TO_ONE' AND thread_state = 'THREADED'
                                THEN 'REVIEW'
                            WHEN category = 'ONE_TO_ONE' THEN 'KEEP'
                            WHEN protected_sender = 0 AND received_at < :ninety_days THEN 'TRASH'
                            WHEN protected_sender = 0 AND received_at < :thirty_days THEN 'ARCHIVE'
                            ELSE 'MARK_READ' END AS recommendation
                FROM waiting_facts wf
            ),
            filtered_facts AS (
                SELECT * FROM classified_facts WHERE 1 = 1%s
            )
            """.formatted(
                hasLabel("m.labels", "INBOX"),
                unreadFilters(query, parameters));
        return new QuerySpec(cte, unreadOrder(query.sort()), Map.copyOf(parameters));
    }

    static String hasLabel(String column, String label) {
        return "'" + label + "'=ANY(" + column + ")";
    }

    private static LinkedHashMap<String, Object> common(
            Collection<Long> accountIds, Instant now) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        if (now == null) throw new IllegalArgumentException("Query instant is required");
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("account_ids", accountIds.stream().distinct().sorted().toList());
        parameters.put("seven_days", Timestamp.from(now.minus(7, ChronoUnit.DAYS)));
        parameters.put("thirty_days", Timestamp.from(now.minus(30, ChronoUnit.DAYS)));
        parameters.put("ninety_days", Timestamp.from(now.minus(90, ChronoUnit.DAYS)));
        return parameters;
    }

    private static String senderFilters(
            SenderQuery query, Map<String, Object> parameters) {
        StringBuilder sql = new StringBuilder();
        if (!query.search().isEmpty()) {
            parameters.put(
                "search", "%" + escapeLike(query.search().toLowerCase(Locale.ROOT)) + "%");
            sql.append("""
                 AND (lower(display_name) LIKE :search ESCAPE '!'
                      OR sender_key LIKE :search ESCAPE '!'
                      OR lower(COALESCE(domain, '')) LIKE :search ESCAPE '!')
                """);
        }
        if (query.type() != SenderQuery.Type.ALL) {
            parameters.put("sender_type", query.type().name());
            sql.append(" AND sender_type = :sender_type");
        }
        if (query.risk() != SenderQuery.Risk.ALL) {
            parameters.put("sender_risk", query.risk().name());
            sql.append(" AND sender_risk = :sender_risk");
        }
        sql.append(switch (query.unread()) {
            case ALL -> "";
            case NONE -> " AND unread_count = 0";
            case SOME -> " AND unread_count > 0 AND unread_count * 2 <= total_count";
            case MAJORITY -> " AND unread_count * 2 > total_count AND unread_count < total_count";
            case ALL_UNREAD -> " AND total_count > 0 AND unread_count = total_count";
        });
        sql.append(switch (query.queue()) {
            case ALL -> "";
            case OPEN -> " AND queued = 0";
            case QUEUED -> " AND queued = 1";
        });
        sql.append(switch (query.protection()) {
            case EXCLUDE -> " AND protected_sender = 0";
            case ONLY -> " AND protected_sender = 1";
            case INCLUDE -> "";
        });
        return sql.toString();
    }

    private static String unreadFilters(
            UnreadQuery query, Map<String, Object> parameters) {
        StringBuilder sql = new StringBuilder();
        if (!query.search().isEmpty()) {
            parameters.put(
                "search", "%" + escapeLike(query.search().toLowerCase(Locale.ROOT)) + "%");
            sql.append("""
                 AND (lower(display_name) LIKE :search ESCAPE '!'
                      OR sender_key LIKE :search ESCAPE '!'
                      OR lower(COALESCE(domain, '')) LIKE :search ESCAPE '!'
                      OR lower(COALESCE(subject_header, '')) LIKE :search ESCAPE '!')
                """);
        }
        sql.append(switch (query.type()) {
            case ALL -> "";
            case ONE_TO_ONE -> " AND category = 'ONE_TO_ONE'";
            case LIST -> " AND category IN ('LIST', 'AUTOMATED')";
        });
        sql.append(switch (query.age()) {
            case ALL -> "";
            case TODAY -> " AND received_at >= :today";
            case D7 -> " AND received_at >= :seven_days";
            case D30 -> " AND received_at >= :thirty_days";
            case OLDER_30D -> " AND received_at < :thirty_days";
        });
        sql.append(switch (query.waiting()) {
            case ALL -> "";
            case WAITING_ON_ME -> " AND waiting_on_me = 1";
            case NOT_WAITING -> " AND waiting_on_me = 0";
        });
        sql.append(switch (query.thread()) {
            case ALL -> "";
            case SINGLE -> " AND thread_state = 'SINGLE'";
            case THREADED -> " AND thread_state = 'THREADED'";
        });
        if (query.recommendation() != UnreadQuery.Recommendation.ALL) {
            parameters.put("recommendation", query.recommendation().name());
            sql.append(" AND recommendation = :recommendation");
        }
        if (!query.sender().isEmpty()) {
            parameters.put("sender", query.sender());
            sql.append(" AND sender_key = :sender");
        }
        if (query.provider() == UnreadQuery.Provider.GOOGLE) {
            sql.append(" AND provider = 'google'");
        }
        return sql.toString();
    }

    private static String senderOrder(SenderQuery.Sort sort) {
        return switch (sort) {
            case VOLUME_DESC ->
                "total_count DESC, account_id ASC, sender_key ASC";
            case UNREAD_DESC ->
                "unread_count DESC, total_count DESC, account_id ASC, sender_key ASC";
            case OLDEST_FIRST ->
                "CASE WHEN oldest_received_at IS NULL THEN 1 ELSE 0 END ASC, "
                    + "oldest_received_at ASC, account_id ASC, sender_key ASC";
            case RISK_DESC ->
                "CASE sender_risk WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END ASC, "
                    + "account_id ASC, sender_key ASC";
            case RECOMMENDATION ->
                "CASE recommendation WHEN 'TRASH' THEN 0 WHEN 'ARCHIVE' THEN 1 "
                    + "WHEN 'MARK_READ' THEN 2 WHEN 'REVIEW' THEN 3 ELSE 4 END ASC, "
                    + "account_id ASC, sender_key ASC";
            case RECENT_DESC ->
                "CASE WHEN newest_received_at IS NULL THEN 1 ELSE 0 END ASC, "
                    + "newest_received_at DESC, account_id ASC, sender_key ASC";
        };
    }

    private static String unreadOrder(UnreadQuery.Sort sort) {
        return switch (sort) {
            case OLDEST_FIRST ->
                "CASE WHEN received_at IS NULL THEN 1 ELSE 0 END ASC, "
                    + "received_at ASC, account_id ASC, metadata_id ASC";
            case NEWEST_FIRST ->
                "CASE WHEN received_at IS NULL THEN 1 ELSE 0 END ASC, "
                    + "received_at DESC, account_id ASC, metadata_id DESC";
            case SENDER_ASC ->
                "sender_key ASC, CASE WHEN received_at IS NULL THEN 1 ELSE 0 END ASC, "
                    + "received_at ASC, account_id ASC, metadata_id ASC";
            case RECOMMENDATION ->
                "CASE recommendation WHEN 'REPLY_REQUIRED' THEN 0 WHEN 'REVIEW' THEN 1 "
                    + "WHEN 'KEEP' THEN 2 WHEN 'MARK_READ' THEN 3 WHEN 'ARCHIVE' THEN 4 "
                    + "ELSE 5 END ASC, account_id ASC, metadata_id ASC";
        };
    }

    public static String senderCategoryPredicate(String categoryKey) {
        return switch (categoryKey == null ? "" : categoryKey) {
            case "lists" -> "sender_type = 'LIST'";
            case "automated" -> "sender_type = 'AUTOMATED'";
            case "transactional" -> "sender_type = 'TRANSACTIONAL'";
            case "personal" -> "sender_type = 'PERSONAL'";
            case "other" -> "sender_type = 'UNKNOWN'";
            default -> "1 = 0";
        };
    }

    public static String unreadCategoryPredicate(String categoryKey) {
        return switch (categoryKey == null ? "" : categoryKey) {
            case "lists-automated" -> "category IN ('LIST', 'AUTOMATED')";
            case "older-than-30-days" -> "received_at < :thirty_days";
            case "direct-mail" ->
                "category = 'ONE_TO_ONE' AND sender_key <> '__missing__'";
            case "attachments" -> "attachment_state = 'PRESENT'";
            case "other" -> "sender_key = '__missing__'";
            default -> "1 = 0";
        };
    }

    static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public record QuerySpec(String cte, String orderBy, Map<String, Object> parameters) {
    }
}
