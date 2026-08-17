package com.tidymail.unsubscribe;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the unsubscribe transport, bounded to safe timeouts, DNS,
 * and response limits, with no redirects or automatic retries.
 * <p>
 * Product functionality supported: safety bounds for TidyMail's automated
 * unsubscribe transport.
 */
@ConfigurationProperties("app.unsubscribe.transport")
public record UnsubscribeTransportProperties(
        @DefaultValue("2s") Duration dnsTimeout,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("3s") Duration tlsHandshakeTimeout,
        @DefaultValue("5s") Duration responseHeaderTimeout,
        @DefaultValue("10s") Duration totalTimeout,
        @DefaultValue("8") int maxDnsAnswers,
        @DefaultValue("8192") int maxResponseBytes,
        @DefaultValue("0") int redirectHops,
        @DefaultValue("0") int automaticRetries) {

    public UnsubscribeTransportProperties {
        requireBounded(dnsTimeout, "dnsTimeout");
        requireBounded(connectTimeout, "connectTimeout");
        requireBounded(tlsHandshakeTimeout, "tlsHandshakeTimeout");
        requireBounded(responseHeaderTimeout, "responseHeaderTimeout");
        requireBounded(totalTimeout, "totalTimeout");
        if (totalTimeout.compareTo(dnsTimeout) <= 0
                  || maxDnsAnswers < 1 || maxDnsAnswers > 8
                  || maxResponseBytes < 1 || maxResponseBytes > 8192
                || redirectHops != 0 || automaticRetries != 0) {
            throw new IllegalArgumentException("Unsafe unsubscribe transport configuration");
        }
    }

    private static void requireBounded(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(name + " must be between 1ns and 30s");
        }
    }
}
