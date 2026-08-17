package com.tidymail.unsubscribe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class Phase11UnsubscribeOnWireTlsTest {
    private static final String HOSTNAME = "unsubscribe.example";

    @Test
    @Timeout(20)
    void realClientPinsAddressWhileKeepingTlsSniCertificateAndHostAuthority()
            throws Exception {
        TlsMaterial tls = tlsMaterial();
        AtomicReference<WireRequest> received = new AtomicReference<>();
        try (SSLServerSocket server = tls.serverSocket();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var serverResult = executor.submit(() -> serveOnce(server, received));
            RedirectingSslSocketFactory socketFactory =
                new RedirectingSslSocketFactory(tls.clientContext(), server.getLocalPort());
            AtomicReference<PinnedTransportFactory.PinnedDnsResolver> resolver =
                new AtomicReference<>();
            PinnedTransportFactory factory = new PinnedTransportFactory(
                properties(), socketFactory, resolver::set, socketFactory::route);
            ApacheUnsubscribeHttpTransport transport =
                new ApacheUnsubscribeHttpTransport(factory, properties());
            ResolvedDestination destination = new ResolvedDestination(
                HOSTNAME, List.of(InetAddress.getByName("8.8.8.8")));

            var result = transport.execute(ValidatedUnsubscribeRequest.oneClickPost(
                new java.net.URI("https://" + HOSTNAME + "/unsubscribe?token=SECRET"),
                destination));

            assertThat(socketFactory.connectCalls).as(result.toString()).hasValue(1);
            assertThat(result).isEqualTo(UnsubscribeHttpTransport.TransportResult.accepted());
            serverResult.get(15, TimeUnit.SECONDS);
            assertThat(resolver.get().resolveCalls()).isOne();
            assertThat(socketFactory.connectCalls).hasValue(1);
            assertThat(socketFactory.validatedRemote.get().getAddress().getHostAddress())
                .isEqualTo("8.8.8.8");
            assertThat(socketFactory.targetHostname).hasValue(HOSTNAME);
            assertThat(received.get().sniHostname()).isEqualTo(HOSTNAME);
            assertThat(received.get().headers())
                .contains("Host: " + HOSTNAME)
                .contains("Content-Type: application/x-www-form-urlencoded")
                .doesNotContain(
                    "Authorization:", "Cookie:", "Referer:", "User-Agent:");
            assertThat(received.get().requestLine())
                .isEqualTo("POST /unsubscribe?token=SECRET HTTP/1.1");
            assertThat(received.get().body())
                .isEqualTo("List-Unsubscribe=One-Click");
        }
    }

    @Test
    @Timeout(20)
    void certificateHostnameMismatchFailsClosed() throws Exception {
        TlsMaterial tls = tlsMaterial();
        try (SSLServerSocket server = tls.serverSocket();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var serverResult = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    try {
                        socket.startHandshake();
                    } catch (IOException expected) {
                        // The client terminates the handshake after hostname verification fails.
                    }
                }
                return null;
            });
            RedirectingSslSocketFactory socketFactory =
                new RedirectingSslSocketFactory(tls.clientContext(), server.getLocalPort());
            AtomicReference<PinnedTransportFactory.PinnedDnsResolver> resolver =
                new AtomicReference<>();
            PinnedTransportFactory factory = new PinnedTransportFactory(
                properties(), socketFactory, resolver::set, socketFactory::route);
            ApacheUnsubscribeHttpTransport transport =
                new ApacheUnsubscribeHttpTransport(factory, properties());
            ResolvedDestination destination = new ResolvedDestination(
                "other.example", List.of(InetAddress.getByName("8.8.8.8")));

            var result = transport.execute(ValidatedUnsubscribeRequest.legacyGet(
                new java.net.URI("https://other.example/unsubscribe"), destination));

            assertThat(result.reason())
                .isEqualTo(UnsubscribeHttpTransport.FailureReason.TLS_FAILURE);
            assertThat(resolver.get().resolveCalls()).isOne();
            assertThat(socketFactory.connectCalls).hasValue(1);
            serverResult.get(15, TimeUnit.SECONDS);
        }
    }

    private static Void serveOnce(
            SSLServerSocket server, AtomicReference<WireRequest> received) throws Exception {
        try (SSLSocket socket = (SSLSocket) server.accept()) {
            socket.startHandshake();
            ExtendedSSLSession session = (ExtendedSSLSession) socket.getSession();
            String sni = session.getRequestedServerNames().stream()
                .filter(SNIHostName.class::isInstance)
                .map(SNIHostName.class::cast)
                .map(SNIHostName::getAsciiName)
                .findFirst()
                .orElse(null);
            byte[] headerBytes = readHeaders(socket);
            String headers = new String(headerBytes, StandardCharsets.US_ASCII);
            int separator = headers.indexOf("\r\n");
            int contentLength = headers.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT)
                    .startsWith("content-length:"))
                .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()))
                .findFirst()
                .orElse(0);
            String body = new String(
                socket.getInputStream().readNBytes(contentLength), StandardCharsets.US_ASCII);
            received.set(new WireRequest(
                headers.substring(0, separator),
                headers.substring(separator + 2),
                body,
                sni));
            socket.getOutputStream().write(("""
                HTTP/1.1 204 No Content\r
                Connection: close\r
                Content-Length: 0\r
                \r
                """).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        }
        return null;
    }

    private static byte[] readHeaders(SSLSocket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = socket.getInputStream().read();
            if (value == -1) throw new IOException("Request headers ended early");
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (bytes.size() > 16_384) throw new IOException("Request headers too large");
        }
        return bytes.toByteArray();
    }

    private static TlsMaterial tlsMaterial() throws Exception {
        X509Certificate certificate;
        try (var input = resource("tls/unsubscribe-example-cert.pem")) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(input);
        }
        String keyPem;
        try (var input = resource("tls/unsubscribe-example-key.pem")) {
            keyPem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        byte[] encodedKey = Base64.getMimeDecoder().decode(
            keyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""));
        PrivateKey key = KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(encodedKey));

        KeyStore serverKeys = KeyStore.getInstance(KeyStore.getDefaultType());
        serverKeys.load(null);
        serverKeys.setKeyEntry("server", key, "changeit".toCharArray(),
            new java.security.cert.Certificate[] {certificate});
        KeyManagerFactory keyManagers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        trust.setCertificateEntry("server", certificate);
        TrustManagerFactory trustManagers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustManagers.getTrustManagers(), null);
        return new TlsMaterial(serverContext, clientContext);
    }

    private static ByteArrayInputStream resource(String path) throws IOException {
        try (var input = Phase11UnsubscribeOnWireTlsTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing TLS fixture");
            return new ByteArrayInputStream(input.readAllBytes());
        }
    }

    private static UnsubscribeTransportProperties properties() {
        return new UnsubscribeTransportProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(3),
            Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 8192, 0, 0);
    }

    private record TlsMaterial(SSLContext serverContext, SSLContext clientContext) {
        SSLServerSocket serverSocket() throws IOException {
            SSLServerSocket socket = (SSLServerSocket) serverContext
                .getServerSocketFactory().createServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            socket.setSoTimeout(15_000);
            return socket;
        }
    }

    private record WireRequest(
            String requestLine, String headers, String body, String sniHostname) {
    }

    private static final class RedirectingSslSocketFactory
            extends SSLConnectionSocketFactory {
        private final int fixturePort;
        private final AtomicInteger connectCalls = new AtomicInteger();
        private final AtomicReference<InetSocketAddress> validatedRemote =
            new AtomicReference<>();
        private final AtomicReference<String> targetHostname = new AtomicReference<>();

        RedirectingSslSocketFactory(SSLContext context, int fixturePort) {
            super(context);
            this.fixturePort = fixturePort;
        }

        @Override
        public Socket createLayeredSocket(
                Socket socket,
                String target,
                int port,
                HttpContext context) throws IOException {
            targetHostname.set(target);
            return super.createLayeredSocket(socket, target, port, context);
        }

        InetSocketAddress route(InetSocketAddress validatedAddress) {
            connectCalls.incrementAndGet();
            validatedRemote.set(validatedAddress);
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), fixturePort);
        }
    }
}
