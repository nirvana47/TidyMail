package com.tidymail.unsubscribe;

import com.tidymail.entity.UnsubscribeIntentNonce;
import com.tidymail.repository.UnsubscribeIntentNonceRepository;
import com.tidymail.security.crypto.CryptoProperties;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.service.UnsubscribeCandidateService.ExecutionCandidate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues and consumes short-lived, signed unsubscribe intent tokens bound to a
 * specific candidate, destination, and method for verification at redemption.
 * <p>
 * Product functionality supported: the signed intent/callback flow in
 * TidyMail's unsubscribe cleanup.
 */
@Service
public class UnsubscribeIntentService {
    private static final int VERSION = 2;
    private static final int MAX_TOKEN_LENGTH = 2048;
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final byte[] SIGNING_CONTEXT =
        "tidymail/unsubscribe-intent/v2".getBytes(StandardCharsets.US_ASCII);

    private final UnsubscribeCandidateService candidates;
    private final UnsubscribeIntentNonceRepository nonces;
    private final UnsubscribeIntentNonceService nonceConsumption;
    private final byte[] signingKey;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public UnsubscribeIntentService(
            UnsubscribeCandidateService candidates,
            UnsubscribeIntentNonceRepository nonces,
            UnsubscribeIntentNonceService nonceConsumption,
            CryptoProperties cryptoProperties) {
        this(candidates, nonces, nonceConsumption,
            cryptoProperties.getSecretKey().getEncoded(), Clock.systemUTC(),
            new SecureRandom());
    }

    UnsubscribeIntentService(
            UnsubscribeCandidateService candidates,
            UnsubscribeIntentNonceRepository nonces,
            UnsubscribeIntentNonceService nonceConsumption,
            byte[] signingKey,
            Clock clock,
            SecureRandom random) {
        if (signingKey == null || signingKey.length < 32) {
            throw new IllegalArgumentException("Intent signing key is unavailable");
        }
        this.candidates = candidates;
        this.nonces = nonces;
        this.nonceConsumption = nonceConsumption;
        this.signingKey = signingKey.clone();
        this.clock = clock;
        this.random = random;
    }

    /**
     * Issues a signed intent token for the candidate and method, persisting a
     * single-use nonce for later verification.
     */
    @Transactional
    public String issue(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod method) {
        ExecutionCandidate candidate =
            candidates.executionCandidate(authorizedAccountIds, candidateKey, method);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(LIFETIME);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String nonceValue = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        IntentPayload payload = new IntentPayload(
            candidate.accountId(), candidate.candidateFingerprint(),
            sha256(candidate.candidateKey().getBytes(StandardCharsets.US_ASCII)),
            method, candidate.destinationFingerprint(), issuedAt, expiresAt, nonceValue);
        byte[] encoded = encode(payload);
        nonces.save(UnsubscribeIntentNonce.issue(
            candidate.accountId(), sha256(nonce), candidate.candidateFingerprint(),
            candidate.destinationFingerprint(), method, issuedAt, expiresAt));
        return base64(encoded) + "." + base64(sign(encoded));
    }

    /**
     * Verifies and redeems an intent token, returning the matched execution
     * candidate or a rejection reason.
     */
    public Consumption consume(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod selectedMethod,
            String token) {
        ParsedToken parsed = parseAndVerify(token);
        IntentPayload payload = parsed.payload();
        Instant now = clock.instant();
        if (!authorizedAccountIds.contains(payload.accountId())
                || selectedMethod != payload.method()
                || !UnsubscribeCandidateKey.isCanonical(candidateKey)
                || !constantEquals(
                    sha256(candidateKey.getBytes(StandardCharsets.US_ASCII)),
                    payload.sourceKeyFingerprint())) {
            throw IntentException.stale();
        }
        if (now.isBefore(payload.issuedAt()) || !now.isBefore(payload.expiresAt())) {
            throw new IntentException(
                HttpStatus.CONFLICT, UnsubscribeReasonCode.EXPIRED_INTENT);
        }

        byte[] rawNonce;
        try {
            rawNonce = Base64.getUrlDecoder().decode(payload.nonce());
        } catch (IllegalArgumentException malformed) {
            throw IntentException.stale();
        }
        String nonceFingerprint = sha256(rawNonce);

        ExecutionCandidate current;
        try {
            current = candidates.executionCandidate(
                authorizedAccountIds, candidateKey, selectedMethod);
        } catch (ResponseStatusException unavailable) {
            consumeNonce(payload, nonceFingerprint, now);
            if (unavailable.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                return Consumption.rejected(UnsubscribeReasonCode.PROTECTED_SENDER);
            }
            return Consumption.rejected(UnsubscribeReasonCode.STALE_INTENT);
        } catch (RuntimeException stale) {
            consumeNonce(payload, nonceFingerprint, now);
            return Consumption.rejected(UnsubscribeReasonCode.STALE_INTENT);
        }
        if (current.accountId() != payload.accountId()
                || !constantEquals(current.candidateFingerprint(), payload.candidateFingerprint())
                || !constantEquals(
                    current.destinationFingerprint(), payload.destinationFingerprint())) {
            consumeNonce(payload, nonceFingerprint, now);
            return Consumption.rejected(UnsubscribeReasonCode.STALE_INTENT);
        }
        consumeNonce(payload, nonceFingerprint, now);
        return Consumption.accepted(current);
    }

