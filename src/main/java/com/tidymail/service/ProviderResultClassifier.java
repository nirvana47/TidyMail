package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.ExecutionTargetStatus;
import com.tidymail.mail.BulkActionResult;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies a provider bulk-action result into per-target facts and execution
 * counts, enforcing the exact-request contract and flagging invalid or unknown
 * outcomes as verification-pending.
 *
 * <p>Product functionality supported: the action execution engine's result
 * classification and contract validation.
 */
public final class ProviderResultClassifier {
    private ProviderResultClassifier() {
    }

    /** Classifies a provider result against the requested ordinals. */
    public static ClassifiedResult classify(
            Map<String, Integer> requestedOrdinals, BulkActionResult result) {
        if (requestedOrdinals == null || requestedOrdinals.isEmpty()) {
            throw new InvalidProviderResultException();
        }
        if (result == null || result.succeededMessageIds() == null
                || result.failedMessageIdToReason() == null) {
            return invalidAfterDispatch(requestedOrdinals);
        }
        List<String> succeeded = result.succeededMessageIds();
        Map<String, String> failed = result.failedMessageIdToReason();
        Set<String> succeededSet = new HashSet<>(succeeded);
        boolean contractInvalid =
            result.requestedCount() != requestedOrdinals.size()
                || result.succeededCount() != succeeded.size()
                || result.failedCount() != failed.size()
                || succeededSet.size() != succeeded.size()
                || !requestedOrdinals.keySet().containsAll(succeededSet)
                || !requestedOrdinals.keySet().containsAll(failed.keySet())
                || succeededSet.stream().anyMatch(failed::containsKey)
                || succeeded.size() + failed.size() > requestedOrdinals.size();

        LinkedHashMap<Integer, TargetFact> facts = new LinkedHashMap<>();
        requestedOrdinals.forEach((id, ordinal) -> {
            if (succeededSet.contains(id) && !failed.containsKey(id)) {
                facts.put(ordinal, new TargetFact(
                    ExecutionTargetStatus.SUCCEEDED, true, ActionReasonCode.NONE));
            } else if (failed.containsKey(id) && !succeededSet.contains(id)) {
                facts.put(ordinal, new TargetFact(
                    ExecutionTargetStatus.FAILED, false,
                    ActionReasonCode.PROVIDER_REJECTED));
            } else {
                facts.put(ordinal, new TargetFact(
                    ExecutionTargetStatus.VERIFICATION_PENDING, false,
                    contractInvalid
                        ? ActionReasonCode.PROVIDER_RESULT_INVALID
                        : ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH));
            }
        });
        int succeededCount = (int) facts.values().stream()
            .filter(fact -> fact.status() == ExecutionTargetStatus.SUCCEEDED).count();
        int failedCount = (int) facts.values().stream()
            .filter(fact -> fact.status() == ExecutionTargetStatus.FAILED).count();
        int pending = requestedOrdinals.size() - succeededCount - failedCount;
        ActionReasonCode reason = contractInvalid
            ? ActionReasonCode.PROVIDER_RESULT_INVALID
            : pending > 0 ? ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH
            : failedCount == 0 ? ActionReasonCode.NONE : ActionReasonCode.PROVIDER_REJECTED;
        return new ClassifiedResult(
            Map.copyOf(facts),
            new ExecutionFacts(
                requestedOrdinals.size(), succeededCount, succeededCount,
                failedCount, pending, 0, reason),
            contractInvalid);
    }

    /** Classifies an exception after dispatch as verification-pending. */
    public static ClassifiedResult afterDispatchException(
            Map<String, Integer> requestedOrdinals) {
        LinkedHashMap<Integer, TargetFact> facts = new LinkedHashMap<>();
        requestedOrdinals.values().forEach(ordinal -> facts.put(
            ordinal, new TargetFact(
                ExecutionTargetStatus.VERIFICATION_PENDING, false,
                ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH)));
        return new ClassifiedResult(
            Map.copyOf(facts),
            new ExecutionFacts(
                requestedOrdinals.size(), 0, 0, 0, requestedOrdinals.size(), 0,
                ActionReasonCode.RESULT_UNKNOWN_AFTER_DISPATCH),
            false);
    }

