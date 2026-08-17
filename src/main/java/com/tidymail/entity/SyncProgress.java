package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Tracks the progress and status of a mailbox sync job per account (messages scanned,
 * pages completed, checkpoint token, lane/phase), including retry and error details.
 * <p>Product functionality: mailbox sync progress — drives sync status reporting
 * and resumable, checkpointed mailbox synchronization.
 */
@Entity
@Table(name = "sync_progress")
public class SyncProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "job_type", nullable = false, length = 32)
    private String jobType;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "messages_scanned", nullable = false)
    private Integer messagesScanned = 0;

    @Column(name = "error_message", length = 200)
    private String errorMessage;

    @Column(name = "checkpoint_token", length = 2048)
    private String checkpointToken;

    @Column(name = "pages_completed", nullable = false)
    private Integer pagesCompleted = 0;

    @Column(name = "messages_upserted", nullable = false)
    private Integer messagesUpserted = 0;

    @Column(name = "last_successful_at")
    private Instant lastSuccessfulAt;

    @Column(name = "lane", nullable = false, length = 32)
    private String lane = "LEGACY";

    @Column(name = "phase", nullable = false, length = 32)
    private String phase = "LEGACY";

    @Column(name = "messages_fetched", nullable = false)
    private Long messagesFetched = 0L;

    @Column(name = "messages_changed", nullable = false)
    private Long messagesChanged = 0L;

    @Column(name = "messages_deleted", nullable = false)
    private Long messagesDeleted = 0L;

    @Column(name = "indexed_messages", nullable = false)
    private Long indexedMessages = 0L;

    @Column(name = "provider_total_snapshot")
    private Long providerTotalSnapshot;

    @Column(name = "retry_code", length = 64)
    private String retryCode;

    public SyncProgress() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getMessagesScanned() {
        return messagesScanned;
    }

    public void setMessagesScanned(Integer messagesScanned) {
        this.messagesScanned = messagesScanned;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCheckpointToken() {
        return checkpointToken;
    }

    public void setCheckpointToken(String checkpointToken) {
        this.checkpointToken = checkpointToken;
    }

    public Integer getPagesCompleted() {
        return pagesCompleted;
    }

    public void setPagesCompleted(Integer pagesCompleted) {
        this.pagesCompleted = pagesCompleted;
    }

    public Integer getMessagesUpserted() {
        return messagesUpserted;
    }

    public void setMessagesUpserted(Integer messagesUpserted) {
        this.messagesUpserted = messagesUpserted;
    }

    public Instant getLastSuccessfulAt() {
        return lastSuccessfulAt;
    }

    public void setLastSuccessfulAt(Instant lastSuccessfulAt) {
        this.lastSuccessfulAt = lastSuccessfulAt;
    }

    public String getLane() { return lane; }
    public void setLane(String value) { lane = value; }
    public String getPhase() { return phase; }
    public void setPhase(String value) { phase = value; }
    public Long getMessagesFetched() { return messagesFetched; }
    public void setMessagesFetched(Long value) { messagesFetched = value; }
    public Long getMessagesChanged() { return messagesChanged; }
    public void setMessagesChanged(Long value) { messagesChanged = value; }
    public Long getMessagesDeleted() { return messagesDeleted; }
    public void setMessagesDeleted(Long value) { messagesDeleted = value; }
    public Long getIndexedMessages() { return indexedMessages; }
    public void setIndexedMessages(Long value) { indexedMessages = value; }
    public Long getProviderTotalSnapshot() { return providerTotalSnapshot; }
    public void setProviderTotalSnapshot(Long value) { providerTotalSnapshot = value; }
    public String getRetryCode() { return retryCode; }
    public void setRetryCode(String value) { retryCode = value; }

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (messagesScanned == null) {
            messagesScanned = 0;
        }
        if (pagesCompleted == null) {
            pagesCompleted = 0;
        }
        if (messagesUpserted == null) {
            messagesUpserted = 0;
        }
        if (lane == null) lane = "LEGACY";
        if (phase == null) phase = "LEGACY";
        if (messagesFetched == null) messagesFetched = 0L;
        if (messagesChanged == null) messagesChanged = 0L;
        if (messagesDeleted == null) messagesDeleted = 0L;
        if (indexedMessages == null) indexedMessages = 0L;
    }
}
