package com.tidymail.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.UserDataDeletionService;
import org.junit.jupiter.api.Test;

class LogoutSemanticsTest {

    @Test
    void deleteDataRemovesEveryAccountForOwnerAndVerifiesCompletion() {
        UserAccountRepository accounts = org.mockito.Mockito.mock(UserAccountRepository.class);
        when(accounts.deleteByOwnerKey("owner@example.com:google")).thenReturn(2L);
        when(accounts.findAllByOwnerKeyOrderByIdAsc("owner@example.com:google"))
            .thenReturn(java.util.List.of());

        new UserDataDeletionService(accounts,org.mockito.Mockito.mock(com.tidymail.service.DashboardJobService.class))
            .deleteOwnerData("owner@example.com:google");

        verify(accounts).deleteByOwnerKey("owner@example.com:google");
        verify(accounts).flush();
    }
}
