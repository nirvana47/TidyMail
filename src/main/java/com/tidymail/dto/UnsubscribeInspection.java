package com.tidymail.dto;

import java.time.Instant;
import java.util.Map;
import java.util.List;

/**
 * A detailed inspection of a single unsubscribe candidate: sender identity,
 * confidence, DKIM/list-unsubscribe facts, prior outcome, and each available
 * method's redacted destination details.
 *
 * <p>Product functionality supported: the <em>Unsubscribe</em> flow's inspection
 * step, which shows redacted destination facts before the user chooses a method.
 */
public record UnsubscribeInspection(
    String candidateKey,
    String accountLabel,
    String senderDisplayName,
    String senderAddress,
    String listIdentity,
    Instant lastReceivedAt,
    String confidence,
    String confidenceReason,
    boolean protectedSender,
    boolean listUnsubscribePresent,
    boolean listUnsubscribePostPresent,
    boolean dkimPassed,
    boolean dkimCoversListUnsubscribe,
    boolean dkimCoversListUnsubscribePost,
    String latestOutcome,
    Instant latestOutcomeAt,
    List<MethodFact> methods
) {
    public UnsubscribeInspection {
        methods = List.copyOf(methods);
    }

    /** Returns a copy of this inspection with the given per-method intents applied. */
    public UnsubscribeInspection withIntents(Map<String, String> intents) {
        return new UnsubscribeInspection(
            candidateKey, accountLabel, senderDisplayName, senderAddress, listIdentity,
            lastReceivedAt, confidence, confidenceReason, protectedSender,
            listUnsubscribePresent, listUnsubscribePostPresent, dkimPassed,
            dkimCoversListUnsubscribe, dkimCoversListUnsubscribePost, latestOutcome,
            latestOutcomeAt,
            methods.stream().map(method -> method.withIntent(intents.get(method.method()))).toList());
    }

    /** A single unsubscribe method with its redacted destination facts and eligibility. */
    public record MethodFact(
        String method,
        String label,
        String behavior,
        String destinationHost,
        String redactedPathShape,
        boolean queryTokenHidden,
        boolean eligible,
        String reason,
        String navigationUrl,
        String navigationLabel,
        String externalNotice,
        boolean mailtoSubjectPresent,
        boolean mailtoBodyPresent,
        String intent
    ) {
        /** Returns a copy of this method fact carrying the user's intent. */
        public MethodFact withIntent(String value) {
            return new MethodFact(
                method, label, behavior, destinationHost, redactedPathShape,
                queryTokenHidden, eligible, reason, navigationUrl, navigationLabel,
                externalNotice, mailtoSubjectPresent, mailtoBodyPresent, value);
        }
    }
}
