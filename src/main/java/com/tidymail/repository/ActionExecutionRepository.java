package com.tidymail.repository;

import com.tidymail.entity.ActionExecution;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionExecutionRepository
        extends JpaRepository<ActionExecution, Long> {
    Optional<ActionExecution> findByPublicIdAndAccountId(UUID publicId, Long accountId);
    Optional<ActionExecution> findByActionIdAndOperationKind(
        Long actionId, String operationKind);
    Optional<ActionExecution> findByAccountIdAndIdempotencyFingerprint(
        Long accountId, String idempotencyFingerprint);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select execution from ActionExecution execution
        where execution.id = :id and execution.accountId = :accountId
        """)
    Optional<ActionExecution> findForUpdate(
        @Param("id") Long id, @Param("accountId") Long accountId);
}
