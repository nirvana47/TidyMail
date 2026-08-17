package com.tidymail.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic, non-AI heuristics that classify senders and unread messages
 * into types, risks, categories, and recommendations with human-readable reasons.
 *
 * <p>Product functionality supported: the <em>Senders</em> and <em>Unread</em>
 * flows' recommendations and the Reply Zero queue's reply-required detection.
 */
public final class DecisionRuleSet {
    public static final DecisionRuleSet V1 = new DecisionRuleSet();

    private DecisionRuleSet() {
    }

    /** Classifies a sender without a manual type override. */
    public SenderDecision classify(SenderFacts facts, Instant now) {
        return classify(facts, now, null);
    }

    /** Classifies a sender, optionally applying a manual type override. */
    public SenderDecision classify(SenderFacts facts, Instant now, SenderType manualOverride) {
        requireNow(now);
        SenderType inferredType;
        ReasonCode typeReason;
        if (facts.messageIdSignal() && facts.threadSignal()) {
            inferredType = SenderType.PERSONAL;
            typeReason = ReasonCode.DIRECT_MESSAGE;
        } else if (facts.returnPathSignal() && !facts.threadSignal()
                && !facts.automatedSignal()) {
            inferredType = SenderType.TRANSACTIONAL;
            typeReason = ReasonCode.TRANSACTIONAL_RETURN_PATH;
        } else if (facts.listSignal()) {
            inferredType = SenderType.LIST;
            typeReason = ReasonCode.LIST_HEADERS_PRESENT;
        } else if (facts.automatedSignal()) {
            inferredType = SenderType.AUTOMATED;
            typeReason = ReasonCode.AUTOMATED_HEADER_PRESENT;
        } else if (facts.returnPathSignal() && !facts.threadSignal()) {
            inferredType = SenderType.TRANSACTIONAL;
            typeReason = ReasonCode.TRANSACTIONAL_RETURN_PATH;
        } else {
            inferredType = SenderType.UNKNOWN;
            typeReason = ReasonCode.AMBIGUOUS_METADATA;
        }
        if (manualOverride == SenderType.UNKNOWN) {
            throw new IllegalArgumentException("Manual sender type cannot be UNKNOWN");
        }
        SenderType type = manualOverride == null ? inferredType : manualOverride;
        if (manualOverride != null) {
            typeReason = ReasonCode.MANUAL_SENDER_TYPE_OVERRIDE;
        }

        SenderRisk risk;
        ReasonCode riskReason;
        if (facts.protectedSender()) {
            risk = SenderRisk.HIGH;
            riskReason = ReasonCode.PROTECTED_SENDER;
        } else if (type == SenderType.PERSONAL || facts.threadSignal()) {
            risk = SenderRisk.HIGH;
            riskReason = ReasonCode.HAS_THREAD_REFERENCES;
        } else if (type == SenderType.TRANSACTIONAL || type == SenderType.UNKNOWN) {
            risk = SenderRisk.MEDIUM;
            riskReason = ReasonCode.CONSERVATIVE_REVIEW;
        } else if (facts.newestReceivedAt() != null
                && !facts.newestReceivedAt().isBefore(now.minus(7, ChronoUnit.DAYS))) {
            risk = SenderRisk.MEDIUM;
            riskReason = ReasonCode.RECENT_ACTIVITY;
        } else {
            risk = SenderRisk.LOW;
            riskReason = ReasonCode.OLDER_AUTOMATED_MAIL;
        }

        SenderRecommendation recommendation;
        ReasonCode recommendationReason;
        if (facts.protectedSender()) {
            recommendation = SenderRecommendation.KEEP;
            recommendationReason = ReasonCode.PROTECTED_SENDER;
        } else if (risk == SenderRisk.HIGH
                || type == SenderType.PERSONAL
                || type == SenderType.TRANSACTIONAL
                || type == SenderType.UNKNOWN) {
            recommendation = SenderRecommendation.REVIEW;
            recommendationReason = ReasonCode.CONSERVATIVE_REVIEW;
        } else if ((type == SenderType.LIST || type == SenderType.AUTOMATED)
                && facts.newestReceivedAt() != null
                && facts.newestReceivedAt().isBefore(now.minus(90, ChronoUnit.DAYS))) {
            recommendation = SenderRecommendation.TRASH;
            recommendationReason = ReasonCode.OLDER_THAN_90_DAYS;
        } else if (facts.totalCount() > 0 && facts.unreadCount() == facts.totalCount()) {
            recommendation = SenderRecommendation.MARK_READ;
            recommendationReason = ReasonCode.ALL_MESSAGES_UNREAD;
        } else {
            recommendation = SenderRecommendation.ARCHIVE;
            recommendationReason = ReasonCode.ROUTINE_CLEANUP;
        }
        return new SenderDecision(
            type, risk, recommendation, distinct(typeReason, riskReason, recommendationReason));
    }

