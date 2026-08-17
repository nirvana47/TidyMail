package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.UnsubscribeCandidateRow;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import com.tidymail.service.ProviderDeepLinkService;
import com.tidymail.service.UnsubscribeCandidateService;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = false)
class Phase15UnsubscribePerformanceTest {
    private static final int SENDERS = 600;
    private static final int MESSAGES_PER_SENDER = 40;
    private static final int CLEANUP_TARGETS = 300;
    private static final Duration PRODUCT_BUDGET = Duration.ofSeconds(2);
    private static final String MIGRATIONS =
        "filesystem:src/main/resources/db/migration";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tidymail")
            .withUsername("tidymail")
            .withPassword("tidymail");

    @Test
    void navigationIsPageBoundedTruthfulAndWithinTheIndexedProductBudget() throws Exception {
        Flyway database = flyway();
        database.clean();
        assertThat(database.migrate().migrationsExecuted).isGreaterThanOrEqualTo(14);
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = spy(new NamedParameterJdbcTemplate(dataSource));
        UnsubscribeCandidateRepository repository =
            new UnsubscribeCandidateRepository(named);
        UnsubscribeCandidateService service =
            new UnsubscribeCandidateService(repository, new ProviderDeepLinkService());
        long accountId = seedFixture(jdbc);
        jdbc.execute("ANALYZE");

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM message_metadata", Long.class))
            .isEqualTo((long) SENDERS * MESSAGES_PER_SENDER);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM unsubscribe_attempts", Long.class))
            .isEqualTo(SENDERS);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM action_execution_targets", Long.class))
            .isEqualTo(CLEANUP_TARGETS);

        UnsubscribeQuery query = query(Map.of());
        clearInvocations(named);
        long heapBefore = usedHeap();
        TimedPage cold = timed(() -> service.query(List.of(accountId), query));
        long retainedHeap = Math.max(0, usedHeap() - heapBefore);
        List<QueryCall> calls = productionCalls(named);
        Map<String, Long> families = familyCounts(calls);
        Map<String, JsonNode> plans = explainFamilies(named, calls);

        assertThat(cold.page().rows()).hasSize(UnsubscribeQuery.PAGE_SIZE);
        assertThat(cold.page().filteredCount()).isEqualTo(SENDERS);
        assertThat(calls).hasSize(3);
        assertThat(families).containsOnly(
            Map.entry("count", 1L),
            Map.entry("page", 1L),
            Map.entry("source", 1L));
        assertThat(plans).containsOnlyKeys("count", "page", "source");
        QueryCall sourceCall = calls.stream()
            .filter(call -> "source".equals(family(call.sql())))
            .findFirst().orElseThrow();
        assertThat(sourceCall.sql())
            .contains(":page_account_0", ":page_sender_0")
            .contains(":page_account_24", ":page_sender_24")
            .doesNotContain(":page_account_25", ":page_sender_25");
        assertThat(calls.stream()
            .filter(call -> !"source".equals(family(call.sql())))
            .map(QueryCall::sql))
            .allSatisfy(sql -> assertThat(sql)
                .doesNotContain("list_unsubscribe_values AS")
                .doesNotContain("list_unsubscribe_post_values AS"));
        plans.forEach((family, plan) ->
            assertThat(planNodes(plan))
                .as(family + " retains PostgreSQL runtime and buffer evidence")
                .anyMatch(node -> node.has("Actual Total Time")
                    && node.has("Shared Hit Blocks")));

        List<Duration> warm = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            warm.add(timed(() -> service.query(List.of(accountId), query)).elapsed());
        }
        assertThat(cold.elapsed()).isLessThan(PRODUCT_BUDGET);
        assertThat(warm).allMatch(value -> value.compareTo(PRODUCT_BUDGET) < 0);

        DecisionPage<UnsubscribeCandidateRow> confirmed = service.query(
            List.of(accountId), query(Map.of(
                "category", List.of("unsubscribed"),
                "q", List.of("sender005"))));
        assertThat(confirmed.filteredCount()).isOne();
        assertThat(confirmed.rows()).singleElement().satisfies(row -> {
            assertThat(row.senderAddress()).isEqualTo("sender005@bulk.example");
            assertThat(row.lifecycleCategory()).isEqualTo("UNSUBSCRIBED");
            assertThat(row.latestOutcome()).isEqualTo("CONFIRMED");
        });
        DecisionPage<UnsubscribeCandidateRow> cleaned = service.query(
            List.of(accountId), query(Map.of(
                "category", List.of("archived_deleted"),
                "q", List.of("sender001"),
                "defaultAction", List.of("delete"))));
        assertThat(cleaned.rows()).singleElement().satisfies(row -> {
            assertThat(row.lifecycleCategory()).isEqualTo("ARCHIVED_DELETED");
            assertThat(row.unsubscribeExecutable()).isTrue();
            assertThat(row.primaryAction()).isEqualTo("Unsubscribe");
        });

