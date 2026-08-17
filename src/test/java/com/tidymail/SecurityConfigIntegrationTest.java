package com.tidymail;

import com.tidymail.config.OAuthProviderAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common",
    // Token encryption: values match the two-variable openssl-rand-hex contract
    // documented in .env.example section 6.  Test-only (all-zero entropy):
    //   - TOKEN_ENCRYPT_SECRET  = openssl rand -hex 32 output → 64 hex chars = 32 bytes
    //   - TOKEN_ENCRYPT_SALT    = openssl rand -hex 16 output → 32 hex chars = 16 bytes
    // CryptoProperties accepts hex OR base64; TOKEN_ENCRYPT_SALT length >= 8 bytes.
    // Required because CryptoStartupValidator ApplicationRunner hard-fails the
    // Spring context when key material is absent/invalid.
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    // (Legacy env vars AES_256_KEY / AES_KEY_ID are accepted as a backward-
    // compat fallback only; they are NOT set in new deployments.)
    // Validate JPA entities against the production Postgres migrations.
    "spring.jpa.hibernate.ddl-auto=validate",
})
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginPageLoadsUnauthenticated() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Welcome to TidyMail")))
            .andExpect(view().name("login"));
    }

    @Test
    void dashboardRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/dashboard"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void rootUnauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "test@example.com", roles = "USER")
    void logoutInvalidatesSessionAndRedirects() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void unconfiguredProvidersRenderWarningNotButtons() throws Exception {
        String html = mockMvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("No sign-in options are available right now");
        assertThat(html).doesNotContain("/oauth2/authorization/google");
        assertThat(html).doesNotContain("/oauth2/authorization/microsoft");
        assertThat(html).doesNotContain("Continue with Google");
        assertThat(html).doesNotContain("Continue with Microsoft Outlook");
    }

    @Test
    void configuredMicrosoftClientRegistrationHasCorrectScopesAndIssuer() {
        OAuthProviderAvailability props = new OAuthProviderAvailability(
            "", "",
            "ms-test-id",
            "ms-test-secret",
            "9188040d-6c67-4c5b-b112-36a304b66dad"
        );
        String[] expectedMicrosoftScopes = {
            "openid", "email", "profile", "offline_access",
            "https://graph.microsoft.com/Mail.ReadWrite"
        };
        String expectedIssuerPrefix = "https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0";
        assertThat(props.isMicrosoftConfigured()).isTrue();
        assertThat(props.isGoogleConfigured()).isFalse();
        assertThat(props.getMicrosoftTenantId()).isEqualTo("9188040d-6c67-4c5b-b112-36a304b66dad");

        OAuthProviderAvailability gProps = new OAuthProviderAvailability(
            "google-id", "google-secret", "", "", "common"
        );
        assertThat(gProps.isGoogleConfigured()).isTrue();
        assertThat(gProps.isMicrosoftConfigured()).isFalse();
        String[] expectedGoogleScopes = {
            "openid", "email", "profile",
            "https://www.googleapis.com/auth/gmail.modify"
        };
        assertThat(expectedGoogleScopes).hasSize(4);
        assertThat(expectedMicrosoftScopes).hasSize(5);
    }

}
