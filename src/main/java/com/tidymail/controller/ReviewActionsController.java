package com.tidymail.controller;

import com.tidymail.dto.ApprovalConfirmation;
import com.tidymail.dto.BatchExecutionReceipt;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.ActionApprovalService;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.ActionConflictService;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.service.ReviewActionService;
import com.tidymail.service.EmailStatsModalService;
import com.tidymail.service.WorkspaceModelService;
import com.tidymail.service.UndoService;
import com.tidymail.service.CompensationService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ReviewActionsController {
    private static final Set<String> FILTER_KEYS =
        Set.of("view", "account", "action", "state", "target", "page");
    private static final String CSRF_PARAMETER = "_csrf";
    private final ActionCommandService commands;
    private final OwnedAccountScopeService accountScopes;
    private final ActionApprovalService approvals;
    private final ActionConflictService conflicts;
    private final WorkspaceModelService workspaceModels;
    private final ReviewActionService reviewActions;
    private final EmailStatsModalService emailStats;
    private final UndoService undo;
    private final CompensationService compensation;

    public ReviewActionsController(
            ActionCommandService commands,
            OwnedAccountScopeService accountScopes,
            ActionApprovalService approvals,
            ActionConflictService conflicts,
            WorkspaceModelService workspaceModels,
            ReviewActionService reviewActions,
            EmailStatsModalService emailStats,
            UndoService undo,
            CompensationService compensation) {
        this.commands = commands;
        this.accountScopes = accountScopes;
        this.approvals = approvals;
        this.conflicts = conflicts;
        this.workspaceModels = workspaceModels;
        this.reviewActions = reviewActions;
        this.emailStats = emailStats;
        this.undo = undo;
        this.compensation = compensation;
    }

    @GetMapping("/review/actions/{actionPublicId}/emails")
    public String emails(
            @PathVariable UUID actionPublicId,
            @RequestParam(defaultValue = "current") String account,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        model.addAttribute("emailStats", emailStats.review(
            accountScopes.resolve(principal, account), account, actionPublicId));
        if ("true".equalsIgnoreCase(htmx)) {
            return "fragments/email-stats-modal-frag :: modal";
        }
        populate(model, principal, new ReviewQuery(
            "queued", account, "all", "all", "", 0));
        return "review";
    }

    @PostMapping("/review/actions/{actionPublicId}/execute/confirm")
    public String confirmOne(
            @PathVariable UUID actionPublicId,
            @RequestParam(defaultValue = "current") String account,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = new ReviewQuery(
            "queued", account, "all", "all", "", 0);
        ApprovalConfirmation confirmation = approvals.confirmSelected(
            owner(principal), accountScopes.resolve(principal, account),
            query, List.of(actionPublicId));
        return confirmation(model, principal, query, confirmation, htmx);
    }

    @PostMapping("/review/actions/{actionPublicId}/undo-staged/confirm")
    public String confirmUndoOne(
            @PathVariable UUID actionPublicId,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = new ReviewQuery(
            "queued", account, "all", "all", "", 0);
        model.addAttribute("reviewUndoConfirmation",
            approvals.confirmUndoSelected(
                owner(principal), accountScopes.resolve(principal, account),
                query, List.of(actionPublicId)));
        return "fragments/review-confirmation-frag :: undoConfirmation";
    }

    @PostMapping("/review/actions/{actionPublicId}/undo/confirm")
    public String confirmUndo(
            @PathVariable UUID actionPublicId,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal,
            Model model) {
        model.addAttribute("undoConfirmation", undo.prepare(
            owner(principal), oneAccount(accountScopes.resolve(principal, account)),
            actionPublicId));
        return "fragments/undo-confirmation-frag :: confirmation";
    }

    @PostMapping("/api/actions/{actionPublicId}/undo")
    public String executeUndo(
            @PathVariable UUID actionPublicId,
            @RequestParam String undoIntent,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal,
            Model model) {
        model.addAttribute("undoResult", undo.execute(
            owner(principal), oneAccount(accountScopes.resolve(principal, account)),
            actionPublicId, undoIntent));
        return "fragments/undo-confirmation-frag :: result";
    }

    @PostMapping("/review/actions/{actionPublicId}/remove-protection/confirm")
    public String confirmCompensation(
            @PathVariable UUID actionPublicId,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal,
            Model model) {
        model.addAttribute("compensationConfirmation", compensation.prepare(
            owner(principal), oneAccount(accountScopes.resolve(principal, account)),
            actionPublicId));
        return "fragments/compensation-frag :: confirmation";
    }

    @PostMapping("/api/actions/{actionPublicId}/remove-protection")
    public String executeCompensation(
            @PathVariable UUID actionPublicId,
            @RequestParam String compensationIntent,
            @RequestParam(required = false) String executionMode,
            @RequestParam(defaultValue = "false") boolean immediateAcknowledged,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal,
            Model model) {
        model.addAttribute("compensationResult", compensation.submit(
            owner(principal), oneAccount(accountScopes.resolve(principal, account)),
            actionPublicId, compensationIntent, executionMode,
            immediateAcknowledged));
        return "fragments/compensation-frag :: result";
    }

    @PostMapping("/api/actions/{actionPublicId}/approve")
    public String approveUnsubscribe(
            @PathVariable UUID actionPublicId,
            @RequestParam String confirmation,
            @RequestParam String unsubscribeMethod,
            OAuth2AuthenticationToken principal) {
        commands.approveUnsubscribe(
            owner(principal), accountScopes.resolve(principal, "current"),
            actionPublicId, confirmation, unsubscribeMethod);
        return "redirect:/review?view=queued";
    }

    @PostMapping("/review/unsubscribe/{actionPublicId}/confirm")
    public String confirmUnsubscribe(
            @PathVariable UUID actionPublicId,
            @RequestParam(defaultValue = "current") String account,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        model.addAttribute("unsubscribeConfirmation",
            commands.prepareUnsubscribeApproval(
                owner(principal), accountScopes.resolve(principal, account),
                actionPublicId));
        if ("true".equalsIgnoreCase(htmx)) {
            return "fragments/review-confirmation-frag :: unsubscribeConfirmation";
        }
        ReviewQuery query = new ReviewQuery(
            "queued", account, "all", "all", "", 0);
        populate(model, principal, query);
        return "review";
    }

    @PostMapping("/api/actions/{actionPublicId}/cancel")
    public String cancel(
            @PathVariable UUID actionPublicId,
            @RequestParam String operationIntent,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal) {
        approvals.cancel(
            owner(principal), accountScopes.resolve(principal, account),
            actionPublicId, operationIntent);
        return "redirect:/review?view=queued&account=" + account;
    }

    @PostMapping("/review/approve-selected/confirm")
    public String confirmSelected(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam("selectedAction") List<UUID> selected,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = query(parameters, Set.of("selectedAction"));
        ApprovalConfirmation confirmation = approvals.confirmSelected(
            owner(principal), accountScopes.resolve(principal, query.account()),
            query, selected);
        return confirmation(model, principal, query, confirmation, htmx);
    }

    @PostMapping("/review/undo-selected/confirm")
    public String confirmUndoSelected(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam("selectedAction") List<UUID> selected,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = query(parameters, Set.of("selectedAction"));
        model.addAttribute("reviewUndoConfirmation",
            approvals.confirmUndoSelected(
                owner(principal), accountScopes.resolve(principal, query.account()),
                query, selected));
        return "fragments/review-confirmation-frag :: undoConfirmation";
    }

    @PostMapping("/review/undo-selected")
    public String undoSelected(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam("selectedAction") List<UUID> selected,
            @RequestParam String operationIntent,
            OAuth2AuthenticationToken principal) {
        ReviewQuery query = query(
            parameters, Set.of("selectedAction", "operationIntent"));
        approvals.undoSelected(
            owner(principal), accountScopes.resolve(principal, query.account()),
            query, selected, operationIntent);
        return "redirect:/review?view=queued&account=" + query.account();
    }

    @PostMapping("/review/approve-all/confirm")
    public String confirmAll(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = query(parameters, Set.of());
        ApprovalConfirmation confirmation = approvals.confirmAll(
            owner(principal), accountScopes.resolve(principal, query.account()), query);
        return confirmation(model, principal, query, confirmation, htmx);
    }

    @PostMapping("/review/approve-selected")
    public String executeSelected(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam("selectedAction") List<UUID> selected,
            @RequestParam String operationIntent,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = query(
            parameters, Set.of("selectedAction", "operationIntent"));
        BatchExecutionReceipt receipt = approvals.executeSelected(
            owner(principal), accountScopes.resolve(principal, query.account()),
            query, selected, operationIntent);
        return outcome(model, principal, query, receipt, htmx);
    }

    @PostMapping("/review/approve-all")
    public String executeAll(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam("selectedAction") List<UUID> confirmed,
            @RequestParam String operationIntent,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = query(
            parameters, Set.of("selectedAction", "operationIntent"));
        BatchExecutionReceipt receipt = approvals.executeAll(
            owner(principal), accountScopes.resolve(principal, query.account()),
            query, confirmed, operationIntent);
        return outcome(model, principal, query, receipt, htmx);
    }

    @GetMapping("/review/conflicts/{conflictPublicId}")
    public String conflict(
            @PathVariable UUID conflictPublicId,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String htmx,
            OAuth2AuthenticationToken principal,
            Model model) {
        ReviewQuery query = query(parameters, Set.of());
        model.addAttribute("conflict", conflicts.detail(
            owner(principal), accountScopes.resolve(principal, query.account()),
            conflictPublicId));
        if ("true".equalsIgnoreCase(htmx)) {
            return "fragments/review-conflict-frag :: conflict";
        }
        populate(model, principal, query);
        return "review";
    }

    @PostMapping("/review/conflicts/{conflictPublicId}/resolve")
    public String resolveConflict(
            @PathVariable UUID conflictPublicId,
            @RequestParam String command,
            @RequestParam String operationIntent,
            @RequestParam(defaultValue = "current") String account,
            OAuth2AuthenticationToken principal) {
        conflicts.resolve(
            owner(principal), accountScopes.resolve(principal, account),
            conflictPublicId, command, operationIntent);
        return "redirect:/review?view=queued&account=" + account;
    }

    @PostMapping("/api/actions/review/apply")
    public String removedBlanketApply() {
        throw new ResponseStatusException(HttpStatus.GONE, "blanket_apply_removed");
    }

    private String confirmation(
            Model model,
            OAuth2AuthenticationToken principal,
            ReviewQuery query,
            ApprovalConfirmation confirmation,
            String htmx) {
        model.addAttribute("confirmation", confirmation);
        if ("true".equalsIgnoreCase(htmx)) {
            return "fragments/review-confirmation-frag :: confirmation";
        }
        populate(model, principal, query);
        return "review";
    }

    private String outcome(
            Model model,
            OAuth2AuthenticationToken principal,
            ReviewQuery query,
            BatchExecutionReceipt receipt,
            String htmx) {
        model.addAttribute("receipt", receipt);
        if ("true".equalsIgnoreCase(htmx)) {
            return "fragments/review-outcome-frag :: outcome";
        }
        populate(model, principal, query);
        return "review";
    }

    private void populate(
            Model model,
            OAuth2AuthenticationToken principal,
            ReviewQuery query) {
        WorkspacePageModel workspace = workspaceModels.build(
            WorkspaceRoute.REVIEW, query.account(), principal);
        model.addAttribute("workspace", workspace);
        model.addAttribute("page", reviewActions.queue(owner(principal), workspace, query));
    }

    private static ReviewQuery query(
            MultiValueMap<String, String> parameters, Set<String> allowedExtras) {
        LinkedMultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        parameters.forEach((key, values) -> {
            if (FILTER_KEYS.contains(key)) {
                filters.put(key, values);
                return;
            }
            if (!CSRF_PARAMETER.equals(key) && !allowedExtras.contains(key)) {
                throw invalidRequest();
            }
            if (!"selectedAction".equals(key)
                    && (values == null || values.size() != 1)) {
                throw invalidRequest();
            }
        });
        return ReviewQuery.parse(filters);
    }

    private static ResponseStatusException invalidRequest() {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "invalid_review_request");
    }

    private static String owner(OAuth2AuthenticationToken principal) {
        return ActionCommandController.ownerKey(principal);
    }

    private static long oneAccount(List<Long> accountIds) {
        if (accountIds.size() != 1) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "one_account_required");
        }
        return accountIds.getFirst();
    }
}