        System.out.printf(
            "PHASE15_UNSUBSCRIBE_PERFORMANCE postgres=%s rows=%d senders=%d "
                + "statements=%d families=%s cold_ms=%d warm_ms=%s heap_bytes=%d "
                + "target_ms=%d%n",
            POSTGRES.getDockerImageName(), SENDERS * MESSAGES_PER_SENDER, SENDERS,
            calls.size(), families, cold.elapsed().toMillis(),
            warm.stream().map(Duration::toMillis).toList(), retainedHeap,
            PRODUCT_BUDGET.toMillis());
        plans.forEach((family, plan) -> System.out.printf(
            "PHASE15_UNSUBSCRIBE_EXPLAIN family=%s node=%s actual_ms=%s "
                + "shared_hit_blocks=%s%n",
            family, plan.path("Node Type").asText(),
            plan.path("Actual Total Time").asText(),
            plan.path("Shared Hit Blocks").asText()));
    }

    private static long seedFixture(JdbcTemplate jdbc) {
        long accountId = jdbc.queryForObject("""
            INSERT INTO user_accounts(
                email,provider,provider_account_id,owner_key,encrypted_access_token,
                encrypted_refresh_token,scopes
            ) VALUES (
                'unsubscribe-performance@example.com','google',?,?,
                decode('01','hex'),decode('02','hex'),ARRAY['email']
            )
            RETURNING id
            """, Long.class, "provider-" + UUID.randomUUID(),
            "unsubscribe-performance@example.com:google");
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id,provider,provider_message_id,provider_thread_id,
                received_at,from_display_name,from_address,from_domain,
                list_id_values,list_unsubscribe_values,list_unsubscribe_post_values,
                unsubscribe_dkim_pass,unsubscribe_dkim_covers_list_unsubscribe,
                unsubscribe_dkim_covers_list_unsubscribe_post,unsubscribe_auth_reason,
                unread,subject_header
            )
            SELECT ?,'google',
                   'unsubscribe-' || sender || '-' || message,
                   'thread-' || sender || '-' || message,
                   CURRENT_TIMESTAMP
                     - ((message % 35) || ' days')::interval
                     - ((sender % 600) || ' seconds')::interval,
                   'Bulk Sender ' || sender,
                   'sender' || lpad(sender::text,3,'0') || '@bulk.example',
                   'bulk.example',
                   ARRAY['list-' || sender || '.bulk.example'],
                   ARRAY['https://unsubscribe.example/u/' || sender],
                   ARRAY['List-Unsubscribe=One-Click'],
                   true,true,true,'TRUSTED_PASS',
                   message % 3 = 0,'Performance subject'
              FROM generate_series(0,?) sender
              CROSS JOIN generate_series(1,?) message
            """, accountId, SENDERS - 1, MESSAGES_PER_SENDER);
        seedOutcomes(jdbc, accountId);
        seedCleanupHistory(jdbc, accountId);
        return accountId;
    }

    private static void seedOutcomes(JdbcTemplate jdbc, long accountId) {
        Instant now = Instant.now();
        jdbc.batchUpdate("""
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,
                method,latest_status,created_at,completed_at
            ) VALUES (?,?,?,'RFC8058_POST',?,?,?)
            """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index)
                        throws java.sql.SQLException {
                    String sender = "sender%03d@bulk.example".formatted(index);
                    String list = "list-%d.bulk.example".formatted(index);
                    statement.setLong(1, accountId);
                    statement.setString(2,
                        UnsubscribeCandidateService.candidateFingerprint(
                            accountId, sender, list));
                    statement.setString(3, hex("sender-" + index));
                    statement.setString(4, index % 5 == 0 ? "CONFIRMED" : "ACCEPTED");
                    statement.setTimestamp(5, Timestamp.from(now.minusSeconds(index)));
                    statement.setTimestamp(6, Timestamp.from(now.minusSeconds(index - 1L)));
                }

                @Override
                public int getBatchSize() {
                    return SENDERS;
                }
            });
    }

    private static void seedCleanupHistory(JdbcTemplate jdbc, long accountId) {
        jdbc.update("""
            INSERT INTO action_scopes(
                account_id,action_type,target_kind,message_ids,scope_hash,
                message_count,target_summary,risk_summary,created_at
            )
            SELECT ?,'ARCHIVE','UNSUBSCRIBE_PERFORMANCE',
                   ARRAY['unsubscribe-' || sender || '-1'],
                   md5('scope-' || sender) || md5('scope-' || sender),
                   1,'{}','{}',CURRENT_TIMESTAMP - interval '1 day'
              FROM generate_series(0,?) sender
            """, accountId, CLEANUP_TARGETS - 1);
        jdbc.update("""
            INSERT INTO action_scope_targets(
                scope_id,account_id,scope_ordinal,target_fingerprint
            )
            SELECT id,account_id,0,md5(message_ids[1]) || md5(message_ids[1])
              FROM action_scopes WHERE account_id=?
            """, accountId);
        jdbc.update("""
            INSERT INTO user_actions(
                account_id,action_scope_id,action_type,message_ids,
                inverse_action_data,target_kind,target_id_json,affected_count,
                status,execution_mode,decision_source,created_at,completed_at
            )
            SELECT account_id,id,action_type,message_ids,'{}',target_kind,'{}',1,
                   'APPLIED','STAGED','SENDERS',created_at,created_at + interval '1 second'
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
                   1,1,1,0,0,0,created_at,completed_at
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
    }

    private static UnsubscribeQuery query(Map<String, List<String>> additions) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LinkedHashMap<String, List<String>> values = new LinkedHashMap<>(additions);
        values.putIfAbsent("from", List.of(today.minusDays(90).toString()));
        values.putIfAbsent("to", List.of(today.toString()));
        values.putIfAbsent("category", List.of("all"));
        values.putIfAbsent("protected", List.of("include"));
        return UnsubscribeQuery.parse(values);
    }

    private static TimedPage timed(
            java.util.function.Supplier<DecisionPage<UnsubscribeCandidateRow>> work) {
        long started = System.nanoTime();
        DecisionPage<UnsubscribeCandidateRow> page = work.get();
        return new TimedPage(page, Duration.ofNanos(System.nanoTime() - started));
    }

    private static List<QueryCall> productionCalls(NamedParameterJdbcTemplate named) {
        LinkedHashMap<String, QueryCall> calls = new LinkedHashMap<>();
        for (Invocation invocation : mockingDetails(named).getInvocations()) {
            Object[] arguments = invocation.getArguments();
            if (arguments.length < 2 || !(arguments[0] instanceof String sql)
                    || sql.startsWith("EXPLAIN ")) {
                continue;
            }
            SqlParameterSource parameters = parameters(arguments[1]);
            if (parameters != null) {
                QueryCall call = new QueryCall(sql, parameters);
                calls.putIfAbsent(sql + '\n' + parameters, call);
            }
        }
        return List.copyOf(calls.values());
    }

    private static SqlParameterSource parameters(Object raw) {
        if (raw instanceof SqlParameterSource source) return source;
        if (raw instanceof Map<?, ?> values) {
            MapSqlParameterSource source = new MapSqlParameterSource();
            values.forEach((key, value) -> source.addValue(String.valueOf(key), value));
            return source;
        }
        return null;
    }

    private static Map<String, Long> familyCounts(List<QueryCall> calls) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        calls.forEach(call -> counts.merge(family(call.sql()), 1L, Long::sum));
        return Map.copyOf(counts);
    }

    private static Map<String, JsonNode> explainFamilies(
            NamedParameterJdbcTemplate named, List<QueryCall> calls) throws Exception {
        LinkedHashMap<String, JsonNode> plans = new LinkedHashMap<>();
        for (QueryCall call : calls) {
            String family = family(call.sql());
            if ("other".equals(family) || plans.containsKey(family)) continue;
            String json = named.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + call.sql(),
                call.parameters(), String.class);
            plans.put(family, JSON.readTree(json).get(0).get("Plan"));
        }
        return Map.copyOf(plans);
    }

    private static String family(String sql) {
        if (sql.contains("WITH normalized_metadata AS")) return "source";
        if (sql.contains("WITH sender_summaries AS")
                && sql.contains("COUNT(*) AS sender_count")) return "count";
        if (sql.contains("WITH sender_summaries AS")) return "page";
        return "other";
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

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
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

    private record QueryCall(String sql, SqlParameterSource parameters) {
    }

    private record TimedPage(
        DecisionPage<UnsubscribeCandidateRow> page,
        Duration elapsed
    ) {
    }
}
