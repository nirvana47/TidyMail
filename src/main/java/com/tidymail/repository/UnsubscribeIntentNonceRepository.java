package com.tidymail.repository;

import com.tidymail.entity.UnsubscribeIntentNonce;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persists and consumes unsubscribe intent nonces, guarding against replay.
 * <p>
 * Product functionality: the unsubscribe flow (intent nonce consumption).
 */
public interface UnsubscribeIntentNonceRepository
        extends JpaRepository<UnsubscribeIntentNonce, Long> {
    /**
     * Loads a nonce with a pessimistic write lock and consumes it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select nonce from UnsubscribeIntentNonce nonce
        where nonce.accountId = :accountId
          and nonce.nonceFingerprint = :fingerprint
        """)
    Optional<UnsubscribeIntentNonce> findForConsume(
        @Param("accountId") Long accountId,
        @Param("fingerprint") String fingerprint);
}
