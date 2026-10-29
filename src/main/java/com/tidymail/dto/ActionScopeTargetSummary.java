package com.tidymail.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Describes what a staged action scope targets: the sender display label,
 * address, domain, and how many targets were selected.
 *
 * <p>Product functionality supported: the <em>Bulk Actions</em> preview and
 * Review UI. Bounds and trims string fields and validates nonnegative counts so the
 * values are safe to display and persist.</p>
 */
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
        if (selectedTargetCount < 0) {
            throw new IllegalArgumentException("selectedTargetCount must be non-negative");
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
