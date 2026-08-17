package com.tidymail.controller;

import com.tidymail.dto.ReplyZeroItem;
import com.tidymail.dto.SenderPageResponse;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.PageResult;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ReplyZeroDetector;
import com.tidymail.service.SenderAggregator;
import com.tidymail.service.SenderStatsPersistService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the htmx fragments that power the Senders dashboard, Reply Zero
 * queue, and sync-progress widgets without full page reloads.
 *
 * <p>Product functionality supported: the <em>Senders</em> dashboard (sender
 * rows and domain rollups), the <em>Reply Zero</em> queue, and the
 * <em>sync progress</em> indicator. These fragments read from the persisted
 * sync snapshot (never hitting the mail provider on page load) so the
 * dashboard stays fast, except the Reply Zero fragment which re-queries the
 * provider for the latest inbox messages.</p>
 */
@Controller
public class FragmentController {

    private final Map<String, MailProvider> mailProviderMap;
    private final UserAccountRepository userAccountRepository;
    private final SenderAggregator senderAggregator;
    private final SenderStatsPersistService senderStatsPersistService;
    private final ReplyZeroDetector replyZeroDetector;
    private final SyncProgressRepository syncProgressRepository;

    public FragmentController(
            Map<String, MailProvider> mailProviderMap,
            UserAccountRepository userAccountRepository,
            SenderAggregator senderAggregator,
            SenderStatsPersistService senderStatsPersistService,
            ReplyZeroDetector replyZeroDetector,
            SyncProgressRepository syncProgressRepository) {
        this.mailProviderMap = mailProviderMap;
        this.userAccountRepository = userAccountRepository;
        this.senderAggregator = senderAggregator;
        this.senderStatsPersistService = senderStatsPersistService;
        this.replyZeroDetector = replyZeroDetector;
        this.syncProgressRepository = syncProgressRepository;
    }

    /**
     * Maps a stored account's provider string to the Spring bean that talks to
     * that mail provider (Gmail or Outlook).
     */
    private MailProvider resolveProvider(UserAccount account) {
        String beanName = switch (account.getProvider()) {
            case "google" -> "gmailMailService";
            case "microsoft" -> "outlookMailService";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown provider");
        };
        MailProvider p = mailProviderMap.get(beanName);
        if (p == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "provider bean not loaded");
        return p;
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
     * Renders the Reply Zero queue fragment: fetches the latest inbox messages
     * from the provider, aggregates them by sender, and runs the Reply Zero
     * detector to surface personal-looking messages awaiting a reply (capped at
     * 20 items).
     */
    @GetMapping(value = "/reply-zero-frag", produces = "text/html;charset=UTF-8")
    public String replyZeroFragment(
            @RequestParam(defaultValue = "3") int olderThanDays,
            Model model,
            OAuth2AuthenticationToken principal) {
        UserAccount account = currentAccount(principal);
        MailProvider provider = resolveProvider(account);
        olderThanDays = Math.max(1, olderThanDays);
        PageResult<MailMessage> page = provider.listMessages(account, null, 100);
        SenderAggregator.AggregationResult agg = senderAggregator.aggregate(page.items());
        List<ReplyZeroItem> allItems = replyZeroDetector.analyze(
                page.items(), agg.perSender(), account, provider, olderThanDays, Instant.now());
        List<ReplyZeroItem> items = allItems.stream().limit(20).toList();
        model.addAttribute("items", items);
        return "fragments/reply-zero-frag";
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
