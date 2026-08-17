package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.entity.ActionCommandToken;
import com.tidymail.repository.ActionCommandTokenRepository;
import com.tidymail.security.crypto.CryptoProperties;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies signed, short-lived confirmation tokens that bind an owner,
 * action type, and exact scope set to a user's intent to execute. Tokens are
 * HMAC-signed, persisted per account, and expire after a short lifetime.
 *
 * <p>Product functionality supported: the <em>Bulk Action</em> and <em>Review</em>
 * flows' confirmation step, which prevents stale or replayed executions.
 */
@Service
public class ActionConfirmationIntentService {
    private static final byte VERSION = 2;
    private static final int MAX_TOKEN = 4096;
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final byte[] CONTEXT =
        "tidymail/action-confirmation/v1".getBytes(StandardCharsets.US_ASCII);

    private final ActionCommandTokenRepository tokens;
    private final byte[] signingKey;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public ActionConfirmationIntentService(
            ActionCommandTokenRepository tokens, CryptoProperties cryptoProperties) {
        this(tokens, cryptoProperties.getSecretKey().getEncoded(),
            Clock.systemUTC(), new SecureRandom());
    }

    ActionConfirmationIntentService(
            ActionCommandTokenRepository tokens, byte[] signingKey,
            Clock clock, SecureRandom random) {
        this.tokens = tokens;
        this.signingKey = signingKey.clone();
        this.clock = clock;
        this.random = random;
    }

