package com.tidymail.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ActionScopeRiskSummary(
        int protectedCount,
        int highRiskCount,
        List<String> warningCodes) {

    private static final Pattern WARNING_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public ActionScopeRiskSummary {
        if (protectedCount < 0 || highRiskCount < 0) {
            throw new IllegalArgumentException("Risk counts must be non-negative");
        }
        if (protectedCount > 1000 || highRiskCount > 1000) {
            throw new IllegalArgumentException("Risk counts must not exceed 1000");
        }
        warningCodes = warningCodes == null ? List.of() : List.copyOf(warningCodes);
        if (warningCodes.size() > 16) {
            throw new IllegalArgumentException("At most 16 warning codes are allowed");
        }
        if (warningCodes.stream().anyMatch(code ->
                code == null || !WARNING_CODE.matcher(code).matches())) {
            throw new IllegalArgumentException("Warning codes must be uppercase enum-like values");
        }
    }

    @Override
    public List<String> warningCodes() {
        return List.copyOf(warningCodes);
    }
}
