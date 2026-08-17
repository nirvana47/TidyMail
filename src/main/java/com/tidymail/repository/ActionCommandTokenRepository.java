package com.tidymail.repository;

import com.tidymail.entity.ActionCommandToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Queries and persists idempotency command tokens used to guard against duplicate action execution.
 * <p>Product functionality: the exact <em>action execution engine</em> (commands).
 */
public interface ActionCommandTokenRepository
        extends JpaRepository<ActionCommandToken, Long> {
    /**
     * Locks and returns the command token for the account/fingerprint so it can be consumed
     * atomically during action execution.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select token from ActionCommandToken token
        where token.accountId = :accountId
          and token.tokenFingerprint = :fingerprint
        """)
    Optional<ActionCommandToken> findForConsume(
        @Param("accountId") Long accountId,
        @Param("fingerprint") String fingerprint);
}
