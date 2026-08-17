package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sender_category_assignments")
public class SenderCategoryAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "sender_address", nullable = false, length = 320)
    private String senderAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SenderCategoryAssignment() {
    }

    public static SenderCategoryAssignment create(
            long accountId, long categoryId, String senderAddress) {
        if (accountId <= 0 || categoryId <= 0
                || senderAddress == null || senderAddress.isBlank()) {
            throw new IllegalArgumentException("Invalid sender category assignment");
        }
        SenderCategoryAssignment assignment = new SenderCategoryAssignment();
        assignment.accountId = accountId;
        assignment.categoryId = categoryId;
        assignment.senderAddress = senderAddress;
        return assignment;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getCategoryId() { return categoryId; }
    public String getSenderAddress() { return senderAddress; }
    public Instant getCreatedAt() { return createdAt; }

    public void moveTo(long categoryId) {
        if (categoryId <= 0) throw new IllegalArgumentException("Invalid category ID");
        this.categoryId = categoryId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
