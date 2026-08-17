package com.tidymail.mail;

import java.util.Objects;

/**
 * Describes a provider's data and reliability capabilities.
 * <p>
 * Product functionality supported: metadata-only sync, deciding whether a
 * total count is a reliable denominator for progress reporting.
 */
public class ProviderCapabilities {

    private final DenominatorReliability mailboxTotal;
    private final DenominatorReliability arbitraryFilteredTotal;

    public ProviderCapabilities(
            DenominatorReliability mailboxTotal,
            DenominatorReliability arbitraryFilteredTotal) {
        if (mailboxTotal == null || arbitraryFilteredTotal == null) {
            throw new IllegalArgumentException("Provider capabilities are required");
        }
        this.mailboxTotal = mailboxTotal;
        this.arbitraryFilteredTotal = arbitraryFilteredTotal;
    }

    public DenominatorReliability mailboxTotal() {
        return mailboxTotal;
    }

    public DenominatorReliability arbitraryFilteredTotal() {
        return arbitraryFilteredTotal;
    }

    /**
     * Returns capabilities where no totals are available.
     */
    public static ProviderCapabilities unknown() {
        return new ProviderCapabilities(
            DenominatorReliability.UNAVAILABLE,
            DenominatorReliability.UNAVAILABLE);
    }

    /**
     * Returns how reliable the total is for the given load scope.
     */
    public DenominatorReliability loadedDenominator(LoadedMessageScope scope) {
        return switch (scope) {
            case MAILBOX -> mailboxTotal;
            case ARBITRARY_FILTER -> arbitraryFilteredTotal;
        };
    }

    /**
     * Builds progress for a load, using the provider total only when reliable.
     */
    public LoadedMessageProgress loadedProgress(
            LoadedMessageScope scope, long loadedCount, Long providerTotal) {
        boolean reliable = loadedDenominator(scope) == DenominatorReliability.RELIABLE;
        Long denominator = reliable && providerTotal != null && providerTotal >= loadedCount
            ? providerTotal : null;
        return new LoadedMessageProgress(loadedCount, denominator);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        ProviderCapabilities that = (ProviderCapabilities) other;
        return mailboxTotal == that.mailboxTotal
            && arbitraryFilteredTotal == that.arbitraryFilteredTotal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mailboxTotal, arbitraryFilteredTotal);
    }

    @Override
    public String toString() {
        return "ProviderCapabilities[mailboxTotal=" + mailboxTotal
            + ", arbitraryFilteredTotal=" + arbitraryFilteredTotal + "]";
    }

    public enum DenominatorReliability {
        RELIABLE,
        ESTIMATE_ONLY,
        UNAVAILABLE
    }

    public enum LoadedMessageScope {
        MAILBOX,
        ARBITRARY_FILTER
    }
}
