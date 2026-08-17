package com.tidymail.unsubscribe;

import java.util.ArrayList;
import java.util.List;

public final class Rfc2369Parser {
    public static final int MAX_FIELD_LENGTH = 8192;
    public static final int MAX_FIELD_VALUES = 8;
    public static final int MAX_ITEMS = 8;

    public ParseResult parse(List<String> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return new ParseResult(List.of(), false, Reason.EMPTY);
        }
        if (fieldValues.size() > MAX_FIELD_VALUES) {
            return new ParseResult(List.of(), true, Reason.TOO_MANY_FIELDS);
        }
        ArrayList<UriItem> items = new ArrayList<>();
        for (String raw : fieldValues) {
            ParseResult result = parseField(raw, items);
            if (result.malformed()) return result;
        }
        return new ParseResult(items, false, items.isEmpty() ? Reason.EMPTY : Reason.NONE);
    }

    private static ParseResult parseField(String raw, ArrayList<UriItem> items) {
        if (raw == null || raw.length() > MAX_FIELD_LENGTH) {
            return malformed(items, Reason.TOO_LONG);
        }
        String unfolded = unfold(raw);
        if (unfolded == null) {
            return malformed(items, Reason.CONTROL);
        }
        int index = 0;
        boolean requireItem = true;
        while (index < unfolded.length()) {
            index = skipWhitespaceAndComments(unfolded, index);
            if (index < 0) return malformed(items, Reason.MALFORMED_COMMENT);
            if (index == unfolded.length()) {
                return requireItem && !items.isEmpty()
                    ? malformed(items, Reason.EXPECTED_ANGLE)
                    : new ParseResult(items, false, items.isEmpty() ? Reason.EMPTY : Reason.NONE);
            }
            if (unfolded.charAt(index) != '<') {
                return malformed(items, Reason.EXPECTED_ANGLE);
            }
            int close = unfolded.indexOf('>', index + 1);
            if (close < 0 || unfolded.substring(index + 1, close).indexOf('<') >= 0) {
                return malformed(items, Reason.MALFORMED_BRACKET);
            }
            StringBuilder uri = new StringBuilder(close - index);
            for (int cursor = index + 1; cursor < close; cursor++) {
                char character = unfolded.charAt(cursor);
                if (character == ' ' || character == '\t') continue;
                if (Character.isISOControl(character)) {
                    return malformed(items, Reason.CONTROL);
                }
                uri.append(character);
            }
            if (uri.isEmpty()) return malformed(items, Reason.MALFORMED_BRACKET);
            if (items.size() == MAX_ITEMS) return malformed(items, Reason.TOO_MANY_ITEMS);
            items.add(new UriItem(uri.toString(), items.size()));
            index = skipWhitespaceAndComments(unfolded, close + 1);
            if (index < 0) return malformed(items, Reason.MALFORMED_COMMENT);
            if (index == unfolded.length()) {
                return new ParseResult(items, false, Reason.NONE);
            }
            if (unfolded.charAt(index) != ',') {
                return malformed(items, Reason.EXPECTED_ANGLE);
            }
            index++;
            requireItem = true;
        }
        return new ParseResult(items, false, items.isEmpty() ? Reason.EMPTY : Reason.NONE);
    }

    private static int skipWhitespaceAndComments(String value, int start) {
        int index = start;
        while (index < value.length()) {
            char character = value.charAt(index);
            if (character == ' ' || character == '\t') {
                index++;
                continue;
            }
            if (character != '(') return index;
            int depth = 1;
            boolean escaped = false;
            index++;
            while (index < value.length() && depth > 0) {
                character = value.charAt(index++);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '(') {
                    if (++depth > 4) return -1;
                } else if (character == ')') {
                    depth--;
                } else if (Character.isISOControl(character) && character != '\t') {
                    return -1;
                }
            }
            if (depth != 0 || escaped) return -1;
        }
        return index;
    }

    private static String unfold(String raw) {
        StringBuilder result = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '\r' || character == '\n') {
                int newlineEnd = index;
                if (character == '\r' && newlineEnd + 1 < raw.length()
                        && raw.charAt(newlineEnd + 1) == '\n') {
                    newlineEnd++;
                }
                if (newlineEnd + 1 >= raw.length()
                        || raw.charAt(newlineEnd + 1) != ' '
                            && raw.charAt(newlineEnd + 1) != '\t') {
                    return null;
                }
                result.append(' ');
                index = newlineEnd;
                while (index + 1 < raw.length()
                        && (raw.charAt(index + 1) == ' '
                            || raw.charAt(index + 1) == '\t')) {
                    index++;
                }
            } else if (Character.isISOControl(character) && character != '\t') {
                return null;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static ParseResult malformed(List<UriItem> items, Reason reason) {
        return new ParseResult(items, true, reason);
    }

    public enum Reason {
        NONE,
        EMPTY,
        TOO_LONG,
        CONTROL,
        EXPECTED_ANGLE,
        MALFORMED_COMMENT,
        MALFORMED_BRACKET,
        TOO_MANY_FIELDS,
        TOO_MANY_ITEMS
    }

    public record UriItem(String value, int preference) {
        public UriItem {
            if (value == null || value.isEmpty() || preference < 0) {
                throw new IllegalArgumentException("Invalid RFC 2369 URI item");
            }
        }
    }

    public record ParseResult(List<UriItem> items, boolean malformed, Reason reason) {
        public ParseResult {
            items = List.copyOf(items);
            if (reason == null) throw new IllegalArgumentException("Parse reason is required");
        }
    }
}
