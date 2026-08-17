package com.tidymail.repository;

import com.tidymail.entity.SenderCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SenderCategoryRepository extends JpaRepository<SenderCategory, Long> {

    List<SenderCategory> findAllByAccountIdOrderByCreatedAtAscIdAsc(Long accountId);

    Optional<SenderCategory> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByAccountIdAndNameKey(Long accountId, String nameKey);
}