    /** Classifies an unread message and its thread state. */
    public UnreadDecision classify(UnreadFacts facts, Instant now) {
        requireNow(now);
        UnreadCategory category;
        ReasonCode categoryReason;
        if (facts.listSignal()) {
            category = UnreadCategory.LIST;
            categoryReason = ReasonCode.LIST_HEADERS_PRESENT;
        } else if (facts.automatedSignal()) {
            category = UnreadCategory.AUTOMATED;
            categoryReason = ReasonCode.AUTOMATED_HEADER_PRESENT;
        } else {
            category = UnreadCategory.ONE_TO_ONE;
            categoryReason = ReasonCode.DIRECT_MESSAGE;
        }

        boolean hasInboundReference = facts.referencesSignal() || facts.inReplyToSignal();
        ThreadState threadState = facts.authorizedThreadCount() > 1 || hasInboundReference
            ? ThreadState.THREADED : ThreadState.SINGLE;
        boolean waitingOnMe = category == UnreadCategory.ONE_TO_ONE
            && threadState == ThreadState.THREADED && hasInboundReference;

        UnreadRecommendation recommendation;
        ReasonCode recommendationReason;
        if (waitingOnMe) {
            recommendation = UnreadRecommendation.REPLY_REQUIRED;
            recommendationReason = ReasonCode.WAITING_ON_ME;
        } else if (category == UnreadCategory.ONE_TO_ONE
                && threadState == ThreadState.THREADED) {
            recommendation = UnreadRecommendation.REVIEW;
            recommendationReason = ReasonCode.HAS_THREAD_REFERENCES;
        } else if (category == UnreadCategory.ONE_TO_ONE) {
            recommendation = UnreadRecommendation.KEEP;
            recommendationReason = ReasonCode.DIRECT_MESSAGE;
        } else if (!facts.protectedSender() && facts.receivedAt() != null
                && facts.receivedAt().isBefore(now.minus(90, ChronoUnit.DAYS))) {
            recommendation = UnreadRecommendation.TRASH;
            recommendationReason = ReasonCode.OLDER_THAN_90_DAYS;
        } else if (!facts.protectedSender() && facts.receivedAt() != null
                && facts.receivedAt().isBefore(now.minus(30, ChronoUnit.DAYS))) {
            recommendation = UnreadRecommendation.ARCHIVE;
            recommendationReason = ReasonCode.OLDER_THAN_30_DAYS;
        } else {
            recommendation = UnreadRecommendation.MARK_READ;
            recommendationReason = facts.protectedSender()
                ? ReasonCode.PROTECTED_SENDER : ReasonCode.RECENT_UNREAD;
        }

        ArrayList<ReasonCode> reasons = new ArrayList<>();
        reasons.add(categoryReason);
        reasons.add(threadState == ThreadState.THREADED
            ? ReasonCode.HAS_THREAD_REFERENCES : ReasonCode.NO_REPLY_SIGNAL);
        reasons.add(recommendationReason);
        return new UnreadDecision(
            category, threadState, waitingOnMe, recommendation, List.copyOf(reasons.stream().distinct().toList()));
    }

    /** Returns the distinct reason codes in order. */
    private static List<ReasonCode> distinct(ReasonCode... values) {
        return List.of(values).stream().distinct().toList();
    }

    /** Requires a non-null classification instant. */
    private static void requireNow(Instant now) {
        if (now == null) throw new IllegalArgumentException("Classification instant is required");
    }

