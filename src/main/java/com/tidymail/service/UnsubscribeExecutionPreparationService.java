package com.tidymail.service;

import com.tidymail.repository.ProtectedSenderRepository;
import com.tidymail.service.UnsubscribeCandidateService.ExecutionCandidate;
import com.tidymail.unsubscribe.UnsubscribeIntentService;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeReasonCode;
import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prepares unsubscribe executions: consumes the intent, enforces the protected-
 * sender gate, and creates a pending outcome (or records a manual completion).
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's execution
 * preparation and protected-sender safety gate.
 */
@Service
public class UnsubscribeExecutionPreparationService {
    private final UnsubscribeIntentService intents;
    private final ProtectedSenderRepository protectedSenders;
    private final UnsubscribeOutcomeService outcomes;

    public UnsubscribeExecutionPreparationService(
            UnsubscribeIntentService intents,
            ProtectedSenderRepository protectedSenders,
            UnsubscribeOutcomeService outcomes) {
        this.intents = intents;
        this.protectedSenders = protectedSenders;
        this.outcomes = outcomes;
    }

    /** Prepares an execution and creates a pending outcome. */
    @Transactional
    public PreparedExecution prepare(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod method,
            String intent) {
        ExecutionCandidate candidate =
            intents.consume(authorizedAccountIds, candidateKey, method, intent)
                .candidateOrThrow();
        hardProtectionGate(candidate);
        return new PreparedExecution(candidate, outcomes.createPending(candidate));
    }

    /** Records a manual completion for a candidate. */
    @Transactional
    public UnsubscribeOutcomeService.OutcomeView completeManual(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod method,
            String intent,
            boolean userConfirmed) {
        ExecutionCandidate candidate =
            intents.consume(authorizedAccountIds, candidateKey, method, intent)
                .candidateOrThrow();
        hardProtectionGate(candidate);
        return outcomes.manualCompleted(candidate, userConfirmed);
    }

    /** Blocks execution for protected senders. */
    private void hardProtectionGate(ExecutionCandidate candidate) {
        if (protectedSenders.findByAccountIdAndSenderAddress(
                candidate.accountId(), candidate.senderAddress()).isPresent()) {
            throw new UnsubscribeExecutionService.ExecutionException(
                HttpStatus.CONFLICT, UnsubscribeReasonCode.PROTECTED_SENDER);
        }
    }

    /** A prepared execution with its pending attempt ID. */
    public record PreparedExecution(ExecutionCandidate candidate, long attemptId) {
    }
}
