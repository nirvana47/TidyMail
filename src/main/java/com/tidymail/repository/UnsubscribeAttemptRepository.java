package com.tidymail.repository;

import com.tidymail.entity.UnsubscribeAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads retained unsubscribe attempts for an authorized account. */
public interface UnsubscribeAttemptRepository extends JpaRepository<UnsubscribeAttempt, Long> {
    Optional<UnsubscribeAttempt> findByIdAndAccountId(Long id, Long accountId);
}
