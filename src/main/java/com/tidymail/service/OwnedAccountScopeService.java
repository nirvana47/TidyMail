package com.tidymail.service;

import com.tidymail.dto.AccountSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the active owned account for a request into a concrete account ID
 * list, used to scope queries and actions to the authenticated user's accounts.
 *
 * <p>Product functionality supported: multi-account scoping across the workspace
 * and all cleanup surfaces.
 */
@Service
public class OwnedAccountScopeService {

    private final AccountSessionService accountSessions;

    public OwnedAccountScopeService(AccountSessionService accountSessions) {
        this.accountSessions = accountSessions;
    }

    /** Resolves the selection to the active account ID. */
    public List<Long> resolve(OAuth2AuthenticationToken authentication, String selection) {
        AccountSession session = accountSessions.resolve(
            currentSession(), authentication, selection);
        return List.of(session.activeAccountId());
    }

    /** Returns the current HTTP session, if any. */
    private static HttpSession currentSession() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getSession();
        }
        return null;
    }
}
