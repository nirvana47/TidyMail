package com.tidymail.unsubscribe;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Evaluates whether an IP address is globally routable, blocking private,
 * loopback, link-local, multicast, and other special-purpose allocations.
 * <p>
 * Product functionality supported: SSRF protection in TidyMail's unsubscribe
 * destination validation.
 */
public final class PublicAddressPolicy {
    // IANA special-purpose registries, reviewed 2026-08-09. Globally reachable
    // exceptions are checked before their non-global parent allocation.
    private static final List<Cidr> GLOBAL_V4_EXCEPTIONS = List.of(
        cidr("192.0.0.9", 32),
        cidr("192.0.0.10", 32));

    private static final List<Cidr> BLOCKED_V4 = List.of(
        cidr("0.0.0.0", 8),
        cidr("10.0.0.0", 8),
        cidr("100.64.0.0", 10),
        cidr("127.0.0.0", 8),
        cidr("169.254.0.0", 16),
        cidr("172.16.0.0", 12),
        cidr("192.0.0.0", 24),
        cidr("192.0.2.0", 24),
        cidr("192.88.99.0", 24),
        cidr("192.168.0.0", 16),
        cidr("198.18.0.0", 15),
        cidr("198.51.100.0", 24),
        cidr("203.0.113.0", 24),
        cidr("224.0.0.0", 4),
        cidr("240.0.0.0", 4));

    private static final List<Cidr> GLOBAL_V6_EXCEPTIONS = List.of(
        cidr("2001:1::1", 128),
        cidr("2001:1::2", 128),
        cidr("2001:1::3", 128),
        cidr("2001:3::", 32),
        cidr("2001:4:112::", 48),
        cidr("2001:30::", 28));
    private static final Cidr GLOBAL_V6_UNICAST = cidr("2000::", 3);

    private static final List<Cidr> BLOCKED_V6 = List.of(
        cidr("::", 96),
        cidr("64:ff9b::", 96),
        cidr("64:ff9b:1::", 48),
          cidr("100::", 64),
          cidr("100:0:0:1::", 64),
        cidr("5f00::", 16),
        cidr("2001::", 23),
        cidr("2001:db8::", 32),
        cidr("2002::", 16),
        cidr("3fff::", 20),
        cidr("fc00::", 7),
        cidr("fe80::", 10),
        cidr("fec0::", 10),
        cidr("ff00::", 8));

    /**
     * Returns whether the given address is a publicly reachable unicast address.
     */
    public boolean isGlobalUnicast(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address ipv6) {
            if (ipv6.getScopeId() != 0 || isEmbeddedIpv4(address.getAddress())
                    || !GLOBAL_V6_UNICAST.contains(address.getAddress())) {
                return false;
            }
        }
        List<Cidr> blocked;
        if (address instanceof Inet4Address) {
            blocked = BLOCKED_V4;
        } else if (address instanceof Inet6Address) {
            blocked = BLOCKED_V6;
        } else {
            return false;
        }
        List<Cidr> exceptions = address instanceof Inet4Address
            ? GLOBAL_V4_EXCEPTIONS : GLOBAL_V6_EXCEPTIONS;
        return exceptions.stream().anyMatch(range -> range.contains(address.getAddress()))
            || blocked.stream().noneMatch(range -> range.contains(address.getAddress()));
    }

    private static boolean isEmbeddedIpv4(byte[] address) {
        boolean firstTenZero = true;
        for (int index = 0; index < 10; index++) {
            firstTenZero &= address[index] == 0;
        }
        boolean compatible = firstTenZero && address[10] == 0 && address[11] == 0;
        boolean mapped = firstTenZero
            && (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
        return compatible || mapped;
    }

    private static Cidr cidr(String address, int prefixLength) {
        try {
            return new Cidr(InetAddress.getByName(address).getAddress(), prefixLength);
        } catch (Exception impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private record Cidr(byte[] network, int prefixLength) {
        private Cidr {
            network = network.clone();
            if (prefixLength < 0 || prefixLength > network.length * 8) {
                throw new IllegalArgumentException("Invalid CIDR prefix");
            }
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        @Override
        public byte[] network() {
            return network.clone();
        }
    }
}
