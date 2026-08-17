package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.api.client.http.GenericUrl;
import com.google.api.services.gmail.Gmail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.GmailClientFactory;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.SecurityConfig;
import com.tidymail.security.crypto.CryptoProperties;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.security.oauth2.JpaOAuth2AuthorizedClientService;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService.CredentialAuthorizationException;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService.FailureCode;
import com.tidymail.support.PostgresJourneyTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

class OAuthAuthorizedClientRefreshTest extends PostgresJourneyTest {

    @Autowired
    private JpaOAuth2AuthorizedClientService authorizedClients;

    @Autowired
    private UserAccountRepository accountRepository;

    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired
    private CryptoProperties cryptoProperties;

    @Test
    void userLosesAuthorizationWithoutLosingIndexedMailboxData() {
        Instant issuedAt = Instant.parse("2026-08-14T12:00:00Z");
        authorizedClients.save(client(
            "google-subject-target",
            accessToken("access-before-removal", issuedAt, issuedAt.plusSeconds(3600)),
            new OAuth2RefreshToken("refresh-preserved", issuedAt)),
            Map.of("email", "target@example.test"));
        authorizedClients.save(client(
            "google-subject-other",
            accessToken("other-access", issuedAt, issuedAt.plusSeconds(3600)),
            new OAuth2RefreshToken("other-refresh", issuedAt)),
            Map.of("email", "other@example.test"));

        UserAccount target = accountRepository
            .findByProviderAndProviderAccountId("google", "google-subject-target")
            .orElseThrow();
        UserAccount other = accountRepository
            .findByProviderAndProviderAccountId("google", "google-subject-other")
            .orElseThrow();
        jdbc.update("""
            INSERT INTO mailbox_sync_state(account_id, provider)
            VALUES (?, 'google')
            """, target.getId());
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_revision
            ) VALUES (?, 'google', 'retained-message', 100)
            """, target.getId());

        authorizedClients.removeAuthorizedClient("google", "google-subject-target");

        UserAccount invalidated = accountRepository
            .findByProviderAndProviderAccountId("google", "google-subject-target")
            .orElseThrow();
        assertThat(invalidated.getOauthStatus()).isEqualTo(UserAccount.OAuthStatus.REAUTH_REQUIRED);
        assertThat(invalidated.getOauthErrorCode()).isEqualTo("authorization_required");
        assertThat(invalidated.getOauthErrorAt()).isNotNull();
        assertThat(accountRepository.findById(other.getId()).orElseThrow().getOauthStatus())
            .isEqualTo(UserAccount.OAuthStatus.ACTIVE);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM mailbox_sync_state WHERE account_id = ?",
            Long.class, target.getId())).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM message_metadata WHERE account_id = ?",
            Long.class, target.getId())).isOne();
    }

    @Test
    void userReauthorizesWithoutReplacingRefreshToken() {
        Instant issuedAt = Instant.parse("2026-08-14T12:00:00Z");
        authorizedClients.save(client(
            "google-subject-reauth",
            accessToken("access-before", issuedAt, issuedAt.plusSeconds(3600)),
            new OAuth2RefreshToken("refresh-preserved", issuedAt)),
            Map.of("email", "reauth@example.test"));
        authorizedClients.removeAuthorizedClient("google", "google-subject-reauth");

        authorizedClients.save(client(
            "google-subject-reauth",
            accessToken("access-after", issuedAt.plusSeconds(60), issuedAt.plusSeconds(3660)),
            null),
            Map.of("email", "reauth@example.test"));

        UserAccount restored = accountRepository
            .findByProviderAndProviderAccountId("google", "google-subject-reauth")
            .orElseThrow();
        assertThat(restored.getOauthStatus()).isEqualTo(UserAccount.OAuthStatus.ACTIVE);
        assertThat(restored.getOauthErrorCode()).isNull();
        assertThat(restored.getOauthErrorAt()).isNull();
        assertThat(new String(
            cryptoUtil.decrypt(restored.getEncryptedRefreshToken()), StandardCharsets.UTF_8))
            .isEqualTo("refresh-preserved");
    }

    @Test
    void userWithUnknownTokenTimesKeepsThemUnknownWhenLoaded() {
        authorizedClients.save(client(
            "google-subject-unknown-times",
            accessToken("access-unknown-times", null, null),
            new OAuth2RefreshToken("refresh-unknown-times", null)),
            Map.of("email", "unknown-times@example.test"));
        ClientRegistrationRepository registrations = registrationId ->
            "google".equals(registrationId) ? googleRegistration() : null;
        JpaOAuth2AuthorizedClientService loader = new JpaOAuth2AuthorizedClientService(
            accountRepository, cryptoUtil, cryptoProperties, registrations);

        OAuth2AuthorizedClient loaded =
            loader.loadAuthorizedClient("google", "google-subject-unknown-times");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getAccessToken().getIssuedAt()).isNull();
        assertThat(loaded.getAccessToken().getExpiresAt()).isNull();
        assertThat(loaded.getRefreshToken()).isNotNull();
        assertThat(loaded.getRefreshToken().getIssuedAt()).isNull();
    }

    @Test
    void userRefreshesOnlyAtOrInsideTheConfiguredExpirySkew() throws Exception {
        try (TokenEndpoint endpoint = TokenEndpoint.success()) {
            ClientRegistration registration = googleRegistration(endpoint.tokenUri());
            JpaOAuth2AuthorizedClientService clients = clientService(registration);
            Instant now = Instant.now();
            clients.save(client(
                registration,
                "google-subject-skew",
                accessToken("access-still-valid", now, now.plusSeconds(120)),
                new OAuth2RefreshToken("refresh-skew", now)),
                Map.of("email", "skew@example.test"));
            UserAccount account = accountRepository
                .findByProviderAndProviderAccountId("google", "google-subject-skew")
                .orElseThrow();
            ManagedGoogleCredentialService credentials = managedCredentials(clients, registration);

            OAuth2AuthorizedClient reused = credentials.authorize(account);

            assertThat(reused.getAccessToken().getTokenValue()).isEqualTo("access-still-valid");
            assertThat(endpoint.requests()).isZero();

            clients.save(client(
                registration,
                "google-subject-skew",
                accessToken("access-at-skew", now, Instant.now().plusSeconds(60)),
                new OAuth2RefreshToken("refresh-skew", now)),
                Map.of("email", "skew@example.test"));

            OAuth2AuthorizedClient refreshed = credentials.authorize(account);

            assertThat(refreshed.getAccessToken().getTokenValue()).isEqualTo("access-refreshed");
            assertThat(endpoint.requests()).isOne();
            UserAccount persisted = accountRepository
                .findByProviderAndProviderAccountId("google", "google-subject-skew")
                .orElseThrow();
            assertThat(new String(
                cryptoUtil.decrypt(persisted.getEncryptedRefreshToken()), StandardCharsets.UTF_8))
                .isEqualTo("refresh-skew");
        }
    }

    @Test
    void parallelMailboxLanesBuildClientsFromOnePersistedRefresh() throws Exception {
        try (TokenEndpoint endpoint = TokenEndpoint.blockingSuccess()) {
            ClientRegistration registration = googleRegistration(endpoint.tokenUri());
            JpaOAuth2AuthorizedClientService clients = clientService(registration);
            Instant issuedAt = Instant.now().minusSeconds(7200);
            clients.save(client(
                registration,
                "google-subject-parallel",
                accessToken("access-expired", issuedAt, issuedAt.plusSeconds(3600)),
                new OAuth2RefreshToken("refresh-parallel", issuedAt)),
                Map.of("email", "parallel@example.test"));
            UserAccount account = accountRepository
                .findByProviderAndProviderAccountId("google", "google-subject-parallel")
                .orElseThrow();
            GmailClientFactory factory =
                new GmailClientFactory(managedCredentials(clients, registration));
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Gmail> freshness = executor.submit(() -> factory.buildFor(account));
                assertThat(endpoint.awaitRequest()).isTrue();
                Future<Gmail> backfill = executor.submit(() -> factory.buildFor(account));
                endpoint.release();

                assertThat(bearerToken(freshness.get(10, TimeUnit.SECONDS)))
                    .isEqualTo("Bearer access-refreshed");
                assertThat(bearerToken(backfill.get(10, TimeUnit.SECONDS)))
                    .isEqualTo("Bearer access-refreshed");
                assertThat(endpoint.requests()).isOne();
                UserAccount persisted = accountRepository
                    .findByProviderAndProviderAccountId("google", "google-subject-parallel")
                    .orElseThrow();
                assertThat(new String(
                    cryptoUtil.decrypt(persisted.getEncryptedAccessToken()),
                    StandardCharsets.UTF_8)).isEqualTo("access-refreshed");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void userWithRevokedGrantStopsBeforeAnyGmailRequestOrRefreshLoop() throws Exception {
        try (TokenEndpoint endpoint = TokenEndpoint.invalidGrant()) {
            ClientRegistration registration = googleRegistration(endpoint.tokenUri());
            JpaOAuth2AuthorizedClientService clients = clientService(registration);
            Instant issuedAt = Instant.now().minusSeconds(7200);
            clients.save(client(
                registration,
                "google-subject-revoked",
                accessToken("access-expired", issuedAt, issuedAt.plusSeconds(3600)),
                new OAuth2RefreshToken("refresh-revoked", issuedAt)),
                Map.of("email", "revoked@example.test"));
            UserAccount account = accountRepository
                .findByProviderAndProviderAccountId("google", "google-subject-revoked")
                .orElseThrow();
            jdbc.update("""
                INSERT INTO mailbox_sync_state(account_id, provider)
                VALUES (?, 'google')
                """, account.getId());
            jdbc.update("""
                INSERT INTO message_metadata(
                    account_id, provider, provider_message_id, provider_revision
                ) VALUES (?, 'google', 'revoked-retained-message', 200)
                """, account.getId());
            GmailClientFactory factory =
                new GmailClientFactory(managedCredentials(clients, registration));

            assertThatThrownBy(() -> factory.buildFor(account))
                .isInstanceOf(CredentialAuthorizationException.class)
                .extracting(ex -> ((CredentialAuthorizationException) ex).getCode())
                .isEqualTo(FailureCode.REAUTH_REQUIRED);
            assertThatThrownBy(() -> factory.buildFor(account))
                .isInstanceOf(CredentialAuthorizationException.class)
                .hasMessage("REAUTH_REQUIRED")
                .hasMessageNotContaining("provider-secret-detail");

            assertThat(endpoint.requests()).isOne();
            UserAccount invalidated = accountRepository
                .findByProviderAndProviderAccountId("google", "google-subject-revoked")
                .orElseThrow();
            assertThat(invalidated.getOauthStatus())
                .isEqualTo(UserAccount.OAuthStatus.REAUTH_REQUIRED);
            assertThat(invalidated.getOauthErrorCode()).isEqualTo("authorization_required");
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mailbox_sync_state WHERE account_id = ?",
                Long.class, account.getId())).isOne();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_metadata WHERE account_id = ?",
                Long.class, account.getId())).isOne();
        }
    }

    private JpaOAuth2AuthorizedClientService clientService(ClientRegistration registration) {
        ClientRegistrationRepository registrations = registrationId ->
            "google".equals(registrationId) ? registration : null;
        return new JpaOAuth2AuthorizedClientService(
            accountRepository, cryptoUtil, cryptoProperties, registrations);
    }

    private ManagedGoogleCredentialService managedCredentials(
            JpaOAuth2AuthorizedClientService clients,
            ClientRegistration registration) {
        ClientRegistrationRepository registrations = registrationId ->
            "google".equals(registrationId) ? registration : null;
        SecurityConfig securityConfig = new SecurityConfig();
        var provider = securityConfig.authorizedClientProvider(60);
        var successHandler = securityConfig.authorizationSuccessHandler(clients);
        var failureHandler = securityConfig.authorizationFailureHandler(clients);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            securityConfig.authorizedClientManager(
                registrations, clients, provider, successHandler, failureHandler);
        return new ManagedGoogleCredentialService(manager, clients, accountRepository, 60);
    }

    private static String bearerToken(Gmail gmail) throws IOException {
        return gmail.getRequestFactory()
            .buildGetRequest(new GenericUrl("https://gmail.googleapis.test/"))
            .getHeaders()
            .getAuthorization();
    }

    private static OAuth2AuthorizedClient client(
            String principalName,
            OAuth2AccessToken accessToken,
            OAuth2RefreshToken refreshToken) {
        return client(googleRegistration(), principalName, accessToken, refreshToken);
    }

    private static OAuth2AuthorizedClient client(
            ClientRegistration registration,
            String principalName,
            OAuth2AccessToken accessToken,
            OAuth2RefreshToken refreshToken) {
        return new OAuth2AuthorizedClient(
            registration, principalName, accessToken, refreshToken);
    }

    private static OAuth2AccessToken accessToken(
            String value, Instant issuedAt, Instant expiresAt) {
        return new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            value,
            issuedAt,
            expiresAt,
            Set.of("openid", "email", "profile",
                "https://www.googleapis.com/auth/gmail.modify"));
    }

    private static ClientRegistration googleRegistration() {
        return googleRegistration("https://oauth2.googleapis.com/token");
    }

    private static ClientRegistration googleRegistration(String tokenUri) {
        return ClientRegistration.withRegistrationId("google")
            .clientId("test.apps.googleusercontent.com")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/login/oauth2/code/google")
            .scope("openid", "email", "profile",
                "https://www.googleapis.com/auth/gmail.modify")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri(tokenUri)
            .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
            .userNameAttributeName("sub")
            .clientName("Google")
            .build();
    }

    private static final class TokenEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final int status;
        private final String body;
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch requestReceived = new CountDownLatch(1);
        private final CountDownLatch release;

        private TokenEndpoint(int status, String body, boolean blocking) throws IOException {
            this.status = status;
            this.body = body;
            this.release = new CountDownLatch(blocking ? 1 : 0);
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/token", this::respond);
            this.server.start();
        }

        static TokenEndpoint success() throws IOException {
            return new TokenEndpoint(200, """
                {"access_token":"access-refreshed","token_type":"Bearer","expires_in":3600}
                """, false);
        }

        static TokenEndpoint blockingSuccess() throws IOException {
            return new TokenEndpoint(200, """
                {"access_token":"access-refreshed","token_type":"Bearer","expires_in":3600}
                """, true);
        }

        static TokenEndpoint invalidGrant() throws IOException {
            return new TokenEndpoint(400, """
                {"error":"invalid_grant","error_description":"provider-secret-detail"}
                """, false);
        }

        String tokenUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        }

        int requests() {
            return requests.get();
        }

        boolean awaitRequest() throws InterruptedException {
            return requestReceived.await(5, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        private void respond(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            requestReceived.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            release();
            server.stop(0);
        }
    }
}
