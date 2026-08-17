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
