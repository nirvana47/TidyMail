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

    public UserDataDeletionService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Deletes all accounts owned by the key and verifies removal. */
    @Transactional
    public long deleteOwnerData(String ownerKey) {
        long deleted = accounts.deleteByOwnerKey(ownerKey);
        accounts.flush();
        if (deleted == 0 || !accounts.findAllByOwnerKeyOrderByIdAsc(ownerKey).isEmpty()) {
            throw new IllegalStateException("User data deletion did not remove owned accounts");
        }
        return deleted;
    }
}
