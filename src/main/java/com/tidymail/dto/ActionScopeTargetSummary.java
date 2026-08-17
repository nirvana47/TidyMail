package com.tidymail.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ActionScopeTargetSummary(
        String displayLabel,
        String senderAddress,
        String senderDomain,
        int selectedTargetCount) {

    public ActionScopeTargetSummary {
        displayLabel = bounded(displayLabel, 512, "displayLabel");
        senderAddress = bounded(senderAddress, 320, "senderAddress");
        senderDomain = bounded(senderDomain, 253, "senderDomain");
        if (selectedTargetCount < 0 || selectedTargetCount > 1000) {
            throw new IllegalArgumentException("selectedTargetCount must be between 0 and 1000");
        }
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return normalized;
    }
}
