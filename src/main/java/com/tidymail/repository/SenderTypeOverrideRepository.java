package com.tidymail.repository;

import com.tidymail.entity.SenderTypeOverride;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persists and queries manual sender-type overrides.
 * <p>
 * Product functionality: sender type overrides on the Senders decision
 * surface.
 */
public interface SenderTypeOverrideRepository
        extends JpaRepository<SenderTypeOverride, Long> {

    Optional<SenderTypeOverride> findByAccountIdAndSenderAddress(
        Long accountId, String senderAddress);

    List<SenderTypeOverride> findAllByAccountIdAndSenderAddressIn(
        Long accountId, Collection<String> senderAddresses);

    long deleteByAccountIdAndSenderAddress(Long accountId, String senderAddress);
}
