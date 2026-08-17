package com.tidymail.repository;

import com.tidymail.entity.MessageMetadata;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Queries and persists the metadata-only representation of synced messages.
 * <p>Product functionality: the metadata-only <em>mailbox sync</em> (message metadata).
 */
public interface MessageMetadataRepository extends JpaRepository<MessageMetadata, Long> {

    interface FreshnessSummary {
        long getMessageCount();
        Instant getNewestReceivedAt();
        Instant getLastSyncedAt();
    }

    List<MessageMetadata> findByAccountIdAndProviderMessageIdIn(
        Long accountId, Collection<String> providerMessageIds);

    Page<MessageMetadata> findByAccountIdInOrderByReceivedAtDescIdDesc(
        Collection<Long> accountIds, Pageable pageable);

    List<MessageMetadata> findByAccountIdInAndProviderMessageIdIn(
        Collection<Long> accountIds, Collection<String> providerMessageIds);

    Page<MessageMetadata> findByAccountIdInAndFromAddressOrderByReceivedAtDescIdDesc(
        Collection<Long> accountIds, String fromAddress, Pageable pageable);

    Page<MessageMetadata> findByAccountIdInAndFromDomainOrderByReceivedAtDescIdDesc(
        Collection<Long> accountIds, String fromDomain, Pageable pageable);

    Page<MessageMetadata> findByAccountIdInAndUnreadOrderByReceivedAtDescIdDesc(
        Collection<Long> accountIds, Boolean unread, Pageable pageable);

    long countByAccountIdIn(Collection<Long> accountIds);

    /**
     * Aggregates message count and newest received/last-synced timestamps for freshness reporting.
     */
    @Query("""
        select count(m) as messageCount,
               max(m.receivedAt) as newestReceivedAt,
               max(m.lastSyncedAt) as lastSyncedAt
        from MessageMetadata m
        where m.accountId in :accountIds
        """)
    FreshnessSummary summarize(@Param("accountIds") Collection<Long> accountIds);

    void deleteByAccountIdIn(Collection<Long> accountIds);
}
