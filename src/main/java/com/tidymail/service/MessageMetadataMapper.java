package com.tidymail.service;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.util.Rfc5322Util;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Maps a provider {@link MailMessage} into a {@link MessageMetadata} entity,
 * extracting and sanitizing headers and deriving DKIM/list-unsubscribe
 * authentication facts for unsubscribe safety.
 *
 * <p>Product functionality supported: the metadata-only sync's message mapping
 * and the unsubscribe flow's DKIM-based destination trust checks.
 */
@Component
public class MessageMetadataMapper {
    private static final int MAX_AUTH_HEADER_LENGTH = 8192;
    private static final int MAX_SIGNATURE_HEADER_LENGTH = 16384;
    private static final int MAX_AUTH_HEADERS = 8;
    private static final Pattern DKIM_PASS = Pattern.compile(
        "(?i)^\\s*dkim\\s*=\\s*pass(?:\\s|$)");
    private static final Pattern HEADER_D = Pattern.compile(
        "(?i)(?:^|\\s)header\\.d\\s*=\\s*([^\\s;]+)");
    private static final Pattern HEADER_S = Pattern.compile(
        "(?i)(?:^|\\s)header\\.s\\s*=\\s*([^\\s;]+)");

    /** Maps a mail message into a metadata entity (new or existing). */
    public MessageMetadata apply(
            Long accountId, MailMessage message, MessageMetadata target, Instant syncedAt) {
        MessageMetadata entity = target == null ? new MessageMetadata() : target;
        if (entity.getId() == null) {
            entity.setAccountId(accountId);
            entity.setProviderMessageId(message.id());
            entity.setFirstSeenAt(syncedAt);
        }

        String from = first(message.headers(), "From");
        entity.setProvider(message.provider());
        entity.setProviderThreadId(message.threadId());
        entity.setFromAddress(Rfc5322Util.extractAddrSpec(from));
        entity.setFromDisplayName(displayName(from));
        entity.setFromDomain(Rfc5322Util.extractDomain(from));
        entity.setSubjectHeader(first(message.headers(), "Subject"));
        entity.setReceivedAt(message.receivedAt());
        entity.setLastSyncedAt(syncedAt);
        entity.setUnread(message.isUnread());
        entity.setSizeEstimate(message.sizeEstimate());
        entity.setHasAttachments(message.hasAttachments());
        entity.setProviderRevision(message.providerRevision());
        entity.setAttachmentState(message.attachmentState());
        if (message.attachmentState() == AttachmentState.UNKNOWN) {
            entity.setAttachmentObservedAt(null);
            entity.setAttachmentSource(null);
        } else {
            entity.setAttachmentObservedAt(syncedAt);
            entity.setAttachmentSource("PROVIDER_METADATA");
        }
        entity.setLabels(message.labelIds() == null
            ? List.of() : new ArrayList<>(message.labelIds()));
        entity.setToValues(values(message.headers(), "To"));
        entity.setCcValues(values(message.headers(), "Cc"));
        entity.setBccValues(values(message.headers(), "Bcc"));
        entity.setListUnsubscribeValues(values(message.headers(), "List-Unsubscribe"));
        entity.setListUnsubscribePostValues(values(message.headers(), "List-Unsubscribe-Post"));
        entity.setListIdValues(values(message.headers(), "List-Id"));
        entity.setReferencesValues(values(message.headers(), "References"));
        entity.setInReplyToValues(values(message.headers(), "In-Reply-To"));
        entity.setReplyToValues(values(message.headers(), "Reply-To"));
        entity.setReturnPathValues(values(message.headers(), "Return-Path"));
        entity.setDeliveredToValues(values(message.headers(), "Delivered-To"));
        entity.setPrecedenceValues(values(message.headers(), "Precedence"));
        entity.setAutoSubmittedValues(values(message.headers(), "Auto-Submitted"));
        entity.setMessageIdValues(values(message.headers(), "Message-ID"));
        entity.setFeedbackIdValues(values(message.headers(), "Feedback-ID"));
        applyUnsubscribeAuthenticationFacts(entity, message);
        return entity;
    }

