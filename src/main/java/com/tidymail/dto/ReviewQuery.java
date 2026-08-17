package com.tidymail.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

public record ReviewQuery(
    String view,
    String account,
    String action,
    String state,
    String target,
    int page
) {
    public static final int PAGE_SIZE = 25;
    public static final int MAX_TARGET_LENGTH = 160;
    private static final Set<String> KEYS =
        Set.of("view", "account", "action", "state", "target", "page");
    private static final Set<String> ACTIONS = Set.of(
        "all", "archive", "trash", "mark_read", "unsubscribe",
        "keep", "protect", "needs_decision");
    private static final Set<String> STATES =
        Set.of("all", "eligible", "conflicted", "needs_decision");

    public ReviewQuery {
        if (!"queued".equals(view)
                || account == null || account.isBlank()
                || !ACTIONS.contains(action)
                || !STATES.contains(state)
                || target == null || target.length() > MAX_TARGET_LENGTH
                || page < 0) {
            throw invalid();
        }
    }

    public static ReviewQuery parse(MultiValueMap<String, String> parameters) {
        for (String key : parameters.keySet()) {
            if (!KEYS.contains(key) || parameters.getOrDefault(key, List.of()).size() != 1) {
                throw invalid();
            }
        }
        String view = value(parameters, "view", "queued").toLowerCase(Locale.ROOT);
        String account = value(parameters, "account", "current");
        String action = value(parameters, "action", "all").toLowerCase(Locale.ROOT);
        String state = value(parameters, "state", "all").toLowerCase(Locale.ROOT);
        String target = value(parameters, "target", "").strip();
        String rawPage = value(parameters, "page", "0");
        if (!rawPage.matches("\\d{1,9}")) {
            throw invalid();
        }
        try {
            return new ReviewQuery(
                view, account, action, state, target, Integer.parseInt(rawPage));
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("view", view);
        result.put("account", account);
        if (!"all".equals(action)) result.put("action", action);
        if (!"all".equals(state)) result.put("state", state);
        if (!target.isBlank()) result.put("target", target);
        if (page > 0) result.put("page", String.valueOf(page));
        return Map.copyOf(result);
    }

    public Map<String, String> withoutPage() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(canonicalParameters());
        result.remove("page");
        return result;
    }

    private static String value(
            MultiValueMap<String, String> parameters, String key, String fallback) {
        List<String> values = parameters.get(key);
        return values == null ? fallback : values.getFirst();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_review_query");
    }
}
