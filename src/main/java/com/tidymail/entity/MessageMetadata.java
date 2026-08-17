package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.tidymail.mail.AttachmentState;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.NumericJdbcType;

/**
 * Cached metadata for an individual email message, excluding its body, used to
 * power filtering, risk, and unsubscribe decisions.
 *
 * <p>Product functionality supported: metadata-only mailbox sync feeding the
 * EXACT-scope action engine and unsubscribe detection.
 */
@Entity
@Table(
    name = "message_metadata",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_message_metadata_account_provider_message",
        columnNames = {"account_id", "provider_message_id"})
)
public class MessageMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "provider_message_id", nullable = false, length = 255)
    private String providerMessageId;

    @Column(name = "provider_thread_id", length = 255)
    private String providerThreadId;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(name = "from_display_name", length = 512)
    private String fromDisplayName;

    @Column(name = "from_domain", length = 253)
    private String fromDomain;

    @Column(name = "subject_header", length = 2048)
    private String subjectHeader;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Column(name = "unread", nullable = false)
    private Boolean unread = false;

    /**
     * Provider estimate for the complete RFC message in bytes, including headers,
     * body, and attachments. This is not an attachment-only size.
     */
    @Column(name = "size_estimate")
    private Long sizeEstimate;

    @Column(name = "has_attachments", nullable = false)
    private Boolean hasAttachments = false;

    @JdbcType(NumericJdbcType.class)
    @Column(name = "provider_revision", precision = 20, scale = 0)
    private BigInteger providerRevision;

    @Column(name = "full_scan_generation")
    private java.util.UUID fullScanGeneration;

    @Column(name = "attachment_state", nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private AttachmentState attachmentState = AttachmentState.UNKNOWN;

    @Column(name = "attachment_observed_at")
    private Instant attachmentObservedAt;

    @Column(name = "attachment_source", length = 32)
    private String attachmentSource;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "labels", nullable = false)
    private List<String> labels = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "to_values", nullable = false)
    private List<String> toValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cc_values", nullable = false)
    private List<String> ccValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "bcc_values", nullable = false)
    private List<String> bccValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "list_unsubscribe_values", nullable = false)
    private List<String> listUnsubscribeValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "list_unsubscribe_post_values", nullable = false)
    private List<String> listUnsubscribePostValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "list_id_values", nullable = false)
    private List<String> listIdValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "references_values", nullable = false)
    private List<String> referencesValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "in_reply_to_values", nullable = false)
    private List<String> inReplyToValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reply_to_values", nullable = false)
    private List<String> replyToValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "return_path_values", nullable = false)
    private List<String> returnPathValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "delivered_to_values", nullable = false)
    private List<String> deliveredToValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "precedence_values", nullable = false)
    private List<String> precedenceValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "auto_submitted_values", nullable = false)
    private List<String> autoSubmittedValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "message_id_values", nullable = false)
    private List<String> messageIdValues = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "feedback_id_values", nullable = false)
    private List<String> feedbackIdValues = new ArrayList<>();

    @Column(name = "unsubscribe_dkim_pass", nullable = false)
    private Boolean unsubscribeDkimPass = false;

    @Column(name = "unsubscribe_dkim_covers_list_unsubscribe", nullable = false)
    private Boolean unsubscribeDkimCoversListUnsubscribe = false;

    @Column(name = "unsubscribe_dkim_covers_list_unsubscribe_post", nullable = false)
    private Boolean unsubscribeDkimCoversListUnsubscribePost = false;

    @Column(name = "unsubscribe_auth_reason", length = 32)
    private String unsubscribeAuthReason;

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String value) { this.providerMessageId = value; }
    public String getProviderThreadId() { return providerThreadId; }
    public void setProviderThreadId(String value) { this.providerThreadId = value; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String value) { this.fromAddress = value; }
    public String getFromDisplayName() { return fromDisplayName; }
    public void setFromDisplayName(String value) { this.fromDisplayName = value; }
    public String getFromDomain() { return fromDomain; }
    public void setFromDomain(String value) { this.fromDomain = value; }
    public String getSubjectHeader() { return subjectHeader; }
    public void setSubjectHeader(String value) { this.subjectHeader = value; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant value) { this.receivedAt = value; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant value) { this.firstSeenAt = value; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant value) { this.lastSyncedAt = value; }
    public Boolean getUnread() { return unread; }
    public void setUnread(Boolean value) { this.unread = value; }
    public Long getSizeEstimate() { return sizeEstimate; }
    public void setSizeEstimate(Long value) { this.sizeEstimate = value; }
    public Boolean getHasAttachments() { return hasAttachments; }
    public void setHasAttachments(Boolean value) { this.hasAttachments = value; }
    public BigInteger getProviderRevision() { return providerRevision; }
    public void setProviderRevision(BigInteger value) { providerRevision = value; }
    public java.util.UUID getFullScanGeneration() { return fullScanGeneration; }
    public void setFullScanGeneration(java.util.UUID value) { fullScanGeneration = value; }
    public AttachmentState getAttachmentState() { return attachmentState; }
    public void setAttachmentState(AttachmentState value) { attachmentState = value; }
    public Instant getAttachmentObservedAt() { return attachmentObservedAt; }
    public void setAttachmentObservedAt(Instant value) { attachmentObservedAt = value; }
    public String getAttachmentSource() { return attachmentSource; }
    public void setAttachmentSource(String value) { attachmentSource = value; }
    public List<String> getLabels() { return List.copyOf(labels); }
    public void setLabels(List<String> value) { this.labels = copy(value); }
    public List<String> getToValues() { return List.copyOf(toValues); }
    public void setToValues(List<String> value) { this.toValues = copy(value); }
    public List<String> getCcValues() { return List.copyOf(ccValues); }
    public void setCcValues(List<String> value) { this.ccValues = copy(value); }
    public List<String> getBccValues() { return List.copyOf(bccValues); }
    public void setBccValues(List<String> value) { this.bccValues = copy(value); }
    public List<String> getListUnsubscribeValues() { return List.copyOf(listUnsubscribeValues); }
    public void setListUnsubscribeValues(List<String> value) { this.listUnsubscribeValues = copy(value); }
    public List<String> getListUnsubscribePostValues() { return List.copyOf(listUnsubscribePostValues); }
    public void setListUnsubscribePostValues(List<String> value) { this.listUnsubscribePostValues = copy(value); }
    public List<String> getListIdValues() { return List.copyOf(listIdValues); }
    public void setListIdValues(List<String> value) { this.listIdValues = copy(value); }
    public List<String> getReferencesValues() { return List.copyOf(referencesValues); }
    public void setReferencesValues(List<String> value) { this.referencesValues = copy(value); }
    public List<String> getInReplyToValues() { return List.copyOf(inReplyToValues); }
    public void setInReplyToValues(List<String> value) { this.inReplyToValues = copy(value); }
    public List<String> getReplyToValues() { return List.copyOf(replyToValues); }
    public void setReplyToValues(List<String> value) { this.replyToValues = copy(value); }
    public List<String> getReturnPathValues() { return List.copyOf(returnPathValues); }
    public void setReturnPathValues(List<String> value) { this.returnPathValues = copy(value); }
    public List<String> getDeliveredToValues() { return List.copyOf(deliveredToValues); }
    public void setDeliveredToValues(List<String> value) { this.deliveredToValues = copy(value); }
    public List<String> getPrecedenceValues() { return List.copyOf(precedenceValues); }
    public void setPrecedenceValues(List<String> value) { this.precedenceValues = copy(value); }
    public List<String> getAutoSubmittedValues() { return List.copyOf(autoSubmittedValues); }
    public void setAutoSubmittedValues(List<String> value) { this.autoSubmittedValues = copy(value); }
    public List<String> getMessageIdValues() { return List.copyOf(messageIdValues); }
    public void setMessageIdValues(List<String> value) { this.messageIdValues = copy(value); }
    public List<String> getFeedbackIdValues() { return List.copyOf(feedbackIdValues); }
    public void setFeedbackIdValues(List<String> value) { this.feedbackIdValues = copy(value); }
    public Boolean getUnsubscribeDkimPass() { return unsubscribeDkimPass; }
    public void setUnsubscribeDkimPass(Boolean value) { this.unsubscribeDkimPass = value; }
    public Boolean getUnsubscribeDkimCoversListUnsubscribe() {
        return unsubscribeDkimCoversListUnsubscribe;
    }
    public void setUnsubscribeDkimCoversListUnsubscribe(Boolean value) {
        this.unsubscribeDkimCoversListUnsubscribe = value;
    }
    public Boolean getUnsubscribeDkimCoversListUnsubscribePost() {
        return unsubscribeDkimCoversListUnsubscribePost;
    }
    public void setUnsubscribeDkimCoversListUnsubscribePost(Boolean value) {
        this.unsubscribeDkimCoversListUnsubscribePost = value;
    }
    public String getUnsubscribeAuthReason() { return unsubscribeAuthReason; }
    public void setUnsubscribeAuthReason(String value) { this.unsubscribeAuthReason = value; }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (firstSeenAt == null) firstSeenAt = now;
        if (lastSyncedAt == null) lastSyncedAt = now;
        if (unread == null) unread = false;
        if (hasAttachments == null) hasAttachments = false;
        if (attachmentState == null) attachmentState = AttachmentState.UNKNOWN;
        if (unsubscribeDkimPass == null) unsubscribeDkimPass = false;
        if (unsubscribeDkimCoversListUnsubscribe == null) {
            unsubscribeDkimCoversListUnsubscribe = false;
        }
        if (unsubscribeDkimCoversListUnsubscribePost == null) {
            unsubscribeDkimCoversListUnsubscribePost = false;
        }
    }

    private static ArrayList<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
