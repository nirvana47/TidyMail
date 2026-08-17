package com.tidymail.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Produces a canonical, deterministic representation of an exact action scope
 * (account, action type, target kind, and sorted message IDs) and its SHA-256
 * hash, so identical scopes can be deduplicated and verified.
 *
 * <p>Product functionality supported: the <em>Bulk Action Preview</em> flow's
 * exact-scope materialization and integrity checks.
 */
@Component
public class ActionScopeHasher {

    static final String CANONICAL_VERSION = "scope-v1";

    /** The canonicalized scope and its computed hash. */
    public record CanonicalScope(
            String actionType,
            String targetKind,
            List<String> messageIds,
            byte[] canonicalBytes,
            String scopeHash) {

        public CanonicalScope {
            messageIds = List.copyOf(messageIds);
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }

    /** Canonicalizes a scope and returns its canonical form and hash. */
    public CanonicalScope canonicalize(
            Long accountId, String actionType, String targetKind, Collection<String> messageIds) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        String normalizedAction = normalize(actionType, "actionType");
        String normalizedTarget = normalize(targetKind, "targetKind");
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("At least one message ID is required");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String messageId : messageIds) {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("Message IDs must not be null or blank");
            }
            sorted.add(messageId);
        }
        List<String> canonicalIds = List.copyOf(sorted);
        byte[] bytes = canonicalBytes(
            CANONICAL_VERSION, Long.toString(accountId), normalizedAction, normalizedTarget,
            canonicalIds);
        return new CanonicalScope(
            normalizedAction, normalizedTarget, canonicalIds, bytes, sha256(bytes));
    }

    /** Normalizes an enum-like value, rejecting malformed input. */
    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(field + " must be an enum-like value");
        }
        return normalized;
    }

    /** Serializes the canonical scope to its deterministic byte form. */
    private static byte[] canonicalBytes(
            String version,
            String accountId,
            String actionType,
            String targetKind,
            List<String> messageIds) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeValue(output, version);
            writeValue(output, accountId);
            writeValue(output, actionType);
            writeValue(output, targetKind);
            output.writeInt(messageIds.size());
            for (String messageId : messageIds) {
                writeValue(output, messageId);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not build canonical scope", exception);
        }
    }

    /** Writes a length-prefixed value to the canonical buffer. */
    private static void writeValue(DataOutputStream output, String value) throws IOException {
        byte[] encoded = encodeUtf8(value);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    /** UTF-8 encodes a value, rejecting malformed Unicode. */
    private static byte[] encodeUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "Canonical scope values must contain valid Unicode", exception);
        }
    }

    /** Returns the lowercase hex SHA-256 of the given bytes. */
    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
