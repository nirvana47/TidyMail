package com.tidymail;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import com.sun.net.httpserver.HttpServer;
import com.tidymail.support.PostgresJourneyTest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;

/**
 * Explicit-only browser harness: authenticated MockMvc bridge over loopback,
 * synthetic DB/provider.
 */
@org.springframework.test.context.TestPropertySource(properties = "app.mvp.enabled=true")
class DashboardBrowserFixture extends PostgresJourneyTest {
    @org.springframework.boot.test.mock.mockito.MockBean
    com.tidymail.service.JevClassifier classifier;
    @org.springframework.boot.test.mock.mockito.MockBean
    com.tidymail.mail.gmail.GmailEvidenceReader evidenceReader;
    @org.springframework.beans.factory.annotation.Autowired
    com.tidymail.job.DashboardCleanupWorker cleanupWorker;
    @org.springframework.beans.factory.annotation.Autowired
    com.tidymail.service.DashboardClassificationService classification;

    private static java.util.Set<String> withUnread(java.util.Set<String> labels, boolean unread) {
        var result = new java.util.HashSet<>(labels);
        if (unread)
            result.add("UNREAD");
        return result;
    }

    @Test
    void serveSyntheticDashboardForBrowserAcceptance() throws Exception {
        long account = userAccounts.createAccount("browser-fixture@example.test");
        classification.automatic(account,false);
        jdbc.update(
                """
                        INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,
                          subject_header,received_at,unread,labels,label_evidence_known,dashboard_from_address,
                          dashboard_unit_kind,dashboard_unit_key,dashboard_identity_reason)
                        SELECT ?,'google','browser-'||i,'news'||(i%12)||'@sender.example','Newsletter '||(i%12),
                          'Indexed subject '||i,TIMESTAMPTZ '2026-10-28 12:00:00+00'-(i%30)*interval '1 day',
                          (i/12)%2=0,CASE WHEN (i/12)%5=0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END,true,
                          'news'||(i%12)||'@sender.example','ADDRESS','news'||(i%12)||'@sender.example','Kept separate — insufficient domain evidence'
                        FROM generate_series(1,360) i
                        """,
                account);
        org.mockito.Mockito.when(classifier.configured()).thenReturn(true);
        org.mockito.Mockito.when(classifier.classify(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            java.util.List<com.tidymail.service.JevClassifier.Evidence> items = call.getArgument(0);
            return new com.tidymail.service.JevClassifier.Result(
                    items.stream().map(e -> new com.tidymail.service.JevClassifier.Decision(
                            e.subject().contains("offer") ? "MARKETING"
                                    : e.address().contains("amazon") ? "RECEIPT" : "PERSONAL",
                            e.address().contains("amazon") ? "AUTOMATED" : "PERSON",
                            e.address().contains("amazon") ? "SHOPPING" : "GENERAL", .98)).toList(),
                    new java.math.BigDecimal("0.0001"));
        });
        for (var sample : java.util.List.of(
                new String[] { "shivani.tewari@gene.com", "Shivani Tewari", "Dinner tonight?" },
                new String[] { "shivani.tewari2016@gmail.com", "Shivani Tewari", "Family plans" },
                new String[] { "orders@amazon.com", "Amazon", "Your receipt" },
                new String[] { "orders@amazon.com", "Amazon", "Special offer" })) {
            String id = "classification-" + java.util.UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,labels,unread,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at) VALUES(?,'google',?,?,?,?,ARRAY['INBOX'],true,true,?,'ADDRESS',?,now())",
                    account, id, sample[0], sample[1], sample[2], sample[0], sample[0]);
        }
        jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,
                  subject_header,labels,unread,label_evidence_known,dashboard_from_address,dashboard_unit_kind,
                  dashboard_unit_key,received_at)
                SELECT ?,'google','list-label-'||i,'offers@lender.example','LendingTree',
                  'Synthetic offer '||i,ARRAY['INBOX'],true,true,'offers@lender.example','LIST',
                  'spc.142003.1.sparkpostmail.com',now() FROM generate_series(1,3) i
                """, account);
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,
            labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,classification_override)
            SELECT ?,'google','pattern-'||i,'orders@amazon.com','Amazon','Order '||(123455+i)||' confirmed',
            ARRAY['INBOX'],true,'orders@amazon.com','ADDRESS','orders@amazon.com',now(),CASE WHEN i=1 THEN 'RECEIPT' END
            FROM generate_series(1,3) i
            """,account);
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,
            labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,in_reply_to_values)
            VALUES(?,'google','pattern-reply','orders@amazon.com','Amazon','Order 123459 confirmed',ARRAY['INBOX'],true,
            'orders@amazon.com','ADDRESS','orders@amazon.com',now(),ARRAY['<synthetic-reply@example.test>'])
            """,account);
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,
            labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,classification_override)
            VALUES(?,'google','personal-work','spouse@gene.com','Spouse','Synthetic work-address invitation',ARRAY['INBOX'],true,
            'spouse@gene.com','ADDRESS','spouse@gene.com',now(),'PERSONAL')
            """,account);
        jdbc.update("UPDATE message_metadata SET size_estimate=2000+(id%50)*400 WHERE account_id=?",account);
        jdbc.update("UPDATE message_metadata SET attachment_state='ABSENT',attachment_source='SYNTHETIC_FIXTURE',attachment_observed_at=now(),has_attachments=false WHERE account_id=? AND provider_message_id LIKE 'pattern-%'",account);
        for (String id : java.util.List.of("calendar-master","calendar-update")) {
            jdbc.update("""
                INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,subject_header,
                labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,
                size_estimate,classification_override,snippet_text,snippet_state)
                VALUES(?,'google',?,'spouse@gmail.com','Spouse',?,ARRAY['INBOX'],true,'spouse@gmail.com','ADDRESS','spouse@gmail.com',now(),8000,'PERSONAL','Synthetic all-day invitation','AVAILABLE')
                """,account,id,id.equals("calendar-master") ? "Calendar sample: monthly family plan" : "Calendar sample: updated family plan");
        }
        jdbc.update("UPDATE message_metadata SET size_estimate=6000000,snippet_text='A bounded preview for checking the email inspector.',snippet_state='AVAILABLE' WHERE account_id=? AND provider_message_id IN ('browser-1','browser-2','browser-3')",account);
        org.mockito.Mockito.when(evidenceReader.read(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            String id=call.getArgument(1);
            String properties=id.equals("calendar-master")
                ? "UID:all-day-fixture\r\nDTSTART;VALUE=DATE:20260930\r\nRRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=2\r\nSEQUENCE:1"
                : id.equals("calendar-update")
                ? "UID:all-day-fixture\r\nRECURRENCE-ID;VALUE=DATE:20261030\r\nDTSTART;VALUE=DATE:20261107\r\nDURATION:P2D\r\nSEQUENCE:2"
                : "UID:fixture\r\nDTSTART:20260101T090000Z\r\nDTEND:20260101T100000Z";
            var facts=new com.tidymail.service.CalendarEvidenceParser().parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nORGANIZER:mailto:person@example.test\r\n"+properties+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").source("1","synthetic-"+id);
            return new com.tidymail.mail.gmail.GmailEvidenceReader.Evidence(com.tidymail.mail.gmail.GmailEvidenceReader.VERSION,
                com.tidymail.mail.EmailSnippet.observed("Synthetic calendar preview"),"AVAILABLE","PRESENT",
                java.util.List.of(new com.tidymail.mail.gmail.GmailEvidenceReader.Part("1","text/calendar","invite.ics",450L,false,true)),
                java.util.List.of(facts),2,1000);
        });
        var classificationExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        classificationExecutor.scheduleWithFixedDelay(classification::runOnce, 1, 1,
                java.util.concurrent.TimeUnit.SECONDS);
        jdbc.update(
                "INSERT INTO mailbox_sync_state(account_id,provider,lifecycle,backfill_status,initial_batch_completed_at,incremental_last_success_at) VALUES(?,'google','ACTIVE','COMPLETE',now(),now())",
                account);
        var providerUnread = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        providerUnread.addAll(
                jdbc.queryForList("SELECT provider_message_id FROM message_metadata WHERE account_id=? AND unread",
                        String.class, account));
        var providerLabels = new java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>>();
        jdbc.queryForList("SELECT provider_message_id,labels FROM message_metadata WHERE account_id=?", account)
                .forEach(row -> {
                    try {
                        providerLabels.put(row.get("provider_message_id").toString(), new java.util.HashSet<>(
                                java.util.Arrays.asList((String[]) ((java.sql.Array) row.get("labels")).getArray())));
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                });
        org.mockito.Mockito.when(gmailProvider.getCleanupMetadata(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
                    java.util.List<String> ids = call.getArgument(1);
                    return ids.stream()
                            .map(id -> new com.tidymail.mail.MailMessage(id, "synthetic-thread", "google",
                                    java.time.Instant.now(), java.util.Map.of(),
                                    withUnread(providerLabels.get(id), providerUnread.contains(id))))
                            .toList();
                });
        org.mockito.Mockito.doAnswer(call -> {
            Thread.sleep(1500);
            providerUnread.removeAll(call.getArgument(1));
            return null;
        })
                .when(gmailProvider)
                .markReadOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.doAnswer(call -> {
            Thread.sleep(1500);
            java.util.List<String> ids = call.getArgument(1);
            com.tidymail.mail.BulkActionType action = call.getArgument(2);
            for (String id : ids)
                providerLabels.compute(id, (key, old) -> {
                    var next = new java.util.HashSet<>(old);
                    if (action == com.tidymail.mail.BulkActionType.ARCHIVE)
                        next.remove("INBOX");
                    else
                        next.add("TRASH");
                    return next;
                });
            return null;
        }).when(gmailProvider).cleanupOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        var cleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        cleanup.scheduleWithFixedDelay(cleanupWorker::tick, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        var session = new MockHttpSession();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                var req = request(HttpMethod.valueOf(exchange.getRequestMethod()),
                        URI.create(exchange.getRequestURI().toString()))
                        .with(authenticatedAs("browser-fixture@example.test")).session(session);
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length"))
                        req.header(name, values.toArray());
                });
                if (!exchange.getRequestMethod().equals("GET"))
                    req.content(exchange.getRequestBody().readAllBytes());
                var response = mockMvc.perform(req).andReturn().getResponse();
                for (String header : response.getHeaderNames())
                    for (String value : response.getHeaders(header))
                        exchange.getResponseHeaders().add(header, value);
                byte[] body = response.getContentAsByteArray();
                if (exchange.getRequestURI().getPath().equals("/dashboard")) {
                    String html=new String(body,java.nio.charset.StandardCharsets.UTF_8);
                    body=html.replace("<h1>TidyMail</h1>","<h1>TidyMail</h1><p><strong>Synthetic test mailbox — no real emails.</strong></p>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(response.getStatus(), body.length == 0 ? -1 : body.length);
                if (body.length > 0)
                    exchange.getResponseBody().write(body);
                exchange.close();
            } catch (Exception error) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                throw new RuntimeException(error);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        Path done = Path.of("/tmp/tidymail-phase3-browser-done");
        Files.deleteIfExists(done);
        System.out.printf("DASHBOARD_BROWSER_FIXTURE http://127.0.0.1:%d/dashboard%n", server.getAddress().getPort());
        try {
            long deadline = System.nanoTime()
                    + java.time.Duration.ofMinutes(Long.getLong("dashboard.browser.minutes", 30)).toNanos();
            while (!Files.exists(done) && System.nanoTime() < deadline)
                Thread.sleep(500);
        } finally {
            classificationExecutor.shutdownNow();
            cleanup.shutdownNow();
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }
}
