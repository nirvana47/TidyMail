package com.tidymail.controller;

import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionSurface;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.service.CleanupActionService;
import com.tidymail.service.CleanupActionService.StageRequest;
import com.tidymail.service.OwnedAccountScopeService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class CleanupActionController {
    private static final Set<String> SENDER_QUERY_PARAMETERS = Set.of(
        "account", "search", "window", "type", "risk", "unread",
        "queue", "protected", "sort", "page", "analyticsKey");
    private static final Set<String> UNREAD_QUERY_PARAMETERS = Set.of(
        "account", "search", "type", "age", "waiting", "thread",
        "recommendation", "sender", "provider", "sort", "page");
    private static final Set<String> FORM_PARAMETERS = Set.of(
        "surface", "action", "rowKey", "requestToken",
        "deleteAcknowledged", "_csrf");
    private static final Set<String> PARAMETERS = Set.of(
        "surface", "action", "rowKey", "requestToken",
        "deleteAcknowledged", "_csrf",
        "account", "search", "window", "type", "risk", "unread",
        "queue", "protected", "sort", "page", "analyticsKey",
        "age", "waiting", "thread", "recommendation", "sender", "provider");

    private final OwnedAccountScopeService accountScopes;
    private final CleanupActionService cleanupActions;

    public CleanupActionController(
            OwnedAccountScopeService accountScopes,
            CleanupActionService cleanupActions) {
        this.accountScopes = accountScopes;
        this.cleanupActions = cleanupActions;
    }

    @PostMapping("/api/cleanup-actions")
    public ResponseEntity<?> stage(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(name = "HX-Request", required = false) String hxRequest,
            HttpServletRequest servletRequest,
            OAuth2AuthenticationToken authentication) {
        if (servletRequest.getQueryString() != null) {
            throw bad("query_cleanup_action_parameters_forbidden");
        }
        rejectUnknown(parameters, PARAMETERS);
        requireSingleValues(parameters);
        SelectionSurface surface = enumeration(
            single(parameters, "surface", true),
            SelectionSurface.class,
            "surface");
        Set<String> queryNames = surface == SelectionSurface.SENDERS
            ? SENDER_QUERY_PARAMETERS : UNREAD_QUERY_PARAMETERS;
        Set<String> allowed = new java.util.HashSet<>(FORM_PARAMETERS);
        allowed.addAll(queryNames);
        rejectUnknown(parameters, allowed);
        PreviewAction action = enumeration(
            single(parameters, "action", true),
            PreviewAction.class,
            "action");
        String acknowledgement =
            single(parameters, "deleteAcknowledged", false);
        boolean deleteAcknowledged = false;
        if (acknowledgement != null) {
            if (!"true".equals(acknowledgement)) {
                throw bad("invalid_delete_acknowledgement");
            }
            deleteAcknowledged = true;
        }
        UUID requestToken;
        try {
            requestToken = UUID.fromString(single(
                parameters, "requestToken", true));
        } catch (IllegalArgumentException invalid) {
            throw bad("invalid_request_token");
        }
        List<String> rowKeys = parameters.getOrDefault("rowKey", List.of());
        if (rowKeys.isEmpty()
                || rowKeys.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw bad("invalid_rowKey");
        }
        SenderQuery senderQuery = surface == SelectionSurface.SENDERS
            ? SenderQuery.parse(queryParameters(parameters, queryNames)) : null;
        UnreadQuery unreadQuery = surface == SelectionSurface.UNREAD
            ? UnreadQuery.parse(queryParameters(parameters, queryNames)) : null;
        String accountSelection = surface == SelectionSurface.SENDERS
            ? senderQuery.account() : unreadQuery.account();
        List<Long> authorized =
            accountScopes.resolve(authentication, accountSelection);
        final CleanupActionService.StageResult result;
        try {
            result = cleanupActions.stage(
                ActionCommandController.ownerKey(authentication),
                authorized,
                new StageRequest(
                      surface, senderQuery, unreadQuery, action,
                      rowKeys, requestToken, deleteAcknowledged));
        } catch (IllegalArgumentException invalid) {
            throw bad("invalid_cleanup_action");
        }
        if ("true".equalsIgnoreCase(hxRequest)) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create(canonicalReturnUrl(
                surface, senderQuery, unreadQuery, action)))
            .build();
    }

    private static void rejectUnknown(
            MultiValueMap<String, String> parameters,
            Set<String> allowed) {
        if (parameters.keySet().stream().anyMatch(key -> !allowed.contains(key))) {
            throw bad("unknown_cleanup_action_parameter");
        }
    }

    private static void requireSingleValues(
            MultiValueMap<String, String> parameters) {
        parameters.forEach((name, values) -> {
            if (!"rowKey".equals(name)
                    && (values == null
                        || values.size() != 1
                        || values.getFirst() == null
                        || values.getFirst().isBlank())) {
                throw bad("invalid_" + name);
            }
        });
    }

    private static Map<String, List<String>> queryParameters(
            MultiValueMap<String, String> parameters,
            Set<String> queryNames) {
        LinkedHashMap<String, List<String>> query = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (queryNames.contains(name)) {
                query.put(name, List.copyOf(values));
            } else if (!FORM_PARAMETERS.contains(name)) {
                throw bad("unknown_cleanup_action_parameter");
            }
        });
        return query;
    }

    private static String canonicalReturnUrl(
            SelectionSurface surface,
            SenderQuery senderQuery,
            UnreadQuery unreadQuery,
            PreviewAction action) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(
            surface == SelectionSurface.SENDERS ? "/senders" : "/unread");
        Map<String, String> query = surface == SelectionSurface.SENDERS
            ? senderQuery.canonicalParameters() : unreadQuery.canonicalParameters();
        query.forEach(url::queryParam);
        String cleanupAction = switch (action) {
            case ARCHIVE -> "archive";
            case TRASH -> "delete";
            case MARK_READ -> "mark_read";
            default -> throw bad("unsupported_cleanup_action");
        };
        url.queryParam("cleanupAction", cleanupAction);
        return url.build().encode().toUriString();
    }

    private static String single(
            MultiValueMap<String, String> parameters,
            String name,
            boolean required) {
        List<String> values = parameters.get(name);
        if (values == null || values.isEmpty()) {
            if (required) {
                throw bad("missing_" + name);
            }
            return null;
        }
        if (values.size() != 1
                || values.getFirst() == null
                || values.getFirst().isBlank()) {
            throw bad("invalid_" + name);
        }
        return values.getFirst();
    }

    private static <E extends Enum<E>> E enumeration(
            String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw bad("invalid_" + field);
        }
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
