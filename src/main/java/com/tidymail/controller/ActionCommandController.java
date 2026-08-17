package com.tidymail.controller;

import com.tidymail.action.ExecutionMode;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.OwnedAccountScopeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ActionCommandController {
    private static final Set<String> PARAMETERS = Set.of(
        "confirmation", "executionMode", "immediateAcknowledged",
        "unsubscribeMethod", "_csrf");

    private final OwnedAccountScopeService accountScopes;
    private final ActionCommandService commands;

    public ActionCommandController(
            OwnedAccountScopeService accountScopes,
            ActionCommandService commands) {
        this.accountScopes = accountScopes;
        this.commands = commands;
    }

    @PostMapping("/api/action-commands")
    public String submit(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String hxRequest,
              HttpServletRequest request,
            OAuth2AuthenticationToken authentication,
            Model model) {
          if (request.getQueryString() != null) {
              throw new ResponseStatusException(
                  HttpStatus.BAD_REQUEST, "query_action_command_parameters_forbidden");
          }
        rejectUnknown(parameters);
        String confirmation = single(parameters, "confirmation", true);
        String mode = single(parameters, "executionMode", false);
        String acknowledged = single(parameters, "immediateAcknowledged", false);
        String unsubscribeMethod = single(parameters, "unsubscribeMethod", false);
        if (acknowledged != null && !"true".equals(acknowledged)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "invalid_immediate_acknowledgement");
        }
        String ownerKey = ownerKey(authentication);
        List<Long> authorized = accountScopes.resolve(authentication, "current");
        ActionCommandService.CommandResult result = commands.submit(
            ownerKey, authorized, confirmation, mode, acknowledged != null,
            unsubscribeMethod);
        model.addAttribute("commandResult", result);
        if ("true".equalsIgnoreCase(hxRequest)) {
            return "fragments/action-command-result-frag";
        }
        String account = String.valueOf(result.actions().getFirst().getAccountId());
        return "redirect:/review?view=queued&account=" + account;
    }

    static String ownerKey(OAuth2AuthenticationToken authentication) {
        String email = authentication.getPrincipal().getAttribute("email");
        String provider = authentication.getAuthorizedClientRegistrationId();
        if (email == null || provider == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return email.strip().toLowerCase() + ":" + provider;
    }

    private static void rejectUnknown(MultiValueMap<String, String> values) {
        if (values.keySet().stream().anyMatch(key -> !PARAMETERS.contains(key))) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unknown_action_command_parameter");
        }
    }

    private static String single(
            Map<String, List<String>> values, String name, boolean required) {
        List<String> found = values.get(name);
        if (found == null || found.isEmpty()) {
            if (required) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "missing_" + name);
            return null;
        }
        if (found.size() != 1 || found.getFirst() == null
                || found.getFirst().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "invalid_" + name);
        }
        return found.getFirst();
    }
}
