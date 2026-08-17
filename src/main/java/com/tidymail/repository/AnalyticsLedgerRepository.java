package com.tidymail.repository;

import com.tidymail.dto.AnalyticsOutcomeSummary;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsSourcePage.SourceRow;
import com.tidymail.dto.AnalyticsSourceQuery.SourceSeries;
import java.sql.Timestamp;
import java.math.BigDecimal;
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
public class AnalyticsLedgerRepository {
    private static final String SAFE_SOURCE_COLUMNS = """
        CASE WHEN account.email IS NULL OR trim(account.email)=''
             THEN 'Gmail account' ELSE account.email || ' (Gmail)' END AS account_label,
        metadata.received_at,
        COALESCE(NULLIF(trim(metadata.from_display_name), ''), 'Unknown sender')
            AS sender_display,
        NULLIF(lower(trim(metadata.from_address)), '') AS sender_address,
        NULLIF(lower(trim(metadata.from_domain)), '') AS sender_domain,
        cleaned.occurred_at AS fact_at,
        cleaned.action_type AS fact_action
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final String arrayValueExpression;

    public AnalyticsLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.arrayValueExpression = "s.message_ids[t.scope_ordinal + 1]";
    }

    public LedgerSnapshot snapshot(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
            , forward_success AS (
                SELECT action_type, COUNT(*) AS success_count
                  FROM resolved_targets
                 WHERE operation_kind='FORWARD'
                   AND target_status='SUCCEEDED'
                   AND completed_at>=:start AND completed_at<:end
                   AND (:action='ALL' OR action_type=:action)
                   AND action_type IN ('ARCHIVE','TRASH','MARK_READ')
                 GROUP BY action_type
            ),
            confirmed_unsubscribe AS (
                SELECT COUNT(DISTINCT r.target_id) AS confirmed_count
                  FROM resolved_targets r
                  JOIN unsubscribe_outcome_events outcome
                    ON outcome.attempt_id=r.unsubscribe_attempt_id
                   AND outcome.account_id=r.account_id
                 WHERE outcome.status='CONFIRMED'
                   AND outcome.occurred_at>=:start AND outcome.occurred_at<:end
                   AND (:action IN ('ALL','UNSUBSCRIBE'))
            ),
            unsubscribe_accepted_or_pending AS (
                SELECT COUNT(DISTINCT r.target_id) AS accepted_or_pending_count
                  FROM resolved_targets r
                  JOIN unsubscribe_outcome_events outcome
                    ON outcome.attempt_id=r.unsubscribe_attempt_id
                   AND outcome.account_id=r.account_id
                 WHERE outcome.status IN ('ACCEPTED','VERIFICATION_PENDING')
                   AND outcome.occurred_at>=:start AND outcome.occurred_at<:end
                   AND (:action IN ('ALL','UNSUBSCRIBE'))
            ),
            inverse_success AS (
                SELECT COUNT(*) AS inverse_count
                  FROM resolved_targets
                 WHERE operation_kind='UNDO'
                   AND target_status='REVERTED'
                   AND action_type IN ('ARCHIVE','TRASH')
                   AND completed_at>=:start AND completed_at<:end
                   AND (:action='ALL' OR action_type=:action)
            )
            SELECT
              (SELECT COUNT(*) FROM first_decisions
                WHERE occurred_at>=:start AND occurred_at<:end
                  AND (:action='ALL' OR action_type=:action)) AS triaged,
              COALESCE((SELECT success_count FROM forward_success
                         WHERE action_type='ARCHIVE'),0) AS archived,
              COALESCE((SELECT success_count FROM forward_success
                         WHERE action_type='TRASH'),0) AS trashed,
              COALESCE((SELECT success_count FROM forward_success
                         WHERE action_type='MARK_READ'),0) AS marked_read,
              (SELECT confirmed_count FROM confirmed_unsubscribe) AS unsubscribed,
              (SELECT accepted_or_pending_count
                 FROM unsubscribe_accepted_or_pending)
                  AS unsubscribe_accepted_or_pending,
              (SELECT inverse_count FROM inverse_success) AS inverse_succeeded
            """;
        LedgerSnapshot snapshot = jdbc.queryForObject(sql, predicate.parameters(), (row, index) ->
            new LedgerSnapshot(
                row.getLong("triaged"), row.getLong("archived"),
                row.getLong("trashed"), row.getLong("marked_read"),
                row.getLong("unsubscribed"),
                row.getLong("unsubscribe_accepted_or_pending"),
                row.getLong("inverse_succeeded")));
        return snapshot.intersect(action);
    }

    public AnalyticsOutcomeSummary outcomes(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
            , requested AS (
                SELECT r.*,
                       CASE
                         WHEN r.completed_at IS NOT NULL AND r.completed_at<:end
                           THEN r.target_status
                         WHEN r.dispatched_at IS NOT NULL AND r.dispatched_at<:end
                           THEN 'VERIFICATION_PENDING'
                         ELSE 'NOT_DISPATCHED'
                       END AS as_of_status,
                       CASE WHEN r.accepted_at IS NOT NULL AND r.accepted_at<:end
                            THEN 1 ELSE 0 END AS accepted_as_of
                  FROM resolved_targets r
                 WHERE r.operation_kind IN ('FORWARD','COMPENSATION')
                   AND (:action='ALL' OR r.action_type=:action)
                   AND EXISTS (
                       SELECT 1 FROM action_events event
                        WHERE event.execution_id=r.execution_id
                          AND event.account_id=r.account_id
                          AND event.event_type='EXECUTION_RESERVED'
                          AND event.occurred_at>=:start AND event.occurred_at<:end
                   )
            )
            SELECT COUNT(*) AS requested,
                   COALESCE(SUM(accepted_as_of),0) AS accepted,
                   COALESCE(SUM(CASE WHEN as_of_status='SUCCEEDED' THEN 1 ELSE 0 END),0)
                       AS succeeded,
                   COALESCE(SUM(CASE WHEN as_of_status='FAILED' THEN 1 ELSE 0 END),0)
                       AS failed,
                   COALESCE(SUM(CASE WHEN as_of_status='VERIFICATION_PENDING'
                                     THEN 1 ELSE 0 END),0) AS verification_pending,
                   COALESCE(SUM(CASE WHEN as_of_status='NOT_DISPATCHED'
                                     THEN 1 ELSE 0 END),0) AS not_dispatched
              FROM requested
            """;
        return jdbc.queryForObject(sql, predicate.parameters(), (row, index) ->
            new AnalyticsOutcomeSummary(
                row.getLong("requested"), row.getLong("accepted"),
                row.getLong("succeeded"), row.getLong("failed"),
                row.getLong("verification_pending"), row.getLong("not_dispatched")));
    }

    public CohortCounts cohortCounts(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.firstDecisions(arrayValueExpression)
            + ", " + stripWith(AnalyticsLedgerSql.cleanedFacts(arrayValueExpression)) + """
            , received_cohort AS (
                SELECT metadata.account_id, metadata.provider_message_id AS message_key
                  FROM message_metadata metadata
                 WHERE metadata.account_id IN (:account_ids)
                   AND metadata.received_at>=:start AND metadata.received_at<:end
            ),
            triaged_in_range AS (
                SELECT * FROM first_decisions
                 WHERE occurred_at>=:start AND occurred_at<:end
                   AND (:action='ALL' OR action_type=:action)
            )
            SELECT
              (SELECT COUNT(*) FROM received_cohort) AS received,
              (SELECT COUNT(*) FROM received_cohort cohort
                WHERE EXISTS (
                    SELECT 1 FROM first_decisions decision
                     WHERE decision.account_id=cohort.account_id
                       AND decision.message_key=cohort.message_key
                       AND decision.occurred_at<:end
                       AND (:action='ALL' OR decision.action_type=:action)
                )) AS decided_received,
              (SELECT COUNT(*) FROM triaged_in_range) AS triaged,
              (SELECT COUNT(*) FROM triaged_in_range triaged
                WHERE EXISTS (
                    SELECT 1 FROM cleaned_facts cleaned
                     WHERE cleaned.account_id=triaged.account_id
                       AND cleaned.message_key=triaged.message_key
                       AND cleaned.occurred_at>=:start AND cleaned.occurred_at<:end
                       AND (:action='ALL' OR cleaned.action_type=:action)
                )) AS cleaned_triaged
            """;
        return jdbc.queryForObject(sql, predicate.parameters(), (row, index) ->
            new CohortCounts(
                row.getLong("received"), row.getLong("decided_received"),
                row.getLong("triaged"), row.getLong("cleaned_triaged")));
    }

    public ReviewCounts reviewCounts(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
            SELECT COUNT(*) AS executed,
                   COALESCE(SUM(CASE WHEN execution_mode='STAGED' THEN 1 ELSE 0 END),0)
                       AS staged
              FROM resolved_targets r
             WHERE r.operation_kind IN ('FORWARD','COMPENSATION')
               AND (:action='ALL' OR r.action_type=:action)
               AND EXISTS (
                   SELECT 1 FROM action_events event
                    WHERE event.execution_id=r.execution_id
                      AND event.account_id=r.account_id
                      AND event.event_type='EXECUTION_RESERVED'
                      AND event.occurred_at>=:start AND event.occurred_at<:end
               )
            """;
        return jdbc.queryForObject(sql, predicate.parameters(), (row, index) ->
            new ReviewCounts(row.getLong("staged"), row.getLong("executed")));
    }

    public UnsubscribeCounts unsubscribeCounts(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range) {
        FactPredicate predicate = AnalyticsLedgerSql.predicate(
            accountIds, range.startInclusive(), range.endExclusive(), "UNSUBSCRIBE");
        String sql = AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
            , initiated AS (
                SELECT r.target_id, r.account_id, r.unsubscribe_attempt_id,
                       MIN(event.occurred_at) AS initiated_at
                  FROM resolved_targets r
                  JOIN unsubscribe_outcome_events event
                    ON event.attempt_id=r.unsubscribe_attempt_id
                   AND event.account_id=r.account_id
                 WHERE r.action_type='UNSUBSCRIBE' AND r.operation_kind='FORWARD'
                 GROUP BY r.target_id, r.account_id, r.unsubscribe_attempt_id
                HAVING MIN(event.occurred_at)>=:start
                   AND MIN(event.occurred_at)<:end
            ),
            classified AS (
                SELECT initiated.target_id,
                       MAX(CASE WHEN event.status='CONFIRMED' THEN 1 ELSE 0 END)
                           AS confirmed,
                       MAX(CASE WHEN event.status IN (
                           'ACCEPTED','CONFIRMED','FAILED','VERIFICATION_PENDING'
                       ) THEN 1 ELSE 0 END) AS eligible,
                       MAX(CASE WHEN event.status='MANUAL_REQUIRED' THEN 1 ELSE 0 END)
                           AS manual,
                       MAX(CASE WHEN event.status<>'MANUAL_REQUIRED' THEN 1 ELSE 0 END)
                           AS non_manual
                  FROM initiated
                  JOIN unsubscribe_outcome_events event
                    ON event.attempt_id=initiated.unsubscribe_attempt_id
                   AND event.account_id=initiated.account_id
                   AND event.occurred_at<:end
                 GROUP BY initiated.target_id
            )
            SELECT COALESCE(SUM(confirmed),0) AS confirmed,
                   COALESCE(SUM(eligible),0) AS eligible,
                   COALESCE(SUM(CASE WHEN manual=1 AND non_manual=0
                                     THEN 1 ELSE 0 END),0) AS manual_only
              FROM classified
            """;
        return jdbc.queryForObject(sql, predicate.parameters(), (row, index) ->
            new UnsubscribeCounts(
                row.getLong("confirmed"), row.getLong("eligible"),
                row.getLong("manual_only")));
    }

    public List<Long> eligibleDelaySeconds(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
            SELECT CAST(EXTRACT(EPOCH FROM (decision.occurred_at-metadata.received_at))
                        AS BIGINT) AS delay_seconds
              FROM first_decisions decision
              JOIN message_metadata metadata
                ON metadata.account_id=decision.account_id
               AND metadata.provider_message_id=decision.message_key
             WHERE decision.occurred_at>=:start AND decision.occurred_at<:end
               AND (:action='ALL' OR decision.action_type=:action)
               AND metadata.received_at IS NOT NULL
               AND decision.occurred_at>=metadata.received_at
             ORDER BY delay_seconds
            """;
        return jdbc.queryForList(sql, predicate.parameters(), Long.class);
    }

    public long excludedDelayCount(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
            SELECT COUNT(*)
              FROM first_decisions decision
              LEFT JOIN message_metadata metadata
                ON metadata.account_id=decision.account_id
               AND metadata.provider_message_id=decision.message_key
             WHERE decision.occurred_at>=:start AND decision.occurred_at<:end
               AND (:action='ALL' OR decision.action_type=:action)
               AND (metadata.received_at IS NULL
                    OR decision.occurred_at<metadata.received_at)
            """;
        Number count = jdbc.queryForObject(sql, predicate.parameters(), Number.class);
        return count == null ? 0 : count.longValue();
    }

    public BigDecimal medianDelaySeconds(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate = predicate(accountIds, range, action);
        String sql = AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
            SELECT percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY CAST(EXTRACT(EPOCH FROM (
                           decision.occurred_at-metadata.received_at
                       )) AS BIGINT)
                   ) AS median_seconds
              FROM first_decisions decision
              JOIN message_metadata metadata
                ON metadata.account_id=decision.account_id
               AND metadata.provider_message_id=decision.message_key
             WHERE decision.occurred_at>=:start AND decision.occurred_at<:end
               AND (:action='ALL' OR decision.action_type=:action)
               AND metadata.received_at IS NOT NULL
               AND decision.occurred_at>=metadata.received_at
            """;
        return jdbc.queryForObject(
            sql, predicate.parameters(),
            (row, index) -> row.getBigDecimal("median_seconds"));
    }

    public long cleanedCount(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        Number count = jdbc.queryForObject(
            AnalyticsLedgerSql.cleanedFacts(arrayValueExpression) + """
                  SELECT COUNT(*) FROM cleaned_facts
                """, predicate.parameters(), Number.class);
        return count == null ? 0 : count.longValue();
    }

    public long detailCount(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action,
            SourceSeries series) {
        if (!seriesMatchesAction(series, action)) return 0;
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        Number count = jdbc.queryForObject(
            detailSourceCte(series) + " SELECT COUNT(*) FROM detail_facts",
            predicate.parameters(), Number.class);
        return count == null ? 0 : count.longValue();
    }

    public List<SourceRow> detailPage(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action,
            SourceSeries series,
            int limit,
            long offset) {
        if (!seriesMatchesAction(series, action)) return List.of();
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        MapSqlParameterSource parameters = new MapSqlParameterSource(predicate.parameters())
            .addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(
            detailSourceCte(series) + detailSourceSelect()
                + " LIMIT :limit OFFSET :offset",
            parameters, DETAIL_SOURCE_ROW_MAPPER);
    }

    public Stream<SourceRow> streamDetail(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action,
            SourceSeries series) {
        if (!seriesMatchesAction(series, action)) return Stream.empty();
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        return jdbc.queryForStream(
            detailSourceCte(series) + detailSourceSelect(),
            predicate.parameters(), DETAIL_SOURCE_ROW_MAPPER);
    }

    private static boolean seriesMatchesAction(
            SourceSeries series, AnalyticsQuery.AnalyticsAction action) {
        if (action == AnalyticsQuery.AnalyticsAction.ALL) return true;
        return switch (series) {
            case TARGET_ARCHIVED -> action == AnalyticsQuery.AnalyticsAction.ARCHIVE;
            case TARGET_TRASHED -> action == AnalyticsQuery.AnalyticsAction.TRASH;
            case TARGET_MARKED_READ -> action == AnalyticsQuery.AnalyticsAction.MARK_READ;
            case TARGET_UNSUB_CONFIRMED, UNSUB_ACCEPTED_OR_PENDING,
                    UNSUB_CONFIRMED, UNSUB_ELIGIBLE ->
                action == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE;
            default -> true;
        };
    }

    private String detailSourceCte(SourceSeries series) {
        if (series == SourceSeries.TARGET_ARCHIVED
                || series == SourceSeries.TARGET_TRASHED
                || series == SourceSeries.TARGET_MARKED_READ) {
            String actionType = switch (series) {
                case TARGET_ARCHIVED -> "ARCHIVE";
                case TARGET_TRASHED -> "TRASH";
                case TARGET_MARKED_READ -> "MARK_READ";
                default -> throw new IllegalStateException("Unexpected target series");
            };
            return AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
                , detail_facts AS (
                    SELECT r.account_id, r.message_key, r.completed_at AS fact_at,
                           r.action_type, 'SUCCEEDED' AS as_of_status
                      FROM resolved_targets r
                     WHERE r.operation_kind='FORWARD'
                       AND r.target_status='SUCCEEDED'
                       AND r.action_type='%s'
                       AND r.completed_at>=:start AND r.completed_at<:end
                       AND (:action='ALL' OR r.action_type=:action)
                )
                """.formatted(actionType);
        }
        if (series == SourceSeries.TARGET_UNSUB_CONFIRMED
                || series == SourceSeries.UNSUB_ACCEPTED_OR_PENDING) {
            String statuses = series == SourceSeries.TARGET_UNSUB_CONFIRMED
                ? "'CONFIRMED'" : "'ACCEPTED','VERIFICATION_PENDING'";
            String outcome = series == SourceSeries.TARGET_UNSUB_CONFIRMED
                ? "CONFIRMED" : "ACCEPTED_OR_PENDING";
            return AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
                , linked_candidates AS (
                    SELECT r.target_id, r.account_id, r.message_key,
                           MIN(event.occurred_at) AS fact_at
                      FROM resolved_targets r
                      JOIN unsubscribe_outcome_events event
                        ON event.attempt_id=r.unsubscribe_attempt_id
                       AND event.account_id=r.account_id
                     WHERE r.operation_kind='FORWARD'
                       AND r.action_type='UNSUBSCRIBE'
                       AND event.status IN (%s)
                       AND event.occurred_at>=:start AND event.occurred_at<:end
                       AND (:action IN ('ALL','UNSUBSCRIBE'))
                     GROUP BY r.target_id, r.account_id, r.message_key
                ),
                detail_facts AS (
                    SELECT account_id, message_key, fact_at,
                           'UNSUBSCRIBE' AS action_type,
                           '%s' AS as_of_status
                      FROM linked_candidates
                )
                """.formatted(statuses, outcome);
        }
        if (series == SourceSeries.INVERSE_INBOX_REMOVAL) {
            return AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
                , detail_facts AS (
                    SELECT r.account_id, r.message_key, r.completed_at AS fact_at,
                           r.action_type, 'REVERTED' AS as_of_status
                      FROM resolved_targets r
                     WHERE r.operation_kind='UNDO'
                       AND r.target_status='REVERTED'
                       AND r.action_type IN ('ARCHIVE','TRASH')
                       AND r.completed_at>=:start AND r.completed_at<:end
                       AND (:action='ALL' OR r.action_type=:action)
                )
                """;
        }
        if (series == SourceSeries.DECISION) {
            return AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
                , detail_facts AS (
                    SELECT account_id, message_key, occurred_at AS fact_at,
                           action_type, 'DECIDED' AS as_of_status
                      FROM first_decisions
                     WHERE occurred_at>=:start AND occurred_at<:end
                       AND (:action='ALL' OR action_type=:action)
                )
                """;
        }
        if (series == SourceSeries.CLEANED_TRIAGED) {
            return AnalyticsLedgerSql.firstDecisions(arrayValueExpression)
                + ", " + stripWith(AnalyticsLedgerSql.cleanedFacts(arrayValueExpression)) + """
                , detail_facts AS (
                    SELECT decision.account_id, decision.message_key,
                           decision.occurred_at AS fact_at, decision.action_type,
                           'CLEANED' AS as_of_status
                      FROM first_decisions decision
                     WHERE decision.occurred_at>=:start AND decision.occurred_at<:end
                       AND (:action='ALL' OR decision.action_type=:action)
                       AND EXISTS (
                           SELECT 1 FROM cleaned_facts cleaned
                            WHERE cleaned.account_id=decision.account_id
                              AND cleaned.message_key=decision.message_key
                              AND cleaned.occurred_at>=:start
                              AND cleaned.occurred_at<:end
                              AND (:action='ALL' OR cleaned.action_type=:action)
                       )
                )
                """;
        }
        if (series == SourceSeries.DELAY_SAMPLE
                || series.name().startsWith("DELAY_")) {
            String delayFilter = switch (series) {
                case DELAY_SAMPLE -> "";
                case DELAY_UNDER_15M -> " AND delay_seconds<900";
                case DELAY_15M_TO_1H -> " AND delay_seconds>=900 AND delay_seconds<3600";
                case DELAY_1H_TO_24H ->
                    " AND delay_seconds>=3600 AND delay_seconds<86400";
                case DELAY_24H_TO_7D ->
                    " AND delay_seconds>=86400 AND delay_seconds<604800";
                case DELAY_7D_OR_MORE -> " AND delay_seconds>=604800";
                default -> throw new IllegalStateException("Unexpected delay series");
            };
            return AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
                , eligible_delay AS (
                    SELECT decision.account_id, decision.message_key,
                           decision.occurred_at AS fact_at, decision.action_type,
                           CAST(EXTRACT(EPOCH FROM (
                               decision.occurred_at-metadata.received_at
                           )) AS BIGINT) AS delay_seconds
                      FROM first_decisions decision
                      JOIN message_metadata metadata
                        ON metadata.account_id=decision.account_id
                       AND metadata.provider_message_id=decision.message_key
                     WHERE decision.occurred_at>=:start
                       AND decision.occurred_at<:end
                       AND (:action='ALL' OR decision.action_type=:action)
                       AND metadata.received_at IS NOT NULL
                       AND decision.occurred_at>=metadata.received_at
                ),
                detail_facts AS (
                    SELECT account_id, message_key, fact_at, action_type,
                           'ELIGIBLE_DELAY' AS as_of_status
                      FROM eligible_delay
                     WHERE 1=1%s
                )
                """.formatted(delayFilter);
        }
        if (series == SourceSeries.COHORT_DECIDED) {
            return AnalyticsLedgerSql.firstDecisions(arrayValueExpression) + """
                , received_cohort AS (
                    SELECT metadata.account_id,
                           metadata.provider_message_id AS message_key
                      FROM message_metadata metadata
                     WHERE metadata.account_id IN (:account_ids)
                       AND metadata.received_at>=:start AND metadata.received_at<:end
                ),
                detail_facts AS (
                    SELECT decision.account_id, decision.message_key,
                           decision.occurred_at AS fact_at, decision.action_type,
                           'DECIDED' AS as_of_status
                      FROM first_decisions decision
                      JOIN received_cohort cohort
                        ON cohort.account_id=decision.account_id
                       AND cohort.message_key=decision.message_key
                     WHERE decision.occurred_at<:end
                       AND (:action='ALL' OR decision.action_type=:action)
                )
                """;
        }
        if (series == SourceSeries.UNSUB_CONFIRMED
                || series == SourceSeries.UNSUB_ELIGIBLE) {
            String filter = series == SourceSeries.UNSUB_CONFIRMED
                ? "confirmed=1" : "eligible=1";
            return AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
                , initiated AS (
                    SELECT r.target_id, r.account_id, r.message_key,
                           r.unsubscribe_attempt_id,
                           MIN(event.occurred_at) AS initiated_at
                      FROM resolved_targets r
                      JOIN unsubscribe_outcome_events event
                        ON event.attempt_id=r.unsubscribe_attempt_id
                       AND event.account_id=r.account_id
                     WHERE r.action_type='UNSUBSCRIBE'
                       AND r.operation_kind='FORWARD'
                     GROUP BY r.target_id, r.account_id, r.message_key,
                              r.unsubscribe_attempt_id
                    HAVING MIN(event.occurred_at)>=:start
                       AND MIN(event.occurred_at)<:end
                ),
                classified AS (
                    SELECT initiated.target_id, initiated.account_id,
                           initiated.message_key, initiated.initiated_at,
                           MAX(CASE WHEN event.status='CONFIRMED' THEN 1 ELSE 0 END)
                               AS confirmed,
                           MAX(CASE WHEN event.status IN (
                               'ACCEPTED','CONFIRMED','FAILED','VERIFICATION_PENDING'
                           ) THEN 1 ELSE 0 END) AS eligible
                      FROM initiated
                      JOIN unsubscribe_outcome_events event
                        ON event.attempt_id=initiated.unsubscribe_attempt_id
                       AND event.account_id=initiated.account_id
                       AND event.occurred_at<:end
                     GROUP BY initiated.target_id, initiated.account_id,
                              initiated.message_key, initiated.initiated_at
                ),
                detail_facts AS (
                    SELECT account_id, message_key, initiated_at AS fact_at,
                           'UNSUBSCRIBE' AS action_type,
                           CASE WHEN confirmed=1 THEN 'CONFIRMED'
                                ELSE 'ELIGIBLE_INITIATED' END AS as_of_status
                      FROM classified
                     WHERE %s
                )
                """.formatted(filter);
        }
        String filter = switch (series) {
            case OUTCOME_REQUESTED, REVIEW_EXECUTED -> "";
            case OUTCOME_ACCEPTED -> " AND accepted_as_of=1";
            case OUTCOME_SUCCEEDED -> " AND as_of_status='SUCCEEDED'";
            case OUTCOME_FAILED -> " AND as_of_status='FAILED'";
            case OUTCOME_PENDING -> " AND as_of_status='VERIFICATION_PENDING'";
            case OUTCOME_NOT_DISPATCHED -> " AND as_of_status='NOT_DISPATCHED'";
            case OUTCOME_RETAINED ->
                " AND as_of_status IN ('SUCCEEDED','FAILED','NOT_DISPATCHED')";
            case REVIEW_STAGED -> " AND execution_mode='STAGED'";
            default -> throw new IllegalArgumentException(
                "Source series is not a target-detail predicate");
        };
        return AnalyticsLedgerSql.resolvedTargets(arrayValueExpression) + """
            , reserved_candidates AS (
                SELECT r.*, event.occurred_at AS fact_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY r.target_id
                           ORDER BY event.occurred_at, event.id
                       ) AS reserved_rank
                  FROM resolved_targets r
                  JOIN action_events event
                    ON event.execution_id=r.execution_id
                   AND event.account_id=r.account_id
                   AND event.event_type='EXECUTION_RESERVED'
                 WHERE r.operation_kind IN ('FORWARD','COMPENSATION')
                   AND (:action='ALL' OR r.action_type=:action)
                   AND event.occurred_at>=:start AND event.occurred_at<:end
            ),
            requested AS (
                SELECT candidate.*,
                       CASE
                         WHEN completed_at IS NOT NULL AND completed_at<:end
                           THEN target_status
                         WHEN dispatched_at IS NOT NULL AND dispatched_at<:end
                           THEN 'VERIFICATION_PENDING'
                         ELSE 'NOT_DISPATCHED'
                       END AS as_of_status,
                       CASE WHEN accepted_at IS NOT NULL AND accepted_at<:end
                            THEN 1 ELSE 0 END AS accepted_as_of
                  FROM reserved_candidates candidate
                 WHERE reserved_rank=1
            ),
            detail_facts AS (
                SELECT * FROM requested WHERE 1=1%s
            )
            """.formatted(filter);
    }

    private static String detailSourceSelect() {
        return """
             SELECT CASE WHEN account.email IS NULL OR trim(account.email)=''
                         THEN 'Gmail account'
                         ELSE account.email || ' (Gmail)' END AS account_label,
                    metadata.received_at,
                    COALESCE(NULLIF(trim(metadata.from_display_name), ''),
                             'Unknown sender') AS sender_display,
                    NULLIF(lower(trim(metadata.from_address)), '') AS sender_address,
                    NULLIF(lower(trim(metadata.from_domain)), '') AS sender_domain,
                    detail.fact_at,
                    detail.action_type AS fact_action,
                    detail.as_of_status AS fact_outcome
               FROM detail_facts detail
               JOIN message_metadata metadata
                 ON metadata.account_id=detail.account_id
                AND metadata.provider_message_id=detail.message_key
               JOIN user_accounts account ON account.id=detail.account_id
              ORDER BY detail.fact_at, metadata.id
            """;
    }

    public List<CleanedBucketCount> cleanedCountByBuckets(
            Collection<Long> accountIds,
            List<AnalyticsQuery.DateRange> buckets,
            AnalyticsQuery.AnalyticsAction action) {
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
        FactPredicate predicate = AnalyticsLedgerSql.predicate(
            accountIds, buckets.getFirst().startInclusive(),
            buckets.getLast().endExclusive(), action.value());
        MapSqlParameterSource parameters = new MapSqlParameterSource(predicate.parameters());
        StringBuilder bucketCase = new StringBuilder("CASE");
        for (int index = 0; index < buckets.size(); index++) {
            AnalyticsQuery.DateRange bucket = buckets.get(index);
            parameters
                .addValue("bucket_start_" + index, Timestamp.from(bucket.startInclusive()))
                .addValue("bucket_end_" + index, Timestamp.from(bucket.endExclusive()));
            bucketCase.append(" WHEN occurred_at>=:bucket_start_")
                .append(index)
                .append(" AND occurred_at<:bucket_end_")
                .append(index)
                .append(" THEN ")
                .append(index);
        }
        bucketCase.append(" END");
        String sql = AnalyticsLedgerSql.cleanedCandidates(arrayValueExpression) + """
            , bucketed_candidates AS (
                SELECT candidate.*, %s AS bucket_index
                  FROM cleanup_candidates candidate
            ),
            bucketed_ranked AS (
                SELECT candidate.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY bucket_index, account_id, message_key
                           ORDER BY occurred_at, tie_id
                       ) AS cleanup_rank
                  FROM bucketed_candidates candidate
                 WHERE bucket_index IS NOT NULL
            ),
            bucketed_with_metadata AS (
                SELECT ranked.bucket_index, metadata.received_at
                  FROM bucketed_ranked ranked
                  LEFT JOIN message_metadata metadata
                    ON metadata.account_id=ranked.account_id
                   AND metadata.provider_message_id=ranked.message_key
                 WHERE ranked.cleanup_rank=1
            )
            SELECT bucket_index,
                   SUM(CASE WHEN received_at IS NOT NULL THEN 1 ELSE 0 END)
                       AS cleaned_count,
                   SUM(CASE WHEN received_at IS NULL THEN 1 ELSE 0 END)
                       AS excluded_count
              FROM bucketed_with_metadata
             GROUP BY bucket_index
             ORDER BY bucket_index
            """.formatted(bucketCase);
        Map<Integer, CleanedBucketCount> counts = jdbc.query(sql, parameters, result -> {
            LinkedHashMap<Integer, CleanedBucketCount> rows = new LinkedHashMap<>();
            while (result.next()) {
                rows.put(result.getInt("bucket_index"), new CleanedBucketCount(
                    result.getLong("cleaned_count"), result.getLong("excluded_count")));
            }
            return rows;
        });
        List<CleanedBucketCount> result = new ArrayList<>(buckets.size());
        for (int index = 0; index < buckets.size(); index++) {
            result.add(counts.getOrDefault(index, new CleanedBucketCount(0, 0)));
        }
        return List.copyOf(result);
    }

    public long cleanedMetadataExclusionCount(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        Number count = jdbc.queryForObject(
            AnalyticsLedgerSql.cleanedFacts(arrayValueExpression) + """
                SELECT COUNT(*) FROM cleaned_with_metadata
                 WHERE received_at IS NULL
                """, predicate.parameters(), Number.class);
        return count == null ? 0 : count.longValue();
    }

    public List<SourceRow> cleanedPage(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action,
            int limit,
            long offset) {
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        MapSqlParameterSource parameters = new MapSqlParameterSource(predicate.parameters())
            .addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(cleanedSourceSql()
            + " LIMIT :limit OFFSET :offset", parameters, SOURCE_ROW_MAPPER);
    }

    public Stream<SourceRow> streamCleaned(
            Collection<Long> accountIds,
            Instant start,
            Instant end,
            AnalyticsQuery.AnalyticsAction action) {
        FactPredicate predicate =
            AnalyticsLedgerSql.predicate(accountIds, start, end, action.value());
        return jdbc.queryForStream(
            cleanedSourceSql(), predicate.parameters(), SOURCE_ROW_MAPPER);
    }

    private String cleanedSourceSql() {
        return AnalyticsLedgerSql.cleanedFacts(arrayValueExpression) + """
            SELECT %s
              FROM cleaned_facts cleaned
              JOIN message_metadata metadata
                  ON metadata.id=cleaned.metadata_id
                 AND metadata.account_id=cleaned.account_id
              JOIN user_accounts account ON account.id=cleaned.account_id
             ORDER BY cleaned.occurred_at, metadata.id
            """.formatted(SAFE_SOURCE_COLUMNS);
    }

    private static FactPredicate predicate(
            Collection<Long> accountIds,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action) {
        return AnalyticsLedgerSql.predicate(
            accountIds, range.startInclusive(), range.endExclusive(), action.value());
    }

    private static String stripWith(String sql) {
        int marker = sql.indexOf("resolved_targets AS");
        int cleaned = sql.indexOf(", cleanup_candidates AS");
        if (marker < 0 || cleaned < 0) {
            throw new IllegalStateException("Unexpected analytics CTE shape");
        }
        return sql.substring(cleaned + 2);
    }

    private static final RowMapper<SourceRow> SOURCE_ROW_MAPPER = (row, index) ->
        new SourceRow(
            row.getString("account_label"),
            row.getTimestamp("received_at").toInstant(),
            row.getString("sender_display"),
            row.getString("sender_address"),
            row.getString("sender_domain"),
            null,
            row.getTimestamp("fact_at").toInstant(),
            row.getString("fact_action"),
            "SUCCEEDED");

    private static final RowMapper<SourceRow> DETAIL_SOURCE_ROW_MAPPER = (row, index) ->
        new SourceRow(
            row.getString("account_label"),
            row.getTimestamp("received_at").toInstant(),
            row.getString("sender_display"),
            row.getString("sender_address"),
            row.getString("sender_domain"),
            null,
            row.getTimestamp("fact_at").toInstant(),
            row.getString("fact_action"),
            row.getString("fact_outcome"));

    public record LedgerSnapshot(
        long triaged,
        long archived,
        long trashed,
        long markedRead,
        long unsubscribed,
        long unsubscribeAcceptedOrPending,
        long inverseSucceeded
    ) {
        LedgerSnapshot intersect(AnalyticsQuery.AnalyticsAction action) {
            if (action == AnalyticsQuery.AnalyticsAction.ALL) return this;
            return new LedgerSnapshot(
                triaged,
                action == AnalyticsQuery.AnalyticsAction.ARCHIVE ? archived : 0,
                action == AnalyticsQuery.AnalyticsAction.TRASH ? trashed : 0,
                action == AnalyticsQuery.AnalyticsAction.MARK_READ ? markedRead : 0,
                action == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE ? unsubscribed : 0,
                action == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE
                    ? unsubscribeAcceptedOrPending : 0,
                action == AnalyticsQuery.AnalyticsAction.ARCHIVE
                        || action == AnalyticsQuery.AnalyticsAction.TRASH
                    ? inverseSucceeded : 0);
        }

        public long forwardInboxRemoval() {
            return Math.addExact(archived, trashed);
        }
    }

    public record CohortCounts(
        long received,
        long decidedReceived,
        long triaged,
        long cleanedTriaged
    ) {
    }

    public record ReviewCounts(long staged, long executed) {
    }

    public record UnsubscribeCounts(long confirmed, long eligible, long manualOnly) {
    }

    public record CleanedBucketCount(long count, long metadataExcluded) {
        public CleanedBucketCount {
            if (count < 0 || metadataExcluded < 0) {
                throw new IllegalArgumentException("Cleaned bucket counts cannot be negative");
            }
        }
    }
}
