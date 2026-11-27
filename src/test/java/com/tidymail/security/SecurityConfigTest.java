package com.tidymail.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class SecurityConfigTest {

    @Test
    void desktopCodeExchangeSendsTheSessionVerifierAndDesktopClientFields() {
        var builder = authorizationRequest("desktop.apps.googleusercontent.com");
        SecurityConfig.customizeAuthorizationRequest(builder, true);
        var request = builder.build();
        String verifier = request.getAttribute("code_verifier");
        var registration = org.springframework.security.oauth2.client.registration.ClientRegistration
            .withRegistrationId("google")
            .clientId("desktop.apps.googleusercontent.com").clientSecret("synthetic-desktop-client-field")
            .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationUri("https://accounts.example.test/authorize")
            .tokenUri("https://accounts.example.test/token")
            .redirectUri(request.getRedirectUri()).build();
        var rest = new org.springframework.web.client.RestTemplate(java.util.List.of(
            new org.springframework.http.converter.FormHttpMessageConverter(),
            new org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter()));
        var provider = org.springframework.test.web.client.MockRestServiceServer.bindTo(rest).build();
        provider.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://accounts.example.test/token"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("code_verifier=" + verifier)))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("client_secret=synthetic-desktop-client-field")))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("code=synthetic-code")))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"access_token\":\"synthetic-access\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"synthetic-refresh\"}",
                org.springframework.http.MediaType.APPLICATION_JSON));
        var response = org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse
            .success("synthetic-code").redirectUri(request.getRedirectUri()).state(request.getState()).build();
        var exchange = new org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange(request, response);
        var client = new org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient();
        client.setRestOperations(rest);
        var tokens = client.getTokenResponse(new org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest(registration, exchange));
        assertThat(tokens.getRefreshToken().getTokenValue()).isEqualTo("synthetic-refresh");
        provider.verify();
    }

    @Test
    void desktopConsentUsesPkceAndFullScopesWithoutIncrementalAuthorization() {
        var builder = authorizationRequest("desktop.apps.googleusercontent.com");
        SecurityConfig.customizeAuthorizationRequest(builder, true);
        var request = builder.build();
        assertThat(request.getAdditionalParameters())
            .containsEntry("code_challenge_method", "S256")
            .containsEntry("access_type", "offline")
            .doesNotContainKey("include_granted_scopes");
        assertThat(request.getAttribute("code_verifier").toString()).hasSizeBetween(43, 128);
    }

    @Test
    void desktopListenerAcceptsOnlyItsOwnLoopbackHostAndOrigin() throws Exception {
        var filter = new DesktopLoopbackFilter();
        for (String[] values : new String[][] {
                {"127.0.0.1", "127.0.0.1:49152", "http://127.0.0.1:49152", "200"},
                {"127.0.0.1", "127.0.0.1:49152", "", "200"},
                {"192.0.2.1", "127.0.0.1:49152", "", "403"},
                {"127.0.0.1", "foreign.example.test:49152", "", "403"},
                {"127.0.0.1", "127.0.0.1:49152", "https://foreign.example.test", "403"},
                {"127.0.0.1", "127.0.0.1:49152", "null", "403"}}) {
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/login");
            request.setRemoteAddr(values[0]);
            request.setLocalPort(49152);
            request.addHeader("Host", values[1]);
            if (!values[2].isEmpty()) request.addHeader("Origin", values[2]);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            var reachedApp = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> reachedApp.set(true));
            assertThat(response.getStatus()).isEqualTo(Integer.parseInt(values[3]));
            assertThat(reachedApp.get()).isEqualTo("200".equals(values[3]));
        }
    }

    @Test
    void googleAuthorizationRequestsRequireOfflineConsent() {
        OAuth2AuthorizationRequest.Builder builder = authorizationRequest(
            "test.apps.googleusercontent.com");

        SecurityConfig.customizeAuthorizationRequest(builder);

        OAuth2AuthorizationRequest request = builder.build();
        assertThat(request.getAdditionalParameters())
            .containsEntry("access_type", "offline")
            .containsEntry("prompt", "consent")
            .containsEntry("code_challenge_method", "S256")
            .containsKey("code_challenge")
            .doesNotContainKey("approval_prompt");
        assertThat(request.getAttribute("code_verifier").toString()).hasSizeBetween(43, 128);
        assertThat(request.getScopes()).doesNotContain("offline_access");
    }

    @Test
    void nonGoogleAuthorizationRequestsAreUnchanged() {
        OAuth2AuthorizationRequest.Builder builder = authorizationRequest("microsoft-client");

        SecurityConfig.customizeAuthorizationRequest(builder);

        assertThat(builder.build().getAdditionalParameters()).isEmpty();
    }

    private static OAuth2AuthorizationRequest.Builder authorizationRequest(String clientId) {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.example.test/authorize")
            .clientId(clientId)
            .redirectUri("http://localhost/login/oauth2/code/provider")
            .scopes(Set.of("openid", "email", "profile"))
            .state("test-state");
    }
}
