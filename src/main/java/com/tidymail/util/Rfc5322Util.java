package com.tidymail.util;

import java.util.Locale;

public final class Rfc5322Util {

    private Rfc5322Util() {
    }

    public static String extractAddrSpec(CharSequence rfc5322Address) {
        if (rfc5322Address == null) return null;
        String s = rfc5322Address.toString();

        StringBuilder stripped = new StringBuilder();
        int parenDepth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--;
            } else if (parenDepth == 0) {
                stripped.append(c);
            }
        }
        String cleaned = stripped.toString();

        String inner;
        int lt = cleaned.lastIndexOf('<');
        if (lt >= 0) {
            int gt = cleaned.lastIndexOf('>');
            if (gt <= lt) return null;
            inner = cleaned.substring(lt + 1, gt).trim();
        } else {
            inner = cleaned.trim();
        }

        int at = inner.lastIndexOf('@');
        if (at <= 0 || at == inner.length() - 1) return null;

        return inner.toLowerCase(Locale.ROOT);
    }

    public static String extractDomain(CharSequence rfc5322Address) {
        if (rfc5322Address == null) return null;
        String s = rfc5322Address.toString();
        int lt = s.lastIndexOf('<');
        int at;
        if (lt >= 0) {
            int gt = s.lastIndexOf('>');
            if (gt <= lt) return null;
            String inner = s.substring(lt + 1, gt).trim();
            at = inner.lastIndexOf('@');
            if (at <= 0 || at == inner.length() - 1) return null;
            return lowerOrNull(inner.substring(at + 1).trim());
        } else {
            at = s.lastIndexOf('@');
            if (at <= 0 || at == s.length() - 1) return null;
            return lowerOrNull(s.substring(at + 1).trim());
        }
    }

    private static String lowerOrNull(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }
}
