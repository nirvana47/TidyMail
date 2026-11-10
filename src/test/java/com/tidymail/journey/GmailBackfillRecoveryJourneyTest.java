package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tidymail.config.MailboxSyncExecutorConfig;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.service.DashboardService;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
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
import java.util.UUID;
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
    @Autowired DashboardService dashboard;

    @Test
    void scheduledSyncRecoversAfter200AndRetriesOnlyThrottledMetadata() throws Exception {
        String email = "backfill-recovery@example.test";
        long accountId = userAccounts.createAccount(email);
        jdbc.update("""
            INSERT INTO message_metadata(account_id, provider, provider_message_id, labels, label_evidence_known)
            VALUES (?, 'google', 'id199', ARRAY['INBOX'], true)
            """, accountId);
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
            assertThat(activity(email)).contains("Indexing Inbox", "Counting messages… 200 indexed", "<progress")
                .doesNotContain("63,465", "100%");
            assertThat(server.listings).containsExactly("INBOX:first");
            server.duringBatch = () -> {};

            server.failListing = true;
            backfill.remove().run();
            assertThat(indexed(accountId)).isEqualTo(200);
            assertThat(states.findByAccountId(accountId).orElseThrow().backfillPageToken()).isEqualTo("inbox-two");
            assertThat(progress.findTopByAccountIdAndJobTypeAndLaneAndStatusOrderByIdDesc(
                accountId, IncrementalSyncJob.JOB_TYPE, "BACKFILL", "FAILED")).isPresent();
            assertThat(limiter.retries).containsExactly(0, 1, 2, 3, 4);

            server.failListing = false;
            server.rejectInboxCursor = true;
            limiter.retries.clear();
            job.run(); // The scheduled path must resume older mail alongside freshness.
            while (indexed(accountId) < 205 && !backfill.isEmpty()) backfill.remove().run();
            assertThat(indexed(accountId)).isEqualTo(205);
            assertThat(server.listings).containsExactly("INBOX:first", "INBOX:first", "INBOX:inbox-two");
            assertThat(activity(email)).contains("Indexing All Mail", "Counting messages… 205 indexed");
            while (indexed(accountId) < 210 && !backfill.isEmpty()) backfill.remove().run();
            assertThat(indexed(accountId)).isEqualTo(210);
            assertThat(activity(email)).contains("210 of ~220 indexed (estimated total)").doesNotContain("100%");
            while (!backfill.isEmpty()) backfill.remove().run();
            while (!freshness.isEmpty()) freshness.remove().run();

            assertThat(indexed(accountId)).isEqualTo(214);
            assertThat(activity(email)).contains("214 of 214 indexed · 100%").doesNotContain("estimated total");
            assertThat(server.listings).containsExactly("INBOX:first", "INBOX:first", "INBOX:inbox-two", "ALL:first", "ALL:all-two");
            assertThat(server.metadataAttempts).hasSize(213).doesNotContainKeys("id199", "id214", "id215");
            assertThat(states.findByAccountId(accountId).orElseThrow().backfillStatus()).isEqualTo("COMPLETE");
            assertThat(server.metadataAttempts.get("id201")).isEqualTo(3);
            assertThat(server.metadataAttempts.get("id200")).isEqualTo(1);
            assertThat(server.metadataAttempts.get("id202")).isEqualTo(1);
            assertThat(limiter.retries).containsExactly(0, 1);
            assertThat(limiter.retryAfterValues).contains("2");
            assertThat(server.metadataBatchTimes).hasSize(13); // 8 bootstrap, 3 older Inbox (including retries), 2 archive pages.
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

    @Test
    void historyAndInterruptedRescanHideMovesRestoreRowsAndDeleteOnlyConfirmedMissing() throws Exception {
        String email = "moves-and-restores@example.test";
        long accountId = userAccounts.createAccount(email);
        try (GmailServer server = new GmailServer()) {
            var provider = provider(server, limiter());
            server.smallMailbox = true;
            states.ensureState(accountId, "google");
            runLane(accountId, provider, Lane.BACKFILL);
            runLane(accountId, provider, Lane.BACKFILL);
            assertThat(indexed(accountId)).isEqualTo(3);
            UUID original = dashboardId(accountId, "id0");
            UUID trashed = dashboardId(accountId, "id1");
            assertThat(browsable(accountId)).isEqualTo(3L);

            server.revision = 1001;
            server.labels.put("id0", "SPAM");
            server.history = """
                "history":[{"id":"1001","labelsAdded":[{"message":{"id":"id0"},"labelIds":["SPAM"]}],
                "messagesAdded":[{"message":{"id":"id214"}}]}],
                """;
            runLane(accountId, provider, Lane.FRESHNESS);
            assertThat(browsable(accountId)).isEqualTo(2L);
            assertThat(dashboardId(accountId, "id0")).isEqualTo(original);
            assertThat(indexed(accountId)).isEqualTo(3); // Newly seen Spam was not imported.

            server.revision = 1002;
            server.labels.put("id0", "INBOX");
            server.history = """
                "history":[{"id":"1002","labelsRemoved":[{"message":{"id":"id0"},"labelIds":["SPAM"]}]}],
                """;
            runLane(accountId, provider, Lane.FRESHNESS);
            assertThat(browsable(accountId)).isEqualTo(3L);

            server.revision = 1003;
            server.expiredHistory = true;
            server.labels.put("id0", "SPAM");
            server.labels.put("id1", "TRASH");
            server.missing = List.of("id2");
            assertThat(runLane(accountId, provider, Lane.FRESHNESS).queueRescan()).isTrue();
            assertThat(runLane(accountId, provider, Lane.RESCAN).queueFreshness()).isTrue();
            server.expiredHistory = false;
            server.revision = 1004;
            server.history = "";
            runLane(accountId, provider, Lane.FRESHNESS);
            assertThat(states.findByAccountId(accountId).orElseThrow().incrementalCursorValue()).isEqualTo("1004");
            assertThat(runLane(accountId, provider, Lane.RESCAN).moreWork()).isTrue();
            assertThat(activity(email)).contains("Checking missing messages");
            server.denyMetadata = true;
            assertThatThrownBy(() -> runLane(accountId, provider, Lane.RESCAN))
                .isInstanceOf(GmailClientInitException.class).hasMessage("BACKFILL_FAILED");
            assertThat(indexed(accountId)).isEqualTo(3);
            server.denyMetadata = false;
            assertThat(runLane(accountId, provider, Lane.RESCAN).moreWork()).isTrue();
            assertThat(runLane(accountId, provider, Lane.RESCAN).queueFreshness()).isTrue();
            assertThat(browsable(accountId)).isEqualTo(0L);
            assertThat(indexed(accountId)).isEqualTo(2);
            assertThat(dashboardId(accountId, "id0")).isEqualTo(original);
            assertThat(dashboardId(accountId, "id1")).isEqualTo(trashed);
            assertThat(server.metadataAttempts).containsEntry("id214", 1).doesNotContainKey("id215");

            server.revision = 1005;
            server.labels.put("id1", "INBOX");
            server.history = """
                "history":[{"id":"1005","labelsRemoved":[{"message":{"id":"id1"},"labelIds":["TRASH"]}]}],
                """;
            runLane(accountId, provider, Lane.FRESHNESS);
            assertThat(browsable(accountId)).isEqualTo(1L);
            assertThat(dashboardId(accountId, "id1")).isEqualTo(trashed);
        }
    }

    @Test
    void historyDeletionWinsOverOlderMetadataFromAnInFlightPage() throws Exception {
        String email = "concurrent-deletion@example.test";
        long accountId = userAccounts.createAccount(email);
        try (GmailServer server = new GmailServer()) {
            server.smallMailbox = true;
            var provider = spy(provider(server, limiter()));
            states.ensureState(accountId, "google");
            runLane(accountId, provider, Lane.BACKFILL);
            doAnswer(invocation -> {
                Object staleMetadata = invocation.callRealMethod();
                server.revision = 1001;
                server.missing = List.of("id2");
                server.history = """
                    "history":[{"id":"1001","messagesDeleted":[{"message":{"id":"id2"}}]}],
                    """;
                runLane(accountId, provider, Lane.FRESHNESS);
                return staleMetadata;
            }).when(provider).hydrateSyncPage(any(), anyList(), anyBoolean());
            runLane(accountId, provider, Lane.BACKFILL);
            assertThat(indexed(accountId)).isEqualTo(2);
            assertThat(activity(email)).contains("2 of 2 indexed · 100%");
        }
    }

    private SyncOutcome runLane(
            long accountId, GmailMailService provider, Lane lane) {
        String owner = "journey-" + UUID.randomUUID();
        var state = states.findByAccountId(accountId).orElseThrow();
        Instant now = Instant.now();
        assertThat(states.acquireLaneLease(accountId, lane, owner, now.plusSeconds(60), now, state.version())).isTrue();
        try {
            return synchronization.synchronize(accounts.findById(accountId).orElseThrow(), provider, owner, lane);
        } finally {
            states.releaseLaneLease(accountId, lane, owner, states.findByAccountId(accountId).orElseThrow().version());
        }
    }

    private Object browsable(long accountId) {
        var indexed = dashboard.page(accountId, new DashboardFilter("indexed", "", "", "", "UTC", 0));
        var cleanup = dashboard.page(accountId, new DashboardFilter("all", "", "", "", "UTC", 0));
        assertThat(indexed.summary().get("messages")).isEqualTo(cleanup.summary().get("messages"));
        return cleanup.summary().get("messages");
    }

    private UUID dashboardId(long accountId, String id) {
        return jdbc.queryForObject("SELECT dashboard_message_id FROM message_metadata WHERE account_id=? AND provider_message_id=?",
            UUID.class, accountId, id);
    }

    private String activity(String email) throws Exception {
        return mockMvc.perform(get("/activity/status").with(authenticatedAs(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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
        final List<String> listings = new ArrayList<>();
        final Map<String, String> labels = new HashMap<>(Map.of("id214", "SPAM", "id215", "TRASH"));
        List<String> missing = List.of();
        boolean smallMailbox;
        boolean expiredHistory;
        int revision = 1000;
        String history = "";
        boolean failListing;
        boolean rejectInboxCursor;
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
                send(exchange, 200, "application/json", "{\"historyId\":\"" + revision + "\",\"messagesTotal\":63465}");
            } else if (path.endsWith("/history")) {
                if (expiredHistory) send(exchange, 404, "application/json", error(404, "notFound"));
                else send(exchange, 200, "application/json", "{" + history + "\"historyId\":\"" + revision + "\"}");
            } else if (path.endsWith("/messages")) {
                if (failListing) {
                    send(exchange, 403, "application/json", error(403, "rateLimitExceeded"));
                    return;
                }
                assertThat(query).contains("includeSpamTrash=false");
                boolean inbox = query.contains("labelIds=INBOX");
                boolean olderInbox = query.contains("pageToken=inbox-two");
                if (olderInbox && rejectInboxCursor) {
                    rejectInboxCursor = false;
                    send(exchange, 400, "application/json", error(400, "invalidArgument"));
                    return;
                }
                boolean olderAll = query.contains("pageToken=all-two");
                listings.add((inbox ? "INBOX:" : "ALL:") + (olderInbox ? "inbox-two" : olderAll ? "all-two" : "first"));
                int start = olderInbox ? 200 : olderAll ? 204 : 0;
                int end = smallMailbox ? 3 : inbox ? olderInbox ? 205 : 200 : olderAll ? 216 : 210;
                String ids = IntStream.range(start, end).mapToObj(i -> "id" + i)
                    .filter(id -> !missing.contains(id) && !List.of("SPAM", "TRASH").contains(label(id))
                        && (!inbox || "INBOX".equals(label(id))))
                    .map(id -> "{\"id\":\"" + id + "\"}")
                    .collect(Collectors.joining(","));
                String next = smallMailbox || olderInbox || olderAll ? "" :
                    ",\"nextPageToken\":\"" + (inbox ? "inbox-two" : "all-two") + "\"";
                send(exchange, 200, "application/json", "{\"messages\":[" + ids + "]"
                    + next + ",\"resultSizeEstimate\":" + (inbox ? 205 : 220) + "}");
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
                    int status = denyMetadata ? 403 : missing.contains(id) ? 404 : id.equals("id201") && attempt <= 2 ? (attempt == 1 ? 403 : 429) : 200;
                    // Historical From display names may contain angle brackets.
                    String from = id.equals("id201") ? "\\\"Alerts > Updates\\\" <sender@example.test>" : "sender@example.test";
                    String body = status == 200
                        ? "{\"id\":\"" + id + "\",\"threadId\":\"t" + id + "\",\"historyId\":\"" + revision + "\",\"labelIds\":[" + (label(id).isEmpty() ? "" : "\"" + label(id) + "\"") + "],\"internalDate\":\"1700000000000\",\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"" + from + "\"}]}}"
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

        private String label(String id) {
            return labels.getOrDefault(id, Integer.parseInt(id.substring(2)) < (smallMailbox ? 2 : 205) ? "INBOX" : "");
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
