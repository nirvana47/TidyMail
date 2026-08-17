package com.tidymail.repository;

import com.tidymail.entity.ProtectedSender;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and queries protected senders, which are excluded from automated
 * cleanup decisions.
 * <p>
 * Product functionality: the Senders decision surface and unsubscribe flow
 * (protected-sender lookups and overrides).
 */
public interface ProtectedSenderRepository extends JpaRepository<ProtectedSender, Long> {

    Optional<ProtectedSender> findByAccountIdAndSenderAddress(
        Long accountId, String senderAddress);

    List<ProtectedSender> findByAccountIdInAndSenderAddressIn(
        Collection<Long> accountIds, Collection<String> senderAddresses);

    @Modifying
    @Transactional
    long deleteByAccountIdAndSenderAddress(Long accountId, String senderAddress);
}
