package com.tidymail.repository;

import com.tidymail.entity.ProtectedOverrideAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persists and queries protected-sender overrides, recording when a sender's
 * protected status was changed.
 * <p>
 * Product functionality: the audit trail for protected-sender overrides on
 * the Senders decision surface.
 */
public interface ProtectedOverrideAuditRepository
        extends JpaRepository<ProtectedOverrideAudit, Long> {

    List<ProtectedOverrideAudit> findByAccountIdOrderByCreatedAtAsc(Long accountId);
}
