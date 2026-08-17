package com.tidymail.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Describes whether analytics data is available for a requested range, and
 * why not.
 *
 * <p>Product functionality supported: the <em>Analytics</em> dashboard's
 * honesty guarantees. Analytics are only shown when the underlying metadata
 * coverage is trustworthy; this type encodes the availability state, the
 * covered time range, and a user-facing explanation, and can combine two
 * availabilities (taking the weaker state and intersecting the range).</p>
 */
public record AnalyticsAvailability(
    State state,
    Reason reason,
    Instant coverageStart,
    Instant coverageEnd,
    long excludedCount
) {
    public AnalyticsAvailability {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        if (excludedCount < 0) {
            throw new IllegalArgumentException("Excluded count cannot be negative");
        }
    }

    /**
     * Builds a fully-available (or partial, when some rows are excluded)
     * availability for the given coverage range.
     */
    public static AnalyticsAvailability available(
            Instant coverageStart, Instant coverageEnd, long excludedCount) {
        Reason reason = excludedCount == 0 ? Reason.COMPLETE : Reason.NULL_RECEIVED_AT;
        State state = excludedCount == 0 ? State.AVAILABLE : State.PARTIAL;
        return new AnalyticsAvailability(
            state, reason, coverageStart, coverageEnd, excludedCount);
    }

    /**
     * Builds a partial availability with the given reason.
     */
    public static AnalyticsAvailability partial(
            Reason reason, Instant coverageStart, Instant coverageEnd, long excludedCount) {
        return new AnalyticsAvailability(
            State.PARTIAL, reason, coverageStart, coverageEnd, excludedCount);
    }

    /**
     * Builds an unavailable availability with the given reason.
     */
    public static AnalyticsAvailability unavailable(
            Reason reason, Instant coverageStart, Instant coverageEnd, long excludedCount) {
        return new AnalyticsAvailability(
            State.UNAVAILABLE, reason, coverageStart, coverageEnd, excludedCount);
    }

    /**
     * Combines this availability with another, keeping the weaker state and
     * intersecting the covered time range (and summing exclusions).
     */
    public AnalyticsAvailability combine(AnalyticsAvailability other) {
        Objects.requireNonNull(other, "other");
        AnalyticsAvailability weaker =
            state.ordinal() >= other.state.ordinal() ? this : other;
        Instant start = later(coverageStart, other.coverageStart);
        Instant end = earlier(coverageEnd, other.coverageEnd);
        return new AnalyticsAvailability(
            weaker.state, weaker.reason, start, end,
            Math.addExact(excludedCount, other.excludedCount));
    }

    public boolean available() {
        return state == State.AVAILABLE;
    }

    /**
     * Returns a human-readable explanation of the availability reason for
     * display in the UI.
     */
    public String safeCopy() {
        return switch (reason) {
            case COMPLETE -> "Indexed metadata covers the selected range.";
            case SYNC_INCOMPLETE -> "Some values are partial; missing history is not estimated.";
            case NO_COMPLETED_SYNC ->
                "Unavailable - no completed sync establishes coverage for this range.";
            case AFTER_SYNC_COVERAGE ->
                "Unavailable - this range is newer than completed sync coverage.";
            case BEFORE_INDEXED_HISTORY ->
                "Unavailable - this range predates indexed history.";
            case NO_TIMESTAMPED_HISTORY ->
                "Unavailable - timestamped metadata is not recorded for this account.";
            case NULL_RECEIVED_AT ->
                "Some indexed rows have no received time and are excluded.";
            case LEGACY_LEDGER_GAP ->
                "Partial - per-target action history is unavailable for legacy actions.";
            case NO_LEDGER_HISTORY ->
                "Unavailable - no per-target action history covers this range.";
            case UNSUBSCRIBE_LINKAGE_GAP ->
                "Partial - some unsubscribe outcomes are not linked to target facts.";
            case CLEANED_METADATA_GAP ->
                "Partial - some cleaned facts lack timestamped source metadata.";
            case NO_ELIGIBLE_ROWS ->
                "Unavailable - no eligible rows establish a denominator.";
            case CORRUPT_DELAY_PAIR ->
                "Some decision delays are missing or negative and are excluded.";
        };
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static Instant earlier(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    public enum State {
        AVAILABLE, PARTIAL, UNAVAILABLE
    }

    public enum Reason {
        COMPLETE,
        SYNC_INCOMPLETE,
        NO_COMPLETED_SYNC,
        AFTER_SYNC_COVERAGE,
        BEFORE_INDEXED_HISTORY,
        NO_TIMESTAMPED_HISTORY,
        NULL_RECEIVED_AT,
        LEGACY_LEDGER_GAP,
        NO_LEDGER_HISTORY,
        UNSUBSCRIBE_LINKAGE_GAP,
        CLEANED_METADATA_GAP,
        NO_ELIGIBLE_ROWS,
        CORRUPT_DELAY_PAIR
    }
}
