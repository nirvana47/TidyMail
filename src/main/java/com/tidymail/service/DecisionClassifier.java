package com.tidymail.service;

import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.UnreadDecisionRow;
import com.tidymail.entity.SenderTypeOverride;
import com.tidymail.repository.DecisionSurfaceQueryRepository.SenderFact;
import com.tidymail.repository.DecisionSurfaceQueryRepository.UnreadFact;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Converts raw decision facts into {@link SenderDecisionRow} and
 * {@link UnreadDecisionRow} by running the deterministic decision rules, and
 * verifies the Java classification matches the SQL-computed facts.
 *
 * <p>Product functionality supported: the <em>Senders</em> and <em>Unread</em>
 * flows' decision lists and recommendations.
 */
@Service
public class DecisionClassifier {

    /** Classifies a sender fact into a decision row (no manual override). */
    public SenderDecisionRow sender(String rowKey, SenderFact fact, Instant now) {
        return sender(rowKey, fact, now, (DecisionRuleSet.SenderType) null);
    }

    /** Classifies a sender fact with a manual type override. */
    public SenderDecisionRow sender(
            String rowKey,
            SenderFact fact,
            Instant now,
            SenderTypeOverride.SenderType manualOverride) {
        return sender(rowKey, fact, now, manualOverride == null
            ? null : DecisionRuleSet.SenderType.valueOf(manualOverride.name()));
    }

    /** Classifies a sender fact with a rule-set type override. */
    public SenderDecisionRow sender(
            String rowKey,
            SenderFact fact,
            Instant now,
            DecisionRuleSet.SenderType manualOverride) {
        DecisionRuleSet.SenderDecision decision = DecisionRuleSet.V1.classify(
            new DecisionRuleSet.SenderFacts(
                fact.listSignal(), fact.automatedSignal(), fact.returnPathSignal(),
                fact.messageIdSignal(), fact.threadSignal(), fact.protectedSender(),
                fact.totalCount(), fact.unreadCount(), fact.newestReceivedAt()),
            now,
            manualOverride);
        if (manualOverride == null && (decision.type() != fact.type()
                || decision.risk() != fact.risk()
                || decision.recommendation() != fact.recommendation())) {
            throw new IllegalStateException("SQL and Java sender classification differ");
        }
        return new SenderDecisionRow(
            rowKey,
            fact.accountId(),
            "__missing__".equals(fact.senderKey()) ? "" : fact.senderKey(),
            fact.displayName(),
            fact.domain(),
            fact.totalCount(),
            fact.unreadCount(),
            fact.estimatedSize(),
            fact.oldestReceivedAt(),
            fact.newestReceivedAt(),
            decision.type(),
            decision.risk(),
            decision.recommendation(),
            fact.protectedSender(),
            fact.queued(),
            decision.reasons(),
            decision.reasons().stream().map(DecisionRuleSet.ReasonCode::copy).toList());
    }

    /** Classifies an unread fact into a decision row. */
    public UnreadDecisionRow unread(String rowKey, UnreadFact fact, Instant now) {
        DecisionRuleSet.UnreadDecision decision = DecisionRuleSet.V1.classify(
            new DecisionRuleSet.UnreadFacts(
                fact.listSignal(), fact.automatedSignal(), fact.referencesSignal(),
                fact.inReplyToSignal(), fact.protectedSender(),
                fact.authorizedThreadCount(), fact.receivedAt()),
            now);
        if (decision.category() != fact.category()
                || decision.threadState() != fact.threadState()
                || decision.waitingOnMe() != fact.waitingOnMe()
                || decision.recommendation() != fact.recommendation()) {
            throw new IllegalStateException("SQL and Java unread classification differ");
        }
        return new UnreadDecisionRow(
            rowKey,
            fact.accountId(),
            "__missing__".equals(fact.senderKey()) ? "" : fact.senderKey(),
            fact.displayName(),
            fact.domain(),
            fact.subject(),
            fact.receivedAt(),
            fact.category(),
            fact.threadState(),
            fact.waitingOnMe(),
            fact.recommendation(),
            fact.protectedSender(),
            decision.reasons(),
            decision.reasons().stream().map(DecisionRuleSet.ReasonCode::copy).toList(),
            null);
    }
}
