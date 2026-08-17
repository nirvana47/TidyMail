package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.entity.SenderStat;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.SenderStatRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.security.crypto.CryptoProperties;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.security.oauth2.JpaOAuth2AuthorizedClientService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
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

    // ---- T3 (a): Flyway schema ----
    @Autowired
    private Flyway flyway;

    // ---- T3 (b): entity roundtrip ----
    @Autowired
    private UserAccountRepository userAccounts;

    @Autowired
    private SenderStatRepository senderStats;

    @Autowired
    private UserActionRepository userActions;

    // ---- T3 (c): encrypt/decrypt inverse ----
    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired
    private CryptoProperties cryptoProperties;

    @Autowired
    private JpaOAuth2AuthorizedClientService authorizedClients;

    @BeforeEach
    void cleanDatabase() {
        // Order matters: child tables first (FK ON DELETE CASCADE not run during
        // deleteAll because JPA issues individual DELETEs; running child-first avoids
        // FK violations). Cascade only runs at DB level for parent-table DELETEs.
        userActions.deleteAll();
        senderStats.deleteAll();
        userAccounts.deleteAll();
    }

    @Test
    @DisplayName("T3(a) — Flyway includes the required V1 through V12 migrations")
    void flywayAppliesV1Schema() {
        // Flyway bean is already initialized by Spring context refresh's
        // FlywayAutoConfiguration (spring.flyway.enabled=true).  info().current()
        // returns the version currently applied.  Failures would have thrown during
        // context load, so reaching this test already proves the migrations succeeded;
        // assert the required history while allowing later additive migrations.
        org.flywaydb.core.api.MigrationInfo current = flyway.info().current();
        assertThat(current)
            .as("A current migration should exist")
            .isNotNull();
        assertThat(flyway.info().applied())
            .as("Required V1 through V12 migrations are applied")
            .extracting(info -> info.getVersion().getVersion())
            .contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    @DisplayName("T3(b) — UserAccount save + find roundtrip via provider+providerAccountId")
    void userAccountSaveAndFindRoundtrip() {
        UserAccount account = new UserAccount();
        account.setEmail("alice.test@example.com");
        account.setProvider("google");
        account.setProviderAccountId("100000000000000012345");
        account.setDisplayName("Alice Tester");
        account.setAvatarUrl("https://lh3.googleusercontent.com/a/photo.jpg");
        account.setEncryptedAccessToken(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        account.setEncryptedRefreshToken(new byte[]{9, 10, 11, 12, 13, 14, 15, 16, 17});
        account.setTokenIssuedAt(Instant.parse("2026-08-07T00:00:00Z"));
        account.setAccessExpiresAt(Instant.parse("2026-08-07T01:00:00Z"));
        account.setScopes(List.of("openid", "email", "profile", "https://www.googleapis.com/auth/gmail.modify"));
        account.setAesKeyId("phase3-test-v1");

        UserAccount saved = userAccounts.save(account);
        assertThat(saved.getId()).as("ID assigned after save").isNotNull();
        assertThat(saved.getCreatedAt()).as("createdAt populated by @PrePersist").isNotNull();
        assertThat(saved.getUpdatedAt()).as("updatedAt populated by @PrePersist").isNotNull();

        Optional<UserAccount> loaded =
            userAccounts.findByProviderAndProviderAccountId("google", "100000000000000012345");
        assertThat(loaded).as("Lookup by (provider, providerAccountId) finds inserted row").isPresent();

        UserAccount got = loaded.get();
        assertThat(got.getEmail()).as("email roundtrip").isEqualTo("alice.test@example.com");
        assertThat(got.getDisplayName()).as("displayName roundtrip").isEqualTo("Alice Tester");
        assertThat(got.getAvatarUrl()).as("avatarUrl roundtrip").isEqualTo("https://lh3.googleusercontent.com/a/photo.jpg");
        assertThat(got.getEncryptedAccessToken()).as("encryptedAccessToken roundtrip bytes identical")
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(got.getEncryptedRefreshToken()).as("encryptedRefreshToken roundtrip bytes identical")
            .containsExactly(9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertThat(got.getScopes()).as("scopes TEXT[] roundtrip")
            .containsExactly("openid", "email", "profile", "https://www.googleapis.com/auth/gmail.modify");
        assertThat(got.getAesKeyId()).as("aesKeyId roundtrip").isEqualTo("phase3-test-v1");
    }

    @Test
    @DisplayName("T3(c) — AES-256-GCM encrypt then decrypt returns original; randomized IV ≠ duplicate ciphertext")
    void aes256gcmRoundtripAndRandomIv() {
        // Sanity: CryptoProperties actually considers our test secret+salt valid.
        assertThat(cryptoProperties.isConfigured())
            .as("CryptoStartupValidator acceptance — hex-encoded test secret/salt must decode to >= 32/8 bytes")
            .isTrue();
        byte[] secretBytes = HexFormat.of().parseHex(
            "0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(secretBytes.length).as("Hard-coded test secret is exactly 32 bytes (64 hex chars)").isEqualTo(32);

        byte[] plaintext = "Hello Phase 3 encryption — AES-256-GCM with randomized IV!"
            .getBytes(StandardCharsets.UTF_8);

        byte[] cipherBlob1 = cryptoUtil.encrypt(plaintext);
        byte[] cipherBlob2 = cryptoUtil.encrypt(plaintext);

        // GCM blob format = 12-byte IV (random per encrypt call) + ciphertext + 16-byte tag
        assertThat(cipherBlob1.length).as("blob length = 12 + plaintext.length + 16 tag")
            .isEqualTo(12 + plaintext.length + 16);
        assertThat(cipherBlob2.length).as("blob length consistent").isEqualTo(cipherBlob1.length);

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
    void oauthSavePreservesExistingRefreshTokenWhenGoogleOmitsAReplacement() {
        ClientRegistration registration = googleRegistration();
        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        authorizedClients.save(new OAuth2AuthorizedClient(
            registration,
            "google-sub-preserve",
            accessToken("access-original", issuedAt),
            new OAuth2RefreshToken("refresh-original", issuedAt)),
            Map.of("email", "preserve.test@example.com"));

        UserAccount original = userAccounts
            .findByProviderAndProviderAccountId("google", "google-sub-preserve")
            .orElseThrow();
        byte[] originalCiphertext = original.getEncryptedRefreshToken().clone();

        authorizedClients.save(new OAuth2AuthorizedClient(
            registration,
            "google-sub-preserve",
            accessToken("access-replacement", issuedAt.plusSeconds(60)),
            null),
            Map.of("email", "preserve.test@example.com"));

        UserAccount updated = userAccounts
            .findByProviderAndProviderAccountId("google", "google-sub-preserve")
            .orElseThrow();
        assertThat(updated.getEncryptedRefreshToken()).containsExactly(originalCiphertext);
        assertThat(new String(
            cryptoUtil.decrypt(updated.getEncryptedRefreshToken()), StandardCharsets.UTF_8))
            .isEqualTo("refresh-original");
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
        assertThat(saved.getEncryptedRefreshToken()).hasSize(28);
        assertThat(cryptoUtil.decrypt(saved.getEncryptedRefreshToken())).isEmpty();
    }

    @Test
    void oauthLoadTreatsEncryptedEmptyRefreshTokenAsAbsent() {
        Instant issuedAt = Instant.parse("2026-08-10T00:00:00Z");
        authorizedClients.save(new OAuth2AuthorizedClient(
            googleRegistration(),
            "google-sub-load-empty",
            accessToken("access-only", issuedAt),
            null),
            Map.of("email", "load.empty.test@example.com"));
        ClientRegistrationRepository registrations = registrationId ->
            "google".equals(registrationId) ? googleRegistration() : null;
        JpaOAuth2AuthorizedClientService loader = new JpaOAuth2AuthorizedClientService(
            userAccounts, cryptoUtil, cryptoProperties, registrations);

        OAuth2AuthorizedClient loaded =
            loader.loadAuthorizedClient("google", "google-sub-load-empty");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("T3(d) — Cascade delete wipes account + sender_stats + user_actions rows for the same account FK")
    void accountDeleteCascadesToChildren() {
        // Arrange: insert one account, one sender_stat, two user_actions.
        UserAccount account = new UserAccount();
        account.setEmail("cascade.test@example.com");
        account.setProvider("microsoft");
        account.setProviderAccountId("live-id-987654321");
        account.setDisplayName("Cascade Tester");
        account.setEncryptedAccessToken(new byte[]{1, 2, 3});
        account.setEncryptedRefreshToken(new byte[]{4, 5, 6});
        account.setScopes(List.of("openid", "email", "offline_access"));
        account = userAccounts.save(account);
        Long accountId = account.getId();

        SenderStat sender = new SenderStat();
        sender.setAccountId(accountId);
        sender.setFromAddress("newsletter@acme.example");
        sender.setFromDisplayName("Acme Newsletter");
        sender.setFromDomain("acme.example");
        sender.setTotalCount(42);
        sender.setUnreadCount(40);
        sender.setHasListUnsubscribe(true);
        sender.setHasPrecedenceBulk(true);
        senderStats.save(sender);

        UserAction action1 = new UserAction();
        action1.setAccountId(accountId);
        action1.setActionType("ARCHIVE");
        action1.setSenderGroupKey("from:newsletter@acme.example");
        action1.setMessageIds(Arrays.asList("msg-001", "msg-002", "msg-003"));
        Map<String, Object> inv1 = new LinkedHashMap<>();
        inv1.put("labelBefore", "INBOX");
        inv1.put("messageIds", new ArrayList<>(action1.getMessageIds()));
        action1.setInverseActionData(inv1);
        userActions.save(action1);

        UserAction action2 = new UserAction();
        action2.setAccountId(accountId);
        action2.setActionType("TRASH");
        action2.setSenderGroupKey("from:spam@evil.example");
        action2.setMessageIds(Arrays.asList("msg-111", "msg-222"));
        action2.setInverseActionData(new LinkedHashMap<>());
        userActions.save(action2);

        // Sanity: children visible via repository count.
        assertThat(senderStats.countByAccountId(accountId)).as("1 sender stat present").isEqualTo(1L);
        assertThat(userActions.countByAccountId(accountId)).as("2 user actions present").isEqualTo(2L);

        // Act: delete account by email+provider tuple (the method used by logout cascade handler).
        userAccounts.deleteByEmailAndProviderIgnoreCase("cascade.test@example.com", "microsoft");

        // Assert: parent row gone AND both child rows GONE as well — DB ON DELETE CASCADE is
        // responsible for wiping the children; if it wasn't working then
        // senderStats.countByAccountId would still return 1 and test would fail here.
        assertThat(userAccounts.findById(accountId)).as("Parent user_accounts row deleted").isEmpty();
        assertThat(senderStats.countByAccountId(accountId)).as("sender_stats child rows CASCADE deleted").isEqualTo(0L);
        assertThat(userActions.countByAccountId(accountId)).as("user_actions child rows CASCADE deleted").isEqualTo(0L);
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