    /** Issues a signed confirmation token for the given scope bindings. */
    @Transactional
    public String issue(
            String ownerKey, ActionType actionType, DecisionSource source,
            List<ScopeBinding> scopeBindings) {
        if (scopeBindings == null || scopeBindings.isEmpty()) {
            throw new IllegalArgumentException("Exact scopes are required");
        }
        List<ScopeBinding> ordered = scopeBindings.stream()
            .distinct()
            .sorted(Comparator.comparingLong(ScopeBinding::accountId)
                .thenComparing(ScopeBinding::scopeHash))
            .toList();
        if (ordered.size() != scopeBindings.size()) {
            throw new IllegalArgumentException("Duplicate scope binding");
        }
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plus(LIFETIME);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        Payload payload = new Payload(
            ActionFingerprints.sha256(ownerKey), actionType, source,
            ordered, issued, expires,
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        byte[] encoded = encode(payload);
        String token = base64(encoded) + "." + base64(sign(encoded));
        String tokenFingerprint = ActionFingerprints.sha256(token);
        String subject = subjectFingerprint(ordered);
        for (ScopeBinding binding : ordered) {
            tokens.save(ActionCommandToken.issue(
                binding.accountId(), tokenFingerprint, payload.ownerFingerprint(),
                ActionFingerprints.sha256("CREATE_" + actionType.name()), subject,
                issued, expires));
        }
        return token;
    }

    /** Verifies a token's signature, owner, and freshness, returning its payload. */
    public Payload verify(String ownerKey, String token) {
        if (token == null || token.length() > MAX_TOKEN
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw invalid();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw invalid();
        byte[] encoded;
        byte[] signature;
        try {
            encoded = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (!base64(encoded).equals(parts[0])
                || !base64(signature).equals(parts[1])
                || !MessageDigest.isEqual(sign(encoded), signature)) {
            throw invalid();
        }
        Payload payload = decode(encoded);
        Instant now = clock.instant();
        if (!MessageDigest.isEqual(
                payload.ownerFingerprint().getBytes(StandardCharsets.US_ASCII),
                ActionFingerprints.sha256(ownerKey).getBytes(StandardCharsets.US_ASCII))
                || now.isBefore(payload.issuedAt()) || !now.isBefore(payload.expiresAt())) {
            throw invalid();
        }
        return payload;
    }

    /** Computes a fingerprint binding the exact scope set for a confirmation. */
    public static String subjectFingerprint(List<ScopeBinding> bindings) {
        List<Object> values = new ArrayList<>();
        bindings.forEach(binding -> {
            values.add(binding.accountId());
            values.add(binding.scopeHash());
            values.add(binding.unsubscribeCandidateFingerprint());
            values.add(binding.unsubscribeDestinationFingerprint());
            values.add(binding.unsubscribeMethod());
        });
        return ActionFingerprints.bind(
            "action-subject-v1", values.toArray());
    }

    /** Serializes a payload to its canonical binary form. */
    private byte[] encode(Payload payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(VERSION);
            write(output, payload.ownerFingerprint());
            write(output, payload.actionType().name());
            write(output, payload.source().name());
            output.writeShort(payload.scopes().size());
            for (ScopeBinding binding : payload.scopes()) {
                output.writeLong(binding.accountId());
                write(output, binding.scopeHash());
                writeOptional(output, binding.unsubscribeCandidateFingerprint());
                writeOptional(output, binding.unsubscribeDestinationFingerprint());
                writeOptional(output, binding.unsubscribeMethod());
            }
            output.writeLong(payload.issuedAt().getEpochSecond());
            output.writeLong(payload.expiresAt().getEpochSecond());
            write(output, payload.nonce());
            output.flush();
            return bytes.toByteArray();
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not encode confirmation", impossible);
        }
    }

    /** Deserializes and validates a payload from its canonical binary form. */
    private Payload decode(byte[] encoded) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readUnsignedByte() != VERSION) throw invalid();
            String owner = read(input, 64);
            ActionType action = ActionType.valueOf(read(input, 32));
            DecisionSource source = DecisionSource.valueOf(read(input, 16));
            int count = input.readUnsignedShort();
            if (count < 1 || count > 100) throw invalid();
            List<ScopeBinding> bindings = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                bindings.add(new ScopeBinding(
                    input.readLong(), read(input, 64),
                    readOptional(input, 64),
                    readOptional(input, 64),
                    readOptional(input, 24)));
            }
            Instant issued = Instant.ofEpochSecond(input.readLong());
            Instant expires = Instant.ofEpochSecond(input.readLong());
            String nonce = read(input, 64);
            Payload payload = new Payload(
                owner, action, source, List.copyOf(bindings), issued, expires, nonce);
            if (input.available() != 0 || !expires.isAfter(issued)
                    || expires.isAfter(issued.plus(Duration.ofMinutes(10)))
                    || !MessageDigest.isEqual(encode(payload), encoded)) {
                throw invalid();
            }
            return payload;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    /** Computes the HMAC-SHA256 signature of a payload. */
    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            mac.update(CONTEXT);
            return mac.doFinal(payload);
        } catch (Exception unavailable) {
            throw new IllegalStateException("Confirmation signing unavailable", unavailable);
        }
    }

    /** Writes a length-prefixed ASCII string to the output stream. */
    private static void write(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    /** Writes a nullable length-prefixed string (empty length means null). */
    private static void writeOptional(
            DataOutputStream output, String value) throws Exception {
        if (value == null) {
            output.writeShort(0);
            return;
        }
        write(output, value);
    }

    /** Reads a bounded length-prefixed ASCII string. */
    private static String read(DataInputStream input, int maximum) throws Exception {
        int length = input.readUnsignedShort();
        if (length < 1 || length > maximum) throw invalid();
        return new String(input.readNBytes(length), StandardCharsets.US_ASCII);
    }

    /** Reads a nullable bounded string (empty length means null). */
    private static String readOptional(
            DataInputStream input, int maximum) throws Exception {
        int length = input.readUnsignedShort();
        if (length == 0) return null;
        if (length > maximum) throw invalid();
        return new String(input.readNBytes(length), StandardCharsets.US_ASCII);
    }

    /** URL-safe base64-encodes a value without padding. */
    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** Returns the exception for an invalid or expired confirmation. */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Action confirmation is no longer valid");
    }

    /** One exact scope (and optional unsubscribe binding) in a confirmation. */
    public record ScopeBinding(
        long accountId,
        String scopeHash,
        String unsubscribeCandidateFingerprint,
        String unsubscribeDestinationFingerprint,
        String unsubscribeMethod
    ) {
        public ScopeBinding(long accountId, String scopeHash) {
            this(accountId, scopeHash, null, null, null);
        }

        public ScopeBinding {
            if (accountId <= 0 || scopeHash == null
                    || !scopeHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid scope binding");
            }
            boolean unsubscribe = unsubscribeMethod != null;
            if (unsubscribe != (unsubscribeCandidateFingerprint != null)
                    || unsubscribe != (unsubscribeDestinationFingerprint != null)
                    || unsubscribe && (!unsubscribeCandidateFingerprint.matches("[0-9a-f]{64}")
                        || !unsubscribeDestinationFingerprint.matches("[0-9a-f]{64}")
                        || !unsubscribeMethod.matches("[A-Z][A-Z0-9_]{0,23}"))) {
                throw new IllegalArgumentException("Invalid unsubscribe binding");
            }
        }
    }

    /** The verified contents of a confirmation token. */
    public record Payload(
        String ownerFingerprint,
        ActionType actionType,
        DecisionSource source,
        List<ScopeBinding> scopes,
        Instant issuedAt,
        Instant expiresAt,
        String nonce
    ) {
        public Payload {
            scopes = List.copyOf(scopes);
        }
    }
}
