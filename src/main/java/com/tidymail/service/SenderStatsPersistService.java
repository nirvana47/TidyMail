package com.tidymail.service;

import com.tidymail.dto.DomainRollup;
import com.tidymail.dto.SenderCard;
import com.tidymail.dto.SenderPageResponse;
import com.tidymail.entity.SenderStat;
import com.tidymail.repository.SenderStatRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists per-sender statistics snapshots and builds the Senders dashboard
 * response from stored stats, with sorting and domain rollups.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's persisted
 * sender stats and dashboard listing.
 */
@Service
public class SenderStatsPersistService {

    private final SenderStatRepository senderStatRepository;

    public SenderStatsPersistService(SenderStatRepository senderStatRepository) {
        this.senderStatRepository = senderStatRepository;
    }

    /** A per-sender delta to upsert into the stats snapshot. */
    public record SenderStatDelta(
            String fromAddress,
            String fromDisplayName,
            String fromDomain,
            int totalCount,
            int unreadCount,
            boolean hasListUnsubscribe,
            boolean hasPrecedenceBulk,
            Instant oldestMessageAt,
            Instant newestMessageAt,
            int replyCount
    ) {}

    /** Upserts a snapshot of sender stats for an account. */
    @Transactional
    public List<SenderStat> upsertSnapshot(Long accountId, List<SenderStatDelta> deltas) {
        Map<String, SenderStat> existingByAddress = new HashMap<>();
        for (SenderStat existing : senderStatRepository.findByAccountId(accountId)) {
            existingByAddress.put(existing.getFromAddress(), existing);
        }

        List<SenderStat> toSave = new ArrayList<>(deltas.size());
        for (SenderStatDelta delta : deltas) {
            SenderStat entity = existingByAddress.getOrDefault(delta.fromAddress(), new SenderStat());
            if (entity.getId() == null) {
                entity.setAccountId(accountId);
                entity.setFromAddress(delta.fromAddress());
                entity.setFromDomain(delta.fromDomain());
            }
            entity.setFromDisplayName(delta.fromDisplayName());
            entity.setTotalCount(delta.totalCount());
            entity.setUnreadCount(delta.unreadCount());
            entity.setHasListUnsubscribe(delta.hasListUnsubscribe());
            entity.setHasPrecedenceBulk(delta.hasPrecedenceBulk());
            entity.setOldestMessageAt(delta.oldestMessageAt());
            entity.setNewestMessageAt(delta.newestMessageAt());
            entity.setReplyCount(delta.replyCount());
            entity.setLastSyncedAt(Instant.now());
            toSave.add(entity);
        }
        return List.copyOf(senderStatRepository.saveAll(toSave));
    }

    /** Returns the account's sender stats sorted by count. */
    @Transactional(readOnly = true)
    public List<SenderStat> findByAccountSortedByCount(Long accountId) {
        return senderStatRepository.findByAccountIdOrderByTotalCountDesc(accountId);
    }

    /** Builds the dashboard response from stored stats. */
    @Transactional(readOnly = true)
    public SenderPageResponse dashboardSnapshot(Long accountId, String sort) {
        List<SenderStat> stats = new ArrayList<>(findByAccountSortedByCount(accountId));
        Comparator<SenderStat> order = switch (sort == null ? "" : sort) {
            case "oldestFirst" -> Comparator.comparing(
                SenderStat::getOldestMessageAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "scoreDesc" -> Comparator.comparingInt(this::bulkMarketingScore).reversed();
            default -> Comparator.comparing(
                SenderStat::getTotalCount, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        stats.sort(order.thenComparing(SenderStat::getFromAddress));

        List<SenderCard> cards = new ArrayList<>(stats.size());
        Map<String, DomainAccumulator> domains = new HashMap<>();
        for (SenderStat stat : stats) {
            cards.add(new SenderCard(
                stat.getFromAddress(),
                stat.getFromDisplayName(),
                stat.getFromDomain(),
                stat.getTotalCount(),
                stat.getUnreadCount(),
                stat.getOldestMessageAt(),
                stat.getNewestMessageAt(),
                stat.getReplyCount(),
                bulkMarketingScore(stat),
                Boolean.TRUE.equals(stat.getHasListUnsubscribe()),
                Boolean.TRUE.equals(stat.getHasPrecedenceBulk()),
                List.of()
            ));
            if (stat.getFromDomain() != null) {
                DomainAccumulator domain = domains.computeIfAbsent(
                    stat.getFromDomain(), ignored -> new DomainAccumulator());
                domain.messageCount += stat.getTotalCount();
                domain.senderCount++;
            }
        }

        List<DomainRollup> rollups = domains.entrySet().stream()
            .map(entry -> new DomainRollup(
                entry.getKey(), entry.getValue().messageCount, entry.getValue().senderCount))
            .sorted(Comparator.comparingInt(DomainRollup::countAcrossAllSenders).reversed())
            .toList();
        int totalMessages = stats.stream().mapToInt(SenderStat::getTotalCount).sum();
        return new SenderPageResponse(List.copyOf(cards), null, cards.size(), totalMessages, rollups);
    }

    /** Computes a 0-3 bulk-marketing score for a stored stat. */
    private int bulkMarketingScore(SenderStat stat) {
        int score = 0;
        if (Boolean.TRUE.equals(stat.getHasListUnsubscribe())) score++;
        if (Boolean.TRUE.equals(stat.getHasPrecedenceBulk())) score++;
        if (stat.getReplyCount() == 0
                && stat.getOldestMessageAt() != null
                && stat.getTotalCount() > 1
                && java.time.Duration.between(stat.getOldestMessageAt(), Instant.now()).toDays() > 7) {
            score++;
        }
        return Math.min(3, score);
    }

    /** Accumulates message/sender counts per domain. */
    private static final class DomainAccumulator {
        private int messageCount;
        private int senderCount;
    }
}
