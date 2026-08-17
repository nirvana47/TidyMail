package com.tidymail.mail.gmail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the Google OAuth developer-credentials (client ID / secret) directly
 * from the environment, matching the variable names used throughout the rest
 * of the app and in {@code .env.example} section 3.
 *
 * <p>We deliberately use {@code @Component + @Value} here instead of
 * {@code @ConfigurationProperties(prefix = "app.google")}.  The reason is
 * that the user-facing variable names are {@code GOOGLE_CLIENT_ID} and
 * {@code GOOGLE_CLIENT_SECRET} — matching how Spring Security's OAuth2
 * client registration builder in {@code SecurityConfig} reads them.  A
 * {@code @ConfigurationProperties} with prefix {@code app.google} would
 * require the env vars to be spelled {@code APP_GOOGLE_CLIENT_ID} under
 * Spring's relaxed-binding rules, which is a different shape and would force
 * users to declare the same credential twice.
 */
/**
 * Reads the Google OAuth developer credentials (client ID / secret) from the
 * environment and reports whether they are configured.
 *
 * <p>Product functionality supported: Gmail provider OAuth configuration.
 */
@Component
public class GoogleOAuthConfigProperties {

    @Value("${GOOGLE_CLIENT_ID:}")
    private String clientId;

    @Value("${GOOGLE_CLIENT_SECRET:}")
    private String clientSecret;

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }
}
