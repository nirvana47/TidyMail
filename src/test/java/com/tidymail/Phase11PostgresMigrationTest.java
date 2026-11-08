package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase11PostgresMigrationTest {
    private static final String MIGRATIONS = "filesystem:src/main/resources/db/migration";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void productionV1ToV8AndV7ToV8UpgradeValidateSchemaHibernateAndPreserveHeaders()
            throws Exception {
        Flyway full = flyway(null);
        full.clean();
        assertThat(full.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        try (Connection connection = connection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertCompleteV8(connection);
        }
        assertHibernateValidation();

        Flyway throughV7 = flyway("7");
        throughV7.clean();
        assertThat(throughV7.migrate().migrationsExecuted).isEqualTo(7);
        long accountId;
        long metadataId;
        try (Connection connection = connection()) {
            accountId = insertAccount(connection, "phase11-upgrade@example.com");
            metadataId = insertMetadata(connection, accountId, "preserved-provider-id");
            execute(connection, """
                INSERT INTO protected_senders(account_id,sender_address,source)
                VALUES (%d,'protected@example.com','USER')
                """.formatted(accountId));
            execute(connection, """
                INSERT INTO protected_override_audits(
                    account_id,selection_surface,selection_mode,action_type,
                    query_fingerprint,protected_sender_count,protected_message_count,
                    reason_code)
                VALUES (%d,'SENDERS','INDIVIDUAL','KEEP','%s',1,1,'USER_CONFIRMED')
                """.formatted(accountId, "a".repeat(64)));
        }
        assertThat(flyway("8").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = connection()) {
            assertCompleteV8(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT provider_message_id, unsubscribe_dkim_pass,
                           unsubscribe_dkim_covers_list_unsubscribe,
                           unsubscribe_dkim_covers_list_unsubscribe_post,
                           unsubscribe_auth_reason
                    FROM message_metadata WHERE id=?
                    """)) {
                statement.setLong(1, metadataId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("preserved-provider-id");
                    assertThat(result.getBoolean(2)).isFalse();
                    assertThat(result.getBoolean(3)).isFalse();
                    assertThat(result.getBoolean(4)).isFalse();
                    assertThat(result.getString(5)).isNull();
                }
            }
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM protected_senders WHERE account_id=" + accountId))
                .isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM protected_override_audits WHERE account_id=" + accountId))
                .isEqualTo(1);
            assertThat(insertMetadata(connection, accountId, "identity-continuation"))
                .isGreaterThan(metadataId);
            assertConstraints(connection, accountId);
        }

        assertThat(flyway(null).migrate().migrationsExecuted).isGreaterThanOrEqualTo(5);
        assertHibernateValidation();
    }

    private static void assertCompleteV8(Connection connection) throws SQLException {
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success"))
            .isGreaterThanOrEqualTo(8);
        assertThat(columns(connection, "message_metadata")).contains(
            "unsubscribe_dkim_pass",
            "unsubscribe_dkim_covers_list_unsubscribe",
            "unsubscribe_dkim_covers_list_unsubscribe_post",
            "unsubscribe_auth_reason");
        assertThat(columns(connection, "unsubscribe_attempts")).containsExactly(
            "id", "account_id", "candidate_fingerprint", "sender_key_fingerprint",
            "method", "destination_host", "destination_fingerprint", "created_at",
            "completed_at", "latest_status", "http_status_class", "reason_code",
            "confirmation_source");
        assertThat(columns(connection, "unsubscribe_outcome_events")).containsExactly(
            "id", "account_id", "attempt_id", "status", "occurred_at", "reason_code",
            "http_status_class", "confirmation_source");
        assertThat(columns(connection, "unsubscribe_intent_nonces")).containsExactly(
            "id", "account_id", "nonce_fingerprint", "candidate_fingerprint",
            "destination_fingerprint", "method", "issued_at", "expires_at", "consumed_at");
        assertThat(indexes(connection, "message_metadata")).contains(
            "idx_message_metadata_unsubscribe_candidate",
            "idx_message_metadata_unsubscribe_list_source");
        assertThat(indexes(connection, "unsubscribe_attempts")).contains(
            "idx_unsubscribe_attempts_account_status_created",
            "idx_unsubscribe_attempts_account_candidate_created");
        assertThat(indexes(connection, "unsubscribe_outcome_events")).contains(
            "idx_unsubscribe_outcome_events_attempt_time",
            "idx_unsubscribe_outcome_events_account_time");
        assertThat(indexes(connection, "unsubscribe_intent_nonces")).contains(
            "idx_unsubscribe_intent_nonces_account_expiry");
    }

    private static void assertConstraints(Connection connection, long accountId)
            throws SQLException {
        assertThatThrownBy(() -> execute(connection, """
            UPDATE message_metadata SET unsubscribe_auth_reason='RAW_HEADER'
            WHERE account_id=%d
            """.formatted(accountId))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,method,latest_status
            ) VALUES (%d,'%s','%s','UNSAFE_METHOD','PENDING')
            """.formatted(accountId, "a".repeat(64), "b".repeat(64))))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,method,
                destination_host,latest_status
            ) VALUES (%d,'%s','%s','MANUAL','https://secret.example','PENDING')
            """.formatted(accountId, "a".repeat(64), "b".repeat(64))))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,method,latest_status
            ) VALUES (%d,'not-a-fingerprint','%s','LEGACY_HTTPS_GET','PENDING')
            """.formatted(accountId, "b".repeat(64))))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,method,
                latest_status,http_status_class
            ) VALUES (%d,'%s','%s','LEGACY_HTTPS_GET','FAILED',6)
            """.formatted(accountId, "a".repeat(64), "b".repeat(64))))
            .isInstanceOf(SQLException.class);

        long attemptId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO unsubscribe_attempts(
                    account_id,candidate_fingerprint,sender_key_fingerprint,method,
                    destination_host,destination_fingerprint,latest_status
                ) VALUES (?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'LEGACY_HTTPS_GET','unsubscribe.example',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                    'PENDING')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                attemptId = keys.getLong(1);
            }
        }
        execute(connection, """
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status)
            VALUES (%d,%d,'PENDING')
            """.formatted(accountId, attemptId));
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status)
            VALUES (%d,%d,'SUCCESS')
            """.formatted(accountId, attemptId))).isInstanceOf(SQLException.class);
        long foreignAccountId = insertAccount(connection, "phase11-foreign@example.com");
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status)
            VALUES (%d,%d,'PENDING')
            """.formatted(foreignAccountId, attemptId))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO unsubscribe_intent_nonces(
                account_id,nonce_fingerprint,candidate_fingerprint,
                destination_fingerprint,method,expires_at
            ) VALUES (%d,'%s','%s','%s','RFC8058_POST',now() - interval '1 second')
            """.formatted(
                accountId, "d".repeat(64), "e".repeat(64), "f".repeat(64))))
            .isInstanceOf(SQLException.class);

        long cascadeAccountId = insertAccount(connection, "phase11-cascade@example.com");
        execute(connection, """
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,method,latest_status
            ) VALUES (%d,'%s','%s','MANUAL','MANUAL_REQUIRED')
            """.formatted(cascadeAccountId, "1".repeat(64), "2".repeat(64)));
        long cascadeAttemptId = queryLong(connection,
            "SELECT max(id) FROM unsubscribe_attempts WHERE account_id=" + cascadeAccountId);
        execute(connection, """
            INSERT INTO unsubscribe_outcome_events(account_id,attempt_id,status)
            VALUES (%d,%d,'MANUAL_REQUIRED')
            """.formatted(cascadeAccountId, cascadeAttemptId));
        execute(connection, """
            INSERT INTO unsubscribe_intent_nonces(
                account_id,nonce_fingerprint,candidate_fingerprint,
                destination_fingerprint,method,expires_at)
            VALUES (%d,'%s','%s','%s','MAILTO',now() + interval '5 minutes')
            """.formatted(
                cascadeAccountId, "3".repeat(64), "4".repeat(64), "5".repeat(64)));
        execute(connection, "DELETE FROM user_accounts WHERE id=" + cascadeAccountId);
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM unsubscribe_attempts WHERE account_id=" + cascadeAccountId))
            .isZero();
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM unsubscribe_outcome_events WHERE account_id="
                + cascadeAccountId)).isZero();
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM unsubscribe_intent_nonces WHERE account_id="
                + cascadeAccountId)).isZero();
    }

    private static long insertAccount(Connection connection, String email) throws SQLException {
        boolean hasOwnerKey;
        try (PreparedStatement columns = connection.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_name='user_accounts' AND column_name='owner_key'
                """);
             ResultSet result = columns.executeQuery()) {
            result.next();
            hasOwnerKey = result.getLong(1) == 1;
        }
        String sql = hasOwnerKey
            ? """
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,owner_key,encrypted_access_token,
                    encrypted_refresh_token,scopes)
                VALUES (?,'google',?,?,decode('01','hex'),decode('02','hex'),ARRAY['openid'])
                """
            : """
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,encrypted_access_token,
                    encrypted_refresh_token,scopes)
                VALUES (?,'google',?,decode('01','hex'),decode('02','hex'),ARRAY['openid'])
                """;
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, "provider-" + email);
            if (hasOwnerKey) statement.setString(3, email.toLowerCase() + ":google");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertMetadata(
            Connection connection, long accountId, String providerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,from_address,from_display_name,
                    from_domain,received_at,list_id_values,list_unsubscribe_values,
                    list_unsubscribe_post_values)
                VALUES (?,'google',?,'postgres-news@example.com','Postgres News',
                    'example.com',?,ARRAY['postgres.list'],
                    ARRAY['<https://unsubscribe.example/u?token=POSTGRES_URI_CANARY>'],
                    ARRAY['List-Unsubscribe=One-Click'])
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.setString(2, providerId);
            statement.setObject(3, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void assertHibernateValidation() {
        try (ConfigurableApplicationContext context = application()) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(InboxCleanerApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.task.scheduling.enabled=false",
                "--TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
                "--TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
                "--GOOGLE_CLIENT_ID=",
                "--GOOGLE_CLIENT_SECRET=",
                "--MICROSOFT_CLIENT_ID=",
                "--MICROSOFT_CLIENT_SECRET=");
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .cleanDisabled(false)
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        return queryStrings(connection, """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema='public' AND table_name='%s' ORDER BY ordinal_position
            """.formatted(table));
    }

    private static List<String> indexes(Connection connection, String table) throws SQLException {
        return queryStrings(connection,
            "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND tablename='"
                + table + "'");
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql)
            throws SQLException {
        ArrayList<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }
}
