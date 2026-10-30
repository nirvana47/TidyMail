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
    private final OwnedAccountScopeService accounts;
    private final DashboardService dashboard;
    private final DashboardGroupingService grouping;
    private final DashboardPreviewService previews;
    private final DashboardRepository repository;
    private final DashboardClassificationService classification;

    public DashboardController(OwnedAccountScopeService accounts, DashboardService dashboard,
            DashboardGroupingService grouping, DashboardPreviewService previews, DashboardRepository repository,
            DashboardClassificationService classification) {
        this.accounts = accounts;
        this.dashboard = dashboard;
        this.grouping = grouping;
        this.previews = previews;
        this.repository = repository;
        this.classification = classification;
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
    public String dashboard(OAuth2AuthenticationToken auth) {
        account(auth);
        return "dashboard";
    }

    @GetMapping("/api/page")
    @ResponseBody
    public DashboardRepository.Page page(@RequestParam Map<String, String> parameters, OAuth2AuthenticationToken auth) {
        return dashboard.page(account(auth), DashboardFilter.from(parameters));
    }

    @GetMapping("/api/groups/{group}")
    @ResponseBody
    public DashboardRepository.Details details(@PathVariable UUID group, @RequestParam Map<String, String> parameters,
            @RequestParam(defaultValue = "0") int messagePage, OAuth2AuthenticationToken auth) {
        return dashboard.details(account(auth), DashboardFilter.from(parameters), group, messagePage);
    }

    public record Selection(DashboardFilter filter, List<UUID> groups, String address, long revision) {
    }

    @PostMapping("/api/selection")
    @ResponseBody
    public List<DashboardRepository.Selected> selection(@RequestBody Selection request,
            OAuth2AuthenticationToken auth) {
        if (request.filter() == null)
            throw new IllegalArgumentException("Filters are required");
        return dashboard.selection(account(auth), request.filter(), request.groups(), request.address(),
                request.revision());
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

    @GetMapping("/api/corrections")
    @ResponseBody
    public List<Map<String, Object>> corrections(OAuth2AuthenticationToken auth) {
        return repository.jdbc().queryForList(
                "SELECT public_id,kind,label,created_at FROM sender_group_corrections WHERE account_id=:account AND active ORDER BY id DESC",
                Map.of("account", account(auth)));
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

    @PostMapping("/api/corrections/preview")
    @ResponseBody
    public DashboardGroupingService.Review correctionPreview(@RequestBody DashboardGroupingService.Correction request,
            OAuth2AuthenticationToken auth) {
        return grouping.correct(account(auth), request, false);
    }

    @PostMapping("/api/corrections")
    @ResponseBody
    public DashboardGroupingService.Review correction(@RequestBody DashboardGroupingService.Correction request,
            OAuth2AuthenticationToken auth) {
        return grouping.correct(account(auth), request, true);
    }

    @GetMapping("/api/classification")
    @ResponseBody
    public DashboardClassificationService.Status classification(OAuth2AuthenticationToken auth) {
        return classification.status(account(auth));
    }

    @PostMapping("/api/classification/start")
    @ResponseBody
    public DashboardClassificationService.Status classify(OAuth2AuthenticationToken auth) {
        return classification.start(account(auth));
    }

    @PostMapping("/api/classification/cancel")
    @ResponseBody
    public DashboardClassificationService.Status cancelClassification(OAuth2AuthenticationToken auth) {
        return classification.cancel(account(auth));
    }

    public record CategoryCorrection(List<UUID> ids, String category, long revision) {
    }

    @PostMapping("/api/classification/category")
    @ResponseBody
    public void category(@RequestBody CategoryCorrection request, OAuth2AuthenticationToken auth) {
        classification.correctCategory(account(auth), request.ids(), request.category(), request.revision());
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
