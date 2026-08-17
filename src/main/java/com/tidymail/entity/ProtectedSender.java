package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Immutable entity representing a sender address a user has protected from cleanup
 * actions, unique per account and sender address.
 * <p>Product functionality: protected-sender protection — keeps protected senders'
 * messages safe from bulk cleanup operations.
 */
@Entity
@Immutable
@Table(
    name = "protected_senders",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_protected_senders_account_address",
        columnNames = {"account_id", "sender_address"})
)
public class ProtectedSender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "sender_address", nullable = false, length = 320)
    private String senderAddress;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProtectedSender() {
    }

    /**
     * Creates a protected sender, validating the account, normalized sender address,
     * and source before persisting.
     */
    public static ProtectedSender create(
            long accountId, String senderAddress, String source, Instant createdAt) {
        if (accountId <= 0 || senderAddress == null || senderAddress.isBlank()
                || senderAddress.length() > 320
                || !senderAddress.equals(senderAddress.strip().toLowerCase())
                || source == null || source.isBlank() || source.length() > 16
                || createdAt == null) {
            throw new IllegalArgumentException("Invalid protected sender");
        }
        ProtectedSender sender = new ProtectedSender();
        sender.accountId = accountId;
        sender.senderAddress = senderAddress;
        sender.source = source;
        sender.createdAt = createdAt;
        return sender;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
