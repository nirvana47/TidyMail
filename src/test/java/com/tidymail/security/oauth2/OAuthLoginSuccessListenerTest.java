package com.tidymail.security.oauth2;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.tidymail.entity.UserAccount;
import com.tidymail.dto.AccountSession;
import com.tidymail.job.IncrementalSyncJob;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.AccountLoginSuccessHandler;
import com.tidymail.service.AccountSessionService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OAuthLoginSuccessListenerTest {

    @Test
    void persistsTokensFromSuccessfulCallbackWithoutLoadingStoredClientFirst() {
        JpaOAuth2AuthorizedClientService clients =
            mock(JpaOAuth2AuthorizedClientService.class);
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        IncrementalSyncJob syncJob = mock(IncrementalSyncJob.class);
        OAuthLoginSuccessListener listener =
            new OAuthLoginSuccessListener(clients, accounts, syncJob);
        UserAccount account = new UserAccount();
        account.setId(42L);
        when(accounts.findByProviderAndProviderAccountId(
            "google", "google-sub-callback"))
            .thenReturn(Optional.empty(), Optional.of(account));

        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "callback-access",
            issuedAt,
            issuedAt.plusSeconds(3600),
            Set.of("openid", "email"));
        OAuth2RefreshToken refreshToken =
            new OAuth2RefreshToken("callback-refresh", issuedAt);
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttributes()).thenReturn(Map.of("email", "callback.test@example.com"));

        OAuth2LoginAuthenticationToken token = mock(OAuth2LoginAuthenticationToken.class);
        when(token.getPrincipal()).thenReturn(user);
        when(token.getClientRegistration()).thenReturn(googleRegistration());
        when(token.getName()).thenReturn("google-sub-callback");
        when(token.getAccessToken()).thenReturn(accessToken);
        when(token.getRefreshToken()).thenReturn(refreshToken);

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(token));

        verify(clients).save(argThat(client ->
            client.getPrincipalName().equals("google-sub-callback")
                && client.getAccessToken() == accessToken
                && client.getRefreshToken() == refreshToken),
            eq(Map.of("email", "callback.test@example.com")));
        verify(syncJob).requestBootstrap(42L);
        verify(syncJob, never()).requestSync(42L);
    }

    @Test
    void returningGoogleAccountQueuesFreshnessInsteadOfBootstrap() {
        JpaOAuth2AuthorizedClientService clients =
            mock(JpaOAuth2AuthorizedClientService.class);
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        IncrementalSyncJob syncJob = mock(IncrementalSyncJob.class);
        OAuthLoginSuccessListener listener =
            new OAuthLoginSuccessListener(clients, accounts, syncJob);
        UserAccount account = new UserAccount();
        account.setId(7L);
        when(accounts.findByProviderAndProviderAccountId(
            "google", "google-sub-callback")).thenReturn(Optional.of(account));

        listener.onAuthenticationSuccess(
            new AuthenticationSuccessEvent(callbackToken()));

        verify(syncJob).requestSync(7L);
        verify(syncJob, never()).requestBootstrap(7L);
    }

    @Test
    void pendingBootstrapRedirectsToActivityWithoutWaitingForWorker() throws Exception {
        AccountSessionService sessions = mock(AccountSessionService.class);
        MailboxSyncStateRepository states = mock(MailboxSyncStateRepository.class);
        var state = mock(MailboxSyncStateRepository.StateSnapshot.class);
        var authentication = mock(
            org.springframework.security.oauth2.client.authentication
                .OAuth2AuthenticationToken.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessions.initializeLogin(request, authentication))
            .thenReturn(new AccountSession("owner:google", 42L));
        when(states.findByAccountId(42L)).thenReturn(Optional.of(state));
        when(state.initialBatchCompletedAt()).thenReturn(null);

        new AccountLoginSuccessHandler(sessions, states)
            .onAuthenticationSuccess(request, response, authentication);

        org.assertj.core.api.Assertions.assertThat(response.getRedirectedUrl())
            .isEqualTo("/activity");
    }

    private static OAuth2LoginAuthenticationToken callbackToken() {
        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttributes()).thenReturn(Map.of("email", "callback.test@example.com"));
        OAuth2LoginAuthenticationToken token = mock(OAuth2LoginAuthenticationToken.class);
        when(token.getPrincipal()).thenReturn(user);
        when(token.getClientRegistration()).thenReturn(googleRegistration());
        when(token.getName()).thenReturn("google-sub-callback");
        when(token.getAccessToken()).thenReturn(new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "callback-access", issuedAt,
            issuedAt.plusSeconds(3600), Set.of("openid", "email")));
        when(token.getRefreshToken()).thenReturn(
            new OAuth2RefreshToken("callback-refresh", issuedAt));
        return token;
    }

    private static ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
            .clientId("test.apps.googleusercontent.com")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/login/oauth2/code/google")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://oauth2.googleapis.com/token")
            .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
            .userNameAttributeName("sub")
            .clientName("Google")
            .build();
    }
}
