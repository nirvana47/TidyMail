package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.AnalyticsSourceQuery.SourceSeries;
import com.tidymail.dto.AnalyticsQuery.AnalyticsAction;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.repository.AnalyticsCoverageRepository;
import com.tidymail.repository.AnalyticsLedgerRepository;
import com.tidymail.repository.AnalyticsReceivedRepository;
import com.tidymail.service.WorkspaceModelService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase13PerformanceTest {
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(400L * 86_400L);
    private static final String MIGRATIONS =
        "filesystem:src/main/resources/db/migration";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<SourceSeries> DELAY_BUCKETS = List.of(
        SourceSeries.DELAY_UNDER_15M,
        SourceSeries.DELAY_15M_TO_1H,
        SourceSeries.DELAY_1H_TO_24H,
        SourceSeries.DELAY_24H_TO_7D,
        SourceSeries.DELAY_7D_OR_MORE);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void representativeAnalyticsAreCorrectIndexedAndWithinProductBudgets()
            throws Exception {
        Flyway database = flyway();
        database.clean();
        assertThat(database.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = spy(new NamedParameterJdbcTemplate(dataSource));
        AnalyticsReceivedRepository received = new AnalyticsReceivedRepository(named);
        AnalyticsLedgerRepository ledger = new AnalyticsLedgerRepository(named);
        AnalyticsCoverageRepository coverage = new AnalyticsCoverageRepository(named);

        long ownedOne = account(jdbc, "performance-one@example.com");
        long ownedTwo = account(jdbc, "performance-two@example.com");
        long ownedStale = account(jdbc, "performance-stale@example.com");
        long ownedNeverStarted = account(jdbc, "performance-never@example.com");
        long foreign = account(jdbc, "performance-foreign@example.com");
        seedMetadata(jdbc, ownedOne, ownedTwo, foreign);
        seedLedger(jdbc, ownedOne);
        seedRepresentativeLedgerStates(jdbc, ownedOne);
        seedUnsubscribeStates(jdbc, ownedOne);
        seedSyncStates(
            jdbc, ownedOne, ownedTwo, ownedStale, ownedNeverStarted, foreign);
        jdbc.execute("ANALYZE");

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM message_metadata", Long.class)).isEqualTo(10_503);
        long utcDates = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT received_at::date)
              FROM message_metadata
             WHERE account_id IN (?,?) AND received_at IS NOT NULL
            """, Long.class, ownedOne, ownedTwo);
        assertThat(utcDates).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM message_metadata
             WHERE account_id IN (?,?) AND received_at IS NOT NULL
            """, Long.class, ownedOne, ownedTwo)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM message_metadata
             WHERE account_id=? AND received_at IS NOT NULL
            """, Long.class, foreign)).isEqualTo(500);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM action_execution_targets",
            Long.class)).isEqualTo(1_001);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(DISTINCT status) FROM unsubscribe_outcome_events
            """, Long.class)).isEqualTo(5);
        assertRepresentativeStates(
            jdbc, coverage, ownedOne, ownedStale, ownedNeverStarted);

        var receivedFact =
            received.receivedFact(List.of(ownedOne, ownedTwo), START, END);
        assertThat(receivedFact.count()).isEqualTo(10_000);
        assertThat(receivedFact.page(1, 0).getFirst())
            .extracting(
                row -> row.accountLabel(),
                row -> row.senderDisplay())
            .containsExactly("performance-one@example.com (Gmail)", "Sender 0");
        assertThat(receivedFact.page(1, 9_999).getFirst().senderDisplay())
            .isEqualTo("Boundary last");
        assertThat(receivedFact.page(25, 0))
            .noneMatch(row -> row.accountLabel().contains("foreign"));

        DateRange range = new DateRange(START, END);
        var snapshot = ledger.snapshot(
            List.of(ownedOne, ownedTwo), range, AnalyticsAction.ALL);
        var outcomes = ledger.outcomes(
            List.of(ownedOne, ownedTwo), range, AnalyticsAction.ALL);
        assertThat(snapshot.triaged()).isEqualTo(1_000);
        assertThat(snapshot.archived()).isEqualTo(332);
        assertThat(snapshot.trashed()).isEqualTo(333);
        assertThat(snapshot.markedRead()).isEqualTo(332);
        assertThat(snapshot.inverseSucceeded()).isOne();
        assertThat(outcomes.requested()).isEqualTo(1_000);
        assertThat(outcomes.succeeded()).isEqualTo(997);
        assertThat(outcomes.failed()).isOne();
        assertThat(outcomes.verificationPending()).isOne();
        assertThat(outcomes.notDispatched()).isOne();
        assertThat(ledger.cleanedCount(
            List.of(ownedOne, ownedTwo), START, END, AnalyticsAction.ALL))
            .isEqualTo(997);
        assertThat(ledger.cleanedPage(
            List.of(ownedOne, ownedTwo), START, END, AnalyticsAction.ALL, 25, 0))
            .hasSize(25);
        assertThat(ledger.eligibleDelaySeconds(
            List.of(ownedOne, ownedTwo), range, AnalyticsAction.ALL))
            .hasSize(1_000);
        assertThat(ledger.medianDelaySeconds(
            List.of(ownedOne, ownedTwo), range, AnalyticsAction.ALL))
            .isNotNull();
        assertThat(coverage.topSenders(
            List.of(ownedOne, ownedTwo), range, 5)).hasSize(5);

        Map<String, TimingStats> timings;
        try (ConfigurableApplicationContext context = applicationContext()) {
            timings = measureProductOperations(
                ledger, receivedFact, coverage,
                context.getBean(WorkspaceModelService.class),
                ownedOne, ownedTwo, range);
        }
        assertThat(timings.get("workspace").max()).isLessThan(Duration.ofSeconds(1));
        assertThat(timings.entrySet())
            .filteredOn(entry -> !entry.getKey().equals("workspace"))
            .allMatch(entry -> entry.getValue().max().compareTo(Duration.ofSeconds(2)) < 0);

        DateRange selective = new DateRange(START, START.plusSeconds(2L * 86_400L));
        Map<String, JsonNode> plans = productionPlans(
            named, received, ledger, coverage, List.of(ownedOne), selective);
        assertProductionPlanEvidence(plans);
        assertThat(jdbc.queryForObject("SHOW enable_seqscan", String.class)).isEqualTo("on");

        System.out.printf(
            "PHASE13_PERFORMANCE postgres=%s rows=10503 dates=%d %s%n",
            POSTGRES.getDockerImageName(), utcDates, timingSummary(timings));
        System.out.printf(
            "PHASE13_EXPLAIN production_sql=%d natural_seqscan=on families=%s%n",
            plans.size(), String.join(",", plans.keySet()));
    }

    private static void seedMetadata(
            JdbcTemplate jdbc, long first, long second, long foreign) {
        insertMetadataSeries(jdbc, first, "a", 5_999, 0);
        insertMetadataSeries(jdbc, second, "b", 3_999, 5_999);
        insertMetadataSeries(jdbc, foreign, "foreign", 500, 20_000);
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id,provider,provider_message_id,provider_thread_id,
                received_at,from_display_name,from_address,from_domain,subject_header
            ) VALUES
              (?,'google','perf-boundary-first','thread-boundary-first',?,
               'Boundary first','boundary-first@example.com','example.com',
               'PRIVACY-SUBJECT-CANARY'),
              (?,'google','perf-boundary-last','thread-boundary-last',?,
               'Boundary last','boundary-last@example.com','example.com',
               'PRIVACY-SUBJECT-CANARY')
            """, first, java.sql.Timestamp.from(START),
            second, java.sql.Timestamp.from(END.minusMillis(1)));
        for (long account : List.of(first, second, foreign)) {
            jdbc.update("""
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,provider_thread_id,
                    received_at,from_display_name,from_address,from_domain,subject_header
                ) VALUES (?,'google',?, ?,NULL,'Unknown',NULL,NULL,
                          'NULL-RECEIPT-PRIVACY-CANARY')
                """, account, "null-" + account, "null-thread-" + account);
        }
    }

    private static void insertMetadataSeries(
            JdbcTemplate jdbc, long accountId, String prefix, int count, int offset) {
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id,provider,provider_message_id,provider_thread_id,
                received_at,from_display_name,from_address,from_domain,subject_header
            )
            SELECT ?,'google',
                   'perf-' || ? || '-' || value,
                   'thread-' || ? || '-' || value,
                   TIMESTAMPTZ '2025-01-01 00:00:00+00'
                     + (((value + ?) % 400) || ' days')::interval
                     + ((value % 300) || ' seconds')::interval,
                   CASE WHEN value % 997=0 THEN '=Formula sender'
                        WHEN value % 991=0 THEN repeat('Long sender ',20)
                        ELSE 'Sender ' || ((value + ?) % 200) END,
                   CASE WHEN value % 983=0 THEN NULL
                        ELSE 'sender' || ((value + ?) % 200) || '@example.com' END,
                   CASE WHEN value % 983=0 THEN NULL ELSE 'example.com' END,
                   'PRIVACY-SUBJECT-CANARY'
              FROM generate_series(1,?) value
            """, accountId, prefix, prefix, offset, offset, offset, count);
    }

    private static void seedLedger(JdbcTemplate jdbc, long accountId) {
        jdbc.update("""
            INSERT INTO action_scopes(
                account_id,action_type,target_kind,message_ids,scope_hash,
                message_count,target_summary,risk_summary,created_at
            )
            SELECT ?,
                   CASE value % 3 WHEN 0 THEN 'ARCHIVE'
                        WHEN 1 THEN 'TRASH' ELSE 'MARK_READ' END,
                   'ANALYTICS_PERFORMANCE',
                   ARRAY['perf-a-' || value],
                   md5('scope-' || value) || md5('scope-' || value),
                   1,'{}','{}',
                   TIMESTAMPTZ '2025-01-01 01:00:00+00'
                     + ((value % 400) || ' days')::interval
              FROM generate_series(1,1000) value
            """, accountId);
        jdbc.update("""
            INSERT INTO action_scope_targets(
                scope_id,account_id,scope_ordinal,target_fingerprint
            )
            SELECT id,account_id,0,
                   md5(message_ids[1]) || md5(message_ids[1])
              FROM action_scopes WHERE account_id=?
            """, accountId);
        jdbc.update("""
            INSERT INTO user_actions(
                account_id,action_scope_id,action_type,message_ids,
                inverse_action_data,target_kind,target_id_json,affected_count,
                status,execution_mode,decision_source,created_at,completed_at
            )
            SELECT account_id,id,action_type,message_ids,'{}',target_kind,'{}',1,
                   'APPLIED',
                   CASE WHEN id % 2=0 THEN 'STAGED' ELSE 'IMMEDIATE' END,
                   'UNREAD',created_at,created_at + interval '2 seconds'
              FROM action_scopes WHERE account_id=?
            """, accountId);
        jdbc.update("""
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,reserved_at,completed_at
            )
            SELECT account_id,id,'FORWARD','APPLIED',
                   md5('execution-' || id) || md5('execution-' || id),
                   1,1,1,0,0,0,created_at + interval '1 second',
                   created_at + interval '2 seconds'
              FROM user_actions WHERE account_id=?
            """, accountId);
        jdbc.update("""
            INSERT INTO action_execution_targets(
                execution_id,account_id,scope_id,scope_ordinal,target_fingerprint,
                dispatch_state,status,accepted_at,dispatched_at,completed_at
            )
            SELECT execution.id,execution.account_id,scope.id,0,
                   target.target_fingerprint,'DISPATCHED','SUCCEEDED',
                   execution.reserved_at,execution.reserved_at,execution.completed_at
              FROM action_executions execution
              JOIN user_actions action ON action.id=execution.action_id
              JOIN action_scopes scope ON scope.id=action.action_scope_id
              JOIN action_scope_targets target
                ON target.scope_id=scope.id AND target.scope_ordinal=0
             WHERE execution.account_id=?
            """, accountId);
        jdbc.update("""
            INSERT INTO action_events(
                account_id,action_id,execution_id,event_type,action_type,
                execution_mode,decision_source,resulting_status,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,occurred_at
            )
            SELECT action.account_id,action.id,NULL,'ACTION_QUEUED',action.action_type,
                   action.execution_mode,action.decision_source,'QUEUED',
                   1,0,0,0,0,1,action.created_at
              FROM user_actions action WHERE action.account_id=?
            UNION ALL
            SELECT action.account_id,action.id,execution.id,'EXECUTION_RESERVED',
                   action.action_type,action.execution_mode,action.decision_source,
                   'APPLIED',1,1,1,0,0,0,execution.reserved_at
              FROM user_actions action
              JOIN action_executions execution ON execution.action_id=action.id
             WHERE action.account_id=?
            """, accountId, accountId);
    }

    private static void seedRepresentativeLedgerStates(JdbcTemplate jdbc, long accountId) {
        for (String actionType : List.of("ARCHIVE", "TRASH", "MARK_READ")) {
            Long targetId = jdbc.queryForObject("""
                SELECT target.id
                  FROM action_execution_targets target
                  JOIN action_executions execution ON execution.id=target.execution_id
                  JOIN user_actions action ON action.id=execution.action_id
                 WHERE target.account_id=? AND action.action_type=?
                 ORDER BY target.id LIMIT 1
                """, Long.class, accountId, actionType);
            String targetStatus = switch (actionType) {
                case "ARCHIVE" -> "FAILED";
                case "TRASH" -> "VERIFICATION_PENDING";
                case "MARK_READ" -> "PENDING";
                default -> throw new IllegalStateException();
            };
            String dispatch = actionType.equals("MARK_READ") ? "NOT_DISPATCHED" : "DISPATCHED";
            jdbc.update("""
                UPDATE action_execution_targets
                   SET status=?, dispatch_state=?,
                       accepted_at=CASE WHEN ?='NOT_DISPATCHED' THEN NULL ELSE accepted_at END,
                       dispatched_at=CASE WHEN ?='NOT_DISPATCHED' THEN NULL ELSE dispatched_at END,
                       completed_at=CASE WHEN ? IN ('PENDING','VERIFICATION_PENDING')
                                         THEN NULL ELSE completed_at END,
                        reason_code=CASE WHEN ?='FAILED' THEN 'PROVIDER_REJECTED'
                                         ELSE NULL END
                 WHERE id=?
                """, targetStatus, dispatch, dispatch, dispatch, targetStatus, targetStatus,
                targetId);
            jdbc.update("""
                UPDATE action_executions
                   SET status='PARTIAL',
                       accepted_count=CASE WHEN ?='NOT_DISPATCHED' THEN 0 ELSE 1 END,
                       succeeded_count=0,
                       failed_count=CASE WHEN ?='FAILED' THEN 1 ELSE 0 END,
                       verification_pending_count=
                           CASE WHEN ?='VERIFICATION_PENDING' THEN 1 ELSE 0 END,
                       not_dispatched_count=CASE WHEN ?='NOT_DISPATCHED' THEN 1 ELSE 0 END
                 WHERE id=(SELECT execution_id FROM action_execution_targets WHERE id=?)
                """, dispatch, targetStatus, targetStatus, dispatch, targetId);
        }

        Long duplicateAction = jdbc.queryForObject("""
            SELECT id FROM user_actions WHERE account_id=? ORDER BY id LIMIT 1
            """, Long.class, accountId);
        jdbc.update("""
            INSERT INTO action_events(
                account_id,action_id,event_type,action_type,execution_mode,
                decision_source,resulting_status,requested_count,accepted_count,
                succeeded_count,failed_count,verification_pending_count,
                not_dispatched_count,occurred_at
            )
            SELECT account_id,id,'ACTION_QUEUED',action_type,execution_mode,
                   decision_source,'QUEUED',1,0,0,0,0,1,created_at + interval '1 second'
              FROM user_actions WHERE id=?
            """, duplicateAction);

        Long undoAction = jdbc.queryForObject("""
            SELECT action.id
              FROM user_actions action
              JOIN action_executions execution ON execution.action_id=action.id
              JOIN action_execution_targets target ON target.execution_id=execution.id
             WHERE action.account_id=? AND action.action_type='ARCHIVE'
               AND target.status='SUCCEEDED'
             ORDER BY action.id LIMIT 1
            """, Long.class, accountId);
        Long undoExecution = jdbc.queryForObject("""
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,reserved_at,completed_at
            )
            SELECT account_id,id,'UNDO','APPLIED',
                   md5('undo-' || id) || md5('undo-' || id),
                   1,1,1,0,0,0,created_at + interval '3 seconds',
                   created_at + interval '4 seconds'
              FROM user_actions WHERE id=?
            RETURNING id
            """, Long.class, undoAction);
        jdbc.update("""
            INSERT INTO action_execution_targets(
                execution_id,account_id,scope_id,scope_ordinal,target_fingerprint,
                dispatch_state,status,accepted_at,dispatched_at,completed_at
            )
            SELECT ?,action.account_id,scope.id,0,target.target_fingerprint,
                   'DISPATCHED','REVERTED',execution.reserved_at,
                   execution.reserved_at,execution.completed_at
              FROM user_actions action
              JOIN action_scopes scope ON scope.id=action.action_scope_id
              JOIN action_scope_targets target
                ON target.scope_id=scope.id AND target.scope_ordinal=0
              JOIN action_executions execution ON execution.id=?
             WHERE action.id=?
            """, undoExecution, undoExecution, undoAction);

        jdbc.update("""
            INSERT INTO user_actions(
                account_id,action_type,message_ids,inverse_action_data,target_kind,
                target_id_json,affected_count,status,execution_mode,decision_source,
                created_at,completed_at
            ) VALUES (
                ?,'ARCHIVE',ARRAY['legacy-private-id'],'{}','LEGACY','{}',1,
                'APPLIED','STAGED','LEGACY',?,?
            )
            """, accountId, Timestamp.from(START.plusSeconds(30)),
            Timestamp.from(START.plusSeconds(31)));
    }

    private static void seedUnsubscribeStates(JdbcTemplate jdbc, long accountId) {
        List<String> statuses = List.of(
            "ACCEPTED", "CONFIRMED", "FAILED", "MANUAL_REQUIRED",
            "VERIFICATION_PENDING");
        for (int index = 0; index < statuses.size(); index++) {
            long attempt = jdbc.queryForObject("""
                INSERT INTO unsubscribe_attempts(
                    account_id,candidate_fingerprint,sender_key_fingerprint,
                    method,latest_status,created_at
                ) VALUES (?, ?, ?, 'MANUAL', ?, ?)
                RETURNING id
                """, Long.class, accountId, hex("candidate-" + index),
                hex("sender-" + index), statuses.get(index),
                java.sql.Timestamp.from(START.plusSeconds(index)));
            jdbc.update("""
                INSERT INTO unsubscribe_outcome_events(
                    account_id,attempt_id,status,occurred_at
                ) VALUES (?,?,?,?)
                """, accountId, attempt, statuses.get(index),
                java.sql.Timestamp.from(START.plusSeconds(index + 1L)));
        }
    }

    private static void seedSyncStates(
            JdbcTemplate jdbc,
            long first,
            long second,
            long stale,
            long neverStarted,
            long foreign) {
        Object[][] states = {
            {first, "INCREMENTAL_SYNC", "COMPLETE", START.plusSeconds(10)},
            {first, "METADATA_SYNC", "RUNNING", null},
            {second, "INCREMENTAL_SYNC", "PARTIAL", START.plusSeconds(20)},
            {second, "METADATA_SYNC", "FAILED", null},
            {stale, "INCREMENTAL_SYNC", "COMPLETE", START},
            {foreign, "INCREMENTAL_SYNC", "QUEUED", null}
        };
        for (Object[] state : states) {
            jdbc.update("""
                INSERT INTO sync_progress(
                    account_id,job_type,status,started_at,last_successful_at
                ) VALUES (?,?,?,?,?)
                """, state[0], state[1], state[2],
                java.sql.Timestamp.from(START),
                state[3] == null ? null : java.sql.Timestamp.from((Instant) state[3]));
        }
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sync_progress WHERE account_id=?",
            Long.class, neverStarted)).isZero();
    }

    private static void assertRepresentativeStates(
            JdbcTemplate jdbc,
            AnalyticsCoverageRepository coverage,
            long accountId,
            long staleAccount,
            long neverStartedAccount) {
        assertThat(jdbc.queryForList("""
            SELECT status FROM action_execution_targets
             WHERE account_id=? GROUP BY status ORDER BY status
            """, String.class, accountId))
            .contains("FAILED", "PENDING", "REVERTED", "SUCCEEDED", "VERIFICATION_PENDING");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM action_execution_targets
             WHERE account_id=? AND dispatch_state='NOT_DISPATCHED'
            """, Long.class, accountId)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM action_events
             WHERE account_id=? AND event_type='ACTION_QUEUED'
            """, Long.class, accountId)).isEqualTo(1_001);
        DateRange range = new DateRange(START, END);
        assertThat(coverage.legacyGapCount(
            List.of(accountId), range, AnalyticsAction.ALL)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sync_progress
             WHERE account_id=? AND status='COMPLETE' AND last_successful_at=?
            """, Long.class, staleAccount, Timestamp.from(START))).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sync_progress WHERE account_id=?
            """, Long.class, neverStartedAccount)).isZero();
    }

    private static Map<String, TimingStats> measureProductOperations(
            AnalyticsLedgerRepository ledger,
            AnalyticsReceivedRepository.ReceivedFact received,
            AnalyticsCoverageRepository coverage,
            WorkspaceModelService workspace,
            long ownedOne,
            long ownedTwo,
            DateRange range) {
        List<Long> accounts = List.of(ownedOne, ownedTwo);
        LinkedHashMap<String, Runnable> operations = new LinkedHashMap<>();
        operations.put("workspace", () -> workspace.build(
            WorkspaceRoute.ANALYTICS, String.valueOf(ownedOne),
            auth("performance-one@example.com")));
        operations.put("aggregate",
            () -> ledger.snapshot(accounts, range, AnalyticsAction.ALL));
        operations.put("outcomes",
            () -> ledger.outcomes(accounts, range, AnalyticsAction.ALL));
        operations.put("received_count", received::count);
        operations.put("received_page", () -> received.page(25, 0));
        operations.put("cleaned_count",
            () -> ledger.cleanedCount(accounts, START, END, AnalyticsAction.ALL));
        operations.put("cleaned_page",
            () -> ledger.cleanedPage(accounts, START, END, AnalyticsAction.ALL, 25, 0));
        operations.put("top_sender", () -> coverage.topSenders(accounts, range, 5));
        operations.put("median_distribution", () -> {
            ledger.medianDelaySeconds(accounts, range, AnalyticsAction.ALL);
            for (SourceSeries bucket : DELAY_BUCKETS) {
                ledger.detailCount(accounts, START, END, AnalyticsAction.ALL, bucket);
            }
        });
        LinkedHashMap<String, TimingStats> result = new LinkedHashMap<>();
        operations.forEach((name, operation) ->
            result.put(name, timedObservations(operation, 2, 5)));
        return Map.copyOf(result);
    }

    private static ConfigurableApplicationContext applicationContext() {
        return new SpringApplicationBuilder(InboxCleanerApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.task.scheduling.enabled=false",
                "--TOKEN_ENCRYPT_SECRET="
                    + "0000000000000000000000000000000000000000000000000000000000000000",
                "--TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
                "--GOOGLE_CLIENT_ID=",
                "--GOOGLE_CLIENT_SECRET=",
                "--MICROSOFT_CLIENT_ID=",
                "--MICROSOFT_CLIENT_SECRET=");
    }

    private static Map<String, JsonNode> productionPlans(
            NamedParameterJdbcTemplate named,
            AnalyticsReceivedRepository received,
            AnalyticsLedgerRepository ledger,
            AnalyticsCoverageRepository coverage,
            List<Long> accounts,
            DateRange range) throws Exception {
        LinkedHashMap<String, JsonNode> plans = new LinkedHashMap<>();
        capturePlan(named, plans, "received_trend",
            () -> received.countByBuckets(accounts, List.of(range)));
        capturePlan(named, plans, "received_source",
            () -> received.receivedFact(
                accounts, range.startInclusive(), range.endExclusive()).page(25, 0));
        capturePlan(named, plans, "cleaned_trend",
            () -> ledger.cleanedCountByBuckets(accounts, List.of(range), AnalyticsAction.ALL));
        capturePlan(named, plans, "cleaned_source",
            () -> ledger.cleanedPage(
                accounts, range.startInclusive(), range.endExclusive(),
                AnalyticsAction.ALL, 25, 0));
        capturePlan(named, plans, "outcomes",
            () -> ledger.outcomes(accounts, range, AnalyticsAction.ALL));
        capturePlan(named, plans, "unsubscribe",
            () -> ledger.unsubscribeCounts(accounts, range));
        capturePlan(named, plans, "top_sender",
            () -> coverage.topSenders(accounts, range, 5));
        capturePlan(named, plans, "median",
            () -> ledger.medianDelaySeconds(accounts, range, AnalyticsAction.ALL));
        for (SourceSeries bucket : DELAY_BUCKETS) {
            capturePlan(named, plans, "delay_" + bucket.name().toLowerCase(),
                () -> ledger.detailCount(
                    accounts, range.startInclusive(), range.endExclusive(),
                    AnalyticsAction.ALL, bucket));
        }
        return Map.copyOf(plans);
    }

    private static void capturePlan(
            NamedParameterJdbcTemplate named,
            Map<String, JsonNode> plans,
            String name,
            Runnable operation) throws Exception {
        clearInvocations(named);
        operation.run();
        CapturedQuery query = capturedProductionQuery(named);
        String json = named.queryForObject(
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query.sql(),
            query.parameters(), String.class);
        plans.put(name, JSON.readTree(json).get(0).get("Plan"));
    }

    private static CapturedQuery capturedProductionQuery(NamedParameterJdbcTemplate named) {
        List<Invocation> invocations =
            new ArrayList<>(mockingDetails(named).getInvocations());
        for (int index = invocations.size() - 1; index >= 0; index--) {
            Object[] arguments = invocations.get(index).getArguments();
            if (arguments.length < 2
                    || !(arguments[0] instanceof String sql)
                    || sql.startsWith("EXPLAIN ")) {
                continue;
            }
            Object raw = arguments[1];
            SqlParameterSource parameters;
            if (raw instanceof SqlParameterSource source) {
                parameters = source;
            } else if (raw instanceof Map<?, ?> values) {
                MapSqlParameterSource source = new MapSqlParameterSource();
                values.forEach((key, value) -> source.addValue(String.valueOf(key), value));
                parameters = source;
            } else {
                continue;
            }
            return new CapturedQuery(sql, parameters);
        }
        throw new AssertionError("No production repository SQL was captured");
    }

    private static void assertProductionPlanEvidence(Map<String, JsonNode> plans) {
        assertThat(plans).hasSize(13);
        for (Map.Entry<String, JsonNode> entry : plans.entrySet()) {
            List<JsonNode> nodes = planNodes(entry.getValue());
            assertThat(nodes).as(entry.getKey() + " has runtime evidence")
                .anyMatch(node -> node.has("Actual Total Time"));
            assertThat(nodes).as(entry.getKey() + " is account bounded")
                .anyMatch(node -> predicate(node).contains("account_id"));
            assertThat(nodes).as(entry.getKey() + " is naturally planned")
                .noneMatch(node -> "Seq Scan".equals(node.path("Node Type").asText())
                    && largeFact(node.path("Relation Name").asText())
                    && predicate(node).isBlank());
        }
        for (String name : List.of("received_trend", "received_source", "top_sender")) {
            assertRelationPredicate(
                plans.get(name), "message_metadata",
                "idx_message_metadata_account_received", "account_id", "received_at");
        }
        for (String name : List.of("cleaned_trend", "cleaned_source", "outcomes")) {
            assertThat(planNodes(plans.get(name)))
                .as(name + " reaches immutable target ledger")
                .anyMatch(node -> "action_execution_targets"
                    .equals(node.path("Relation Name").asText()));
            assertThat(planText(plans.get(name)))
                .contains("account_id")
                .containsAnyOf("completed_at", "occurred_at");
        }
        List<JsonNode> unsubscribeScans = planNodes(plans.get("unsubscribe")).stream()
            .filter(node -> "unsubscribe_outcome_events"
                .equals(node.path("Relation Name").asText()))
            .toList();
        assertThat(unsubscribeScans).isNotEmpty();
        assertThat(unsubscribeScans)
            .anyMatch(node -> predicate(node).contains("account_id")
                && node.path("Index Name").asText().contains("attempt"));
        assertThat(unsubscribeScans)
            .anyMatch(node -> predicate(node).contains("occurred_at"));
        for (String name : plans.keySet().stream()
                .filter(value -> value.equals("median") || value.startsWith("delay_"))
                .toList()) {
            assertThat(planText(plans.get(name)))
                .as(name + " uses decision fact time and metadata receipt time")
                .contains("occurred_at", "received_at", "account_id");
        }
    }

    private static void assertRelationPredicate(
            JsonNode plan,
            String relation,
            String index,
            String... predicates) {
        List<JsonNode> scans = planNodes(plan).stream()
            .filter(node -> relation.equals(node.path("Relation Name").asText()))
            .toList();
        assertThat(scans).as(relation + " scan exists").isNotEmpty();
        assertThat(scans).anySatisfy(node -> {
            String predicate = predicate(node);
            assertThat(predicate).contains(predicates);
            if (index != null) {
                assertThat(node.path("Index Name").asText()).isEqualTo(index);
            }
        });
    }

    private static List<JsonNode> planNodes(JsonNode root) {
        ArrayList<JsonNode> nodes = new ArrayList<>();
        collectPlanNodes(root, nodes);
        return List.copyOf(nodes);
    }

    private static void collectPlanNodes(JsonNode node, List<JsonNode> nodes) {
        if (node == null || node.isMissingNode()) return;
        nodes.add(node);
        node.path("Plans").forEach(child -> collectPlanNodes(child, nodes));
    }

    private static String predicate(JsonNode node) {
        return String.join(" ",
            node.path("Index Cond").asText(),
            node.path("Filter").asText(),
            node.path("Recheck Cond").asText(),
            node.path("Hash Cond").asText(),
            node.path("Join Filter").asText());
    }

    private static String planText(JsonNode plan) {
        return plan.toString();
    }

    private static boolean largeFact(String relation) {
        return relation.equals("message_metadata")
            || relation.equals("action_events")
            || relation.equals("action_executions")
            || relation.equals("action_execution_targets");
    }

    private static TimingStats timedObservations(Runnable work, int warmups, int observations) {
        for (int index = 0; index < warmups; index++) work.run();
        ArrayList<Duration> values = new ArrayList<>();
        for (int index = 0; index < observations; index++) {
            long started = System.nanoTime();
            work.run();
            values.add(Duration.ofNanos(System.nanoTime() - started));
        }
        values.sort(Comparator.naturalOrder());
        return new TimingStats(
            List.copyOf(values), values.get(values.size() / 2), values.getLast());
    }

    private static String timingSummary(Map<String, TimingStats> timings) {
        return timings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> "%s_p50_ms=%d %s_max_ms=%d".formatted(
                entry.getKey(), entry.getValue().median().toMillis(),
                entry.getKey(), entry.getValue().max().toMillis()))
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = Map.of(
            "email", email, "name", "Performance Owner", "sub", "performance-owner");
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(
            new DefaultOAuth2User(authorities, attributes, "sub"),
            authorities, "google");
    }

    private static long account(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("""
            INSERT INTO user_accounts(
                email,provider,provider_account_id,owner_key,encrypted_access_token,
                encrypted_refresh_token,scopes
            ) VALUES (
                ?,'google',?,?,decode('01','hex'),decode('02','hex'),ARRAY['email']
            )
            RETURNING id
            """, Long.class, email, "provider-" + UUID.randomUUID(),
            email.toLowerCase(java.util.Locale.ROOT) + ":google");
    }

    private static String hex(String value) {
        String hash = Integer.toHexString(value.hashCode()).replace("-", "0");
        return (hash + "0".repeat(64)).substring(0, 64);
    }

    private static Flyway flyway() {
        return Flyway.configure()
            .cleanDisabled(false)
            .dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .load();
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record CapturedQuery(String sql, SqlParameterSource parameters) {
    }

    private record TimingStats(
        List<Duration> observations,
        Duration median,
        Duration max
    ) {
    }

}
