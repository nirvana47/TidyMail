package com.tidymail.repository;

import com.tidymail.entity.UnsubscribeOutcomeEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnsubscribeOutcomeEventRepository
        extends JpaRepository<UnsubscribeOutcomeEvent, Long> {
    List<UnsubscribeOutcomeEvent> findByAttemptIdAndAccountIdOrderByOccurredAtAscIdAsc(
        Long attemptId, Long accountId);
}
