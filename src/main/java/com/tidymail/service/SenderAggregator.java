package com.tidymail.service;

import com.tidymail.dto.DomainRollup;
import com.tidymail.dto.SenderCard;
import com.tidymail.dto.SenderPageResponse;
import com.tidymail.mail.MailMessage;
import com.tidymail.util.Rfc5322Util;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Aggregates raw messages into per-sender accumulators, computes a bulk-marketing
 * score, persists sender snapshots, and builds the Senders page response with
 * sorting and domain rollups.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's aggregation,
 * scoring, and listing.
 */
@Service
public class SenderAggregator {

    private final SenderStatsPersistService senderStatsPersistService;

    public SenderAggregator(SenderStatsPersistService senderStatsPersistService) {
        this.senderStatsPersistService = senderStatsPersistService;
    }

    /** Aggregates messages into per-sender accumulators. */
    public AggregationResult aggregate(List<MailMessage> messages) {
        Map<String, SenderAccumulator> perSender = new LinkedHashMap<>();
        for (MailMessage m : messages) {
            String fromRfc = m.from().orElse(null);
            if (fromRfc == null) continue;
            String addrSpec = Rfc5322Util.extractAddrSpec(fromRfc);
            if (addrSpec == null) continue;
            String domain = Rfc5322Util.extractDomain(fromRfc);
            if (domain == null) continue;
            SenderAccumulator acc = perSender.computeIfAbsent(addrSpec, k -> {
                SenderAccumulator fresh = new SenderAccumulator();
                fresh.fromAddress = addrSpec;
                fresh.fromDisplayName = extractDisplayOnly(fromRfc);
                fresh.fromDomain = domain;
                return fresh;
            });
            acc.accumulate(m);
        }
        return new AggregationResult(perSender, messages.size());
    }

    /** Computes a 0-3 bulk-marketing score for a sender. */
    public static int bulkMarketingScore(SenderAccumulator acc, Instant now) {
        if (acc == null) return 0;
        int sum = 0;
        if (acc.hasListUnsubscribe) sum++;
        if (acc.hasPrecedenceBulk) sum++;
        if (acc.replyCount == 0 && acc.oldestMessageAt != null
                && Duration.between(acc.oldestMessageAt, now).toDays() > 7
                && acc.totalCount > 1) {
            sum++;
        }
        return Math.min(3, Math.max(0, sum));
    }

    /** Persists the aggregation as a sender-stats snapshot. */
    public int persistSnapshot(Long accountId, AggregationResult agg) {
        List<SenderStatsPersistService.SenderStatDelta> deltas = new ArrayList<>(agg.perSender().size());
        for (SenderAccumulator acc : agg.perSender().values()) {
            deltas.add(new SenderStatsPersistService.SenderStatDelta(
                    acc.fromAddress,
                    acc.fromDisplayName,
                    acc.fromDomain,
                    acc.totalCount,
                    acc.unreadCount,
                    acc.hasListUnsubscribe,
                    acc.hasPrecedenceBulk,
                    acc.oldestMessageAt,
                    acc.newestMessageAt,
                    acc.replyCount
            ));
        }
        senderStatsPersistService.upsertSnapshot(accountId, deltas);
        return deltas.size();
    }

