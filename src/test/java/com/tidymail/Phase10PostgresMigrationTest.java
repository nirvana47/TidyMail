package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.SelectionMode;
import com.tidymail.dto.SelectionRequest;
import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionScopePreview.State;
import com.tidymail.dto.SelectionSurface;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.DecisionSurfaceQueryRepository.SelectionLocator;
import com.tidymail.service.SelectionMaterializationService;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.UnreadDecisionQueryService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class Phase10PostgresMigrationTest {
    private static final String PRODUCTION_MIGRATIONS =
        "filesystem:src/main/resources/db/migration";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void productionV7UpgradeQueriesAndHibernateValidationRemainCompatible() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(PRODUCTION_MIGRATIONS)
            .target("6")
            .load()
            .migrate();

        long accountId;
        long metadataId;
        long actionId;
        try (Connection connection = connection()) {
            accountId = insertAccount(connection, "phase10-postgres@example.com");
            metadataId = insertMetadata(connection, accountId, "preserved", 0, true);
            actionId = insertAction(connection, accountId);
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(PRODUCTION_MIGRATIONS)
            .load()
            .migrate();

        long protectedId;
        long capAccountId;
        try (Connection connection = connection()) {
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM message_metadata WHERE id=" + metadataId)).isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM user_actions WHERE id=" + actionId)).isEqualTo(1);

            protectedId = insertProtected(connection, accountId, "protected@example.com");
            long nextProtectedId =
                insertProtected(connection, accountId, "second-protected@example.com");
            assertThat(nextProtectedId).isGreaterThan(protectedId);
            assertThatThrownBy(() ->
                insertProtected(connection, accountId, "protected@example.com"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() ->
                insertProtected(connection, accountId, "Protected@Example.com"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() ->
                insertAudit(connection, accountId, "A".repeat(64), 0))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() ->
                insertAudit(connection, accountId, "a".repeat(64), -1))
                .isInstanceOf(SQLException.class);

            assertThat(columns(connection, "protected_senders")).containsExactly(
                "id", "account_id", "sender_address", "source", "created_at");
            assertThat(columns(connection, "protected_override_audits")).containsExactly(
                "id", "account_id", "selection_surface", "selection_mode", "action_type",
                "query_fingerprint", "protected_sender_count", "protected_message_count",
                "reason_code", "created_at");
            assertThat(indexes(connection, "protected_senders")).contains(
                "protected_senders_pkey", "uq_protected_senders_account_address",
                "idx_protected_senders_account_created");
            assertThat(indexes(connection, "message_metadata")).contains(
                "idx_message_metadata_account_sender_window",
                "idx_message_metadata_account_unread_sender_time");

            Instant now = Instant.now();
            insertNullFirstArrayMetadata(connection, accountId, now);
            capAccountId = insertOwnedAccount(connection, "phase10-cap@example.com");
            connection.setAutoCommit(false);
            for (int i = 0; i < 1_000; i++) {
                insertMetadata(connection, capAccountId, "cap-" + i, 0, true, now);
            }
            connection.commit();
            for (int i = 0; i < 10_000; i++) {
                insertMetadata(connection, accountId, "bulk-" + i, i,
                    i % 2 == 0, now);
            }
            connection.commit();
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE message_metadata");
                statement.execute("SET enable_seqscan=off");
                try (ResultSet result = statement.executeQuery("""
                        EXPLAIN SELECT id FROM message_metadata
                        WHERE account_id=%d AND unread=true
                        ORDER BY from_address, received_at DESC, id DESC LIMIT 25
                        """.formatted(accountId))) {
                    StringBuilder plan = new StringBuilder();
                    while (result.next()) plan.append(result.getString(1));
                    assertThat(plan.toString())
                        .contains("idx_message_metadata_account_unread_sender_time");
                }
            }
        }

        try (ConfigurableApplicationContext context = application()) {
            assertThat(context.isActive()).isTrue();
            try (Connection validated = context.getBean(DataSource.class).getConnection()) {
                assertThat(validated.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            }
            SenderDecisionQueryService senders =
                context.getBean(SenderDecisionQueryService.class);
            UnreadDecisionQueryService unread =
                context.getBean(UnreadDecisionQueryService.class);
            DecisionSurfaceQueryRepository decisionRepository =
                context.getBean(DecisionSurfaceQueryRepository.class);
            SelectionMaterializationService materialization =
                context.getBean(SelectionMaterializationService.class);
            SenderQuery senderQuery = SenderQuery.parse(Map.of(
                "window", List.of("all"), "queue", List.of("all"),
                "protected", List.of("include")));
            UnreadQuery unreadQuery = UnreadQuery.parse(Map.of(
                "provider", List.of("google"), "sort", List.of("newest_first")))
                .withPage(1_000);
            UnreadQuery materializationUnreadQuery = unreadQuery.withPage(0);

            assertThat(senders.query(List.of(accountId), senderQuery).filteredCount())
                .isEqualTo(501);
            assertThat(unread.query(List.of(accountId), unreadQuery).filteredCount())
                .isEqualTo(5_002);
            assertThat(senders.query(List.of(accountId), SenderQuery.parse(Map.of(
                "search", List.of("null-array@example.com"),
                "window", List.of("all"), "type", List.of("unknown"),
                "queue", List.of("all"), "protected", List.of("include")))).filteredCount())
                .isEqualTo(1);
            assertThat(unread.query(List.of(accountId), UnreadQuery.parse(Map.of(
                "search", List.of("null-array@example.com"),
                "type", List.of("one_to_one"))).withPage(1)).filteredCount())
                .isEqualTo(1);
            senders.query(List.of(accountId), senderQuery);
            unread.query(List.of(accountId), unreadQuery);
            assertTimeout(Duration.ofSeconds(2),
                () -> senders.query(List.of(accountId), senderQuery));
            assertTimeout(Duration.ofSeconds(2),
                () -> unread.query(List.of(accountId), unreadQuery));

            Instant materializedAt = Instant.now();
            assertThat(decisionRepository.materializeSenders(
                List.of(accountId), senderQuery, SelectionMode.INDIVIDUAL,
                List.of(new SelectionLocator(accountId, "sender0@example.com")),
                false, materializedAt).accounts().getFirst().exactMessageCount())
                .isEqualTo(21);
            assertThat(decisionRepository.materializeSenders(
                List.of(accountId), senderQuery, SelectionMode.VISIBLE_PAGE,
                List.of(), false, materializedAt).accounts().getFirst().includedTargetCount())
                .isEqualTo(25);
            assertThat(decisionRepository.materializeSenders(
                List.of(accountId), senderQuery, SelectionMode.ALL_MATCHING,
                List.of(), false, materializedAt).accounts().getFirst().exactMessageCount())
                .isGreaterThan(1000);
            assertThat(decisionRepository.materializeUnread(
                List.of(accountId), materializationUnreadQuery, SelectionMode.INDIVIDUAL,
                List.of(new SelectionLocator(accountId, Long.toString(metadataId))),
                false, materializedAt).accounts().getFirst().exactMessageCount())
                .isEqualTo(1);
            assertThat(decisionRepository.materializeUnread(
                List.of(accountId), materializationUnreadQuery, SelectionMode.VISIBLE_PAGE,
                List.of(), false, materializedAt).accounts().getFirst().exactMessageCount())
                .isEqualTo(25);
            assertThat(decisionRepository.materializeUnread(
                List.of(accountId), materializationUnreadQuery, SelectionMode.ALL_MATCHING,
                List.of(), false, materializedAt).accounts().getFirst().exactMessageCount())
                .isGreaterThan(1000);

            SenderQuery capQuery = SenderQuery.parse(Map.of(
                "account", List.of(Long.toString(capAccountId)),
                "window", List.of("all"),
                "queue", List.of("all"),
                "protected", List.of("include")));
            SelectionRequest capRequest = new SelectionRequest(
                SelectionSurface.SENDERS,
                SelectionMode.ALL_MATCHING,
                capQuery,
                null,
                List.of(),
                PreviewAction.ARCHIVE,
                false,
                null);
            assertThat(materialization.preview(List.of(capAccountId), capRequest).state())
                .isEqualTo(State.READY);
            assertThat(materialization.preview(List.of(capAccountId), capRequest).messageCount())
                .isEqualTo(1_000);
            try (Connection connection = connection()) {
                assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM action_scopes WHERE account_id=" + capAccountId))
                    .isEqualTo(1);
                assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM user_actions WHERE account_id=" + capAccountId))
                    .isZero();
                insertMetadata(connection, capAccountId, "cap-1000", 0, true);
            }
            assertThat(materialization.preview(List.of(capAccountId), capRequest).state())
                .isEqualTo(State.CAP_EXCEEDED);
            try (Connection connection = connection()) {
                assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM action_scopes WHERE account_id=" + capAccountId))
                    .isEqualTo(1);
                assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM protected_override_audits "
                        + "WHERE account_id=" + capAccountId)).isZero();
                assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM user_actions WHERE account_id=" + capAccountId))
                    .isZero();
            }
        }

        try (Connection connection = connection();
             PreparedStatement delete = connection.prepareStatement(
                 "DELETE FROM user_accounts WHERE id=?")) {
            delete.setLong(1, accountId);
            delete.executeUpdate();
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM protected_senders WHERE id=" + protectedId)).isZero();
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

    private static Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insertAccount(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,encrypted_access_token,
                    encrypted_refresh_token,scopes)
                VALUES (?,'google',?,decode('01','hex'),decode('02','hex'),ARRAY['openid'])
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, "provider-" + email);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertOwnedAccount(Connection connection, String email)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,owner_key,encrypted_access_token,
                    encrypted_refresh_token,scopes)
                VALUES (
                    ?,'google',?,?,decode('01','hex'),decode('02','hex'),ARRAY['openid']
                )
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, "provider-" + email);
            statement.setString(3, email.toLowerCase(java.util.Locale.ROOT) + ":google");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertMetadata(
            Connection connection, long accountId, String providerId, int index, boolean unread)
            throws SQLException {
        return insertMetadata(
            connection, accountId, providerId, index, unread, Instant.now());
    }

    private static long insertMetadata(
            Connection connection, long accountId, String providerId, int index,
            boolean unread, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,from_address,from_display_name,
                    from_domain,subject_header,received_at,last_synced_at,unread,labels)
                VALUES (?,'google',?,?,?,?,?,?,?,?,ARRAY['INBOX']::text[])
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.setString(2, providerId);
            statement.setString(3, "sender" + (index % 500) + "@example.com");
            statement.setString(4, "Sender " + (index % 500));
            statement.setString(5, "example.com");
            statement.setString(6, "Subject " + index);
            statement.setObject(7,
                now.minus(index % 100, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC));
            statement.setObject(8, now.atOffset(ZoneOffset.UTC));
            statement.setBoolean(9, unread);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertAction(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_actions(
                    account_id,action_type,message_ids,inverse_action_data,target_id_json,status)
                VALUES (?,'ARCHIVE',ARRAY['legacy'],'{}'::jsonb,'{}'::jsonb,'QUEUED')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertNullFirstArrayMetadata(
            Connection connection, long accountId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,from_address,from_display_name,
                    from_domain,subject_header,received_at,last_synced_at,unread,
                    labels,auto_submitted_values,precedence_values)
                VALUES (?,'google','null-first-array','null-array@example.com','Null array',
                    'example.com','Null first array values',?,?,true,ARRAY['INBOX']::text[],
                    ARRAY[NULL]::text[],ARRAY[NULL]::text[])
                """)) {
            statement.setLong(1, accountId);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.setObject(3, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static long insertProtected(
            Connection connection, long accountId, String address) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO protected_senders(account_id,sender_address,source)
                VALUES (?,?,'USER')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.setString(2, address);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertAudit(
            Connection connection, long accountId, String fingerprint, int senderCount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO protected_override_audits(
                    account_id,selection_surface,selection_mode,action_type,
                    query_fingerprint,protected_sender_count,protected_message_count,reason_code)
                VALUES (?,'UNREAD','INDIVIDUAL','REVIEW',?,?,0,'OTHER_REVIEWED')
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, fingerprint);
            statement.setInt(3, senderCount);
            statement.executeUpdate();
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
