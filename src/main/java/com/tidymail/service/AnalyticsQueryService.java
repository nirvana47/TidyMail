package com.tidymail.service;

import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsQuery.AnalyticsAction;
import com.tidymail.dto.AnalyticsQuery.BucketGrouping;
import com.tidymail.dto.AnalyticsQuery.Comparison;
import com.tidymail.dto.AnalyticsQuery.TimePeriod;
import com.tidymail.dto.AnalyticsSourceQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parses and validates analytics and analytics-source query parameters, and
 * resolves the selected account scope for the authenticated user.
 *
 * <p>Product functionality supported: the <em>Analytics</em> flow's query
 * parsing, date-range handling, and account resolution.
 */
@Service
public class AnalyticsQueryService {
    private static final Set<String> ANALYTICS_KEYS =
        Set.of("from", "to", "account", "action", "group", "compare", "period");
    private static final Set<String> SOURCE_KEYS = Set.of(
        "from", "to", "account", "action", "group", "compare",
            "period", "series", "bucketStart", "bucketEnd", "page");

    private final OwnedAccountScopeService accountScopeService;
    private final Clock clock;

    @Autowired
    public AnalyticsQueryService(OwnedAccountScopeService accountScopeService) {
        this(accountScopeService, Clock.systemUTC());
    }

    public AnalyticsQueryService(OwnedAccountScopeService accountScopeService, Clock clock) {
        this.accountScopeService = accountScopeService;
        this.clock = clock;
    }

    /** Parses analytics query parameters into an {@link AnalyticsQuery}. */
    public AnalyticsQuery parse(Map<String, ? extends List<String>> parameters) {
        return parseAnalytics(singleValues(parameters, ANALYTICS_KEYS));
    }

    /** Parses analytics source-query parameters into an {@link AnalyticsSourceQuery}. */
    public AnalyticsSourceQuery parseSource(
            Map<String, ? extends List<String>> parameters) {
        Map<String, String> values = singleValues(parameters, SOURCE_KEYS);
        LinkedHashMap<String, List<String>> analyticsValues = new LinkedHashMap<>();
        ANALYTICS_KEYS.forEach(key -> {
            if (values.containsKey(key)) analyticsValues.put(key, List.of(values.get(key)));
        });
        AnalyticsQuery analytics = parse(analyticsValues);
        LinkedHashMap<String, List<String>> all = new LinkedHashMap<>();
        values.forEach((key, value) -> all.put(key, List.of(value)));
        return AnalyticsSourceQuery.parse(all, analytics);
    }

