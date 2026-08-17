package com.tidymail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;

class AccountSessionServiceTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AccountSessionService service = new AccountSessionService(accounts);

    @Test
    void selectionPersistsOneOwnedActiveAccountAndRejectsAllScope() {
        UserAccount first = account(1L, "owner@example.com", "owner@example.com:google");
        UserAccount second = account(2L, "other@gmail.com", "owner@example.com:google");
        OAuth2AuthenticationToken authentication = authentication();
        when(accounts.findByProviderAndProviderAccountId("google", authentication.getName()))
            .thenReturn(java.util.Optional.of(first));
        when(accounts.findAllByOwnerKeyOrderByIdAsc(first.getOwnerKey()))
            .thenReturn(List.of(first, second));
        MockHttpSession session = new MockHttpSession();

        assertThat(service.resolve(session, authentication, "2").activeAccountId()).isEqualTo(2L);
        assertThat(service.resolve(session, authentication, "current").activeAccountId())
            .isEqualTo(2L);
        assertThatThrownBy(() -> service.resolve(session, authentication, "all"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("All-account scope is not supported");
    }

    @Test
    void foreignAccountCannotBecomeActive() {
        UserAccount first = account(1L, "owner@example.com", "owner@example.com:google");
        OAuth2AuthenticationToken authentication = authentication();
        when(accounts.findByProviderAndProviderAccountId("google", authentication.getName()))
            .thenReturn(java.util.Optional.of(first));
        when(accounts.findAllByOwnerKeyOrderByIdAsc(first.getOwnerKey()))
            .thenReturn(List.of(first));

        assertThatThrownBy(() ->
            service.resolve(new MockHttpSession(), authentication, "99"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Account is not owned");
    }

    private static UserAccount account(Long id, String email, String ownerKey) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("subject-" + id);
        account.setOwnerKey(ownerKey);
        return account;
    }

    private static OAuth2AuthenticationToken authentication() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = new DefaultOAuth2User(
            authorities,
            Map.of("sub", "subject-1", "email", "owner@example.com"),
            "sub");
        return new OAuth2AuthenticationToken(principal, authorities, "google");
    }
}
