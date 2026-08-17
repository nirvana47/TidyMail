package com.tidymail.service;

import com.tidymail.dto.AccountSession;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the authenticated user's session state: which connected mailbox account
 * is active, how new accounts are attached to an owner, and how the active account
 * is resolved from a request.
 *
 * <p>Product functionality supported: multi-account support across the workspace,
 * including login, adding accounts, and per-request account selection.
 */
@Service
public class AccountSessionService {

    static final String OWNER_KEY = AccountSessionService.class.getName() + ".OWNER_KEY";
    static final String ACTIVE_ACCOUNT_ID =
        AccountSessionService.class.getName() + ".ACTIVE_ACCOUNT_ID";
    static final String PENDING_ADD_OWNER_KEY =
        AccountSessionService.class.getName() + ".PENDING_ADD_OWNER_KEY";

    private final UserAccountRepository accounts;

    public AccountSessionService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Completes a login, attaching any pending owner key and activating the account. */
    public AccountSession initializeLogin(
            HttpServletRequest request, OAuth2AuthenticationToken authentication) {
        UserAccount authenticated = authenticatedAccount(authentication);
        HttpSession session = request.getSession();
        Object pending = session.getAttribute(PENDING_ADD_OWNER_KEY);
        if (pending instanceof String ownerKey && !ownerKey.isBlank()) {
            authenticated.setOwnerKey(ownerKey);
            authenticated = accounts.saveAndFlush(authenticated);
            session.removeAttribute(PENDING_ADD_OWNER_KEY);
        }
        session.setAttribute(OWNER_KEY, authenticated.getOwnerKey());
        session.setAttribute(ACTIVE_ACCOUNT_ID, authenticated.getId());
        return new AccountSession(authenticated.getOwnerKey(), authenticated.getId());
    }

    /** Begins adding a new account by recording the owner key as pending. */
    public String beginAddAccount(
            HttpServletRequest request, OAuth2AuthenticationToken authentication) {
        String ownerKey = ownerKey(request.getSession(), authentication);
        request.getSession().setAttribute(PENDING_ADD_OWNER_KEY, ownerKey);
        return ownerKey;
    }

    /** Resolves the active account for a request, honoring the requested selection. */
    public AccountSession resolve(
            HttpSession session,
            OAuth2AuthenticationToken authentication,
            String requestedSelection) {
        String ownerKey = ownerKey(session, authentication);
        List<UserAccount> owned = accounts.findAllByOwnerKeyOrderByIdAsc(ownerKey);
        if (owned.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No connected account is owned by this user");
        }

        String requested = requestedSelection == null ? "" : requestedSelection.trim();
        if ("all".equalsIgnoreCase(requested)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "All-account scope is not supported");
        }

        Long selected = requestedAccountId(requested);
        if (selected == null) {
            Object active = session == null ? null : session.getAttribute(ACTIVE_ACCOUNT_ID);
            if (active instanceof Long activeId
                    && owned.stream().anyMatch(account -> account.getId().equals(activeId))) {
                selected = activeId;
            }
        }
        if (selected == null) {
            UserAccount authenticated = authenticatedAccount(authentication);
            selected = owned.stream()
                .filter(account -> account.getId().equals(authenticated.getId()))
                .map(UserAccount::getId)
                .findFirst()
                .orElse(owned.getFirst().getId());
        }

        Long activeId = selected;
        if (owned.stream().noneMatch(account -> account.getId().equals(activeId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account is not owned");
        }
        if (session != null) {
            session.setAttribute(OWNER_KEY, ownerKey);
            session.setAttribute(ACTIVE_ACCOUNT_ID, activeId);
        }
        return new AccountSession(ownerKey, activeId);
    }

    /** Returns the owner key for the session, falling back to the authenticated account. */
    public String ownerKey(
            HttpSession session, OAuth2AuthenticationToken authentication) {
        if (session != null) {
            Object stored = session.getAttribute(OWNER_KEY);
            if (stored instanceof String ownerKey && !ownerKey.isBlank()) {
                return ownerKey;
            }
        }
        UserAccount authenticated = authenticatedAccount(authentication);
        if (session != null) {
            session.setAttribute(OWNER_KEY, authenticated.getOwnerKey());
        }
        return authenticated.getOwnerKey();
    }

    /** Loads the connected account for the OAuth authentication, or rejects it. */
    private UserAccount authenticatedAccount(OAuth2AuthenticationToken authentication) {
        String provider = authentication.getAuthorizedClientRegistrationId();
        return accounts.findByProviderAndProviderAccountId(provider, authentication.getName())
            .or(() -> {
                String email = authentication.getPrincipal().getAttribute("email");
                return email == null ? java.util.Optional.empty()
                    : accounts.findByEmailAndProviderIgnoreCase(email, provider);
            })
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Authenticated account is not connected"));
    }

    /** Parses a requested account selector, returning null for the default/current scope. */
    private static Long requestedAccountId(String requested) {
        if (requested.isEmpty()
                || "current".equalsIgnoreCase(requested)
                || "default".equalsIgnoreCase(requested)) {
            return null;
        }
        try {
            long value = Long.parseLong(requested);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed account ID");
        }
    }
}
