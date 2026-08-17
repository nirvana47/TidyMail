package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ActionType;
import com.tidymail.unsubscribe.UnsubscribeIntentService;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeOutcomeStatus;
import com.tidymail.unsubscribe.UnsubscribeReasonCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Handles UNSUBSCRIBE actions by verifying the candidate/destination facts, then
 * dispatching the unsubscribe execution and translating its outcome into a
 * classified handler result.
 *
 * <p>Product functionality supported: the action execution engine's unsubscribe
 * handling and outcome classification.
 */
@Service
public class UnsubscribeActionHandler implements ActionHandler {
    private final UnsubscribeCandidateService candidates;
    private final UnsubscribeIntentService intents;
    private final UnsubscribeExecutionService execution;

    public UnsubscribeActionHandler(
            UnsubscribeCandidateService candidates,
            UnsubscribeIntentService intents,
            UnsubscribeExecutionService execution) {
        this.candidates = candidates;
        this.intents = intents;
        this.execution = execution;
    }

    /** Returns true for UNSUBSCRIBE actions. */
    @Override
    public boolean supports(ActionType actionType) {
        return actionType == ActionType.UNSUBSCRIBE;
    }

    /** Executes the unsubscribe and classifies its outcome. */
    @Override
    public HandlerResult execute(ExecutionContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Unsubscribe transport cannot run in a transaction");
        }
        UnsubscribeMethod method;
        try {
            method = UnsubscribeMethod.valueOf(
                context.intent().getUnsubscribeMethod());
            var candidate = candidates.executionCandidateByFingerprint(
                context.account().getId(),
                context.intent().getUnsubscribeCandidateFingerprint(),
                method);
            if (!candidate.destinationFingerprint().equals(
                    context.intent().getUnsubscribeDestinationFingerprint())
                    || !context.exactTargetIds().equals(
                        java.util.List.of(candidate.providerMessageId()))) {
                return HandlerResult.local(ProviderResultClassifier.failed(
                    context.targetOrdinals(), ActionReasonCode.STALE_ACTION));
            }
            String freshIntent = intents.issue(
                java.util.List.of(context.account().getId()),
                candidate.candidateKey(), method);
            var result = execution.execute(
                java.util.List.of(context.account().getId()),
                candidate.candidateKey(), method, freshIntent);
            return translate(context, result.outcome());
        } catch (RuntimeException staleOrBlocked) {
            return HandlerResult.local(ProviderResultClassifier.failed(
                context.targetOrdinals(), ActionReasonCode.FRESH_CONSENT_REQUIRED));
        }
    }

    /** Translates an unsubscribe outcome into a classified handler result. */
    private static HandlerResult translate(
            ExecutionContext context,
            UnsubscribeOutcomeService.OutcomeView outcome) {
        boolean accepted = outcome.events().stream()
            .anyMatch(event -> event.status() == UnsubscribeOutcomeStatus.ACCEPTED);
        if (outcome.latestStatus() == UnsubscribeOutcomeStatus.VERIFICATION_PENDING) {
            return HandlerResult.unsubscribe(
                ProviderResultClassifier.verificationPending(
                    context.targetOrdinals(), accepted,
                    ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH),
                outcome.attemptId());
        }
        if (outcome.latestStatus() == UnsubscribeOutcomeStatus.FAILED) {
            boolean unknown = outcome.events().stream().anyMatch(event ->
                event.reason() == UnsubscribeReasonCode.TIMEOUT_UNKNOWN_REMOTE_RESULT);
            return HandlerResult.unsubscribe(
                unknown
                    ? ProviderResultClassifier.verificationPending(
                        context.targetOrdinals(), false,
                        ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH)
                    : ProviderResultClassifier.failed(
                        context.targetOrdinals(), ActionReasonCode.PROVIDER_REJECTED),
                outcome.attemptId());
        }
        return HandlerResult.unsubscribe(
            ProviderResultClassifier.verificationPending(
                context.targetOrdinals(), accepted,
                ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH),
            outcome.attemptId());
    }
}
