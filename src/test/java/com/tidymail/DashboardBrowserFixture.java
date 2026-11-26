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
    com.tidymail.mail.gmail.GmailEvidenceReader evidenceReader;
    @org.springframework.boot.test.mock.mockito.SpyBean
    com.tidymail.rules.GmailRuleProvider ruleLabels;
    @org.springframework.beans.factory.annotation.Autowired
    com.tidymail.job.DashboardCleanupWorker cleanupWorker;
    @org.springframework.beans.factory.annotation.Autowired
    com.tidymail.service.LocalLabelService localLabels;

    @org.springframework.beans.factory.annotation.Autowired
    com.tidymail.service.CorrespondentHistory correspondence;

    private static java.util.Set<String> withUnread(java.util.Set<String> labels, boolean unread) {
        var result = new java.util.HashSet<>(labels);
        if (unread)
            result.add("UNREAD");
        return result;
    }

    @Test
    void serveSyntheticDashboardForBrowserAcceptance() throws Exception {
        long account = userAccounts.createAccount("browser-fixture@example.test");
        jdbc.update("UPDATE user_accounts SET scopes=ARRAY['https://www.googleapis.com/auth/gmail.settings.basic'] WHERE id=?",account);
        var nativeRules=new java.util.concurrent.CopyOnWriteArrayList<com.tidymail.rules.RuleProvider.NativeRule>();
        var ruleIds=new java.util.concurrent.atomic.AtomicInteger();
        var overlapJson=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"criteria\":{\"from\":\"overlap@example.test\",\"subject\":\"Invoice\",\"negatedQuery\":\"subject:receipt\"},\"action\":{\"addLabelIds\":[\"TRASH\"]}}");
        nativeRules.add(new com.tidymail.rules.RuleProvider.NativeRule("fixture-overlap","Gmail filter",true,false,overlapJson,null,""));
        org.mockito.Mockito.doReturn(java.util.Set.of()).when(ruleLabels).matchingInbox(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.doAnswer(i->java.util.List.copyOf(nativeRules)).when(ruleLabels).list(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doReturn(0L).when(ruleLabels).destinationCount(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(i->{var raw=((com.fasterxml.jackson.databind.JsonNode)i.getArgument(1)).deepCopy();String id="fixture-rule-"+ruleIds.incrementAndGet();((com.fasterxml.jackson.databind.node.ObjectNode)raw).put("id",id);nativeRules.add(new com.tidymail.rules.RuleProvider.NativeRule(id,"Synthetic rule",true,false,raw,null,""));return id;}).when(ruleLabels).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(i->{nativeRules.removeIf(r->r.id().equals(i.getArgument(1)));return null;}).when(ruleLabels).pause(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(i->{
            var definition=(com.tidymail.rules.RuleDefinition)i.getArgument(1);var included=new java.util.HashSet<String>();var before=new java.util.HashSet<String>();
            for(var row:jdbc.queryForList("SELECT provider_message_id,from_address,subject_header FROM message_metadata WHERE account_id=?",account)){
                String subject=java.util.Objects.toString(row.get("subject_header"),"").toLowerCase(java.util.Locale.ROOT);
                if(!definition.matchesSender((String)row.get("from_address"),"google")||!definition.subjects().isEmpty()&&definition.subjects().stream().noneMatch(v->subject.contains(v.toLowerCase(java.util.Locale.ROOT))))continue;
                String id=(String)row.get("provider_message_id");before.add(id);
                if(definition.exceptions().stream().noneMatch(v->subject.contains(v.toLowerCase(java.util.Locale.ROOT))))included.add(id);
            }
            return new com.tidymail.rules.GmailRuleProvider.Matches(included,before);
        }).when(ruleLabels).matching(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doReturn(java.util.List.of(new com.tidymail.rules.GmailRuleProvider.Destination("Label_42","Offers"),new com.tidymail.rules.GmailRuleProvider.Destination("Label_43","Receipts"))).when(ruleLabels).destinations(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(i->ruleLabels.useDestination(i.getArgument(1),"Label_42")).when(ruleLabels).prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        for(String[] sample:java.util.List.of(new String[]{"domain-a","prefix-a@relay.example.test","Weekly update"},new String[]{"domain-b","prefix-b@relay.example.test","Weekly invoice"},new String[]{"domain-fixed","alerts@fixed.example.test","Weekly update"}))
            jdbc.update("INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,subject_header,labels,label_evidence_known,received_at) VALUES(?,'google',?,?,?,'ADDRESS',?,?,ARRAY['INBOX'],true,now())",account,sample[0],sample[1],sample[1],sample[1],sample[2]);
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,
              subject_header,received_at,unread,labels,label_evidence_known,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key)
            SELECT ?,'google','browser-'||i,'news'||(i%12)||'@sender.example','Sender '||(i%12),
              'Invoice Indexed subject '||i,TIMESTAMPTZ '2026-10-28 12:00:00+00'-(i%30)*interval '1 day',
              (i/12)%2=0,CASE WHEN (i/12)%5=0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END,true,
              'news'||(i%12)||'@sender.example','ADDRESS','news'||(i%12)||'@sender.example'
            FROM generate_series(1,360) i
            """,account);
        localLabels.processBatch(account,500);
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,lifecycle,backfill_status,initial_batch_completed_at,incremental_last_success_at) VALUES(?,'google','ACTIVE','COMPLETE',now(),now())",account);
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
                    if (action == com.tidymail.mail.BulkActionType.ARCHIVE
                            || action == com.tidymail.mail.BulkActionType.MARK_READ_ARCHIVE)
                        next.remove("INBOX");
                    else
                        next.add("TRASH");
                    return next;
                });
            if (action == com.tidymail.mail.BulkActionType.MARK_READ_ARCHIVE)
                providerUnread.removeAll(ids);
            return null;
        }).when(gmailProvider).cleanupOnce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call->{
            java.util.List<String> ids=call.getArgument(1);String label=call.getArgument(2);boolean skip=call.getArgument(3);
            for(String id:ids)providerLabels.computeIfPresent(id,(key,current)->{var next=new java.util.HashSet<>(current);next.add(label);if(skip)next.remove("INBOX");return next;});return null;
        }).when(gmailProvider).fileOnce(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyBoolean());
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
                    body=html.replace("<main class=\"app\">","<main class=\"app\"><small data-browser-fixture>Synthetic test mailbox</small>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            cleanup.shutdownNow();
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }
}
