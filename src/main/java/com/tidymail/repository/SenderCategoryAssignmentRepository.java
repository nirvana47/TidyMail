package com.tidymail.repository;

import com.tidymail.entity.SenderCategoryAssignment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persists and queries sender-to-category assignments.
 * <p>
 * Product functionality: sender categories on the Senders decision surface.
 */
public interface SenderCategoryAssignmentRepository
        extends JpaRepository<SenderCategoryAssignment, Long> {

    Optional<SenderCategoryAssignment> findByAccountIdAndSenderAddress(
        Long accountId, String senderAddress);

    List<SenderCategoryAssignment> findAllByAccountIdAndSenderAddressIn(
        Long accountId, Collection<String> senderAddresses);

    List<SenderCategoryAssignment> findAllByAccountIdAndCategoryIdOrderBySenderAddressAsc(
        Long accountId, Long categoryId);

    long deleteByAccountIdAndSenderAddress(Long accountId, String senderAddress);
}
