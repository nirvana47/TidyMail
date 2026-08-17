package com.tidymail.service;

import com.tidymail.dto.AnalyticsSenderScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues and resolves short-lived, encrypted drilldown tokens that let the
 * Analytics page link a top sender to a pre-filtered Senders view without exposing
 * the sender key or scope in the URL.
 *
 * <p>Product functionality supported: the <em>Analytics</em> flow's sender
 * drilldown links.
 */
@Service
public class AnalyticsDrilldownKeyService {
    private static final String PREFIX = "v1.";
    private static final byte[] AAD =
        "TidyMail analytics sender drilldown v1".getBytes(StandardCharsets.US_ASCII);
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final int NONCE_BYTES = 12;
    private static final int MAX_TOKEN_LENGTH = 1024;

    private final SecretKeySpec key;
    private final Clock clock;
    private final SecureRandom random;

    public AnalyticsDrilldownKeyService() {
        this(randomKey(), Clock.systemUTC(), new SecureRandom());
    }

    public AnalyticsDrilldownKeyService(byte[] key, Clock clock, SecureRandom random) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Analytics drilldown key must be 256 bits");
        }
        this.key = new SecretKeySpec(Arrays.copyOf(key, key.length), "AES");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    /** Issues an encrypted drilldown token for the given sender scope. */
    public String issue(
            OAuth2AuthenticationToken authentication,
            AnalyticsSenderScope scope) {
        try {
            Instant expiry = clock.instant().plus(LIFETIME);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(payload)) {
                out.writeByte(1);
                out.write(ownerFingerprint(authentication));
                out.writeLong(scope.accountId());
                out.writeLong(scope.startInclusive().getEpochSecond());
                out.writeInt(scope.startInclusive().getNano());
                out.writeLong(scope.endExclusive().getEpochSecond());
                out.writeInt(scope.endExclusive().getNano());
                out.writeLong(expiry.getEpochSecond());
                writeBounded(out, scope.senderKey());
            }
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(payload.toByteArray());
            byte[] token = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, token, 0, nonce.length);
            System.arraycopy(ciphertext, 0, token, nonce.length, ciphertext.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    /** Resolves and validates a drilldown token back to its sender scope. */
    public AnalyticsSenderScope resolve(
            OAuth2AuthenticationToken authentication, String token) {
        try {
            if (token == null || !token.startsWith(PREFIX)
                    || token.length() > MAX_TOKEN_LENGTH) {
                throw invalid();
            }
            byte[] encoded = Base64.getUrlDecoder().decode(token.substring(PREFIX.length()));
            if (encoded.length <= NONCE_BYTES + 16) throw invalid();
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
                    .equals(token.substring(PREFIX.length()))) {
                throw invalid();
            }
            byte[] nonce = Arrays.copyOfRange(encoded, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(encoded, NONCE_BYTES, encoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] plaintext = cipher.doFinal(ciphertext);
            try (DataInputStream in =
                    new DataInputStream(new ByteArrayInputStream(plaintext))) {
                if (in.readUnsignedByte() != 1) throw invalid();
                byte[] owner = in.readNBytes(32);
                if (owner.length != 32
                        || !MessageDigest.isEqual(owner, ownerFingerprint(authentication))) {
                    throw invalid();
                }
                long accountId = in.readLong();
                Instant start = Instant.ofEpochSecond(in.readLong(), in.readInt());
                Instant end = Instant.ofEpochSecond(in.readLong(), in.readInt());
                Instant expiry = Instant.ofEpochSecond(in.readLong());
                String senderKey = readBounded(in);
                if (in.available() != 0 || !clock.instant().isBefore(expiry)
                        || Duration.between(start, end).isNegative()
                        || Duration.between(start, end).compareTo(Duration.ofDays(367)) > 0) {
                    throw invalid();
                }
                AnalyticsSenderScope scope =
                    new AnalyticsSenderScope(accountId, senderKey, start, end);
                if (!issuePayloadCanonical(scope, owner, expiry, plaintext)) throw invalid();
                return scope;
            }
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    /** Verifies the decrypted payload matches the canonical encoding. */
    private static boolean issuePayloadCanonical(
            AnalyticsSenderScope scope, byte[] owner, Instant expiry, byte[] original)
            throws Exception {
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(canonical)) {
            out.writeByte(1);
            out.write(owner);
            out.writeLong(scope.accountId());
            out.writeLong(scope.startInclusive().getEpochSecond());
            out.writeInt(scope.startInclusive().getNano());
            out.writeLong(scope.endExclusive().getEpochSecond());
            out.writeInt(scope.endExclusive().getNano());
            out.writeLong(expiry.getEpochSecond());
            writeBounded(out, scope.senderKey());
        }
        return MessageDigest.isEqual(canonical.toByteArray(), original);
    }

    /** Computes the owner fingerprint bound into the token. */
    private static byte[] ownerFingerprint(OAuth2AuthenticationToken authentication)
            throws Exception {
        String email = authentication == null
            ? null : authentication.getPrincipal().getAttribute("email");
        if (email == null || email.isBlank()) throw invalid();
        String owner = authentication.getAuthorizedClientRegistrationId()
            + "\n" + email.trim().toLowerCase(java.util.Locale.ROOT);
        return MessageDigest.getInstance("SHA-256")
            .digest(owner.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a bounded length-prefixed string to the payload. */
    private static void writeBounded(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 640) throw invalid();
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /** Reads a bounded length-prefixed string from the payload. */
    private static String readBounded(DataInputStream in) throws Exception {
        int length = in.readUnsignedShort();
        if (length == 0 || length > 640) throw invalid();
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw invalid();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Generates a random 256-bit encryption key. */
    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** Returns the exception for an invalid or expired drilldown token. */
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Analytics sender drilldown is unavailable");
    }
}
