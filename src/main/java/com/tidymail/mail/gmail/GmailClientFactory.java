package com.tidymail.mail.gmail;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.tidymail.entity.UserAccount;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService;
import java.io.IOException;
import java.security.GeneralSecurityException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Component;

/**
 * Builds authenticated Gmail API clients for Google user accounts, wiring an
 * end-user's OAuth token into a configured HTTP transport.
 *
 * <p>Product functionality supported: Gmail provider OAuth client creation.
 */
@Component
public class GmailClientFactory {

    private final ManagedGoogleCredentialService credentialService;
    private volatile com.google.api.client.http.HttpTransport transport;

    private com.google.api.client.http.HttpTransport transport() throws GeneralSecurityException,IOException {
        if(transport==null)synchronized(this){if(transport==null)transport=GoogleNetHttpTransport.newTrustedTransport();}
        return transport;
    }

    public GmailClientFactory(ManagedGoogleCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    public Gmail buildFor(UserAccount account) throws GmailClientInitException {
        String provider = account.getProvider();
        if (provider == null || !provider.equalsIgnoreCase("google")) {
            throw new GmailClientInitException(
                "GmailClientFactory only handles GOOGLE accounts; got " + provider);
        }

        OAuth2AuthorizedClient authorizedClient = credentialService.authorize(account);
        OAuth2AccessToken managedToken = authorizedClient.getAccessToken();

        try {
            return new Gmail.Builder(
                    transport(),
                    JacksonFactory.getDefaultInstance(),
                    null)
                .setApplicationName("TidyMail/0.1.0")
                .setHttpRequestInitializer(request -> {
                    request.setConnectTimeout(10000);
                    request.setReadTimeout(30000);
                    request.setNumberOfRetries(0);
                    // Spring owns refresh. A token-only GoogleCredentials adapter cannot refresh
                    // itself and previously threw near expiry during long-running background sync.
                    request.getHeaders().setAuthorization("Bearer "+managedToken.getTokenValue());
                })
                .build();
        } catch (GeneralSecurityException | IOException ex) {
            throw new GmailClientInitException(
                "Failed to build Gmail service for account " + account.getEmail(), ex);
        }
    }
}
