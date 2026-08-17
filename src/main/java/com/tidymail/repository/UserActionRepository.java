package com.tidymail.repository;

import com.tidymail.entity.UserAction;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persists and queries user actions, including pessimistic-lock lookups and
 * conflict/active-action queries.
 * <p>
 * Product functionality: user actions across the decision surfaces and
 * execution pipeline.
 */
public interface UserActionRepository extends JpaRepository<UserAction, Long> {

    long countByAccountId(Long accountId);

    Optional<UserAction> findByIdAndAccountId(Long id, Long accountId);
    Optional<UserAction> findByPublicIdAndAccountId(UUID publicId, Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select action from UserAction action "
        + "where action.id = :id and action.accountId = :accountId")
    Optional<UserAction> findForUpdateByIdAndAccountId(
        @Param("id") Long id, @Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select action from UserAction action "
        + "where action.publicId = :publicId and action.accountId = :accountId")
    Optional<UserAction> findForUpdateByPublicIdAndAccountId(
        @Param("publicId") UUID publicId, @Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select action from UserAction action "
        + "where action.accountId = :accountId "
        + "and action.actionScopeId = :scopeId "
        + "and action.actionType = :actionType "
        + "and action.status in ('QUEUED', 'BLOCKED', 'EXECUTING') "
        + "order by action.id asc")
    List<UserAction> findActiveForUpdate(
        @Param("accountId") Long accountId,
        @Param("scopeId") Long scopeId,
        @Param("actionType") String actionType);

    List<UserAction> findByAccountIdAndStatusOrderByCreatedAtAsc(Long accountId, String status);

    @Query("""
        select action from UserAction action
        where action.accountId = :accountId
          and action.actionScopeId is not null
          and action.status in ('QUEUED', 'BLOCKED')
        order by action.id asc
        """)
    List<UserAction> findConflictCandidates(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select action from UserAction action
        where action.accountId in :accountIds and action.publicId in :publicIds
        order by action.accountId asc, action.id asc
        """)
    List<UserAction> findAllForUpdateByAccountIdInAndPublicIdIn(
        @Param("accountIds") List<Long> accountIds,
        @Param("publicIds") List<UUID> publicIds);

    long countByAccountIdAndStatus(Long accountId, String status);

    long countByAccountIdInAndStatus(List<Long> accountIds, String status);

    void deleteByAccountIdIn(List<Long> accountIds);
}
