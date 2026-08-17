package com.tidymail.service;

import com.tidymail.security.crypto.CryptoProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues and verifies short-lived, HMAC-signed intents for review operations
 * (cancel, approve, undo, resolve conflict), binding the owner, operation, and
 * subject to prevent stale or replayed actions.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's operation
 * confirmation and safety checks.
 */
@Service
public class ReviewOperationIntentService {
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final Set<String> OPERATIONS = Set.of(
        "CANCEL", "APPROVE_SELECTED", "APPROVE_ALL", "UNDO_SELECTED",
        "RESOLVE_CONFLICT");
    private final byte[] signingKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ReviewOperationIntentService(CryptoProperties properties) {
        this.signingKey = properties.getSecretKey().getEncoded().clone();
        this.clock = Clock.systemUTC();
    }

    /** Issues a signed intent token for the operation and subject. */
    public String issue(String ownerKey, String operation, String subjectFingerprint) {
        requireOperation(operation);
        requireFingerprint(subjectFingerprint);
        Instant issued = clock.instant();
        byte[] nonce = new byte[24];
        random.nextBytes(nonce);
        String payload = String.join("|",
            "1",
            ActionFingerprints.sha256(ownerKey),
            operation,
            subjectFingerprint,
            String.valueOf(issued.getEpochSecond()),
            String.valueOf(issued.plus(LIFETIME).getEpochSecond()),
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        byte[] encoded = payload.getBytes(StandardCharsets.US_ASCII);
        return base64(encoded) + "." + base64(sign(encoded));
    }

    /** Verifies an intent token's signature, owner, operation, and freshness. */
    public Payload verify(
            String ownerKey, String token, String operation, String subjectFingerprint) {
        requireOperation(operation);
        requireFingerprint(subjectFingerprint);
        if (token == null || token.length() > 2048 || token.contains(" ")) throw stale();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw stale();
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!base64(encoded).equals(parts[0])
                    || !base64(signature).equals(parts[1])
                    || !MessageDigest.isEqual(sign(encoded), signature)) {
                throw stale();
            }
            String[] fields = new String(encoded, StandardCharsets.US_ASCII)
                .split("\\|", -1);
            if (fields.length != 7 || !"1".equals(fields[0])
                    || !MessageDigest.isEqual(
                        fields[1].getBytes(StandardCharsets.US_ASCII),
                        ActionFingerprints.sha256(ownerKey)
                            .getBytes(StandardCharsets.US_ASCII))
                    || !operation.equals(fields[2])
                    || !MessageDigest.isEqual(
                        fields[3].getBytes(StandardCharsets.US_ASCII),
                        subjectFingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw stale();
            }
            Instant issued = Instant.ofEpochSecond(Long.parseLong(fields[4]));
            Instant expires = Instant.ofEpochSecond(Long.parseLong(fields[5]));
            Instant now = clock.instant();
            if (now.isBefore(issued) || !now.isBefore(expires)
                    || expires.isAfter(issued.plus(Duration.ofMinutes(10)))) {
                throw stale();
            }
            return new Payload(fields[1], operation, subjectFingerprint, fields[6]);
        } catch (ResponseStatusException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw stale();
        }
    }

    /** Computes the subject fingerprint binding the given values. */
    public static String subject(Object... values) {
        return ActionFingerprints.bind("review-operation-subject-v1", values);
    }

    /** Computes the HMAC-SHA256 signature of a payload. */
    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            mac.update("tidymail/review-operation/v1".getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(payload);
        } catch (Exception unavailable) {
            throw new IllegalStateException("Review intent signing unavailable", unavailable);
        }
    }

    /** URL-safe base64-encodes a value without padding. */
    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** Requires a recognized operation identifier. */
    private static void requireOperation(String operation) {
        if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("operation");
    }

    /** Requires a valid 64-hex subject fingerprint. */
    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("subject");
        }
    }

    /** Throws a conflict when the intent is stale or invalid. */
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "review_intent_stale");
    }

    /** The verified contents of an intent token. */
    public record Payload(
        String ownerFingerprint,
        String operation,
        String subjectFingerprint,
        String nonce
    ) {
    }
}
