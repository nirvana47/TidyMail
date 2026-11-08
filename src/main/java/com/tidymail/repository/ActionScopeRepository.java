package com.tidymail.repository;

import com.tidymail.entity.ActionScope;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Looks up the saved scope behind a dashboard cleanup preview. */
public interface ActionScopeRepository extends JpaRepository<ActionScope, Long> {

    Optional<ActionScope> findByIdAndAccountId(Long id, Long accountId);
}
