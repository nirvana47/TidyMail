package com.tidymail.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class SecurityConfigTest {

    @Test
    void googleAuthorizationRequestsRequireOfflineConsent() {
        OAuth2AuthorizationRequest.Builder builder = authorizationRequest(
            "test.apps.googleusercontent.com");

        SecurityConfig.customizeAuthorizationRequest(builder);

        OAuth2AuthorizationRequest request = builder.build();
        assertThat(request.getAdditionalParameters())
            .containsEntry("access_type", "offline")
            .containsEntry("prompt", "consent")
            .doesNotContainKey("approval_prompt");
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
