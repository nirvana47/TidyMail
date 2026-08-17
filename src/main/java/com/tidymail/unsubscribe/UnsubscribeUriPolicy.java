package com.tidymail.unsubscribe;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Validates unsubscribe URIs (HTTPS or mailto) against strict hostname, port,
 * encoding, and redaction rules, producing canonical targets and fingerprints.
 * <p>
 * Product functionality supported: destination validation with redaction for
 * TidyMail's unsubscribe flow.
 */
public final class UnsubscribeUriPolicy {
    public static final int MAX_URI_LENGTH = 4096;
    public static final int MAX_HOST_LENGTH = 253;
    public static final int MAX_PATH_LENGTH = 2048;
    public static final int MAX_QUERY_LENGTH = 2048;
    public static final int MAX_MAILTO_RECIPIENT_LENGTH = 320;

    /**
     * Validates the raw URI, returning an invalid result with a reason or a
     * valid redacted/canonical target.
     */
    public Result validate(String raw) {
        if (raw == null || raw.isEmpty()) return Result.invalid(Reason.EMPTY);
        if (raw.length() > MAX_URI_LENGTH) return Result.invalid(Reason.TOO_LONG);
        if (!Normalizer.isNormalized(raw, Normalizer.Form.NFC)) {
            return Result.invalid(Reason.UNICODE_AMBIGUITY);
        }
        if (raw.indexOf('\\') >= 0 || hasControl(raw)) {
            return Result.invalid(Reason.CONTROL_OR_BACKSLASH);
        }
        Reason percentReason = validatePercentEncoding(raw);
        if (percentReason != Reason.NONE) return Result.invalid(percentReason);

        final URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException invalid) {
            return Result.invalid(Reason.MALFORMED_URI);
        }
        if (!uri.isAbsolute()) return Result.invalid(Reason.NOT_ABSOLUTE);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return validateHttps(raw, uri);
        }
        if ("mailto".equalsIgnoreCase(uri.getScheme())) {
            return validateMailto(raw, uri);
        }
        return Result.invalid(Reason.UNSUPPORTED_SCHEME);
    }

    private static Result validateHttps(String raw, URI uri) {
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return Result.invalid(Reason.FORBIDDEN_AUTHORITY);
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Result.invalid(Reason.NON_DEFAULT_PORT);
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || authority.indexOf('%') >= 0
                || authority.indexOf('@') >= 0 || authority.indexOf('[') >= 0
                || authority.indexOf(']') >= 0) {
            return Result.invalid(Reason.FORBIDDEN_AUTHORITY);
        }
        String hostAuthority = authority;
        int portSeparator = authority.lastIndexOf(':');
        if (portSeparator >= 0) {
            if (!authority.substring(portSeparator + 1).equals("443")
                    || authority.indexOf(':') != portSeparator) {
                return Result.invalid(Reason.FORBIDDEN_AUTHORITY);
            }
            hostAuthority = authority.substring(0, portSeparator);
        }
        String host;
        try {
            host = IDN.toASCII(hostAuthority, IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return Result.invalid(Reason.INVALID_HOST);
        }
        if (host.endsWith(".") || host.isEmpty() || host.length() > MAX_HOST_LENGTH
                || host.indexOf('.') < 0 || isNumericHost(host)
                || !hasValidLabels(host) || hasInvalidAceLabel(host)) {
            return Result.invalid(Reason.INVALID_HOST);
        }
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        if (path != null && path.length() > MAX_PATH_LENGTH
                || query != null && query.length() > MAX_QUERY_LENGTH) {
            return Result.invalid(Reason.TOO_LONG);
        }
        try {
            URI canonical = new URI(
                "https", null, host, uri.getPort(), path, query, null);
            return Result.valid(new ValidatedTarget(
                TargetKind.HTTPS,
                uri,
                canonical,
                host,
                pathShape(path),
                query != null,
                fingerprint(raw),
                null,
                false,
                false));
        } catch (URISyntaxException invalid) {
            return Result.invalid(Reason.MALFORMED_URI);
        }
    }

    private static Result validateMailto(String raw, URI uri) {
        if (uri.getRawFragment() != null || uri.getRawAuthority() != null) {
            return Result.invalid(Reason.FORBIDDEN_AUTHORITY);
        }
        String schemeSpecific = uri.getRawSchemeSpecificPart();
        int querySeparator = schemeSpecific.indexOf('?');
        String recipient = querySeparator < 0
            ? schemeSpecific : schemeSpecific.substring(0, querySeparator);
        if (recipient.isEmpty() || recipient.length() > MAX_MAILTO_RECIPIENT_LENGTH
                || recipient.indexOf(',') >= 0 || recipient.indexOf(';') >= 0
                || !isStrictEmailAddress(recipient)) {
            return Result.invalid(Reason.INVALID_MAILTO);
        }
        boolean subject = false;
        boolean body = false;
        if (querySeparator >= 0) {
            String query = schemeSpecific.substring(querySeparator + 1);
            if (query.length() > MAX_QUERY_LENGTH || query.isEmpty()) {
                return Result.invalid(Reason.INVALID_MAILTO);
            }
            Map<String, String> parameters = new LinkedHashMap<>();
            for (String pair : query.split("&", -1)) {
                int equals = pair.indexOf('=');
                if (equals <= 0) return Result.invalid(Reason.INVALID_MAILTO);
                String name = pair.substring(0, equals).toLowerCase(Locale.ROOT);
                if (!name.equals("subject") && !name.equals("body")
                        || parameters.putIfAbsent(name, pair.substring(equals + 1)) != null) {
                    return Result.invalid(Reason.INVALID_MAILTO);
                }
            }
            subject = parameters.containsKey("subject");
            body = parameters.containsKey("body");
        }
        return Result.valid(new ValidatedTarget(
            TargetKind.MAILTO,
            uri,
            uri,
            null,
            "Mail recipient hidden",
            querySeparator >= 0,
            fingerprint(raw),
            recipient,
            subject,
            body));
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean isStrictEmailAddress(String value) {
        int separator = value.indexOf('@');
        if (separator <= 0 || separator != value.lastIndexOf('@')
                || separator > 64 || value.length() - separator - 1 > 253) {
            return false;
        }
        String local = value.substring(0, separator);
        String domain = value.substring(separator + 1);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || local.chars().anyMatch(character ->
                    Character.isWhitespace(character)
                        || character == ':' || character == '/' || character == '\\')) {
            return false;
        }
        try {
            String asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES);
            return asciiDomain.length() <= 253
                && asciiDomain.indexOf('.') > 0
                && !asciiDomain.endsWith(".")
                && hasValidLabels(asciiDomain);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Reason validatePercentEncoding(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '%') continue;
            int runStart = index;
            byte[] bytes = new byte[(value.length() - index) / 3 + 1];
            int count = 0;
            while (index < value.length() && value.charAt(index) == '%') {
                if (index + 2 >= value.length()
                        || Character.digit(value.charAt(index + 1), 16) < 0
                        || Character.digit(value.charAt(index + 2), 16) < 0) {
                    return Reason.MALFORMED_PERCENT;
                }
                int octet = Character.digit(value.charAt(index + 1), 16) * 16
                    + Character.digit(value.charAt(index + 2), 16);
                if (octet <= 0x1f || octet == 0x7f || octet == '\\') {
                    return Reason.ENCODED_CONTROL_OR_BACKSLASH;
                }
                bytes[count++] = (byte) octet;
                index += 3;
            }
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, count));
            } catch (CharacterCodingException malformed) {
                return Reason.UNICODE_AMBIGUITY;
            }
            index--;
            if (index < runStart) throw new IllegalStateException("Percent parser made no progress");
        }
        return Reason.NONE;
    }

    private static boolean isNumericHost(String host) {
        String[] labels = host.split("\\.", -1);
        return java.util.Arrays.stream(labels).allMatch(UnsubscribeUriPolicy::isNumericLabel);
    }

    private static boolean isNumericLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.matches("[0-9]+") || lower.matches("0x[0-9a-f]+");
    }

    private static boolean hasValidLabels(String host) {
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasInvalidAceLabel(String host) {
        for (String label : host.split("\\.")) {
            if (!label.startsWith("xn--")) continue;
            String unicode = IDN.toUnicode(label);
            try {
                if (unicode.equals(label)
                        || !IDN.toASCII(unicode, IDN.USE_STD3_ASCII_RULES)
                            .equalsIgnoreCase(label)) {
                    return true;
                }
            } catch (IllegalArgumentException invalid) {
                return true;
            }
        }
        return false;
    }

    private static String pathShape(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) return "/";
        long segments = java.util.Arrays.stream(rawPath.split("/"))
            .filter(segment -> !segment.isEmpty())
            .count();
        return "/... (" + segments + (segments == 1 ? " segment)" : " segments)");
    }

    private static String fingerprint(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum TargetKind { HTTPS, MAILTO }

    public enum Reason {
        NONE,
        EMPTY,
        TOO_LONG,
        UNICODE_AMBIGUITY,
        CONTROL_OR_BACKSLASH,
        ENCODED_CONTROL_OR_BACKSLASH,
        MALFORMED_PERCENT,
        MALFORMED_URI,
        NOT_ABSOLUTE,
        UNSUPPORTED_SCHEME,
        FORBIDDEN_AUTHORITY,
        NON_DEFAULT_PORT,
        INVALID_HOST,
        INVALID_MAILTO
    }

    public record ValidatedTarget(
            TargetKind kind,
            URI originalUri,
            URI canonicalUri,
            String host,
            String redactedPathShape,
            boolean queryPresent,
            String fingerprint,
            String mailtoRecipient,
            boolean mailtoSubjectPresent,
            boolean mailtoBodyPresent) {

        @Override
        public String toString() {
            return "ValidatedTarget[kind=" + kind
                + ", host=" + host
                + ", redactedPathShape=" + redactedPathShape
                + ", queryPresent=" + queryPresent
                + ", fingerprint=" + fingerprint + "]";
        }
    }

    public record Result(ValidatedTarget target, Reason reason) {
        public static Result valid(ValidatedTarget target) {
            return new Result(target, Reason.NONE);
        }

        public static Result invalid(Reason reason) {
            return new Result(null, reason);
        }

        public boolean valid() {
            return target != null;
        }
    }
}
