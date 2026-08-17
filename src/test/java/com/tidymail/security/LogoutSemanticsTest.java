package com.tidymail.security;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.UserDataDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;

class LogoutSemanticsTest {

    @Test
    void ordinaryLogoutDoesNotDeletePersistentData() throws Exception {
        UserAccountRepository accounts = org.mockito.Mockito.mock(UserAccountRepository.class);
        CascadeDeleteLogoutSuccessHandler handler = new CascadeDeleteLogoutSuccessHandler();

        handler.onLogoutSuccess(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            new TestingAuthenticationToken("owner", "ignored"));

        verify(accounts, never()).deleteByOwnerKey(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteDataRemovesEveryAccountForOwnerAndVerifiesCompletion() {
        UserAccountRepository accounts = org.mockito.Mockito.mock(UserAccountRepository.class);
        when(accounts.deleteByOwnerKey("owner@example.com:google")).thenReturn(2L);
        when(accounts.findAllByOwnerKeyOrderByIdAsc("owner@example.com:google"))
            .thenReturn(java.util.List.of());

        new UserDataDeletionService(accounts)
            .deleteOwnerData("owner@example.com:google");

        verify(accounts).deleteByOwnerKey("owner@example.com:google");
        verify(accounts).flush();
    }
}
