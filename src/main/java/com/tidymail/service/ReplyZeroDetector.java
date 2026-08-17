package com.tidymail.service;

import com.tidymail.dto.ReplyZeroItem;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.MailThreadSummary;
import com.tidymail.util.Rfc5322Util;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Detects personal messages that may be awaiting a reply (the "Reply Zero"
 * queue) by filtering out bulk/automated senders and confirming, via a provider
 * thread lookup, that the user has not yet participated in the thread.
 *
 * <p>Product functionality supported: the <em>Reply Zero</em> queue, which
 * surfaces personal emails awaiting the user's reply.
 */
@Service
public class ReplyZeroDetector {

    public static final Set<String> BULK_ESP_DOMAINS = Set.of(
            "mailchimp.com", "sendgrid.net", "amazonses.com", "mailgun.net",
            "constantcontact.com", "aweber.com", "convertkit.com", "hubspotemail.net",
            "sendinblue.com", "getresponse-mail.com", "postmarkapp.com",
            "sparkpostmail.com", "mailjet.com", "sendgrid.com", "yahoo-mail.com"
    );

    /** Analyzes messages and returns the reply-required candidates. */
    public List<ReplyZeroItem> analyze(
            List<MailMessage> messages,
            Map<String, SenderAggregator.SenderAccumulator> senderAggregations,
            UserAccount me,
            MailProvider provider,
            int olderThanDays,
            Instant now) {

        Map<String, MailMessage> newestPerThreadId = new HashMap<>();
        for (MailMessage m : messages) {
            String tid = m.threadId();
            if (tid == null) continue;
            MailMessage existing = newestPerThreadId.get(tid);
            if (existing == null) {
                newestPerThreadId.put(tid, m);
            } else {
                Instant existingRecv = existing.receivedAt();
                Instant newRecv = m.receivedAt();
                if (newRecv != null && (existingRecv == null || newRecv.isAfter(existingRecv))) {
                    newestPerThreadId.put(tid, m);
                }
            }
        }

        List<ReplyZeroItem> result = new ArrayList<>();
        int fetchedDeepDives = 0;

        for (MailMessage candidate : newestPerThreadId.values()) {
            Instant recvAt = candidate.receivedAt();
            if (recvAt == null) continue;
            long age = Duration.between(recvAt, now).toDays();
            if (age < olderThanDays) continue;

            String fromRfc = candidate.from().orElse(null);
            if (fromRfc == null) continue;
            String addrSpec = Rfc5322Util.extractAddrSpec(fromRfc);
            if (addrSpec == null) continue;

            SenderAggregator.SenderAccumulator acc = senderAggregations.get(addrSpec);
            int score = SenderAggregator.bulkMarketingScore(acc, now);
            if (score >= 1) continue;

            String domain = Rfc5322Util.extractDomain(fromRfc);
            if (domain == null) continue;

            boolean isBulkEsp = false;
            for (String espDomain : BULK_ESP_DOMAINS) {
                if (domain.endsWith("." + espDomain) || domain.equals(espDomain)) {
                    isBulkEsp = true;
                    break;
                }
            }
            if (isBulkEsp) continue;

            String toHeader = candidate.to().orElse(null);
            if (toHeader == null || toHeader.isBlank()) continue;
            Set<String> toSlds = new HashSet<>();
            String[] toParts = toHeader.split(",");
            for (String toPart : toParts) {
                String toDomain = Rfc5322Util.extractDomain(toPart.trim());
                if (toDomain != null) {
                    String s = sld(toDomain);
                    if (s != null) toSlds.add(s);
                }
            }
            if (toSlds.isEmpty()) continue;
            String fromSld = sld(domain);
            if (fromSld == null || !toSlds.contains(fromSld)) continue;

            if (fetchedDeepDives >= 50) break;
            MailThreadSummary summary = provider.getThreadMetadata(me, candidate.threadId());
            fetchedDeepDives++;

            String myEmail = me.getEmail();
            boolean meParticipated = false;
            if (summary.participantEmails() != null) {
                for (String p : summary.participantEmails()) {
                    String pAddr = Rfc5322Util.extractAddrSpec(p);
                    if (pAddr != null && pAddr.equalsIgnoreCase(myEmail)) {
                        meParticipated = true;
                        break;
                    }
                }
            }
            if (meParticipated) continue;

            int participantCount = summary.participantEmails() == null ? 0 : summary.participantEmails().size();
            if (meParticipated) participantCount = Math.max(0, participantCount - 1);

            result.add(new ReplyZeroItem(
                    candidate.threadId(),
                    addrSpec,
                    acc == null ? null : acc.fromDisplayName,
                    domain,
                    candidate.subject().orElse(null),
                    recvAt,
                    age,
                    participantCount,
                    false
            ));
        }

        result.sort(Comparator.comparing((ReplyZeroItem i) -> i.lastMessageAt(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        return List.copyOf(result);
    }

    /** Extracts the second-level domain from a domain. */
    private static String sld(String domain) {
        if (domain == null) return null;
        String[] parts = domain.split("\\.");
        if (parts.length < 2) return null;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
