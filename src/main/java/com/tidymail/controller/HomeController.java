package com.tidymail.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Root path redirector — {@code /} goes to {@code /dashboard} for signed-in users, {@code /login}
 * for guests. Tiny class; exists primarily so users opening the Cloudflare URL without a sub-path
 * get routed correctly.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = auth != null && auth.isAuthenticated()
            && !(auth instanceof AnonymousAuthenticationToken);
        return signedIn ? "redirect:/dashboard" : "redirect:/login";
    }
}
