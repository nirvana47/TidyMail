package com.tidymail.controller;

import com.tidymail.dto.SenderPageResponse;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.SenderStatsPersistService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves sender rows, domain rollups, and sync-progress fragments from the
 * persisted snapshot without provider calls on page load.
 */
@Controller
public class FragmentController {

    private final UserAccountRepository userAccountRepository;
    private final SenderStatsPersistService senderStatsPersistService;
    private final SyncProgressRepository syncProgressRepository;

    public FragmentController(
            UserAccountRepository userAccountRepository,
            SenderStatsPersistService senderStatsPersistService,
            SyncProgressRepository syncProgressRepository) {
        this.userAccountRepository = userAccountRepository;
        this.senderStatsPersistService = senderStatsPersistService;
        this.syncProgressRepository = syncProgressRepository;
    }

    /**
     * Resolves the authenticated user's mailbox account row from the OAuth
     * principal (email + provider).
     */
    private UserAccount currentAccount(OAuth2AuthenticationToken token) {
        OAuth2AuthenticatedPrincipal principal = token.getPrincipal();
        String email = principal.getAttribute("email");
        String provider = token.getAuthorizedClientRegistrationId();
        if (email == null || provider == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return userAccountRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no account row"));
    }

    /**
     * Renders the Senders dashboard fragment (sender rows sorted by the given
     * sort key) from the persisted sync snapshot.
     */
    @GetMapping(value = "/senders-frag", produces = "text/html;charset=UTF-8")
    public String sendersFragment(
            @RequestParam(defaultValue = "countDesc") String sort,
            Model model,
            OAuth2AuthenticationToken principal) {
        UserAccount account = currentAccount(principal);
        // Navigation must only read the snapshot written by the background sync.
        // Re-fetching Gmail here caused 100 metadata API calls on every page load.
        SenderPageResponse response = senderStatsPersistService.dashboardSnapshot(account.getId(), sort);
        model.addAttribute("resp", response);
        return "fragments/senders-frag";
    }

    /**
     * Renders the domain rollups fragment (per-domain message counts) from the
     * persisted sync snapshot.
     */
    @GetMapping(value = "/domain-rollups-frag", produces = "text/html;charset=UTF-8")
    public String domainRollupsFragment(
            Model model,
            OAuth2AuthenticationToken principal) {
        UserAccount account = currentAccount(principal);
        SenderPageResponse response = senderStatsPersistService.dashboardSnapshot(account.getId(), "countDesc");
        model.addAttribute("domains", response.domainRollups());
        return "fragments/domain-rollups-frag";
    }

    /**
     * Renders the sync progress fragment showing the latest incremental sync
     * progress for the account.
     */
    @GetMapping(value = "/sync-progress-frag", produces = "text/html;charset=UTF-8")
    public String syncProgressFragment(OAuth2AuthenticationToken principal, Model model) {
        UserAccount account = currentAccount(principal);
        SyncProgress progress = syncProgressRepository
            .findTopByAccountIdAndJobTypeOrderByIdDesc(account.getId(), "INCREMENTAL_SYNC")
            .orElse(null);
        model.addAttribute("progress", progress);
        return "fragments/sync-progress-frag";
    }
}