    /** Records the derived DKIM facts for unsubscribe authentication. */
    private static void applyUnsubscribeAuthenticationFacts(
            MessageMetadata entity, MailMessage message) {
        DkimFacts facts = deriveDkimFacts(message);
        entity.setUnsubscribeDkimPass(facts.pass());
        entity.setUnsubscribeDkimCoversListUnsubscribe(facts.coversListUnsubscribe());
        entity.setUnsubscribeDkimCoversListUnsubscribePost(
            facts.coversListUnsubscribePost());
        entity.setUnsubscribeAuthReason(facts.reason());
    }

    /** Derives trusted DKIM pass and list-unsubscribe coverage facts. */
    private static DkimFacts deriveDkimFacts(MailMessage message) {
        if (!"google".equals(message.provider())) {
            return DkimFacts.noTrustedResult();
        }
        List<String> authenticationResults =
            values(message.headers(), "Authentication-Results");
        List<String> signatures = values(message.headers(), "DKIM-Signature");
        if (authenticationResults.size() > MAX_AUTH_HEADERS
                || signatures.size() > MAX_AUTH_HEADERS
                || authenticationResults.stream().anyMatch(value ->
                    invalidHeader(value, MAX_AUTH_HEADER_LENGTH))
                || signatures.stream().anyMatch(value ->
                    invalidHeader(value, MAX_SIGNATURE_HEADER_LENGTH))) {
            return DkimFacts.ambiguous();
        }
        if (authenticationResults.size() > 1) {
            return DkimFacts.ambiguous();
        }

        List<DkimIdentity> trustedPasses = new ArrayList<>();
        for (String raw : authenticationResults) {
            String unfolded = unfold(raw);
            if (unfolded == null) return DkimFacts.ambiguous();
            int separator = unfolded.indexOf(';');
            if (separator <= 0
                    || !"mx.google.com".equalsIgnoreCase(
                        unfolded.substring(0, separator).trim())) {
                continue;
            }
            String[] clauses = unfolded.substring(separator + 1).split(";", -1);
            for (String clause : clauses) {
                if (!DKIM_PASS.matcher(clause).find()) continue;
                DkimIdentity identity = parsePassingIdentity(clause);
                if (identity == null) return DkimFacts.ambiguous();
                trustedPasses.add(identity);
            }
        }
        if (trustedPasses.isEmpty()) {
            return DkimFacts.noTrustedResult();
        }
        if (trustedPasses.size() != 1) {
            return DkimFacts.ambiguous();
        }

        DkimIdentity passing = trustedPasses.getFirst();
        List<Map<String, String>> parsedSignatures = new ArrayList<>();
        for (String signature : signatures) {
            Map<String, String> tags = parseSignatureTags(signature);
            if (tags == null) return DkimFacts.ambiguous();
            parsedSignatures.add(tags);
        }
        List<Map<String, String>> matching = parsedSignatures.stream()
            .filter(tags -> passing.matches(tags.get("d"), tags.get("s")))
            .toList();
        if (matching.size() != 1) {
            return DkimFacts.ambiguous();
        }
        String signedHeaders = matching.getFirst().get("h");
        if (signedHeaders == null) {
            return DkimFacts.coverageMissing(false, false);
        }
        List<String> covered = Pattern.compile(":")
            .splitAsStream(signedHeaders)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .toList();
        boolean listUnsubscribe = covered.contains("list-unsubscribe");
        boolean listUnsubscribePost = covered.contains("list-unsubscribe-post");
        return listUnsubscribe && listUnsubscribePost
            ? new DkimFacts(true, true, true, "TRUSTED_PASS")
            : DkimFacts.coverageMissing(listUnsubscribe, listUnsubscribePost);
    }

