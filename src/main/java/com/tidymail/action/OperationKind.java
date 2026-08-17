package com.tidymail.action;

/**
 * Distinguishes the direction of an execution: a forward action, an undo, or a
 * protection compensation.
 *
 * <p>Product functionality supported: the action execution engine's operation
 * tracking across forward/undo/compensation.
 */
public enum OperationKind {
    FORWARD,
    UNDO,
    COMPENSATION
}
