package com.tidymail.security;

import com.tidymail.config.OAuthProviderAvailability;
import com.tidymail.security.oauth2.JpaOAuth2AuthorizedClientService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizationFailureHandler;
import org.springframework.security.oauth2.client.OAuth2AuthorizationSuccessHandler;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.RemoveAuthorizedClientOAuth2AuthorizationFailureHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Spring Security 6 configuration for TidyMail Phase 2. Uses programmatic ClientRegistration
 * construction (not application.properties declarations) to avoid startup failures when OAuth
 * credentials are absent — matches PITFALLS §J2 mitigation pattern. OAuth credentials are always
 * conditionally loaded only when both client-id AND client-secret environment variables for that
 * provider are non-empty via OAuthProviderAvailability.
 *
 * <p>Product functionality supported: Gmail and Outlook OAuth login, route
 * protection for the inbox cleanup app, and the login/logout flows.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Value("${app.mvp.enabled:true}")
    private boolean mvpEnabled;


    /**
     * Builds an OAuth2AuthorizedClientProvider that enables refresh-token renewal with
     * the configured clock-skew, so linked accounts can keep their tokens fresh.
     */
    @Bean
    public OAuth2AuthorizedClientProvider authorizedClientProvider(
            @Value("${app.oauth.refresh-expiry-skew-seconds}") long expirySkewSeconds) {
        if (expirySkewSeconds < 0 || expirySkewSeconds > 3600) {
            throw new IllegalArgumentException(
                "OAUTH_REFRESH_EXPIRY_SKEW_SECONDS must be between 0 and 3600");
        }
        return OAuth2AuthorizedClientProviderBuilder.builder()
            .refreshToken(refresh -> refresh.clockSkew(Duration.ofSeconds(expirySkewSeconds)))
            .build();
    }

    @Bean
    public OAuth2AuthorizationSuccessHandler authorizationSuccessHandler(
            JpaOAuth2AuthorizedClientService authorizedClientService) {
        return (authorizedClient, principal, attributes) ->
            authorizedClientService.saveAuthorizedClient(authorizedClient, principal);
    }

    @Bean
    public OAuth2AuthorizationFailureHandler authorizationFailureHandler(
            JpaOAuth2AuthorizedClientService authorizedClientService) {
        return new RemoveAuthorizedClientOAuth2AuthorizationFailureHandler(
            (registrationId, principal, attributes) ->
                authorizedClientService.removeAuthorizedClient(
                    registrationId, principal.getName()));
    }

    @Bean
    public AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            JpaOAuth2AuthorizedClientService authorizedClientService,
            OAuth2AuthorizedClientProvider authorizedClientProvider,
            OAuth2AuthorizationSuccessHandler authorizationSuccessHandler,
            OAuth2AuthorizationFailureHandler authorizationFailureHandler) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        manager.setAuthorizationSuccessHandler(authorizationSuccessHandler);
        manager.setAuthorizationFailureHandler(authorizationFailureHandler);
        return manager;
    }

    /**
     * Builds the security filter chain that protects routes, enables form login,
     * conditionally wires OAuth2 login for configured providers, and configures
     * session-based logout.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            OAuth2AuthorizedClientService authorizedClientService,
                                            CascadeDeleteLogoutSuccessHandler cascadeHandler,
                                            AccountLoginSuccessHandler loginSuccessHandler,
                                            OAuthProviderAvailability props) throws Exception {
        // NOTE: .authorizeHttpRequests(...) MUST be called exactly ONCE per SecurityFilterChain;
        // a second call either overwrites the first or throws a DSL re-entry error.  All matchers
        // for every scenario (public static/login, actuator, and the /oauth2/** handshake paths)
        // are therefore declared inside a SINGLE authorizeHttpRequests block below.
        http
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/static/**", "/login", "/login?logout").permitAll();
                auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();
                // Always allow the OAuth handshake endpoints.  When NO providers are configured
                // (the "user has not filled .env yet" default), these URLs simply 404 because
                // Spring's OAuth2ClientAuthenticationFilter is not wired in (see the conditional
                // .oauth2Login() block below).  Allowing them in the matcher layer keeps the DSL
                // to a single authorizeHttpRequests closure.
                auth.requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll();
                if (mvpEnabled) {
                    auth.requestMatchers("/senders", "/senders/**", "/unread", "/unread/**",
                        "/unsubscribe", "/unsubscribe/**", "/review", "/review/**",
                        "/analytics", "/analytics/**", "/accounts/**", "/workspace/**",
                        "/reply-zero", "/reply-zero/**", "/api/actions/**",
                        "/api/action-commands", "/api/cleanup-actions", "/api/selection-preview",
                        "/api/sender-categories/**", "/api/senders", "/api/senders/**",
                        "/api/reply-zero", "/senders-frag", "/domain-rollups-frag",
                        "/reply-zero-frag", "/sync-progress-frag").denyAll();
                }
                auth.anyRequest().authenticated();
            })
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            );

        // Conditionally enable OAuth2 login only when at least one provider has both
        // client-id and client-secret configured.  This avoids both:
        //   a) `InMemoryClientRegistrationRepository`'s "registrations cannot be empty"
        //      IllegalArgumentException on startup (the immediate cause of test failures),
        //   b) Spring Security's OAuth2 filter chain trying to resolve registrations for
        //      providers that don't logically exist yet.
        //
        // When NO providers are configured, the login page falls back to the warning banner
        // defined in login.html and no OAuth2 handshake endpoints are exposed at all.
        boolean anyProviderConfigured = props.isGoogleConfigured() || props.isMicrosoftConfigured();
        if (anyProviderConfigured) {
            // Build a custom OAuth2AuthorizationRequestResolver that injects Google-specific
            // query parameters (access_type=offline, prompt=consent) onto the auth URL.
            // Spring Security 6's AuthorizationEndpointConfig DSL does NOT expose
            // .authorizationRequestCustomizer(...) directly, so we construct the resolver
            // manually and attach a customizer via DefaultOAuth2AuthorizationRequestResolver.
            ClientRegistrationRepository clientRepo = clientRegistrationRepository(props);
            DefaultOAuth2AuthorizationRequestResolver defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                    clientRepo, "/oauth2/authorization");
            defaultResolver.setAuthorizationRequestCustomizer(
                SecurityConfig::customizeAuthorizationRequest);
            final OAuth2AuthorizationRequestResolver customResolver = defaultResolver;

            http.oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .successHandler(loginSuccessHandler)
                .authorizedClientService(authorizedClientService)
                .authorizationEndpoint(authEndpoint -> authEndpoint
                    .authorizationRequestResolver(customResolver)
                )
            );
        }

        http
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(cascadeHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            )
            .csrf(withDefaults());
        return http.build();
    }

    /**
     * Augments the OAuth authorization request with Google-specific parameters
     * ({@code access_type=offline}, {@code prompt=consent}) so a refresh token is
     * made available after the Gmail/Outlook login.
     */
    static void customizeAuthorizationRequest(OAuth2AuthorizationRequest.Builder requestBuilder) {
        OAuth2AuthorizationRequest built = requestBuilder.build();
        boolean isGoogle = built.getClientId() != null
            && built.getClientId().endsWith(".apps.googleusercontent.com");
        if (isGoogle) {
            // Google uses access_type=offline to request a refresh token.
            // prompt=consent ensures an existing grant is eligible to issue one again.
            requestBuilder.additionalParameters(params -> {
                params.put("access_type", "offline");
                params.put("prompt", "consent");
            });
        }
    }

    /**
     * Builds the in-memory ClientRegistration repository from configured providers,
     * returning a no-op repository when none are configured to avoid startup failure.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OAuthProviderAvailability props) {
        List<ClientRegistration> registrations = new ArrayList<>();

        if (props.isGoogleConfigured()) {
            try {
                ClientRegistration google = ClientRegistrations.fromOidcIssuerLocation("https://accounts.google.com")
                    .registrationId("google")
                    .clientId(props.getGoogleClientId())
                    .clientSecret(props.getGoogleClientSecret())
                    // NOTE: Google does NOT accept the literal string "offline_access" as a
                    // scope (that's Microsoft/Azure AD terminology).  Requesting a refresh token
                    // from Google is driven SOLELY by the access_type=offline QUERY PARAMETER on
                    // the authorization redirect URL, which we inject via the custom
                    // OAuth2AuthorizationRequestResolver configured earlier in filterChain().
                    .scope("openid", "email", "profile",
                           "https://www.googleapis.com/auth/gmail.modify")
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .clientName("Google")
                    .build();
                registrations.add(google);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Failed to configure Google OAuth via OIDC discovery. "
                    + "NOTE: In production with internet access, fromOidcIssuerLocation fetches the OIDC discovery document "
                    + "and populates authorization/token/jwk-set URIs. If offline during testing, fallback to manual "
                    + "ClientRegistration.withRegistrationId(...) builder with known URIs hardcoded — but we prefer the "
                    + "discovery flow because it future-proofs against endpoint URL changes.", ex);
            }
        }

        if (props.isMicrosoftConfigured()) {
            try {
                String issuerUri = "https://login.microsoftonline.com/"
                    + (StringUtils.hasText(props.getMicrosoftTenantId()) ? props.getMicrosoftTenantId() : "common")
                    + "/v2.0";
                ClientRegistration microsoft = ClientRegistrations.fromOidcIssuerLocation(issuerUri)
                    .registrationId("microsoft")
                    .clientId(props.getMicrosoftClientId())
                    .clientSecret(props.getMicrosoftClientSecret())
                    .scope("openid", "email", "profile", "offline_access", "https://graph.microsoft.com/Mail.ReadWrite")
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .clientName("Microsoft (Outlook)")
                    .build();
                registrations.add(microsoft);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Failed to configure Microsoft OAuth via OIDC discovery. "
                    + "NOTE: In production with internet access, fromOidcIssuerLocation fetches the OIDC discovery document "
                    + "and populates authorization/token/jwk-set URIs. If offline during testing, fallback to manual "
                    + "ClientRegistration.withRegistrationId(...) builder with known URIs hardcoded — but we prefer the "
                    + "discovery flow because it future-proofs against endpoint URL changes.", ex);
            }
        }

        if (registrations.isEmpty()) {
            // InMemoryClientRegistrationRepository rejects empty collections with
            // IllegalArgumentException("registrations cannot be empty").  When no OAuth
            // providers are configured yet (.env has not been filled in), return a
            // lightweight no-op implementation instead.  findByRegistrationId always
            // returns null, which is equivalent to "provider not registered" — exactly
            // the same behavior as if the bean wasn't present.  Keeping the bean around
            // (even when empty) avoids conditional logic around @ConditionalOnMissingBean,
            // and simplifies injection into OAuth2AuthorizedClientService (method-injection
            // just gets a non-null repo that always returns null for lookups).
            return new ClientRegistrationRepository() {
                @Override
                public ClientRegistration findByRegistrationId(String registrationId) {
                    return null;
                }
            };
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

}
