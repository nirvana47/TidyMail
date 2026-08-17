package com.tidymail.service;

import com.tidymail.entity.SenderTypeOverride;
import com.tidymail.entity.SenderTypeOverride.SenderType;
import com.tidymail.repository.SenderTypeOverrideRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.util.Rfc5322Util;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages user-provided sender-type overrides that correct the inferred sender
 * type, used by the decision rules when classifying senders.
 *
 * <p>Product functionality supported: the <em>Senders</em> flow's manual sender
 * type corrections.
 */
@Service
public class SenderTypeOverrideService {

    private final UserAccountRepository accounts;
    private final SenderTypeOverrideRepository overrides;

    public SenderTypeOverrideService(
            UserAccountRepository accounts,
            SenderTypeOverrideRepository overrides) {
        this.accounts = accounts;
        this.overrides = overrides;
    }

    /** Finds the override for a sender, if any. */
    @Transactional(readOnly = true)
    public Optional<SenderTypeOverride> find(
            String ownerKey, long accountId, String rawSenderAddress) {
        requireOwnedAccount(ownerKey, accountId);
        return overrides.findByAccountIdAndSenderAddress(
            accountId, normalizeSenderAddress(rawSenderAddress));
    }

    /** Creates or updates the sender-type override. */
    @Transactional
    public SenderTypeOverride upsert(
            String ownerKey,
            long accountId,
            String rawSenderAddress,
            SenderType senderType) {
        requireOwnedAccount(ownerKey, accountId);
        if (senderType == null) {
            throw new IllegalArgumentException("Sender type is required");
        }
        String senderAddress = normalizeSenderAddress(rawSenderAddress);
        SenderTypeOverride override = overrides
            .findByAccountIdAndSenderAddress(accountId, senderAddress)
            .orElseGet(() -> SenderTypeOverride.create(
                accountId, senderAddress, senderType));
        override.changeType(senderType);
        return overrides.save(override);
    }

    /** Removes the sender-type override. */
    @Transactional
    public void remove(String ownerKey, long accountId, String rawSenderAddress) {
        requireOwnedAccount(ownerKey, accountId);
        overrides.deleteByAccountIdAndSenderAddress(
            accountId, normalizeSenderAddress(rawSenderAddress));
    }

    /** Requires the account to be owned by the given owner. */
    private void requireOwnedAccount(String ownerKey, long accountId) {
        if (ownerKey == null || ownerKey.isBlank() || accountId <= 0
                || accounts.findByIdAndOwnerKey(accountId, ownerKey).isEmpty()) {
            throw new IllegalArgumentException("Account is not owned");
        }
    }

    /** Normalizes and validates a sender address. */
    private static String normalizeSenderAddress(String rawAddress) {
        String normalized = Rfc5322Util.extractAddrSpec(rawAddress);
        if (normalized == null || normalized.length() > 320
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid sender address");
        }
        return normalized;
    }
}
