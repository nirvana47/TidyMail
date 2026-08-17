package com.tidymail.mail.sync;

import com.tidymail.mail.ProviderCapabilities;
import java.util.Objects;

/**
 * Describes what change-feed and scanning behaviors a sync provider supports.
 *
 * <p>Product functionality supported: provider-agnostic sync capability
 * negotiation.
 */
public final class SyncCapabilities extends ProviderCapabilities {

    private final ChangeFeedKind changeFeedKind;
    private final boolean reliableProfileTotalSupported;
    private final boolean completeScanSupported;

    public SyncCapabilities(
            ChangeFeedKind changeFeedKind,
            boolean reliableProfileTotalSupported,
            boolean completeScanSupported) {
        super(
            reliableProfileTotalSupported
                ? DenominatorReliability.RELIABLE : DenominatorReliability.UNAVAILABLE,
            DenominatorReliability.UNAVAILABLE);
        if (changeFeedKind == null) {
            throw new IllegalArgumentException("Change feed kind is required");
        }
        this.changeFeedKind = changeFeedKind;
        this.reliableProfileTotalSupported = reliableProfileTotalSupported;
        this.completeScanSupported = completeScanSupported;
    }

    public ChangeFeedKind changeFeedKind() {
        return changeFeedKind;
    }

    public boolean reliableProfileTotalSupported() {
        return reliableProfileTotalSupported;
    }

    public boolean completeScanSupported() {
        return completeScanSupported;
    }

    public static SyncCapabilities none() {
        return new SyncCapabilities(ChangeFeedKind.NONE, false, false);
    }

    public enum ChangeFeedKind {
        HISTORY,
        DELTA,
        NONE
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SyncCapabilities that)) return false;
        return reliableProfileTotalSupported == that.reliableProfileTotalSupported
            && completeScanSupported == that.completeScanSupported
            && changeFeedKind == that.changeFeedKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            changeFeedKind, reliableProfileTotalSupported, completeScanSupported);
    }

    @Override
    public String toString() {
        return "SyncCapabilities[changeFeedKind=" + changeFeedKind
            + ", reliableProfileTotalSupported=" + reliableProfileTotalSupported
            + ", completeScanSupported=" + completeScanSupported + "]";
    }
}
