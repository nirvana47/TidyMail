package com.tidymail.service;

import com.tidymail.action.ActionType;
import com.tidymail.action.ActionStatus;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract for executing a single cleanup action against its exact target scope.
 * Implementations dispatch to the provider (mailbox mutation) or handle local
 * actions (protect, review, unsubscribe), returning a classified result.
 *
 * <p>Product functionality supported: the action execution engine that applies
 * archive/trash/mark-read/protect/unsubscribe actions.
 */
public interface ActionHandler {
    /** Returns true when this handler can execute the given action type. */
    boolean supports(ActionType actionType);

    /** Executes the action described by the context and returns its result. */
    HandlerResult execute(ExecutionContext context);

    /** The account, action, scope, intent, and exact targets for one execution. */
    record ExecutionContext(
        UserAccount account,
        UserAction action,
        ActionScope scope,
        ActionIntent intent,
        ActionType actionType,
        List<String> exactTargetIds,
        Map<String, Integer> targetOrdinals
    ) {
        public ExecutionContext {
            exactTargetIds = List.copyOf(exactTargetIds);
            targetOrdinals = Map.copyOf(new LinkedHashMap<>(targetOrdinals));
        }
    }

    /** The classified outcome of an execution, with optional overrides. */
    record HandlerResult(
        ProviderResultClassifier.ClassifiedResult classified,
        Long unsubscribeAttemptId,
        ActionStatus actionStatusOverride,
        boolean undoEligible
    ) {
        public HandlerResult {
            if (classified == null) {
                throw new IllegalArgumentException("Classified result is required");
            }
        }

        /** Creates a result for a provider mailbox mutation. */
        public static HandlerResult mailbox(
                ProviderResultClassifier.ClassifiedResult classified) {
            return new HandlerResult(classified, null, null, true);
        }

        /** Creates a result for a local (non-provider) action. */
        public static HandlerResult local(
                ProviderResultClassifier.ClassifiedResult classified) {
            return new HandlerResult(classified, null, null, false);
        }

        /** Creates a result that blocks the action pending review. */
        public static HandlerResult review(
                ProviderResultClassifier.ClassifiedResult classified) {
            return new HandlerResult(
                classified, null, ActionStatus.BLOCKED, false);
        }

        /** Creates a result for an unsubscribe attempt. */
        public static HandlerResult unsubscribe(
                ProviderResultClassifier.ClassifiedResult classified,
                long attemptId) {
            return new HandlerResult(classified, attemptId, null, false);
        }
    }
}
