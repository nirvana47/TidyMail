package com.tidymail.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Legacy bean name retained for configuration compatibility. Ordinary logout is deliberately
 * session-only; destructive deletion is handled by the confirmed delete-data endpoint.
 */
@Component
public class CascadeDeleteLogoutSuccessHandler extends SimpleUrlLogoutSuccessHandler {

    public CascadeDeleteLogoutSuccessHandler() {
        setDefaultTargetUrl("/login?logout");
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication) throws IOException, ServletException {
        super.onLogoutSuccess(request, response, authentication);
    }
}
