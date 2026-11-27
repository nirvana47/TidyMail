package com.tidymail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protects the desktop HTTP listener against forwarded hosts and DNS rebinding. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.desktop.enabled", havingValue = "true")
public class DesktopLoopbackFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String host = "127.0.0.1:" + request.getLocalPort();
        String origin = request.getHeader("Origin");
        if (!"127.0.0.1".equals(request.getRemoteAddr())
                || !host.equals(request.getHeader("Host"))
                || (origin != null && !("http://" + host).equals(origin))) {
            // Do not dispatch to /error, where authentication could redirect to login.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
