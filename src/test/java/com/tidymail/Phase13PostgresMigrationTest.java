package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import com.tidymail.dto.AnalyticsQuery.AnalyticsAction;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import com.tidymail.repository.AnalyticsCoverageRepository;
import com.tidymail.repository.AnalyticsLedgerRepository;
import com.tidymail.repository.AnalyticsReceivedRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase13PostgresMigrationTest {
    private static final String MIGRATIONS =
        "filesystem:src/main/resources/db/migration";
    private static final Map<Integer, String> PRODUCTION_HASHES = hashes(
        "ab67ba33bbd733ec00dfe1a9f816226f6e0220994b23660bafa462a80e78b418",
        "445c0a0854bf679b85416985fea97b3a9184e0df817c9bc3c325955350c9ddd6",
        "2caa13f0770ee3111b03df19256b36d3825a8aa1eafcd60a03970f54d1c571a1",
        "4d851ef2470654129f6c8c1e11a5d27d3e403fdab177a5ad5eea69e13823cd5c",
        "8e3301300edee870d4c31eafdada4da8401510acacddcd129f49f33b30d259fb",
        "9d59cdc1a0f22c0df01407df7754d9fec92cbcc2eb59be131516fbdcc3c1d116",
        "8b04c928007a9b90abbc8dbe9502f5227d295c13c21f47bc4f02ee901a14fb5c",
        "190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461",
        "c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379",
        "97323832aaa62a5bcabee502d99f97eb532c1287537eb942d0f739eb10776322");
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void productionCleanV1ToV13FreezesHistoryValidatesHibernateAndCatalog()
            throws Exception {
        assertFrozenMigrations("src/main/resources/db/migration", PRODUCTION_HASHES);

        Flyway full = flyway(null);
        full.clean();
        assertThat(full.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        try (Connection connection = connection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(strings(connection, "SHOW server_version")).singleElement()
                .asString().startsWith("16.");
            assertThat(strings(connection, """
                SELECT version::text || ':' || success::text
                  FROM flyway_schema_history
                 WHERE type='SQL'
                 ORDER BY installed_rank
                """)).startsWith(
                    "1:true", "2:true", "3:true", "4:true", "5:true",
                    "6:true", "7:true", "8:true", "9:true", "10:true",
                    "11:true", "12:true", "13:true");
            assertThat(strings(connection, """
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE table_schema='public'
                """)).contains(
                    "action_executions_counts_check",
                    "fk_action_execution_targets_scope_target",
                    "fk_action_events_execution_action_account",
                    "uq_action_executions_action_operation",
                    "uq_action_executions_account_idempotency",
                    "uq_action_execution_targets_execution_ordinal");
            assertThat(strings(connection, """
                SELECT indexname || '=' || indexdef
                  FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname LIKE '%_analytics_fact'
                 ORDER BY indexname
                """)).containsExactly(
                    "idx_action_events_analytics_fact=CREATE INDEX "
                        + "idx_action_events_analytics_fact ON public.action_events "
                        + "USING btree (account_id, occurred_at, action_type, "
                        + "resulting_status, id)",
                    "idx_action_executions_analytics_fact=CREATE INDEX "
                        + "idx_action_executions_analytics_fact "
                        + "ON public.action_executions USING btree "
                        + "(account_id, completed_at, status, id)",
                    "idx_unsubscribe_outcome_events_analytics_fact=CREATE INDEX "
                        + "idx_unsubscribe_outcome_events_analytics_fact "
                        + "ON public.unsubscribe_outcome_events USING btree "
                        + "(account_id, occurred_at, status, id)");
            assertThat(strings(connection, """
                SELECT table_name || '.' || column_name
                  FROM information_schema.columns
                 WHERE table_schema='public'
                   AND (
                     column_name LIKE 'analytics_%'
                     OR column_name IN (
                       'provider_message_id_copy','raw_header','response_body',
                       'authorization','cookie','csrf_token'
                     )
                   )
                """)).isEmpty();
            assertThat(strings(connection, """
                SELECT (
                    (SELECT COUNT(*) FROM action_events)
                    + (SELECT COUNT(*) FROM action_executions)
                    + (SELECT COUNT(*) FROM action_execution_targets)
                    + (SELECT COUNT(*) FROM unsubscribe_outcome_events)
                )::text
                """)).containsExactly("0");
        }
        assertHibernateValidation();
    }

    @Test
    void productionV9ToV10IsIndexOnlyAndPreservesFacts() throws Exception {
        assertThat(sha256(Path.of(
            "src/main/resources/db/migration/V9__action_execution_history_and_undo.sql")))
            .isEqualTo("c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379");

        Flyway throughV9 = flyway("9");
        throughV9.clean();
        assertThat(throughV9.migrate().migrationsExecuted).isEqualTo(9);
        List<String> tables;
        List<String> columns;
        long preservedAccount;
        long preservedAction;
        long preservedExecution;
        long preservedTarget;
        long preservedEvent;
        try (Connection connection = connection()) {
            execute(connection, """
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,encrypted_access_token,
                    encrypted_refresh_token,scopes
                ) VALUES (
                    'postgres-v10@example.com','google','postgres-v10',
                    decode('01','hex'),decode('02','hex'),ARRAY['email']
                )
                """);
            preservedAccount = Long.parseLong(strings(connection, """
                SELECT id::text FROM user_accounts
                 WHERE email='postgres-v10@example.com'
                """).getFirst());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource());
            Instant occurredAt = Instant.parse("2026-08-01T00:00:00Z");
            metadata(jdbc, preservedAccount, "preserved-message", occurredAt);
            preservedAction = action(
                jdbc, preservedAccount, "preserved-message", "ARCHIVE", occurredAt);
            preservedExecution = execution(
                jdbc, preservedAccount, preservedAction, "FORWARD",
                occurredAt.plusSeconds(1));
            target(
                jdbc, preservedAccount, preservedExecution, scope(jdbc, preservedAction),
                "SUCCEEDED", occurredAt.plusSeconds(2), null);
            event(
                jdbc, preservedAccount, preservedAction, preservedExecution,
                "EXECUTION_RESERVED", "ARCHIVE", occurredAt.plusSeconds(1));
            preservedTarget = Long.parseLong(strings(connection, """
                SELECT id::text FROM action_execution_targets
                 WHERE execution_id=%d
                """.formatted(preservedExecution)).getFirst());
            preservedEvent = Long.parseLong(strings(connection, """
                SELECT id::text FROM action_events
                 WHERE execution_id=%d AND event_type='EXECUTION_RESERVED'
                """.formatted(preservedExecution)).getFirst());
            tables = strings(connection, """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema='public' ORDER BY table_name
                """);
            columns = strings(connection, """
                SELECT table_name || '.' || column_name
                  FROM information_schema.columns
                 WHERE table_schema='public'
                 ORDER BY table_name, ordinal_position
                """);
        }

        assertThat(flyway("10").migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = connection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(strings(connection, """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema='public' ORDER BY table_name
                """)).isEqualTo(tables);
            assertThat(strings(connection, """
                SELECT table_name || '.' || column_name
                  FROM information_schema.columns
                 WHERE table_schema='public'
                 ORDER BY table_name, ordinal_position
                """)).isEqualTo(columns);
            assertThat(strings(connection, """
                SELECT email FROM user_accounts
                 WHERE email='postgres-v10@example.com'
                """)).containsExactly("postgres-v10@example.com");
            assertThat(strings(connection, """
                SELECT id::text || ':' || account_id::text
                  FROM user_actions WHERE id=%d
                """.formatted(preservedAction)))
                .containsExactly(preservedAction + ":" + preservedAccount);
            assertThat(strings(connection, """
                SELECT id::text || ':' || action_id::text
                  FROM action_executions WHERE id=%d
                """.formatted(preservedExecution)))
                .containsExactly(preservedExecution + ":" + preservedAction);
            assertThat(strings(connection, """
                SELECT id::text || ':' || status
                  FROM action_execution_targets WHERE id=%d
                """.formatted(preservedTarget)))
                .containsExactly(preservedTarget + ":SUCCEEDED");
            assertThat(strings(connection, """
                SELECT id::text || ':' || event_type
                  FROM action_events WHERE id=%d
                """.formatted(preservedEvent)))
                .containsExactly(preservedEvent + ":EXECUTION_RESERVED");
            assertThat(strings(connection, """
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname='idx_action_events_analytics_fact'
                """).getFirst())
                .contains("(account_id, occurred_at, action_type, resulting_status, id)");
            assertThat(strings(connection, """
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname='idx_action_executions_analytics_fact'
                """).getFirst())
                .contains("(account_id, completed_at, status, id)");
            assertThat(strings(connection, """
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname='idx_unsubscribe_outcome_events_analytics_fact'
                """).getFirst())
                .contains("(account_id, occurred_at, status, id)");
        }
    }

    @Test
    void deterministicLedgerFixtureExecutesOnPostgres16WithSourceParity() {
        Flyway database = flyway(null);
        database.clean();
        assertThat(database.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AnalyticsLedgerRepository ledger = new AnalyticsLedgerRepository(
            new NamedParameterJdbcTemplate(dataSource));
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = start.plusSeconds(86_400);
        long owner = account(jdbc, "pg-owner@example.com");
        long foreign = account(jdbc, "pg-foreign@example.com");
        metadata(jdbc, owner, "archive", start.minusSeconds(100));
        metadata(jdbc, owner, "unsubscribe", start.minusSeconds(200));
        metadata(jdbc, owner, "read", start.minusSeconds(300));
        metadata(jdbc, foreign, "foreign", start.minusSeconds(400));

        long archive = action(jdbc, owner, "archive", "ARCHIVE", start);
        long archiveForward = execution(jdbc, owner, archive, "FORWARD", start.plusSeconds(1));
        target(jdbc, owner, archiveForward, scope(jdbc, archive), "SUCCEEDED",
            start.plusSeconds(10), null);
        event(jdbc, owner, archive, archiveForward, "EXECUTION_RESERVED", "ARCHIVE",
            start.plusSeconds(1));
        long archiveUndo = execution(jdbc, owner, archive, "UNDO", start.plusSeconds(11));
        target(jdbc, owner, archiveUndo, scope(jdbc, archive), "REVERTED",
            start.plusSeconds(12), null);

        long read = action(jdbc, owner, "read", "MARK_READ", start.plusSeconds(20));
        long readForward = execution(jdbc, owner, read, "FORWARD", start.plusSeconds(21));
        target(jdbc, owner, readForward, scope(jdbc, read), "SUCCEEDED",
            start.plusSeconds(30), null);
        event(jdbc, owner, read, readForward, "EXECUTION_RESERVED", "MARK_READ",
            start.plusSeconds(21));
        long readUndo = execution(jdbc, owner, read, "UNDO", start.plusSeconds(31));
        target(jdbc, owner, readUndo, scope(jdbc, read), "REVERTED",
            start.plusSeconds(32), null);

        long unsubscribe =
            action(jdbc, owner, "unsubscribe", "UNSUBSCRIBE", start.plusSeconds(40));
        long unsubscribeForward =
            execution(jdbc, owner, unsubscribe, "FORWARD", start.plusSeconds(41));
        long attempt = attempt(jdbc, owner, start.plusSeconds(42));
        target(jdbc, owner, unsubscribeForward, scope(jdbc, unsubscribe),
            "VERIFICATION_PENDING", start.plusSeconds(50), attempt);
        event(jdbc, owner, unsubscribe, unsubscribeForward, "EXECUTION_RESERVED",
            "UNSUBSCRIBE", start.plusSeconds(41));
        jdbc.update("""
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status,occurred_at)
            VALUES (?,?,?,?),(?,?,?,?)
            """, owner, attempt, "ACCEPTED", timestamp(start.plusSeconds(43)),
            owner, attempt, "CONFIRMED", timestamp(start.plusSeconds(60)));

        long foreignAction = action(jdbc, foreign, "foreign", "ARCHIVE", start);
        long foreignExecution =
            execution(jdbc, foreign, foreignAction, "FORWARD", start.plusSeconds(1));
        target(jdbc, foreign, foreignExecution, scope(jdbc, foreignAction), "SUCCEEDED",
            start.plusSeconds(2), null);
        event(jdbc, foreign, foreignAction, foreignExecution, "EXECUTION_RESERVED",
            "ARCHIVE", start.plusSeconds(1));

        DateRange range = new DateRange(start, end);
        var snapshot = ledger.snapshot(List.of(owner), range, AnalyticsAction.ALL);
        var outcomes = ledger.outcomes(List.of(owner), range, AnalyticsAction.ALL);
        var unsubscribeCounts = ledger.unsubscribeCounts(List.of(owner), range);
        long cleaned = ledger.cleanedCount(List.of(owner), start, end, AnalyticsAction.ALL);
        var page = ledger.cleanedPage(
            List.of(owner), start, end, AnalyticsAction.ALL, 25, 0);
        List<?> streamed;
        try (var rows =
                ledger.streamCleaned(List.of(owner), start, end, AnalyticsAction.ALL)) {
            streamed = rows.toList();
        }

        assertThat(snapshot.triaged()).isEqualTo(3);
        assertThat(snapshot.archived()).isEqualTo(1);
        assertThat(snapshot.markedRead()).isEqualTo(1);
        assertThat(snapshot.unsubscribed()).isEqualTo(1);
        assertThat(snapshot.inverseSucceeded()).isEqualTo(1);
        assertThat(outcomes.requested()).isEqualTo(3);
        assertThat(outcomes.succeeded()).isEqualTo(2);
        assertThat(outcomes.verificationPending()).isEqualTo(1);
        assertThat(unsubscribeCounts.confirmed()).isEqualTo(1);
        assertThat(cleaned).isEqualTo(3);
        assertThat(page).hasSize(3).isEqualTo(streamed);
        assertThat(ledger.eligibleDelaySeconds(List.of(owner), range, AnalyticsAction.ALL))
            .containsExactly(100L, 240L, 320L);
        assertThat(ledger.medianDelaySeconds(List.of(owner), range, AnalyticsAction.ALL))
            .isEqualByComparingTo("240");
        assertThat(ledger.cleanedCountByBuckets(
            List.of(owner),
            List.of(
                new DateRange(start, start.plusSeconds(30)),
                new DateRange(start.plusSeconds(30), end)),
            AnalyticsAction.ALL))
            .extracting(AnalyticsLedgerRepository.CleanedBucketCount::count)
            .containsExactly(1L, 2L);
    }

    @Test
    void postgresMetricsSourcesOutcomesAndOrderingRemainExact() {
        Flyway postgres = flyway(null);
        postgres.clean();
        assertThat(postgres.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        DialectMetrics postgresMetrics = sharedDialectMetrics(dataSource());
        assertThat(postgresMetrics.received()).isEqualTo(3);
        assertThat(postgresMetrics.triaged()).isEqualTo(9);
        assertThat(postgresMetrics.succeeded()).isEqualTo(3);
        assertThat(postgresMetrics.failed()).isEqualTo(1);
        assertThat(postgresMetrics.median()).isPositive();
        assertThat(postgresMetrics.bucketCounts()).containsExactly(1L, 3L);
    }

    private static DialectMetrics sharedDialectMetrics(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        AnalyticsLedgerRepository ledger = new AnalyticsLedgerRepository(named);
        AnalyticsReceivedRepository received = new AnalyticsReceivedRepository(named);
        AnalyticsCoverageRepository coverage = new AnalyticsCoverageRepository(named);
        Instant start = Instant.parse("2026-02-01T00:00:00Z");
        Instant end = start.plusSeconds(86_400);
        long owner = account(jdbc, "dialect-owner@example.com");
        long second = account(jdbc, "dialect-second@example.com");
        long foreign = account(jdbc, "dialect-foreign@example.com");
        metadata(jdbc, owner, "received-first", start);
        metadata(jdbc, owner, "received-middle", start.plusSeconds(100));
        metadata(jdbc, owner, "received-last", end.minusMillis(1));
        metadata(jdbc, owner, "received-excluded-end", end);
        metadata(jdbc, owner, "archive", start.minusSeconds(100).minusMillis(250));
        metadata(jdbc, owner, "read", start.minusSeconds(200).minusMillis(250));
        metadata(jdbc, owner, "failed", start.minusSeconds(300).minusMillis(250));
        metadata(jdbc, owner, "pending", start.minusSeconds(400).minusMillis(250));
        metadata(jdbc, owner, "not-dispatched", start.minusSeconds(500).minusMillis(250));
        metadata(jdbc, owner, "unsubscribe-confirmed", start.minusSeconds(600).minusMillis(250));
        metadata(jdbc, owner, "unsubscribe-manual", start.minusSeconds(700).minusMillis(250));
        metadata(jdbc, owner, "unsubscribe-unlinked", start.minusSeconds(800).minusMillis(250));
        metadata(jdbc, second, "same-provider", start.minusSeconds(300).minusMillis(250));
        metadata(jdbc, foreign, "same-provider", start.minusSeconds(300).minusMillis(250));
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id,provider,provider_message_id,provider_thread_id,received_at,
                from_display_name,from_address,from_domain,subject_header
            ) VALUES (?,'google','null-received','thread-null',NULL,
                      'Unknown',NULL,NULL,'private')
            """, owner);

        long archive = action(jdbc, owner, "archive", "ARCHIVE", start);
        long archiveExecution =
            execution(jdbc, owner, archive, "FORWARD", start.plusSeconds(1));
        target(jdbc, owner, archiveExecution, scope(jdbc, archive), "SUCCEEDED",
            start.plusSeconds(10), null);
        event(jdbc, owner, archive, archiveExecution, "EXECUTION_RESERVED", "ARCHIVE",
            start.plusSeconds(1));
        event(jdbc, owner, archive, null, "ACTION_QUEUED", "ARCHIVE",
            start.plusSeconds(2));
        long archiveUndo =
            execution(jdbc, owner, archive, "UNDO", start.plusSeconds(11));
        target(jdbc, owner, archiveUndo, scope(jdbc, archive), "REVERTED",
            start.plusSeconds(12), null);

        long read = action(jdbc, owner, "read", "MARK_READ", start.plusSeconds(20));
        long readExecution =
            execution(jdbc, owner, read, "FORWARD", start.plusSeconds(21));
        target(jdbc, owner, readExecution, scope(jdbc, read), "SUCCEEDED",
            start.plusSeconds(30), null);
        event(jdbc, owner, read, readExecution, "EXECUTION_RESERVED", "MARK_READ",
            start.plusSeconds(21));

        long secondAction =
            action(jdbc, second, "same-provider", "TRASH", start.plusSeconds(40));
        long secondExecution =
            execution(jdbc, second, secondAction, "FORWARD", start.plusSeconds(41));
        target(jdbc, second, secondExecution, scope(jdbc, secondAction), "SUCCEEDED",
            start.plusSeconds(50), null);
        event(jdbc, second, secondAction, secondExecution, "EXECUTION_RESERVED", "TRASH",
            start.plusSeconds(41));

        long failed = action(jdbc, owner, "failed", "ARCHIVE", start.plusSeconds(60));
        long failedExecution =
            execution(jdbc, owner, failed, "FORWARD", start.plusSeconds(61));
        target(jdbc, owner, failedExecution, scope(jdbc, failed), "FAILED",
            start.plusSeconds(62), null);
        event(jdbc, owner, failed, failedExecution, "EXECUTION_RESERVED", "ARCHIVE",
            start.plusSeconds(61));
        targetState(jdbc, failedExecution, "FAILED", "DISPATCHED", true, true);

        long pending = action(jdbc, owner, "pending", "TRASH", start.plusSeconds(70));
        long pendingExecution =
            execution(jdbc, owner, pending, "FORWARD", start.plusSeconds(71));
        target(jdbc, owner, pendingExecution, scope(jdbc, pending), "VERIFICATION_PENDING",
            start.plusSeconds(72), null);
        event(jdbc, owner, pending, pendingExecution, "EXECUTION_RESERVED", "TRASH",
            start.plusSeconds(71));
        targetState(jdbc, pendingExecution, "VERIFICATION_PENDING", "DISPATCHED", true, false);

        long notDispatched =
            action(jdbc, owner, "not-dispatched", "MARK_READ", start.plusSeconds(80));
        long notDispatchedExecution =
            execution(jdbc, owner, notDispatched, "FORWARD", start.plusSeconds(81));
        target(jdbc, owner, notDispatchedExecution, scope(jdbc, notDispatched), "PENDING",
            start.plusSeconds(82), null);
        event(jdbc, owner, notDispatched, notDispatchedExecution, "EXECUTION_RESERVED",
            "MARK_READ", start.plusSeconds(81));
        targetState(jdbc, notDispatchedExecution, "PENDING", "NOT_DISPATCHED", false, false);

        long confirmedAttempt = attempt(jdbc, owner, start.plusSeconds(90));
        long unsubscribeConfirmed = action(
            jdbc, owner, "unsubscribe-confirmed", "UNSUBSCRIBE", start.plusSeconds(90));
        long confirmedExecution = execution(
            jdbc, owner, unsubscribeConfirmed, "FORWARD", start.plusSeconds(91));
        target(jdbc, owner, confirmedExecution, scope(jdbc, unsubscribeConfirmed),
            "VERIFICATION_PENDING", start.plusSeconds(92), confirmedAttempt);
        event(jdbc, owner, unsubscribeConfirmed, confirmedExecution, "EXECUTION_RESERVED",
            "UNSUBSCRIBE", start.plusSeconds(91));
        jdbc.update("""
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status,occurred_at)
            VALUES (?,?,?,?),(?,?,?,?)
            """, owner, confirmedAttempt, "ACCEPTED", timestamp(start.plusSeconds(92)),
            owner, confirmedAttempt, "CONFIRMED", timestamp(start.plusSeconds(93)));

        long manualAttempt = attempt(jdbc, owner, start.plusSeconds(100));
        long unsubscribeManual = action(
            jdbc, owner, "unsubscribe-manual", "UNSUBSCRIBE", start.plusSeconds(100));
        long manualExecution = execution(
            jdbc, owner, unsubscribeManual, "FORWARD", start.plusSeconds(101));
        target(jdbc, owner, manualExecution, scope(jdbc, unsubscribeManual),
            "VERIFICATION_PENDING", start.plusSeconds(102), manualAttempt);
        event(jdbc, owner, unsubscribeManual, manualExecution, "EXECUTION_RESERVED",
            "UNSUBSCRIBE", start.plusSeconds(101));
        jdbc.update("""
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status,occurred_at)
            VALUES (?,?,?,?)
            """, owner, manualAttempt, "MANUAL_REQUIRED", timestamp(start.plusSeconds(102)));

        long unsubscribeUnlinked = action(
            jdbc, owner, "unsubscribe-unlinked", "UNSUBSCRIBE", start.plusSeconds(110));
        long unlinkedExecution = execution(
            jdbc, owner, unsubscribeUnlinked, "FORWARD", start.plusSeconds(111));
        target(jdbc, owner, unlinkedExecution, scope(jdbc, unsubscribeUnlinked),
            "VERIFICATION_PENDING", start.plusSeconds(112), null);
        event(jdbc, owner, unsubscribeUnlinked, unlinkedExecution, "EXECUTION_RESERVED",
            "UNSUBSCRIBE", start.plusSeconds(111));

        jdbc.update("""
            INSERT INTO user_actions(
                account_id,action_type,message_ids,inverse_action_data,target_kind,
                target_id_json,affected_count,status,execution_mode,decision_source,created_at
            ) VALUES (?,'ARCHIVE',ARRAY[CAST('legacy-no-detail' AS VARCHAR)],
                      '{}','LEGACY','{}',1,'APPLIED','STAGED','LEGACY',?)
            """, owner, timestamp(start.plusSeconds(120)));

        long foreignAction =
            action(jdbc, foreign, "same-provider", "ARCHIVE", start.plusSeconds(60));
        long foreignExecution =
            execution(jdbc, foreign, foreignAction, "FORWARD", start.plusSeconds(61));
        target(jdbc, foreign, foreignExecution, scope(jdbc, foreignAction), "SUCCEEDED",
            start.plusSeconds(70), null);
        event(jdbc, foreign, foreignAction, foreignExecution, "EXECUTION_RESERVED",
            "ARCHIVE", start.plusSeconds(61));

        List<Long> owned = List.of(owner, second);
        DateRange range = new DateRange(start, end);
        var receivedFact = received.receivedFact(owned, start, end);
        var snapshot = ledger.snapshot(owned, range, AnalyticsAction.ALL);
        var outcomes = ledger.outcomes(owned, range, AnalyticsAction.ALL);
        var unsubscribe = ledger.unsubscribeCounts(owned, range);
        List<String> firstCleanedPage = ledger.cleanedPage(
            owned, start, end, AnalyticsAction.ALL, 2, 0).stream()
            .map(row -> row.accountLabel() + "|" + row.senderDisplay()
                + "|" + row.action() + "|" + row.factAt())
            .toList();
        List<String> secondCleanedPage = ledger.cleanedPage(
            owned, start, end, AnalyticsAction.ALL, 2, 2).stream()
            .map(row -> row.accountLabel() + "|" + row.senderDisplay()
                + "|" + row.action() + "|" + row.factAt())
            .toList();
        List<String> orderedSources = new ArrayList<>(firstCleanedPage);
        orderedSources.addAll(secondCleanedPage);
        List<String> receivedBoundaries = List.of(
            receivedFact.page(1, 0).getFirst().receivedAt().toString(),
            receivedFact.page(1, receivedFact.count() - 1).getFirst().receivedAt().toString());
        List<DateRange> cleanedBuckets = List.of(
            new DateRange(start, start.plusSeconds(30)),
            new DateRange(start.plusSeconds(30), end));
        List<Long> bucketCounts = ledger.cleanedCountByBuckets(
            owned, cleanedBuckets, AnalyticsAction.ALL).stream().map(
                AnalyticsLedgerRepository.CleanedBucketCount::count).toList();
        List<Long> bucketSourceUnionCounts = cleanedBuckets.stream()
            .map(bucket -> completeCleanedSourceCount(
                ledger, owned, bucket, AnalyticsAction.ALL))
            .toList();
        assertThat(bucketSourceUnionCounts).isEqualTo(bucketCounts);
        assertThat(receivedFact.count()).isEqualTo(3);
        assertThat(receivedBoundaries)
            .containsExactly(start.toString(), end.minusMillis(1).toString());
        assertThat(snapshot.triaged()).isEqualTo(9);
        assertThat(snapshot.inverseSucceeded()).isOne();
        assertThat(outcomes.requested()).isEqualTo(9);
        assertThat(outcomes.accepted()).isEqualTo(8);
        assertThat(outcomes.succeeded()).isEqualTo(3);
        assertThat(outcomes.failed()).isOne();
        assertThat(outcomes.verificationPending()).isEqualTo(4);
        assertThat(outcomes.notDispatched()).isOne();
        assertThat(unsubscribe.confirmed()).isOne();
        assertThat(unsubscribe.eligible()).isOne();
        assertThat(unsubscribe.manualOnly()).isOne();
        assertThat(coverage.legacyGapCount(owned, range, AnalyticsAction.ALL)).isOne();
        assertThat(coverage.unsubscribeLinkageGapCount(owned, range)).isOne();
        assertThat(received.nullReceivedCount(owned)).isOne();
        assertThat(firstCleanedPage).hasSize(2);
        assertThat(secondCleanedPage).hasSize(2);
        assertThat(ledger.cleanedCount(
            owned, start, end, AnalyticsAction.ARCHIVE)).isOne();
        assertThat(ledger.outcomes(owned, range, AnalyticsAction.ARCHIVE))
            .extracting(
                outcome -> outcome.requested(),
                outcome -> outcome.succeeded(),
                outcome -> outcome.failed())
            .containsExactly(2L, 1L, 1L);
        return new DialectMetrics(
            receivedFact.count(),
            receivedBoundaries,
            snapshot.triaged(), snapshot.archived(), snapshot.trashed(),
            snapshot.markedRead(), snapshot.unsubscribed(), snapshot.inverseSucceeded(),
            outcomes.requested(), outcomes.accepted(), outcomes.succeeded(),
            outcomes.failed(), outcomes.verificationPending(), outcomes.notDispatched(),
            unsubscribe.confirmed(), unsubscribe.eligible(), unsubscribe.manualOnly(),
            ledger.cleanedCount(owned, start, end, AnalyticsAction.ALL),
            orderedSources,
            firstCleanedPage,
            secondCleanedPage,
            ledger.cleanedCount(owned, start, end, AnalyticsAction.ARCHIVE),
            coverage.legacyGapCount(owned, range, AnalyticsAction.ALL),
            coverage.unsubscribeLinkageGapCount(owned, range),
            received.nullReceivedCount(owned),
            coverage.earliestLedgerByAccount(owned),
            ledger.eligibleDelaySeconds(owned, range, AnalyticsAction.ALL),
            ledger.medianDelaySeconds(owned, range, AnalyticsAction.ALL),
            bucketCounts,
            bucketSourceUnionCounts);
    }

    private static long completeCleanedSourceCount(
            AnalyticsLedgerRepository ledger,
            List<Long> accountIds,
            DateRange bucket,
            AnalyticsAction action) {
        long count = 0;
        int pageSize = 1;
        while (true) {
            var page = ledger.cleanedPage(
                accountIds, bucket.startInclusive(), bucket.endExclusive(),
                action, pageSize, count);
            count += page.size();
            if (page.size() < pageSize) {
                return count;
            }
        }
    }

    private record DialectMetrics(
        long received,
        List<String> receivedBoundaries,
        long triaged,
        long archived,
        long trashed,
        long markedRead,
        long unsubscribed,
        long inverseSucceeded,
        long requested,
        long accepted,
        long succeeded,
        long failed,
        long verificationPending,
        long notDispatched,
        long unsubscribeConfirmed,
        long unsubscribeEligible,
        long unsubscribeManualOnly,
        long sourceCount,
        List<String> orderedSources,
        List<String> firstPage,
        List<String> secondPage,
        long archiveFilteredSourceCount,
        long legacyGap,
        long unsubscribeLinkageGap,
        long nullReceived,
        Map<Long, Instant> earliestLedger,
        List<Long> delays,
        BigDecimal median,
        List<Long> bucketCounts,
        List<Long> bucketSourceUnionCounts
    ) {
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .cleanDisabled(false)
            .dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void assertHibernateValidation() {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(InboxCleanerApplication.class)
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
                        "--MICROSOFT_CLIENT_SECRET=")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static long account(JdbcTemplate jdbc, String email) {
        jdbc.update("""
            INSERT INTO user_accounts(
                email,provider,provider_account_id,owner_key,encrypted_access_token,
                encrypted_refresh_token,scopes
            ) VALUES (?,'google',?,?,?, ?,ARRAY['email'])
            """, email, "provider-" + email, email.toLowerCase(java.util.Locale.ROOT) + ":google",
            new byte[] {1}, new byte[] {2});
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM user_accounts", Long.class);
    }

    private static void metadata(
            JdbcTemplate jdbc, long accountId, String messageId, Instant receivedAt) {
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id,provider,provider_message_id,provider_thread_id,received_at,
                from_display_name,from_address,from_domain,subject_header
            ) VALUES (?,'google',?,?,?,'Sender','sender@example.com','example.com','private')
            """, accountId, messageId, "thread-" + messageId, timestamp(receivedAt));
    }

    private static long action(
            JdbcTemplate jdbc,
            long accountId,
            String messageId,
            String actionType,
            Instant occurredAt) {
        jdbc.update("""
            INSERT INTO action_scopes(
                account_id,action_type,target_kind,message_ids,scope_hash,
                message_count,target_summary,risk_summary,created_at
            ) VALUES (
                ?,?,'ANALYTICS_TEST',ARRAY[CAST(? AS VARCHAR)],?,1,'{}','{}',?
            )
            """, accountId, actionType, messageId,
            hash(accountId + actionType + messageId + occurredAt), timestamp(occurredAt));
        long scope = jdbc.queryForObject(
            "SELECT MAX(id) FROM action_scopes", Long.class);
        jdbc.update("""
            INSERT INTO action_scope_targets(
                scope_id,account_id,scope_ordinal,target_fingerprint
            ) VALUES (?,?,0,?)
            """, scope, accountId, hash(messageId));
        jdbc.update("""
            INSERT INTO user_actions(
                account_id,action_scope_id,action_type,message_ids,inverse_action_data,
                target_kind,target_id_json,affected_count,status,execution_mode,
                decision_source,created_at
            ) VALUES (
                ?,?,?,ARRAY[CAST(? AS VARCHAR)],'{}','ANALYTICS_TEST','{}',
                1,'QUEUED','STAGED','UNREAD',?
            )
            """, accountId, scope, actionType, messageId,
            timestamp(occurredAt));
        long action = jdbc.queryForObject(
            "SELECT MAX(id) FROM user_actions", Long.class);
        event(jdbc, accountId, action, null, "ACTION_QUEUED", actionType, occurredAt);
        return action;
    }

    private static long scope(JdbcTemplate jdbc, long actionId) {
        return jdbc.queryForObject(
            "SELECT action_scope_id FROM user_actions WHERE id=?", Long.class, actionId);
    }

    private static long execution(
            JdbcTemplate jdbc,
            long accountId,
            long actionId,
            String operation,
            Instant reservedAt) {
        jdbc.update("""
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,reserved_at,completed_at
            ) VALUES (?,?,?,'APPLIED',?,1,1,1,0,0,0,?,?)
            """, accountId, actionId, operation, hash(actionId + operation),
            timestamp(reservedAt), timestamp(reservedAt.plusSeconds(1)));
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM action_executions", Long.class);
    }

    private static void target(
            JdbcTemplate jdbc,
            long accountId,
            long executionId,
            long scopeId,
            String status,
            Instant completedAt,
            Long attemptId) {
        String fingerprint = jdbc.queryForObject("""
            SELECT target_fingerprint FROM action_scope_targets
             WHERE scope_id=? AND scope_ordinal=0
            """, String.class, scopeId);
        jdbc.update("""
            INSERT INTO action_execution_targets(
                execution_id,account_id,scope_id,scope_ordinal,target_fingerprint,
                dispatch_state,status,accepted_at,dispatched_at,completed_at,
                unsubscribe_attempt_id
            ) VALUES (?,?,?,0,?,'DISPATCHED',?,?,?,?,?)
            """, executionId, accountId, scopeId, fingerprint, status,
            timestamp(completedAt.minusSeconds(1)), timestamp(completedAt.minusSeconds(1)),
            timestamp(completedAt), attemptId);
    }

    private static void targetState(
            JdbcTemplate jdbc,
            long executionId,
            String status,
            String dispatchState,
            boolean accepted,
            boolean completed) {
        jdbc.update("""
            UPDATE action_execution_targets
               SET status=?,dispatch_state=?,
                   accepted_at=CASE WHEN ? THEN accepted_at ELSE NULL END,
                   dispatched_at=CASE WHEN ?='DISPATCHED' THEN dispatched_at ELSE NULL END,
                   completed_at=CASE WHEN ? THEN completed_at ELSE NULL END,
                   reason_code=CASE WHEN ?='FAILED' THEN 'PROVIDER_REJECTED'
                                    WHEN ?='NOT_DISPATCHED' THEN 'NOT_DISPATCHED'
                                    ELSE NULL END
             WHERE execution_id=?
            """, status, dispatchState, accepted, dispatchState, completed,
            status, dispatchState, executionId);
        jdbc.update("""
            UPDATE action_executions
               SET status='PARTIAL',
                   accepted_count=CASE WHEN ? THEN 1 ELSE 0 END,
                   succeeded_count=CASE WHEN ?='SUCCEEDED' THEN 1 ELSE 0 END,
                   failed_count=CASE WHEN ?='FAILED' THEN 1 ELSE 0 END,
                   verification_pending_count=
                       CASE WHEN ?='VERIFICATION_PENDING' THEN 1 ELSE 0 END,
                   not_dispatched_count=CASE WHEN ?='NOT_DISPATCHED' THEN 1 ELSE 0 END
             WHERE id=?
            """, accepted, status, status, status, dispatchState, executionId);
    }

    private static void event(
            JdbcTemplate jdbc,
            long accountId,
            long actionId,
            Long executionId,
            String type,
            String actionType,
            Instant occurredAt) {
        jdbc.update("""
            INSERT INTO action_events(
                account_id,action_id,execution_id,event_type,action_type,execution_mode,
                decision_source,resulting_status,requested_count,accepted_count,
                succeeded_count,failed_count,verification_pending_count,
                not_dispatched_count,occurred_at
            ) VALUES (?,?,?,?,?,'STAGED','UNREAD','QUEUED',1,0,0,0,0,1,?)
            """, accountId, actionId, executionId, type, actionType,
            timestamp(occurredAt));
    }

    private static long attempt(JdbcTemplate jdbc, long accountId, Instant createdAt) {
        jdbc.update("""
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,
                method,created_at,latest_status
            ) VALUES (?,?,?,'RFC8058_POST',?,'VERIFICATION_PENDING')
            """, accountId, hash("candidate" + createdAt),
            hash("sender" + createdAt), timestamp(createdAt));
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM unsubscribe_attempts", Long.class);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static List<String> strings(Connection connection, String sql)
            throws Exception {
        ArrayList<String> values = new ArrayList<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Map<Integer, String> hashes(String... values) {
        LinkedHashMap<Integer, String> hashes = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index++) {
            hashes.put(index + 1, values[index]);
        }
        return Map.copyOf(hashes);
    }

    private static void assertFrozenMigrations(
            String directory, Map<Integer, String> expected) throws Exception {
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            List<Path> matches;
            try (var files = Files.list(Path.of(directory))) {
                String prefix = "V" + entry.getKey() + "__";
                matches = files.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList();
            }
            assertThat(matches).as("migration V%s in %s", entry.getKey(), directory)
                .singleElement()
                .satisfies(path -> assertThat(sha256(path)).isEqualTo(entry.getValue()));
        }
    }
}
