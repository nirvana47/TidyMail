package com.tidymail.action;

/**
 * The surface or flow from which a cleanup decision/action originated.
 *
 * <p>Product functionality supported: tracking the origin (and origin reporting)
 * of staged actions across the Senders, Unread, Unsubscribe, and Review flows.
 */
public enum DecisionSource {
    SENDERS,
    UNREAD,
    UNSUBSCRIBE,
    REVIEW,
    LEGACY
}
