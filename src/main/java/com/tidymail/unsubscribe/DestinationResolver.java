package com.tidymail.unsubscribe;

import jakarta.annotation.PreDestroy;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class DestinationResolver implements AutoCloseable {
    private static final int MAX_HOST_LENGTH = 253;
    private static final List<String> FORBIDDEN_SUFFIXES = List.of(
        "localhost", "local", "localdomain", "internal", "home.arpa");

    private final DnsLookup lookup;
    private final PublicAddressPolicy addressPolicy;
    private final Duration timeout;
    private final int maxAnswers;
    private final ThreadPoolExecutor executor;

    @Autowired
    public DestinationResolver(UnsubscribeTransportProperties properties) {
        this(InetAddress::getAllByName, new PublicAddressPolicy(),
            properties.dnsTimeout(), properties.maxDnsAnswers());
    }

    public DestinationResolver(
            DnsLookup lookup,
            PublicAddressPolicy addressPolicy,
            Duration timeout,
            int maxAnswers) {
        this.lookup = lookup;
        this.addressPolicy = addressPolicy;
        this.timeout = timeout;
        this.maxAnswers = maxAnswers;
        this.executor = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "unsubscribe-dns");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    public ResolvedDestination resolveAndValidate(String hostname) {
        String normalized = normalizeHostname(hostname);
        final Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> lookup.resolve(normalized));
        } catch (RuntimeException saturated) {
            throw ResolutionException.of(Reason.DNS_UNAVAILABLE);
        }

        final InetAddress[] raw;
        try {
            raw = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw ResolutionException.of(Reason.DNS_TIMEOUT);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ResolutionException.of(Reason.DNS_UNAVAILABLE);
        } catch (ExecutionException lookupFailure) {
            throw ResolutionException.of(Reason.DNS_UNAVAILABLE);
        }

        Map<AddressKey, InetAddress> unique = new LinkedHashMap<>();
        if (raw != null) {
            for (InetAddress answer : raw) {
                if (answer != null) unique.putIfAbsent(new AddressKey(answer), answer);
            }
        }
        if (unique.isEmpty()) throw ResolutionException.of(Reason.NO_ADDRESSES);
        if (unique.size() > maxAnswers) {
            throw ResolutionException.of(Reason.TOO_MANY_ADDRESSES);
        }
        if (unique.values().stream().anyMatch(answer -> !addressPolicy.isGlobalUnicast(answer))) {
            throw ResolutionException.of(Reason.NON_PUBLIC_ADDRESS);
        }
        return new ResolvedDestination(normalized, List.copyOf(unique.values()));
    }

    static String normalizeHostname(String hostname) {
        if (hostname == null || hostname.isBlank() || hostname.endsWith(".")
                || hostname.indexOf('/') >= 0 || hostname.indexOf('\\') >= 0
                || hostname.chars().anyMatch(Character::isISOControl)) {
            throw ResolutionException.of(Reason.INVALID_HOSTNAME);
        }
        final String ascii;
        try {
            ascii = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            throw ResolutionException.of(Reason.INVALID_HOSTNAME);
        }
        if (ascii.length() > MAX_HOST_LENGTH || ascii.indexOf('.') < 0
                || isNumericHostname(ascii) || isForbiddenName(ascii)
                || hasInvalidAceLabel(ascii)) {
            throw ResolutionException.of(Reason.INVALID_HOSTNAME);
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw ResolutionException.of(Reason.INVALID_HOSTNAME);
            }
        }
        return ascii;
    }

    private static boolean isNumericHostname(String hostname) {
        return Arrays.stream(hostname.split("\\.", -1))
            .allMatch(DestinationResolver::isNumericLabel);
    }

    private static boolean isNumericLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.matches("[0-9]+") || lower.matches("0x[0-9a-f]+");
    }

    private static boolean isForbiddenName(String hostname) {
        return FORBIDDEN_SUFFIXES.stream()
            .anyMatch(suffix -> hostname.equals(suffix) || hostname.endsWith("." + suffix));
    }

    private static boolean hasInvalidAceLabel(String hostname) {
        for (String label : hostname.split("\\.")) {
            if (label.startsWith("xn--")) {
                String unicode = IDN.toUnicode(label);
                try {
                    if (unicode.equals(label)
                            || !IDN.toASCII(unicode, IDN.USE_STD3_ASCII_RULES)
                                .equalsIgnoreCase(label)) {
                        return true;
                    }
                } catch (IllegalArgumentException invalid) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface DnsLookup {
        InetAddress[] resolve(String hostname) throws UnknownHostException;
    }

    public enum Reason {
        INVALID_HOSTNAME,
        DNS_TIMEOUT,
        DNS_UNAVAILABLE,
        NO_ADDRESSES,
        TOO_MANY_ADDRESSES,
        NON_PUBLIC_ADDRESS
    }

    public static final class ResolutionException extends RuntimeException {
        private final Reason reason;

        private ResolutionException(Reason reason) {
            super("Destination resolution failed");
            this.reason = reason;
        }

        static ResolutionException of(Reason reason) {
            return new ResolutionException(reason);
        }

        public Reason reason() {
            return reason;
        }
    }

    private record AddressKey(byte[] bytes) {
        AddressKey(InetAddress address) {
            this(address.getAddress());
        }

        private AddressKey {
            bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AddressKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
