package com.tidymail.repository;

import com.tidymail.entity.UnsubscribeOutcomeEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persists and queries unsubscribe outcome events for an attempt.
 * <p>
 * Product functionality: the unsubscribe flow (outcome tracking and
 * analytics).
 */
public interface UnsubscribeOutcomeEventRepository
        extends JpaRepository<UnsubscribeOutcomeEvent, Long> {
    List<UnsubscribeOutcomeEvent> findByAttemptIdAndAccountIdOrderByOccurredAtAscIdAsc(
        Long attemptId, Long accountId);
}
