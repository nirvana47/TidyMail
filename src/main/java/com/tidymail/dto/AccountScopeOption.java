package com.tidymail.dto;

public record AccountScopeOption(
    String value,
    String label,
    String provider,
    boolean selected
) {
    public AccountScopeOption(String value, String label, boolean selected) {
        this(value, label, null, selected);
    }
}
