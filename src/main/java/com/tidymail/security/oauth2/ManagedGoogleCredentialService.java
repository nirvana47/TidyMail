package com.tidymail.security.oauth2;

import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Service;

@Service
public class ManagedGoogleCredentialService {

    public enum FailureCode {
        REAUTH_REQUIRED,
        RETRYABLE
    }

    public static final class CredentialAuthorizationException extends RuntimeException {
        private final FailureCode code;

        public CredentialAuthorizationException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode getCode() {
            return code;
        }
    }

    private static final Set<String> TERMINAL_ERROR_CODES =
        Set.of("invalid_grant", "invalid_token");

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final JpaOAuth2AuthorizedClientService authorizedClientService;
    private final UserAccountRepository userAccountRepository;
    private final Duration expirySkew;
    private final ConcurrentHashMap<AccountKey, CompletableFuture<OAuth2AuthorizedClient>>
        inFlight = new ConcurrentHashMap<>();

    public ManagedGoogleCredentialService(
            OAuth2AuthorizedClientManager authorizedClientManager,
            JpaOAuth2AuthorizedClientService authorizedClientService,
            UserAccountRepository userAccountRepository,
            @Value("${app.oauth.refresh-expiry-skew-seconds}") long expirySkewSeconds) {
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClientService = authorizedClientService;
        this.userAccountRepository = userAccountRepository;
        this.expirySkew = Duration.ofSeconds(expirySkewSeconds);
    }

    public OAuth2AuthorizedClient authorize(UserAccount account) {
        requireGoogleIdentity(account);
        AccountKey key = new AccountKey("google", account.getProviderAccountId());
        CompletableFuture<OAuth2AuthorizedClient> candidate = new CompletableFuture<>();
        CompletableFuture<OAuth2AuthorizedClient> existing = inFlight.putIfAbsent(key, candidate);
        if (existing != null) {
            return await(existing);
        }

        try {
            OAuth2AuthorizedClient authorizedClient = authorizeOnce(key, account.getId());
            candidate.complete(authorizedClient);
            return authorizedClient;
        } catch (RuntimeException ex) {
            candidate.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, candidate);
        }
    }

    private OAuth2AuthorizedClient authorizeOnce(AccountKey key, Long expectedAccountId) {
        UserAccount persistedAccount = userAccountRepository
            .findByProviderAndProviderAccountId(key.provider(), key.providerAccountId())
            .filter(account -> expectedAccountId == null || expectedAccountId.equals(account.getId()))
            .orElseThrow(() ->
                new CredentialAuthorizationException(FailureCode.REAUTH_REQUIRED));
        if (persistedAccount.getOauthStatus() == UserAccount.OAuthStatus.REAUTH_REQUIRED) {
            throw new CredentialAuthorizationException(FailureCode.REAUTH_REQUIRED);
        }

        Authentication principal = new UsernamePasswordAuthenticationToken(
            key.providerAccountId(), "managed-oauth", List.of());
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
            .withClientRegistrationId("google")
            .principal(principal)
            .build();

        try {
            OAuth2AuthorizedClient result = authorizedClientManager.authorize(request);
            OAuth2AuthorizedClient persisted = authorizedClientService.loadAuthorizedClient(
                "google", key.providerAccountId());
            if (result == null || persisted == null || requiresRefresh(persisted)) {
                authorizedClientService.removeAuthorizedClient(
                    "google", key.providerAccountId());
                throw new CredentialAuthorizationException(FailureCode.REAUTH_REQUIRED);
            }
            return persisted;
        } catch (OAuth2AuthorizationException ex) {
            FailureCode code = TERMINAL_ERROR_CODES.contains(ex.getError().getErrorCode())
                ? FailureCode.REAUTH_REQUIRED
                : FailureCode.RETRYABLE;
            throw new CredentialAuthorizationException(code);
        }
    }

    private boolean requiresRefresh(OAuth2AuthorizedClient client) {
        Instant expiresAt = client.getAccessToken().getExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(Instant.now().plus(expirySkew));
    }

    private static OAuth2AuthorizedClient await(
            CompletableFuture<OAuth2AuthorizedClient> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private static void requireGoogleIdentity(UserAccount account) {
        if (account == null
                || account.getProvider() == null
                || !account.getProvider().equalsIgnoreCase("google")
                || account.getProviderAccountId() == null
                || account.getProviderAccountId().isBlank()) {
            throw new CredentialAuthorizationException(FailureCode.REAUTH_REQUIRED);
        }
    }

    private record AccountKey(String provider, String providerAccountId) {
    }
}
