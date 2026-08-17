package com.tidymail.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tidymail.security.crypto.CryptoUtil.CryptoException;

/**
 * Resolves the two configured environment variables into a concrete AES-256
 * secret key usable by {@link CryptoUtil}.
 *
 * <p>Environment contract (matches {@code .env.example} §6):
 * <ul>
 *   <li>{@code TOKEN_ENCRYPT_SECRET} — 256-bit master secret.  Generate with:
 *       {@code openssl rand -hex 32}.  Produces a 64-character lowercase hex
 *       string which decodes to 32 raw bytes.  Base64 encoding of the same 32
 *       bytes is also accepted for backward compatibility.</li>
 *   <li>{@code TOKEN_ENCRYPT_SALT} — 128-bit static salt.  Generate with:
 *       {@code openssl rand -hex 16}.  Produces a 32-character lowercase hex
 *       string which decodes to 16 raw bytes.  Base64 is also accepted.</li>
 * </ul>
 *
 * <p>Backward-compatibility:  If the legacy {@code AES_256_KEY} env var is
 * set and {@code TOKEN_ENCRYPT_SECRET} is blank, the legacy value is used as
 * the master secret directly and the salt defaults to 16 zero bytes.  New
 * deployments should always use the two newer variables.
 *
 * <p>Key derivation:  the final working AES-256 key is obtained via
 * {@code HKDF-SHA256(secret, salt, info="tidymail/oauth-token-aes-256-gcm/v1", L=32)}.
 * This provides domain separation so the same master material can never be
 * accidentally reused for a different purpose (it derives a different 32-byte
 * subkey for each unique {@code info} label).
 */
@Component
public class CryptoProperties {

    private static final byte[] HKDF_INFO =
        "tidymail/oauth-token-aes-256-gcm/v1".getBytes(StandardCharsets.UTF_8);

    /** Static key-id recorded alongside every encrypted token blob. */
    private static final String AES_KEY_ID = "hkdf-sha256-v1";

    @Value("${TOKEN_ENCRYPT_SECRET:}")
    private String tokenEncryptSecret;

    @Value("${TOKEN_ENCRYPT_SALT:}")
    private String tokenEncryptSalt;

    @Value("${AES_256_KEY:}")
    private String legacyAes256KeyBase64;

    public boolean isConfigured() {
        byte[] secret = decodeSecretOrNull();
        if (secret == null) {
            return false;
        }
        byte[] salt = decodeSaltOrNull();
        return secret.length == 32 && salt.length >= 8;
    }

    /**
     * Returns a 256-bit AES key derived from the configured secret + salt via
     * HKDF-SHA256.  Callers are responsible for checking {@link #isConfigured()}
     * first; if configuration is invalid this method throws an unchecked
     * exception (wrapping the underlying JCA error) with a descriptive message
     * that references the {@code .env.example} §6 generation commands.
     */
    public SecretKey getSecretKey() {
        byte[] master = decodeSecretOrNull();
        if (master == null || master.length != 32) {
            throw new CryptoException(
                "TOKEN_ENCRYPT_SECRET environment variable missing or invalid.  Expected 32 raw bytes "
                + "(generate via `openssl rand -hex 32` → 64 hex characters; base64 of 32 bytes also accepted). "
                + "See .env.example section 6.");
        }
        byte[] salt = decodeSaltOrNull();
        if (salt.length < 8) {
            throw new CryptoException(
                "TOKEN_ENCRYPT_SALT environment variable missing or too short.  Expected at least 8 bytes "
                + "(generate via `openssl rand -hex 16` → 32 hex characters; base64 also accepted).  "
                + "See .env.example section 6.");
        }
        try {
            byte[] derived = hkdfExtractExpand(master, salt, HKDF_INFO, 32);
            return new SecretKeySpec(derived, "AES");
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new CryptoException("HKDF key derivation failed unexpectedly — JCA HmacSHA256 provider missing?", ex);
        }
    }

    /**
     * Returns a static short label describing the key-derivation configuration.
     * This value is persisted alongside every encrypted token row in the
     * {@code user_accounts.aes_key_id} column so future key-rotation migrations
     * can identify which KDF + parameters produced a given blob.
     */
    public String getAesKeyId() {
        return AES_KEY_ID;
    }

    // ------------------------------------------------------------------
    // Decoding helpers.  Try hex first (openssl rand -hex output), then
    // fall back to base64 for the legacy "python base64" workflow.
    // ------------------------------------------------------------------

    private byte[] decodeSecretOrNull() {
        String s = (tokenEncryptSecret != null && !tokenEncryptSecret.isBlank())
            ? tokenEncryptSecret.trim()
            : (legacyAes256KeyBase64 != null && !legacyAes256KeyBase64.isBlank())
                ? legacyAes256KeyBase64.trim()
                : null;
        if (s == null) {
            return null;
        }
        return decodeHexOrBase64(s);
    }

