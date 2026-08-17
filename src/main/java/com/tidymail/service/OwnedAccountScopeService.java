package com.tidymail.service;

import com.tidymail.dto.AccountSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class OwnedAccountScopeService {

    private final AccountSessionService accountSessions;

    public OwnedAccountScopeService(AccountSessionService accountSessions) {
        this.accountSessions = accountSessions;
    }

    public List<Long> resolve(OAuth2AuthenticationToken authentication, String selection) {
        AccountSession session = accountSessions.resolve(
            currentSession(), authentication, selection);
        return List.of(session.activeAccountId());
    }

    private static HttpSession currentSession() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getSession();
        }
        return null;
    }
}
