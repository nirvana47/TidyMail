package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
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
