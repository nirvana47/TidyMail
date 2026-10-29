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
import org.junit.jupiter.params.provider.ValueSource;

class DashboardGmailDispatchTest {
    @ParameterizedTest @ValueSource(ints={200,401,503})
    void exactMarkReadRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries(int status) throws Exception {
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
            if(status==200)provider.markReadOnce(account,List.of("synthetic-one","synthetic-two"));
            else assertThatThrownBy(()->provider.markReadOnce(account,List.of("synthetic-one","synthetic-two"))).hasMessageContaining("verification");
            assertThat(calls.get()).isEqualTo(1);assertThat(path.get()).endsWith("/users/me/messages/batchModify");
            var request=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
            assertThat(request.get("ids").toString()).isEqualTo("[\"synthetic-one\",\"synthetic-two\"]");
            assertThat(request.get("removeLabelIds").toString()).isEqualTo("[\"UNREAD\"]");
            assertThat(request.has("addLabelIds")).isFalse();
        } finally {server.stop(0);}
    }
}
