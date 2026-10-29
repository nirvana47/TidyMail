package com.tidymail.service;

import com.tidymail.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes all owned accounts' data for a user (owner key), used for account
 * deletion and data-privacy requests.
 *
 * <p>Product functionality supported: user account/data deletion.
 */
@Service
public class UserDataDeletionService {

    private final UserAccountRepository accounts;
    private final DashboardJobService jobs;

    public UserDataDeletionService(UserAccountRepository accounts,DashboardJobService jobs) {
        this.accounts = accounts;
        this.jobs = jobs;
    }

    /** Deletes all accounts owned by the key and verifies removal. */
    @Transactional(noRollbackFor=org.springframework.web.server.ResponseStatusException.class)
    public long deleteOwnerData(String ownerKey) {
        accounts.lockOwnerForDeletion(ownerKey);
        jobs.cancelOwner(ownerKey);
        if(accounts.pendingCleanupForDeletion(ownerKey)>0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "Remaining cleanup was cancelled. Wait for active requests and verification leases to settle before deleting local data.");
        long deleted = accounts.deleteByOwnerKey(ownerKey);
        accounts.flush();
        if (deleted == 0 || !accounts.findAllByOwnerKeyOrderByIdAsc(ownerKey).isEmpty()) {
            throw new IllegalStateException("User data deletion did not remove owned accounts");
        }
        return deleted;
    }
}
