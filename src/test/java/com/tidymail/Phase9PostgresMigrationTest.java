package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase9PostgresMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void productionV6PreservesV5RowsAndPassesHibernateValidation() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("5")
            .load()
            .migrate();

        long accountId;
        long otherAccountId;
        long progressId;
        long actionId;
        try (Connection connection = connection()) {
            accountId = insertAccount(connection, "postgres@example.com");
            otherAccountId = insertAccount(connection, "other@example.com");
            progressId = insertProgress(connection, accountId);
            actionId = insertLegacyAction(connection, accountId);
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT status,messages_scanned,error_message FROM sync_progress WHERE id=?")) {
                statement.setLong(1, progressId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("status")).isEqualTo("COMPLETE");
                    assertThat(result.getInt("messages_scanned")).isEqualTo(91);
                    assertThat(result.getString("error_message")).isEqualTo("legacy-safe");
                }
            }
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM user_actions WHERE id=" + actionId)).isEqualTo(1);

            long scopeId = insertActionScope(connection, accountId);
            assertThatThrownBy(() -> attachForeignScope(connection, otherAccountId, scopeId))
                .isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo("23503");

            OffsetDateTime received = OffsetDateTime.of(
                2026, 8, 9, 12, 0, 0, 123_000_000, ZoneOffset.UTC);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO message_metadata(account_id,provider,provider_message_id,"
                        + "provider_thread_id,received_at,labels,to_values,message_id_values,"
                        + "last_synced_at) VALUES (?,'google','pg-message','pg-thread',?,"
                        + "ARRAY['INBOX','UNREAD'],ARRAY['me@example.com'],ARRAY['<pg@example>'],?)")) {
                statement.setLong(1, accountId);
                statement.setObject(2, received);
                statement.setObject(3, received.plusMinutes(1));
                statement.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT labels,to_values,message_id_values,received_at "
                         + "FROM message_metadata WHERE provider_message_id='pg-message'")) {
                assertThat(result.next()).isTrue();
                assertThat((String[]) result.getArray("labels").getArray())
                    .containsExactly("INBOX", "UNREAD");
                assertThat((String[]) result.getArray("to_values").getArray())
                    .containsExactly("me@example.com");
                assertThat((String[]) result.getArray("message_id_values").getArray())
                    .containsExactly("<pg@example>");
                assertThat(result.getObject("received_at", OffsetDateTime.class))
                    .isEqualTo(received);
            }

            long newProgressId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sync_progress(account_id,job_type,status) "
                        + "VALUES (?,'INCREMENTAL_SYNC','QUEUED')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, accountId);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    newProgressId = keys.getLong(1);
                }
            }
            assertThat(newProgressId).isGreaterThan(progressId);

            List<String> indexes = queryStrings(connection,
                "SELECT indexname FROM pg_indexes WHERE tablename='message_metadata'");
            assertThat(indexes).contains(
                "idx_message_metadata_account_received",
                "idx_message_metadata_account_sender_received",
                "idx_message_metadata_account_domain_received",
                "idx_message_metadata_account_unread_received",
                "idx_message_metadata_account_thread",
                "idx_message_metadata_account_last_synced");
        }

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                InboxCleanerApplication.class)
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
                      "--MICROSOFT_CLIENT_SECRET=")) {
            assertThat(context.isActive()).isTrue();
            try (Connection validated = context.getBean(DataSource.class).getConnection()) {
                assertThat(validated.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            }
        }
    }

    private static Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertAccount(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_accounts(email,provider,provider_account_id,"
                    + "encrypted_access_token,encrypted_refresh_token,scopes) "
                    + "VALUES (?,'google',?,decode('01','hex'),decode('02','hex'),ARRAY['openid'])",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, "provider-" + email);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertProgress(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sync_progress(account_id,job_type,status,messages_scanned,"
                    + "finished_at,error_message) VALUES (?,'INCREMENTAL_SYNC','SUCCESS',91,now(),"
                    + "'legacy-safe')",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertLegacyAction(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_actions(account_id,action_type,message_ids,inverse_action_data,"
                    + "target_id_json,status) VALUES (?,'ARCHIVE',ARRAY['legacy-message'],"
                    + "'{}'::jsonb,'{}'::jsonb,'QUEUED')",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertActionScope(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO action_scopes(account_id,action_type,target_kind,message_ids,"
                    + "scope_hash,message_count,target_summary,risk_summary) "
                    + "VALUES (?,'ARCHIVE','MESSAGE_IDS',ARRAY['m1'],repeat('a',64),1,"
                    + "'{\"kind\":\"exact\"}'::jsonb,'{\"level\":\"low\"}'::jsonb)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void attachForeignScope(
            Connection connection, long accountId, long scopeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_actions(account_id,action_type,message_ids,inverse_action_data,"
                    + "target_id_json,status,action_scope_id) VALUES (?,'ARCHIVE',ARRAY['m1'],"
                    + "'{}'::jsonb,'{}'::jsonb,'QUEUED',?)")) {
            statement.setLong(1, accountId);
            statement.setLong(2, scopeId);
            statement.executeUpdate();
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (result.next()) values.add(result.getString(1));
            return values;
        }
    }
}