    /** Parses a passing DKIM identity (header.d/header.s) from a clause. */
    private static DkimIdentity parsePassingIdentity(String clause) {
        List<String> domains = matches(HEADER_D, clause);
        List<String> selectors = matches(HEADER_S, clause);
        if (domains.size() != 1 || selectors.size() > 1) return null;
        return new DkimIdentity(
            domains.getFirst().toLowerCase(Locale.ROOT),
            selectors.isEmpty() ? null : selectors.getFirst().toLowerCase(Locale.ROOT));
    }

    /** Parses a DKIM-Signature header into its tags. */
    private static Map<String, String> parseSignatureTags(String raw) {
        String unfolded = unfold(raw);
        if (unfolded == null) return null;
        LinkedHashMap<String, String> tags = new LinkedHashMap<>();
        for (String part : unfolded.split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) continue;
            String name = part.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(separator + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            if (tags.putIfAbsent(name, value) != null) return null;
        }
        return Map.copyOf(tags);
    }

    /** Returns true if a header value is malformed or exceeds the bound. */
    private static boolean invalidHeader(String value, int maximum) {
        return value == null || value.length() > maximum
            || value.indexOf('\0') >= 0
            || value.chars().anyMatch(character ->
                Character.isISOControl(character)
                    && character != '\r' && character != '\n' && character != '\t');
    }

    /** Unfolds a folded header value, or returns null if malformed. */
    private static String unfold(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n') {
                int end = index;
                if (character == '\r' && end + 1 < value.length()
                        && value.charAt(end + 1) == '\n') {
                    end++;
                }
                if (end + 1 >= value.length()
                        || value.charAt(end + 1) != ' '
                            && value.charAt(end + 1) != '\t') {
                    return null;
                }
                result.append(' ');
                index = end;
                while (index + 1 < value.length()
                        && (value.charAt(index + 1) == ' '
                            || value.charAt(index + 1) == '\t')) {
                    index++;
                }
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    /** Returns all group-1 matches of the pattern in the value. */
    private static List<String> matches(Pattern pattern, String value) {
        ArrayList<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) matches.add(matcher.group(1));
        return List.copyOf(matches);
    }

    /** Returns the first value of a named header, if any. */
    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = values(headers, name);
        return values.isEmpty() ? null : values.getFirst();
    }

    /** Returns the values of a named header, case-insensitively. */
    private static List<String> values(Map<String, List<String>> headers, String name) {
        if (headers == null) return List.of();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null ? List.of() : new ArrayList<>(entry.getValue());
            }
        }
        return List.of();
    }

    /** Extracts the display name from a From header value. */
    private static String displayName(String from) {
        if (from == null) return null;
        int angle = from.lastIndexOf('<');
        if (angle < 0) return null;
        String display = from.substring(0, angle).trim();
        if (display.length() >= 2 && display.startsWith("\"") && display.endsWith("\"")) {
            display = display.substring(1, display.length() - 1).trim();
        }
        return display.isEmpty() ? null : display;
    }

    /** A DKIM identity (domain/selector) from a trusted pass. */
    private record DkimIdentity(String domain, String selector) {
        private boolean matches(String signatureDomain, String signatureSelector) {
            return signatureDomain != null
                && domain.equals(signatureDomain.toLowerCase(Locale.ROOT))
                && (selector == null || signatureSelector != null
                    && selector.equals(signatureSelector.toLowerCase(Locale.ROOT)));
        }
    }

    /** The derived DKIM pass and list-unsubscribe coverage facts. */
    private record DkimFacts(
            boolean pass,
            boolean coversListUnsubscribe,
            boolean coversListUnsubscribePost,
            String reason) {
        private static DkimFacts noTrustedResult() {
            return new DkimFacts(false, false, false, "NO_TRUSTED_RESULT");
        }

        private static DkimFacts ambiguous() {
            return new DkimFacts(false, false, false, "AMBIGUOUS_RESULT");
        }

        private static DkimFacts coverageMissing(boolean list, boolean post) {
            return new DkimFacts(true, list, post, "COVERAGE_MISSING");
        }
    }
}
