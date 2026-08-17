package com.tidymail.service;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.repository.MessageMetadataRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries over indexed message metadata: paged listings, freshness
 * summary, exact provider IDs, and sender/domain/unread lookups.
 *
 * <p>Product functionality supported: the metadata-only sync's read surfaces and
 * the Senders/Unread flows' underlying message queries.
 */
@Service
@Transactional(readOnly = true)
public class MessageMetadataQueryService {

    /** Freshness summary of the indexed metadata. */
    public record Freshness(long messageCount, Instant newestReceivedAt, Instant lastSyncedAt) {}

    private final MessageMetadataRepository repository;

    public MessageMetadataQueryService(MessageMetadataRepository repository) {
        this.repository = repository;
    }

    /** Returns a paged listing of messages for the accounts. */
    public Page<MessageMetadata> page(Collection<Long> authorizedAccountIds, int page, int size) {
        return repository.findByAccountIdInOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    /** Returns the freshness summary for the accounts. */
    public Freshness freshness(Collection<Long> authorizedAccountIds) {
        MessageMetadataRepository.FreshnessSummary summary =
            repository.summarize(requireScope(authorizedAccountIds));
        return new Freshness(
            summary.getMessageCount(), summary.getNewestReceivedAt(), summary.getLastSyncedAt());
    }

    /** Returns messages matching the exact provider IDs. */
    public List<MessageMetadata> exactProviderIds(
            Collection<Long> authorizedAccountIds, Collection<String> providerMessageIds) {
        if (providerMessageIds == null || providerMessageIds.isEmpty()) return List.of();
        return repository.findByAccountIdInAndProviderMessageIdIn(
            requireScope(authorizedAccountIds), List.copyOf(providerMessageIds));
    }

    /** Returns a paged listing of messages from a sender. */
    public Page<MessageMetadata> bySender(
            Collection<Long> authorizedAccountIds, String fromAddress, int page, int size) {
        return repository.findByAccountIdInAndFromAddressOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), fromAddress,
            PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    /** Returns a paged listing of messages from a domain. */
    public Page<MessageMetadata> byDomain(
            Collection<Long> authorizedAccountIds, String fromDomain, int page, int size) {
        return repository.findByAccountIdInAndFromDomainOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), fromDomain,
            PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    /** Returns a paged listing of unread messages. */
    public Page<MessageMetadata> unread(
            Collection<Long> authorizedAccountIds, int page, int size) {
        return repository.findByAccountIdInAndUnreadOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), true,
            PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    /** Requires a non-empty set of authorized account IDs. */
    private static List<Long> requireScope(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty() || accountIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("At least one authorized account ID is required");
        }
        return List.copyOf(accountIds);
    }

    /** Bounds a page size between 1 and 100. */
    private static int boundedSize(int size) {
        return Math.max(1, Math.min(100, size));
    }
}
