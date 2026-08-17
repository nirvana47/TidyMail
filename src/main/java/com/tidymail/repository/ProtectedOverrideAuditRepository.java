package com.tidymail.repository;

import com.tidymail.entity.ProtectedOverrideAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProtectedOverrideAuditRepository
        extends JpaRepository<ProtectedOverrideAudit, Long> {

    List<ProtectedOverrideAudit> findByAccountIdOrderByCreatedAtAsc(Long accountId);
}
