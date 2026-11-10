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
    private final LabelingPipeline labeling;
    private final LabelingReviewService labelingReview;
    private final com.tidymail.job.LabelingWorker labelingWorker;
    private final RecommendationService recommendations;
    private final RecommendationProcessingService processing;
    private final MessageEvidenceService evidence;
    private final ReviewedPatternService patterns;
    private final OwnedAccountScopeService accounts;
    private final DashboardService dashboard;
    private final DashboardGroupingService grouping;
    private final DashboardPreviewService previews;
    private final DashboardRepository repository;
    private final DashboardClassificationService classification;
    private final com.tidymail.repository.UserAccountRepository accountRepository;
    private final com.tidymail.mail.MailProvider gmail;
    private final com.tidymail.config.OAuthProviderAvailability availability;
    private record GmailLabels(java.time.Instant expiresAt, Map<String,String> names) {}
    private final Map<Long,GmailLabels> gmailLabels = new java.util.concurrent.ConcurrentHashMap<>();

    public DashboardController(OwnedAccountScopeService accounts, DashboardService dashboard,
            DashboardGroupingService grouping, DashboardPreviewService previews, DashboardRepository repository,
            DashboardClassificationService classification, MessageEvidenceService evidence, ReviewedPatternService patterns,
            RecommendationService recommendations, RecommendationProcessingService processing, LabelingPipeline labeling,
            LabelingReviewService labelingReview,com.tidymail.job.LabelingWorker labelingWorker,
            com.tidymail.repository.UserAccountRepository accountRepository,
            @org.springframework.beans.factory.annotation.Qualifier("gmailMailService") com.tidymail.mail.MailProvider gmail,
            com.tidymail.config.OAuthProviderAvailability availability) {
        this.accountRepository=accountRepository;
        this.gmail=gmail;
        this.availability=availability;
        this.labelingReview=labelingReview;this.labelingWorker=labelingWorker;
        this.recommendations=recommendations; this.processing=processing;this.labeling=labeling;
        this.patterns = patterns;
        this.evidence = evidence;
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
    public String dashboard(OAuth2AuthenticationToken auth, org.springframework.ui.Model model) {
        var active = accountRepository.findById(account(auth)).orElseThrow();
        model.addAttribute("mailboxAccounts", accountRepository.findAllByOwnerKeyOrderByIdAsc(active.getOwnerKey()));
        model.addAttribute("activeAccount", active.getId());
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

    @PostMapping("/api/patterns/preview")
    @ResponseBody
    public ReviewedPatternService.Preview patternPreview(@RequestBody Map<String,Integer> request,OAuth2AuthenticationToken auth) {
        return patterns.preview(account(auth),request.getOrDefault("months",24));
    }
    @PostMapping("/api/patterns/{id}/apply")
    @ResponseBody
    public Map<String,Integer> patternApply(@PathVariable UUID id,@RequestBody ReviewedPatternService.Apply request,OAuth2AuthenticationToken auth) {
        return Map.of("applied",patterns.apply(account(auth),id,request.messages()));
    }

    public record LabelingRequest(List<UUID> messages,String kind,RecommendationSemanticContract.Variant variant,java.math.BigDecimal maxSpend) {}
    public record LabelingApproval(String hash) {}
    public record LabelingBatch(LabelingReviewService.Request selection,java.math.BigDecimal maxSpend) {}
    public record LabelingAllowance(String baseHash,java.math.BigDecimal newLimit,String reviewHash) {}
    @PostMapping("/api/labeling/scopes/{scope}/allowance-review") @ResponseBody
    public LabelingPipeline.AllowanceReview reviewLabelingAllowance(@PathVariable UUID scope,@RequestBody LabelingAllowance request,OAuth2AuthenticationToken auth) {
        try{return labeling.reviewAllowance(account(auth),scope,request.baseHash(),request.newLimit());}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,invalid.getMessage());}
    }
    @PostMapping("/api/labeling/scopes/{scope}/allowance") @ResponseBody
    public LabelingPipeline.ScopeStatus increaseLabelingAllowance(@PathVariable UUID scope,@RequestBody LabelingAllowance request,OAuth2AuthenticationToken auth) {
        try{return labeling.confirmAllowance(account(auth),scope,request.baseHash(),request.newLimit(),request.reviewHash());}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,invalid.getMessage());}
    }
    @PostMapping("/api/labeling/preview") @ResponseBody
    public LabelingReviewService.Preview previewLabels(@RequestBody LabelingReviewService.Request request,OAuth2AuthenticationToken auth) {
        try{return labelingReview.preview(account(auth),request);}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage());}
    }
    @PostMapping("/api/labeling/prepare") @ResponseBody
    public LabelingPipeline.Prepared prepareLabels(@RequestBody LabelingBatch request,OAuth2AuthenticationToken auth) {
        try{return labelingReview.prepare(account(auth),request.selection(),request.maxSpend());}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage());}
    }
    @GetMapping("/api/labeling/scopes") @ResponseBody
    public List<LabelingReviewService.Recent> recentLabels(OAuth2AuthenticationToken auth){return labelingReview.recent(account(auth));}
    @GetMapping("/api/labeling/scopes/{scope}/running") @ResponseBody
    public Map<String,Boolean> labelingRunning(@PathVariable UUID scope,OAuth2AuthenticationToken auth) {
        long account=account(auth);labeling.progress(account,scope);return Map.of("running",labelingWorker.active(account,scope));
    }
    public record LabelingProgress(LabelingPipeline.ScopeStatus status,boolean running) {}
    @GetMapping("/api/labeling/scopes/{scope}/progress") @ResponseBody
    public LabelingProgress labelingProgress(@PathVariable UUID scope,OAuth2AuthenticationToken auth) {
        long account=account(auth);return new LabelingProgress(labeling.progress(account,scope),labelingWorker.active(account,scope));
    }
    @PostMapping("/api/labeling/scopes/{scope}/start") @ResponseBody
    public LabelingPipeline.ScopeStatus startLabeling(@PathVariable UUID scope,@RequestBody LabelingApproval request,OAuth2AuthenticationToken auth) {
        long account=account(auth);
        try{labelingWorker.startReviewed(account,scope,request.hash());return labeling.progress(account,scope);}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,invalid.getMessage());}
    }
    @GetMapping("/api/labeling/scopes/{scope}") @ResponseBody
    public LabelingPipeline.ScopeStatus labelingStatus(@PathVariable UUID scope,OAuth2AuthenticationToken auth) {
        return labeling.scopeStatus(account(auth),scope);
    }
    @PostMapping("/api/labeling/scopes") @ResponseBody
    public LabelingPipeline.Prepared prepareLabeling(@RequestBody LabelingRequest request,OAuth2AuthenticationToken auth) {
        try {return labeling.prepare(account(auth),request.messages(),request.kind(),request.variant(),request.maxSpend());}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage());}
    }
    @PostMapping("/api/labeling/scopes/{scope}/authorize") @ResponseBody
    public Map<String,Boolean> authorizeLabeling(@PathVariable UUID scope,@RequestBody LabelingApproval request,OAuth2AuthenticationToken auth) {
        try {labeling.authorize(account(auth),scope,request.hash());return Map.of("authorized",true);}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed labeling scope unavailable for this account");}
    }
    @PostMapping("/api/labeling/scopes/{scope}/pause") @ResponseBody
    public Map<String,Boolean> pauseLabeling(@PathVariable UUID scope,OAuth2AuthenticationToken auth) {
        labeling.pause(account(auth),scope);return Map.of("paused",true);
    }
    @PostMapping("/api/labeling/scopes/{scope}/resume") @ResponseBody
    public Map<String,Boolean> resumeLabeling(@PathVariable UUID scope,@RequestBody LabelingApproval request,OAuth2AuthenticationToken auth) {
        try {labeling.resume(account(auth),scope,request.hash());return Map.of("paused",false);}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed labeling scope unavailable for this account");}
    }
    @PostMapping("/api/labeling/scopes/{scope}/retry-mime") @ResponseBody
    public Map<String,Boolean> retryLabelingMime(@PathVariable UUID scope,@RequestBody LabelingApproval request,OAuth2AuthenticationToken auth) {
        try {labeling.retryMime(account(auth),scope,request.hash());return Map.of("retryPrepared",true);}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed MIME scope unavailable for this account");}
    }

    public record RecommendationCorrection(MessageRecommendation.Action action, MessageRecommendation.Importance importance, long revision) {}
    @GetMapping("/api/recommendations") @ResponseBody
    public RecommendationProcessingService.Status recommendationStatus(OAuth2AuthenticationToken auth) { return processing.status(account(auth)); }
    public record RecommendationProcessing(boolean enabled) {}
    @PostMapping("/api/recommendations/processing") @ResponseBody
    public RecommendationProcessingService.Status recommendationProcessing(@RequestBody RecommendationProcessing request,OAuth2AuthenticationToken auth) {
        return processing.enable(account(auth),request.enabled());
    }
    @PostMapping("/api/messages/{message}/recommendation") @ResponseBody
    public RecommendationService.View recommendationCorrection(@PathVariable UUID message,@RequestBody RecommendationCorrection correction,OAuth2AuthenticationToken auth) {
        long account=account(auth);
        recommendations.correct(account,message,correction.action(),correction.importance(),correction.revision());
        recommendations.recompute(account,List.of(message));
        return recommendations.read(account,List.of(message)).getFirst();
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

    @GetMapping("/api/groups/{group}/grouping")
    @ResponseBody
    public DashboardRepository.Grouping grouping(@PathVariable UUID group, OAuth2AuthenticationToken auth) {
        return dashboard.grouping(account(auth), group);
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

    public record AutomaticClassification(boolean enabled) {}
    @PostMapping("/api/classification/automatic")
    @ResponseBody
    public DashboardClassificationService.Status automaticClassification(@RequestBody AutomaticClassification request,OAuth2AuthenticationToken auth) {
        long account=account(auth); classification.automatic(account,request.enabled()); return classification.status(account);
    }
    public record ContinueClassification(long generation,boolean acknowledgeUnknown) {}
    @PostMapping("/api/classification/continue")
    @ResponseBody
    public DashboardClassificationService.Status continueClassification(@RequestBody ContinueClassification request,OAuth2AuthenticationToken auth) {
        long account=account(auth); classification.continueSpending(account,request.generation(),request.acknowledgeUnknown()); return classification.status(account);
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
