package com.tidymail.repository;

import com.tidymail.entity.SenderStat;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SenderStatRepository extends JpaRepository<SenderStat, Long> {

    long countByAccountId(Long accountId);

    List<SenderStat> findByAccountIdOrderByTotalCountDesc(Long accountId);

    Optional<SenderStat> findByAccountIdAndFromAddress(Long accountId, String fromAddress);

    List<SenderStat> findByAccountId(Long accountId);

    void deleteByAccountIdIn(List<Long> accountIds);
}
