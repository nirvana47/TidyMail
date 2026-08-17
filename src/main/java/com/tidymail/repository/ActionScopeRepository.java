package com.tidymail.repository;

import com.tidymail.entity.ActionScope;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Queries and persists the scopes that cleanup actions operate over.
 * <p>Product functionality: the exact <em>action execution engine</em> (scopes).
 */
public interface ActionScopeRepository extends JpaRepository<ActionScope, Long> {

    Optional<ActionScope> findByAccountIdAndScopeHash(Long accountId, String scopeHash);

    Optional<ActionScope> findByIdAndAccountId(Long id, Long accountId);

    /**
     * Locks and returns a scope by id and account so it can be updated atomically.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select scope from ActionScope scope "
        + "where scope.id = :id and scope.accountId = :accountId")
    Optional<ActionScope> findForUpdateByIdAndAccountId(
        @Param("id") Long id, @Param("accountId") Long accountId);

    void deleteByAccountIdIn(Collection<Long> accountIds);
}
