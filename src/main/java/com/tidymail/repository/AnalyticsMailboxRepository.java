package com.tidymail.repository;

import com.tidymail.dto.AnalyticsMailboxDashboard.RankedValue;
import com.tidymail.dto.AnalyticsMailboxDashboard.SizeMetrics;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import com.tidymail.dto.UnreadScopeCounts;
import java.sql.Array;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Provides mailbox-level analytics: per-bucket received/sent/read counts,
 * top senders/domains/recipients, size metrics, and unread scope counts.
 * <p>
 * Product functionality: analytics dashboards and drilldowns for mailbox
 * volume, rankings, and storage size.
 */
@Repository
public class AnalyticsMailboxRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsMailboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MailboxBucket> countsByBuckets(long accountId, List<DateRange> buckets) {
        requireAccount(accountId);
        if (buckets == null || buckets.isEmpty()
                || buckets.size() > com.tidymail.dto.AnalyticsQuery.MAX_BUCKETS) {
            throw new IllegalArgumentException("A bounded bucket set is required");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_id", accountId)
            .addValue("start", Timestamp.from(buckets.getFirst().startInclusive()))
            .addValue("end", Timestamp.from(buckets.getLast().endExclusive()));
        StringBuilder bucketCase = new StringBuilder("CASE");
        for (int index = 0; index < buckets.size(); index++) {
            DateRange bucket = buckets.get(index);
            parameters.addValue("start_" + index, Timestamp.from(bucket.startInclusive()))
                .addValue("end_" + index, Timestamp.from(bucket.endExclusive()));
            bucketCase.append(" WHEN received_at>=:start_").append(index)
                .append(" AND received_at<:end_").append(index)
                .append(" THEN ").append(index);
        }
        bucketCase.append(" END");
        String sent = hasLabel("labels", "SENT");
        String sql = """
            SELECT bucket_index,
                   SUM(CASE WHEN NOT (%s) THEN 1 ELSE 0 END) AS received_count,
                   SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS sent_count,
                   SUM(CASE WHEN NOT (%s) AND unread=FALSE THEN 1 ELSE 0 END) AS read_count
              FROM (
                  SELECT labels, unread, %s AS bucket_index
                    FROM message_metadata
                   WHERE account_id=:account_id
                     AND received_at>=:start AND received_at<:end
              ) facts
             WHERE bucket_index IS NOT NULL
             GROUP BY bucket_index
             ORDER BY bucket_index
            """.formatted(sent, sent, sent, bucketCase);
        LinkedHashMap<Integer, MailboxBucket> rows = jdbc.query(sql, parameters, result -> {
            LinkedHashMap<Integer, MailboxBucket> values = new LinkedHashMap<>();
            while (result.next()) {
                values.put(result.getInt("bucket_index"), new MailboxBucket(
                    result.getLong("received_count"),
                    result.getLong("sent_count"),
                    result.getLong("read_count")));
            }
            return values;
        });
        ArrayList<MailboxBucket> result = new ArrayList<>(buckets.size());
        for (int index = 0; index < buckets.size(); index++) {
            result.add(rows.getOrDefault(index, new MailboxBucket(0, 0, 0)));
        }
        return List.copyOf(result);
    }

    public List<RankedValue> topReceivedEmails(
            long accountId, DateRange range, int limit) {
        return ranking(accountId, range, limit,
            "lower(trim(from_address))", "from_address IS NOT NULL");
    }

    public List<RankedValue> topReceivedDomains(
            long accountId, DateRange range, int limit) {
        return ranking(accountId, range, limit,
            "lower(trim(from_domain))", "from_domain IS NOT NULL");
    }

    private List<RankedValue> ranking(
            long accountId, DateRange range, int limit,
            String expression, String presentPredicate) {
        MapSqlParameterSource parameters = parameters(accountId, range)
            .addValue("limit", requireLimit(limit));
        String sql = """
            SELECT %s AS rank_label, COUNT(*) AS rank_count
              FROM message_metadata
             WHERE account_id=:account_id
               AND received_at>=:start AND received_at<:end
               AND NOT (%s) AND %s AND trim(%s)<>''
             GROUP BY %s
             ORDER BY rank_count DESC, rank_label ASC
             LIMIT :limit
            """.formatted(
                expression, hasLabel("labels", "SENT"), presentPredicate,
                expression.contains("domain") ? "from_domain" : "from_address", expression);
        return jdbc.query(sql, parameters,
            (row, index) -> new RankedValue(
                row.getString("rank_label"), row.getLong("rank_count")));
    }

