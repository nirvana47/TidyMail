package com.tidymail.repository;

import com.tidymail.entity.ActionIntent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Queries and persists the intent behind each cleanup action.
 * <p>Product functionality: the exact <em>action execution engine</em> (intents).
 */
public interface ActionIntentRepository extends JpaRepository<ActionIntent, Long> {
    Optional<ActionIntent> findByActionIdAndAccountId(Long actionId, Long accountId);
}
