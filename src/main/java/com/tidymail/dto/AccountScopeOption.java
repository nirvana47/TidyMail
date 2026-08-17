package com.tidymail.dto;

/**
 * A single selectable account option for the workspace account switcher.
 *
 * <p>Product functionality supported: the <em>account switcher</em> in the
 * workspace chrome. Carries the account's value/label, the mail provider it
 * belongs to, and whether it is currently selected.</p>
 */
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
