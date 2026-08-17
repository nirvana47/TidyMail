package com.tidymail.mail.gmail;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.tidymail.entity.UserAccount;
import com.tidymail.security.oauth2.ManagedGoogleCredentialService;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Date;
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
            Date expiration = managedToken.getExpiresAt() == null
                ? null
                : Date.from(managedToken.getExpiresAt());
            GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken(managedToken.getTokenValue(), expiration));
            HttpCredentialsAdapter credentialAdapter = new HttpCredentialsAdapter(credentials);

            return new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JacksonFactory.getDefaultInstance(),
                    credentialAdapter)
                .setApplicationName("TidyMail/0.1.0")
                .setHttpRequestInitializer(request -> {
                    request.setConnectTimeout(10000);
                    request.setReadTimeout(60000);
                    credentialAdapter.initialize(request);
                })
                .build();
        } catch (GeneralSecurityException | IOException ex) {
            throw new GmailClientInitException(
                "Failed to build Gmail service for account " + account.getEmail(), ex);
        }
    }
}
