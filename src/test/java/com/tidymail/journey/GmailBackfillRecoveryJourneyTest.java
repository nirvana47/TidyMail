package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tidymail.config.MailboxSyncExecutorConfig;
import com.tidymail.job.IncrementalSyncJob;
import com.tidymail.mail.gmail.GmailClientFactory;
import com.tidymail.mail.gmail.GmailClientInitException;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.mail.gmail.GmailRateLimiter;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.BackfillRequest;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.MessageMetadataSyncService;
import com.tidymail.support.PostgresJourneyTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** Real Gmail SDK HTTP batches, sync workers, production migrations, and PostgreSQL. */
class GmailBackfillRecoveryJourneyTest extends PostgresJourneyTest {
    @Autowired MailboxSyncStateRepository states;
    @Autowired SyncProgressRepository progress;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataSyncService synchronization;

    @Test
    void scheduledSyncRecoversAfter200AndRetriesOnlyThrottledMetadata() throws Exception {
        long accountId = userAccounts.createAccount("backfill-recovery@example.test");
        try (GmailServer server = new GmailServer()) {
            RecordingLimiter limiter = limiter();
            GmailMailService provider = provider(server, limiter);
            ArrayDeque<Runnable> freshness = new ArrayDeque<>();
            ArrayDeque<Runnable> backfill = new ArrayDeque<>();
            MailboxSyncExecutorConfig config = mock(MailboxSyncExecutorConfig.class);
            when(config.laneLeaseDuration()).thenReturn(Duration.ofSeconds(60));
            IncrementalSyncJob job = new IncrementalSyncJob(
                Map.of("gmailMailService", provider), accounts, progress, states,
                synchronization, freshness::add, backfill::add, config);
            // Exercise heartbeat during provider work near the lease boundary.
            server.duringBatch = () -> {
                jdbc.update("UPDATE mailbox_sync_state SET backfill_lease_expires_at = ? WHERE account_id = ?",
                    java.sql.Timestamp.from(Instant.now().plusMillis(500)), accountId);
                job.renewActiveLeases();
                assertThat(states.findByAccountId(accountId).orElseThrow().backfillLeaseExpiresAt())
                    .isAfter(Instant.now().plusSeconds(30));
            };
            // Simulate a queue row retained across a process restart with no executor task.
            states.ensureState(accountId, "google");
            states.queueLane(accountId, com.tidymail.repository.MailboxSyncStateRepository.Lane.BACKFILL);
            job.requestSync(accountId);
            assertThat(backfill).hasSize(1);
            backfill.remove().run();
            assertThat(indexed(accountId)).isEqualTo(200);
            server.duringBatch = () -> {};

            server.failListing = true;
            backfill.remove().run();
            assertThat(indexed(accountId)).isEqualTo(200);
            assertThat(states.findByAccountId(accountId).orElseThrow().backfillPageToken()).isNull();
            assertThat(progress.findTopByAccountIdAndJobTypeAndLaneAndStatusOrderByIdDesc(
                accountId, IncrementalSyncJob.JOB_TYPE, "BACKFILL", "FAILED")).isPresent();
            assertThat(limiter.retries).containsExactly(0, 1, 2, 3, 4);

            server.failListing = false;
            limiter.retries.clear();
            job.run(); // The scheduled path must resume older mail alongside freshness.
            while (!backfill.isEmpty()) backfill.remove().run();
            while (!freshness.isEmpty()) freshness.remove().run();

            assertThat(indexed(accountId)).isEqualTo(214);
            assertThat(states.findByAccountId(accountId).orElseThrow().backfillStatus()).isEqualTo("COMPLETE");
            assertThat(server.metadataAttempts.get("id201")).isEqualTo(3);
            assertThat(server.metadataAttempts.get("id200")).isEqualTo(1);
            assertThat(server.metadataAttempts.get("id202")).isEqualTo(1);
            assertThat(limiter.retries).containsExactly(0, 1);
            assertThat(limiter.retryAfterValues).contains("2");
            assertThat(server.metadataBatchTimes).hasSize(12); // 8 bootstrap, 1 page plus 2 failed-ID retries, 1 final page.
            for (int i = 1; i < server.metadataBatchTimes.size(); i++) {
                assertThat(server.metadataBatchTimes.get(i) - server.metadataBatchTimes.get(i - 1))
                    .isGreaterThanOrEqualTo(Duration.ofMillis(8).toNanos());
            }
        }
    }

    @Test
    void permanentForbiddenMetadataFailsWithoutRetryingOrAdvancingState() throws Exception {
        long accountId = userAccounts.createAccount("backfill-denied@example.test");
        try (GmailServer server = new GmailServer()) {
            server.denyMetadata = true;
            RecordingLimiter limiter = limiter();
            assertThatThrownBy(() -> provider(server, limiter).listBackfill(
                accounts.findById(accountId).orElseThrow(), new BackfillRequest(null, 500)))
                .isInstanceOf(GmailClientInitException.class).hasMessage("BACKFILL_FAILED");
            assertThat(limiter.retries).isEmpty();
            assertThat(server.metadataBatchTimes).hasSize(1);
            assertThat(indexed(accountId)).isZero();
        }
    }