    /** Resolves the query's account scope to concrete account IDs. */
    public ResolvedAnalyticsQuery resolve(
            AnalyticsQuery query, OAuth2AuthenticationToken authentication) {
        List<Long> accountIds = accountScopeService.resolve(authentication, query.account());
        if (accountIds.size() != 1) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Analytics requires one active account");
        }
        String account = Long.toString(accountIds.getFirst());
        return new ResolvedAnalyticsQuery(query.withAccount(account), accountIds);
    }

    /** Resolves a source query's account scope to concrete account IDs. */
    public ResolvedAnalyticsSourceQuery resolve(
            AnalyticsSourceQuery source, OAuth2AuthenticationToken authentication) {
        ResolvedAnalyticsQuery resolved = resolve(source.analytics(), authentication);
        return new ResolvedAnalyticsSourceQuery(
            new AnalyticsSourceQuery(
                resolved.query(), source.series(), source.bucketStart(),
                source.bucketEnd(), source.page()),
            resolved.accountIds());
    }

    /** Parses a validated single-value parameter map into an {@link AnalyticsQuery}. */
    private AnalyticsQuery parseAnalytics(Map<String, String> values) {
        LocalDate today = LocalDate.now(clock);
        TimePeriod period = enumeration(
            values.get("period"),
            values.containsKey("from") || values.containsKey("to")
                ? TimePeriod.CUSTOM : TimePeriod.LAST_MONTH,
            TimePeriod.class, "period");
        LocalDate to = date(values.get("to"), "to", today);
        LocalDate from = date(values.get("from"), "from", periodStart(period, to));
        boolean groupingOverridden = values.containsKey("group");
        BucketGrouping grouping = enumeration(
            values.get("group"), period.defaultGrouping(), BucketGrouping.class, "group");
        return new AnalyticsQuery(
            from,
            to,
            account(values.getOrDefault("account", "current")),
            enumeration(values.get("action"), AnalyticsAction.ALL, AnalyticsAction.class, "action"),
            grouping,
            enumeration(values.get("compare"), Comparison.PRIOR, Comparison.class, "compare"),
            period,
            groupingOverridden);
    }

    /** Returns the start date for a time period ending at the given date. */
    private static LocalDate periodStart(TimePeriod period, LocalDate to) {
        return switch (period) {
            case LAST_WEEK -> to.minusDays(6);
            case LAST_MONTH -> to.minusDays(29);
            case LAST_THREE_MONTHS -> to.minusMonths(3).plusDays(1);
            case LAST_YEAR -> to.minusYears(1).plusDays(1);
            case ALL -> LocalDate.of(2004, 4, 1);
            case CUSTOM -> to.minusDays(29);
        };
    }

    /** Validates that only recognized single-valued parameters are present. */
    private static Map<String, String> singleValues(
            Map<String, ? extends List<String>> input, Set<String> recognized) {
        if (input == null || input.isEmpty()) return Map.of();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<String>> entry : input.entrySet()) {
            if (!recognized.contains(entry.getKey())) throw bad("query");
            List<String> values = entry.getValue();
            if (values == null || values.size() != 1 || values.getFirst() == null) {
                throw bad(entry.getKey());
            }
            result.put(entry.getKey(), values.getFirst());
        }
        return result;
    }

    /** Parses a canonical date parameter, falling back to the default. */
    private static LocalDate date(String value, String field, LocalDate defaultValue) {
        if (value == null) return defaultValue;
        if (value.length() != 10) throw bad(field);
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw bad(field);
            return parsed;
        } catch (DateTimeParseException exception) {
            throw bad(field);
        }
    }

    /** Normalizes and validates an account selector. */
    private static String account(String value) {
        String normalized = value == null ? "current" : value.trim().toLowerCase(Locale.ROOT);
        if ("current".equals(normalized) || "all".equals(normalized)) return normalized;
        try {
            long id = Long.parseLong(normalized);
            if (id > 0) return Long.toString(id);
        } catch (NumberFormatException ignored) {
            // Converted to one bounded response below.
        }
        throw bad("account");
    }

    /** Parses an enum parameter by its value method, defaulting when absent. */
    private static <E extends Enum<E>> E enumeration(
            String value, E defaultValue, Class<E> type, String field) {
        if (value == null) return defaultValue;
        for (E candidate : type.getEnumConstants()) {
            try {
                String candidateValue =
                    (String) type.getMethod("value").invoke(candidate);
                if (candidateValue.equals(value)) return candidate;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Analytics enum has no value contract", exception);
            }
        }
        throw bad(field);
    }

    /** Returns a BAD_REQUEST exception for an invalid analytics parameter. */
    private static ResponseStatusException bad(String field) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Invalid analytics " + field + " parameter");
    }

    /** A parsed analytics query bound to concrete account IDs. */
    public record ResolvedAnalyticsQuery(
        AnalyticsQuery query,
        List<Long> accountIds
    ) {
        public ResolvedAnalyticsQuery {
            accountIds = List.copyOf(accountIds);
        }
    }

    /** A parsed analytics source query bound to concrete account IDs. */
    public record ResolvedAnalyticsSourceQuery(
        AnalyticsSourceQuery query,
        List<Long> accountIds
    ) {
        public ResolvedAnalyticsSourceQuery {
            accountIds = List.copyOf(accountIds);
        }
    }
}
