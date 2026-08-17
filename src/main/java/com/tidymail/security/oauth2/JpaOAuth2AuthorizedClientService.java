package com.tidymail.security.oauth2;

import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoProperties;
import com.tidymail.security.crypto.CryptoUtil;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class JpaOAuth2AuthorizedClientService implements OAuth2AuthorizedClientService {

    private final UserAccountRepository userAccountRepository;
    private final CryptoUtil cryptoUtil;
    private final CryptoProperties cryptoProperties;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public JpaOAuth2AuthorizedClientService(UserAccountRepository userAccountRepository,
                                            CryptoUtil cryptoUtil,
                                            CryptoProperties cryptoProperties,
                                            ClientRegistrationRepository clientRegistrationRepository) {
        this.userAccountRepository = userAccountRepository;
        this.cryptoUtil = cryptoUtil;
        this.cryptoProperties = cryptoProperties;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                     String principalName) {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(clientRegistrationId);
        if (registration == null) {
            return null;
        }

        String provider = clientRegistrationId;
        String providerAccountId = principalName;

        Optional<UserAccount> accountOpt = userAccountRepository
            .findByProviderAndProviderAccountId(provider, providerAccountId);
        if (accountOpt.isEmpty()) {
            return null;
        }

        UserAccount account = accountOpt.get();

        byte[] accessTokenBytes = cryptoUtil.decrypt(account.getEncryptedAccessToken());
        String accessTokenValue = new String(accessTokenBytes, StandardCharsets.UTF_8);

        byte[] refreshTokenBytes = cryptoUtil.decrypt(account.getEncryptedRefreshToken());
        String refreshTokenValue = new String(refreshTokenBytes, StandardCharsets.UTF_8);

        Set<String> scopeSet = new HashSet<>();
        if (account.getScopes() != null) {
            scopeSet.addAll(account.getScopes());
        }

        Instant issuedAt = account.getTokenIssuedAt();
        Instant expiresAt = account.getAccessExpiresAt();

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            accessTokenValue,
            issuedAt,
            expiresAt,
            scopeSet
        );

        OAuth2RefreshToken refreshToken = refreshTokenValue.isBlank()
            ? null
            : new OAuth2RefreshToken(refreshTokenValue, issuedAt);

        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(
            registration,
            principalName,
            accessToken,
            refreshToken
        );

        return (T) authorizedClient;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                                     Authentication principal) {
        Map<String, Object> attributes = null;
        Object principalObj = principal.getPrincipal();
        if (principalObj instanceof OAuth2User oauth2User) {
            attributes = oauth2User.getAttributes();
        }
        save(authorizedClient, attributes);
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId,
                                       String principalName) {
        String provider = clientRegistrationId;
        String providerAccountId = principalName;

        userAccountRepository.invalidateOAuthCredentials(
            provider,
            providerAccountId,
            "authorization_required",
            Instant.now());
    }

    public void save(OAuth2AuthorizedClient authorizedClient,
                     Map<String, Object> userInfoClaims) {
        ClientRegistration registration = authorizedClient.getClientRegistration();
        String provider = registration.getRegistrationId();
        String providerAccountId = authorizedClient.getPrincipalName();

        String email = null;
        String displayName = null;
        String avatarUrl = null;

        if (userInfoClaims != null) {
            Object emailObj = userInfoClaims.get("email");
            if (emailObj instanceof String s) {
                email = s;
            }
            Object nameObj = userInfoClaims.get("name");
            if (nameObj instanceof String s) {
                displayName = s;
            }
            Object pictureObj = userInfoClaims.get("picture");
            if (pictureObj instanceof String s) {
                avatarUrl = s;
            }
        }

        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
        OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();

        String accessTokenValue = accessToken.getTokenValue();
        byte[] encryptedAccessToken = cryptoUtil.encrypt(accessTokenValue.getBytes(StandardCharsets.UTF_8));

        boolean hasRefreshToken = refreshToken != null
            && refreshToken.getTokenValue() != null
            && !refreshToken.getTokenValue().isBlank();
        byte[] encryptedRefreshToken = hasRefreshToken
            ? cryptoUtil.encrypt(refreshToken.getTokenValue().getBytes(StandardCharsets.UTF_8))
            : null;

        Instant tokenIssuedAt = accessToken.getIssuedAt();
        Instant accessExpiresAt = accessToken.getExpiresAt();

        List<String> scopes = null;
        if (accessToken.getScopes() != null) {
            scopes = List.copyOf(accessToken.getScopes());
        }

        Optional<UserAccount> existingOpt = userAccountRepository
            .findByProviderAndProviderAccountId(provider, providerAccountId);

        UserAccount account;
        if (existingOpt.isPresent()) {
            account = existingOpt.get();
            account.setEncryptedAccessToken(encryptedAccessToken);
            if (hasRefreshToken) {
                account.setEncryptedRefreshToken(encryptedRefreshToken);
            }
            account.setTokenIssuedAt(tokenIssuedAt);
            account.setAccessExpiresAt(accessExpiresAt);
            account.setScopes(scopes);
            if (email != null) {
                account.setEmail(email);
            }
            if (displayName != null) {
                account.setDisplayName(displayName);
            }
            if (avatarUrl != null) {
                account.setAvatarUrl(avatarUrl);
            }
            account.setOauthStatus(UserAccount.OAuthStatus.ACTIVE);
            account.setOauthErrorCode(null);
            account.setOauthErrorAt(null);
        } else {
            account = new UserAccount();
            account.setProvider(provider);
            account.setProviderAccountId(providerAccountId);
            account.setEmail(email != null ? email : providerAccountId);
            account.setOwnerKey(account.getEmail().strip().toLowerCase(java.util.Locale.ROOT)
                + ":" + provider);
            account.setDisplayName(displayName);
            account.setAvatarUrl(avatarUrl);
            account.setEncryptedAccessToken(encryptedAccessToken);
            account.setEncryptedRefreshToken(hasRefreshToken
                ? encryptedRefreshToken
                : cryptoUtil.encrypt(new byte[0]));
            account.setTokenIssuedAt(tokenIssuedAt);
            account.setAccessExpiresAt(accessExpiresAt);
            account.setScopes(scopes);
            account.setAesKeyId(cryptoProperties.getAesKeyId());
        }

        userAccountRepository.save(account);
    }
}
