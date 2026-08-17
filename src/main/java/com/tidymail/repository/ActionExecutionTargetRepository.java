package com.tidymail.repository;

import com.tidymail.entity.ActionExecutionTarget;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionExecutionTargetRepository
        extends JpaRepository<ActionExecutionTarget, Long> {
    List<ActionExecutionTarget> findByExecutionIdAndAccountIdOrderByScopeOrdinal(
        Long executionId, Long accountId);

    @Query("""
        select target from ActionExecutionTarget target, ActionExecution execution
        where target.executionId = execution.id
          and execution.actionId = :actionId
          and execution.accountId = :accountId
          and execution.operationKind = 'FORWARD'
          and target.status = 'SUCCEEDED'
        order by target.scopeOrdinal
        """)
    List<ActionExecutionTarget> findForwardSucceededTargets(
        @Param("actionId") Long actionId,
        @Param("accountId") Long accountId);
}
