package com.tidymail.security.oauth2;

import com.tidymail.entity.UserAccount;
import com.tidymail.job.IncrementalSyncJob;
import com.tidymail.repository.UserAccountRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginSuccessListener {

    private final JpaOAuth2AuthorizedClientService jpaOAuth2AuthorizedClientService;
    private final UserAccountRepository userAccountRepository;
    private final IncrementalSyncJob incrementalSyncJob;

    public OAuthLoginSuccessListener(JpaOAuth2AuthorizedClientService jpaOAuth2AuthorizedClientService,
                                     UserAccountRepository userAccountRepository,
                                     IncrementalSyncJob incrementalSyncJob) {
        this.jpaOAuth2AuthorizedClientService = jpaOAuth2AuthorizedClientService;
        this.userAccountRepository = userAccountRepository;
        this.incrementalSyncJob = incrementalSyncJob;
    }

    @EventListener(AuthenticationSuccessEvent.class)
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();

        if (!(authentication instanceof OAuth2LoginAuthenticationToken oauth2Token)) {
            return;
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof OAuth2User oauth2User)) {
            return;
        }

        Map<String, Object> attributes = oauth2User.getAttributes();
        String registrationId = oauth2Token.getClientRegistration().getRegistrationId();
        String principalName = oauth2Token.getName();
        boolean newAccount = userAccountRepository
            .findByProviderAndProviderAccountId(registrationId, principalName)
            .isEmpty();

        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(
            oauth2Token.getClientRegistration(),
            principalName,
            oauth2Token.getAccessToken(),
            oauth2Token.getRefreshToken());
        jpaOAuth2AuthorizedClientService.save(authorizedClient, attributes);

        userAccountRepository.findByProviderAndProviderAccountId(registrationId, principalName)
            .ifPresent(account -> {
                account.setLastLoginAt(Instant.now());
                userAccountRepository.save(account);
                if ("google".equals(registrationId)) {
                    if (newAccount) {
                        incrementalSyncJob.requestBootstrap(account.getId());
                    } else {
                        incrementalSyncJob.requestSync(account.getId());
                    }
                }
            });
    }
}
