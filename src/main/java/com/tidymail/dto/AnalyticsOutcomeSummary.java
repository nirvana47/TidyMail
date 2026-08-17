package com.tidymail.dto;

/**
 * Aggregate outcome counts for analytics.
 *
 * <p>Product functionality supported: the <em>Analytics</em> outcome
 * reporting. Flavates the partition of a batch of action targets (requested /
 * accepted / succeeded / failed / verification-pending / not-dispatched),
 * enforcing that requested equals the terminal partition.</p>
 */
public record AnalyticsOutcomeSummary(
    long requested,
    long accepted,
    long succeeded,
    long failed,
    long verificationPending,
    long notDispatched
) {
    public AnalyticsOutcomeSummary {
        if (requested < 0 || accepted < 0 || succeeded < 0 || failed < 0
                || verificationPending < 0 || notDispatched < 0
                || accepted > requested
                || requested != Math.addExact(
                    Math.addExact(succeeded, failed),
                    Math.addExact(verificationPending, notDispatched))) {
            throw new IllegalArgumentException(
                "Requested must equal the terminal target partition");
        }
    }

    public long retainedConfidence() {
        return Math.addExact(Math.addExact(succeeded, failed), notDispatched);
    }
}
