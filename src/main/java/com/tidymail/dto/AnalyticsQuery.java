package com.tidymail.dto;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed, validated analytics query: date range, account, action filter,
 * bucket grouping, and prior-period comparison.
 *
 * <p>Product functionality supported: the <em>Analytics</em> dashboard's
 * filters and time-series. Derives the selected range, the prior comparison
 * range, and the time buckets (day/week/month/year), and produces the
 * canonical URL parameters for the query.</p>
 */
public record AnalyticsQuery(
    LocalDate from,
    LocalDate to,
    String account,
    AnalyticsAction action,
    BucketGrouping grouping,
    Comparison comparison,
    TimePeriod period,
    boolean groupingOverridden
) {
    public static final int MAX_DATES = 366;
    public static final int MAX_RANGE_DATES = 36_525;
    public static final int MAX_BUCKETS = 1_000;

    public AnalyticsQuery(
            LocalDate from,
            LocalDate to,
            String account,
            AnalyticsAction action,
            BucketGrouping grouping,
            Comparison comparison) {
        this(from, to, account, action, grouping, comparison, TimePeriod.CUSTOM, true);
    }

    public AnalyticsQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        account = QueryParsing.account(account);
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(grouping, "grouping");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(period, "period");
        long dates = ChronoUnit.DAYS.between(from, to) + 1;
        if (dates < 1 || dates > MAX_RANGE_DATES
                || dates > MAX_DATES && period != TimePeriod.ALL) {
            throw QueryParsing.bad("date range");
        }
    }

    /**
     * The selected date range as instants (inclusive start, exclusive end).
     */
    public DateRange range() {
        return new DateRange(
            from.atStartOfDay(ZoneOffset.UTC).toInstant(),
            to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * The immediately preceding range of equal duration, for prior-period
     * comparison.
     */
    public DateRange priorRange() {
        DateRange current = range();
        Duration duration = current.duration();
        return new DateRange(current.startInclusive().minus(duration), current.startInclusive());
    }

    /**
     * Splits the selected range into buckets according to the grouping
     * (day/week/month/year), capped at {@link #MAX_BUCKETS}.
     */
    public List<DateRange> buckets() {
        DateRange selected = range();
        java.util.ArrayList<DateRange> buckets = new java.util.ArrayList<>();
        Instant cursor = selected.startInclusive();
        while (cursor.isBefore(selected.endExclusive())) {
            LocalDate cursorDate = cursor.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate nextDate = switch (grouping) {
                case DAY -> cursorDate.plusDays(1);
                case WEEK -> cursorDate.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
                case MONTH -> cursorDate.with(TemporalAdjusters.firstDayOfNextMonth());
                case YEAR -> cursorDate.with(TemporalAdjusters.firstDayOfNextYear());
            };
            Instant next = nextDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            if (next.isAfter(selected.endExclusive())) next = selected.endExclusive();
            buckets.add(new DateRange(cursor, next));
            if (buckets.size() > MAX_BUCKETS) {
                throw QueryParsing.bad("group");
            }
            cursor = next;
        }
        return List.copyOf(buckets);
    }

    /**
     * The canonical URL parameters for this query (from/to/account/action/
     * group/compare).
     */
    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("from", from.toString());
        values.put("to", to.toString());
        values.put("account", account);
        values.put("action", action.value());
        values.put("group", grouping.value());
        values.put("compare", comparison.value());
        return Collections.unmodifiableMap(values);
    }

    public String actionValue() {
        return action.value();
    }

    public String groupingValue() {
        return grouping.value();
    }

    public String comparisonValue() {
        return comparison.value();
    }

    public boolean actionFiltered() {
        return action != AnalyticsAction.ALL;
    }
    public AnalyticsQuery withAccount(String normalizedAccount) {
        return new AnalyticsQuery(
            from, to, normalizedAccount, action, grouping, comparison,
            period, groupingOverridden);
    }

    public String periodLabel() {
        return period.label();
    }

    public enum AnalyticsAction implements QueryValue {
        ALL("all"), ARCHIVE("archive"), TRASH("trash"), MARK_READ("mark_read"),
        UNSUBSCRIBE("unsubscribe"), KEEP("keep"), PROTECT("protect"), REVIEW("review");

        private final String value;
        AnalyticsAction(String value) { this.value = value; }
        public String value() { return value; }
    }

    public enum BucketGrouping implements QueryValue {
        DAY("day"), WEEK("week"), MONTH("month"), YEAR("year");

        private final String value;
        BucketGrouping(String value) { this.value = value; }
        public String value() { return value; }
    }

    public enum TimePeriod implements QueryValue {
        LAST_WEEK("week", "Last week", BucketGrouping.DAY),
        LAST_MONTH("month", "Last month", BucketGrouping.DAY),
        LAST_THREE_MONTHS("3months", "Last 3 months", BucketGrouping.WEEK),
        LAST_YEAR("year", "Last year", BucketGrouping.MONTH),
        ALL("all", "All", BucketGrouping.YEAR),
        CUSTOM("custom", "Custom", BucketGrouping.DAY);

        private final String value;
        private final String label;
        private final BucketGrouping defaultGrouping;

        TimePeriod(String value, String label, BucketGrouping defaultGrouping) {
            this.value = value;
            this.label = label;
            this.defaultGrouping = defaultGrouping;
        }

        public String value() { return value; }
        public String label() { return label; }
        public BucketGrouping defaultGrouping() { return defaultGrouping; }
    }

    public enum Comparison implements QueryValue {
        PRIOR("prior"), NONE("none");

        private final String value;
        Comparison(String value) { this.value = value; }
        public String value() { return value; }
    }

    public record DateRange(Instant startInclusive, Instant endExclusive) {
        public DateRange {
            Objects.requireNonNull(startInclusive, "startInclusive");
            Objects.requireNonNull(endExclusive, "endExclusive");
            if (!startInclusive.isBefore(endExclusive)) {
                throw new IllegalArgumentException("Date range must be non-empty");
            }
        }

        public Duration duration() {
            return Duration.between(startInclusive, endExclusive);
        }
    }
}
