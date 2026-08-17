package com.tidymail.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

/**
 * The parsed, validated query for the Review page's action-history view.
 *
 * <p>Product functionality supported: the <em>Review &gt; History</em> tab's
 * filters (date range, action, status, target). Parses and validates the
 * request parameters, defaults a missing end/start of the range, and produces
 * canonical URL parameters.</p>
 */
public record HistoryQuery(
    String account,
    LocalDate from,
    LocalDate to,
    String action,
    String status,
    String target,
    int page
) {
    public static final int PAGE_SIZE = 25;
    public static final int MAX_RANGE_DAYS = 366;
    public static final int MAX_TARGET_LENGTH = 160;
    private static final Set<String> KEYS =
        Set.of("view", "account", "from", "to", "action", "status", "target", "page");
    private static final Set<String> ACTIONS = Set.of(
        "all", "archive", "trash", "mark_read", "unsubscribe", "keep",
        "protect", "review", "remove_protection");
    private static final Set<String> STATUSES = Set.of(
        "all", "in_progress", "applied", "partial", "verification_pending",
        "failed", "cancelled", "undone");

    public HistoryQuery {
        if (from != null && to == null) {
            to = from.plusDays(MAX_RANGE_DAYS);
        } else if (from == null && to != null) {
            from = to.minusDays(MAX_RANGE_DAYS);
        }
        if (account == null || account.isBlank() || !ACTIONS.contains(action)
                || !STATUSES.contains(status) || target == null
                || target.length() > MAX_TARGET_LENGTH || page < 0
                || from != null && to != null && (from.isAfter(to)
                    || java.time.temporal.ChronoUnit.DAYS.between(from, to)
                        > MAX_RANGE_DAYS)) {
            throw invalid();
        }
    }

    /**
     * Parses and validates the history query from request parameters.
     */
    public static HistoryQuery parse(MultiValueMap<String, String> parameters) {
        for (String key : parameters.keySet()) {
            if (!KEYS.contains(key)
                    || parameters.getOrDefault(key, List.of()).size() != 1) {
                throw invalid();
            }
        }
        if (!"history".equalsIgnoreCase(value(parameters, "view", "history"))) {
            throw invalid();
        }
        String rawPage = value(parameters, "page", "0");
        if (!rawPage.matches("\\d{1,9}")) throw invalid();
        try {
            return new HistoryQuery(
                value(parameters, "account", "current"),
                date(value(parameters, "from", "")),
                date(value(parameters, "to", "")),
                value(parameters, "action", "all").toLowerCase(Locale.ROOT),
                value(parameters, "status", "all").toLowerCase(Locale.ROOT),
                value(parameters, "target", "").strip(),
                Integer.parseInt(rawPage));
        } catch (DateTimeParseException | NumberFormatException invalid) {
            throw invalid();
        }
    }

    /**
     * The canonical URL parameters for this history query.
     */
    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("view", "history");
        values.put("account", account);
        if (from != null) values.put("from", from.toString());
        if (to != null) values.put("to", to.toString());
        if (!"all".equals(action)) values.put("action", action);
        if (!"all".equals(status)) values.put("status", status);
        if (!target.isBlank()) values.put("target", target);
        if (page > 0) values.put("page", String.valueOf(page));
        return Map.copyOf(values);
    }

    /**
     * The canonical parameters without the page entry (for &quot;clear
     * paging&quot; links).
     */
    public Map<String, String> withoutPage() {
        LinkedHashMap<String, String> values =
            new LinkedHashMap<>(canonicalParameters());
        values.remove("page");
        return values;
    }

    private static LocalDate date(String value) {
        return value.isBlank() ? null : LocalDate.parse(value);
    }

    private static String value(
            MultiValueMap<String, String> parameters, String key, String fallback) {
        List<String> values = parameters.get(key);
        return values == null ? fallback : values.getFirst();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "invalid_history_query");
    }
}
