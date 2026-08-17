package com.tidymail.service;

import com.tidymail.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDataDeletionService {

    private final UserAccountRepository accounts;

    public UserDataDeletionService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

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
