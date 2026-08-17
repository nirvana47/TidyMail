package com.tidymail.dto;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AnalyticsSourceQuery(
    AnalyticsQuery analytics,
    SourceSeries series,
    Instant bucketStart,
    Instant bucketEnd,
    int page
) {
    public static final int PAGE_SIZE = 25;
    static final Set<String> SOURCE_PARAMETERS = Set.of(
        "from", "to", "account", "action", "group", "compare",
        "series", "bucketStart", "bucketEnd", "page");

    public AnalyticsSourceQuery {
        Objects.requireNonNull(analytics, "analytics");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(bucketEnd, "bucketEnd");
        if (page < 0) throw QueryParsing.bad("page");
        boolean exactBucket = analytics.range().startInclusive().equals(bucketStart)
                && analytics.range().endExclusive().equals(bucketEnd)
            || analytics.buckets().stream().anyMatch(bucket ->
            bucket.startInclusive().equals(bucketStart)
                && bucket.endExclusive().equals(bucketEnd));
        if (!exactBucket) throw QueryParsing.bad("bucket");
    }

    public static AnalyticsSourceQuery parse(
            Map<String, ? extends List<String>> parameters,
            AnalyticsQuery analytics) {
        Map<String, String> values =
            QueryParsing.singleValues(parameters, SOURCE_PARAMETERS);
        if (!values.containsKey("series")
                || !values.containsKey("bucketStart")
                || !values.containsKey("bucketEnd")) {
            throw QueryParsing.bad("source");
        }
        return new AnalyticsSourceQuery(
            analytics,
            QueryParsing.enumeration(
                values.get("series"), null, SourceSeries.class, "series"),
            instant(values.get("bucketStart"), "bucketStart"),
            instant(values.get("bucketEnd"), "bucketEnd"),
            QueryParsing.page(values.get("page")));
    }

    public long offset() {
        return Math.multiplyExact((long) page, PAGE_SIZE);
    }

    public Map<String, String> canonicalParameters() {
        LinkedHashMap<String, String> values =
            new LinkedHashMap<>(analytics.canonicalParameters());
        values.put("series", series.value());
        values.put("bucketStart", bucketStart.toString());
        values.put("bucketEnd", bucketEnd.toString());
        values.put("page", Integer.toString(page));
        return Collections.unmodifiableMap(values);
    }

    public AnalyticsSourceQuery withPage(int newPage) {
        return new AnalyticsSourceQuery(
            analytics, series, bucketStart, bucketEnd, newPage);
    }

    public String seriesValue() {
        return series.value();
    }

    public boolean receivedSeries() {
        return series == SourceSeries.RECEIVED;
    }

    public boolean cleanedSeries() {
        return series == SourceSeries.CLEANED;
    }

    public boolean unsubscribeLinkageSeries() {
        return series == SourceSeries.TARGET_UNSUB_CONFIRMED
            || series == SourceSeries.UNSUB_ACCEPTED_OR_PENDING
            || series == SourceSeries.UNSUB_CONFIRMED
            || series == SourceSeries.UNSUB_ELIGIBLE;
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.length() > 40) throw QueryParsing.bad(field);
        try {
            Instant instant = Instant.parse(value);
            if (!instant.toString().equals(value)) throw QueryParsing.bad(field);
            return instant;
        } catch (DateTimeParseException exception) {
            throw QueryParsing.bad(field);
        }
    }

    public enum SourceSeries implements QueryValue {
        RECEIVED("received"),
        CLEANED("cleaned"),
        TARGET_ARCHIVED("target_archived"),
        TARGET_TRASHED("target_trashed"),
        TARGET_MARKED_READ("target_marked_read"),
        TARGET_UNSUB_CONFIRMED("target_unsubscribe_confirmed"),
        UNSUB_ACCEPTED_OR_PENDING("unsubscribe_accepted_or_pending"),
        INVERSE_INBOX_REMOVAL("inverse_inbox_removal"),
        OUTCOME_REQUESTED("outcome_requested"),
        OUTCOME_ACCEPTED("outcome_accepted"),
        OUTCOME_SUCCEEDED("outcome_succeeded"),
        OUTCOME_FAILED("outcome_failed"),
        OUTCOME_PENDING("outcome_pending"),
        OUTCOME_NOT_DISPATCHED("outcome_not_dispatched"),
        OUTCOME_RETAINED("outcome_retained"),
        REVIEW_STAGED("review_staged"),
        REVIEW_EXECUTED("review_executed"),
        DECISION("decision"),
        COHORT_DECIDED("cohort_decided"),
        CLEANED_TRIAGED("cleaned_triaged"),
        DELAY_SAMPLE("delay_sample"),
        DELAY_UNDER_15M("delay_under_15m"),
        DELAY_15M_TO_1H("delay_15m_to_1h"),
        DELAY_1H_TO_24H("delay_1h_to_24h"),
        DELAY_24H_TO_7D("delay_24h_to_7d"),
        DELAY_7D_OR_MORE("delay_7d_or_more"),
        UNSUB_CONFIRMED("unsubscribe_confirmed"),
        UNSUB_ELIGIBLE("unsubscribe_eligible");

        private final String value;
        SourceSeries(String value) { this.value = value; }
        public String value() { return value; }
    }
}
