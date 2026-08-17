package com.tidymail.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class CursorUtil {

    private CursorUtil() {
    }

    public static String encode(String googlePageToken, long localOffset, String localSort) {
        String token = googlePageToken == null ? "" : googlePageToken;
        String concat = token + "|" + localOffset + "|" + localSort;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(concat.getBytes(StandardCharsets.UTF_8));
    }

    public static record CursorParts(String googlePageToken, long localOffset, String localSort) {}

    public static CursorParts decode(String encoded) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String str = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = str.split("\\|");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid cursor format: expected 3 parts");
            }
            long offset = Long.parseLong(parts[1]);
            return new CursorParts(parts[0], offset, parts[2]);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor: " + e.getMessage(), e);
        }
    }
}
