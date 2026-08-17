package com.tidymail.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utilities for computing SHA-256 fingerprints and domain-scoped binding hashes
 * used to identify tokens, scopes, and intents without storing raw values.
 *
 * <p>Product functionality supported: the confirmation, scope, and intent
 * integrity checks across the bulk-action and Review flows.
 */
public final class ActionFingerprints {
    private ActionFingerprints() {
    }

    /** Returns the lowercase hex SHA-256 of the given string. */
    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns a domain-scoped hash binding the given values together. */
    public static String bind(String domain, Object... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, domain);
            for (Object value : values) {
                write(out, String.valueOf(value));
            }
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not bind fingerprint", impossible);
        }
    }

    /** Writes a length-prefixed value to the fingerprint buffer. */
    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    /** Returns the lowercase hex SHA-256 of the given bytes. */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
