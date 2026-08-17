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

/**
 * Handles post-OAuth login success by initializing the per-owner account session
 * and routing the user to the activity page when their mailbox still needs an
 * initial bootstrap sync.
 * <p>
 * Product functionality supported: session-based account selection and the
 * initial mailbox bootstrap flow after Gmail/Outlook login.
 */
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

    /**
     * Initializes the account session for the authenticated OAuth principal and
     * redirects to {@code /activity} when the mailbox bootstrap is still pending,
     * otherwise defers to the default target URL handling.
     */
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
