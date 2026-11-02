package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.sun.net.httpserver.HttpServer;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.EmailSnippet;
import com.tidymail.mail.gmail.*;
import com.tidymail.service.CalendarEvidenceParser;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class GmailEvidenceReaderTest {
    HttpServer server; GmailEvidenceReader reader; UserAccount account;
    String structure; int status=200;
    final List<String> requests=new ArrayList<>();final AtomicInteger attachments=new AtomicInteger();
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            requests.add(URLDecoder.decode(exchange.getRequestURI().toString(),StandardCharsets.UTF_8));
            String body=structure;
            if(exchange.getRequestURI().getPath().contains("/attachments/")) {
                attachments.incrementAndGet();
                String ics="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:private-id\r\nDTSTART:20200101T090000Z\r\nDTEND:20200101T100000Z\r\nSUMMARY:Private title\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
                body=json.writeValueAsString(Map.of("data",Base64.getUrlEncoder().withoutPadding().encodeToString(ics.getBytes(StandardCharsets.UTF_8))));
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var gmail=new Gmail.Builder(new NetHttpTransport(),JacksonFactory.getDefaultInstance(),request->request.setNumberOfRetries(10))
            .setApplicationName("Synthetic evidence test").setRootUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/").build();
        var factory=mock(GmailClientFactory.class);account=new UserAccount();account.setId(1L);when(factory.buildFor(account)).thenReturn(gmail);
        var config=new GmailSyncConfigProperties();config.setApiConcurrency(3);config.setBatchDelayMs(0);var limiter=new GmailRateLimiter(config);limiter.init();
        reader=new GmailEvidenceReader(factory,limiter,json,new CalendarEvidenceParser());
    }
    @AfterEach void close(){server.stop(0);}
    @Test void selectiveCalendarReadsRetainPartProvenanceAndNeverDownloadPdfOrProse() throws Exception {
        structure="""
            {"id":"one","snippet":"Preview","payload":{"partId":"0","mimeType":"multipart/mixed","parts":[
            {"partId":"1","mimeType":"text/calendar","filename":"invite.ics","body":{"size":200,"attachmentId":"calendar"}},
            {"partId":"2","mimeType":"application/ics","filename":"copy.ics","body":{"size":200,"attachmentId":"copy"}},
            {"partId":"4","mimeType":"text/calendar","filename":"same.ics","body":{"size":200,"attachmentId":"calendar"}},
            {"partId":"3","mimeType":"application/pdf","filename":"private.pdf","body":{"size":9000000,"attachmentId":"pdf"}}]}}
            """;
        var evidence=reader.read(account,"one");
        assertThat(evidence.requests()).isEqualTo(3);assertThat(attachments.get()).isEqualTo(2);
        assertThat(evidence.calendars()).hasSize(3);
        assertThat(evidence.calendars()).extracting(CalendarEvidenceParser.Facts::partId).containsExactly("1","2","4");
        assertThat(evidence.calendars()).extracting(CalendarEvidenceParser.Facts::contentHash).containsOnly(evidence.calendars().getFirst().contentHash());assertThat(evidence.calendars().getFirst().conclusion()).contains("still need checking");
        assertThat(requests).allMatch(r->!r.contains("attachments/pdf"));
        assertThat(requests.getFirst()).contains("format=FULL").doesNotContain(",data");
        assertThat(json.writeValueAsString(evidence)).doesNotContain("Private title","private-id","BEGIN:VCALENDAR");
    }
    @Test void largeInlineContentNeedsNoAttachmentToRemainInspectable() {
        structure="""
            {"id":"one","payload":{"mimeType":"multipart/related","parts":[{"partId":"1","mimeType":"image/png","body":{"size":6000000},"headers":[{"name":"Content-Disposition","value":"inline"}]}]}}
            """;
        var evidence=reader.read(account,"one");
        assertThat(evidence.attachmentState()).isEqualTo("ABSENT");assertThat(evidence.parts()).anyMatch(GmailEvidenceReader.Part::inline);
        assertThat(attachments.get()).isZero();assertThat(evidence.snippet().state()).isEqualTo("ABSENT");
    }
    @Test void wrongIdentityContentAndOversizedResponsesAreRejectedWithoutRetries() {
        for(String response:List.of("{\"id\":\"wrong\"}","{\"id\":\"one\",\"payload\":{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"private\"}}}","x".repeat(1048577))) {
            structure=response;requests.clear();assertThatThrownBy(()->reader.read(account,"one")).hasMessageContaining("Could not check");assertThat(requests).hasSize(1);
        }
        structure="{}";status=503;requests.clear();assertThatThrownBy(()->reader.read(account,"one")).hasMessageContaining("Could not check");assertThat(requests).hasSize(1);
    }
    @Test void snippetsRespectUtf8BytesWithoutSplittingCharacters() {
        var snippet=EmailSnippet.observed("😀".repeat(400));
        assertThat(snippet.state()).isEqualTo("TRUNCATED");assertThat(snippet.text().getBytes(StandardCharsets.UTF_8)).hasSize(1024);
    }
}
