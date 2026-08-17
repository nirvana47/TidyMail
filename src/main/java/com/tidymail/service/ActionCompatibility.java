package com.tidymail.service;

import com.tidymail.action.ActionType;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ActionCompatibility {
    private static final Map<ActionType, Set<ActionType>> CONFLICTS = Map.of(
        ActionType.ARCHIVE, EnumSet.of(
            ActionType.TRASH, ActionType.KEEP, ActionType.REVIEW),
        ActionType.TRASH, EnumSet.of(
            ActionType.ARCHIVE, ActionType.MARK_READ, ActionType.KEEP,
            ActionType.PROTECT, ActionType.UNSUBSCRIBE, ActionType.REVIEW),
        ActionType.MARK_READ, EnumSet.of(
            ActionType.TRASH, ActionType.KEEP, ActionType.REVIEW),
        ActionType.KEEP, EnumSet.of(
            ActionType.ARCHIVE, ActionType.TRASH, ActionType.MARK_READ,
            ActionType.UNSUBSCRIBE, ActionType.REVIEW),
        ActionType.PROTECT, EnumSet.of(
            ActionType.TRASH, ActionType.UNSUBSCRIBE, ActionType.REVIEW),
        ActionType.UNSUBSCRIBE, EnumSet.of(
            ActionType.TRASH, ActionType.KEEP, ActionType.PROTECT, ActionType.REVIEW),
        ActionType.REVIEW, EnumSet.allOf(ActionType.class),
        ActionType.REMOVE_PROTECTION, EnumSet.of(
            ActionType.PROTECT, ActionType.REMOVE_PROTECTION, ActionType.REVIEW));

    private ActionCompatibility() {
    }

    public static boolean compatible(ActionType left, ActionType right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Action types are required");
        }
        if (left == right) {
            return left != ActionType.REVIEW
                && left != ActionType.REMOVE_PROTECTION;
        }
        return !CONFLICTS.getOrDefault(left, Set.of()).contains(right)
            && !CONFLICTS.getOrDefault(right, Set.of()).contains(left);
    }
}
