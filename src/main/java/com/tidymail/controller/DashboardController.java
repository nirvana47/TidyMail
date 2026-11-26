package com.tidymail.controller;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.service.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/dashboard")
public class DashboardController {
    private final MessageEvidenceService evidence;
    private final OwnedAccountScopeService accounts;
    private final DashboardService dashboard;
    private final DashboardPreviewService previews;
    private final DashboardRepository repository;
    private final com.tidymail.repository.UserAccountRepository accountRepository;
    private final com.tidymail.mail.MailProvider gmail;
    private final com.tidymail.config.OAuthProviderAvailability availability;
    private record GmailLabels(java.time.Instant expiresAt, Map<String,String> names) {}
    private final Map<Long,GmailLabels> gmailLabels = new java.util.concurrent.ConcurrentHashMap<>();

    public DashboardController(OwnedAccountScopeService accounts, DashboardService dashboard,
            DashboardPreviewService previews, DashboardRepository repository, MessageEvidenceService evidence,
            com.tidymail.repository.UserAccountRepository accountRepository,
            @org.springframework.beans.factory.annotation.Qualifier("gmailMailService") com.tidymail.mail.MailProvider gmail,
            com.tidymail.config.OAuthProviderAvailability availability) {
        this.accountRepository=accountRepository;
        this.gmail=gmail;
        this.availability=availability;
        this.evidence = evidence;
        this.accounts = accounts;
        this.dashboard = dashboard;
        this.previews = previews;
        this.repository = repository;
    }

    @ModelAttribute
    public void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    private long account(OAuth2AuthenticationToken authentication) {
        if (authentication == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue");
        return accounts.resolve(authentication, "current").getFirst();
    }

    @GetMapping
    public String dashboard(OAuth2AuthenticationToken auth, org.springframework.ui.Model model) {
        var active = accountRepository.findById(account(auth)).orElseThrow();
        model.addAttribute("mailboxAccounts", accountRepository.findAllByOwnerKeyOrderByIdAsc(active.getOwnerKey()));
        model.addAttribute("activeAccount", active.getId());
        model.addAttribute("mailboxEmail", active.getEmail());
        model.addAttribute("outlook", "microsoft".equals(active.getProvider()));
        model.addAttribute("outlookAvailable", availability.isMicrosoftConfigured());
        return "dashboard";
    }

    @GetMapping("/api/page")
    @ResponseBody
    public DashboardRepository.Page page(@RequestParam Map<String, String> parameters, OAuth2AuthenticationToken auth) {
        return dashboard.page(account(auth), DashboardFilter.from(parameters));
    }

    @GetMapping("/api/gmail-labels") @ResponseBody
    public Map<String,String> gmailLabels(OAuth2AuthenticationToken auth) {
        long account = account(auth);
        var now = java.time.Instant.now();
        return gmailLabels.compute(account,(id,cached) -> {
            if(cached!=null && cached.expiresAt().isAfter(now)) return cached;
            var owner=accountRepository.findById(id).orElseThrow();
            if(!"google".equals(owner.getProvider())) return new GmailLabels(now.plusSeconds(600),Map.of());
            try {
                var names=new TreeMap<String,String>();
                for(var label:gmail.listFoldersOrLabels(owner))
                    if("user-label".equals(label.type())) names.put(label.id(),label.name());
                return new GmailLabels(now.plusSeconds(600),names);
            } catch(RuntimeException unavailable) {
                return new GmailLabels(now.plusSeconds(60),cached==null ? Map.of() : cached.names());
            }
        }).names();
    }

    @GetMapping("/api/inbox-composition")
    @ResponseBody
    public List<DashboardRepository.Composition> inboxComposition(@RequestParam Map<String,String> parameters,
            OAuth2AuthenticationToken auth) {
        return dashboard.inboxComposition(account(auth),DashboardFilter.from(parameters));
    }

    @PostMapping("/api/messages/{message}/evidence")
    @ResponseBody
    public com.fasterxml.jackson.databind.JsonNode evidence(@PathVariable UUID message, OAuth2AuthenticationToken auth) {
        return evidence.inspect(account(auth), message);
    }

    @GetMapping("/api/groups/{group}")
    @ResponseBody
    public DashboardRepository.Details details(@PathVariable UUID group, @RequestParam Map<String, String> parameters,
            @RequestParam(defaultValue = "0") int messagePage, @RequestParam(defaultValue = "false") boolean unreadOnly, OAuth2AuthenticationToken auth) {
        return dashboard.details(account(auth), DashboardFilter.from(parameters), group, messagePage,parameters.get("address"),unreadOnly);
    }

    @GetMapping("/api/groups/{group}/addresses")
    @ResponseBody
    public DashboardRepository.Addresses addresses(@PathVariable UUID group, @RequestParam Map<String, String> parameters,
            OAuth2AuthenticationToken auth) {
        return dashboard.addresses(account(auth), DashboardFilter.from(parameters), group);
    }

    public record Selection(DashboardFilter filter, List<UUID> groups, String address, long revision, boolean unreadOnly) {
    }

    @PostMapping("/api/selection")
    @ResponseBody
    public List<DashboardRepository.Selected> selection(@RequestBody Selection request,
            OAuth2AuthenticationToken auth) {
        if (request.filter() == null)
            throw new IllegalArgumentException("Filters are required");
        return dashboard.selection(account(auth), request.filter(), request.groups(), request.address(),
                request.revision(), request.unreadOnly());
    }

    @PostMapping("/api/previews")
    @ResponseBody
    public DashboardPreviewService.Preview preview(@RequestBody DashboardPreviewService.Request request,
            OAuth2AuthenticationToken auth) {
        return previews.create(account(auth), request);
    }

    @GetMapping("/api/previews/{id}/targets")
    @ResponseBody
    public DashboardPreviewService.Targets targets(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            OAuth2AuthenticationToken auth) {
        return previews.targets(account(auth), id, page);
    }

    @PostMapping("/api/previews/{id}/invalidate")
    @ResponseBody
    public void invalidate(@PathVariable UUID id, OAuth2AuthenticationToken auth) {
        previews.invalidate(account(auth), id);
    }

    @GetMapping("/api/settings")
    @ResponseBody
    public Map<String, String> settings(OAuth2AuthenticationToken auth) {
        String zone = repository.jdbc().queryForObject(
                "SELECT COALESCE((SELECT display_timezone FROM dashboard_account_settings WHERE account_id=:account),'UTC')",
                Map.of("account", account(auth)), String.class);
        return Map.of("zone", zone);
    }

    @PostMapping("/api/settings")
    @ResponseBody
    public void settings(@RequestBody Map<String, String> value, OAuth2AuthenticationToken auth) {
        String zone = value.get("zone");
        if (zone == null || zone.length() > 64)
            throw new IllegalArgumentException("Invalid timezone");
        java.time.ZoneId.of(zone);
        repository.jdbc().update(
                "INSERT INTO dashboard_account_settings(account_id,display_timezone) VALUES(:account,:zone) ON CONFLICT(account_id) DO UPDATE SET display_timezone=EXCLUDED.display_timezone",
                Map.of("account", account(auth), "zone", zone));
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> statusError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("message", Objects.toString(error.getReason(), "Dashboard request failed")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", Objects.toString(error.getMessage(), "Invalid request")));
    }
}
