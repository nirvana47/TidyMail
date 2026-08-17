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

/**
 * Computes and renders the bulk-action selection preview before the user
 * commits to an action.
 *
 * <p>Product functionality supported: the <em>Bulk Actions</em> feature's
 * &quot;what will this affect?&quot; step. Given a selection (surface, mode,
 * action, row keys, and the originating query), it materializes the exact
 * scope of messages that would be affected and returns a preview fragment (or
 * full page) showing the count, risk, and any protected-message warnings so
 * the user can confirm before anything is executed.</p>
 */
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

    /**
     * Builds the selection preview for the submitted request.
     *
     * <p>Resolves the target account(s) (including analytics-sender scopes),
     * materializes the preview, and returns either an htmx fragment or a full
     * page. Invalid or stale selections fall back to an INVALID preview rather
     * than erroring, and the HTTP status reflects the preview state (e.g. 409
     * when protected messages block the action).</p>
     */
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

    /**
     * Parses and validates the selection request from the submitted form
     * parameters (surface, mode, action, protected override, reason, row keys,
     * and the originating query).
     */
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

    /**
     * Rejects the request if any parameter is not a recognized form or query
     * parameter for the given surface.
     */
    private static void rejectUnknownParameters(
            MultiValueMap<String, String> parameters, SelectionSurface surface) {
        Set<String> query = surface == SelectionSurface.SENDERS
            ? SENDER_QUERY_PARAMETERS : UNREAD_QUERY_PARAMETERS;
        if (parameters.keySet().stream()
                .anyMatch(name -> !FORM_PARAMETERS.contains(name) && !query.contains(name))) {
            throw new IllegalArgumentException("Unknown preview parameter");
        }
    }

    /**
     * Builds a safe fallback request (ALL_MATCHING, REVIEW action, no rows)
     * used when the submitted request is invalid, so the UI can show an
     * INVALID preview instead of crashing.
     */
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

    /**
     * Extracts the recognized query parameters for the given surface from the
     * submitted form.
     */
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

    /**
     * Reads a single-valued parameter, returning {@code null} when optional and
     * absent, and rejecting missing (when required), duplicate, or blank values.
     */
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

    /**
     * Parses a string into an enum value (case-insensitive), rejecting invalid
     * values.
     */
    private static <E extends Enum<E>> E enumeration(
            String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid " + field, invalid);
        }
    }

    /**
     * Builds an INVALID preview describing a selection that no longer matches
     * the indexed data.
     */
    private static SelectionScopePreview invalidPreview() {
        return new SelectionScopePreview(
            State.INVALID, List.of(), 0, 0, 0, 0, 0, 0, 0,
            null, 0, "The selection is invalid or no longer matches the indexed data.",
            null);
    }

    /**
     * Maps a preview state to the HTTP status the response should carry.
     */
    private static HttpStatus status(State state) {
        return switch (state) {
            case READY, EMPTY -> HttpStatus.OK;
            case PROTECTED_BLOCKED -> HttpStatus.CONFLICT;
            case CAP_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * Adds the preview, surface, mode, action, row keys, canonical query,
     * change-selection URL, and override reasons to the model for rendering.
     */
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