    /** The metadata facts used to classify a sender. */
    public record SenderFacts(
        boolean listSignal,
        boolean automatedSignal,
        boolean returnPathSignal,
        boolean messageIdSignal,
        boolean threadSignal,
        boolean protectedSender,
        long totalCount,
        long unreadCount,
        Instant newestReceivedAt
    ) {
        public SenderFacts {
            if (totalCount < 0 || unreadCount < 0 || unreadCount > totalCount) {
                throw new IllegalArgumentException("Invalid sender counts");
            }
        }
    }

    /** The metadata facts used to classify an unread message. */
    public record UnreadFacts(
        boolean listSignal,
        boolean automatedSignal,
        boolean referencesSignal,
        boolean inReplyToSignal,
        boolean protectedSender,
        long authorizedThreadCount,
        Instant receivedAt
    ) {
        public UnreadFacts {
            if (authorizedThreadCount < 0) {
                throw new IllegalArgumentException("Thread count cannot be negative");
            }
        }
    }

    /** The classification result for a sender. */
    public record SenderDecision(
        SenderType type,
        SenderRisk risk,
        SenderRecommendation recommendation,
        List<ReasonCode> reasons
    ) {
        public SenderDecision {
            reasons = List.copyOf(reasons);
        }
    }

    /** The classification result for an unread message. */
    public record UnreadDecision(
        UnreadCategory category,
        ThreadState threadState,
        boolean waitingOnMe,
        UnreadRecommendation recommendation,
        List<ReasonCode> reasons
    ) {
        public UnreadDecision {
            reasons = List.copyOf(reasons);
        }
    }

    /** The inferred sender types. */
    public enum SenderType { LIST, AUTOMATED, TRANSACTIONAL, PERSONAL, UNKNOWN }
    /** The sender risk levels. */
    public enum SenderRisk { HIGH, MEDIUM, LOW }
    /** The recommended handling for a sender. */
    public enum SenderRecommendation { TRASH, ARCHIVE, MARK_READ, REVIEW, KEEP }
    /** The inferred unread message categories. */
    public enum UnreadCategory { LIST, AUTOMATED, ONE_TO_ONE }
    /** The thread shape of an unread message. */
    public enum ThreadState { THREADED, SINGLE }
    /** The recommended handling for an unread message. */
    public enum UnreadRecommendation {
        REPLY_REQUIRED, REVIEW, KEEP, MARK_READ, ARCHIVE, TRASH
    }

    /** A human-readable reason code explaining a classification. */
    public enum ReasonCode {
        LIST_HEADERS_PRESENT("List or unsubscribe headers are indexed."),
        AUTOMATED_HEADER_PRESENT("Automated delivery headers are indexed."),
        TRANSACTIONAL_RETURN_PATH("A return path is present without a reply signal."),
        DIRECT_MESSAGE("Direct-message metadata is present."),
        AMBIGUOUS_METADATA("Indexed metadata is ambiguous, so review is recommended."),
        MANUAL_SENDER_TYPE_OVERRIDE("Your saved sender type correction is applied."),
        PROTECTED_SENDER("This sender is protected."),
        HAS_THREAD_REFERENCES("Reply or repeated-thread metadata is present."),
        CONSERVATIVE_REVIEW("The metadata requires conservative review."),
        RECENT_ACTIVITY("Messages arrived within the last seven days."),
        OLDER_AUTOMATED_MAIL("Automated mail has no recent activity."),
        OLDER_THAN_90_DAYS("The indexed message activity is older than 90 days."),
        OLDER_THAN_30_DAYS("The indexed unread message is older than 30 days."),
        ALL_MESSAGES_UNREAD("Every matching indexed message is unread."),
        ROUTINE_CLEANUP("No higher-priority risk rule matched."),
        WAITING_ON_ME("Inbound thread references indicate a reply may be needed."),
        NO_REPLY_SIGNAL("No reply or repeated-thread signal is indexed."),
        RECENT_UNREAD("The unread message is not older than 30 days.");

        private final String copy;

        ReasonCode(String copy) {
            this.copy = copy;
        }

        /** Returns the human-readable copy for this reason. */
        public String copy() {
            return copy;
        }
    }
}