    private long indexed(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE account_id = ?", Long.class, accountId);
    }

    private static RecordingLimiter limiter() {
        GmailSyncConfigProperties config = config();
        ReflectionTestUtils.setField(config, "metadataBatchDelayMs", 10);
        RecordingLimiter limiter = new RecordingLimiter(config);
        limiter.init();
        return limiter;
    }

    private static GmailSyncConfigProperties config() {
        GmailSyncConfigProperties config = new GmailSyncConfigProperties();
        config.setApiConcurrency(3);
        config.setBatchDelayMs(0);
        config.setQuotaUnitsPerMinute(0); // Synthetic HTTP timing; quota admission is checked separately.
        return config;
    }

    private static GmailMailService provider(GmailServer server, RecordingLimiter limiter) {
        Gmail gmail = new Gmail.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance(),
            request -> request.setNumberOfRetries(0))
            .setRootUrl("http://127.0.0.1:" + server.server.getAddress().getPort() + "/")
            .setServicePath("gmail/v1/").setBatchPath("batch/gmail/v1")
            .setApplicationName("TidyMail test").build();
        GmailClientFactory factory = mock(GmailClientFactory.class);
        when(factory.buildFor(any())).thenReturn(gmail);
        return new GmailMailService(factory, limiter, config());
    }

    private static class RecordingLimiter extends GmailRateLimiter {
        final List<Integer> retries = new ArrayList<>();
        final List<String> retryAfterValues = new ArrayList<>();
        RecordingLimiter(GmailSyncConfigProperties config) { super(config); }
        @Override public void pauseBeforeRetry(int retry, HttpHeaders headers) {
            retries.add(retry);
            if (headers != null) retryAfterValues.add(headers.getRetryAfter());
        }
    }

    private static class GmailServer implements AutoCloseable {
        final HttpServer server;
        final Map<String, Integer> metadataAttempts = new HashMap<>();
        final List<Long> metadataBatchTimes = new ArrayList<>();
        boolean failListing;
        boolean denyMetadata;
        Runnable duringBatch = () -> {};

        GmailServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            if (path.endsWith("/profile")) {
                send(exchange, 200, "application/json", "{\"historyId\":\"1000\",\"messagesTotal\":214}");
            } else if (path.endsWith("/history")) {
                send(exchange, 200, "application/json", "{\"historyId\":\"1001\"}");
            } else if (path.endsWith("/messages")) {
                if (failListing) {
                    send(exchange, 403, "application/json", error(403, "rateLimitExceeded"));
                    return;
                }
                boolean bootstrap = query.contains("maxResults=200");
                int start = bootstrap ? 0 : query.contains("pageToken=page-two") ? 212 : 200;
                int end = bootstrap ? 200 : start == 200 ? 212 : 214;
                String ids = IntStream.range(start, end).mapToObj(i -> "{\"id\":\"id" + i + "\"}")
                    .collect(Collectors.joining(","));
                send(exchange, 200, "application/json", "{\"messages\":[" + ids + "]"
                    + (start == 200 ? ",\"nextPageToken\":\"page-two\"" : "") + "}");
            } else {
                metadataBatchTimes.add(System.nanoTime());
                duringBatch.run();
                var input = exchange.getRequestBody();
                if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                    input = new GZIPInputStream(input);
                }
                String request = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(request).contains("format=METADATA").doesNotContain("format=FULL", "format=RAW");
                var matcher = Pattern.compile("/messages/(id[0-9]+)\\?").matcher(request);
                StringBuilder response = new StringBuilder();
                int index = 0;
                while (matcher.find()) {
                    String id = matcher.group(1);
                    int attempt = metadataAttempts.merge(id, 1, Integer::sum);
                    int status = denyMetadata ? 403 : id.equals("id201") && attempt <= 2 ? (attempt == 1 ? 403 : 429) : 200;
                    // Historical From display names may contain angle brackets.
                    String from = id.equals("id201") ? "\\\"Alerts > Updates\\\" <sender@example.test>" : "sender@example.test";
                    String body = status == 200
                        ? "{\"id\":\"" + id + "\",\"threadId\":\"t" + id + "\",\"historyId\":\"1000\",\"internalDate\":\"1700000000000\",\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"" + from + "\"}]}}"
                        : error(status, denyMetadata ? "insufficientPermissions" : "rateLimitExceeded");
                    response.append("--test_boundary\r\nContent-Type: application/http\r\nContent-ID: response-")
                        .append(++index).append("\r\n\r\nHTTP/1.1 ").append(status).append(" status\r\n")
                        .append("Content-Type: application/json; charset=UTF-8\r\nRetry-After: 2\r\n\r\n")
                        .append(body).append("\r\n");
                }
                response.append("--test_boundary--\r\n");
                send(exchange, 200, "multipart/mixed; boundary=test_boundary", response.toString());
            }
        }

        private static String error(int status, String reason) {
            return "{\"error\":{\"code\":" + status + ",\"message\":\"synthetic provider error\",\"errors\":[{\"reason\":\"" + reason + "\",\"domain\":\"usageLimits\"}]}}";
        }

        private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
