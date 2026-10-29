package com.tidymail.repository;

import com.tidymail.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and queries user accounts, including OAuth credential
 * invalidation/activation and cascade deletion.
 * <p>
 * Product functionality: user accounts and OAuth lifecycle across the
 * product.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    @Query(value="SELECT id FROM user_accounts WHERE owner_key=:owner ORDER BY id FOR UPDATE",nativeQuery=true)
    List<Long> lockOwnerForDeletion(@Param("owner") String owner);

    @Query(value="""
        SELECT count(*) FROM cleanup_jobs j JOIN user_accounts a ON a.id=j.account_id
        WHERE a.owner_key=:owner AND (j.worker_id IS NOT NULL OR EXISTS
        (SELECT 1 FROM cleanup_job_targets t WHERE t.job_id=j.public_id
         AND t.state IN ('QUEUED','DISPATCH_INTENT')))
        """,nativeQuery=true)
    long pendingCleanupForDeletion(@Param("owner") String owner);

    Optional<UserAccount> findByProviderAndProviderAccountId(String provider, String providerAccountId);

    Optional<UserAccount> findByEmailAndProvider(String email, String provider);

    Optional<UserAccount> findByEmailAndProviderIgnoreCase(String email, String provider);

    List<UserAccount> findAllByEmailIgnoreCaseAndProviderOrderByIdAsc(String email, String provider);

    List<UserAccount> findAllByOwnerKeyOrderByIdAsc(String ownerKey);

    Optional<UserAccount> findByIdAndOwnerKey(Long id, String ownerKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update UserAccount account
           set account.oauthStatus = com.tidymail.entity.UserAccount.OAuthStatus.REAUTH_REQUIRED,
               account.oauthErrorCode = :errorCode,
               account.oauthErrorAt = :errorAt
         where account.provider = :provider
           and account.providerAccountId = :providerAccountId
        """)
    int invalidateOAuthCredentials(@Param("provider") String provider,
                                   @Param("providerAccountId") String providerAccountId,
                                   @Param("errorCode") String errorCode,
                                   @Param("errorAt") Instant errorAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update UserAccount account
           set account.oauthStatus = com.tidymail.entity.UserAccount.OAuthStatus.ACTIVE,
               account.oauthErrorCode = null,
               account.oauthErrorAt = null
         where account.provider = :provider
           and account.providerAccountId = :providerAccountId
        """)
    int activateOAuthCredentials(@Param("provider") String provider,
                                 @Param("providerAccountId") String providerAccountId);

    List<UserAccount> findAllByIdInOrderByIdAsc(List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account "
        + "where account.id in :ids order by account.id asc")
    List<UserAccount> findAllForUpdateByIdInOrderByIdAsc(@Param("ids") List<Long> ids);

    /**
     * Cascade delete a user account by (email, provider) tuple.  Because both
     * {@code sender_stats.user_account_id} and {@code user_actions.user_account_id}
     * have DB-level {@code ON DELETE CASCADE} in V1 schema, removing the user_accounts
     * row automatically wipes associated rows in both child tables without needing any
     * Java-side cascade.  Uses Spring Data derived-query naming: Spring builds DELETE
     * FROM user_accounts WHERE email ILIKE ? AND provider = ?.  {@code @Modifying}
     * + {@code @Transactional} are required for mutation queries.
     */
    @Modifying
    @Transactional
    void deleteByEmailAndProviderIgnoreCase(String email, String provider);

    @Modifying
    @Transactional
    void deleteByProviderAndProviderAccountId(String provider, String providerAccountId);

    @Modifying
    @Transactional
    long deleteByOwnerKey(String ownerKey);
}