    private void consumeNonce(
            IntentPayload payload, String nonceFingerprint, Instant now) {
        UnsubscribeIntentNonceService.ConsumeResult result = nonceConsumption.consume(
            payload.accountId(), nonceFingerprint, payload.candidateFingerprint(),
            payload.destinationFingerprint(), payload.method(), payload.issuedAt(),
            payload.expiresAt(), now);
        switch (result) {
            case CONSUMED -> {
            }
            case REPLAYED -> throw IntentException.replayed();
            case EXPIRED -> throw new IntentException(
                HttpStatus.CONFLICT, UnsubscribeReasonCode.EXPIRED_INTENT);
            case STALE -> throw IntentException.stale();
        }
    }

    private ParsedToken parseAndVerify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw IntentException.stale();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw IntentException.stale();
        final byte[] payload;
        final byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException malformed) {
            throw IntentException.stale();
        }
        if (!base64(payload).equals(parts[0])
                || !base64(signature).equals(parts[1])
                || !MessageDigest.isEqual(sign(payload), signature)) {
            throw IntentException.stale();
        }
        return new ParsedToken(decode(payload));
    }

    private byte[] encode(IntentPayload payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(VERSION);
            output.writeLong(payload.accountId());
            write(output, payload.candidateFingerprint());
            write(output, payload.sourceKeyFingerprint());
            write(output, payload.method().name());
            write(output, payload.destinationFingerprint());
            output.writeLong(payload.issuedAt().getEpochSecond());
            output.writeLong(payload.expiresAt().getEpochSecond());
            write(output, payload.nonce());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode unsubscribe intent", impossible);
        }
    }

    private IntentPayload decode(byte[] encoded) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readUnsignedByte() != VERSION) throw IntentException.stale();
            long accountId = input.readLong();
            String candidate = read(input, 64);
            String sourceKey = read(input, 64);
            UnsubscribeMethod method = UnsubscribeMethod.valueOf(read(input, 24));
            String destination = read(input, 64);
            Instant issuedAt = Instant.ofEpochSecond(input.readLong());
            Instant expiresAt = Instant.ofEpochSecond(input.readLong());
            String nonce = read(input, 64);
            if (input.available() != 0 || accountId <= 0
                    || !candidate.matches("[0-9a-f]{64}")
                    || !sourceKey.matches("[0-9a-f]{64}")
                    || !destination.matches("[0-9a-f]{64}")
                    || !expiresAt.isAfter(issuedAt)
                    || expiresAt.isAfter(issuedAt.plus(Duration.ofMinutes(10)))
                    || nonce.length() != 43) {
                throw IntentException.stale();
            }
            IntentPayload payload = new IntentPayload(
                accountId, candidate, sourceKey, method, destination,
                issuedAt, expiresAt, nonce);
            if (!MessageDigest.isEqual(encode(payload), encoded)) throw IntentException.stale();
            return payload;
        } catch (IOException | RuntimeException invalid) {
            if (invalid instanceof IntentException intent) throw intent;
            throw IntentException.stale();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            hmac.update(SIGNING_CONTEXT);
            return hmac.doFinal(payload);
        } catch (Exception unavailable) {
            throw new IllegalStateException("Intent signing is unavailable", unavailable);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII),
            right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input, int maximum) throws IOException {
        int length = input.readUnsignedShort();
        if (length < 1 || length > maximum) throw IntentException.stale();
        return new String(input.readNBytes(length), StandardCharsets.US_ASCII);
    }

    private record ParsedToken(IntentPayload payload) {
    }

    private record IntentPayload(
        long accountId,
        String candidateFingerprint,
        String sourceKeyFingerprint,
        UnsubscribeMethod method,
        String destinationFingerprint,
        Instant issuedAt,
        Instant expiresAt,
        String nonce
    ) {
    }

    public record Consumption(
        ExecutionCandidate candidate,
        UnsubscribeReasonCode rejection
    ) {
        private static Consumption accepted(ExecutionCandidate candidate) {
            return new Consumption(candidate, null);
        }

        private static Consumption rejected(UnsubscribeReasonCode reason) {
            return new Consumption(null, reason);
        }

        public ExecutionCandidate candidateOrThrow() {
            if (candidate != null) return candidate;
            if (rejection == UnsubscribeReasonCode.PROTECTED_SENDER) {
                throw IntentException.protectedSender();
            }
            throw IntentException.stale();
        }
    }

    public static final class IntentException extends RuntimeException {
        private final HttpStatus status;
        private final UnsubscribeReasonCode reason;

        private IntentException(HttpStatus status, UnsubscribeReasonCode reason) {
            super("Unsubscribe intent is no longer valid");
            this.status = status;
            this.reason = reason;
        }

        public static IntentException stale() {
            return new IntentException(
                HttpStatus.CONFLICT, UnsubscribeReasonCode.STALE_INTENT);
        }

        public static IntentException replayed() {
            return new IntentException(
                HttpStatus.CONFLICT, UnsubscribeReasonCode.REPLAYED_INTENT);
        }

        public static IntentException protectedSender() {
            return new IntentException(
                HttpStatus.CONFLICT, UnsubscribeReasonCode.PROTECTED_SENDER);
        }

        public HttpStatus status() { return status; }
        public UnsubscribeReasonCode reason() { return reason; }
    }
}
