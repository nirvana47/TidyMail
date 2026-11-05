package com.tidymail.mail;

import java.nio.charset.StandardCharsets;

/** Bounded preview text, kept separate from a full message body. */
public record EmailSnippet(String text, String state) {
    public static EmailSnippet unknown() {
        return new EmailSnippet(null, "UNKNOWN");
    }

    public static EmailSnippet observed(String value) {
        if (value == null || value.isBlank())
            return new EmailSnippet(null, "ABSENT");
        String bounded = bound(value, 1024);
        return new EmailSnippet(bounded, bounded.equals(value) ? "AVAILABLE" : "TRUNCATED");
    }

    public static String bound(String value, int bytes) {
        if (value == null)
            return null;
        int end = 0, used = 0;
        while (end < value.length()) {
            int cp = value.codePointAt(end);
            int length = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + length > bytes)
                break;
            used += length;
            end += Character.charCount(cp);
        }
        return value.substring(0, end).replace("\u0000", "");
    }
}