    public List<RankedValue> topSentEmails(
            long accountId, DateRange range, int limit) {
        int boundedLimit = requireLimit(limit);
        MapSqlParameterSource parameters = parameters(accountId, range);
        HashMap<String, Long> counts = new HashMap<>();
        jdbc.query("""
            SELECT to_values
              FROM message_metadata
             WHERE account_id=:account_id
               AND received_at>=:start AND received_at<:end
               AND %s
            """.formatted(hasLabel("labels", "SENT")), parameters, result -> {
                Array values = result.getArray("to_values");
                if (values == null) return;
                Object raw = values.getArray();
                if (!(raw instanceof Object[] recipients)) return;
                for (Object recipient : recipients) {
                    if (recipient == null) continue;
                    String normalized = recipient.toString().trim().toLowerCase(Locale.ROOT);
                    if (!normalized.isEmpty()) counts.merge(normalized, 1L, Long::sum);
                }
            });
        return counts.entrySet().stream()
            .sorted(Comparator.<java.util.Map.Entry<String, Long>>comparingLong(
                    java.util.Map.Entry::getValue)
                .reversed()
                .thenComparing(java.util.Map.Entry::getKey))
            .limit(boundedLimit)
            .map(entry -> new RankedValue(entry.getKey(), entry.getValue()))
            .toList();
    }

    public Optional<SizeMetrics> sizeMetrics(long accountId, DateRange range) {
        MapSqlParameterSource parameters = parameters(accountId, range)
            .addValue("ten_mb", 10L * 1024 * 1024);
        String inbox = hasLabel("labels", "INBOX");
        String sent = hasLabel("labels", "SENT");
        return jdbc.queryForObject("""
            SELECT
              SUM(CASE WHEN %s AND unread=TRUE
                       THEN COALESCE(size_estimate, 0) ELSE 0 END) AS unread_inbox_bytes,
              SUM(CASE WHEN %s AND size_estimate>:ten_mb THEN 1 ELSE 0 END)
                  AS inbox_over_ten_mb,
              SUM(CASE WHEN %s AND size_estimate>:ten_mb THEN 1 ELSE 0 END)
                  AS sent_over_ten_mb,
              SUM(CASE WHEN (%s OR %s) AND size_estimate IS NULL THEN 1 ELSE 0 END)
                  AS missing_size_count
              FROM message_metadata
             WHERE account_id=:account_id
               AND received_at>=:start AND received_at<:end
            """.formatted(inbox, inbox, sent, inbox, sent),
            parameters, (row, index) -> row.getLong("missing_size_count") > 0
                ? Optional.empty()
                : Optional.of(new SizeMetrics(
                    row.getLong("unread_inbox_bytes"),
                    row.getLong("inbox_over_ten_mb"),
                    row.getLong("sent_over_ten_mb"),
                    null)));
    }

    public UnreadScopeCounts unreadScopeCounts(long accountId) {
        requireAccount(accountId);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_ids", List.of(accountId))
            .addValue("account_count", 1);
        return jdbc.queryForObject(
            DecisionSurfaceSql.unreadScopeCounts(),
            parameters,
            (row, index) -> row.getBoolean("available")
                ? UnreadScopeCounts.available(
                    row.getLong("inbox_unread_count"),
                    row.getLong("all_mail_unread_count"))
                : UnreadScopeCounts.pending());
    }

    private String hasLabel(String column, String label) {
        return "'" + label + "'=ANY(" + column + ")";
    }

    private static MapSqlParameterSource parameters(long accountId, DateRange range) {
        requireAccount(accountId);
        return new MapSqlParameterSource()
            .addValue("account_id", accountId)
            .addValue("start", Timestamp.from(range.startInclusive()))
            .addValue("end", Timestamp.from(range.endExclusive()));
    }

    private static void requireAccount(long accountId) {
        if (accountId <= 0) throw new IllegalArgumentException("Active account is required");
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > 10) {
            throw new IllegalArgumentException("Analytics rankings are limited to 10 rows");
        }
        return limit;
    }

    public record MailboxBucket(long received, long sent, long read) {
        public MailboxBucket {
            if (received < 0 || sent < 0 || read < 0) {
                throw new IllegalArgumentException("Mailbox counts cannot be negative");
            }
        }
    }
}
