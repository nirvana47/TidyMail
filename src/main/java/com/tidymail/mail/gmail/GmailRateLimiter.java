package com.tidymail.mail.gmail;

import jakarta.annotation.PostConstruct;
import com.google.api.client.http.HttpHeaders;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import com.tidymail.mail.CleanupProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Throttles outbound Gmail API requests with semaphore-based concurrency
 * limits and an inter-request delay, reserving capacity for freshness
 * operations so backfill/rescan work cannot starve live sync.
 *
 * <p>
 * Product functionality supported: Gmail provider rate limiting.
 */
@Component
public class GmailRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(GmailRateLimiter.class);

    private final GmailSyncConfigProperties config;

    private Semaphore totalCapacity;
    private Semaphore backfillCapacity;
    private int delayMs;
    private long nextMetadataBatchNanos;
    private boolean metadataBatchScheduled;
    private final java.util.function.LongSupplier nanoTime;
    private final java.util.function.LongConsumer sleeper;
    private final ConcurrentHashMap<Long, AccountBudget> budgets = new ConcurrentHashMap<>();
    private final Bucket projectBudget;

    /**
     * Weighted per-account admission shared by cleanup and sync, with background
     * headroom.
     */
    public void charge(Long accountId, int units, boolean backfill) {
        if (units < 1 || units > 500)
            throw new IllegalArgumentException("Gmail reservation must cost 1–500 units");
        if (accountId == null || config.getQuotaUnitsPerMinute() == 0)
            return;
        var budget = budgets.computeIfAbsent(accountId, id -> new AccountBudget(config, nanoTime.getAsLong()));
        for (;;) {
            long wait;
            synchronized (budget) {
                long now = nanoTime.getAsLong();
                long cooldown = budget.cooldownActive ? budget.cooldownUntil - now : 0;
                if (cooldown > 0)
                    throw new CooldownRejection(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(cooldown) + 1));
                synchronized (projectBudget) {
                    wait = Math.max(budget.total.waitNanos(units, now), projectBudget.waitNanos(units, now));
                    if (backfill)
                        wait = Math.max(wait, budget.background.waitNanos(units, now));
                    if (wait <= 0) {
                        budget.total.take(units);
                        projectBudget.take(units);
                        if (backfill)
                            budget.background.take(units);
                        return;
                    }
                }
            }
            // Never sleep holding the budget monitor; foreground requests can use their
            // reservation.
            sleeper.accept(Math.min(1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(wait) + 1)));
        }
    }

    /** Read-only sync may wait through a short cooldown without losing its page.
     * Mutations keep using charge(), so their rejection/reconciliation stays unchanged. */
    public void chargeForSync(Long accountId, int units, boolean backfill) {
        long started = nanoTime.getAsLong();
        long maxWait = TimeUnit.SECONDS.toNanos(60);
        for (;;) {
            try {
                charge(accountId, units, backfill);
                return;
            } catch (CleanupProviderException rejection) {
                AccountBudget budget = budgets.get(accountId);
                if (budget == null || rejection.status() != 429 || !rejection.retryableRejection()) throw rejection;
                long remaining;
                synchronized (budget) {
                    remaining = budget.cooldownActive ? budget.cooldownUntil - nanoTime.getAsLong() : 0;
                }
                if (remaining <= 0) continue;
                if (nanoTime.getAsLong() - started + remaining > maxWait) throw rejection;
                sleeper.accept(Math.min(1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining) + 1)));
            }
        }
    }

    public void recordThrottle(Long accountId, HttpHeaders headers) {
        if (accountId == null || config.getQuotaUnitsPerMinute() == 0)
            return;
        long seconds = 30;
        if (headers != null && headers.getRetryAfter() != null)
            try {
                seconds = Math.max(seconds, Long.parseLong(headers.getRetryAfter()));
            } catch (NumberFormatException error) {
                try {
                    seconds = Math.max(seconds,
                            ZonedDateTime.parse(headers.getRetryAfter(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toEpochSecond() - Instant.now().getEpochSecond());
                } catch (DateTimeParseException ignored) {
                }
            }
        var budget = budgets.computeIfAbsent(accountId, id -> new AccountBudget(config, nanoTime.getAsLong()));
        synchronized (budget) {
            long now = nanoTime.getAsLong();
            long remaining = budget.cooldownActive ? Math.max(0, budget.cooldownUntil - now) : 0;
            budget.cooldownUntil = now + Math.max(remaining, TimeUnit.SECONDS.toNanos(Math.min(seconds, 86400)));
            budget.cooldownActive = true;
        }
    }

    private static final class AccountBudget {
        final Bucket total, background;
        long cooldownUntil;
        boolean cooldownActive;

        AccountBudget(GmailSyncConfigProperties config, long now) {
            total = new Bucket(config.getQuotaUnitsPerMinute(), now);
            background = new Bucket(config.getBackfillQuotaUnitsPerMinute(), now);
        }
    }

    private static final class Bucket {
        final int perMinute;
        final double capacity;
        double tokens;
        long at;

        Bucket(int perMinute, long now) {
            this.perMinute = perMinute;
            capacity = Math.max(500, perMinute / 12.0);
            tokens = capacity;
            at = now;
        }

        long waitNanos(int cost, long now) {
            if (perMinute == 0)
                return 0;
            tokens = Math.min(capacity, tokens + (now - at) * perMinute / 60_000_000_000.0);
            at = now;
            return (long) Math.ceil(Math.max(0, cost - tokens) * 60_000_000_000.0 / perMinute);
        }

        void take(int cost) {
            if (perMinute > 0)
                tokens -= cost;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GmailRateLimiter(GmailSyncConfigProperties config) {
        this(config, System::nanoTime, GmailRateLimiter::sleep);
    }

    public GmailRateLimiter(GmailSyncConfigProperties config, java.util.function.LongSupplier nanoTime,
                            java.util.function.LongConsumer sleeper) {
        this.config = config;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.projectBudget = new Bucket(960000, nanoTime.getAsLong());
    }

    @PostConstruct
    public void init() {
        int concurrency = config.getApiConcurrency();
        this.totalCapacity = new Semaphore(concurrency, true);
        this.backfillCapacity = new Semaphore(
                concurrency - config.getFreshnessReservedCapacity(), true);
        this.delayMs = config.getBatchDelayMs();

        if (concurrency > 10) {
            logger.warn(
                    "Gmail API concurrency set to {} (>10) — risk of Gmail concurrency throttling;"
                            + " prefer 5 or lower unless you know your project quota",
                    concurrency);
        }
    }

    public void acquire() {
        acquireFreshnessPermit();
    }

    public void release() {
        totalCapacity.release();
    }

    /** Pace every metadata HTTP batch, sharing the budget across sync lanes. */
    public synchronized void paceMetadataBatch() {
        long remaining = metadataBatchScheduled ? nextMetadataBatchNanos - nanoTime.getAsLong() : 0;
        if (remaining > 0) {
            sleeper.accept(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
        }
        nextMetadataBatchNanos = nanoTime.getAsLong()
                + TimeUnit.MILLISECONDS.toNanos(config.getMetadataBatchDelayMs());
        metadataBatchScheduled = true;
    }

    /** Bounded exponential backoff with jitter; respect Google's Retry-After. */
    public void pauseBeforeRetry(int retry, HttpHeaders headers) {
        long delay = (1000L << Math.min(retry, 5))
                + ThreadLocalRandom.current().nextLong(251);
        String retryAfter = headers == null ? null : headers.getRetryAfter();
        if (retryAfter != null) {
            try {
                delay = Math.max(delay, Math.multiplyExact(Long.parseLong(retryAfter), 1000));
            } catch (NumberFormatException exception) {
                try {
                    long until = ZonedDateTime.parse(
                            retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - System.currentTimeMillis();
                    delay = Math.max(delay, until);
                } catch (DateTimeParseException ignored) {
                    // Malformed provider headers do not disable bounded retries.
                }
            } catch (ArithmeticException ignored) {
                // Ignore an unrepresentable provider delay.
            }
        }
        sleeper.accept(delay);
    }

    public static final class CooldownRejection extends CleanupProviderException {
        CooldownRejection(long seconds) { super(429, Long.toString(seconds), true); }
    }

    private static void sleep(long milliseconds) {
        try {
            java.lang.Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            java.lang.Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pacing Gmail requests", exception);
        }
    }

    public Permit acquireFreshnessPermit() {
        acquire(totalCapacity, "freshness");
        delayOrRelease(totalCapacity, null);
        return new Permit(totalCapacity, null);
    }

    public Permit acquireBackfillRescanPermit() {
        acquire(backfillCapacity, "backfill/rescan");
        try {
            acquire(totalCapacity, "total Gmail");
        } catch (RuntimeException ex) {
            backfillCapacity.release();
            throw ex;
        }
        delayOrRelease(totalCapacity, backfillCapacity);
        return new Permit(totalCapacity, backfillCapacity);
    }

    /**
     * Cleanup and verification share the background lane, preserving freshness
     * capacity.
     */
    public <T> T supplyWithCleanupPermit(Supplier<T> supplier) {
        return supplyWithBackfillRescanPermit(supplier);
    }

    public <T> T supplyWithPermit(Supplier<T> supplier) {
        try (Permit ignored = acquireFreshnessPermit()) {
            return supplier.get();
        }
    }

    public <T> T supplyWithFreshnessPermit(Supplier<T> supplier) {
        return supplyWithPermit(supplier);
    }

    public <T> T supplyWithBackfillRescanPermit(Supplier<T> supplier) {
        try (Permit ignored = acquireBackfillRescanPermit()) {
            return supplier.get();
        }
    }

    private static void acquire(Semaphore semaphore, String capacity) {
        try {
            semaphore.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted acquiring " + capacity + " request capacity", ex);
        }
    }

    private void delayOrRelease(Semaphore total, Semaphore backfill) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            total.release();
            if (backfill != null) {
                backfill.release();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Gmail request delay", ex);
        }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore total;
        private final Semaphore backfill;
        private boolean closed;

        private Permit(Semaphore total, Semaphore backfill) {
            this.total = total;
            this.backfill = backfill;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            total.release();
            if (backfill != null) {
                backfill.release();
            }
        }
    }
}
