package com.tidymail.mail;

import java.util.Objects;

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

    public static ProviderCapabilities unknown() {
        return new ProviderCapabilities(
            DenominatorReliability.UNAVAILABLE,
            DenominatorReliability.UNAVAILABLE);
    }

    public DenominatorReliability loadedDenominator(LoadedMessageScope scope) {
        return switch (scope) {
            case MAILBOX -> mailboxTotal;
            case ARBITRARY_FILTER -> arbitraryFilteredTotal;
        };
    }

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
