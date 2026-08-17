package com.tidymail.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validated query parameters for the Senders page, controlling which senders are
 * listed (account, search, time window, sender type, risk, unread pressure, queue
 * state, protection, sort, page, and optional analytics drilldown key).
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's list, filtering,
 * sorting, and pagination.
 */
public record SenderQuery(
    String account,
    String search,
    Window window,
    Type type,
    Risk risk,
    UnreadPressure unread,
    QueueState queue,
    Protection protection,
    Sort sort,
    int page,
    String analyticsKey
) {
    public static final int PAGE_SIZE = 25;
    private static final Set<String> PARAMETERS = Set.of(
        "account", "search", "window", "type", "risk", "unread",
        "queue", "protected", "sort", "page", "analyticsKey");

    public SenderQuery(
            String account,
            String search,
            Window window,
            Type type,
            Risk risk,
            UnreadPressure unread,
            QueueState queue,
            Protection protection,
            Sort sort,
            int page) {
        this(account, search, window, type, risk, unread, queue, protection, sort, page, "");
    }

    public SenderQuery {
        account = QueryParsing.account(account);
        search = QueryParsing.text(search, "search", 200, false);
        window = required(window, "window");
        type = required(type, "type");
        risk = required(risk, "risk");
        unread = required(unread, "unread");
        queue = required(queue, "queue");
        protection = required(protection, "protected");
        sort = required(sort, "sort");
        analyticsKey = QueryParsing.text(
            analyticsKey == null ? "" : analyticsKey, "analyticsKey", 1024, false);
        if (page < 0) throw QueryParsing.bad("page");
    }

    /** Parses and validates raw HTTP parameters into a {@link SenderQuery}. */
    public static SenderQuery parse(Map<String, ? extends List<String>> parameters) {
        Map<String, String> values = QueryParsing.singleValues(parameters, PARAMETERS);
        return new SenderQuery(
            values.getOrDefault("account", "current"),
            values.getOrDefault("search", ""),
            QueryParsing.enumeration(values.get("window"), Window.D30, Window.class, "window"),
            QueryParsing.enumeration(values.get("type"), Type.ALL, Type.class, "type"),
            QueryParsing.enumeration(values.get("risk"), Risk.ALL, Risk.class, "risk"),
            QueryParsing.enumeration(
                values.get("unread"), UnreadPressure.ALL, UnreadPressure.class, "unread"),
            QueryParsing.enumeration(values.get("queue"), QueueState.OPEN, QueueState.class, "queue"),
            QueryParsing.enumeration(
                values.get("protected"), Protection.EXCLUDE, Protection.class, "protected"),
            QueryParsing.enumeration(
                values.get("sort"), Sort.VOLUME_DESC, Sort.class, "sort"),
            QueryParsing.page(values.get("page")),
            values.getOrDefault("analyticsKey", ""));
    }

    /** Returns the canonical parameter set for this query. */
    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("account", account);
        values.put("search", search);
        values.put("window", window.value);
        values.put("type", type.value);
        values.put("risk", risk.value);
        values.put("unread", unread.value);
        values.put("queue", queue.value);
        values.put("protected", protection.value);
        values.put("sort", sort.value);
        values.put("page", Integer.toString(page));
        if (!analyticsKey.isBlank()) values.put("analyticsKey", analyticsKey);
        return Collections.unmodifiableMap(values);
    }

    /** Returns a copy of this query with the given page number. */
    public SenderQuery withPage(int newPage) {
        return new SenderQuery(
            account, search, window, type, risk, unread, queue, protection, sort, newPage,
            analyticsKey);
    }

    /** Returns a copy of this query reset to the first page. */
    public SenderQuery resetPage() {
        return withPage(0);
    }

    /** Returns the row offset for the current page. */
    public long offset() {
        return Math.multiplyExact((long) page, PAGE_SIZE);
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw QueryParsing.bad(field);
        return value;
    }

    /** Time window over which senders are aggregated. */
    public enum Window implements QueryValue {
        D7("7d"), D30("30d"), D90("90d"), ALL("all");
        private final String value;
        Window(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Inferred sender type filter. */
    public enum Type implements QueryValue {
        ALL("all"), PERSONAL("personal"), LIST("list"), AUTOMATED("automated"),
        TRANSACTIONAL("transactional"), UNKNOWN("unknown");
        private final String value;
        Type(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Sender risk-level filter. */
    public enum Risk implements QueryValue {
        ALL("all"), LOW("low"), MEDIUM("medium"), HIGH("high");
        private final String value;
        Risk(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Unread-pressure filter for a sender's messages. */
    public enum UnreadPressure implements QueryValue {
        ALL("all"), NONE("none"), SOME("some"), MAJORITY("majority"), ALL_UNREAD("all_unread");
        private final String value;
        UnreadPressure(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Whether senders are queued for action or still open. */
    public enum QueueState implements QueryValue {
        ALL("all"), OPEN("open"), QUEUED("queued");
        private final String value;
        QueueState(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** How protected senders are treated in the listing. */
    public enum Protection implements QueryValue {
        EXCLUDE("exclude"), ONLY("only"), INCLUDE("include");
        private final String value;
        Protection(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** Sort order for the sender listing. */
    public enum Sort implements QueryValue {
        VOLUME_DESC("volume_desc"), UNREAD_DESC("unread_desc"), OLDEST_FIRST("oldest_first"),
        RISK_DESC("risk_desc"), RECOMMENDATION("recommendation"), RECENT_DESC("recent_desc");
        private final String value;
        Sort(String value) { this.value = value; }
        public String value() { return value; }
    }
}

/**
 * Common contract for query enums that expose a canonical string {@code value}.
 */
interface QueryValue {
    String value();
}

/**
 * Shared helper for parsing and validating query parameters across the Senders,
 * Unread, and Unsubscribe surfaces.
 */
final class QueryParsing {
    private QueryParsing() {
    }

    /** Validates that only recognized single-valued parameters are present. */
    static Map<String, String> singleValues(
            Map<String, ? extends List<String>> input, Set<String> recognized) {
        if (input == null || input.isEmpty()) return Map.of();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<String>> entry : input.entrySet()) {
            if (!recognized.contains(entry.getKey())) throw bad("query");
            List<String> values = entry.getValue();
            if (values == null || values.size() != 1 || values.get(0) == null) {
                throw bad(entry.getKey());
            }
            result.put(entry.getKey(), values.get(0));
        }
        return result;
    }

    /** Normalizes and validates an account selector ("current", "all", or an id). */
    static String account(String value) {
        String normalized = text(value == null ? "current" : value, "account", 32, false)
            .toLowerCase(Locale.ROOT);
        if (normalized.equals("current") || normalized.equals("all")) return normalized;
        try {
            long accountId = Long.parseLong(normalized);
            if (accountId > 0) return Long.toString(accountId);
        } catch (NumberFormatException ignored) {
            // Converted to one bounded error below.
        }
        throw bad("account");
    }

    /** Trims, length-checks, and optionally lowercases a free-text parameter. */
    static String text(String value, String field, int max, boolean lowerCase) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max || malformed(normalized)) throw bad(field);
        return lowerCase ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    /** Validates a sender-address parameter, rejecting malformed addresses. */
    static String senderAddress(String value) {
        String normalized = text(value, "sender", 320, true);
        if (normalized.isEmpty()) return normalized;
        int separator = normalized.indexOf('@');
        if (separator <= 0
                || separator != normalized.lastIndexOf('@')
                || separator == normalized.length() - 1
                || normalized.chars().anyMatch(character ->
                    Character.isWhitespace(character) || character == '/' || character == '\\')) {
            throw bad("sender");
        }
        return normalized;
    }

    /** Parses a non-negative page number parameter. */
    static int page(String value) {
        if (value == null) return 0;
        try {
            int page = Integer.parseInt(value);
            if (page >= 0) return page;
        } catch (NumberFormatException ignored) {
            // Converted to one bounded error below.
        }
        throw bad("page");
    }

    /** Parses an enum parameter, falling back to a default when absent. */
    static <E extends Enum<E> & QueryValue> E enumeration(
            String value, E defaultValue, Class<E> type, String field) {
        if (value == null) return defaultValue;
        if (malformed(value) || value.length() > 40) throw bad(field);
        for (E candidate : type.getEnumConstants()) {
            if (candidate.value().equals(value)) return candidate;
        }
        throw bad(field);
    }

    /** Builds a BAD_REQUEST exception for an invalid query parameter. */
    static ResponseStatusException bad(String field) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Invalid " + field + " query parameter");
    }

    /** Returns true if the value contains control, replacement, or malformed surrogate characters. */
    private static boolean malformed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\uFFFD'
                    || Character.isISOControl(character)
                    || Character.isHighSurrogate(character)
                        && (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    || Character.isLowSurrogate(character)) {
                return true;
            }
        }
        return false;
    }
}
