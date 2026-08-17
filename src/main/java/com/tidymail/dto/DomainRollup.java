package com.tidymail.dto;

public record DomainRollup(
    String fromDomain,
    int countAcrossAllSenders,
    int distinctFromAddresses
) {
}
