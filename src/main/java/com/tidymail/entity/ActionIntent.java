package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Immutable record of what a user action intends to affect, such as a set of
 * messages, a sender, or an unsubscribe candidate.
 *
 * <p>Product functionality supported: exact-scope action-execution engine for
 * messages, protection, review, compensation, and unsubscribe intents.
 */
@Entity
@Immutable
@Table(name = "action_intents")
public class ActionIntent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "action_id", nullable = false, unique = true)
    private Long actionId;
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    @Column(name = "subject_kind", nullable = false, length = 32)
    private String subjectKind;
    @Column(name = "safe_target_label", nullable = false, length = 512)
    private String safeTargetLabel;
    @Column(name = "target_search_key", nullable = false, length = 512)
    private String targetSearchKey;
    @Column(name = "normalized_sender_address", length = 320)
    private String normalizedSenderAddress;
    @Column(name = "unsubscribe_candidate_fingerprint", length = 64)
    private String unsubscribeCandidateFingerprint;
    @Column(name = "unsubscribe_destination_fingerprint", length = 64)
    private String unsubscribeDestinationFingerprint;
    @Column(name = "unsubscribe_method", length = 24)
    private String unsubscribeMethod;
    @Column(name = "unsubscribe_intent_nonce_id")
    private Long unsubscribeIntentNonceId;
    @Column(name = "compensates_action_id")
    private Long compensatesActionId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ActionIntent() {
    }

    /**
     * Builds an intent over a set of messages described by a search key.
     */
    public static ActionIntent messages(
            long actionId, long accountId, String label, String searchKey, Instant now) {
        if (actionId <= 0 || accountId <= 0 || label == null || label.isBlank()
                || label.length() > 512 || searchKey == null || searchKey.length() > 512) {
            throw new IllegalArgumentException("Invalid action intent");
        }
        ActionIntent intent = new ActionIntent();
        intent.actionId = actionId;
        intent.accountId = accountId;
        intent.subjectKind = "MESSAGES";
        intent.safeTargetLabel = label.strip();
        intent.targetSearchKey = searchKey.strip().toLowerCase();
        intent.createdAt = now;
        return intent;
    }

    /**
     * Builds a sender-protection intent from a normalized sender address.
     */
    public static ActionIntent protection(
            long actionId, long accountId, String label,
            String normalizedSenderAddress, Instant now) {
        ActionIntent intent = messages(
            actionId, accountId, label, normalizedSenderAddress, now);
        intent.subjectKind = "SENDER";
        intent.normalizedSenderAddress = normalizedSenderAddress;
        return intent;
    }

    /**
     * Builds a local-review intent over the given messages.
     */
    public static ActionIntent review(
            long actionId, long accountId, String label, String searchKey, Instant now) {
        ActionIntent intent = messages(actionId, accountId, label, searchKey, now);
        intent.subjectKind = "LOCAL_REVIEW";
        return intent;
    }

    /**
     * Builds a compensation intent that reverses a prior action.
     */
    public static ActionIntent compensation(
            long actionId, long accountId, String label,
            String normalizedSenderAddress, long compensatesActionId, Instant now) {
        if (compensatesActionId <= 0) {
            throw new IllegalArgumentException("Invalid compensation link");
        }
        ActionIntent intent = protection(
            actionId, accountId, label, normalizedSenderAddress, now);
        intent.subjectKind = "SENDER";
        intent.compensatesActionId = compensatesActionId;
        return intent;
    }

    /**
     * Builds an unsubscribe-candidate intent with its fingerprints and method.
     */
    public static ActionIntent unsubscribe(
            long actionId,
            long accountId,
            String label,
            String searchKey,
            String candidateFingerprint,
            String destinationFingerprint,
            String method,
            Instant now) {
        if (candidateFingerprint == null
                || !candidateFingerprint.matches("[0-9a-f]{64}")
                || destinationFingerprint == null
                || !destinationFingerprint.matches("[0-9a-f]{64}")
                || method == null || method.isBlank()) {
            throw new IllegalArgumentException("Invalid unsubscribe intent");
        }
        ActionIntent intent = messages(actionId, accountId, label, searchKey, now);
        intent.subjectKind = "UNSUBSCRIBE_CANDIDATE";
        intent.unsubscribeCandidateFingerprint = candidateFingerprint;
        intent.unsubscribeDestinationFingerprint = destinationFingerprint;
        intent.unsubscribeMethod = method;
        return intent;
    }

    public Long getId() { return id; }
    public Long getActionId() { return actionId; }
    public Long getAccountId() { return accountId; }
    public String getSubjectKind() { return subjectKind; }
    public String getSafeTargetLabel() { return safeTargetLabel; }
    public String getTargetSearchKey() { return targetSearchKey; }
    public String getNormalizedSenderAddress() { return normalizedSenderAddress; }
    public String getUnsubscribeCandidateFingerprint() {
        return unsubscribeCandidateFingerprint;
    }
    public String getUnsubscribeDestinationFingerprint() {
        return unsubscribeDestinationFingerprint;
    }
    public String getUnsubscribeMethod() { return unsubscribeMethod; }
    public Long getUnsubscribeIntentNonceId() { return unsubscribeIntentNonceId; }
    public Long getCompensatesActionId() { return compensatesActionId; }
    public Instant getCreatedAt() { return createdAt; }
}
