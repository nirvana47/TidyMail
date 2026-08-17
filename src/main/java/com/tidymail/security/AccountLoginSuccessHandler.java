package com.tidymail.security;

import com.tidymail.service.AccountSessionService;
import com.tidymail.repository.MailboxSyncStateRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class AccountLoginSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {

    private final AccountSessionService accountSessions;
    private final MailboxSyncStateRepository mailboxStates;

    public AccountLoginSuccessHandler(
            AccountSessionService accountSessions,
            MailboxSyncStateRepository mailboxStates) {
        this.accountSessions = accountSessions;
        this.mailboxStates = mailboxStates;
        setDefaultTargetUrl("/dashboard");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)) {
            throw new ServletException("OAuth account authentication is required");
        }
        var account = accountSessions.initializeLogin(request, oauth);
        boolean pendingBootstrap = mailboxStates.findByAccountId(account.activeAccountId())
            .map(state -> state.initialBatchCompletedAt() == null)
            .orElse(false);
        if (pendingBootstrap) {
            getRedirectStrategy().sendRedirect(request, response, "/activity");
            return;
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
