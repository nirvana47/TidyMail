package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "sender_stats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_sender_stats_account_id_from_address", columnNames = {"account_id", "from_address"})
    }
)
public class SenderStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "from_address", nullable = false)
    private String fromAddress;

    @Column(name = "from_display_name")
    private String fromDisplayName;

    @Column(name = "from_domain", nullable = false)
    private String fromDomain;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount = 0;

    @Column(name = "unread_count", nullable = false)
    private Integer unreadCount = 0;

    @Column(name = "reply_count", nullable = false)
    private Integer replyCount = 0;

    @Column(name = "has_list_unsubscribe", nullable = false)
    private Boolean hasListUnsubscribe = false;

    @Column(name = "has_precedence_bulk", nullable = false)
    private Boolean hasPrecedenceBulk = false;

    @Column(name = "oldest_message_at")
    private Instant oldestMessageAt;

    @Column(name = "newest_message_at")
    private Instant newestMessageAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    public SenderStat() {
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

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getFromDisplayName() {
        return fromDisplayName;
    }

    public void setFromDisplayName(String fromDisplayName) {
        this.fromDisplayName = fromDisplayName;
    }

    public String getFromDomain() {
        return fromDomain;
    }

    public void setFromDomain(String fromDomain) {
        this.fromDomain = fromDomain;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(Integer unreadCount) {
        this.unreadCount = unreadCount;
    }

    public Integer getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(Integer replyCount) {
        this.replyCount = replyCount;
    }

    public Boolean getHasListUnsubscribe() {
        return hasListUnsubscribe;
    }

    public void setHasListUnsubscribe(Boolean hasListUnsubscribe) {
        this.hasListUnsubscribe = hasListUnsubscribe;
    }

    public Boolean getHasPrecedenceBulk() {
        return hasPrecedenceBulk;
    }

    public void setHasPrecedenceBulk(Boolean hasPrecedenceBulk) {
        this.hasPrecedenceBulk = hasPrecedenceBulk;
    }

    public Instant getOldestMessageAt() {
        return oldestMessageAt;
    }

    public void setOldestMessageAt(Instant oldestMessageAt) {
        this.oldestMessageAt = oldestMessageAt;
    }

    public Instant getNewestMessageAt() {
        return newestMessageAt;
    }

    public void setNewestMessageAt(Instant newestMessageAt) {
        this.newestMessageAt = newestMessageAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
