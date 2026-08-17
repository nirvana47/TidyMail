package com.tidymail.repository;

import com.tidymail.entity.ActionIntent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionIntentRepository extends JpaRepository<ActionIntent, Long> {
    Optional<ActionIntent> findByActionIdAndAccountId(Long actionId, Long accountId);
}
