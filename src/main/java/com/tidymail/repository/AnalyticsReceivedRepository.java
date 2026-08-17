package com.tidymail.repository;

import com.tidymail.dto.AnalyticsSourcePage.SourceRow;
import com.tidymail.dto.AnalyticsQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsReceivedRepository {
    private static final String SAFE_COLUMNS = """
        CASE WHEN a.email IS NULL OR trim(a.email) = '' THEN 'Gmail account'
             ELSE a.email || ' (Gmail)' END AS account_label,
        m.received_at,
        COALESCE(NULLIF(trim(m.from_display_name), ''), 'Unknown sender') AS sender_display,
        NULLIF(lower(trim(m.from_address)), '') AS sender_address,
        NULLIF(lower(trim(m.from_domain)), '') AS sender_domain
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsReceivedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReceivedFact receivedFact(
            Collection<Long> authorizedAccountIds,
            Instant startInclusive,
            Instant endExclusive) {
        return new ReceivedFact(
            AnalyticsReceivedSql.received(
                authorizedAccountIds, startInclusive, endExclusive));
    }

    public List<Long> countByBuckets(
            Collection<Long> authorizedAccountIds,
            List<AnalyticsQuery.DateRange> buckets) {
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

        ReceivedFactPredicate predicate = AnalyticsReceivedSql.received(
            authorizedAccountIds,
            buckets.getFirst().startInclusive(),
            buckets.getLast().endExclusive());
        MapSqlParameterSource parameters = new MapSqlParameterSource(predicate.parameters());
        StringBuilder bucketCase = new StringBuilder("CASE");
        for (int index = 0; index < buckets.size(); index++) {
            AnalyticsQuery.DateRange bucket = buckets.get(index);
            parameters
                .addValue("bucket_start_" + index, Timestamp.from(bucket.startInclusive()))
                .addValue("bucket_end_" + index, Timestamp.from(bucket.endExclusive()));
            bucketCase.append(" WHEN m.received_at >= :bucket_start_")
                .append(index)
                .append(" AND m.received_at < :bucket_end_")
                .append(index)
                .append(" THEN ")
                .append(index);
        }
        bucketCase.append(" END");

        String sql = "SELECT bucket_index, COUNT(DISTINCT message_id) AS bucket_count "
            + "FROM (SELECT m.id AS message_id, " + bucketCase + " AS bucket_index "
            + AnalyticsReceivedSql.BASE_FROM + ") bucketed "
            + "WHERE bucket_index IS NOT NULL GROUP BY bucket_index ORDER BY bucket_index";
        Map<Integer, Long> counts = jdbc.query(sql, parameters, result -> {
            LinkedHashMap<Integer, Long> rows = new LinkedHashMap<>();
            while (result.next()) {
                rows.put(result.getInt("bucket_index"), result.getLong("bucket_count"));
            }
            return rows;
        });
        List<Long> result = new ArrayList<>(buckets.size());
        for (int index = 0; index < buckets.size(); index++) {
            result.add(counts.getOrDefault(index, 0L));
        }
        return List.copyOf(result);
    }

    public Map<Long, Instant> earliestReceivedByAccount(
            Collection<Long> authorizedAccountIds) {
        List<Long> accountIds = authorized(authorizedAccountIds);
        return jdbc.query("""
            SELECT account_id, MIN(received_at) AS earliest_received
            FROM message_metadata
            WHERE account_id IN (:account_ids) AND received_at IS NOT NULL
            GROUP BY account_id
            ORDER BY account_id
            """, Map.of("account_ids", accountIds), result -> {
                LinkedHashMap<Long, Instant> rows = new LinkedHashMap<>();
                while (result.next()) {
                    rows.put(result.getLong("account_id"),
                        result.getTimestamp("earliest_received").toInstant());
                }
                return Map.copyOf(rows);
            });
    }

    public long nullReceivedCount(Collection<Long> authorizedAccountIds) {
        List<Long> accountIds = authorized(authorizedAccountIds);
        Number count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM message_metadata
            WHERE account_id IN (:account_ids) AND received_at IS NULL
            """, Map.of("account_ids", accountIds), Number.class);
        return count == null ? 0 : count.longValue();
    }

    private static List<Long> authorized(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        return accountIds.stream().distinct().sorted().toList();
    }

    public final class ReceivedFact {
        private final ReceivedFactPredicate predicate;

        private ReceivedFact(ReceivedFactPredicate predicate) {
            this.predicate = predicate;
        }

        public long count() {
            Number count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT m.id) " + AnalyticsReceivedSql.BASE_FROM,
                predicate.parameters(), Number.class);
            return count == null ? 0 : count.longValue();
        }

        public List<SourceRow> page(int pageSize, long offset) {
            if (pageSize < 1 || pageSize > 100 || offset < 0) {
                throw new IllegalArgumentException("Bounded source page is required");
            }
            MapSqlParameterSource parameters =
                new MapSqlParameterSource(predicate.parameters())
                    .addValue("limit", pageSize)
                    .addValue("offset", offset);
            return jdbc.query(
                "SELECT " + SAFE_COLUMNS + " " + AnalyticsReceivedSql.BASE_FROM
                    + " ORDER BY m.received_at ASC, m.id ASC LIMIT :limit OFFSET :offset",
                parameters, SOURCE_ROW_MAPPER);
        }

        public Stream<SourceRow> stream() {
            return jdbc.queryForStream(
                "SELECT " + SAFE_COLUMNS + " " + AnalyticsReceivedSql.BASE_FROM
                    + " ORDER BY m.received_at ASC, m.id ASC",
                predicate.parameters(), SOURCE_ROW_MAPPER);
        }

        public Instant startInclusive() {
            return predicate.startInclusive();
        }

        public Instant endExclusive() {
            return predicate.endExclusive();
        }
    }

    private static final RowMapper<SourceRow> SOURCE_ROW_MAPPER =
        (result, rowNumber) -> new SourceRow(
            result.getString("account_label"),
            instant(result, "received_at"),
            result.getString("sender_display"),
            result.getString("sender_address"),
            result.getString("sender_domain"),
            null);

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
