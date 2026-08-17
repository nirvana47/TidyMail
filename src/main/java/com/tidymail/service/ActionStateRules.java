package com.tidymail.service;

import com.tidymail.action.ActionStatus;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.ExecutionTargetStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Defines the allowed state transitions for actions, executions, and execution
 * targets, so the execution engine can reject invalid lifecycle changes.
 *
 * <p>Product functionality supported: the action execution engine's state machine
 * and integrity enforcement.
 */
public final class ActionStateRules {
    private static final Map<ActionStatus, Set<ActionStatus>> ACTION = Map.ofEntries(
        Map.entry(ActionStatus.QUEUED, EnumSet.of(
            ActionStatus.BLOCKED, ActionStatus.EXECUTING, ActionStatus.CANCELLED)),
        Map.entry(ActionStatus.BLOCKED, EnumSet.of(
            ActionStatus.QUEUED, ActionStatus.CANCELLED)),
        Map.entry(ActionStatus.EXECUTING, EnumSet.of(
            ActionStatus.APPLIED, ActionStatus.PARTIAL,
            ActionStatus.VERIFICATION_PENDING, ActionStatus.FAILED)),
        Map.entry(ActionStatus.APPLIED, EnumSet.of(
            ActionStatus.UNDONE, ActionStatus.PARTIALLY_UNDONE,
            ActionStatus.COMPENSATED)),
        Map.entry(ActionStatus.PARTIAL, EnumSet.of(
            ActionStatus.PARTIALLY_UNDONE, ActionStatus.COMPENSATED)),
        Map.entry(ActionStatus.VERIFICATION_PENDING, EnumSet.of(
            ActionStatus.APPLIED, ActionStatus.PARTIAL, ActionStatus.FAILED)),
        Map.entry(ActionStatus.FAILED, EnumSet.noneOf(ActionStatus.class)),
        Map.entry(ActionStatus.CANCELLED, EnumSet.noneOf(ActionStatus.class)),
        Map.entry(ActionStatus.UNDONE, EnumSet.noneOf(ActionStatus.class)),
        Map.entry(ActionStatus.PARTIALLY_UNDONE, EnumSet.noneOf(ActionStatus.class)),
        Map.entry(ActionStatus.COMPENSATED, EnumSet.noneOf(ActionStatus.class)));

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> EXECUTION = Map.of(
        ExecutionStatus.RESERVED, EnumSet.of(
            ExecutionStatus.RUNNING, ExecutionStatus.FAILED),
        ExecutionStatus.RUNNING, EnumSet.of(
            ExecutionStatus.APPLIED, ExecutionStatus.PARTIAL,
            ExecutionStatus.VERIFICATION_PENDING, ExecutionStatus.FAILED),
        ExecutionStatus.APPLIED, EnumSet.noneOf(ExecutionStatus.class),
        ExecutionStatus.PARTIAL, EnumSet.noneOf(ExecutionStatus.class),
        ExecutionStatus.VERIFICATION_PENDING, EnumSet.of(
            ExecutionStatus.APPLIED, ExecutionStatus.PARTIAL, ExecutionStatus.FAILED),
        ExecutionStatus.FAILED, EnumSet.noneOf(ExecutionStatus.class));

    private static final Map<ExecutionTargetStatus, Set<ExecutionTargetStatus>> TARGET =
        Map.of(
            ExecutionTargetStatus.PENDING, EnumSet.of(
                ExecutionTargetStatus.SUCCEEDED, ExecutionTargetStatus.FAILED,
                ExecutionTargetStatus.VERIFICATION_PENDING),
            ExecutionTargetStatus.SUCCEEDED, EnumSet.of(
                ExecutionTargetStatus.REVERTED, ExecutionTargetStatus.COMPENSATED),
            ExecutionTargetStatus.FAILED, EnumSet.noneOf(ExecutionTargetStatus.class),
            ExecutionTargetStatus.VERIFICATION_PENDING,
                EnumSet.of(ExecutionTargetStatus.SUCCEEDED, ExecutionTargetStatus.FAILED),
            ExecutionTargetStatus.REVERTED, EnumSet.noneOf(ExecutionTargetStatus.class),
            ExecutionTargetStatus.COMPENSATED, EnumSet.noneOf(ExecutionTargetStatus.class));

    private ActionStateRules() {
    }

    /** Returns true if an action may transition between the given statuses. */
    public static boolean canTransition(ActionStatus from, ActionStatus to) {
        return ACTION.get(from).contains(to);
    }

    /** Returns true if an execution may transition between the given statuses. */
    public static boolean canTransition(ExecutionStatus from, ExecutionStatus to) {
        return EXECUTION.get(from).contains(to);
    }

    /** Returns true if an execution target may transition between the given statuses. */
    public static boolean canTransition(
            ExecutionTargetStatus from, ExecutionTargetStatus to) {
        return TARGET.get(from).contains(to);
    }
}
