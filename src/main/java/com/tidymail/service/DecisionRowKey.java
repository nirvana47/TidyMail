package com.tidymail.service;

import com.tidymail.dto.SelectionSurface;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DecisionRowKey {
    private DecisionRowKey() {
    }

    public static String sender(long accountId, String senderKey) {
        if (senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException("Sender key is required");
        }
        return encode("s1.", accountId + ":" + senderKey);
    }

    public static String unread(long accountId, long metadataId) {
        if (metadataId <= 0) throw new IllegalArgumentException("Metadata ID must be positive");
        return encode("u1.", accountId + ":" + metadataId);
    }

    public static String unreadSender(
            long accountId, String categoryKey, String senderKey) {
        if (accountId <= 0 || !isUnreadCategory(categoryKey)
                || senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException(
                "Unread sender account, category, and sender are required");
        }
        return encode("us1.", accountId + ":" + categoryKey + ":" + senderKey);
    }

    public static UnreadSenderLocator decodeUnreadSender(String rowKey) {
        if (rowKey == null || !rowKey.startsWith("us1.") || rowKey.length() > 1024) {
            throw new IllegalArgumentException("Invalid unread sender row key");
        }
        String identity = decodePayload(rowKey.substring("us1.".length()));
        int accountSeparator = identity.indexOf(':');
        int categorySeparator = identity.indexOf(':', accountSeparator + 1);
        if (accountSeparator <= 0 || categorySeparator <= accountSeparator + 1
                || categorySeparator == identity.length() - 1) {
            throw new IllegalArgumentException("Invalid unread sender row key");
        }
        final long accountId;
        try {
            accountId = Long.parseLong(identity.substring(0, accountSeparator));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid unread sender row key", malformed);
        }
        String categoryKey =
            identity.substring(accountSeparator + 1, categorySeparator);
        String senderKey = identity.substring(categorySeparator + 1);
        String canonical = unreadSender(accountId, categoryKey, senderKey);
        if (!canonical.equals(rowKey)) {
            throw new IllegalArgumentException("Invalid unread sender row key");
        }
        return new UnreadSenderLocator(accountId, categoryKey, senderKey);
    }

    public static Locator decode(SelectionSurface surface, String rowKey) {
        String prefix = surface == SelectionSurface.SENDERS ? "s1." : "u1.";
        if (rowKey == null || !rowKey.startsWith(prefix) || rowKey.length() > 1024) {
            throw new IllegalArgumentException("Invalid selection row key");
        }
        String identity = decodePayload(rowKey.substring(prefix.length()));
        int separator = identity.indexOf(':');
        if (separator <= 0 || separator == identity.length() - 1) {
            throw new IllegalArgumentException("Invalid selection row key");
        }
        final long accountId;
        try {
            accountId = Long.parseLong(identity.substring(0, separator));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid selection row key", malformed);
        }
        if (accountId <= 0) throw new IllegalArgumentException("Invalid selection row key");
        String target = identity.substring(separator + 1);
        String canonical;
        if (surface == SelectionSurface.UNREAD) {
            try {
                long metadataId = Long.parseLong(target);
                if (metadataId <= 0) {
                    throw new IllegalArgumentException("Invalid selection row key");
                }
                canonical = unread(accountId, metadataId);
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Invalid selection row key", malformed);
            }
        } else {
            canonical = sender(accountId, target);
        }
        if (!canonical.equals(rowKey)) {
            throw new IllegalArgumentException("Invalid selection row key");
        }
        return new Locator(accountId, target);
    }

    private static String decodePayload(String payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(payload)))
                .toString();
        } catch (IllegalArgumentException | CharacterCodingException malformed) {
            throw new IllegalArgumentException("Invalid selection row key", malformed);
        }
    }

    private static boolean isUnreadCategory(String categoryKey) {
        return "lists-automated".equals(categoryKey)
            || "older-than-30-days".equals(categoryKey)
            || "direct-mail".equals(categoryKey)
            || "attachments".equals(categoryKey)
            || "other".equals(categoryKey);
    }

    private static String encode(String prefix, String identity) {
        return prefix + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identity.getBytes(StandardCharsets.UTF_8));
    }

    public record Locator(long accountId, String target) {
        public long metadataId() {
            return Long.parseLong(target);
        }
    }

    public record UnreadSenderLocator(
        long accountId,
        String categoryKey,
        String senderKey
    ) {
    }
}
