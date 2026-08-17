package com.tidymail.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SenderCategoryNameSanitizer {
    public static final int MAX_LENGTH = 80;

    private static final Pattern MARKUP_OR_SCRIPT = Pattern.compile(
        "(?i)[<>]|&(?:#\\d+|#x[0-9a-f]+|[a-z][a-z0-9]+);"
            + "|\\b(?:javascript|vbscript|data)\\s*:"
            + "|\\bon[a-z]+\\s*=");

    public SanitizedName sanitize(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("Category name is required");
        }
        String name = rawName.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Category name is required");
        }
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "Category name must be " + MAX_LENGTH + " characters or fewer");
        }
        if (name.codePoints().anyMatch(SenderCategoryNameSanitizer::isUnsafeControl)) {
            throw new IllegalArgumentException("Category name contains control characters");
        }
        if (MARKUP_OR_SCRIPT.matcher(name).find()) {
            throw new IllegalArgumentException(
                "Category name must be plain text without HTML or script syntax");
        }
        return new SanitizedName(name, name.toLowerCase(Locale.ROOT));
    }

    private static boolean isUnsafeControl(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
            || type == Character.FORMAT
            || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR;
    }

    public record SanitizedName(String value, String key) {
    }
}
