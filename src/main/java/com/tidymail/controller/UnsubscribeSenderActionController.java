package com.tidymail.controller;

import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.UnsubscribeSenderActionService;
import com.tidymail.service.WorkspaceModelService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class UnsubscribeSenderActionController {
    private static final Set<String> QUERY_FIELDS = Set.of(
        "account", "category", "from", "to", "range", "q", "defaultAction", "sort", "page");
    private static final Set<String> FORM_FIELDS = Set.of("rowKey", "action", "_csrf");

    private final WorkspaceModelService workspace;
    private final UnsubscribeSenderActionService actions;

    public UnsubscribeSenderActionController(
            WorkspaceModelService workspace,
            UnsubscribeSenderActionService actions) {
        this.workspace = workspace;
        this.actions = actions;
    }

    @PostMapping("/unsubscribe/sender-action-preview")
    public String preview(
            @RequestParam MultiValueMap<String, String> parameters,
            OAuth2AuthenticationToken authentication,
            jakarta.servlet.http.HttpServletResponse response,
            Model model) {
        if (parameters.keySet().stream()
                .anyMatch(key -> !QUERY_FIELDS.contains(key) && !FORM_FIELDS.contains(key))) {
            throw new IllegalArgumentException("Unknown unsubscribe action field");
        }
        UnsubscribeQuery query = UnsubscribeQuery.parse(queryParameters(parameters));
        var page = workspace.build(WorkspaceRoute.UNSUBSCRIBE, query.account(), authentication);
        PreviewAction action = action(one(parameters, "action"));
        SelectionScopePreview preview = actions.preview(
            ActionCommandController.ownerKey(authentication),
            page.selectedAccountIds(),
            query,
            one(parameters, "rowKey"),
            action);
        response.setStatus(status(preview).value());
        model.addAttribute("preview", preview);
        model.addAttribute("action", action);
        return "fragments/selection-preview-frag :: preview";
    }

    private static Map<String, List<String>> queryParameters(
            MultiValueMap<String, String> parameters) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (QUERY_FIELDS.contains(key)) result.put(key, List.copyOf(value));
        });
        return result;
    }

    private static String one(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null || values.size() != 1 || values.getFirst() == null
                || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return values.getFirst();
    }

    private static PreviewAction action(String value) {
        return switch (value) {
            case "ARCHIVE" -> PreviewAction.ARCHIVE;
            case "TRASH" -> PreviewAction.TRASH;
            default -> throw new IllegalArgumentException("Archive or Delete is required");
        };
    }

    private static HttpStatus status(SelectionScopePreview preview) {
        return switch (preview.state()) {
            case READY, EMPTY -> HttpStatus.OK;
            case PROTECTED_BLOCKED -> HttpStatus.CONFLICT;
            case CAP_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
    }
}
