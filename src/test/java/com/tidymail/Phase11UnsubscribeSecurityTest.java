package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tidymail.unsubscribe.ApacheUnsubscribeHttpTransport;
import com.tidymail.unsubscribe.DestinationResolver;
import com.tidymail.unsubscribe.DestinationResolver.Reason;
import com.tidymail.unsubscribe.PinnedTransportFactory;
import com.tidymail.unsubscribe.PublicAddressPolicy;
import com.tidymail.unsubscribe.ResolvedDestination;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport.FailureReason;
import com.tidymail.unsubscribe.UnsubscribeTransportProperties;
import com.tidymail.unsubscribe.UnsubscribeUriPolicy;
import com.tidymail.unsubscribe.ValidatedUnsubscribeRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class Phase11UnsubscribeSecurityTest {

    @Test
    void rejectsEveryNonGlobalIpv4ClassAtPrefixBoundaries() throws Exception {
        PublicAddressPolicy policy = new PublicAddressPolicy();
        List<String> blocked = List.of(
            "0.0.0.0", "0.255.255.255",
            "10.0.0.0", "10.255.255.255",
            "100.64.0.0", "100.127.255.255",
            "127.0.0.0", "127.255.255.255",
            "169.254.0.0", "169.254.255.255",
            "172.16.0.0", "172.31.255.255",
            "192.0.0.0", "192.0.0.255",
            "192.0.2.0", "192.0.2.255",
            "192.88.99.0", "192.88.99.255",
            "192.168.0.0", "192.168.255.255",
            "198.18.0.0", "198.19.255.255",
            "198.51.100.0", "198.51.100.255",
            "203.0.113.0", "203.0.113.255",
            "224.0.0.0", "239.255.255.255",
            "240.0.0.0", "255.255.255.255");
        assertThat(blocked)
            .allSatisfy(value -> assertThat(policy.isGlobalUnicast(address(value)))
                .as(value).isFalse());
        assertThat(List.of("1.1.1.1", "8.8.8.8", "100.63.255.255",
                "100.128.0.0", "172.15.255.255", "172.32.0.0",
                "192.0.0.9", "192.0.0.10", "198.17.255.255", "198.20.0.0"))
            .allSatisfy(value -> assertThat(policy.isGlobalUnicast(address(value)))
                .as(value).isTrue());
    }

    @Test
    void rejectsIpv6LocalReservedDocumentationAndTransitionForms() throws Exception {
        PublicAddressPolicy policy = new PublicAddressPolicy();
        List<String> blocked = List.of(
            "::", "::1", "::192.0.2.1", "64:ff9b::808:808",
            "64:ff9b:1::1", "100::1", "100:0:0:1::1", "5f00::1",
            "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "4000::", "4000::1",
            "7fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "8000::1", "f000::1",
            "2001::1", "2001:2::1", "2001:10::1", "2001:20::1",
            "2001:db8::1", "2002:0808:0808::1", "3fff::1",
            "fc00::1", "fdff:ffff::1", "fe80::1", "fec0::1", "ff00::1");
        assertThat(blocked)
            .allSatisfy(value -> assertThat(policy.isGlobalUnicast(address(value)))
                .as(value).isFalse());
        assertThat(policy.isGlobalUnicast(address("2606:4700:4700::1111"))).isTrue();
        assertThat(policy.isGlobalUnicast(address("2001:4860:4860::8888"))).isTrue();
        assertThat(List.of(
                "2001:1::1", "2001:1::2", "2001:1::3",
                "2001:3::1", "2001:4:112::1", "2001:30::1",
                "2620:4f:8000::1"))
            .allSatisfy(value -> assertThat(policy.isGlobalUnicast(address(value)))
                .as(value).isTrue());

        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 8;
        mapped[13] = 8;
        mapped[14] = 8;
        mapped[15] = 8;
        assertThat(policy.isGlobalUnicast(
            Inet6Address.getByAddress(null, mapped, -1))).isFalse();
        assertThat(policy.isGlobalUnicast(Inet6Address.getByAddress(
            null, address("2606:4700:4700::1111").getAddress(), 1))).isFalse();
    }

    @Test
    void hostnamePolicyFailsBeforeDnsAndHandsOffCanonicalIdn() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<String> lookedUp = new AtomicReference<>();
        try (DestinationResolver resolver = resolver(host -> {
            lookups.incrementAndGet();
            lookedUp.set(host);
            return new InetAddress[] {address("8.8.8.8")};
        }, Duration.ofSeconds(1), 8)) {
            for (String invalid : List.of(
                    "localhost", "api.localhost", "printer", "x.local",
                    "x.localdomain", "x.internal", "x.home.arpa", "example.com.",
                    "-bad.example", "bad-.example", "xn--a.example",
                    "bad\\host.example", "0177.0.0.1", "1.0x7f.1")) {
                assertResolutionReason(resolver, invalid, Reason.INVALID_HOSTNAME);
            }
            assertThat(lookups).hasValue(0);

            ResolvedDestination destination = resolver.resolveAndValidate("B\u00fccher.Example");
            assertThat(destination.asciiHostname()).isEqualTo("xn--bcher-kva.example");
            assertThat(lookedUp).hasValue("xn--bcher-kva.example");
            assertThat(lookups).hasValue(1);
        }
    }

    @Test
    void resolveOnceDeduplicatesAndRejectsEmptyExcessAndMixedAnswers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (DestinationResolver resolver = resolver(host -> {
            calls.incrementAndGet();
            return new InetAddress[] {
                address("8.8.8.8"), address("8.8.8.8"), address("1.1.1.1")
            };
        }, Duration.ofSeconds(1), 8)) {
            ResolvedDestination destination = resolver.resolveAndValidate("safe.example");
            assertThat(destination.addresses()).extracting(InetAddress::getHostAddress)
                .containsExactly("8.8.8.8", "1.1.1.1");
            assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> destination.addresses().add(address("9.9.9.9")))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        assertRejectedAnswers(new InetAddress[0], 8, Reason.NO_ADDRESSES);
        assertRejectedAnswers(new InetAddress[] {
            address("8.8.8.8"), address("10.0.0.1")
        }, 8, Reason.NON_PUBLIC_ADDRESS);
        InetAddress[] nine = new InetAddress[9];
        for (int index = 0; index < nine.length; index++) {
            nine[index] = address("8.8.8." + (index + 1));
        }
        assertRejectedAnswers(nine, 8, Reason.TOO_MANY_ADDRESSES);
    }

    @Test
    void dnsDeadlineRejectsLateAnswersAndNeverUsesThem() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        try (DestinationResolver resolver = resolver(host -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            completed.incrementAndGet();
            return new InetAddress[] {address("8.8.8.8")};
        }, Duration.ofMillis(20), 8)) {
            assertResolutionReason(resolver, "slow.example", Reason.DNS_TIMEOUT);
            Thread.sleep(80);
            assertThat(completed.get()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void pinnedFactoryUsesImmutableValidatedSetAndOriginalTlsHostnameOnly()
            throws Exception {
        UnsubscribeTransportProperties properties = properties();
        ResolvedDestination destination = new ResolvedDestination(
            "unsubscribe.example", List.of(address("8.8.8.8"), address("1.1.1.1")));
        PinnedTransportFactory factory = new PinnedTransportFactory(properties);
        try (var handle = factory.create(destination)) {
            assertThat(handle.resolver().tlsHostname()).isEqualTo("unsubscribe.example");
            assertThat(handle.resolver().resolve("unsubscribe.example"))
                .extracting(InetAddress::getHostAddress)
                .containsExactly("8.8.8.8", "1.1.1.1");
            InetAddress[] first = handle.resolver().pinnedAddresses();
            first[0] = address("10.0.0.1");
            assertThat(handle.resolver().pinnedAddresses())
                .extracting(InetAddress::getHostAddress)
                .containsExactly("8.8.8.8", "1.1.1.1");
            assertThatThrownBy(() -> handle.resolver().resolve("rebound.example"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageNotContaining("8.8.8.8");
            assertThat(handle.requestConfig().isRedirectsEnabled()).isFalse();
            assertThat(handle.requestConfig().isAuthenticationEnabled()).isFalse();
            assertThat(handle.requestConfig().getProxy()).isNull();
            assertThat(handle.requestConfig().getMaxRedirects()).isZero();
        }
    }

    @Test
    void exactPostAndBodylessGetExposeNoCredentialHeaders(CapturedOutput output)
            throws Exception {
        CapturingFactory fixture = new CapturingFactory(204, new byte[0], 0);
        ApacheUnsubscribeHttpTransport transport = fixture.transport(properties());
        var destination = destination();
        var postResult = transport.execute(ValidatedUnsubscribeRequest.oneClickPost(
            new java.net.URI("https://unsubscribe.example/u?token=SECRET"), destination));
        assertThat(postResult.outcome().name()).isEqualTo("ACCEPTED");
        assertThat(fixture.requests).hasSize(1);
        CapturedRequest post = fixture.requests.getFirst();
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.body()).isEqualTo(
            "List-Unsubscribe=One-Click".getBytes(StandardCharsets.US_ASCII));
        assertThat(post.headers()).contains("Content-Type: application/x-www-form-urlencoded");
        assertThat(post.headers().stream()
            .filter(header -> header.toLowerCase().startsWith("content-type:")))
            .containsExactly("Content-Type: application/x-www-form-urlencoded");
        assertThat(post.headers()).noneMatch(header ->
            header.toLowerCase().startsWith("authorization:")
                || header.toLowerCase().startsWith("cookie:")
                || header.toLowerCase().startsWith("referer:")
                || header.toLowerCase().startsWith("user-agent:"));
        assertThat(post.targetHost()).isEqualTo("unsubscribe.example");

        fixture = new CapturingFactory(200, new byte[0], 0);
        transport = fixture.transport(properties());
        transport.execute(ValidatedUnsubscribeRequest.legacyGet(
            new java.net.URI("https://unsubscribe.example/u"), destination));
        assertThat(fixture.requests.getFirst().method()).isEqualTo("GET");
        assertThat(fixture.requests.getFirst().body()).isEmpty();

        fixture = new CapturingFactory(
            500, "RESPONSE_BODY_CANARY".getBytes(StandardCharsets.US_ASCII), 0);
        fixture.transport(properties()).execute(ValidatedUnsubscribeRequest.legacyGet(
            new java.net.URI(
                "https://unsubscribe.example/u?token=URI_QUERY_CANARY"), destination));
        assertThat(output.getAll())
            .doesNotContain("URI_QUERY_CANARY", "RESPONSE_BODY_CANARY");
    }

    @Test
    void everyRedirectStopsAfterOneRequestWithoutInspectingLocation() throws Exception {
        List<String> locations = Arrays.asList(
            null,
            "https://public.example/next",
            "https://127.0.0.1/private",
            "http://public.example/insecure",
            "https://user:password@public.example/credentialed",
            ":// malformed location");
        for (int status : List.of(300, 301, 302, 303, 304, 305, 307, 308)) {
            for (String location : locations) {
                CapturingFactory fixture =
                    new CapturingFactory(status, new byte[0], 0, location);
                var result = fixture.transport(properties()).execute(
                    ValidatedUnsubscribeRequest.legacyGet(
                        new java.net.URI("https://unsubscribe.example/u"), destination()));
                assertThat(result.reason()).as("status " + status + ", " + location)
                    .isEqualTo(FailureReason.REDIRECT_BLOCKED);
                assertThat(result.httpStatusClass()).isEqualTo(3);
                assertThat(fixture.requests).hasSize(1);
                assertThat(fixture.createCalls).hasValue(1);
            }
        }

        CapturingFactory oversizedRedirect =
            new CapturingFactory(302, new byte[16_384], 0, false, false);
        var result = oversizedRedirect.transport(properties()).execute(
            ValidatedUnsubscribeRequest.legacyGet(
                new java.net.URI("https://unsubscribe.example/u"), destination()));
        assertThat(result.reason()).isEqualTo(FailureReason.REDIRECT_BLOCKED);
        assertThat(oversizedRedirect.responseBytesRead).hasValue(0);
    }

    @Test
    void responseCapAndTotalTimeoutAreDeterministicAndSanitized() throws Exception {
        byte[] exact = new byte[8192];
        CapturingFactory fixture = new CapturingFactory(200, exact, 0);
        assertThat(fixture.transport(properties()).execute(
            ValidatedUnsubscribeRequest.legacyGet(
                new java.net.URI("https://unsubscribe.example/u"), destination())).outcome().name())
            .isEqualTo("ACCEPTED");

        byte[] overflow = new byte[8193];
        fixture = new CapturingFactory(200, overflow, 0);
        var tooLarge = fixture.transport(properties()).execute(
            ValidatedUnsubscribeRequest.legacyGet(
                new java.net.URI("https://unsubscribe.example/u"), destination()));
        assertThat(tooLarge.reason()).isEqualTo(FailureReason.RESPONSE_TOO_LARGE);
        assertThat(tooLarge.toString()).doesNotContain(Arrays.toString(overflow));

        fixture = new CapturingFactory(200, new byte[16_384], 0, false, false);
        tooLarge = fixture.transport(properties()).execute(
            ValidatedUnsubscribeRequest.legacyGet(
                new java.net.URI("https://unsubscribe.example/u"), destination()));
        assertThat(tooLarge.reason()).isEqualTo(FailureReason.RESPONSE_TOO_LARGE);
        assertThat(fixture.responseBytesRead).hasValue(8193);

        UnsubscribeTransportProperties shortTotal = new UnsubscribeTransportProperties(
            Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofMillis(20),
            Duration.ofMillis(20), Duration.ofMillis(40), 8, 8192, 0, 0);
        fixture = new CapturingFactory(200, new byte[0], 1000, true, true);
        long started = System.nanoTime();
        var timedOut = fixture.transport(shortTotal).execute(
            ValidatedUnsubscribeRequest.legacyGet(
                new java.net.URI("https://unsubscribe.example/u"), destination()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(timedOut.reason())
            .isEqualTo(FailureReason.TIMEOUT_UNKNOWN_REMOTE_RESULT);
        assertThat(fixture.requests).hasSize(1);
        assertThat(elapsedMillis).isLessThan(500);
    }

    @Test
    void unusualStatusClassesAreRejectedWithoutPersistableStatusDetails() throws Exception {
        for (int status : List.of(101, 199, 600, 999)) {
            CapturingFactory fixture = new CapturingFactory(status, new byte[0], 0);
            var result = fixture.transport(properties()).execute(
                ValidatedUnsubscribeRequest.legacyGet(
                    new java.net.URI("https://unsubscribe.example/u"), destination()));
            assertThat(result.reason()).as("status " + status)
                .isEqualTo(FailureReason.REMOTE_REJECTED);
            assertThat(result.httpStatusClass()).isNull();
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @Test
    void requestAndConfigurationRejectUnsafeSchemePortMethodAndLimits() throws Exception {
        UnsubscribeUriPolicy policy = new UnsubscribeUriPolicy();
        assertThat(policy.validate("http://unsubscribe.example/u").valid()).isFalse();
        assertThat(policy.validate("https://unsubscribe.example:444/u").valid()).isFalse();
        assertThat(policy.validate("https://127.0.0.1/u").valid()).isFalse();
        assertThat(policy.validate("https://0177.0.0.1/u").valid()).isFalse();
        assertThat(policy.validate("https://1.0x7f.1/u").valid()).isFalse();
        assertThat(policy.validate("https://unsubscribe.example./u").valid()).isFalse();

        assertThatThrownBy(() -> ValidatedUnsubscribeRequest.oneClickPost(
            new java.net.URI("http://unsubscribe.example/u"), destination()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("http://");
        assertThatThrownBy(() -> new UnsubscribeTransportProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3),
            Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 8192, 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnsubscribeTransportProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3),
            Duration.ofSeconds(5), Duration.ofSeconds(10), 9, 8192, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnsubscribeTransportProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3),
            Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 8193, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedDestination(
            "unsubscribe.example", List.of(address("10.0.0.1"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("10.0.0.1");

        var request = ValidatedUnsubscribeRequest.legacyGet(
            new java.net.URI("https://unsubscribe.example/u?token=SECRET"), destination());
        assertThat(request.toString()).doesNotContain("SECRET", "/u?", "8.8.8.8");
        assertThat(request.destination().toString()).doesNotContain("8.8.8.8");
    }

    private static DestinationResolver resolver(
            DestinationResolver.DnsLookup lookup, Duration timeout, int maxAnswers) {
        return new DestinationResolver(
            lookup, new PublicAddressPolicy(), timeout, maxAnswers);
    }

    private static void assertRejectedAnswers(
            InetAddress[] answers, int maxAnswers, Reason reason) {
        try (DestinationResolver resolver = resolver(
                host -> answers, Duration.ofSeconds(1), maxAnswers)) {
            assertResolutionReason(resolver, "safe.example", reason);
        }
    }

    private static void assertResolutionReason(
            DestinationResolver resolver, String host, Reason reason) {
        assertThatThrownBy(() -> resolver.resolveAndValidate(host))
            .isInstanceOfSatisfying(
                DestinationResolver.ResolutionException.class,
                failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.getMessage()).isEqualTo("Destination resolution failed");
                    assertThat(failure.getMessage()).doesNotContain(host);
                });
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static ResolvedDestination destination() {
        return new ResolvedDestination(
            "unsubscribe.example", List.of(address("8.8.8.8")));
    }

    private static UnsubscribeTransportProperties properties() {
        return new UnsubscribeTransportProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3),
            Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 8192, 0, 0);
    }

    private record CapturedRequest(
            String method, String targetHost, byte[] body, List<String> headers) {
        private CapturedRequest {
            body = body.clone();
            headers = List.copyOf(headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class CapturingFactory {
        private final int status;
        private final byte[] responseBody;
        private final long delayMillis;
        private final boolean ignoreInterrupts;
        private final boolean advertisedLength;
        private final String location;
        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicInteger responseBytesRead = new AtomicInteger();
        private final List<CapturedRequest> requests = new ArrayList<>();

        CapturingFactory(int status, byte[] responseBody, long delayMillis) {
            this(status, responseBody, delayMillis, false, true,
                "http://127.0.0.1/SECRET_LOCATION");
        }

        CapturingFactory(
                int status, byte[] responseBody, long delayMillis, String location) {
            this(status, responseBody, delayMillis, false, true, location);
        }

        CapturingFactory(
                int status,
                byte[] responseBody,
                long delayMillis,
                boolean ignoreInterrupts,
                boolean advertisedLength) {
            this(status, responseBody, delayMillis, ignoreInterrupts, advertisedLength,
                "http://127.0.0.1/SECRET_LOCATION");
        }

        CapturingFactory(
                int status,
                byte[] responseBody,
                long delayMillis,
                boolean ignoreInterrupts,
                boolean advertisedLength,
                String location) {
            this.status = status;
            this.responseBody = responseBody.clone();
            this.delayMillis = delayMillis;
            this.ignoreInterrupts = ignoreInterrupts;
            this.advertisedLength = advertisedLength;
            this.location = location;
        }

        ApacheUnsubscribeHttpTransport transport(
                UnsubscribeTransportProperties properties) throws Exception {
            PinnedTransportFactory factory = mock(PinnedTransportFactory.class);
            when(factory.create(any())).thenAnswer(invocation -> {
                createCalls.incrementAndGet();
                ResolvedDestination destination = invocation.getArgument(0);
                var realHandle = new PinnedTransportFactory(properties).create(destination);
                var resolver = realHandle.resolver();
                var config = realHandle.requestConfig();
                realHandle.close();
                return new PinnedTransportFactory.ClientHandle(
                    new StubClient(
                        status, responseBody, delayMillis, ignoreInterrupts,
                        advertisedLength, location, responseBytesRead, requests),
                    resolver,
                    config);
            });
            return new ApacheUnsubscribeHttpTransport(factory, properties);
        }
    }

    private static final class StubClient extends CloseableHttpClient {
        private final int status;
        private final byte[] responseBody;
        private final long delayMillis;
        private final boolean ignoreInterrupts;
        private final boolean advertisedLength;
        private final String location;
        private final AtomicInteger responseBytesRead;
        private final List<CapturedRequest> requests;

        StubClient(
                int status,
                byte[] responseBody,
                long delayMillis,
                boolean ignoreInterrupts,
                boolean advertisedLength,
                String location,
                AtomicInteger responseBytesRead,
                List<CapturedRequest> requests) {
            this.status = status;
            this.responseBody = responseBody.clone();
            this.delayMillis = delayMillis;
            this.ignoreInterrupts = ignoreInterrupts;
            this.advertisedLength = advertisedLength;
            this.location = location;
            this.responseBytesRead = responseBytesRead;
            this.requests = requests;
        }

        @Override
        protected CloseableHttpResponse doExecute(
                HttpHost target,
                ClassicHttpRequest request,
                HttpContext context) throws IOException {
            byte[] body = request.getEntity() == null
                ? new byte[0] : EntityUtils.toByteArray(request.getEntity());
            List<String> headers = Arrays.stream(request.getHeaders())
                .map(header -> header.getName() + ": " + header.getValue())
                .toList();
            requests.add(new CapturedRequest(
                request.getMethod(), target.getHostName(), body, headers));
            if (delayMillis > 0) {
                long deadline = System.nanoTime() + Duration.ofMillis(delayMillis).toNanos();
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(Math.max(1, Duration.ofNanos(
                            deadline - System.nanoTime()).toMillis()));
                    } catch (InterruptedException interrupted) {
                        if (!ignoreInterrupts) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("interrupted");
                        }
                    }
                }
            }
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
            if (location != null) response.setHeader("Location", location);
            if (advertisedLength) {
                response.setEntity(new ByteArrayEntity(responseBody, null));
            } else {
                response.setEntity(new InputStreamEntity(
                    new CountingInputStream(responseBody, responseBytesRead), null));
            }
            return CloseableHttpResponse.adapt(response);
        }

        @Override
        public void close() {
        }

        @Override
        public void close(org.apache.hc.core5.io.CloseMode closeMode) {
        }
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private final AtomicInteger bytesRead;

        CountingInputStream(byte[] bytes, AtomicInteger bytesRead) {
            super(bytes);
            this.bytesRead = bytesRead;
        }

        @Override
        public synchronized int read() {
            int result = super.read();
            if (result != -1) bytesRead.incrementAndGet();
            return result;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int result = super.read(bytes, offset, length);
            if (result > 0) bytesRead.addAndGet(result);
            return result;
        }
    }
}
