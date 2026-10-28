package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.dto.SenderQuery;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Phase 2 measurements of current queries, not acceptance of the future dashboard. */
@Testcontainers(disabledWithoutDocker = false)
class DashboardBaselinePerformanceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void measureIndexedBrowsingWithAndWithoutConcurrentMetadataWrites() throws Exception {
        var source = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var repository = new DecisionSurfaceQueryRepository(new NamedParameterJdbcTemplate(source));
        long account = jdbc.queryForObject("""
            INSERT INTO user_accounts(email,provider,provider_account_id,owner_key,
              encrypted_access_token,encrypted_refresh_token,scopes)
            VALUES ('baseline@example.test','google','baseline','baseline:google',
              decode('01','hex'),decode('02','hex'),ARRAY['email']) RETURNING id
            """, Long.class);
        Instant now = Instant.parse("2026-10-28T12:00:00Z");
        for (int size : new int[] {10_000, 50_000}) {
            jdbc.update("DELETE FROM message_metadata WHERE account_id=?", account);
            jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,
                  from_address,from_display_name,from_domain,subject_header,received_at,unread,labels)
                SELECT ?, 'google', 'synthetic-' || i,
                  'sender' || (i % 500) || '@example.test','Sender ' || (i % 500),
                  'example.test','Synthetic subject ' || i,
                  TIMESTAMPTZ '2026-10-28 12:00:00+00' - (i % 100) * interval '1 hour',
                  i % 2 = 0, CASE WHEN i % 4 = 0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END
                FROM generate_series(1,?) i
                """, account, size);
            jdbc.execute("ANALYZE message_metadata");
            var query = SenderQuery.parse(Map.of("window", List.of("all"),
                "queue", List.of("all"), "protected", List.of("include")));
            assertThat(repository.senders(List.of(account), query, now).count()).isEqualTo(500);
            for (boolean writing : new boolean[] {false, true}) {
                var stop = new AtomicBoolean();
                var writes = new AtomicInteger();
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var worker = executor.submit(() -> {
                        while (writing && !stop.get()) {
                            // Separate committed transactions approximate sync write contention.
                            jdbc.update("""
                                UPDATE message_metadata SET last_synced_at=clock_timestamp()
                                WHERE account_id=? AND provider_message_id IN
                                  (SELECT provider_message_id FROM message_metadata
                                   WHERE account_id=? ORDER BY id LIMIT 200)
                                """, account, account);
                            writes.incrementAndGet();
                        }
                    });
                    try {
                        measure(size, writing, "summary_and_first_page", () ->
                            assertThat(repository.senders(List.of(account), query, now).rows()).hasSize(25));
                        var filtered = SenderQuery.parse(Map.of("window", List.of("7d"),
                            "queue", List.of("all"), "protected", List.of("include"),
                            "search", List.of("sender1")));
                        measure(size, writing, "search_date_page", () ->
                            assertThat(repository.senders(List.of(account), filtered, now).count()).isEqualTo(111));
                        measure(size, writing, "sender_page_10", () ->
                            assertThat(repository.senders(List.of(account), query.withPage(9), now).rows()).hasSize(25));
                        // SQL prototype: legacy app has no general sender-message expansion endpoint.
                        measure(size, writing, "expansion_sql_prototype", () ->
                            assertThat(jdbc.queryForList("""
                                SELECT provider_message_id,subject_header,received_at,unread
                                FROM message_metadata WHERE account_id=? AND from_address=?
                                ORDER BY received_at DESC NULLS LAST,provider_message_id LIMIT 10
                                """, account, "sender1@example.test")).hasSize(10));
                    } finally {
                        stop.set(true);
                        worker.get(30, TimeUnit.SECONDS);
                    }
                    if (writing) assertThat(writes.get()).isPositive();
                    System.out.printf("DASHBOARD_BASELINE rows=%d concurrent=%s committed_write_batches=%d%n",
                        size, writing, writes.get());
                }
            }
        }
    }

    private static void measure(int rows, boolean writing, String operation, Runnable work) {
        work.run(); // One warmup; all ten subsequent observations are reported.
        double[] milliseconds = new double[10];
        for (int i = 0; i < milliseconds.length; i++) {
            long start = System.nanoTime();
            work.run();
            milliseconds[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(milliseconds);
        System.out.printf(java.util.Locale.ROOT,
            "DASHBOARD_BASELINE rows=%d senders=500 concurrent=%s operation=%s samples=10 p50_ms=%.2f p95_ms=%.2f max_ms=%.2f%n",
            rows, writing, operation, (milliseconds[4] + milliseconds[5]) / 2,
            milliseconds[9], milliseconds[9]);
    }
}
