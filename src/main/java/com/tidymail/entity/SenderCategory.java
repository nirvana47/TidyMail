package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sender_categories")
public class SenderCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "name_key", nullable = false, length = 80)
    private String nameKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SenderCategory() {
    }

    public static SenderCategory create(long accountId, String name, String nameKey) {
        if (accountId <= 0 || name == null || nameKey == null) {
            throw new IllegalArgumentException("Invalid sender category");
        }
        SenderCategory category = new SenderCategory();
        category.accountId = accountId;
        category.name = name;
        category.nameKey = nameKey;
        return category;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void rename(String name, String nameKey) {
        this.name = name;
        this.nameKey = nameKey;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
