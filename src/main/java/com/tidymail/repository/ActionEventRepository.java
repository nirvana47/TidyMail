package com.tidymail.repository;

import com.tidymail.entity.ActionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Queries and persists the event log emitted during action execution.
 * <p>Product functionality: the exact <em>action execution engine</em> (events).
 */
public interface ActionEventRepository extends JpaRepository<ActionEvent, Long> {
    List<ActionEvent> findByActionIdAndAccountIdOrderByOccurredAtAscIdAsc(
        Long actionId, Long accountId);
}
