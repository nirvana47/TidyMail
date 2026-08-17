package com.tidymail.repository;

import com.tidymail.entity.SyncProgress;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persists and queries mailbox sync progress per account and job type.
 * <p>
 * Product functionality: sync progress tracking for mailbox synchronization.
 */
public interface SyncProgressRepository extends JpaRepository<SyncProgress, Long> {

    Optional<SyncProgress> findTopByAccountIdAndJobTypeOrderByIdDesc(Long accountId, String jobType);

    Optional<SyncProgress> findTopByAccountIdAndJobTypeAndStatusOrderByIdDesc(
        Long accountId, String jobType, String status);

    Optional<SyncProgress> findTopByAccountIdAndJobTypeAndLaneAndStatusOrderByIdDesc(
        Long accountId, String jobType, String lane, String status);

    List<SyncProgress> findTop2ByAccountIdAndJobTypeOrderByIdDesc(Long accountId, String jobType);

    List<SyncProgress> findByAccountIdInAndJobTypeOrderByIdDesc(
        List<Long> accountIds, String jobType);

    void deleteByAccountIdIn(List<Long> accountIds);
}
