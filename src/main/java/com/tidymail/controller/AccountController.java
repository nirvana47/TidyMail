package com.tidymail.controller;

import com.tidymail.service.AccountSessionService;
import com.tidymail.service.UserDataDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles the account management web workflows: linking a new mail provider
 * (Gmail/Outlook) to a session via OAuth, and the full-account data-deletion
 * flow on logout.
 *
 * <p>Product functionality supported: the <em>Account Connections</em> section
 * of the settings/workspace screens. {@code connect()} drives the OAuth
 * authorization-code redirect that links an additional mailbox to the current
 * user, while {@code deleteDataAndLogout()} implements the GDPR-style
 * "delete my data" self-service flow that wipes all locally stored mailbox
 * data before signing the user out.
 */
@Controller
public class AccountController {

    private static final String DELETE_CONFIRMATION = "DELETE";

    private final ClientRegistrationRepository registrations;
    private final AccountSessionService accountSessions;
    private final UserDataDeletionService deletionService;

    public AccountController(
            ClientRegistrationRepository registrations,
            AccountSessionService accountSessions,
            UserDataDeletionService deletionService) {
        this.registrations = registrations;
        this.accountSessions = accountSessions;
        this.deletionService = deletionService;
    }

    /**
     * Begins the OAuth "add a new mailbox account" flow for the given provider
     * (e.g. {@code gmail}, {@code outlook}).
     *
     * <p>The chosen provider must be configured at the server; otherwise the
     * request 404s. If valid, we record the intent to add an account before
     * redirecting to Spring Security's OAuth authorization endpoint, so the
     * OAuth callback knows this is a new-account link rather than an existing
     * session.
     */
    @GetMapping("/accounts/connect/{provider}")
    public String connect(
            @PathVariable String provider,
            HttpServletRequest request,
            OAuth2AuthenticationToken authentication) {
        if (registrations.findByRegistrationId(provider) == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Connection provider is unavailable");
        }
        accountSessions.beginAddAccount(request, authentication);
        return "redirect:/oauth2/authorization/" + provider;
    }

    /**
     * Deletes all of the current user's stored mailbox data and then signs them
     * out. Supports the &quot;delete my data&quot; privacy feature.
     *
     * <p>Requires the literal confirmation value {@code "DELETE"} to prevent
     * accidental or automated submissions. Only the local TidyMail copy of the
     * user's data is removed (it never touches the mail provider directly),
     * and the destructive purge is performed <em>before</em> the session is
     * logged out so the authenticated ownership context is still available.</p>
     */
    @PostMapping("/logout/delete-data")
    public String deleteDataAndLogout(
            @RequestParam String confirmation,
            HttpServletRequest request,
            HttpServletResponse response,
            OAuth2AuthenticationToken authentication) {
        if (!DELETE_CONFIRMATION.equals(confirmation)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Delete-data confirmation is required");
        }
        String ownerKey = accountSessions.ownerKey(request.getSession(), authentication);
        deletionService.deleteOwnerData(ownerKey);
        new SecurityContextLogoutHandler().logout(
            request, response, (Authentication) authentication);
        return "redirect:/login?deleted";
    }
}
