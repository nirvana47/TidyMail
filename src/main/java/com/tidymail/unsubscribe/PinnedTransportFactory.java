package com.tidymail.unsubscribe;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.socket.LayeredConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Creates Apache HttpClient clients pinned to a pre-validated destination so
 * connections only ever reach approved hosts, with TLS validated against the
 * pinned hostname.
 * <p>
 * Product functionality supported: the pinned, no-redirect transport behind
 * TidyMail's automated unsubscribe requests.
 */
@Component
public final class PinnedTransportFactory {
    private final UnsubscribeTransportProperties properties;
    private final LayeredConnectionSocketFactory testSocketFactory;
    private final Consumer<PinnedDnsResolver> testResolverObserver;
    private final ConnectionTargetOverride testConnectionTargetOverride;

    @Autowired
    public PinnedTransportFactory(UnsubscribeTransportProperties properties) {
        this(properties, null, resolver -> { }, null);
    }

    PinnedTransportFactory(
            UnsubscribeTransportProperties properties,
            LayeredConnectionSocketFactory testSocketFactory,
            Consumer<PinnedDnsResolver> testResolverObserver,
            ConnectionTargetOverride testConnectionTargetOverride) {
        this.properties = properties;
        this.testSocketFactory = testSocketFactory;
        this.testResolverObserver = testResolverObserver;
        this.testConnectionTargetOverride = testConnectionTargetOverride;
    }

    /**
     * Builds a client bound to the resolved destination with bounded timeouts,
     * no redirects, and no automatic retries.
     */
    public ClientHandle create(ResolvedDestination destination) {
        PinnedDnsResolver resolver = new PinnedDnsResolver(destination);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(Timeout.of(properties.connectTimeout()))
            .setSocketTimeout(Timeout.of(properties.responseHeaderTimeout()))
            .setTimeToLive(TimeValue.of(properties.totalTimeout()))
            .build();
        TlsConfig tlsConfig = TlsConfig.custom()
            .setHandshakeTimeout(Timeout.of(properties.tlsHandshakeTimeout()))
            .build();
        var connectionManagerBuilder =
            new ConnectionManagerBuilder(testConnectionTargetOverride)
            .setDnsResolver(resolver)
            .setMaxConnTotal(1)
            .setMaxConnPerRoute(1)
            .setDefaultConnectionConfig(connectionConfig)
            .setDefaultTlsConfig(tlsConfig);
        if (testSocketFactory != null) {
            connectionManagerBuilder.setSSLSocketFactory(testSocketFactory);
        }
        var connectionManager = connectionManagerBuilder.build();
        RequestConfig requestConfig = RequestConfig.custom()
            .setRedirectsEnabled(false)
            .setMaxRedirects(0)
            .setAuthenticationEnabled(false)
            .setContentCompressionEnabled(false)
            .setExpectContinueEnabled(false)
            .setHardCancellationEnabled(true)
            .setConnectionRequestTimeout(Timeout.of(properties.connectTimeout()))
            .setResponseTimeout(Timeout.of(properties.responseHeaderTimeout()))
            .build();
        CloseableHttpClient client = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setConnectionManagerShared(false)
            .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .setDefaultRequestConfig(requestConfig)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableConnectionState()
            .disableContentCompression()
            .disableDefaultUserAgent()
            .build();
        testResolverObserver.accept(resolver);
        return new ClientHandle(client, resolver, requestConfig);
    }

    public record ClientHandle(
            CloseableHttpClient client,
            PinnedDnsResolver resolver,
            RequestConfig requestConfig) implements AutoCloseable {
        @Override
        public void close() throws java.io.IOException {
            client.close(CloseMode.IMMEDIATE);
        }
    }

    public static final class PinnedDnsResolver implements DnsResolver {
        private final String hostname;
        private final InetAddress[] addresses;
        private final AtomicInteger resolveCalls = new AtomicInteger();

        private PinnedDnsResolver(ResolvedDestination destination) {
            this.hostname = destination.asciiHostname();
            this.addresses = destination.addresses().toArray(InetAddress[]::new);
        }

        @Override
        public InetAddress[] resolve(String requestedHostname) throws UnknownHostException {
            resolveCalls.incrementAndGet();
            requireExactHostname(requestedHostname);
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String requestedHostname)
                throws UnknownHostException {
            requireExactHostname(requestedHostname);
            return hostname;
        }

        public int resolveCalls() {
            return resolveCalls.get();
        }

        public String tlsHostname() {
            return hostname;
        }

        public InetAddress[] pinnedAddresses() {
            return addresses.clone();
        }

        private void requireExactHostname(String requestedHostname) throws UnknownHostException {
            if (!hostname.equals(requestedHostname)) {
                throw new UnknownHostException("Unvalidated destination");
            }
        }
    }

    @FunctionalInterface
    interface ConnectionTargetOverride {
        InetSocketAddress replace(InetSocketAddress validatedAddress);
    }

    private static final class ConnectionManagerBuilder
            extends PoolingHttpClientConnectionManagerBuilder {
        private final ConnectionTargetOverride targetOverride;

        ConnectionManagerBuilder(ConnectionTargetOverride targetOverride) {
            this.targetOverride = targetOverride;
        }

        @Override
        protected HttpClientConnectionOperator createConnectionOperator(
                SchemePortResolver schemePortResolver,
                DnsResolver dnsResolver,
                TlsSocketStrategy tlsSocketStrategy) {
            HttpClientConnectionOperator delegate = super.createConnectionOperator(
                schemePortResolver, dnsResolver, tlsSocketStrategy);
            if (targetOverride == null) return delegate;
            DetachedSocketFactory socketFactory = new DetachedSocketFactory() {
                @Override
                public Socket create(Proxy proxy) {
                    return proxy == null
                        ? new TargetOverrideSocket(targetOverride)
                        : new TargetOverrideSocket(proxy, targetOverride);
                }
            };
            return new DefaultHttpClientConnectionOperator(
                socketFactory,
                schemePortResolver,
                dnsResolver,
                RegistryBuilder.<TlsSocketStrategy>create()
                    .register(URIScheme.HTTPS.id, tlsSocketStrategy)
                    .build());
        }
    }

    private static final class TargetOverrideSocket extends Socket {
        private final ConnectionTargetOverride targetOverride;

        TargetOverrideSocket(ConnectionTargetOverride targetOverride) {
            this.targetOverride = targetOverride;
        }

        TargetOverrideSocket(Proxy proxy, ConnectionTargetOverride targetOverride) {
            super(proxy);
            this.targetOverride = targetOverride;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws java.io.IOException {
            InetSocketAddress validated = (InetSocketAddress) endpoint;
            super.connect(targetOverride.replace(validated), timeout);
        }
    }
}
