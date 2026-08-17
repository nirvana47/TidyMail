package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mailbox_sync_state")
public class MailboxSyncState {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "lifecycle", nullable = false, length = 32)
    private String lifecycle = "NEW";

    @Column(name = "incremental_cursor_kind", length = 32)
    private String incrementalCursorKind;

    @Column(name = "incremental_cursor_value", length = 2048)
    private String incrementalCursorValue;

    @Column(name = "incremental_last_attempt_at")
    private Instant incrementalLastAttemptAt;

    @Column(name = "incremental_last_success_at")
    private Instant incrementalLastSuccessAt;

    @Column(name = "backfill_page_token", length = 2048)
    private String backfillPageToken;

    @Column(name = "backfill_status", nullable = false, length = 32)
    private String backfillStatus = "NOT_STARTED";

    @Column(name = "backfill_started_at")
    private Instant backfillStartedAt;

    @Column(name = "backfill_completed_at")
    private Instant backfillCompletedAt;

    @Column(name = "initial_batch_completed_at")
    private Instant initialBatchCompletedAt;

    @Column(name = "provider_total")
    private Long providerTotal;

    @Column(name = "provider_total_observed_at")
    private Instant providerTotalObservedAt;

    @Column(name = "rescan_status", nullable = false, length = 32)
    private String rescanStatus = "NOT_STARTED";

    @Column(name = "rescan_generation")
    private UUID rescanGeneration;

    @Column(name = "rescan_started_at")
    private Instant rescanStartedAt;

    @Column(name = "rescan_anchor", length = 2048)
    private String rescanAnchor;

    @Column(name = "rescan_completed_at")
    private Instant rescanCompletedAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_detail", length = 200)
    private String errorDetail;

    @Column(name = "freshness_lease_owner", length = 128)
    private String freshnessLeaseOwner;

    @Column(name = "freshness_lease_expires_at")
    private Instant freshnessLeaseExpiresAt;

    @Column(name = "backfill_lease_owner", length = 128)
    private String backfillLeaseOwner;

    @Column(name = "backfill_lease_expires_at")
    private Instant backfillLeaseExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String value) { lifecycle = value; }
    public String getIncrementalCursorKind() { return incrementalCursorKind; }
    public void setIncrementalCursorKind(String value) { incrementalCursorKind = value; }
    public String getIncrementalCursorValue() { return incrementalCursorValue; }
    public void setIncrementalCursorValue(String value) { incrementalCursorValue = value; }
    public Instant getIncrementalLastAttemptAt() { return incrementalLastAttemptAt; }
    public void setIncrementalLastAttemptAt(Instant value) { incrementalLastAttemptAt = value; }
    public Instant getIncrementalLastSuccessAt() { return incrementalLastSuccessAt; }
    public void setIncrementalLastSuccessAt(Instant value) { incrementalLastSuccessAt = value; }
    public String getBackfillPageToken() { return backfillPageToken; }
    public void setBackfillPageToken(String value) { backfillPageToken = value; }
    public String getBackfillStatus() { return backfillStatus; }
    public void setBackfillStatus(String value) { backfillStatus = value; }
    public Instant getBackfillStartedAt() { return backfillStartedAt; }
    public void setBackfillStartedAt(Instant value) { backfillStartedAt = value; }
    public Instant getBackfillCompletedAt() { return backfillCompletedAt; }
    public void setBackfillCompletedAt(Instant value) { backfillCompletedAt = value; }
    public Instant getInitialBatchCompletedAt() { return initialBatchCompletedAt; }
    public void setInitialBatchCompletedAt(Instant value) { initialBatchCompletedAt = value; }
    public Long getProviderTotal() { return providerTotal; }
    public void setProviderTotal(Long value) { providerTotal = value; }
    public Instant getProviderTotalObservedAt() { return providerTotalObservedAt; }
    public void setProviderTotalObservedAt(Instant value) { providerTotalObservedAt = value; }
    public String getRescanStatus() { return rescanStatus; }
    public void setRescanStatus(String value) { rescanStatus = value; }
    public UUID getRescanGeneration() { return rescanGeneration; }
    public void setRescanGeneration(UUID value) { rescanGeneration = value; }
    public Instant getRescanStartedAt() { return rescanStartedAt; }
    public void setRescanStartedAt(Instant value) { rescanStartedAt = value; }
    public String getRescanAnchor() { return rescanAnchor; }
    public void setRescanAnchor(String value) { rescanAnchor = value; }
    public Instant getRescanCompletedAt() { return rescanCompletedAt; }
    public void setRescanCompletedAt(Instant value) { rescanCompletedAt = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String value) { errorDetail = value; }
    public String getFreshnessLeaseOwner() { return freshnessLeaseOwner; }
    public void setFreshnessLeaseOwner(String value) { freshnessLeaseOwner = value; }
    public Instant getFreshnessLeaseExpiresAt() { return freshnessLeaseExpiresAt; }
    public void setFreshnessLeaseExpiresAt(Instant value) { freshnessLeaseExpiresAt = value; }
    public String getBackfillLeaseOwner() { return backfillLeaseOwner; }
    public void setBackfillLeaseOwner(String value) { backfillLeaseOwner = value; }
    public Instant getBackfillLeaseExpiresAt() { return backfillLeaseExpiresAt; }
    public void setBackfillLeaseExpiresAt(Instant value) { backfillLeaseExpiresAt = value; }
    public Long getVersion() { return version; }
}
