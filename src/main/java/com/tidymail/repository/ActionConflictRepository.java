package com.tidymail.repository;

import com.tidymail.entity.ActionConflict;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Queries and persists action conflicts detected between competing cleanup actions.
 * <p>Product functionality: the exact <em>action execution engine</em> (conflicts).
 */
public interface ActionConflictRepository
        extends JpaRepository<ActionConflict, Long> {
    List<ActionConflict> findByAccountIdAndStatusOrderByCreatedAtAsc(
        Long accountId, String status);
    boolean existsByAccountIdAndStatusAndLowerActionIdOrAccountIdAndStatusAndHigherActionId(
        Long accountId, String status, Long lowerActionId,
        Long otherAccountId, String otherStatus, Long higherActionId);

    Optional<ActionConflict> findByAccountIdAndLowerActionIdAndHigherActionId(
        Long accountId, Long lowerActionId, Long higherActionId);

    Optional<ActionConflict> findByPublicIdAndAccountId(UUID publicId, Long accountId);

    /**
     * Locks and returns an open conflict by public id across the given accounts for atomic resolution.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select conflict from ActionConflict conflict
        where conflict.publicId = :publicId
          and conflict.accountId in :accountIds
        """)
    Optional<ActionConflict> findForUpdateByPublicIdAndAccountIdIn(
        @Param("publicId") UUID publicId,
        @Param("accountIds") List<Long> accountIds);

    /**
     * Returns the open conflicts involving the given action, oldest first.
     */
    @Query("""
        select conflict from ActionConflict conflict
        where conflict.accountId = :accountId and conflict.status = 'OPEN'
          and (conflict.lowerActionId = :actionId
               or conflict.higherActionId = :actionId)
        order by conflict.id asc
        """)
    List<ActionConflict> findOpenByAction(
        @Param("accountId") Long accountId, @Param("actionId") Long actionId);
}
