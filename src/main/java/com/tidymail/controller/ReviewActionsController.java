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

/**
 * The Review page controller — the heart of TidyMail's safe bulk-action
 * workflow.
 *
 * <p>Product functionality supported: the <em>Review</em> workspace tab, where
 * staged cleanup actions are approved, executed, undone, or cancelled. It
 * handles single and batch approval/undo confirmations, unsubscribe approval,
 * conflict resolution, and the &quot;remove protection&quot; (compensation)
 * flow, returning either htmx fragments for in-place updates or full Review
 * page renders.</p>
 */
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

    /**
     * Shows the email-stats modal for a single staged action (which exact
     * messages it targets), either as an htmx fragment or within the Review
     * page.
     */
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

    /**
     * Builds the approval confirmation for executing a single staged action.
     */
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

    /**
     * Builds the confirmation for un-staging (undoing the staging of) a single
     * action before it has been executed.
     */
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

    /**
     * Prepares the confirmation for undoing an already-executed action,
     * showing what the undo will reverse.
     */
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

    /**
     * Executes the undo of a previously executed action and returns the result
     * fragment.
     */
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

    /**
     * Prepares the confirmation for the &quot;remove protection&quot;
     * (compensation) flow, which lets the user proceed with an action that
     * would otherwise be blocked by protected messages.
     */
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

    /**
     * Executes the remove-protection (compensation) flow for an action and
     * returns the result fragment.
     */
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

    /**
     * Approves and executes an unsubscribe action for a single staged action.
     */
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

    /**
     * Prepares the confirmation for approving an unsubscribe action.
     */
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

    /**
     * Cancels a staged action (removes it from the Review queue).
     */
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

    /**
     * Builds the approval confirmation for executing a batch of selected
     * staged actions.
     */
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

    /**
     * Builds the confirmation for un-staging a batch of selected actions.
     */
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

    /**
     * Un-stages (removes from the queue) a batch of selected actions.
     */
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

    /**
     * Builds the approval confirmation for executing all staged actions in the
     * current Review view.
     */
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

    /**
     * Executes a batch of selected staged actions and returns the outcome.
     */
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

    /**
     * Executes all staged actions in the current Review view and returns the
     * outcome.
     */
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

    /**
     * Shows the detail of a single action conflict (an action that can't be
     * executed as staged) and its resolution options.
     */
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

    /**
     * Resolves a conflict by applying the chosen command to the affected
     * action.
     */
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

    /**
     * Legacy blanket-apply endpoint; the blanket apply flow has been removed
     * in favor of explicit per-action approval, so this always returns 410.
     */
    @PostMapping("/api/actions/review/apply")
    public String removedBlanketApply() {
        throw new ResponseStatusException(HttpStatus.GONE, "blanket_apply_removed");
    }

    /**
     * Renders the approval confirmation, either as an htmx fragment or within
     * the full Review page.
     */
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

    /**
     * Renders the batch execution outcome, either as an htmx fragment or within
     * the full Review page.
     */
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

    /**
     * Builds the workspace chrome and the Review queue page model for the given
     * query and adds them to the model.
     */
    private void populate(
            Model model,
            OAuth2AuthenticationToken principal,
            ReviewQuery query) {
        WorkspacePageModel workspace = workspaceModels.build(
            WorkspaceRoute.REVIEW, query.account(), principal);
        model.addAttribute("workspace", workspace);
        model.addAttribute("page", reviewActions.queue(owner(principal), workspace, query));
    }

    /**
     * Parses the Review filter parameters (view, account, action, state,
     * target, page) into a {@link ReviewQuery}, rejecting unknown or malformed
     * parameters.
     */
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

    /**
     * Convenience factory for a 400 Bad Request with the review error reason.
     */
    private static ResponseStatusException invalidRequest() {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "invalid_review_request");
    }

    /**
     * Derives the owner key from the OAuth principal.
     */
    private static String owner(OAuth2AuthenticationToken principal) {
        return ActionCommandController.ownerKey(principal);
    }

    /**
     * Requires exactly one account to be in scope, returning its id; used by
     * single-account operations like undo and compensation.
     */
    private static long oneAccount(List<Long> accountIds) {
        if (accountIds.size() != 1) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "one_account_required");
        }
        return accountIds.getFirst();
    }
}
