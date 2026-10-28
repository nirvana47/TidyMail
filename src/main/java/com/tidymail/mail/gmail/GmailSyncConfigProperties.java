package com.tidymail.mail.gmail;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for Gmail sync operations.  Values are read from the
 * identically-named environment variables (documented in
 * {@code .env.example} section 10 — Sync) with the same defaults the
 * application properties file specifies.
 *
 * <p>Like {@link GoogleOAuthConfigProperties}, this class uses
 * {@code @Component + @Value} rather than {@code @ConfigurationProperties}
 * because the canonical variable names are {@code SYNC_GMAIL_*} (matching
 * the user's shell and {@code .env} file), not the
 * {@code APP_SYNC_GMAIL_*} convention that a
 * {@code @ConfigurationProperties(prefix = "app.sync.gmail")} would require.
 * Using the same variable names everywhere eliminates double-declaration.
 */
/**
 * Holds the Gmail sync tuning parameters read from the environment, and
 * validates them on startup so misconfiguration fails fast.
 *
 * <p>Product functionality supported: Gmail provider sync configuration.
 */
@Component
public class GmailSyncConfigProperties {

    private static final Logger logger =
        LoggerFactory.getLogger(GmailSyncConfigProperties.class);

    @Value("${SYNC_GMAIL_API_CONCURRENCY:5}")
    private int apiConcurrency;

    @Value("${SYNC_GMAIL_API_BATCH_DELAY_MS:100}")
    private int batchDelayMs;

    @Value("${SYNC_GMAIL_METADATA_BATCH_DELAY_MS:1000}")
    private int metadataBatchDelayMs = 1000;

    @Value("${app.sync.initial-page-size:200}")
    private int initialPageSize = 200;

    @Value("${app.sync.gmail-backfill-page-size:500}")
    private int backfillPageSize = 500;

    @Value("${app.sync.max-messages-per-account:0}")
    private long maxMessagesPerAccount;

    @Value("${app.sync.gmail-repair-limit:100}")
    private int repairLimit = 100;

    @Value("${app.sync.gmail-freshness-reserved-capacity:1}")
    private int freshnessReservedCapacity = 1;

    public int getApiConcurrency() {
        return apiConcurrency;
    }

    public void setApiConcurrency(int apiConcurrency) {
        this.apiConcurrency = apiConcurrency;
    }

    public int getBatchDelayMs() {
        return batchDelayMs;
    }

    public void setBatchDelayMs(int batchDelayMs) {
        this.batchDelayMs = batchDelayMs;
    }

    public int getInitialPageSize() {
        return initialPageSize;
    }

    public int getMetadataBatchDelayMs() {
        return metadataBatchDelayMs;
    }

    public void setInitialPageSize(int initialPageSize) {
        this.initialPageSize = initialPageSize;
    }

    public int getBackfillPageSize() {
        return backfillPageSize;
    }

    public void setBackfillPageSize(int backfillPageSize) {
        this.backfillPageSize = backfillPageSize;
    }

    public long getMaxMessagesPerAccount() {
        return maxMessagesPerAccount;
    }

    public void setMaxMessagesPerAccount(long maxMessagesPerAccount) {
        this.maxMessagesPerAccount = maxMessagesPerAccount;
    }

    public boolean isMessageLimitUnlimited() {
        return maxMessagesPerAccount == 0;
    }

    public int getRepairLimit() {
        return repairLimit;
    }

    public void setRepairLimit(int repairLimit) {
        this.repairLimit = repairLimit;
    }

    public int getFreshnessReservedCapacity() {
        return freshnessReservedCapacity;
    }

    public void setFreshnessReservedCapacity(int freshnessReservedCapacity) {
        this.freshnessReservedCapacity = freshnessReservedCapacity;
    }

    @PostConstruct
    public void validate() {
        if (apiConcurrency < 1) {
            throw new IllegalStateException(
                "SYNC_GMAIL_API_CONCURRENCY must be >= 1; got " + apiConcurrency);
        }
        if (apiConcurrency > 20) {
            throw new IllegalStateException(
                "SYNC_GMAIL_API_CONCURRENCY max is 20; got " + apiConcurrency
                    + " — risk self-DoS Gmail quota");
        }
        if (batchDelayMs < 0) {
            throw new IllegalStateException(
                "SYNC_GMAIL_API_BATCH_DELAY_MS must be >= 0; got " + batchDelayMs);
        }
        if (metadataBatchDelayMs < 0 || metadataBatchDelayMs > 60000) {
            throw new IllegalStateException(
                "SYNC_GMAIL_METADATA_BATCH_DELAY_MS must be between 0 and 60000");
        }
        if (initialPageSize < 100 || initialPageSize > 250) {
            throw new IllegalStateException(
                "SYNC_INITIAL_PAGE_SIZE must be between 100 and 250; got " + initialPageSize);
        }
        if (backfillPageSize < 1 || backfillPageSize > 500) {
            throw new IllegalStateException(
                "SYNC_GMAIL_BACKFILL_PAGE_SIZE must be between 1 and 500; got "
                    + backfillPageSize);
        }
        if (maxMessagesPerAccount < 0) {
            throw new IllegalStateException(
                "SYNC_MAX_MESSAGES_PER_ACCOUNT must be zero (unlimited) or positive; got "
                    + maxMessagesPerAccount);
        }
        if (repairLimit < 1) {
            throw new IllegalStateException(
                "SYNC_GMAIL_REPAIR_LIMIT must be positive; got " + repairLimit);
        }
        if (freshnessReservedCapacity < 1
                || freshnessReservedCapacity >= apiConcurrency) {
            throw new IllegalStateException(
                "SYNC_GMAIL_FRESHNESS_RESERVED_CAPACITY must be >= 1 and below "
                    + "SYNC_GMAIL_API_CONCURRENCY; got " + freshnessReservedCapacity);
        }
        if (apiConcurrency > 10) {
            logger.warn(
                "SYNC_GMAIL_API_CONCURRENCY set to {} (>10) — risk 429 from Google's "
                    + "250 reqs/sec/project quota; prefer 5 or lower unless you know "
                    + "your project quota.  SYNC_GMAIL_API_BATCH_DELAY_MS={}",
                apiConcurrency, batchDelayMs);
        }
    }
}
