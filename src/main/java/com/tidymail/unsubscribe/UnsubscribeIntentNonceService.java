package com.tidymail.unsubscribe;

import com.tidymail.entity.UnsubscribeIntentNonce;
import com.tidymail.repository.UnsubscribeIntentNonceRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes short-lived unsubscribe intent nonces, guarding against replay,
 * expiration, and stale tokens, then exactly once per issue.
 * <p>
 * Product functionality supported: the nonce-based intent lifecycle in
 * TidyMail's unsubscribe flow.
 */
@Service
public class UnsubscribeIntentNonceService {
    private final UnsubscribeIntentNonceRepository nonces;

    public UnsubscribeIntentNonceService(UnsubscribeIntentNonceRepository nonces) {
        this.nonces = nonces;
    }

    /**
     * Marks a nonce consumed if it still matches the issued characteristics and
     * has not expired, returning the consumption verdict.
     */
    @Transactional
    public ConsumeResult consume(
            long accountId,
            String nonceFingerprint,
            String candidateFingerprint,
            String destinationFingerprint,
            UnsubscribeMethod method,
            Instant issuedAt,
            Instant expiresAt,
            Instant now) {
        UnsubscribeIntentNonce nonce = nonces.findForConsume(accountId, nonceFingerprint)
            .orElse(null);
        if (nonce == null || nonce.getConsumedAt() != null) {
            return ConsumeResult.REPLAYED;
        }
        if (!nonce.getCandidateFingerprint().equals(candidateFingerprint)
                || !nonce.getDestinationFingerprint().equals(destinationFingerprint)
                || nonce.getMethod() != method
                || !nonce.getIssuedAt().equals(issuedAt)
                || !nonce.getExpiresAt().equals(expiresAt)) {
            return ConsumeResult.STALE;
        }
        if (!now.isBefore(nonce.getExpiresAt())) {
            return ConsumeResult.EXPIRED;
        }
        nonce.consume(now);
        nonces.saveAndFlush(nonce);
        return ConsumeResult.CONSUMED;
    }

    public enum ConsumeResult {
        CONSUMED,
        REPLAYED,
        EXPIRED,
        STALE
    }
}
