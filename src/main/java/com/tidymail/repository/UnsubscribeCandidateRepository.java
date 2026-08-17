package com.tidymail.repository;

import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.mail.AttachmentState;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Queries unsubscribe candidates and sender pages, including per-sender
 * message/email facts, outcomes, and cleanup tracking.
 * <p>
 * Product functionality: the unsubscribe flow (candidate discovery, sender
 * pages, and email drilldowns).
 */
@Repository
public class UnsubscribeCandidateRepository {
    private static final String IDENTITY_FILTER = "/* page_identity_filter */";
    private static final String CANDIDATE_SQL = """
        WITH normalized_metadata AS (
            SELECT m.*,
                   CASE WHEN CARDINALITY(m.list_id_values) > 0
                                  AND m.list_id_values[1] IS NOT NULL
                        THEN CASE
                            WHEN POSITION('<' IN m.list_id_values[1]) > 0
                                 AND POSITION('>' IN m.list_id_values[1])
                                     > POSITION('<' IN m.list_id_values[1])
                            THEN lower(trim(SUBSTRING(
                                m.list_id_values[1]
                                FROM POSITION('<' IN m.list_id_values[1]) + 1
                                FOR POSITION('>' IN m.list_id_values[1])
                                    - POSITION('<' IN m.list_id_values[1]) - 1
                            )))
                            ELSE lower(trim(m.list_id_values[1]))
                        END
                        ELSE lower(trim(m.from_address))
                   END AS normalized_list_identity
            FROM message_metadata m
            WHERE m.account_id IN (:account_ids)
              AND m.from_address IS NOT NULL
              AND trim(m.from_address) <> ''
              /* page_identity_filter */
        ),
        candidate_base AS (
            SELECT m.id AS metadata_id,
                   m.account_id,
                   a.email AS account_label,
                   m.provider,
                   m.provider_message_id,
                   m.provider_thread_id,
                   lower(trim(m.from_address)) AS sender_key,
                   m.from_display_name,
                   m.from_domain,
                   COALESCE(NULLIF(m.normalized_list_identity, ''),
                            lower(trim(m.from_address))) AS list_identity,
                   m.received_at,
                   m.list_unsubscribe_values,
                   m.list_unsubscribe_post_values,
                   m.unsubscribe_dkim_pass,
                   m.unsubscribe_dkim_covers_list_unsubscribe,
                   m.unsubscribe_dkim_covers_list_unsubscribe_post,
                   m.unsubscribe_auth_reason,
                   CASE WHEN CARDINALITY(m.list_id_values) > 1
                        THEN 1 ELSE 0 END AS ambiguous_list_identity,
                   CAST(m.list_unsubscribe_values AS VARCHAR(10000)) || '|'
                       || CAST(m.list_unsubscribe_post_values AS VARCHAR(10000))
                       AS unsubscribe_signature,
                   CASE WHEN m.received_at >= :thirty_days THEN 1 ELSE 0 END AS recent_row,
                   CASE WHEN CARDINALITY(m.list_unsubscribe_values) > 0
                        THEN 1 ELSE 0 END AS unsubscribe_source
            FROM normalized_metadata m
            JOIN user_accounts a ON a.id = m.account_id
        ),
        recent_conflicts AS (
            SELECT account_id, sender_key, list_identity,
                   CASE WHEN MIN(unsubscribe_signature) <> MAX(unsubscribe_signature)
                        THEN 1 ELSE 0 END AS conflicting_recent_source
            FROM candidate_base
            WHERE received_at >= :ninety_days
              AND unsubscribe_source = 1
            GROUP BY account_id, sender_key, list_identity
        ),
        grouped AS (
            SELECT cb.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY account_id, sender_key, list_identity
                       ORDER BY unsubscribe_source DESC,
                                CASE WHEN received_at IS NULL THEN 1 ELSE 0 END,
                                received_at DESC, metadata_id DESC
                   ) AS source_rank,
                   SUM(recent_row) OVER (
                       PARTITION BY account_id, sender_key, list_identity
                   ) AS thirty_day_count,
                   MAX(unsubscribe_source) OVER (
                       PARTITION BY account_id, sender_key, list_identity
                   ) AS has_unsubscribe_source
            FROM candidate_base cb
        )
        SELECT g.*,
               COALESCE(rc.conflicting_recent_source, 0) AS conflicting_recent_source,
               CASE WHEN EXISTS (
                   SELECT 1 FROM protected_senders ps
                   WHERE ps.account_id = g.account_id
                     AND ps.sender_address = g.sender_key
               ) THEN 1 ELSE 0 END AS protected_sender
        FROM grouped g
        LEFT JOIN recent_conflicts rc
          ON rc.account_id = g.account_id
         AND rc.sender_key = g.sender_key
         AND rc.list_identity = g.list_identity
        WHERE source_rank = 1
          AND has_unsubscribe_source = 1
        ORDER BY thirty_day_count DESC,
                 CASE WHEN received_at IS NULL THEN 1 ELSE 0 END,
                 received_at DESC,
                 account_id ASC, sender_key ASC, list_identity ASC, metadata_id DESC
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public UnsubscribeCandidateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SenderPageCounts countSenderPage(
            Collection<Long> authorizedAccountIds, UnsubscribeQuery query) {
        return jdbc.queryForObject(senderPipeline() + """
            SELECT COUNT(*) AS sender_count,
                   COALESCE(SUM(CASE WHEN lifecycle_category = 'UNSUBSCRIBED'
                                     THEN 1 ELSE 0 END), 0) AS unsubscribed_count,
                   COALESCE(SUM(CASE WHEN lifecycle_category = 'ARCHIVED_DELETED'
                                     THEN 1 ELSE 0 END), 0) AS cleaned_count
              FROM filtered_senders
            """, senderParameters(authorizedAccountIds, query),
            (result, rowNumber) -> new SenderPageCounts(
                result.getLong("sender_count"),
                result.getLong("unsubscribed_count"),
                result.getLong("cleaned_count")));
    }

    public List<SenderPageFact> findSenderPage(
            Collection<Long> authorizedAccountIds, UnsubscribeQuery query) {
        MapSqlParameterSource parameters = senderParameters(authorizedAccountIds, query)
            .addValue("page_limit", UnsubscribeQuery.PAGE_SIZE)
            .addValue("page_offset", query.offset());
        return jdbc.query(senderPipeline() + """
            SELECT account_id, account_label, sender_key, display_name, domain,
                   email_count, read_count, last_received_at, protected_sender,
                   latest_status, outcome_at, outcome_method, lifecycle_category
              FROM filtered_senders
            """ + senderOrder(query.sort()) + """
             LIMIT :page_limit OFFSET :page_offset
            """, parameters, (result, rowNumber) -> new SenderPageFact(
                new SenderSummary(
                    result.getLong("account_id"),
                    result.getString("account_label"),
                    result.getString("sender_key"),
                    result.getString("display_name"),
                    result.getString("domain"),
                    result.getLong("email_count"),
                    result.getLong("read_count"),
                    instant(result.getTimestamp("last_received_at")),
                    result.getInt("protected_sender") == 1),
                "NOT_ATTEMPTED".equals(result.getString("latest_status"))
                    ? null : new LatestOutcome(
                        result.getString("latest_status"),
                        instant(result.getTimestamp("outcome_at")),
                        result.getString("outcome_method")),
                "ARCHIVED_DELETED".equals(result.getString("lifecycle_category"))));
    }

    public List<SourceCandidate> findCandidatesForSenders(
            Collection<Long> authorizedAccountIds,
            Collection<SenderIdentity> identities,
            Instant now) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()
                || identities == null || identities.size() > 25 || now == null) {
            throw new IllegalArgumentException("A bounded owned sender page is required");
        }
        if (identities.isEmpty()) {
            return List.of();
        }
        List<Long> scope = authorizedAccountIds.stream().distinct().sorted().toList();
        StringBuilder predicate = new StringBuilder("AND (");
        MapSqlParameterSource parameters = candidateParameters(scope, now);
        int index = 0;
        for (SenderIdentity identity : identities) {
            if (!scope.contains(identity.accountId())
                    || identity.senderKey() == null || identity.senderKey().isBlank()) {
                throw new IllegalArgumentException("Page sender must belong to the owned scope");
            }
            if (index > 0) predicate.append(" OR ");
            predicate.append("(m.account_id = :page_account_").append(index)
                .append(" AND lower(trim(m.from_address)) = :page_sender_")
                .append(index).append(')');
            parameters
                .addValue("page_account_" + index, identity.accountId())
                .addValue("page_sender_" + index, identity.senderKey());
            index++;
        }
        predicate.append(')');
        return jdbc.query(
            CANDIDATE_SQL.replace(IDENTITY_FILTER, predicate.toString()),
            parameters, this::sourceCandidate);
    }

    public List<SenderSummary> findSenderBatch(
            Collection<Long> authorizedAccountIds,
            Instant startInclusive,
            Instant endExclusive,
            int batchSize,
            long batchOffset) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()
                || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)
                || batchSize < 1 || batchSize > 256 || batchOffset < 0) {
            throw new IllegalArgumentException("A bounded authorized sender query is required");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_ids", authorizedAccountIds.stream().distinct().sorted().toList())
            .addValue("start_inclusive", Timestamp.from(startInclusive))
            .addValue("end_exclusive", Timestamp.from(endExclusive))
            .addValue("batch_limit", batchSize)
            .addValue("batch_offset", batchOffset);
        return jdbc.query("""
            SELECT m.account_id,
                   a.email AS account_label,
                   lower(trim(m.from_address)) AS sender_key,
                   COALESCE(MAX(NULLIF(trim(m.from_display_name), '')), '') AS display_name,
                   COALESCE(MAX(NULLIF(lower(trim(m.from_domain)), '')), '') AS domain,
                   COUNT(*) AS email_count,
                   SUM(CASE WHEN m.unread = false THEN 1 ELSE 0 END) AS read_count,
                   MAX(m.received_at) AS last_received_at,
                   CASE WHEN COUNT(ps.account_id) > 0 THEN 1 ELSE 0 END AS protected_sender
              FROM message_metadata m
              JOIN user_accounts a ON a.id = m.account_id
              LEFT JOIN protected_senders ps
                ON ps.account_id = m.account_id
               AND ps.sender_address = lower(trim(m.from_address))
             WHERE m.account_id IN (:account_ids)
               AND m.from_address IS NOT NULL
               AND trim(m.from_address) <> ''
               AND m.received_at >= :start_inclusive
               AND m.received_at < :end_exclusive
             GROUP BY m.account_id, a.email, lower(trim(m.from_address))
             ORDER BY email_count DESC, m.account_id ASC, sender_key ASC
             LIMIT :batch_limit OFFSET :batch_offset
            """, parameters, (result, rowNumber) -> new SenderSummary(
                result.getLong("account_id"),
                result.getString("account_label"),
                result.getString("sender_key"),
                result.getString("display_name"),
                result.getString("domain"),
                result.getLong("email_count"),
                result.getLong("read_count"),
                result.getTimestamp("last_received_at") == null
                    ? null : result.getTimestamp("last_received_at").toInstant(),
                result.getInt("protected_sender") == 1));
    }

    public List<String> findSenderMessageIds(
            long accountId,
            String senderAddress,
            Instant startInclusive,
            Instant endExclusive,
            int limit) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()
                || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive) || limit < 1 || limit > 1001) {
            throw new IllegalArgumentException("A bounded sender scope is required");
        }
        return jdbc.query("""
            SELECT provider_message_id
              FROM message_metadata
             WHERE account_id = :account_id
               AND lower(trim(from_address)) = :sender_address
               AND received_at >= :start_inclusive
               AND received_at < :end_exclusive
             ORDER BY provider_message_id
             LIMIT :limit
            """, new MapSqlParameterSource()
                .addValue("account_id", accountId)
                .addValue("sender_address", senderAddress)
                .addValue("start_inclusive", Timestamp.from(startInclusive))
                .addValue("end_exclusive", Timestamp.from(endExclusive))
                .addValue("limit", limit),
            (result, rowNumber) -> result.getString("provider_message_id"));
    }

    public boolean isProtectedSender(long accountId, String senderAddress) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()) {
            throw new IllegalArgumentException("Owned sender identity is required");
        }
        Number count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM protected_senders
             WHERE account_id = :account_id
               AND sender_address = :sender_address
            """, new MapSqlParameterSource()
                .addValue("account_id", accountId)
                .addValue("sender_address", senderAddress), Number.class);
        return count != null && count.longValue() > 0;
    }

    public long countSenderEmails(
            long accountId,
            String senderAddress,
            Instant startInclusive,
            Instant endExclusive) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()
                || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("A bounded sender history is required");
        }
        Number count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM message_metadata
             WHERE account_id = :account_id
               AND lower(trim(from_address)) = :sender_address
               AND received_at >= :start_inclusive
               AND received_at < :end_exclusive
            """, new MapSqlParameterSource()
                .addValue("account_id", accountId)
                .addValue("sender_address", senderAddress)
                .addValue("start_inclusive", Timestamp.from(startInclusive))
                .addValue("end_exclusive", Timestamp.from(endExclusive)),
            Number.class);
        return count == null ? 0 : count.longValue();
    }

    public List<SenderEmailBucketCount> countSenderEmailBuckets(
            long accountId,
            String senderAddress,
            List<SenderEmailBucket> buckets) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()
                || buckets == null || buckets.isEmpty() || buckets.size() > 14) {
            throw new IllegalArgumentException("Bounded sender stats buckets are required");
        }
        StringBuilder select = new StringBuilder();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_id", accountId)
            .addValue("sender_address", senderAddress);
        for (int index = 0; index < buckets.size(); index++) {
            SenderEmailBucket bucket = buckets.get(index);
            if (bucket.label() == null || bucket.label().isBlank()
                    || bucket.startInclusive() == null || bucket.endExclusive() == null
                    || !bucket.startInclusive().isBefore(bucket.endExclusive())) {
                throw new IllegalArgumentException("Invalid sender stats bucket");
            }
            if (index > 0) select.append(",\n                   ");
            select.append("""
                SUM(CASE WHEN received_at >= :bucket_start_%d
                          AND received_at < :bucket_end_%d
                         THEN 1 ELSE 0 END) AS bucket_%d""".formatted(
                    index, index, index));
            parameters
                .addValue("bucket_start_" + index, Timestamp.from(bucket.startInclusive()))
                .addValue("bucket_end_" + index, Timestamp.from(bucket.endExclusive()));
        }
        List<SenderEmailBucketCount> counts = jdbc.queryForObject("""
            SELECT %s
              FROM message_metadata
             WHERE account_id = :account_id
               AND lower(trim(from_address)) = :sender_address
               AND received_at >= :range_start
               AND received_at < :range_end
            """.formatted(select), parameters
                .addValue("range_start", Timestamp.from(buckets.getFirst().startInclusive()))
                .addValue("range_end", Timestamp.from(buckets.getLast().endExclusive())),
            (result, rowNumber) -> {
                ArrayList<SenderEmailBucketCount> values = new ArrayList<>(buckets.size());
                for (int index = 0; index < buckets.size(); index++) {
                    values.add(new SenderEmailBucketCount(
                        buckets.get(index).label(),
                        buckets.get(index).startInclusive(),
                        buckets.get(index).endExclusive(),
                        result.getLong("bucket_" + index)));
                }
                return values;
            });
        return counts == null ? List.of() : counts;
    }

    public List<SenderEmailFact> findSenderEmails(
            long accountId,
            String senderAddress,
            Instant startInclusive,
            Instant endExclusive,
            int limit) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()
                || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive) || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("A bounded sender history is required");
        }
        return jdbc.query("""
            SELECT COALESCE(NULLIF(trim(from_display_name), ''), from_address) AS sender,
                   subject_header, received_at, unread, attachment_state
              FROM message_metadata
             WHERE account_id = :account_id
               AND lower(trim(from_address)) = :sender_address
               AND received_at >= :start_inclusive
               AND received_at < :end_exclusive
             ORDER BY received_at DESC, id DESC
             LIMIT :limit
            """, new MapSqlParameterSource()
                .addValue("account_id", accountId)
                .addValue("sender_address", senderAddress)
                .addValue("start_inclusive", Timestamp.from(startInclusive))
                .addValue("end_exclusive", Timestamp.from(endExclusive))
                .addValue("limit", limit),
            (result, rowNumber) -> new SenderEmailFact(
                result.getString("sender"),
                result.getString("subject_header"),
                result.getTimestamp("received_at") == null
                    ? null : result.getTimestamp("received_at").toInstant(),
                result.getBoolean("unread"),
                AttachmentState.valueOf(result.getString("attachment_state"))));
    }

    public Set<SenderIdentity> findSuccessfulCleanupSenders(
            Collection<Long> authorizedAccountIds,
            Instant startInclusive,
            Instant endExclusive) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()) return Set.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_ids", authorizedAccountIds.stream().distinct().sorted().toList())
            .addValue("start_inclusive", Timestamp.from(startInclusive))
            .addValue("end_exclusive", Timestamp.from(endExclusive));
        List<CleanupTarget> targets = jdbc.query("""
            SELECT scope.account_id, scope.message_ids, target.scope_ordinal
              FROM action_execution_targets target
              JOIN action_executions execution
                ON execution.id = target.execution_id
               AND execution.account_id = target.account_id
              JOIN user_actions action
                ON action.id = execution.action_id
               AND action.account_id = execution.account_id
              JOIN action_scopes scope
                ON scope.id = target.scope_id
               AND scope.account_id = target.account_id
             WHERE target.account_id IN (:account_ids)
               AND execution.operation_kind = 'FORWARD'
               AND target.status = 'SUCCEEDED'
               AND action.action_type IN ('ARCHIVE', 'TRASH')
               AND target.completed_at >= :start_inclusive
               AND target.completed_at < :end_exclusive
            """, parameters, (result, rowNumber) -> {
                List<String> ids = strings(result.getArray("message_ids"));
                int ordinal = result.getInt("scope_ordinal");
                return new CleanupTarget(
                    result.getLong("account_id"),
                    ordinal >= 0 && ordinal < ids.size() ? ids.get(ordinal) : null);
            });
        targets = targets.stream().filter(target -> target.messageId() != null).toList();
        if (targets.isEmpty()) return Set.of();

        StringBuilder predicate = new StringBuilder();
        MapSqlParameterSource messageParameters = new MapSqlParameterSource();
        for (int index = 0; index < targets.size(); index++) {
            if (index > 0) predicate.append(" OR ");
            predicate.append("(account_id = :cleanup_account_").append(index)
                .append(" AND provider_message_id = :cleanup_message_").append(index).append(')');
            messageParameters
                .addValue("cleanup_account_" + index, targets.get(index).accountId())
                .addValue("cleanup_message_" + index, targets.get(index).messageId());
        }
        return Set.copyOf(jdbc.query("""
            SELECT DISTINCT account_id, lower(trim(from_address)) AS sender_key
              FROM message_metadata
             WHERE from_address IS NOT NULL
               AND (%s)
            """.formatted(predicate), messageParameters,
            (result, rowNumber) -> new SenderIdentity(
                result.getLong("account_id"), result.getString("sender_key"))));
    }

    public List<SourceCandidate> findCandidateBatch(
            Collection<Long> authorizedAccountIds,
            Instant now,
            int batchSize,
            long batchOffset) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()
                || now == null || batchSize < 1 || batchSize > 256 || batchOffset < 0) {
            throw new IllegalArgumentException("Authorized accounts and query instant are required");
        }
        MapSqlParameterSource parameters = candidateParameters(
            authorizedAccountIds.stream().distinct().sorted().toList(), now)
            .addValue("batch_limit", batchSize)
            .addValue("batch_offset", batchOffset);
        return jdbc.query(CANDIDATE_SQL.replace(IDENTITY_FILTER, "") + """
            LIMIT :batch_limit OFFSET :batch_offset
            """, parameters, this::sourceCandidate);
    }

    public Map<String, LatestOutcome> findLatestOutcomes(
            Collection<Long> authorizedAccountIds, Collection<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) return Map.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("account_ids", authorizedAccountIds.stream().distinct().sorted().toList())
            .addValue("fingerprints", fingerprints.stream().distinct().toList());
        LinkedHashMap<String, LatestOutcome> outcomes = new LinkedHashMap<>();
        List<OutcomeEntry> rows = jdbc.query("""
            WITH ranked AS (
                SELECT candidate_fingerprint, latest_status,
                       COALESCE(completed_at, created_at) AS outcome_at,
                       created_at, method,
                       ROW_NUMBER() OVER (
                           PARTITION BY account_id, candidate_fingerprint
                           ORDER BY created_at DESC, id DESC
                       ) AS outcome_rank
                FROM unsubscribe_attempts
                WHERE account_id IN (:account_ids)
                  AND candidate_fingerprint IN (:fingerprints)
            )
            SELECT candidate_fingerprint, latest_status, outcome_at, method
            FROM ranked
            WHERE outcome_rank = 1
            """, parameters, (result, rowNumber) ->
                new OutcomeEntry(
                    result.getString("candidate_fingerprint"),
                    new LatestOutcome(
                    result.getString("latest_status"),
                    result.getTimestamp("outcome_at").toInstant(),
                    result.getString("method"))));
        rows.forEach(row -> outcomes.put(row.fingerprint(), row.outcome()));
        return Map.copyOf(outcomes);
    }

    private String senderPipeline() {
        String candidateValue = "CAST(summary.account_id AS VARCHAR) || ':' || "
            + "CAST(char_length(summary.sender_key) AS VARCHAR) || ':' || "
            + "summary.sender_key || ':' || "
            + "CAST(char_length(source.list_identity) AS VARCHAR) || ':' || "
            + "source.list_identity";
        String candidateFingerprint =
            "encode(sha256(convert_to(" + candidateValue + ", 'UTF8')), 'hex')";
        return """
            WITH sender_summaries AS (
                SELECT m.account_id,
                       a.email AS account_label,
                       lower(trim(m.from_address)) AS sender_key,
                       COALESCE(MAX(NULLIF(trim(m.from_display_name), '')), '')
                           AS display_name,
                       COALESCE(MAX(NULLIF(lower(trim(m.from_domain)), '')), '')
                           AS domain,
                       COUNT(*) AS email_count,
                       SUM(CASE WHEN m.unread = false THEN 1 ELSE 0 END) AS read_count,
                       MAX(m.received_at) AS last_received_at,
                       CASE WHEN COUNT(ps.account_id) > 0 THEN 1 ELSE 0 END
                           AS protected_sender
                  FROM message_metadata m
                  JOIN user_accounts a ON a.id = m.account_id
                  LEFT JOIN protected_senders ps
                    ON ps.account_id = m.account_id
                   AND ps.sender_address = lower(trim(m.from_address))
                 WHERE m.account_id IN (:account_ids)
                   AND m.from_address IS NOT NULL
                   AND trim(m.from_address) <> ''
                   AND m.received_at >= :start_inclusive
                   AND m.received_at < :end_exclusive
                 GROUP BY m.account_id, a.email, lower(trim(m.from_address))
            ),
            normalized_sources AS (
                SELECT m.id AS metadata_id,
                       m.account_id,
                       lower(trim(m.from_address)) AS sender_key,
                       CASE WHEN CARDINALITY(m.list_id_values) > 0
                                      AND m.list_id_values[1] IS NOT NULL
                            THEN CASE
                                WHEN POSITION('<' IN m.list_id_values[1]) > 0
                                     AND POSITION('>' IN m.list_id_values[1])
                                         > POSITION('<' IN m.list_id_values[1])
                                THEN lower(trim(SUBSTRING(
                                    m.list_id_values[1]
                                    FROM POSITION('<' IN m.list_id_values[1]) + 1
                                    FOR POSITION('>' IN m.list_id_values[1])
                                        - POSITION('<' IN m.list_id_values[1]) - 1
                                )))
                                ELSE lower(trim(m.list_id_values[1]))
                            END
                            ELSE lower(trim(m.from_address))
                       END AS list_identity,
                       m.received_at,
                       CASE WHEN CARDINALITY(m.list_unsubscribe_values) > 0
                            THEN 1 ELSE 0 END AS unsubscribe_source,
                       CASE WHEN m.received_at >= :thirty_days THEN 1 ELSE 0 END
                           AS recent_row
                  FROM message_metadata m
                 WHERE m.account_id IN (:account_ids)
                   AND m.from_address IS NOT NULL
                   AND trim(m.from_address) <> ''
            ),
            grouped_sources AS (
                SELECT source.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY account_id, sender_key, list_identity
                           ORDER BY unsubscribe_source DESC,
                                    CASE WHEN received_at IS NULL THEN 1 ELSE 0 END,
                                    received_at DESC, metadata_id DESC
                       ) AS source_rank,
                       SUM(recent_row) OVER (
                           PARTITION BY account_id, sender_key, list_identity
                       ) AS thirty_day_count,
                       MAX(unsubscribe_source) OVER (
                           PARTITION BY account_id, sender_key, list_identity
                       ) AS has_unsubscribe_source
                  FROM normalized_sources source
            ),
            ranked_source_identities AS (
                SELECT grouped.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY account_id, sender_key
                           ORDER BY thirty_day_count DESC,
                                    CASE WHEN received_at IS NULL THEN 1 ELSE 0 END,
                                    received_at DESC, list_identity ASC, metadata_id DESC
                       ) AS candidate_rank
                  FROM grouped_sources grouped
                 WHERE source_rank = 1 AND has_unsubscribe_source = 1
            ),
            source_identities AS (
                SELECT account_id, sender_key, list_identity, metadata_id
                  FROM ranked_source_identities
                 WHERE candidate_rank = 1
            ),
            ranked_outcomes AS (
                SELECT account_id, candidate_fingerprint, latest_status,
                       COALESCE(completed_at, created_at) AS outcome_at,
                       method AS outcome_method,
                       ROW_NUMBER() OVER (
                           PARTITION BY account_id, candidate_fingerprint
                           ORDER BY created_at DESC, id DESC
                       ) AS outcome_rank
                  FROM unsubscribe_attempts
                 WHERE account_id IN (:account_ids)
            ),
            cleaned_senders AS (
                SELECT DISTINCT scope.account_id,
                       lower(trim(metadata.from_address)) AS sender_key
                  FROM action_execution_targets target
                  JOIN action_executions execution
                    ON execution.id = target.execution_id
                   AND execution.account_id = target.account_id
                  JOIN user_actions action
                    ON action.id = execution.action_id
                   AND action.account_id = execution.account_id
                  JOIN action_scopes scope
                    ON scope.id = target.scope_id
                   AND scope.account_id = target.account_id
                  JOIN message_metadata metadata
                    ON metadata.account_id = scope.account_id
                   AND metadata.provider_message_id =
                       scope.message_ids[target.scope_ordinal + 1]
                 WHERE target.account_id IN (:account_ids)
                   AND execution.operation_kind = 'FORWARD'
                   AND target.status = 'SUCCEEDED'
                   AND action.action_type IN ('ARCHIVE', 'TRASH')
                   AND target.completed_at >= :start_inclusive
                   AND target.completed_at < :end_exclusive
                   AND metadata.from_address IS NOT NULL
            ),
            enriched_senders AS (
                SELECT summary.*,
                       COALESCE(outcome.latest_status, 'NOT_ATTEMPTED') AS latest_status,
                       outcome.outcome_at,
                       outcome.outcome_method,
                       CASE WHEN outcome.latest_status = 'CONFIRMED'
                            THEN 'UNSUBSCRIBED'
                            WHEN cleaned.sender_key IS NOT NULL
                            THEN 'ARCHIVED_DELETED'
                            ELSE 'UNHANDLED' END AS lifecycle_category
                  FROM sender_summaries summary
                  LEFT JOIN source_identities source
                    ON source.account_id = summary.account_id
                   AND source.sender_key = summary.sender_key
                  LEFT JOIN ranked_outcomes outcome
                    ON outcome.account_id = summary.account_id
                   AND outcome.candidate_fingerprint = %s
                   AND outcome.outcome_rank = 1
                  LEFT JOIN cleaned_senders cleaned
                    ON cleaned.account_id = summary.account_id
                   AND cleaned.sender_key = summary.sender_key
            ),
            filtered_senders AS (
                SELECT * FROM enriched_senders
                 WHERE (:category = 'all'
                        OR lower(lifecycle_category) = :category)
                   AND (:protection = 'include'
                        OR (:protection = 'exclude' AND protected_sender = 0)
                        OR (:protection = 'only' AND protected_sender = 1))
                   AND (:search = ''
                        OR lower(display_name) LIKE :search_pattern ESCAPE '\\'
                        OR lower(sender_key) LIKE :search_pattern ESCAPE '\\'
                        OR lower(domain) LIKE :search_pattern ESCAPE '\\')
            )
            """.formatted(candidateFingerprint);
    }

    private static String senderOrder(UnsubscribeQuery.Sort sort) {
        String selected = switch (sort) {
            case VOLUME_DESC -> "email_count DESC";
            case READ_DESC ->
                "(read_count * 100.0 / CASE WHEN email_count = 0 THEN 1 ELSE email_count END) DESC";
            case SENDER -> "lower(sender_key) ASC";
            case NEWEST -> "CASE WHEN last_received_at IS NULL THEN 1 ELSE 0 END, "
                + "last_received_at DESC";
            case OLDEST -> "CASE WHEN last_received_at IS NULL THEN 1 ELSE 0 END, "
                + "last_received_at ASC";
            case STATE -> "lower(latest_status) ASC";
            case CONFIDENCE_DESC, METHOD, REVIEW ->
                throw new IllegalArgumentException("Classification sort requires bounded fallback");
        };
        return " ORDER BY " + selected
            + ", lower(sender_key) ASC, account_id ASC";
    }

    private static MapSqlParameterSource senderParameters(
            Collection<Long> authorizedAccountIds, UnsubscribeQuery query) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty() || query == null) {
            throw new IllegalArgumentException("Owned unsubscribe query is required");
        }
        String escaped = query.search().toLowerCase(java.util.Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return new MapSqlParameterSource()
            .addValue("account_ids",
                authorizedAccountIds.stream().distinct().sorted().toList())
            .addValue("start_inclusive", Timestamp.from(query.startInclusive()))
            .addValue("end_exclusive", Timestamp.from(query.endExclusive()))
            .addValue("thirty_days",
                Timestamp.from(Instant.now().minusSeconds(30L * 24 * 60 * 60)))
            .addValue("category", query.category().value())
            .addValue("protection", query.protection().value())
            .addValue("search", query.search())
            .addValue("search_pattern", "%" + escaped + "%");
    }

    private static MapSqlParameterSource candidateParameters(
            Collection<Long> authorizedAccountIds, Instant now) {
        return new MapSqlParameterSource()
            .addValue("account_ids", List.copyOf(authorizedAccountIds))
            .addValue("thirty_days", Timestamp.from(now.minusSeconds(30L * 24 * 60 * 60)))
            .addValue("ninety_days", Timestamp.from(now.minusSeconds(90L * 24 * 60 * 60)));
    }

    private SourceCandidate sourceCandidate(
            java.sql.ResultSet result, int rowNumber) throws SQLException {
        return new SourceCandidate(
            result.getLong("metadata_id"),
            result.getLong("account_id"),
            result.getString("account_label"),
            result.getString("provider"),
            result.getString("provider_message_id"),
            result.getString("provider_thread_id"),
            result.getString("sender_key"),
            result.getString("from_display_name"),
            result.getString("from_domain"),
            result.getString("list_identity"),
            instant(result.getTimestamp("received_at")),
            result.getLong("thirty_day_count"),
            strings(result.getArray("list_unsubscribe_values")),
            strings(result.getArray("list_unsubscribe_post_values")),
            result.getBoolean("unsubscribe_dkim_pass"),
            result.getBoolean("unsubscribe_dkim_covers_list_unsubscribe"),
            result.getBoolean("unsubscribe_dkim_covers_list_unsubscribe_post"),
            result.getString("unsubscribe_auth_reason"),
            result.getInt("ambiguous_list_identity") == 1,
            result.getInt("conflicting_recent_source") == 1,
            result.getInt("protected_sender") == 1);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        if (value instanceof String[] strings) return List.of(strings);
        if (value instanceof Object[] values) {
            return java.util.Arrays.stream(values).map(String::valueOf).toList();
        }
        throw new SQLException("Unsupported SQL array representation");
    }

    public record SourceCandidate(
        long metadataId,
        long accountId,
        String accountLabel,
        String provider,
        String providerMessageId,
        String providerThreadId,
        String senderKey,
        String senderDisplayName,
        String senderDomain,
        String listIdentity,
        Instant receivedAt,
        long thirtyDayCount,
        List<String> listUnsubscribeValues,
        List<String> listUnsubscribePostValues,
        boolean dkimPass,
        boolean dkimCoversListUnsubscribe,
        boolean dkimCoversListUnsubscribePost,
        String authenticationReason,
        boolean ambiguousListIdentity,
        boolean conflictingRecentSource,
        boolean protectedSender
    ) {
        public SourceCandidate {
            listUnsubscribeValues = List.copyOf(listUnsubscribeValues);
            listUnsubscribePostValues = List.copyOf(listUnsubscribePostValues);
        }
    }

    public record LatestOutcome(String state, Instant occurredAt, String method) {
    }

    public record SenderPageCounts(
        long senderCount,
        long unsubscribedCount,
        long cleanedCount
    ) {
    }

    public record SenderPageFact(
        SenderSummary summary,
        LatestOutcome latestOutcome,
        boolean cleaned
    ) {
    }

    public record SenderSummary(
        long accountId,
        String accountLabel,
        String senderKey,
        String senderDisplayName,
        String senderDomain,
        long emailCount,
        long readCount,
        Instant lastReceivedAt,
        boolean protectedSender
    ) {
    }

    public record SenderIdentity(long accountId, String senderKey) {
    }

    public record SenderEmailFact(
        String sender,
        String subject,
        Instant receivedAt,
        boolean unread,
        AttachmentState attachmentState
    ) {
    }

    public record SenderEmailBucket(
        String label,
        Instant startInclusive,
        Instant endExclusive
    ) {
    }

    public record SenderEmailBucketCount(
        String label,
        Instant startInclusive,
        Instant endExclusive,
        long count
    ) {
    }

    private record OutcomeEntry(String fingerprint, LatestOutcome outcome) {
    }

    private record CleanupTarget(long accountId, String messageId) {
    }
}
