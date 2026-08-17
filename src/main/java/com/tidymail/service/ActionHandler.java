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

public interface ActionHandler {
    boolean supports(ActionType actionType);

    HandlerResult execute(ExecutionContext context);

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

        public static HandlerResult mailbox(
                ProviderResultClassifier.ClassifiedResult classified) {
            return new HandlerResult(classified, null, null, true);
        }

        public static HandlerResult local(
                ProviderResultClassifier.ClassifiedResult classified) {
            return new HandlerResult(classified, null, null, false);
        }

        public static HandlerResult review(
                ProviderResultClassifier.ClassifiedResult classified) {
            return new HandlerResult(
                classified, null, ActionStatus.BLOCKED, false);
        }

        public static HandlerResult unsubscribe(
                ProviderResultClassifier.ClassifiedResult classified,
                long attemptId) {
            return new HandlerResult(classified, attemptId, null, false);
        }
    }
}
