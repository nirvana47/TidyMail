package com.tidymail.controller;

import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.UnsubscribeExecutionService;
import com.tidymail.service.UnsubscribeExecutionService.ExecutionException;
import com.tidymail.service.UnsubscribeOutcomeService;
import com.tidymail.service.WorkspaceModelService;
import com.tidymail.unsubscribe.UnsubscribeIntentService.IntentException;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeReasonCode;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class UnsubscribeExecutionController {
    private static final Set<String> MANUAL_FIELDS =
        Set.of("candidateKey", "method", "intent", "confirmed", "_csrf");

    private final WorkspaceModelService workspace;
    private final UnsubscribeExecutionService execution;
    private final UnsubscribeOutcomeService outcomes;

    public UnsubscribeExecutionController(
            WorkspaceModelService workspace,
            UnsubscribeExecutionService execution,
            UnsubscribeOutcomeService outcomes) {
        this.workspace = workspace;
        this.execution = execution;
        this.outcomes = outcomes;
    }

    @PostMapping("/unsubscribe/execute")
    public void retiredAutomatedExecution() {
        throw new ResponseStatusException(
            HttpStatus.GONE, "direct_unsubscribe_execution_removed");
    }

    @PostMapping("/unsubscribe/manual-complete")
    public String manualComplete(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            HttpServletResponse response,
            Model model) {
        try {
            requireFields(parameters, MANUAL_FIELDS);
            String confirmed = optionalOne(parameters, "confirmed");
            if (confirmed != null && !confirmed.equals("true")) {
                throw new IllegalArgumentException("Invalid attestation");
            }
            var result = execution.completeManual(
                owned(authentication),
                one(parameters, "candidateKey"),
                method(one(parameters, "method")),
                one(parameters, "intent"),
                "true".equals(confirmed));
            response.setStatus(result.status().value());
            model.addAttribute("outcome", result.outcome());
        } catch (IntentException blocked) {
            safeError(response, model, blocked.status(), blocked.reason());
        } catch (ExecutionException blocked) {
            safeError(response, model, blocked.status(), blocked.reason());
        } catch (RuntimeException invalid) {
            safeError(response, model, HttpStatus.BAD_REQUEST,
                UnsubscribeReasonCode.STALE_INTENT);
        }
        return "fragments/unsubscribe-outcome-frag :: outcome";
    }

    @GetMapping("/unsubscribe/outcomes/{attemptId}")
    public String outcome(
            @PathVariable long attemptId,
            OAuth2AuthenticationToken authentication,
            Model model) {
        model.addAttribute("outcome", outcomes.viewOwned(owned(authentication), attemptId));
        return "fragments/unsubscribe-outcome-frag :: outcome";
    }

    private List<Long> owned(OAuth2AuthenticationToken authentication) {
        return workspace.build(WorkspaceRoute.UNSUBSCRIBE, "current", authentication)
            .selectedAccountIds();
    }

    private static void safeError(
            HttpServletResponse response,
            Model model,
            HttpStatus status,
            UnsubscribeReasonCode reason) {
        response.setStatus(status.value());
        model.addAttribute("outcomeError", true);
        model.addAttribute("outcomeReason", reason);
        model.addAttribute("outcomeErrorCopy", switch (reason) {
            case PROTECTED_SENDER ->
                "This sender is now protected. No unsubscribe action was recorded.";
            case EXPIRED_INTENT ->
                "This confirmation expired. Inspect the candidate again.";
            case REPLAYED_INTENT ->
                "This confirmation was already used. Inspect the latest outcome.";
            default ->
                "The candidate or method changed. Inspect it again before continuing.";
        });
    }

    private static void requireFields(
            MultiValueMap<String, String> parameters, Set<String> allowlist) {
        if (!allowlist.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Unexpected request field");
        }
        one(parameters, "candidateKey");
        one(parameters, "method");
        one(parameters, "intent");
        one(parameters, "_csrf");
    }

    private static String one(MultiValueMap<String, String> parameters, String name) {
        String value = optionalOne(parameters, name);
        if (value == null) throw new IllegalArgumentException("Missing request field");
        return value;
    }

    private static String optionalOne(
            MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) return null;
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()
                || values.getFirst().length() > 2048
                || values.getFirst().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid request field");
        }
        return values.getFirst();
    }

    private static UnsubscribeMethod method(String value) {
        try {
            return UnsubscribeMethod.valueOf(value);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
