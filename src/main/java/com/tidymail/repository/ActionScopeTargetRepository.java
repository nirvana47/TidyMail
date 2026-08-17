package com.tidymail.repository;

import com.tidymail.entity.ActionScopeTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Queries and persists the individual targets that make up an action scope.
 * <p>Product functionality: the exact <em>action execution engine</em> (scope targets).
 */
public interface ActionScopeTargetRepository
        extends JpaRepository<ActionScopeTarget, Long> {
    List<ActionScopeTarget> findByScopeIdAndAccountIdOrderByScopeOrdinal(
        Long scopeId, Long accountId);
    Optional<ActionScopeTarget> findByScopeIdAndAccountIdAndScopeOrdinal(
        Long scopeId, Long accountId, Integer scopeOrdinal);

    /**
     * Counts the targets shared exactly between two scopes for overlap analysis.
     */
    @Query("""
        select count(leftTarget)
        from ActionScopeTarget leftTarget, ActionScopeTarget rightTarget
        where leftTarget.accountId = :accountId
          and rightTarget.accountId = :accountId
          and leftTarget.scopeId = :leftScopeId
          and rightTarget.scopeId = :rightScopeId
          and leftTarget.targetFingerprint = rightTarget.targetFingerprint
        """)
    long countExactOverlap(
        @Param("accountId") Long accountId,
        @Param("leftScopeId") Long leftScopeId,
        @Param("rightScopeId") Long rightScopeId);
}
