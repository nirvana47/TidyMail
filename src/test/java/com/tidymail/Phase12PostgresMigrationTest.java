package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.ExecutionTargetStatus;
import com.tidymail.action.OperationKind;
import com.tidymail.dto.ActionHistoryModel;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.HistoryQuery;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.entity.ActionExecution;
import com.tidymail.entity.ActionExecutionTarget;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeTargetRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionHistoryService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.ReviewActionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase12PostgresMigrationTest {
    private static final String MIGRATIONS =
        "filesystem:src/main/resources/db/migration";
    private static final String PRODUCTION_V8 =
        "190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461";
    private static final String PRODUCTION_V9 =
        "c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void ACTN_07_productionV1ToV9AndV8UpgradePreserveTruthAndEnforceLedger()
            throws Exception {
        assertThat(sha256(Path.of(
            "src/main/resources/db/migration/V8__safe_unsubscribe.sql")))
            .isEqualTo(PRODUCTION_V8);
        assertThat(sha256(Path.of(
            "src/main/resources/db/migration/V9__action_execution_history_and_undo.sql")))
            .isEqualTo(PRODUCTION_V9);

        Flyway full = flyway(null);
        full.clean();
        assertThat(full.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        try (Connection connection = connection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertV9(connection);
        }
        assertHibernateValidation();

        Flyway throughV8 = flyway("8");
        throughV8.clean();
        assertThat(throughV8.migrate().migrationsExecuted).isEqualTo(8);
        long accountId;
        long scopeId;
        long actionId;
        long nonceId;
        long attemptId;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            accountId = generated(statement, """
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,encrypted_access_token,
                    encrypted_refresh_token,scopes
                ) VALUES (
                    'upgrade@example.com','google','provider-upgrade',
                    decode('01','hex'),decode('02','hex'),ARRAY['email']
                ) RETURNING id
                """);
            scopeId = generated(statement, """
                INSERT INTO action_scopes(
                    account_id,action_type,target_kind,message_ids,scope_hash,
                    message_count,target_summary,risk_summary
                ) VALUES (
                    %d,'ARCHIVE','SENDER_SELECTION',ARRAY['one','two'],
                    '%s',2,
                    '{"displayLabel":"2 selected senders","senderAddress":null,
                      "senderDomain":null,"selectedTargetCount":2}'::jsonb,
                    '{"protectedCount":0,"highRiskCount":0,"warningCodes":[]}'::jsonb
                ) RETURNING id
                """.formatted(accountId, "a".repeat(64)));
            actionId = generated(statement, """
                INSERT INTO user_actions(
                    account_id,action_scope_id,action_type,message_ids,
                    inverse_action_data,target_kind,target_id_json,affected_count,status
                ) VALUES (
                    %d,%d,'ARCHIVE',ARRAY['one','two'],'{}'::jsonb,
                    'SENDER_SELECTION','{}'::jsonb,2,'APPLIED'
                ) RETURNING id
                """.formatted(accountId, scopeId));
            nonceId = generated(statement, """
                INSERT INTO unsubscribe_intent_nonces(
                    account_id,nonce_fingerprint,candidate_fingerprint,
                    destination_fingerprint,method,expires_at
                ) VALUES (
                    %d,'%s','%s','%s','RFC8058_POST',now()+interval '5 minutes'
                ) RETURNING id
                """.formatted(
                    accountId, "b".repeat(64), "c".repeat(64), "d".repeat(64)));
            attemptId = generated(statement, """
                INSERT INTO unsubscribe_attempts(
                    account_id,candidate_fingerprint,sender_key_fingerprint,
                    method,latest_status
                ) VALUES (
                    %d,'%s','%s','RFC8058_POST','VERIFICATION_PENDING'
                ) RETURNING id
                """.formatted(accountId, "1".repeat(64), "2".repeat(64)));
            execute(connection, """
                INSERT INTO unsubscribe_outcome_events(
                    account_id,attempt_id,status,reason_code
                ) VALUES (%d,%d,'VERIFICATION_PENDING','TIMEOUT_UNKNOWN_REMOTE_RESULT')
                """.formatted(accountId, attemptId));
            execute(connection, """
                INSERT INTO protected_senders(account_id,sender_address,source)
                VALUES (%d,'protected@example.com','USER')
                """.formatted(accountId));
            execute(connection, """
                INSERT INTO protected_override_audits(
                    account_id,selection_surface,selection_mode,action_type,
                    query_fingerprint,protected_sender_count,protected_message_count,
                    reason_code
                ) VALUES (
                    %d,'SENDERS','INDIVIDUAL','ARCHIVE','%s',1,1,'USER_CONFIRMED'
                )
                """.formatted(accountId, "3".repeat(64)));
        }

        assertThat(flyway("9").migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = connection()) {
            assertV9(connection);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM user_actions WHERE id=" + actionId
                    + " AND public_id IS NOT NULL AND execution_mode='STAGED'"
                    + " AND decision_source='LEGACY' AND status='APPLIED'"))
                .isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM action_scope_targets WHERE scope_id=" + scopeId))
                .isEqualTo(2);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM action_executions WHERE action_id=" + actionId))
                .isZero();
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM action_events WHERE action_id=" + actionId))
                .isZero();
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM unsubscribe_intent_nonces WHERE id=" + nonceId))
                .isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM unsubscribe_attempts WHERE id=" + attemptId))
                .isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM unsubscribe_outcome_events WHERE attempt_id="
                    + attemptId)).isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM protected_senders WHERE account_id=" + accountId))
                .isEqualTo(1);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM protected_override_audits WHERE account_id="
                    + accountId)).isEqualTo(1);
            assertThat(generated(connection.createStatement(), """
                INSERT INTO user_actions(
                    account_id,action_scope_id,action_type,message_ids,
                    inverse_action_data,target_kind,target_id_json,affected_count,status
                ) VALUES (
                    %d,%d,'ARCHIVE',ARRAY['one','two'],'{}'::jsonb,
                    'SENDER_SELECTION','{}'::jsonb,2,'QUEUED'
                ) RETURNING id
                """.formatted(accountId, scopeId))).isGreaterThan(actionId);
            assertConstraints(connection, accountId, actionId, scopeId);
        }
        assertThat(flyway(null).migrate().migrationsExecuted).isGreaterThanOrEqualTo(4);
        assertHibernateValidation();
    }

    @Test
    void ACTN_01_ACTN_06_ACTN_08_realPostgresRacesEnforceSingleWinners()
            throws Exception {
        Flyway full = flyway(null);
        full.clean();
        assertThat(full.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        long accountId;
        long scopeId;
        long actionId;
        long secondActionId;
        try (Connection connection = connection()) {
            accountId = insertAccount(connection, "race@example.com");
            scopeId = insertScope(connection, accountId, "race-a", "race-b");
            actionId = insertAction(connection, accountId, scopeId, "ARCHIVE");
            secondActionId = insertAction(connection, accountId, scopeId, "MARK_READ");
        }

        assertConcurrentInsertWinner(accountId, actionId, "FORWARD");
        assertConcurrentInsertWinner(accountId, actionId, "UNDO");
        assertThat(countExecutions(actionId, "FORWARD")).isOne();
        assertThat(countExecutions(actionId, "UNDO")).isOne();

        String sharedIdempotency = "7".repeat(64);
        AtomicInteger idempotencyWinners = new AtomicInteger();
        runTogether(
            () -> insertExecution(accountId, secondActionId, "FORWARD", sharedIdempotency,
                idempotencyWinners),
            () -> {
                try (Connection connection = connection()) {
                    long thirdAction =
                        insertAction(connection, accountId, scopeId, "KEEP");
                    insertExecution(accountId, thirdAction, "FORWARD",
                        sharedIdempotency, idempotencyWinners);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        assertThat(idempotencyWinners).hasValue(1);

        assertLeaseCompareAndSetWinner(accountId, actionId);
        assertExactConflictOverlapIsAccountLocal(accountId, scopeId, actionId);
    }

    @Test
    void ACTN_06_ACTN_07_ACTN_08_postgresLedgerFactsRemainExact() {
        Flyway full = flyway(null);
        full.clean();
        assertThat(full.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        try (ConfigurableApplicationContext context = application()) {
            DialectFacts facts = dialectFacts(context, "postgres-ledger@example.com");
            assertThat(facts.queueRows()).isEqualTo(1);
            assertThat(facts.queuedActions()).isEqualTo(1);
            assertThat(facts.exactTargets()).isEqualTo(1);
            assertThat(facts.eligibleActions()).isEqualTo(1);
            assertThat(facts.historyRows()).isEqualTo(1);
            assertThat(facts.succeededTargets()).isEqualTo(1);
            assertThat(facts.action()).isEqualTo("Archive");
            assertThat(facts.mode()).isEqualTo("Staged");
            assertThat(facts.status()).isEqualTo("Applied");
            assertThat(facts.appliedCount()).isEqualTo(1);
            assertThat(facts.undoEligible()).isTrue();
            assertThat(facts.requested()).isEqualTo(1);
            assertThat(facts.succeeded()).isEqualTo(1);
        }
    }

    @Test
    void ACTN_10_postgresRepresentativeHistoryAndQueueStayIndexedUnderTwoSeconds()
            throws Exception {
        Flyway full = flyway(null);
        full.clean();
        assertThat(full.migrate().migrationsExecuted).isGreaterThanOrEqualTo(13);
        long accountId;
        try (Connection connection = connection()) {
            accountId = insertAccount(connection, "performance@example.com");
            execute(connection, """
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,provider_thread_id,
                    from_address,from_display_name,from_domain,received_at,labels
                )
                SELECT %d,'google','perf-' || value,'thread-' || value,
                       'sender' || (value %% 100) || '@example.com',
                       'Sender ' || (value %% 100),'example.com',
                       TIMESTAMPTZ '2026-08-09 12:00:00+00'
                           - (value || ' seconds')::interval,
                       ARRAY[]::varchar[]
                  FROM generate_series(1,10000) value
                """.formatted(accountId));
            execute(connection, """
                INSERT INTO action_scopes(
                    account_id,action_type,target_kind,message_ids,scope_hash,
                    message_count,target_summary,risk_summary
                )
                SELECT %d,
                       CASE WHEN value %% 2 = 0 THEN 'ARCHIVE' ELSE 'MARK_READ' END,
                       'UNREAD_SELECTION',ARRAY['perf-' || value],
                       encode(digest('scope-' || value, 'sha256'),'hex'),1,
                       jsonb_build_object('displayLabel','Sender ' || (value %% 100),
                           'senderAddress',NULL,'senderDomain','example.com',
                           'selectedTargetCount',1),
                       '{"protectedCount":0,"highRiskCount":0,"warningCodes":[]}'::jsonb
                  FROM generate_series(1,500) value
                """.formatted(accountId));
            execute(connection, """
                INSERT INTO user_actions(
                    account_id,action_scope_id,action_type,message_ids,
                    inverse_action_data,target_kind,target_id_json,affected_count,
                    status,execution_mode,decision_source,completed_at
                )
                SELECT s.account_id,s.id,s.action_type,s.message_ids,'{}'::jsonb,
                       s.target_kind,'{}'::jsonb,1,
                       CASE WHEN s.id %% 3 = 0 THEN 'FAILED' ELSE 'APPLIED' END,
                       CASE WHEN s.id %% 2 = 0 THEN 'IMMEDIATE' ELSE 'STAGED' END,
                       'UNREAD',TIMESTAMPTZ '2026-08-09 12:00:00+00'
                           - (s.id || ' seconds')::interval
                  FROM action_scopes s WHERE s.account_id=%d
                """.formatted(accountId));
            execute(connection, """
                INSERT INTO action_intents(
                    action_id,account_id,subject_kind,safe_target_label,target_search_key
                )
                SELECT id,account_id,'MESSAGES','Sender ' || (id %% 100),
                       'sender' || (id %% 100) || '@example.com'
                  FROM user_actions WHERE account_id=%d
                """.formatted(accountId));
            execute(connection, "ANALYZE message_metadata");
            execute(connection, "ANALYZE user_actions");
            execute(connection, "ANALYZE action_intents");

            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM message_metadata WHERE account_id=" + accountId))
                .isEqualTo(10_000);
            assertThat(queryLong(connection,
                "SELECT COUNT(*) FROM user_actions WHERE account_id=" + accountId))
                .isEqualTo(500);
            assertThat(string(connection, """
                SELECT provider_message_id FROM message_metadata
                 WHERE account_id=%d ORDER BY received_at DESC,id DESC LIMIT 1
                """.formatted(accountId))).isEqualTo("perf-1");
            assertThat(string(connection, """
                SELECT provider_message_id FROM message_metadata
                 WHERE account_id=%d ORDER BY received_at,id LIMIT 1
                """.formatted(accountId))).isEqualTo("perf-10000");

            String queue = """
                SELECT id FROM user_actions
                 WHERE account_id=%d AND status='APPLIED'
                 ORDER BY created_at DESC,id DESC LIMIT 25
                """.formatted(accountId);
            String history = """
                SELECT a.id FROM user_actions a
                JOIN action_intents i
                  ON i.action_id=a.id AND i.account_id=a.account_id
                 WHERE a.account_id=%d AND a.completed_at IS NOT NULL
                   AND i.target_search_key LIKE 'sender%%@example.com'
                 ORDER BY a.completed_at DESC,a.id DESC LIMIT 25
                """.formatted(accountId);
            queryLong(connection, "SELECT COUNT(*) FROM (" + queue + ") warmed");
            queryLong(connection, "SELECT COUNT(*) FROM (" + history + ") warmed");
            Duration queueDuration = assertUnderTwoSeconds(connection, queue);
            Duration historyDuration = assertUnderTwoSeconds(connection, history);
            System.out.printf(
                "PHASE12_PERFORMANCE queue_ms=%d history_ms=%d messages=10000 actions=500%n",
                queueDuration.toMillis(), historyDuration.toMillis());
            assertPlanUses(connection, queue, "idx_user_actions_account_status_created_v9");
            assertPlanUses(connection, history, "idx_user_actions_account_completed_v9");
        }
    }

    private static void assertV9(Connection connection) throws Exception {
        assertThat(queryLong(connection, "SHOW server_version_num"))
            .isBetween(160000L, 169999L);
        assertThat(queryLong(connection,
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success"))
            .isGreaterThanOrEqualTo(9);
        assertThat(tables(connection)).contains(
            "action_intents", "action_scope_targets", "action_command_tokens",
            "action_executions", "action_execution_targets", "action_events",
            "action_conflicts");
        assertThat(indexes(connection)).contains(
            "idx_action_executions_account_status_started",
            "idx_action_scope_targets_account_fingerprint",
            "idx_action_events_account_time",
            "idx_action_conflicts_account_status_created");
        assertThat(columns(connection, "action_execution_targets"))
            .doesNotContain("provider_message_id", "message_id", "uri", "response_body");
        assertThat(constraints(connection)).contains(
            "action_executions_counts_check",
            "fk_action_execution_targets_scope_target",
            "fk_action_events_execution_action_account",
            "uq_action_executions_action_operation",
            "uq_action_executions_account_idempotency",
            "uq_action_execution_targets_execution_ordinal");
    }

    private static void assertConstraints(
            Connection connection, long accountId, long actionId, long scopeId) {
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count
            ) VALUES (%d,%d,'FORWARD','RESERVED','%s',2,0,0,0,0,1)
            """.formatted(accountId, actionId, "e".repeat(64))))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO action_scope_targets(
                scope_id,account_id,scope_ordinal,target_fingerprint
            ) VALUES (%d,%d,9,'not-a-fingerprint')
            """.formatted(scopeId, accountId)))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> execute(connection,
            "UPDATE user_actions SET execution_mode='BYPASS' WHERE id=" + actionId))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> execute(connection, """
            INSERT INTO action_events(
                account_id,action_id,event_type,action_type,execution_mode,
                decision_source,resulting_status,requested_count,
                not_dispatched_count
            ) VALUES (
                %d,%d,'ACTION_QUEUED','ARCHIVE','IMMEDIATE',
                'UNTRUSTED_SOURCE','QUEUED',2,2
            )
            """.formatted(accountId, actionId)))
            .isInstanceOf(Exception.class);
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

    private static DialectFacts dialectFacts(
            ConfigurableApplicationContext context, String email) {
        UserAccountRepository accounts = context.getBean(UserAccountRepository.class);
        UserActionRepository actions = context.getBean(UserActionRepository.class);
        ActionIntentRepository intents = context.getBean(ActionIntentRepository.class);
        ActionExecutionRepository executions =
            context.getBean(ActionExecutionRepository.class);
        ActionExecutionTargetRepository targets =
            context.getBean(ActionExecutionTargetRepository.class);
        ActionScopeTargetRepository scopeTargets =
            context.getBean(ActionScopeTargetRepository.class);
        ActionScopeService scopeService = context.getBean(ActionScopeService.class);

        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("provider-" + email);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        account = accounts.saveAndFlush(account);

        var preview = scopeService.createOrGet(
            account.getId(), "ARCHIVE", "UNREAD_SELECTION", List.of("parity-message"),
            new ActionScopeTargetSummary("Parity target", null, "example.com", 1),
            new ActionScopeRiskSummary(0, 0, List.of()));
        ActionScope scope = scopeService.requireScope(account.getId(), preview.scopeId());
        UserAction action = new UserAction();
        action.setAccountId(account.getId());
        action.setActionScopeId(scope.getId());
        action.setActionType("ARCHIVE");
        action.setMessageIds(scope.getMessageIds());
        action.setTargetKind(scope.getTargetKind());
        action.setAffectedCount(1);
        action.setStatus("QUEUED");
        action.setExecutionMode("STAGED");
        action.setDecisionSource("UNREAD");
        action = actions.saveAndFlush(action);
        intents.saveAndFlush(ActionIntent.messages(
            action.getId(), account.getId(), "Parity target",
            "parity@example.com", Instant.parse("2026-08-09T12:00:00Z")));

        WorkspacePageModel workspace = new WorkspacePageModel(
            WorkspaceRoute.REVIEW, "Parity", email, List.of(),
            List.of(account.getId()), String.valueOf(account.getId()), email,
            1L, UnreadScopeCounts.available(0, 0),
            1, 1, 1, null, null, WorkspaceState.READY,
            false, false, false);
        var queue = context.getBean(ReviewActionService.class).queue(
            "parity-owner", workspace,
            new ReviewQuery("queued", String.valueOf(account.getId()),
                "archive", "eligible", "Parity", 0));

        Instant now = Instant.parse("2026-08-09T12:01:00Z");
        ActionExecution execution = ActionExecution.reserve(
            account.getId(), action.getId(), OperationKind.FORWARD,
            "4".repeat(64), "5".repeat(64), 1, now, now.plusSeconds(120));
        execution.start(now.plusSeconds(1));
        execution.complete(
            ExecutionStatus.APPLIED, 0, 1, 0, 0, 0, null, now.plusSeconds(2));
        execution = executions.saveAndFlush(execution);
        var scopeTarget = scopeTargets.findByScopeIdAndAccountIdOrderByScopeOrdinal(
            scope.getId(), account.getId()).getFirst();
        ActionExecutionTarget target =
            ActionExecutionTarget.pending(execution.getId(), scopeTarget);
        target.markDispatched(now.plusSeconds(1));
        target.complete(
            ExecutionTargetStatus.SUCCEEDED, false, null, now.plusSeconds(2));
        targets.saveAndFlush(target);
        action.setStatus("APPLIED");
        action.setAppliedAt(now.plusSeconds(2));
        action.setCompletedAt(now.plusSeconds(2));
        action.setUndoEligibleUntil(Instant.now().plusSeconds(3_600));
        actions.saveAndFlush(action);

        ActionHistoryModel history = context.getBean(ActionHistoryService.class).history(
            workspace,
            new HistoryQuery(
                String.valueOf(account.getId()), null, null,
                "archive", "applied", "Parity", 0));
        var row = history.rows().getFirst();
        return new DialectFacts(
            queue.filteredCount(), queue.metrics().queuedActions(),
            queue.metrics().exactTargets(), queue.metrics().eligibleActions(),
            history.totalRows(), history.metrics().succeededTargets(),
            row.action(), row.mode(), row.status(), row.undoAppliedCount(),
            row.undoEligible(), row.outcome().requested(), row.outcome().succeeded());
    }

    private record DialectFacts(
        long queueRows,
        long queuedActions,
        long exactTargets,
        long eligibleActions,
        long historyRows,
        long succeededTargets,
        String action,
        String mode,
        String status,
        int appliedCount,
        boolean undoEligible,
        int requested,
        int succeeded
    ) {
    }

    private static void assertHibernateValidation() {
        try (ConfigurableApplicationContext context = application()) {
            assertThat(context.isActive()).isTrue();
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

    private static long insertAccount(Connection connection, String email)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return generated(statement, """
                INSERT INTO user_accounts(
                    email,provider,provider_account_id,owner_key,encrypted_access_token,
                    encrypted_refresh_token,scopes
                ) VALUES (
                    '%s','google','provider-%s','%s:google',
                    decode('01','hex'),decode('02','hex'),
                    ARRAY['email']
                ) RETURNING id
                """.formatted(email, email, email.toLowerCase(java.util.Locale.ROOT)));
        } catch (Exception failure) {
            throw new SQLException(failure);
        }
    }

    private static long insertScope(
            Connection connection, long accountId, String... messageIds)
            throws Exception {
        String array = java.util.Arrays.stream(messageIds)
            .map(value -> "'" + value.replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","));
        long scopeId;
        try (Statement statement = connection.createStatement()) {
            scopeId = generated(statement, """
                INSERT INTO action_scopes(
                    account_id,action_type,target_kind,message_ids,scope_hash,
                    message_count,target_summary,risk_summary
                ) VALUES (
                    %d,'ARCHIVE','UNREAD_SELECTION',ARRAY[%s],'%s',%d,
                    '{"displayLabel":"Race targets","senderAddress":null,
                      "senderDomain":null,"selectedTargetCount":%d}'::jsonb,
                    '{"protectedCount":0,"highRiskCount":0,"warningCodes":[]}'::jsonb
                ) RETURNING id
                """.formatted(accountId, array, UUID.randomUUID().toString()
                    .replace("-", "") + "0".repeat(32), messageIds.length,
                    messageIds.length));
        }
        for (int index = 0; index < messageIds.length; index++) {
            execute(connection, """
                INSERT INTO action_scope_targets(
                    scope_id,account_id,scope_ordinal,target_fingerprint
                ) VALUES (%d,%d,%d,encode(digest('%s','sha256'),'hex'))
                """.formatted(scopeId, accountId, index, messageIds[index]));
        }
        return scopeId;
    }

    private static long insertAction(
            Connection connection, long accountId, long scopeId, String actionType)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            return generated(statement, """
                INSERT INTO user_actions(
                    account_id,action_scope_id,action_type,message_ids,
                    inverse_action_data,target_kind,target_id_json,affected_count,status
                )
                SELECT %d,%d,'%s',message_ids,'{}'::jsonb,target_kind,'{}'::jsonb,
                       message_count,'QUEUED'
                  FROM action_scopes WHERE id=%d
                RETURNING id
                """.formatted(accountId, scopeId, actionType, scopeId));
        }
    }

    private static void assertConcurrentInsertWinner(
            long accountId, long actionId, String operation) throws Exception {
        AtomicInteger winners = new AtomicInteger();
        runTogether(
            () -> insertExecution(accountId, actionId, operation,
                hex(UUID.randomUUID()), winners),
            () -> insertExecution(accountId, actionId, operation,
                hex(UUID.randomUUID()), winners));
        assertThat(winners).as(operation).hasValue(1);
    }

    private static void insertExecution(
            long accountId, long actionId, String operation,
            String idempotency, AtomicInteger winners) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO action_executions(
                    account_id,action_id,operation_kind,status,idempotency_fingerprint,
                    lease_fingerprint,lease_expires_at,requested_count,
                    not_dispatched_count
                ) VALUES (?,? ,?,'RESERVED',?,?,now()+interval '2 minutes',1,1)
                """)) {
            statement.setLong(1, accountId);
            statement.setLong(2, actionId);
            statement.setString(3, operation);
            statement.setString(4, idempotency);
            statement.setString(5, hex(UUID.randomUUID()));
            statement.executeUpdate();
            winners.incrementAndGet();
        } catch (Exception expectedLoser) {
            assertThat(expectedLoser).isInstanceOf(SQLException.class);
        }
    }

    private static void runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = List.of(
                pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    first.run();
                }),
                pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    second.run();
                }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }

    private static void assertLeaseCompareAndSetWinner(
            long accountId, long actionId) throws Exception {
        long executionId;
        try (Connection connection = connection()) {
            executionId = queryLong(connection, """
                SELECT id FROM action_executions
                 WHERE action_id=%d AND operation_kind='FORWARD'
                """.formatted(actionId));
        }
        try (Connection holder = connection();
             Connection contender = connection()) {
            holder.setAutoCommit(false);
            String originalLease = string(
                holder, "SELECT lease_fingerprint FROM action_executions WHERE id="
                + executionId + " FOR UPDATE");
            try (var pool = Executors.newSingleThreadExecutor()) {
                Future<Integer> stale = pool.submit(() -> {
                    try (PreparedStatement statement = contender.prepareStatement("""
                        UPDATE action_executions SET lease_fingerprint=?
                         WHERE id=? AND lease_fingerprint=?
                        """)) {
                        statement.setString(1, "9".repeat(64));
                        statement.setLong(2, executionId);
                        statement.setString(3, originalLease);
                        return statement.executeUpdate();
                    }
                });
                Thread.sleep(150);
                assertThat(stale.isDone()).isFalse();
                execute(holder, "UPDATE action_executions SET lease_fingerprint='"
                    + "8".repeat(64) + "' WHERE id=" + executionId);
                holder.commit();
                assertThat(stale.get(5, TimeUnit.SECONDS)).isZero();
            }
        }
    }

    private static void assertExactConflictOverlapIsAccountLocal(
            long accountId, long firstScopeId, long firstActionId) throws Exception {
        try (Connection connection = connection()) {
            long secondScopeId = insertScope(connection, accountId, "race-b", "race-c");
            long secondActionId =
                insertAction(connection, accountId, secondScopeId, "TRASH");
            assertThat(queryLong(connection, """
                SELECT COUNT(*) FROM action_scope_targets left_target
                JOIN action_scope_targets right_target
                  ON right_target.account_id=left_target.account_id
                 AND right_target.target_fingerprint=left_target.target_fingerprint
                 WHERE left_target.scope_id=%d AND right_target.scope_id=%d
                """.formatted(firstScopeId, secondScopeId))).isOne();
            execute(connection, """
                INSERT INTO action_conflicts(
                    account_id,lower_action_id,higher_action_id,reason_code,overlap_count
                ) VALUES (%d,%d,%d,'INCOMPATIBLE_ACTIONS',1)
                """.formatted(accountId, Math.min(firstActionId, secondActionId),
                    Math.max(firstActionId, secondActionId)));
            assertThat(queryLong(connection,
                "SELECT overlap_count FROM action_conflicts WHERE account_id="
                    + accountId)).isOne();

            long otherAccountId =
                insertAccount(connection, "race-other-account@example.com");
            long otherScopeId =
                insertScope(connection, otherAccountId, "race-b", "race-c");
            assertThat(queryLong(connection, """
                SELECT COUNT(*) FROM action_scope_targets left_target
                JOIN action_scope_targets right_target
                  ON right_target.account_id=left_target.account_id
                 AND right_target.target_fingerprint=left_target.target_fingerprint
                 WHERE left_target.scope_id=%d AND right_target.scope_id=%d
                """.formatted(firstScopeId, otherScopeId))).isZero();
        }
    }

    private static long countExecutions(long actionId, String operation)
            throws Exception {
        try (Connection connection = connection()) {
            return queryLong(connection, """
                SELECT COUNT(*) FROM action_executions
                 WHERE action_id=%d AND operation_kind='%s'
                """.formatted(actionId, operation));
        }
    }

    private static Duration assertUnderTwoSeconds(Connection connection, String sql)
            throws Exception {
        long started = System.nanoTime();
        strings(connection, sql);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        return elapsed;
    }

    private static void assertPlanUses(
            Connection connection, String sql, String index) throws Exception {
        execute(connection, "SET enable_seqscan=off");
        assertThat(strings(connection, "EXPLAIN " + sql).toString()).contains(index);
    }

    private static String hex(UUID value) {
        return value.toString().replace("-", "") + value.toString().replace("-", "");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static long generated(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> tables(Connection connection) throws Exception {
        return strings(connection, """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema='public'
            """);
    }

    private static List<String> columns(Connection connection, String table)
            throws Exception {
        return strings(connection, """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema='public' AND table_name='%s'
            """.formatted(table));
    }

    private static List<String> indexes(Connection connection) throws Exception {
        return strings(connection,
            "SELECT indexname FROM pg_indexes WHERE schemaname='public'");
    }

    private static List<String> constraints(Connection connection) throws Exception {
        return strings(connection, """
            SELECT constraint_name FROM information_schema.table_constraints
            WHERE table_schema='public'
            """);
    }

    private static String string(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static List<String> strings(Connection connection, String sql)
            throws Exception {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
