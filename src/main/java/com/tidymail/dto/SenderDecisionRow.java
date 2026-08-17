package com.tidymail.dto;

import com.tidymail.service.DecisionRuleSet.ReasonCode;
import com.tidymail.service.DecisionRuleSet.SenderRecommendation;
import com.tidymail.service.DecisionRuleSet.SenderRisk;
import com.tidymail.service.DecisionRuleSet.SenderType;
import java.time.Instant;
import java.util.List;

public record SenderDecisionRow(
    String rowKey,
    long accountId,
    String senderAddress,
    String displayName,
    String domain,
    long totalCount,
    long unreadCount,
    Long estimatedSize,
    Instant oldestReceivedAt,
    Instant newestReceivedAt,
    SenderType type,
    SenderRisk risk,
    SenderRecommendation recommendation,
    boolean protectedSender,
    boolean queued,
    List<ReasonCode> reasonCodes,
    List<String> reasons
) {
    public SenderDecisionRow {
        if (estimatedSize != null && estimatedSize < 0) {
            throw new IllegalArgumentException("Estimated size cannot be negative");
        }
        reasonCodes = List.copyOf(reasonCodes);
        reasons = List.copyOf(reasons);
    }

    public String estimatedSizeLabel() {
        return EstimatedSizeLabel.format(estimatedSize);
    }
}

final class EstimatedSizeLabel {
    private EstimatedSizeLabel() {
    }

    static String format(Long bytes) {
        if (bytes == null) return "";
        if (bytes < 1024) return bytes + " B";
        double kibibytes = bytes / 1024.0;
        if (kibibytes < 1024) return Math.round(kibibytes) + " KB";
        double mebibytes = kibibytes / 1024.0;
        if (mebibytes < 1024) return Math.round(mebibytes) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f", mebibytes / 1024.0)
            + " GB";
    }
}
