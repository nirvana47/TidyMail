package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.sun.net.httpserver.HttpServer;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.tidymail.mail.BulkActionType;

class DashboardGmailDispatchTest {
    @ParameterizedTest @CsvSource({"200,MARK_READ","401,MARK_READ","503,MARK_READ","200,ARCHIVE","401,ARCHIVE","503,ARCHIVE","200,TRASH","401,TRASH","503,TRASH"})
    void exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries(int status,BulkActionType action) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var body=new AtomicReference<String>();var path=new AtomicReference<String>();
        server.createContext("/",exchange->{
            calls.incrementAndGet();path.set(exchange.getRequestURI().getPath());
            var input=exchange.getRequestBody();if("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")))input=new GZIPInputStream(input);
            body.set(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status,-1);exchange.close();
        });server.start();
        try {
            var gmail=new Gmail.Builder(new NetHttpTransport(),JacksonFactory.getDefaultInstance(),request->{
                request.setNumberOfRetries(10);request.setUnsuccessfulResponseHandler((req,response,retry)->true);
                request.setConnectTimeout(1000);request.setReadTimeout(1000);
            }).setApplicationName("TidyMail synthetic test").setRootUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/").build();
            var factory=mock(GmailClientFactory.class);var account=new UserAccount();when(factory.buildFor(account)).thenReturn(gmail);
            var config=new GmailSyncConfigProperties();config.setApiConcurrency(2);config.setBatchDelayMs(0);var limiter=new GmailRateLimiter(config);limiter.init();
            var provider=new GmailMailService(factory,limiter,config);
            if(status==200)provider.cleanupOnce(account,List.of("synthetic-one","synthetic-two"),action);
            else assertThatThrownBy(()->provider.cleanupOnce(account,List.of("synthetic-one","synthetic-two"),action)).hasMessageContaining("verification");
            assertThat(calls.get()).isEqualTo(1);assertThat(path.get()).endsWith("/users/me/messages/batchModify");
            var request=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
            assertThat(request.get("ids").toString()).isEqualTo("[\"synthetic-one\",\"synthetic-two\"]");
            if(action==BulkActionType.TRASH){assertThat(request.get("addLabelIds").toString()).isEqualTo("[\"TRASH\"]");assertThat(request.has("removeLabelIds")).isFalse();}
            else {assertThat(request.get("removeLabelIds").toString()).isEqualTo(action==BulkActionType.ARCHIVE?"[\"INBOX\"]":"[\"UNREAD\"]");assertThat(request.has("addLabelIds")).isFalse();}
        } finally {server.stop(0);}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={401,403,429,503})
    void cleanupMetadataHasOneAttemptAndPropagatesRetryGuidanceWithoutSleeping(int status) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            String body="--test_boundary\r\nContent-Type: application/http\r\nContent-ID: response-1\r\n\r\nHTTP/1.1 "+status+" status\r\nContent-Type: application/json\r\nRetry-After: 120\r\n\r\n{\"error\":{\"code\":"+status+",\"message\":\"synthetic\"}}\r\n--test_boundary--\r\n";
            var bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","multipart/mixed; boundary=test_boundary");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var gmail=new Gmail.Builder(new NetHttpTransport(),JacksonFactory.getDefaultInstance(),request->{request.setNumberOfRetries(10);})
                .setApplicationName("TidyMail synthetic test").setRootUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/").build();
            var factory=mock(GmailClientFactory.class);var account=new UserAccount();when(factory.buildFor(account)).thenReturn(gmail);
            var config=new GmailSyncConfigProperties();config.setApiConcurrency(2);config.setBatchDelayMs(0);
            var limiter=new GmailRateLimiter(config);limiter.init();var provider=new GmailMailService(factory,limiter,config);
            assertThatThrownBy(()->provider.getCleanupMetadata(account,List.of("synthetic-one")))
                .isInstanceOfSatisfying(com.tidymail.mail.CleanupProviderException.class,error->{
                    assertThat(error.status()).isEqualTo(status);assertThat(error.retryAt()).isAfter(java.time.Instant.now().plusSeconds(110));
                });
            assertThat(calls.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @org.junit.jupiter.api.Test
    void cleanupSharesBackgroundCapacityWhileFreshnessRemainsAvailable() throws Exception {
        var config=new GmailSyncConfigProperties();config.setApiConcurrency(2);config.setBatchDelayMs(0);
        var limiter=new GmailRateLimiter(config);limiter.init();var entered=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var backfill=limiter.acquireBackfillRescanPermit()) {
            var cleanup=pool.submit(()->limiter.supplyWithCleanupPermit(()->{entered.countDown();return true;}));
            try(var freshness=limiter.acquireFreshnessPermit()){assertThat(entered.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();}
            backfill.close();assertThat(cleanup.get(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {pool.shutdownNow();}
    }

}
