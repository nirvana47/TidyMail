package com.tidymail.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes and decodes the opaque row keys used to select rows on the Senders and
 * Unread surfaces, binding an account and target into a canonical, URL-safe key.
 *
 * <p>Product functionality supported: the <em>Senders</em> and <em>Unread</em>
 * flows' row selection and bulk-action targeting.
 */
public final class DecisionRowKey {
    private DecisionRowKey() {
    }

    /** Encodes a sender row key for an account and sender. */
    public static String sender(long accountId, String senderKey) {
        if (senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException("Sender key is required");
        }
        return encode("s1.", accountId + ":" + senderKey);
    }

    /** Encodes an unread row key for an account and metadata ID. */
    public static String unread(long accountId, long metadataId) {
        if (metadataId <= 0) throw new IllegalArgumentException("Metadata ID must be positive");
        return encode("u1.", accountId + ":" + metadataId);
    }

    /** Encodes an unread-sender row key for an account, category, and sender. */
    public static String unreadSender(
            long accountId, String categoryKey, String senderKey) {
        if (accountId <= 0 || !isUnreadCategory(categoryKey)
                || senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException(
                "Unread sender account, category, and sender are required");
        }
        return encode("us1.", accountId + ":" + categoryKey + ":" + senderKey);
    }

    private static boolean isUnreadCategory(String categoryKey) {
        return "lists-automated".equals(categoryKey)
            || "older-than-30-days".equals(categoryKey)
            || "direct-mail".equals(categoryKey)
            || "attachments".equals(categoryKey)
            || "other".equals(categoryKey);
    }

    /** URL-safe base64-encodes an identity with a version prefix. */
    private static String encode(String prefix, String identity) {
        return prefix + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identity.getBytes(StandardCharsets.UTF_8));
    }

    /** A decoded selection locator (account and target). */
}
