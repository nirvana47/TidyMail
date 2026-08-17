package com.tidymail.repository;

import com.tidymail.entity.ActionCommandToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionCommandTokenRepository
        extends JpaRepository<ActionCommandToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select token from ActionCommandToken token
        where token.accountId = :accountId
          and token.tokenFingerprint = :fingerprint
        """)
    Optional<ActionCommandToken> findForConsume(
        @Param("accountId") Long accountId,
        @Param("fingerprint") String fingerprint);
}
