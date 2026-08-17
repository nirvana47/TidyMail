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

@Service
@Transactional(readOnly = true)
public class MessageMetadataQueryService {

    public record Freshness(long messageCount, Instant newestReceivedAt, Instant lastSyncedAt) {}

    private final MessageMetadataRepository repository;

    public MessageMetadataQueryService(MessageMetadataRepository repository) {
        this.repository = repository;
    }

    public Page<MessageMetadata> page(Collection<Long> authorizedAccountIds, int page, int size) {
        return repository.findByAccountIdInOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    public Freshness freshness(Collection<Long> authorizedAccountIds) {
        MessageMetadataRepository.FreshnessSummary summary =
            repository.summarize(requireScope(authorizedAccountIds));
        return new Freshness(
            summary.getMessageCount(), summary.getNewestReceivedAt(), summary.getLastSyncedAt());
    }

    public List<MessageMetadata> exactProviderIds(
            Collection<Long> authorizedAccountIds, Collection<String> providerMessageIds) {
        if (providerMessageIds == null || providerMessageIds.isEmpty()) return List.of();
        return repository.findByAccountIdInAndProviderMessageIdIn(
            requireScope(authorizedAccountIds), List.copyOf(providerMessageIds));
    }

    public Page<MessageMetadata> bySender(
            Collection<Long> authorizedAccountIds, String fromAddress, int page, int size) {
        return repository.findByAccountIdInAndFromAddressOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), fromAddress,
            PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    public Page<MessageMetadata> byDomain(
            Collection<Long> authorizedAccountIds, String fromDomain, int page, int size) {
        return repository.findByAccountIdInAndFromDomainOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), fromDomain,
            PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    public Page<MessageMetadata> unread(
            Collection<Long> authorizedAccountIds, int page, int size) {
        return repository.findByAccountIdInAndUnreadOrderByReceivedAtDescIdDesc(
            requireScope(authorizedAccountIds), true,
            PageRequest.of(Math.max(0, page), boundedSize(size)));
    }

    private static List<Long> requireScope(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty() || accountIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("At least one authorized account ID is required");
        }
        return List.copyOf(accountIds);
    }

    private static int boundedSize(int size) {
        return Math.max(1, Math.min(100, size));
    }
}
