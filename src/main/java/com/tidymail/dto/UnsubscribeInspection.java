package com.tidymail.dto;

import java.time.Instant;
import java.util.Map;
import java.util.List;

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

    public UnsubscribeInspection withIntents(Map<String, String> intents) {
        return new UnsubscribeInspection(
            candidateKey, accountLabel, senderDisplayName, senderAddress, listIdentity,
            lastReceivedAt, confidence, confidenceReason, protectedSender,
            listUnsubscribePresent, listUnsubscribePostPresent, dkimPassed,
            dkimCoversListUnsubscribe, dkimCoversListUnsubscribePost, latestOutcome,
            latestOutcomeAt,
            methods.stream().map(method -> method.withIntent(intents.get(method.method()))).toList());
    }

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
        public MethodFact withIntent(String value) {
            return new MethodFact(
                method, label, behavior, destinationHost, redactedPathShape,
                queryTokenHidden, eligible, reason, navigationUrl, navigationLabel,
                externalNotice, mailtoSubjectPresent, mailtoBodyPresent, value);
        }
    }
}