    /** Builds the sender page response with sorting and domain rollups. */
    public SenderPageResponse toPageResponse(AggregationResult agg, String nextCursor, String sort) {
        List<SenderAccumulator> list = new ArrayList<>(agg.perSender().values());
        Comparator<SenderAccumulator> primary;
        switch (sort == null ? "" : sort) {
            case "oldestFirst":
                primary = (a, b) -> {
                    Instant ai = a.oldestMessageAt;
                    Instant bi = b.oldestMessageAt;
                    if (ai == null && bi == null) return 0;
                    if (ai == null) return 1;
                    if (bi == null) return -1;
                    return ai.compareTo(bi);
                };
                break;
            case "scoreDesc":
                primary = Comparator.comparingInt((SenderAccumulator a) -> -bulkMarketingScore(a, Instant.now()));
                break;
            case "countDesc":
            default:
                primary = Comparator.comparingInt((SenderAccumulator a) -> -a.totalCount);
                break;
        }
        Comparator<SenderAccumulator> cmp = primary.thenComparing(a -> a.fromAddress == null ? "" : a.fromAddress);
        list.sort(cmp);

        Instant now = Instant.now();
        List<SenderCard> items = new ArrayList<>(list.size());
        for (SenderAccumulator acc : list) {
            items.add(new SenderCard(
                    acc.fromAddress,
                    acc.fromDisplayName,
                    acc.fromDomain,
                    acc.totalCount,
                    acc.unreadCount,
                    acc.oldestMessageAt,
                    acc.newestMessageAt,
                    acc.replyCount,
                    bulkMarketingScore(acc, now),
                    acc.hasListUnsubscribe,
                    acc.hasPrecedenceBulk,
                    List.copyOf(acc.sampleMessageIds)
            ));
        }

        Map<String, List<SenderAccumulator>> byDomain = list.stream()
                .filter(a -> a.fromDomain != null)
                .collect(Collectors.groupingBy(a -> a.fromDomain));
        List<DomainRollup> domainRollups = new ArrayList<>(byDomain.size());
        for (Map.Entry<String, List<SenderAccumulator>> e : byDomain.entrySet()) {
            int count = 0;
            for (SenderAccumulator a : e.getValue()) count += a.totalCount;
            domainRollups.add(new DomainRollup(e.getKey(), count, e.getValue().size()));
        }
        domainRollups.sort((x, y) -> Integer.compare(y.countAcrossAllSenders(), x.countAcrossAllSenders()));

        return new SenderPageResponse(
                List.copyOf(items),
                nextCursor,
                agg.perSender().size(),
                agg.totalMessagesScanned(),
                List.copyOf(domainRollups)
        );
    }

    /** Extracts the display name from an RFC-5322 address. */
    private static String extractDisplayOnly(String rfc5322Address) {
        if (rfc5322Address == null) return null;
        int lt = rfc5322Address.indexOf('<');
        if (lt < 0) return null;
        String namePart = rfc5322Address.substring(0, lt).trim();
        if (namePart.startsWith("\"") && namePart.endsWith("\"") && namePart.length() >= 2) {
            namePart = namePart.substring(1, namePart.length() - 1).trim();
        }
        return namePart.isEmpty() ? null : namePart;
    }

    /** Accumulates per-sender statistics across messages. */
    public static final class SenderAccumulator {
        public String fromAddress;
        public String fromDisplayName;
        public String fromDomain;
        public int totalCount;
        public int unreadCount;
        public boolean hasListUnsubscribe;
        public boolean hasPrecedenceBulk;
        public Instant oldestMessageAt;
        public Instant newestMessageAt;
        public int replyCount;
        public final List<String> sampleMessageIds = new ArrayList<>(5);

        /** Adds a message's facts to this sender's statistics. */
        public void accumulate(MailMessage m) {
            totalCount++;
            if (m.isUnread()) unreadCount++;

            if (!hasListUnsubscribe) {
                String lu = m.getHeaderSingle("List-Unsubscribe").orElse(null);
                if (lu != null && !lu.isEmpty()) {
                    if (lu.contains("mailto:") || lu.contains("<") || lu.contains("https://")) {
                        hasListUnsubscribe = true;
                    }
                }
            }

            if (!hasPrecedenceBulk) {
                String prec = m.getHeaderSingle("Precedence").orElse(null);
                if (prec != null && prec.equalsIgnoreCase("bulk")) {
                    hasPrecedenceBulk = true;
                }
            }

            Instant recv = m.receivedAt();
            if (recv != null) {
                if (oldestMessageAt == null || recv.isBefore(oldestMessageAt)) {
                    oldestMessageAt = recv;
                }
                if (newestMessageAt == null || recv.isAfter(newestMessageAt)) {
                    newestMessageAt = recv;
                }
            }

            if (sampleMessageIds.size() < 5) {
                sampleMessageIds.add(m.id());
            }
        }
    }

    /** The aggregated senders and total messages scanned. */
    public record AggregationResult(
            Map<String, SenderAccumulator> perSender,
            int totalMessagesScanned
    ) {}
}
