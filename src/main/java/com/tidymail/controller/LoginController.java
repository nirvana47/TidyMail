package com.tidymail.controller;

import com.tidymail.config.OAuthProviderAvailability;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller for authentication-related views: /login (provider buttons, conditional on env var
 * presence) and /dashboard (initial landing page post-OAuth, minimal stub to prove login worked —
 * real inbox cards come in Phases 4-7).
 */
@Controller
public class LoginController {

    // NOTE: OAuthProviderAvailability MUST be injected at class level (constructor/field)
    // because passing it as a Spring MVC handler METHOD PARAMETER causes
    // ModelAttributeMethodProcessor / ServletModelAttributeMethodProcessor to instantiate a
    // FRESH copy via the no-arg constructor instead of resolving the singleton @Component.
    // That fresh copy skips AutowiredAnnotationBeanPostProcessor's @Value field injection,
    // so all 5 credential fields are null/empty → every isXxxConfigured() returns false even
    // when real env vars exist.  DebugEnvController used constructor injection and always
    // correctly saw true flags, which helped narrow this down.
    private final OAuthProviderAvailability availability;

    public LoginController(OAuthProviderAvailability availability) {
        this.availability = availability;
    }

    /**
     * Renders the login page, exposing whether each OAuth provider (Google /
     * Microsoft) is configured so the UI can show/hide the sign-in buttons.
     */
    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("googleAvailable", availability.isGoogleConfigured());
        model.addAttribute("microsoftAvailable", availability.isMicrosoftConfigured());
        return "login";
    }

    /**
     * Post-login landing page; currently redirects straight to the Senders
     * dashboard.
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/senders";
    }
}
