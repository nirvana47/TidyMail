package com.tidymail.unsubscribe;

import java.net.InetAddress;
import java.util.List;

/**
 * An immutable, validated unsubscribe destination: an ASCII hostname with its
 * resolved public addresses.
 * <p>
 * Product functionality supported: destination validation for TidyMail's
 * automated unsubscribe flow.
 */
public record ResolvedDestination(String asciiHostname, List<InetAddress> addresses) {
    public ResolvedDestination {
        if (asciiHostname == null || asciiHostname.isBlank()
                || addresses == null || addresses.isEmpty() || addresses.size() > 8) {
            throw new IllegalArgumentException("Resolved destination is incomplete");
        }
        addresses = List.copyOf(addresses);
        try {
            PublicAddressPolicy addressPolicy = new PublicAddressPolicy();
            if (!asciiHostname.equals(DestinationResolver.normalizeHostname(asciiHostname))
                    || addresses.stream().anyMatch(
                        address -> !addressPolicy.isGlobalUnicast(address))) {
                throw new IllegalArgumentException("Resolved destination is invalid");
            }
        } catch (DestinationResolver.ResolutionException invalid) {
            throw new IllegalArgumentException("Resolved destination is invalid");
        }
    }

    @Override
    public String toString() {
        return "ResolvedDestination[asciiHostname=" + asciiHostname
            + ", addressCount=" + addresses.size() + "]";
    }
}
