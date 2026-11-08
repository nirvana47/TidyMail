package com.tidymail.mail;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A message projection with headers, labels, size and bounded preview text.
 * <p>
 * Product functionality supported: metadata-only mailbox sync. This record
 * keeps full message bodies unavailable. Optional MIME/calendar evidence is
 * acquired through a separate, bounded read path.
 */
public record MailMessage(
        String id,
        String threadId,
        String provider,
        Instant receivedAt,
        Map<String, List<String>> headers,
        Set<String> labelIds,
        Long sizeEstimate,
        BigInteger providerRevision,
        AttachmentState attachmentState,
        Instant observationStartedAt,
        EmailSnippet snippet,
        Outlook outlook) {
    public record Outlook(String folderId, List<String> categories, String webLink) {}

    public MailMessage(String id, String threadId, String provider, Instant receivedAt,
            Map<String, List<String>> headers, Set<String> labelIds, Long sizeEstimate,
            BigInteger providerRevision, AttachmentState attachmentState, Instant observationStartedAt,
            EmailSnippet snippet) {
        this(id, threadId, provider, receivedAt, headers, labelIds, sizeEstimate, providerRevision,
            attachmentState, observationStartedAt, snippet, null);
    }
    public MailMessage(String id, String threadId, String provider, Instant receivedAt,
            Map<String, List<String>> headers, Set<String> labelIds, Long sizeEstimate,
            BigInteger providerRevision, AttachmentState attachmentState, Instant observationStartedAt) {
        this(id, threadId, provider, receivedAt, headers, labelIds, sizeEstimate, providerRevision, attachmentState,
                observationStartedAt, EmailSnippet.unknown());
    }

    public MailMessage(String id, String threadId, String provider, Instant receivedAt,
            Map<String, List<String>> headers, Set<String> labelIds, Long sizeEstimate,
            BigInteger providerRevision, AttachmentState attachmentState) {
        this(id, threadId, provider, receivedAt, headers, labelIds, sizeEstimate, providerRevision, attachmentState,
                null);
    }

    public MailMessage observedSince(Instant startedAt) {
        return new MailMessage(id, threadId, provider, receivedAt, headers, labelIds, sizeEstimate, providerRevision,
                attachmentState, startedAt, snippet, outlook);
    }

    public MailMessage(
            String id,
            String threadId,
            String provider,
            Instant receivedAt,
            Map<String, List<String>> headers,
            Set<String> labelIds) {
        this(id, threadId, provider, receivedAt, headers, labelIds,
                null, null, AttachmentState.UNKNOWN);
    }

    public MailMessage {
        if (snippet == null)
            snippet = EmailSnippet.unknown();
        if (sizeEstimate != null && sizeEstimate < 0) {
            throw new IllegalArgumentException("sizeEstimate is the non-negative full message size");
        }
        if (providerRevision != null && providerRevision.signum() < 0) {
            throw new IllegalArgumentException("providerRevision must be unsigned");
        }
        if (attachmentState == null) {
            attachmentState = AttachmentState.UNKNOWN;
        }
    }

    public boolean hasAttachments() {
        return attachmentState == AttachmentState.PRESENT;
    }

    public String body() {
        throw new UnsupportedOperationException(
                "METADATA-only privacy contract — body not available on this MailMessage projection.");
    }

    public boolean isUnread() {
        return labelIds != null && labelIds.contains("UNREAD");
    }

    /**
     * Returns the first value for a case-insensitive header name, if present.
     */
    public Optional<String> getHeaderSingle(String name) {
        if (headers == null || name == null) {
            return Optional.empty();
        }
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey())) {
                    values = entry.getValue();
                    break;
                }
            }
        }
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.getFirst());
    }

    public Optional<String> from() {
        return getHeaderSingle("From");
    }

    public Optional<String> to() {
        return getHeaderSingle("To");
    }

    public Optional<String> subject() {
        return getHeaderSingle("Subject");
    }

    public Optional<String> date() {
        return getHeaderSingle("Date");
    }
}