    private byte[] decodeSaltOrNull() {
        // 16 zero bytes = the legacy salt when only AES_256_KEY was configured.
        byte[] fallbackLegacySalt = new byte[16];
        if (tokenEncryptSalt == null || tokenEncryptSalt.isBlank()) {
            // Legacy path: no salt set.  If the *secret* came from AES_256_KEY
            // (i.e. TOKEN_ENCRYPT_SECRET is empty too), keep backward compat by
            // using a deterministic zero-byte salt so existing ciphertexts from
            // the Phase-3 pre-fix era can still be decrypted.
            if (tokenEncryptSecret == null || tokenEncryptSecret.isBlank()) {
                return fallbackLegacySalt;
            }
            // New two-var contract but the user forgot the salt → fail short.
            return new byte[0];
        }
        byte[] decoded = decodeHexOrBase64(tokenEncryptSalt.trim());
        return decoded == null ? new byte[0] : decoded;
    }

    private static byte[] decodeHexOrBase64(String s) {
        // Hex test: length even and every character in [0-9a-fA-F].
        if ((s.length() % 2) == 0 && s.chars().allMatch(CryptoProperties::isHexChar)) {
            try {
                return HexFormat.of().parseHex(s);
            } catch (IllegalArgumentException ignored) {
                // Fall through to base64 below.
            }
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isHexChar(int c) {
        return (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f')
            || (c >= 'A' && c <= 'F');
    }

    /**
     * {@code HMAC-based Extract-and-Expand KDF (HKDF)} as defined by RFC 5869,
     * implemented directly on top of the JCA {@code HmacSHA256} {@link Mac}.
     * This approach is fully portable across every JDK 8+ build without
     * relying on JDK-18's {@code HKDFParameterSpec} (which is present in the
     * JDK 21 class library but not always visible to the Maven compiler's
     * default release profile).
     *
     * <p>RFC 5869 steps:
     * <ol>
     *   <li><b>Extract.</b> {@code PRK = HMAC-SHA256(salt, IKM)}.  PRK length is
     *       always the HMAC output size = 32 bytes (SHA-256).</li>
     *   <li><b>Expand.</b> {@code T(0) = (empty) ; T(i) = HMAC-SHA256(PRK,
     *       T(i-1) || info || byte(i))} for i = 1, 2, ...  Take the first
     *       {@code L} bytes of the concatenation of {@code T(1) || T(2) || ...}.
     *       For our L=32 case we only need a single iteration {@code T(1)}.</li>
     * </ol>
     *
     * @param ikm   input keying material — 32 random bytes in our case
     * @param salt  salt (optional, RFC allows empty; we enforce >= 8 bytes)
     * @param info  context / application label
     * @param outL  desired output length in bytes (32 = 256-bit AES key)
     */
    private static byte[] hkdfExtractExpand(byte[] ikm, byte[] salt, byte[] info, int outL)
            throws NoSuchAlgorithmException, InvalidKeyException {
        if (outL < 1) {
            throw new IllegalArgumentException("HKDF outL must be >= 1");
        }

        Mac hmac = Mac.getInstance("HmacSHA256");
        int hashLen = hmac.getMacLength(); // == 32 for SHA-256
        // Compute how many T(i) blocks are needed; ceil(outL/hashLen).
        int blocks = (outL + hashLen - 1) / hashLen;
        if (blocks > 255) {
            throw new IllegalArgumentException("HKDF output too large: blocks=" + blocks + " > 255 (RFC 5869 §2.3)");
        }

        // -------- Step 1: Extract --------
        // If salt is zero-length, RFC says use a string of HashLen zeros.
        // Our contract enforces salt.length >= 8 so this branch never fires
        // for TidyMail, but the RFC-correct fallback is cheap.
        byte[] saltBytes = (salt == null || salt.length == 0) ? new byte[hashLen] : salt;
        hmac.init(new SecretKeySpec(saltBytes, "HmacSHA256"));
        byte[] prk = hmac.doFinal(ikm);

        // -------- Step 2: Expand --------
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] output = new byte[outL];
        int written = 0;
        byte[] tPrev = new byte[0]; // T(0) = empty string per RFC
        for (int i = 1; i <= blocks; i++) {
            // Feed: T(i-1) || info || 0x01..0xNN
            // Use single doFinal() over a concatenated buffer to avoid
            // needing to reset the Mac state between update calls.
            int feedLen = tPrev.length + info.length + 1;
            byte[] feed = new byte[feedLen];
            System.arraycopy(tPrev, 0, feed, 0, tPrev.length);
            System.arraycopy(info, 0, feed, tPrev.length, info.length);
            feed[feedLen - 1] = (byte) i;
            byte[] tI = hmac.doFinal(feed);
            int copy = Math.min(tI.length, outL - written);
            System.arraycopy(tI, 0, output, written, copy);
            written += copy;
            tPrev = tI;
        }
        return output;
    }
}
