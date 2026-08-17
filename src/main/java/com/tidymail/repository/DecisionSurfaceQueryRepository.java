package com.tidymail.repository;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.SelectionMode;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.service.DecisionRuleSet.SenderRecommendation;
import com.tidymail.service.DecisionRuleSet.SenderRisk;
import com.tidymail.service.DecisionRuleSet.SenderType;
import com.tidymail.service.DecisionRuleSet.ThreadState;
import com.tidymail.service.DecisionRuleSet.UnreadCategory;
import com.tidymail.service.DecisionRuleSet.UnreadRecommendation;
import com.tidymail.service.DecisionRowKey.UnreadSenderLocator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DecisionSurfaceQueryRepository {
    private static final String SENDER_COLUMNS = """
        account_id, account_email, sender_key, display_name, domain, total_count, unread_count,
        estimated_size, oldest_received_at, newest_received_at, list_signal, automated_signal,
        return_path_signal, message_id_signal, thread_signal, protected_sender,
        queued, sender_type, manual_sender_type, sender_risk, recommendation
        """;
    private static final String UNREAD_COLUMNS = """
        metadata_id, account_id, sender_key, display_name, domain, subject_header,
        received_at, size_estimate, has_attachments, list_signal, automated_signal, references_signal,
        in_reply_to_signal, authorized_thread_count, protected_sender,
        manual_sender_type, category, thread_state, waiting_on_me, recommendation, provider,
        account_email, provider_message_id, provider_thread_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public DecisionSurfaceQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public QueryResult<SenderFact> senders(
            Collection<Long> authorizedAccountIds, SenderQuery query, Instant now) {
        return senders(authorizedAccountIds, query, now, null);
    }

    public QueryResult<SenderFact> senders(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.sender(authorizedAccountIds, query, now, analyticsScope);
        MapSqlParameterSource parameters = parameters(spec)
            .addValue("limit", SenderQuery.PAGE_SIZE)
            .addValue("offset", query.offset());
        long count = number(spec.cte()
            + " SELECT COUNT(*) FROM filtered_facts", parameters);
        DecisionPage.Metrics metrics = metrics(spec.cte() + """
             SELECT COUNT(*) AS total_rows,
                    COALESCE(SUM(total_count), 0) AS primary_count,
                    COALESCE(SUM(unread_count), 0) AS secondary_count,
                    COALESCE(SUM(protected_sender), 0) AS protected_count
             FROM filtered_facts
            """, parameters);
        List<SenderFact> rows = jdbc.query(spec.cte()
            + " SELECT " + SENDER_COLUMNS + " FROM filtered_facts ORDER BY "
            + spec.orderBy() + " LIMIT :limit OFFSET :offset", parameters, SENDER_MAPPER);
        return new QueryResult<>(rows, count, metrics);
    }

    public QueryResult<UnreadFact> unread(
            Collection<Long> authorizedAccountIds, UnreadQuery query, Instant now) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.unread(authorizedAccountIds, query, now);
        MapSqlParameterSource parameters = parameters(spec)
            .addValue("limit", UnreadQuery.PAGE_SIZE)
            .addValue("offset", query.offset());
        long count = number(spec.cte()
            + " SELECT COUNT(*) FROM filtered_facts", parameters);
        DecisionPage.Metrics metrics = metrics(spec.cte() + """
             SELECT COUNT(*) AS total_rows,
                    COALESCE(SUM(CASE WHEN category = 'ONE_TO_ONE' THEN 1 ELSE 0 END), 0)
                        AS primary_count,
                    COALESCE(SUM(CASE WHEN category IN ('LIST', 'AUTOMATED')
                                      THEN 1 ELSE 0 END), 0) AS secondary_count,
                    COALESCE(SUM(CASE WHEN received_at < :thirty_days THEN 1 ELSE 0 END), 0)
                        AS protected_count
             FROM filtered_facts
            """, parameters);
        List<UnreadFact> rows = jdbc.query(spec.cte()
            + " SELECT " + UNREAD_COLUMNS + " FROM filtered_facts ORDER BY "
            + spec.orderBy() + " LIMIT :limit OFFSET :offset", parameters, UNREAD_MAPPER);
        return new QueryResult<>(rows, count, metrics);
    }

    public UnreadScopeCounts unreadScopeCounts(Collection<Long> authorizedAccountIds) {
        List<Long> accountIds = requireAccountIds(authorizedAccountIds);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_ids", accountIds)
            .addValue("account_count", accountIds.size());
        return jdbc.queryForObject(
            DecisionSurfaceSql.unreadScopeCounts(),
            parameters,
            (result, rowNumber) -> result.getBoolean("available")
                ? UnreadScopeCounts.available(
                    result.getLong("inbox_unread_count"),
                    result.getLong("all_mail_unread_count"))
                : UnreadScopeCounts.pending());
    }

    public long countSenders(
            Collection<Long> authorizedAccountIds, SenderQuery query, Instant now) {
        return countSenders(authorizedAccountIds, query, now, null);
    }

    public long countSenders(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.sender(authorizedAccountIds, query, now, analyticsScope);
        return number(
            spec.cte() + " SELECT COUNT(*) FROM filtered_facts",
            parameters(spec));
    }

    public boolean analyticsSenderExists(AnalyticsSenderScope scope) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_id", scope.accountId())
            .addValue("sender", scope.senderKey())
            .addValue("start", Timestamp.from(scope.startInclusive()))
            .addValue("end", Timestamp.from(scope.endExclusive()));
        return number("""
            SELECT COUNT(*)
              FROM message_metadata metadata
             WHERE metadata.account_id=:account_id
               AND COALESCE(NULLIF(lower(trim(metadata.from_address)), ''), '__missing__')
                   =:sender
               AND metadata.received_at>=:start
               AND metadata.received_at<:end
            """, parameters) > 0;
    }

    public Stream<SenderFact> streamSenders(
            Collection<Long> authorizedAccountIds, SenderQuery query, Instant now) {
        return streamSenders(authorizedAccountIds, query, now, null);
    }

    public Stream<SenderFact> streamSenders(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.sender(authorizedAccountIds, query, now, analyticsScope);
        return jdbc.queryForStream(
            spec.cte() + " SELECT " + SENDER_COLUMNS
                + " FROM filtered_facts ORDER BY " + spec.orderBy(),
            parameters(spec), SENDER_MAPPER);
    }

    public List<SenderCategorySummary> senderCategorySummaries(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.sender(
                authorizedAccountIds, query.resetPage(), now, analyticsScope);
        return jdbc.query(spec.cte() + """
             SELECT sender_type,
                    COUNT(*) AS sender_count,
                    CASE WHEN COUNT(estimated_size) = COUNT(*)
                         THEN SUM(estimated_size) ELSE NULL END AS estimated_size
             FROM filtered_facts
             GROUP BY sender_type
             ORDER BY sender_type
            """, parameters(spec), (result, rowNumber) -> new SenderCategorySummary(
                categoryKey(SenderType.valueOf(result.getString("sender_type"))),
                result.getLong("sender_count"),
                nullableLong(result, "estimated_size")));
    }

    public List<SenderFact> senderCategoryRows(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            String categoryKey,
            long offset,
            int limit,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        if (offset < 0 || limit < 0 || limit > 1_000) {
            throw new IllegalArgumentException("Category page bounds are invalid");
        }
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.sender(
                authorizedAccountIds, query.resetPage(), now, analyticsScope);
        MapSqlParameterSource parameters = parameters(spec)
            .addValue("category_offset", offset)
            .addValue("category_limit", limit);
        return jdbc.query(spec.cte()
            + " SELECT " + SENDER_COLUMNS + " FROM filtered_facts WHERE "
            + DecisionSurfaceSql.senderCategoryPredicate(categoryKey)
            + " ORDER BY total_count DESC, account_id ASC, sender_key ASC"
            + " LIMIT :category_limit OFFSET :category_offset",
            parameters, SENDER_MAPPER);
    }

    public long countUnread(
            Collection<Long> authorizedAccountIds, UnreadQuery query, Instant now) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.unread(authorizedAccountIds, query, now);
        return number(
            spec.cte() + " SELECT COUNT(*) FROM filtered_facts",
            parameters(spec));
    }

    public Stream<UnreadFact> streamUnread(
            Collection<Long> authorizedAccountIds, UnreadQuery query, Instant now) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.unread(authorizedAccountIds, query, now);
        return jdbc.queryForStream(
            spec.cte() + " SELECT " + UNREAD_COLUMNS
                + " FROM filtered_facts ORDER BY " + spec.orderBy(),
            parameters(spec), UNREAD_MAPPER);
    }

    public MaterializationResult materializeSenders(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            SelectionMode mode,
            List<SelectionLocator> locators,
            boolean includeProtected,
            Instant now) {
        return materializeSenders(
            authorizedAccountIds, query, mode, locators, includeProtected, now, null);
    }

    public MaterializationResult materializeSenders(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            SelectionMode mode,
            List<SelectionLocator> locators,
            boolean includeProtected,
            Instant now,
            AnalyticsSenderScope analyticsScope) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.sender(authorizedAccountIds, query, now, analyticsScope);
        MapSqlParameterSource parameters = parameters(spec)
            .addValue("include_protected", includeProtected ? 1 : 0);
        String selected = selectedTargets(
            spec, mode, locators, parameters, "account_id, sender_key, sender_risk, protected_sender",
            "sender_key", SenderQuery.PAGE_SIZE, query.offset());
        String materialized = spec.cte() + selected + """
            , materialized_rows AS (
                SELECT st.account_id, b.metadata_id, st.sender_key,
                       st.sender_key AS target_key,
                       st.protected_sender,
                       CASE WHEN st.sender_risk = 'HIGH' THEN 1 ELSE 0 END AS high_risk,
                       b.provider_message_id
                FROM selected_targets st
                JOIN base_rows b ON b.account_id = st.account_id
                    AND b.sender_key = st.sender_key
                WHERE b.provider_message_id IS NOT NULL
            )
            """;
        return materialization(
            materialized, parameters, "target_key", mode, locators.size());
    }

    public MaterializationResult materializeUnread(
            Collection<Long> authorizedAccountIds,
            UnreadQuery query,
            SelectionMode mode,
            List<SelectionLocator> locators,
            boolean includeProtected,
            Instant now) {
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.unread(authorizedAccountIds, query, now);
        MapSqlParameterSource parameters = parameters(spec)
            .addValue("include_protected", includeProtected ? 1 : 0);
        List<SelectionLocator> distinctLocators =
            locators == null ? List.of() : locators.stream().distinct().toList();
        String selected = selectedTargets(
            spec, mode, distinctLocators, parameters,
            "metadata_id, account_id, sender_key, protected_sender, category, "
                + "provider_message_id",
            "metadata_id", UnreadQuery.PAGE_SIZE, query.offset());
        String materialized = spec.cte() + selected + """
            , materialized_rows AS (
                SELECT account_id, metadata_id, sender_key,
                       CAST(metadata_id AS VARCHAR) AS target_key,
                       protected_sender,
                       CASE WHEN protected_sender = 1 OR category = 'ONE_TO_ONE'
                            THEN 1 ELSE 0 END AS high_risk,
                       provider_message_id
                FROM selected_targets
                WHERE provider_message_id IS NOT NULL
            )
            """;
        return materialization(
            materialized, parameters, "metadata_id", mode, distinctLocators.size());
    }

    public MaterializationResult materializeUnreadSenders(
            Collection<Long> authorizedAccountIds,
            UnreadQuery query,
            List<UnreadSenderLocator> locators,
            Instant now) {
        if (locators == null || locators.isEmpty()) {
            throw new IllegalArgumentException(
                "Unread sender materialization requires locators");
        }
        DecisionSurfaceSql.QuerySpec spec =
            DecisionSurfaceSql.unread(authorizedAccountIds, query, now);
        MapSqlParameterSource parameters = parameters(spec)
            .addValue("include_protected", 0);
        StringBuilder selected = new StringBuilder("""
            , selected_targets AS (
                SELECT metadata_id, account_id, sender_key, protected_sender,
                       category, provider_message_id
                FROM filtered_facts
                WHERE
            """);
        for (int index = 0; index < locators.size(); index++) {
            if (index > 0) selected.append(" OR ");
            UnreadSenderLocator locator = locators.get(index);
            String accountParameter = "unread_sender_account_" + index;
            String senderParameter = "unread_sender_key_" + index;
            selected.append("(account_id = :").append(accountParameter)
                .append(" AND sender_key = :").append(senderParameter)
                .append(" AND ")
                .append(DecisionSurfaceSql.unreadCategoryPredicate(
                    locator.categoryKey()))
                .append(')');
            parameters.addValue(accountParameter, locator.accountId());
            parameters.addValue(senderParameter, locator.senderKey());
        }
        selected.append(")");
        String materialized = spec.cte() + selected + """
            , materialized_rows AS (
                SELECT account_id, metadata_id, sender_key,
                       sender_key AS target_key,
                       protected_sender,
                       CASE WHEN protected_sender = 1 OR category = 'ONE_TO_ONE'
                            THEN 1 ELSE 0 END AS high_risk,
                       provider_message_id
                FROM selected_targets
                WHERE provider_message_id IS NOT NULL
            )
            """;
        int senderCount = Math.toIntExact(locators.stream()
            .map(locator -> locator.accountId() + "\n" + locator.senderKey())
            .distinct()
            .count());
        return materialization(
            materialized, parameters, "sender_key",
            SelectionMode.INDIVIDUAL, senderCount);
    }

    private MaterializationResult materialization(
            String materialized,
            MapSqlParameterSource parameters,
            String targetColumn,
            SelectionMode mode,
            int locatorCount) {
        String included = "(protected_sender = 0 OR :include_protected = 1)";
        List<AccountCounts> counts = jdbc.query(materialized + """
            SELECT account_id,
                   COUNT(DISTINCT %s) AS total_target_count,
                   COUNT(DISTINCT CASE WHEN %s THEN %s END) AS included_target_count,
                   COUNT(DISTINCT CASE WHEN %s THEN provider_message_id END)
                       AS included_message_count,
                   COUNT(DISTINCT CASE WHEN protected_sender = 1 THEN sender_key END)
                       AS protected_sender_count,
                   COUNT(DISTINCT CASE WHEN protected_sender = 1 THEN provider_message_id END)
                       AS protected_message_count,
                   COUNT(DISTINCT CASE WHEN %s AND high_risk = 1
                                       THEN provider_message_id END) AS high_risk_count
            FROM materialized_rows
            GROUP BY account_id
            ORDER BY account_id
            """.formatted(targetColumn, included, targetColumn, included, included),
            parameters,
            (result, rowNumber) -> new AccountCounts(
                result.getLong("account_id"),
                result.getLong("total_target_count"),
                result.getLong("included_target_count"),
                result.getLong("included_message_count"),
                result.getLong("protected_sender_count"),
                result.getLong("protected_message_count"),
                result.getLong("high_risk_count")));

        parameters.addValue("guard_limit", 1001);
        List<GuardedId> guarded = jdbc.query(materialized + """
            , included_ids AS (
                SELECT DISTINCT account_id, provider_message_id
                FROM materialized_rows
                WHERE %s
            ),
            ranked_ids AS (
                SELECT account_id, provider_message_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY account_id ORDER BY provider_message_id
                       ) AS row_number
                FROM included_ids
            )
            SELECT account_id, provider_message_id
            FROM ranked_ids
            WHERE row_number <= :guard_limit
            ORDER BY account_id, provider_message_id
            """.formatted(included), parameters,
            (result, rowNumber) ->
                new GuardedId(result.getLong("account_id"), result.getString("provider_message_id")));

        Map<Long, List<String>> idsByAccount = new LinkedHashMap<>();
        guarded.forEach(row -> idsByAccount.computeIfAbsent(
            row.accountId(), ignored -> new java.util.ArrayList<>()).add(row.messageId()));
        List<AccountMaterialization> accounts = counts.stream()
            .map(count -> new AccountMaterialization(
                count.accountId(),
                count.totalTargetCount(),
                count.includedTargetCount(),
                count.includedMessageCount(),
                count.protectedSenderCount(),
                count.protectedMessageCount(),
                count.highRiskCount(),
                idsByAccount.getOrDefault(count.accountId(), List.of())))
            .toList();
        long matchedTargets = counts.stream().mapToLong(AccountCounts::totalTargetCount).sum();
        if (mode == SelectionMode.INDIVIDUAL && matchedTargets != locatorCount) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "One or more selected rows are stale, foreign, or no longer match the filters");
        }
        return new MaterializationResult(accounts, matchedTargets);
    }

    private static String selectedTargets(
            DecisionSurfaceSql.QuerySpec spec,
            SelectionMode mode,
            List<SelectionLocator> locators,
            MapSqlParameterSource parameters,
            String columns,
            String identityColumn,
            int pageSize,
            long offset) {
        String select = ", selected_targets AS (SELECT " + columns + " FROM filtered_facts";
        if (mode == SelectionMode.INDIVIDUAL) {
            if (locators == null || locators.isEmpty()) {
                throw new IllegalArgumentException("Individual selection requires locators");
            }
            StringBuilder predicate = new StringBuilder(" WHERE ");
            for (int index = 0; index < locators.size(); index++) {
                if (index > 0) predicate.append(" OR ");
                SelectionLocator locator = locators.get(index);
                String accountParameter = "selection_account_" + index;
                String targetParameter = "selection_target_" + index;
                predicate.append("(account_id = :").append(accountParameter)
                    .append(" AND ").append(identityColumn).append(" = :")
                    .append(targetParameter).append(')');
                parameters.addValue(accountParameter, locator.accountId());
                parameters.addValue(targetParameter,
                    "metadata_id".equals(identityColumn)
                        ? Long.parseLong(locator.target()) : locator.target());
            }
            return select + predicate + ")";
        }
        if (locators != null && !locators.isEmpty()) {
            throw new IllegalArgumentException("Only individual selection accepts locators");
        }
        if (mode == SelectionMode.VISIBLE_PAGE) {
            parameters.addValue("selection_limit", pageSize);
            parameters.addValue("selection_offset", offset);
            return select + " ORDER BY " + spec.orderBy()
                + " LIMIT :selection_limit OFFSET :selection_offset)";
        }
        return select + ")";
    }

    private long number(String sql, MapSqlParameterSource parameters) {
        Number value = jdbc.queryForObject(sql, parameters, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private DecisionPage.Metrics metrics(String sql, MapSqlParameterSource parameters) {
        return jdbc.queryForObject(sql, parameters, (result, rowNumber) ->
            new DecisionPage.Metrics(
                result.getLong("total_rows"),
                result.getLong("primary_count"),
                result.getLong("secondary_count"),
                result.getLong("protected_count")));
    }

    private static MapSqlParameterSource parameters(DecisionSurfaceSql.QuerySpec spec) {
        return new MapSqlParameterSource(spec.parameters());
    }

    private static List<Long> requireAccountIds(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        return accountIds.stream().distinct().sorted().toList();
    }

    private static final RowMapper<SenderFact> SENDER_MAPPER = (result, rowNumber) ->
        new SenderFact(
            result.getLong("account_id"),
            result.getString("account_email"),
            result.getString("sender_key"),
            result.getString("display_name"),
            result.getString("domain"),
            result.getLong("total_count"),
            result.getLong("unread_count"),
            nullableLong(result, "estimated_size"),
            instant(result, "oldest_received_at"),
            instant(result, "newest_received_at"),
            flag(result, "list_signal"),
            flag(result, "automated_signal"),
            flag(result, "return_path_signal"),
            flag(result, "message_id_signal"),
            flag(result, "thread_signal"),
            flag(result, "protected_sender"),
            flag(result, "queued"),
            SenderType.valueOf(result.getString("sender_type")),
            nullableSenderType(result, "manual_sender_type"),
            SenderRisk.valueOf(result.getString("sender_risk")),
            SenderRecommendation.valueOf(result.getString("recommendation")));

    private static final RowMapper<UnreadFact> UNREAD_MAPPER = (result, rowNumber) ->
        new UnreadFact(
            result.getLong("metadata_id"),
            result.getLong("account_id"),
            result.getString("sender_key"),
            result.getString("display_name"),
            result.getString("domain"),
            result.getString("subject_header"),
            instant(result, "received_at"),
            nullableLong(result, "size_estimate"),
            flag(result, "has_attachments"),
            flag(result, "list_signal"),
            flag(result, "automated_signal"),
            flag(result, "references_signal"),
            flag(result, "in_reply_to_signal"),
            result.getLong("authorized_thread_count"),
            flag(result, "protected_sender"),
            nullableSenderType(result, "manual_sender_type"),
            UnreadCategory.valueOf(result.getString("category")),
            ThreadState.valueOf(result.getString("thread_state")),
            flag(result, "waiting_on_me"),
            UnreadRecommendation.valueOf(result.getString("recommendation")),
            result.getString("provider"),
            result.getString("account_email"),
            result.getString("provider_message_id"),
            result.getString("provider_thread_id"));

    private static boolean flag(ResultSet result, String column) throws SQLException {
        return result.getBoolean(column);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static SenderType nullableSenderType(ResultSet result, String column)
            throws SQLException {
        String value = result.getString(column);
        return value == null ? null : SenderType.valueOf(value);
    }

    public record QueryResult<T>(
        List<T> rows,
        long count,
        DecisionPage.Metrics metrics
    ) {
        public QueryResult {
            rows = List.copyOf(rows);
            if (count < 0) throw new IllegalArgumentException("Count cannot be negative");
        }
    }

    public record SenderCategorySummary(
        String categoryKey,
        long senderCount,
        Long estimatedSize
    ) {
        public SenderCategorySummary {
            if (categoryKey == null || categoryKey.isBlank()
                    || senderCount < 0 || estimatedSize != null && estimatedSize < 0) {
                throw new IllegalArgumentException("Valid category summary is required");
            }
        }
    }

    public record SenderFact(
        long accountId,
        String accountLabel,
        String senderKey,
        String displayName,
        String domain,
        long totalCount,
        long unreadCount,
        Long estimatedSize,
        Instant oldestReceivedAt,
        Instant newestReceivedAt,
        boolean listSignal,
        boolean automatedSignal,
        boolean returnPathSignal,
        boolean messageIdSignal,
        boolean threadSignal,
        boolean protectedSender,
        boolean queued,
        SenderType type,
        SenderType manualType,
        SenderRisk risk,
        SenderRecommendation recommendation
    ) {
        @Override
        public boolean listSignal() {
            return manualType == null ? listSignal : manualType == SenderType.LIST;
        }

        @Override
        public boolean automatedSignal() {
            return manualType == null
                ? automatedSignal : manualType == SenderType.AUTOMATED;
        }

        @Override
        public boolean returnPathSignal() {
            return manualType == null
                ? returnPathSignal : manualType == SenderType.TRANSACTIONAL;
        }

        @Override
        public boolean messageIdSignal() {
            return manualType == null
                ? messageIdSignal : manualType == SenderType.PERSONAL;
        }

        @Override
        public boolean threadSignal() {
            return manualType == null
                ? threadSignal : manualType == SenderType.PERSONAL;
        }
    }

    public record UnreadFact(
        long metadataId,
        long accountId,
        String senderKey,
        String displayName,
        String domain,
        String subject,
        Instant receivedAt,
        Long sizeEstimate,
        boolean hasAttachments,
        boolean listSignal,
        boolean automatedSignal,
        boolean referencesSignal,
        boolean inReplyToSignal,
        long authorizedThreadCount,
        boolean protectedSender,
        SenderType manualType,
        UnreadCategory category,
        ThreadState threadState,
        boolean waitingOnMe,
        UnreadRecommendation recommendation,
        String provider,
        String accountEmail,
        String providerMessageId,
        String providerThreadId
    ) {
        @Override
        public boolean listSignal() {
            return manualType == null ? listSignal : manualType == SenderType.LIST;
        }

        @Override
        public boolean automatedSignal() {
            return manualType == null
                ? automatedSignal : manualType == SenderType.AUTOMATED;
        }
    }

    public record SelectionLocator(long accountId, String target) {
        public SelectionLocator {
            if (accountId <= 0 || target == null || target.isBlank()) {
                throw new IllegalArgumentException("Valid selection locator is required");
            }
        }
    }

    public record MaterializationResult(
        List<AccountMaterialization> accounts,
        long matchedTargetCount
    ) {
        public MaterializationResult {
            accounts = List.copyOf(accounts);
        }
    }

    public record AccountMaterialization(
        long accountId,
        long totalTargetCount,
        long includedTargetCount,
        long exactMessageCount,
        long protectedSenderCount,
        long protectedMessageCount,
        long highRiskCount,
        List<String> messageIds
    ) {
        public AccountMaterialization {
            messageIds = List.copyOf(messageIds);
            if (accountId <= 0 || totalTargetCount < 0 || includedTargetCount < 0
                    || exactMessageCount < 0 || protectedSenderCount < 0
                    || protectedMessageCount < 0 || highRiskCount < 0) {
                throw new IllegalArgumentException("Materialization facts must be non-negative");
            }
        }

        @Override
        public List<String> messageIds() {
            return List.copyOf(messageIds);
        }
    }

    private record AccountCounts(
        long accountId,
        long totalTargetCount,
        long includedTargetCount,
        long includedMessageCount,
        long protectedSenderCount,
        long protectedMessageCount,
        long highRiskCount
    ) {
    }

    private record GuardedId(long accountId, String messageId) {
    }

    private static String categoryKey(SenderType type) {
        return switch (type) {
            case LIST -> "lists";
            case AUTOMATED -> "automated";
            case TRANSACTIONAL -> "transactional";
            case PERSONAL -> "personal";
            case UNKNOWN -> "other";
        };
    }
}
