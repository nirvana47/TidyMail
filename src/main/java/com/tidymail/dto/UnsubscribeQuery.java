package com.tidymail.dto;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record UnsubscribeQuery(
    String account,
    Category category,
    LocalDate from,
    LocalDate to,
    QuickRange range,
    String search,
    DefaultAction defaultAction,
    Sort sort,
    int page,
    Protection protection
) {
    public static final int PAGE_SIZE = 25;
    public static final int MAX_RANGE_DAYS = 36_525;
    private static final Set<String> PARAMETERS = Set.of(
        "account", "category", "from", "to", "range", "q", "defaultAction", "sort", "page",
        "method", "volume", "confidence", "review", "age", "protected", "state");

    public UnsubscribeQuery(
            String account,
            Method method,
            Volume volume,
            Confidence confidence,
            Review review,
            Age age,
            Protection protection,
            OutcomeState state,
            String search,
            Sort sort,
            int page) {
        this(
            account, Category.ALL,
            LocalDate.now(ZoneOffset.UTC).minusDays(92),
            LocalDate.now(ZoneOffset.UTC),
            QuickRange.THREE_MONTHS,
            search,
            DefaultAction.ARCHIVE,
            sort,
            page,
            protection);
    }

    public UnsubscribeQuery {
        account = QueryParsing.account(account);
        if (category == null || from == null || to == null || range == null
                || defaultAction == null || sort == null || protection == null
                || page < 0 || page > 1000) {
            throw QueryParsing.bad("unsubscribe");
        }
        search = QueryParsing.text(search, "q", 200, false);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > MAX_RANGE_DAYS) throw QueryParsing.bad("date range");
    }

    public static UnsubscribeQuery parse(Map<String, ? extends List<String>> parameters) {
        Map<String, String> values = QueryParsing.singleValues(parameters, PARAMETERS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QuickRange range = QueryParsing.enumeration(
            values.get("range"), QuickRange.THREE_MONTHS, QuickRange.class, "range");
        LocalDate defaultFrom = range.from(today);
        LocalDate from = date(values.get("from"), "from", defaultFrom);
        LocalDate to = date(values.get("to"), "to", today);
        if (values.containsKey("from") || values.containsKey("to")) range = QuickRange.CUSTOM;
        validateLegacyFilters(values);
        return new UnsubscribeQuery(
            values.getOrDefault("account", "current"),
            QueryParsing.enumeration(
                values.get("category"), Category.UNHANDLED, Category.class, "category"),
            from,
            to,
            range,
            values.getOrDefault("q", ""),
            QueryParsing.enumeration(
                values.get("defaultAction"), DefaultAction.ARCHIVE,
                DefaultAction.class, "defaultAction"),
            QueryParsing.enumeration(
                values.get("sort"), Sort.VOLUME_DESC, Sort.class, "sort"),
            QueryParsing.page(values.get("page")),
            QueryParsing.enumeration(
                values.get("protected"), Protection.EXCLUDE,
                Protection.class, "protected"));
    }

    private static void validateLegacyFilters(Map<String, String> values) {
        QueryParsing.enumeration(values.get("method"), Method.ALL, Method.class, "method");
        QueryParsing.enumeration(values.get("volume"), Volume.ALL, Volume.class, "volume");
        QueryParsing.enumeration(
            values.get("confidence"), Confidence.ALL, Confidence.class, "confidence");
        QueryParsing.enumeration(values.get("review"), Review.ALL, Review.class, "review");
        QueryParsing.enumeration(values.get("age"), Age.ALL, Age.class, "age");
        QueryParsing.enumeration(
            values.get("state"), OutcomeState.ALL, OutcomeState.class, "state");
    }

    public static UnsubscribeQuery parseCanonical(Map<String, String> parameters) {
        LinkedHashMap<String, List<String>> values = new LinkedHashMap<>();
        parameters.forEach((key, value) -> values.put(key, List.of(value)));
        return parse(values);
    }

    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("account", account);
        values.put("category", category.value());
        values.put("from", from.toString());
        values.put("to", to.toString());
        values.put("range", range.value());
        values.put("q", search);
        values.put("defaultAction", defaultAction.value());
        values.put("sort", sort.value());
        values.put("page", Integer.toString(page));
        return Collections.unmodifiableMap(values);
    }

    public UnsubscribeQuery withPage(int value) {
        return new UnsubscribeQuery(
            account, category, from, to, range, search, defaultAction, sort, value, protection);
    }

    public UnsubscribeQuery resetPage() {
        return withPage(0);
    }

    public long offset() {
        return Math.multiplyExact((long) page, PAGE_SIZE);
    }

    public java.time.Instant startInclusive() {
        return from.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public java.time.Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate date(String value, String field, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            throw QueryParsing.bad(field);
        }
    }

    public enum Category implements QueryValue {
        UNHANDLED("unhandled"),
        UNSUBSCRIBED("unsubscribed"),
        ARCHIVED_DELETED("archived_deleted"),
        ALL("all");

        private final String value;
        Category(String value) { this.value = value; }
        public String value() { return value; }
    }

    public enum QuickRange implements QueryValue {
        WEEK("week", 6),
        MONTH("month", 29),
        THREE_MONTHS("3months", 92),
        YEAR("year", 364),
        ALL("all", MAX_RANGE_DAYS - 1),
        CUSTOM("custom", 92);

        private final String value;
        private final int daysBack;
        QuickRange(String value, int daysBack) {
            this.value = value;
            this.daysBack = daysBack;
        }
        public String value() { return value; }
        LocalDate from(LocalDate today) { return today.minusDays(daysBack); }
    }

    public enum DefaultAction implements QueryValue {
        ARCHIVE("archive", "Archive"),
        DELETE("delete", "Delete");

        private final String value;
        private final String label;
        DefaultAction(String value, String label) {
            this.value = value;
            this.label = label;
        }
        public String value() { return value; }
        public String label() { return label; }
    }

    public enum Sort implements QueryValue {
        VOLUME_DESC("volume_desc"),
        READ_DESC("read_desc"),
        SENDER("sender"),
        NEWEST("newest"),
        CONFIDENCE_DESC("confidence_desc"),
        METHOD("method"),
        OLDEST("oldest"),
        REVIEW("review"),
        STATE("state");

        private final String value;
        Sort(String value) { this.value = value; }
        public String value() { return value; }
    }

    public enum Method implements QueryValue {
        ALL, RFC8058, LEGACY_GET, MAILTO, MANUAL;
        public String value() { return name().toLowerCase(); }
    }

    public enum Volume implements QueryValue {
        ALL(0), V10(10), V20(20), V50(50), V100(100);
        private final int minimum;
        Volume(int minimum) { this.minimum = minimum; }
        public int minimum() { return minimum; }
        public String value() {
            return this == ALL ? "all" : name().substring(1);
        }
    }

    public enum Confidence implements QueryValue {
        ALL, HIGH, MEDIUM, REVIEW;
        public String value() { return name().toLowerCase(); }
    }
    public enum Review implements QueryValue {
        ALL, READY, NEEDS_REVIEW;
        public String value() { return name().toLowerCase(); }
    }
    public enum Age implements QueryValue {
        ALL, D7, D30, D90, OLDER_90D;
        public String value() {
            return switch (this) {
                case D7 -> "7d";
                case D30 -> "30d";
                case D90 -> "90d";
                default -> name().toLowerCase();
            };
        }
    }
    public enum Protection implements QueryValue {
        EXCLUDE, INCLUDE, ONLY;
        public String value() { return name().toLowerCase(); }
    }
    public enum OutcomeState implements QueryValue {
        ALL, NOT_ATTEMPTED, PENDING, ACCEPTED, CONFIRMED, FAILED,
        MANUAL_REQUIRED, VERIFICATION_PENDING;
        public String value() { return name().toLowerCase(); }
    }

    public Method method() { return Method.ALL; }
    public Volume volume() { return Volume.ALL; }
    public Confidence confidence() { return Confidence.ALL; }
    public Review review() { return Review.ALL; }
    public Age age() { return Age.ALL; }
    public OutcomeState state() { return OutcomeState.ALL; }
}
