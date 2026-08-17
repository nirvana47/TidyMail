package com.tidymail.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds shared raw-SQL fragments and predicates for received-message
 * analytics queries.
 * <p>
 * Product functionality: analytics dashboards and drilldowns over received
 * messages.
 */
public final class AnalyticsReceivedSql {
    static final String BASE_FROM = """
        FROM message_metadata m
        JOIN user_accounts a ON a.id = m.account_id
        WHERE m.account_id IN (:account_ids)
          AND m.received_at >= :start
          AND m.received_at < :end
        """;

    private AnalyticsReceivedSql() {
    }

    static ReceivedFactPredicate received(
            Collection<Long> authorizedAccountIds,
            Instant startInclusive,
            Instant endExclusive) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()
                || authorizedAccountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        if (startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("A non-empty received interval is required");
        }
        return new ReceivedFactPredicate(
            authorizedAccountIds.stream().distinct().sorted().toList(),
            startInclusive,
            endExclusive);
    }
}

record ReceivedFactPredicate(
    List<Long> accountIds,
    Instant startInclusive,
    Instant endExclusive
) {
    ReceivedFactPredicate {
        accountIds = List.copyOf(accountIds);
    }

    Map<String, Object> parameters() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("account_ids", accountIds);
        parameters.put("start", Timestamp.from(startInclusive));
        parameters.put("end", Timestamp.from(endExclusive));
        return Map.copyOf(parameters);
    }
}