    /** Builds a uniform all-succeeded result. */
    public static ClassifiedResult allSucceeded(Map<String, Integer> requestedOrdinals) {
        return uniform(
            requestedOrdinals, ExecutionTargetStatus.SUCCEEDED, true,
            ActionReasonCode.NONE, ExecutionStatus.APPLIED);
    }

    /** Builds a uniform failed result with a reason. */
    public static ClassifiedResult failed(
            Map<String, Integer> requestedOrdinals, ActionReasonCode reason) {
        return uniform(
            requestedOrdinals, ExecutionTargetStatus.FAILED, false,
            reason, ExecutionStatus.FAILED);
    }

    /** Builds a uniform verification-pending result. */
    public static ClassifiedResult verificationPending(
            Map<String, Integer> requestedOrdinals,
            boolean accepted,
            ActionReasonCode reason) {
        return uniform(
            requestedOrdinals, ExecutionTargetStatus.VERIFICATION_PENDING,
            accepted, reason, ExecutionStatus.VERIFICATION_PENDING);
    }

    /** Builds a uniform result for all targets. */
    private static ClassifiedResult uniform(
            Map<String, Integer> requestedOrdinals,
            ExecutionTargetStatus targetStatus,
            boolean accepted,
            ActionReasonCode reason,
            ExecutionStatus expectedStatus) {
        if (requestedOrdinals == null || requestedOrdinals.isEmpty()) {
            throw new InvalidProviderResultException();
        }
        LinkedHashMap<Integer, TargetFact> facts = new LinkedHashMap<>();
        requestedOrdinals.values().forEach(ordinal -> facts.put(
            ordinal, new TargetFact(targetStatus, accepted, reason)));
        int requested = requestedOrdinals.size();
        int succeeded = targetStatus == ExecutionTargetStatus.SUCCEEDED ? requested : 0;
        int failed = targetStatus == ExecutionTargetStatus.FAILED ? requested : 0;
        int pending =
            targetStatus == ExecutionTargetStatus.VERIFICATION_PENDING ? requested : 0;
        ExecutionFacts executionFacts = new ExecutionFacts(
            requested, accepted ? requested : 0, succeeded, failed, pending, 0, reason);
        if (executionFacts.status() != expectedStatus) {
            throw new IllegalStateException("Uniform execution facts are inconsistent");
        }
        return new ClassifiedResult(Map.copyOf(facts), executionFacts, false);
    }

    /** Builds a contract-invalid result for all targets. */
    private static ClassifiedResult invalidAfterDispatch(
            Map<String, Integer> requestedOrdinals) {
        LinkedHashMap<Integer, TargetFact> facts = new LinkedHashMap<>();
        requestedOrdinals.values().forEach(ordinal -> facts.put(
            ordinal, new TargetFact(
                ExecutionTargetStatus.VERIFICATION_PENDING, false,
                ActionReasonCode.PROVIDER_RESULT_INVALID)));
        return new ClassifiedResult(
            Map.copyOf(facts),
            new ExecutionFacts(
                requestedOrdinals.size(), 0, 0, 0, requestedOrdinals.size(), 0,
                ActionReasonCode.PROVIDER_RESULT_INVALID),
            true);
    }

    /** The classified outcome for a single target. */
    public record TargetFact(
        ExecutionTargetStatus status,
        boolean accepted,
        ActionReasonCode reason
    ) {
    }

    /** The classified per-target facts and execution counts. */
    public record ClassifiedResult(
        Map<Integer, TargetFact> targets,
        ExecutionFacts facts,
        boolean contractInvalid
    ) {
        public ClassifiedResult {
            targets = Map.copyOf(targets);
        }
    }

    /** Thrown when a provider result violates the exact request contract. */
    public static final class InvalidProviderResultException
            extends IllegalArgumentException {
        public InvalidProviderResultException() {
            super("Provider result violated the exact request contract");
        }
    }
}
