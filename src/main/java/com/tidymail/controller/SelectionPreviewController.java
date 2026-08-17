package com.tidymail.controller;

import com.tidymail.dto.SelectionMode;
import com.tidymail.dto.SelectionRequest;
import com.tidymail.dto.SelectionRequest.OverrideReason;
import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.SelectionScopePreview.State;
import com.tidymail.dto.SelectionSurface;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.service.AnalyticsSenderScopeResolver;
import com.tidymail.service.SelectionMaterializationService;
import com.tidymail.service.WorkspaceModelService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class SelectionPreviewController {
    private static final Set<String> SENDER_QUERY_PARAMETERS = Set.of(
        "account", "search", "window", "type", "risk", "unread",
        "queue", "protected", "sort", "page", "analyticsKey");
    private static final Set<String> UNREAD_QUERY_PARAMETERS = Set.of(
        "account", "search", "type", "age", "waiting", "thread",
        "recommendation", "sender", "provider", "sort", "page");
    private static final Set<String> FORM_PARAMETERS = Set.of(
        "surface", "mode", "action", "rowKey", "includeProtected", "reasonCode", "_csrf");

    private final OwnedAccountScopeService accountScopeService;
    private final SelectionMaterializationService materializationService;
    private final WorkspaceModelService workspaceModelService;
    private final AnalyticsSenderScopeResolver analyticsSenderScopes;

    public SelectionPreviewController(
            OwnedAccountScopeService accountScopeService,
            SelectionMaterializationService materializationService,
            WorkspaceModelService workspaceModelService,
            AnalyticsSenderScopeResolver analyticsSenderScopes) {
        this.accountScopeService = accountScopeService;
        this.materializationService = materializationService;
        this.workspaceModelService = workspaceModelService;
        this.analyticsSenderScopes = analyticsSenderScopes;
    }

    @PostMapping("/api/selection-preview")
    public String preview(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String hxRequest,
            OAuth2AuthenticationToken authentication,
            Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        SelectionRequest request;
        SelectionScopePreview preview;
        try {
            request = request(parameters);
            List<Long> accountIds;
            if (request.surface() == SelectionSurface.SENDERS
                    && !request.senderQuery().analyticsKey().isBlank()) {
                var resolved =
                    analyticsSenderScopes.resolve(authentication, request.senderQuery());
                accountIds = resolved.accountIds();
                request = request.withAnalyticsSenderScope(resolved.scope());
            } else {
                accountIds =
                    accountScopeService.resolve(authentication, request.accountSelection());
            }
            preview = materializationService.preview(accountIds, request);
        } catch (IllegalArgumentException invalid) {
            request = fallbackRequest(parameters);
            preview = invalidPreview();
        } catch (ResponseStatusException invalidQuery) {
            if (invalidQuery.getStatusCode() != HttpStatus.BAD_REQUEST) throw invalidQuery;
            request = fallbackRequest(parameters);
            preview = invalidPreview();
        }

        response.setStatus(status(preview.state()).value());
        populate(model, request, preview);
        if (!"true".equalsIgnoreCase(hxRequest)) {
            WorkspaceRoute route = request.surface() == SelectionSurface.SENDERS
                ? WorkspaceRoute.SENDERS : WorkspaceRoute.UNREAD;
            model.addAttribute("workspace", workspaceModelService.build(
                route, request.accountSelection(), authentication));
            return "selection-preview";
        }
        return "fragments/selection-preview-frag";
    }

    private static SelectionRequest request(MultiValueMap<String, String> parameters) {
        SelectionSurface surface = enumeration(
            single(parameters, "surface", true), SelectionSurface.class, "surface");
        rejectUnknownParameters(parameters, surface);
        SelectionMode mode = enumeration(
            single(parameters, "mode", true), SelectionMode.class, "mode");
        PreviewAction action = enumeration(
            single(parameters, "action", true), PreviewAction.class, "action");
        String include = single(parameters, "includeProtected", false);
        boolean includeProtected;
        if (include == null) {
            includeProtected = false;
        } else if ("true".equals(include)) {
            includeProtected = true;
        } else {
            throw new IllegalArgumentException("Invalid protected override");
        }
        String reasonValue = single(parameters, "reasonCode", false);
        OverrideReason reason = reasonValue == null ? null
            : enumeration(reasonValue, OverrideReason.class, "reasonCode");
        Map<String, List<String>> queryParameters = queryParameters(parameters, surface);
        return new SelectionRequest(
            surface,
            mode,
            surface == SelectionSurface.SENDERS
                ? SenderQuery.parse(queryParameters) : null,
            surface == SelectionSurface.UNREAD
                ? UnreadQuery.parse(queryParameters) : null,
            parameters.getOrDefault("rowKey", List.of()),
            action,
            includeProtected,
            reason);
    }

    private static void rejectUnknownParameters(
            MultiValueMap<String, String> parameters, SelectionSurface surface) {
        Set<String> query = surface == SelectionSurface.SENDERS
            ? SENDER_QUERY_PARAMETERS : UNREAD_QUERY_PARAMETERS;
        if (parameters.keySet().stream()
                .anyMatch(name -> !FORM_PARAMETERS.contains(name) && !query.contains(name))) {
            throw new IllegalArgumentException("Unknown preview parameter");
        }
    }

    private static SelectionRequest fallbackRequest(
            MultiValueMap<String, String> parameters) {
        SelectionSurface surface;
        try {
            surface = enumeration(
                single(parameters, "surface", true), SelectionSurface.class, "surface");
        } catch (RuntimeException ignored) {
            surface = SelectionSurface.SENDERS;
        }
        return new SelectionRequest(
            surface,
            SelectionMode.ALL_MATCHING,
            surface == SelectionSurface.SENDERS ? SenderQuery.parse(Map.of()) : null,
            surface == SelectionSurface.UNREAD ? UnreadQuery.parse(Map.of()) : null,
            List.of(),
            PreviewAction.REVIEW,
            false,
            null);
    }

    private static Map<String, List<String>> queryParameters(
            MultiValueMap<String, String> parameters, SelectionSurface surface) {
        Set<String> recognized = surface == SelectionSurface.SENDERS
            ? SENDER_QUERY_PARAMETERS : UNREAD_QUERY_PARAMETERS;
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        parameters.forEach((key, values) -> {
            if (recognized.contains(key)) result.put(key, List.copyOf(values));
        });
        return result;
    }

    private static String single(
            MultiValueMap<String, String> parameters, String name, boolean required) {
        List<String> values = parameters.get(name);
        if (values == null || values.isEmpty()) {
            if (required) throw new IllegalArgumentException("Missing " + name);
            return null;
        }
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return values.getFirst();
    }

    private static <E extends Enum<E>> E enumeration(
            String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid " + field, invalid);
        }
    }

    private static SelectionScopePreview invalidPreview() {
        return new SelectionScopePreview(
            State.INVALID, List.of(), 0, 0, 0, 0, 0, 0, 0,
            null, 0, "The selection is invalid or no longer matches the indexed data.",
            null);
    }

    private static HttpStatus status(State state) {
        return switch (state) {
            case READY, EMPTY -> HttpStatus.OK;
            case PROTECTED_BLOCKED -> HttpStatus.CONFLICT;
            case CAP_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
    }

    private static void populate(
            Model model, SelectionRequest request, SelectionScopePreview preview) {
        String path = request.surface() == SelectionSurface.SENDERS ? "/senders" : "/unread";
        UriComponentsBuilder returnUrl = UriComponentsBuilder.fromPath(path);
        request.canonicalQuery().forEach(returnUrl::queryParam);
        model.addAttribute("preview", preview);
        model.addAttribute("surface", request.surface());
        model.addAttribute("mode", request.mode());
        model.addAttribute("action", request.action());
        model.addAttribute("rowKeys", request.rowKeys());
        model.addAttribute("canonicalQuery", request.canonicalQuery());
        model.addAttribute(
            "changeSelectionUrl",
            returnUrl.fragment("selection-heading").build().encode().toUriString());
        model.addAttribute("overrideReasons", OverrideReason.values());
    }
}
