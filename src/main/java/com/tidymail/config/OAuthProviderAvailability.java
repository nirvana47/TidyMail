package com.tidymail.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OAuthProviderAvailability implements InitializingBean {

    @Value("${GOOGLE_CLIENT_ID:}")
    private String googleClientId;

    @Value("${GOOGLE_CLIENT_SECRET:}")
    private String googleClientSecret;

    @Value("${GOOGLE_SECRET:}")
    private String googleSecretAlias;

    @Value("${MICROSOFT_CLIENT_ID:}")
    private String microsoftClientId;

    @Value("${MICROSOFT_CLIENT_SECRET:}")
    private String microsoftClientSecret;

    @Value("${MICROSOFT_SECRET:}")
    private String microsoftSecretAlias;

    @Value("${MICROSOFT_TENANT_ID:common}")
    private String microsoftTenantId;

    public OAuthProviderAvailability() {
    }

    public OAuthProviderAvailability(String googleClientId, String googleClientSecret,
                                     String microsoftClientId, String microsoftClientSecret,
                                     String microsoftTenantId) {
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        this.microsoftClientId = microsoftClientId;
        this.microsoftClientSecret = microsoftClientSecret;
        this.microsoftTenantId = microsoftTenantId != null ? microsoftTenantId : "common";
    }

    @Override
    public void afterPropertiesSet() {
        // Alias support: accept shorter GOOGLE_SECRET / MICROSOFT_SECRET in user .env files
        // as a fallback for the canonical GOOGLE_CLIENT_SECRET / MICROSOFT_CLIENT_SECRET keys.
        if ((googleClientSecret == null || googleClientSecret.isBlank())
                && googleSecretAlias != null && !googleSecretAlias.isBlank()) {
            googleClientSecret = googleSecretAlias;
        }
        if ((microsoftClientSecret == null || microsoftClientSecret.isBlank())
                && microsoftSecretAlias != null && !microsoftSecretAlias.isBlank()) {
            microsoftClientSecret = microsoftSecretAlias;
        }
    }

    public boolean isGoogleConfigured() {
        return googleClientId != null && !googleClientId.isBlank()
            && !googleClientId.startsWith("your-") && !googleClientId.equals("skipped")
            && googleClientSecret != null && !googleClientSecret.isBlank()
            && !googleClientSecret.startsWith("your-") && !googleClientSecret.equals("skipped");
    }

    public boolean isMicrosoftConfigured() {
        return microsoftClientId != null && !microsoftClientId.isBlank()
            && !microsoftClientId.startsWith("your-") && !microsoftClientId.equals("skipped")
            && microsoftClientSecret != null && !microsoftClientSecret.isBlank()
            && !microsoftClientSecret.startsWith("your-") && !microsoftClientSecret.equals("skipped");
    }

    public String getGoogleClientId() {
        return googleClientId;
    }

    public String getGoogleClientSecret() {
        return googleClientSecret;
    }

    public String getMicrosoftClientId() {
        return microsoftClientId;
    }

    public String getMicrosoftClientSecret() {
        return microsoftClientSecret;
    }

    public String getMicrosoftTenantId() {
        return microsoftTenantId != null ? microsoftTenantId : "common";
    }
}
