package com.tidymail.dto;

import java.time.Instant;
import java.util.List;

public record UnsubscribeCandidateRow(
    String candidateKey,
    long accountId,
    long sourceMetadataId,
    String accountLabel,
    String senderDisplayName,
    String senderAddress,
    String senderDomain,
    String listIdentity,
    long estimatedThirtyDayMessages,
    Instant lastReceivedAt,
    List<String> methodLabels,
    String recommendedMethod,
    String confidence,
    String reason,
    boolean needsReview,
    boolean protectedSender,
    String latestOutcome,
    Instant latestOutcomeAt,
    String latestOutcomeMethod,
    String recommendation,
    String senderRowKey,
    long emailCount,
    long readCount,
    int readPercent,
    String lifecycleCategory,
    boolean unsubscribeExecutable,
    String primaryAction
) {
    public UnsubscribeCandidateRow(
            String candidateKey,
            long accountId,
            long sourceMetadataId,
            String accountLabel,
            String senderDisplayName,
            String senderAddress,
            String senderDomain,
            String listIdentity,
            long estimatedThirtyDayMessages,
            Instant lastReceivedAt,
            List<String> methodLabels,
            String recommendedMethod,
            String confidence,
            String reason,
            boolean needsReview,
            boolean protectedSender,
            String latestOutcome,
            Instant latestOutcomeAt,
            String latestOutcomeMethod,
            String recommendation) {
        this(
            candidateKey, accountId, sourceMetadataId, accountLabel, senderDisplayName,
            senderAddress, senderDomain, listIdentity, estimatedThirtyDayMessages,
            lastReceivedAt, methodLabels, recommendedMethod, confidence, reason,
            needsReview, protectedSender, latestOutcome, latestOutcomeAt,
            latestOutcomeMethod, recommendation,
            candidateKey, estimatedThirtyDayMessages, 0, 0,
            "UNHANDLED", false, recommendation);
    }

    public UnsubscribeCandidateRow {
        methodLabels = List.copyOf(methodLabels);
        if (emailCount < 0 || readCount < 0 || readCount > emailCount
                || readPercent < 0 || readPercent > 100
                || senderRowKey == null || senderRowKey.isBlank()
                || lifecycleCategory == null || lifecycleCategory.isBlank()
                || primaryAction == null || primaryAction.isBlank()) {
            throw new IllegalArgumentException("Invalid unsubscribe sender row");
        }
    }
}
