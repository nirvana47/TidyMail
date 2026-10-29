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

/** Explicit-only browser harness: authenticated MockMvc bridge over loopback, synthetic DB/provider. */
class DashboardBrowserFixture extends PostgresJourneyTest {
    @Test void serveSyntheticDashboardForBrowserAcceptance() throws Exception {
        long account=userAccounts.createAccount("browser-fixture@example.test");
        jdbc.update("""
          INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,from_display_name,
            subject_header,received_at,unread,labels,label_evidence_known,dashboard_from_address,
            dashboard_unit_kind,dashboard_unit_key,dashboard_identity_reason)
          SELECT ?,'google','browser-'||i,'news'||(i%12)||'@sender.example','Newsletter '||(i%12),
            'Indexed subject '||i,TIMESTAMPTZ '2026-10-28 12:00:00+00'-(i%30)*interval '1 day',
            (i/12)%2=0,CASE WHEN (i/12)%5=0 THEN ARRAY[]::text[] ELSE ARRAY['INBOX'] END,true,
            'news'||(i%12)||'@sender.example','ADDRESS','news'||(i%12)||'@sender.example','Kept separate — insufficient domain evidence'
          FROM generate_series(1,360) i
          """,account);
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,lifecycle,backfill_status,initial_batch_completed_at,incremental_last_success_at) VALUES(?,'google','ACTIVE','COMPLETE',now(),now())",account);
        var session=new MockHttpSession();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                var req=request(HttpMethod.valueOf(exchange.getRequestMethod()),URI.create(exchange.getRequestURI().toString()))
                    .with(authenticatedAs("browser-fixture@example.test")).session(session);
                exchange.getRequestHeaders().forEach((name,values)->{if(!name.equalsIgnoreCase("Host")&&!name.equalsIgnoreCase("Content-Length"))req.header(name,values.toArray());});
                if(!exchange.getRequestMethod().equals("GET"))req.content(exchange.getRequestBody().readAllBytes());
                var response=mockMvc.perform(req).andReturn().getResponse();
                for(String header:response.getHeaderNames())for(String value:response.getHeaders(header))exchange.getResponseHeaders().add(header,value);
                byte[] body=response.getContentAsByteArray();exchange.sendResponseHeaders(response.getStatus(),body.length==0?-1:body.length);
                if(body.length>0)exchange.getResponseBody().write(body);exchange.close();
            } catch(Exception error) {exchange.sendResponseHeaders(500,-1);exchange.close();throw new RuntimeException(error);}
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());server.start();
        Path done=Path.of("/tmp/tidymail-phase3-browser-done");Files.deleteIfExists(done);
        System.out.printf("DASHBOARD_BROWSER_FIXTURE http://127.0.0.1:%d/dashboard%n",server.getAddress().getPort());
        try {long deadline=System.nanoTime()+java.time.Duration.ofMinutes(15).toNanos();while(!Files.exists(done)&&System.nanoTime()<deadline)Thread.sleep(500);}
        finally {server.stop(0);((java.util.concurrent.ExecutorService)server.getExecutor()).shutdownNow();}
    }
}
