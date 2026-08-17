package com.tidymail.repository;

import com.tidymail.entity.UnsubscribeAttempt;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persists and queries unsubscribe attempts, including pessimistic-lock
 * lookups for concurrent processing.
 * <p>
 * Product functionality: the unsubscribe flow (attempt tracking and
 * concurrency).
 */
public interface UnsubscribeAttemptRepository
        extends JpaRepository<UnsubscribeAttempt, Long> {
    Optional<UnsubscribeAttempt> findByIdAndAccountId(Long id, Long accountId);

    /**
     * Loads an attempt with a pessimistic write lock for concurrent update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select attempt from UnsubscribeAttempt attempt
        where attempt.id = :id and attempt.accountId = :accountId
        """)
    Optional<UnsubscribeAttempt> findForUpdateByIdAndAccountId(
        @Param("id") Long id,
        @Param("accountId") Long accountId);

    List<UnsubscribeAttempt> findByAccountIdAndCandidateFingerprintOrderByCreatedAtDescIdDesc(
        Long accountId, String candidateFingerprint);
}
