package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.UnreadQuery;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase15PostgresMigrationTest {
    private static final String MIGRATIONS =
        "filesystem:src/main/resources/db/migration";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void postgresV13ToV14PreservesCategoriesAndEnforcesOwnedOverrides()
            throws Exception {
        Flyway throughV13 = flyway("13");
        throughV13.clean();
        assertThat(throughV13.migrate().migrationsExecuted).isEqualTo(13);

        long first;
        long second;
        long category;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            first = generated(statement, """
                INSERT INTO user_accounts(
                    owner_key,email,provider,provider_account_id,
                    encrypted_access_token,encrypted_refresh_token,scopes
                ) VALUES (
                    'owner','first@example.com','google','phase15-first',
                    decode('01','hex'),decode('02','hex'),ARRAY['email']
                ) RETURNING id
                """);
            second = generated(statement, """
                INSERT INTO user_accounts(
                    owner_key,email,provider,provider_account_id,
                    encrypted_access_token,encrypted_refresh_token,scopes
                ) VALUES (
                    'owner','second@example.com','google','phase15-second',
                    decode('01','hex'),decode('02','hex'),ARRAY['email']
                ) RETURNING id
                """);
            category = generated(statement, """
                INSERT INTO sender_categories(account_id,name,name_key)
                VALUES (%d,'Travel','travel') RETURNING id
                """.formatted(first));
            statement.executeUpdate("""
                INSERT INTO sender_category_assignments(
                    account_id,category_id,sender_address
                ) VALUES (%d,%d,'same@example.com')
                """.formatted(first, category));
        }

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(6);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(queryLong(connection, """
                SELECT COUNT(*) FROM sender_category_assignments
                 WHERE account_id=%d AND category_id=%d
                   AND sender_address='same@example.com'
                """.formatted(first, category))).isOne();
            statement.executeUpdate("""
                INSERT INTO sender_type_overrides(
                    account_id,sender_address,sender_type
                ) VALUES (%d,'same@example.com','PERSONAL'),
                         (%d,'same@example.com','AUTOMATED')
                """.formatted(first, second));
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM sender_type_overrides")).isEqualTo(2);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO sender_type_overrides(
                    account_id,sender_address,sender_type
                ) VALUES (%d,'same@example.com','LIST')
                """.formatted(first))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO sender_type_overrides(
                    account_id,sender_address,sender_type
                ) VALUES (%d,'Mixed@Example.com','LIST')
                """.formatted(first))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO sender_type_overrides(
                    account_id,sender_address,sender_type
                ) VALUES (%d,'%s','LIST')
                """.formatted(first, "a".repeat(310) + "@example.com")))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO sender_type_overrides(
                    account_id,sender_address,sender_type
                ) VALUES (%d,'unsupported@example.com','UNKNOWN')
                """.formatted(first))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO sender_type_overrides(
                    account_id,sender_address,sender_type
                ) VALUES (999999,'foreign@example.com','LIST')
                """)).isInstanceOf(SQLException.class);
            assertThat(indexDefinition(connection))
                .contains("(account_id, sender_type, sender_address)");

            statement.executeUpdate("""
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,from_address,from_domain,
                    received_at,unread,labels,list_id_values
                ) VALUES
                  (%d,'google','inbox-unread','same@example.com','example.com',
                   now(),TRUE,ARRAY['INBOX'],ARRAY['list.example.com']),
                  (%d,'google','inbox-read','same@example.com','example.com',
                   now(),FALSE,ARRAY['INBOX'],ARRAY['list.example.com']),
                  (%d,'google','archived-unread','same@example.com','example.com',
                   now(),TRUE,ARRAY[]::TEXT[],ARRAY['list.example.com']),
                  (%d,'google','spam-unread','same@example.com','example.com',
                   now(),TRUE,ARRAY['SPAM'],ARRAY['list.example.com']),
                  (%d,'google','trash-unread','same@example.com','example.com',
                   now(),TRUE,ARRAY['TRASH'],ARRAY['list.example.com'])
                """.formatted(first, first, first, first, first));
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            DecisionSurfaceQueryRepository decisions = new DecisionSurfaceQueryRepository(
                new NamedParameterJdbcTemplate(dataSource));
            UnreadQuery unreadQuery = UnreadQuery.parse(Map.of(
                "account", List.of(Long.toString(first))));
            assertThat(decisions.unread(
                    List.of(first), unreadQuery, Instant.now()).rows())
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.providerMessageId()).isEqualTo("inbox-unread");
                    assertThat(fact.category())
                        .isEqualTo(com.tidymail.service.DecisionRuleSet.UnreadCategory.ONE_TO_ONE);
                    assertThat(fact.manualType())
                        .isEqualTo(com.tidymail.service.DecisionRuleSet.SenderType.PERSONAL);
                });
            assertThat(decisions.countUnread(
                List.of(first), unreadQuery, Instant.now())).isOne();

            statement.executeUpdate("DELETE FROM user_accounts WHERE id=" + first);
            assertThat(queryLong(connection, """
                SELECT COUNT(*) FROM sender_type_overrides WHERE account_id=%d
                """.formatted(first))).isZero();
            assertThat(queryLong(connection, """
                SELECT COUNT(*) FROM sender_type_overrides WHERE account_id=%d
                """.formatted(second))).isOne();
        }
        assertHibernateValidation();
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

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long generated(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String indexDefinition(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname='idx_sender_type_overrides_account_type_sender'
                """)) {
            result.next();
            return result.getString(1);
        }
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
}
