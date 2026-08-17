package com.tidymail.unsubscribe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Derives a canonical opaque identifier for an unsubscribe candidate from its
 * account, metadata, sender, and list identity, for stable correlation.
 * <p>
 * Product functionality supported: candidate correlation in TidyMail's
 * unsubscribe flow.
 */
public final class UnsubscribeCandidateKey {
    private static final String PREFIX = "c1.";
    private static final int DIGEST_LENGTH = 43;
    private static final Pattern DIGEST =
        Pattern.compile("[A-Za-z0-9_-]{" + DIGEST_LENGTH + "}");

    private UnsubscribeCandidateKey() {
    }

    /**
     * Encodes the candidate identity into a canonical key string.
     */
    public static String encode(
            long accountId, long metadataId, String senderKey, String listIdentity) {
        if (accountId <= 0 || metadataId <= 0 || invalid(senderKey) || invalid(listIdentity)) {
            throw new IllegalArgumentException("Invalid unsubscribe candidate identity");
        }
        String identity = accountId + ":" + metadataId + ":"
            + senderKey.length() + ":" + senderKey
            + listIdentity.length() + ":" + listIdentity;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * Returns whether the given key matches the expected canonical format.
     */
    public static boolean isCanonical(String key) {
        return key != null
            && key.length() == PREFIX.length() + DIGEST_LENGTH
            && key.startsWith(PREFIX)
            && DIGEST.matcher(key.substring(PREFIX.length())).matches();
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 512
            || value.chars().anyMatch(Character::isISOControl);
    }
}
