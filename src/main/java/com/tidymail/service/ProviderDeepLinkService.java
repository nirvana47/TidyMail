package com.tidymail.service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Builds safe, validated deep links into the mail provider (currently Gmail) for
 * an account or a specific thread/message, rejecting malformed input.
 *
 * <p>Product functionality supported: the <em>Unread</em> and <em>Review</em>
 * flows' provider links to open messages in the mailbox.
 */
@Service
public final class ProviderDeepLinkService {
    private static final String GMAIL_HOST = "mail.google.com";
    private static final int MAX_ACCOUNT_EMAIL_LENGTH = 320;

    /** Returns a validated deep link to the account's mailbox, if supported. */
    public Optional<String> accountPage(String provider, String accountEmail) {
        if (!"google".equals(provider) || !validAccountEmail(accountEmail)) {
            return Optional.empty();
        }
        try {
            String url = "https://" + GMAIL_HOST + "/mail/u/" + encode(accountEmail) + "/";
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !GMAIL_HOST.equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return Optional.empty();
            }
            return Optional.of(uri.toASCIIString());
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            return Optional.empty();
        }
    }

    /** Returns a validated deep link to a Gmail thread or message. */
    public Optional<String> gmail(
            String provider,
            String accountEmail,
            String threadId,
            String messageId) {
        if (!"google".equals(provider)) {
            return Optional.empty();
        }
        String itemId;
        if (threadId == null || threadId.isBlank()) {
            if (threadId != null && threadId.chars().anyMatch(Character::isISOControl)) {
                return Optional.empty();
            }
            itemId = messageId;
        } else {
            itemId = threadId;
        }
        if (!validAccountEmail(accountEmail) || !validSegment(itemId)) {
            return Optional.empty();
        }
        try {
            String url = "https://" + GMAIL_HOST + "/mail/u/" + encode(accountEmail)
                + "/#all/" + encode(itemId);
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme())
                    || !GMAIL_HOST.equals(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || !uri.getRawPath().equals("/mail/u/" + encode(accountEmail) + "/")
                    || !uri.getRawFragment().equals("all/" + encode(itemId))) {
                return Optional.empty();
            }
            return Optional.of(uri.toASCIIString());
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            return Optional.empty();
        }
    }

    /** Returns true if a URL segment is safe to embed. */
    private static boolean validSegment(String value) {
        if (!hasText(value) || value.length() > 1024) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\uFFFD'
                    || character == '/' || character == '\\'
                    || Character.isISOControl(character)) {
                return false;
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    /** Returns true if the value is a plausible account email. */
    private static boolean validAccountEmail(String value) {
        if (!validSegment(value) || value.length() > MAX_ACCOUNT_EMAIL_LENGTH) return false;
        int separator = value.indexOf('@');
        return separator > 0
            && separator == value.lastIndexOf('@')
            && separator < value.length() - 1
            && separator <= 64
            && value.length() - separator - 1 <= 253
            && value.chars().noneMatch(Character::isWhitespace);
    }

    /** Percent-encodes a UTF-8 value for a URL path/fragment. */
    private static String encode(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.remaining());
        while (encoded.hasRemaining()) {
            bytes.write(encoded.get());
        }
        StringBuilder result = new StringBuilder(bytes.size() * 3);
        for (byte raw : bytes.toByteArray()) {
            int valueByte = raw & 0xff;
            if (valueByte >= 'a' && valueByte <= 'z'
                    || valueByte >= 'A' && valueByte <= 'Z'
                    || valueByte >= '0' && valueByte <= '9'
                    || valueByte == '-' || valueByte == '.' || valueByte == '_'
                    || valueByte == '~') {
                result.append((char) valueByte);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit(valueByte >>> 4, 16)));
                result.append(Character.toUpperCase(Character.forDigit(valueByte & 0xf, 16)));
            }
        }
        return result.toString();
    }

    /** Returns true if the value has non-blank text. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
