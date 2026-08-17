package com.tidymail.dto;

/**
 * A per-domain rollup of senders, used by the Senders dashboard.
 *
 * <p>Product functionality supported: the <em>Senders</em> page's domain
 * rollups, showing total messages and distinct senders per domain.</p>
 */
public record DomainRollup(
    String fromDomain,
    int countAcrossAllSenders,
    int distinctFromAddresses
) {
}
