package com.tidymail.dto;

import com.tidymail.service.DecisionRuleSet.ReasonCode;
import com.tidymail.service.DecisionRuleSet.ThreadState;
import com.tidymail.service.DecisionRuleSet.UnreadCategory;
import com.tidymail.service.DecisionRuleSet.UnreadRecommendation;
import java.time.Instant;
import java.util.List;

public record UnreadDecisionRow(
    String rowKey,
    long accountId,
    String senderAddress,
    String displayName,
    String domain,
    String subject,
    Instant receivedAt,
    UnreadCategory category,
    ThreadState threadState,
    boolean waitingOnMe,
    UnreadRecommendation recommendation,
    boolean protectedSender,
    List<ReasonCode> reasonCodes,
    List<String> reasons,
    String providerLink
) {
    public UnreadDecisionRow {
        reasonCodes = List.copyOf(reasonCodes);
        reasons = List.copyOf(reasons);
    }
}
