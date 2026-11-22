package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoProperties;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.security.oauth2.JpaOAuth2AuthorizedClientService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    // Validate Hibernate's entity model against the production Postgres migrations.
    "spring.jpa.hibernate.ddl-auto=validate",
    // Token encryption: two-variable openssl-rand-hex contract per .env.example §6.
    // Test-only all-zero values: 32-byte secret (64 hex chars) + 16-byte salt (32 hex chars).
    // CryptoStartupValidator runs before every test context refresh; without valid key
    // material the entire SpringBoot context fails with IllegalStateException.
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    // (Legacy single-var AES_256_KEY is still accepted for backward compat but not
    // used in new deployments.)
    // No OAuth providers configured for this persistence test (irrelevant).
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common"
})
class Phase3PersistenceTest {

    @Autowired
    private UserAccountRepository userAccounts;

    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired
    private CryptoProperties cryptoProperties;

    @Autowired
    private JpaOAuth2AuthorizedClientService authorizedClients;

    @Test
    @DisplayName("T3(c) — AES-256-GCM encrypt then decrypt returns original; randomized IV ≠ duplicate ciphertext")
    void aes256gcmRoundtripAndRandomIv() {
        byte[] plaintext = "Hello Phase 3 encryption — AES-256-GCM with randomized IV!"
            .getBytes(StandardCharsets.UTF_8);

        byte[] cipherBlob1 = cryptoUtil.encrypt(plaintext);
        byte[] cipherBlob2 = cryptoUtil.encrypt(plaintext);

        // Two separate encrypt calls of the SAME plaintext should NEVER produce the same
        // ciphertext blob because the IV is randomized.  This guards against IV-reuse
        // bugs which are catastrophic for AES-GCM (breaks confidentiality + auth tag).
        assertThat(cipherBlob1).as("Random IV causes distinct ciphertext blobs per encrypt()")
            .isNotEqualTo(cipherBlob2);

        // Decrypt returns byte-for-byte identical plaintext.
        byte[] decrypted1 = cryptoUtil.decrypt(cipherBlob1);
        byte[] decrypted2 = cryptoUtil.decrypt(cipherBlob2);
        assertThat(decrypted1).as("decrypt(encrypt(p)) == p (1st blob)").isEqualTo(plaintext);
        assertThat(decrypted2).as("decrypt(encrypt(p)) == p (2nd blob)").isEqualTo(plaintext);

        // Tamper detection: flipping any byte in the blob should fail GCM auth tag check.
        cipherBlob1[cipherBlob1.length - 1] ^= 0x01;
        assertThatThrownBy(() -> cryptoUtil.decrypt(cipherBlob1))
            .as("Tampered ciphertext fails GCM authentication")
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void oauthSaveRepresentsMissingInitialRefreshTokenAsEncryptedEmptyValue() {
        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        authorizedClients.save(new OAuth2AuthorizedClient(
            googleRegistration(),
            "google-sub-empty",
            accessToken("access-only", issuedAt),
            null),
            Map.of("email", "empty.test@example.com"));

        UserAccount saved = userAccounts
            .findByProviderAndProviderAccountId("google", "google-sub-empty")
            .orElseThrow();
        assertThat(cryptoUtil.decrypt(saved.getEncryptedRefreshToken())).isEmpty();
        ClientRegistrationRepository registrations = id -> googleRegistration();
        var loader = new JpaOAuth2AuthorizedClientService(userAccounts, cryptoUtil, cryptoProperties, registrations);
        assertThat(loader.<OAuth2AuthorizedClient>loadAuthorizedClient("google", "google-sub-empty").getRefreshToken()).isNull();

    }

    private static ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
            .clientId("test.apps.googleusercontent.com")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/login/oauth2/code/google")
            .scope("openid", "email", "profile",
                "https://www.googleapis.com/auth/gmail.modify")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://oauth2.googleapis.com/token")
            .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
            .userNameAttributeName("sub")
            .clientName("Google")
            .build();
    }

    private static OAuth2AccessToken accessToken(String value, Instant issuedAt) {
        return new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            value,
            issuedAt,
            issuedAt.plusSeconds(3600),
            Set.of("openid", "email", "profile",
                "https://www.googleapis.com/auth/gmail.modify"));
    }
}
