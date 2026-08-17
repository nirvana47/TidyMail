package com.tidymail.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MailboxSyncExecutorConfig {

    @Value("${app.sync.freshness-cycle-ms:300000}")
    private long freshnessCycleMs;

    @Value("${app.sync.freshness-executor-threads:2}")
    private int freshnessThreads;

    @Value("${app.sync.freshness-executor-queue-capacity:100}")
    private int freshnessQueueCapacity;

    @Value("${app.sync.backfill-executor-threads:2}")
    private int backfillThreads;

    @Value("${app.sync.backfill-executor-queue-capacity:100}")
    private int backfillQueueCapacity;

    @Value("${app.sync.lane-lease-seconds:60}")
    private long laneLeaseSeconds;

    @PostConstruct
    void validate() {
        if (freshnessCycleMs < 1000) {
            throw new IllegalStateException("SYNC_FRESHNESS_CYCLE_MS must be at least 1000");
        }
        requirePositive(freshnessThreads, "SYNC_FRESHNESS_EXECUTOR_THREADS");
        requirePositive(freshnessQueueCapacity, "SYNC_FRESHNESS_EXECUTOR_QUEUE_CAPACITY");
        requirePositive(backfillThreads, "SYNC_BACKFILL_EXECUTOR_THREADS");
        requirePositive(backfillQueueCapacity, "SYNC_BACKFILL_EXECUTOR_QUEUE_CAPACITY");
        if (laneLeaseSeconds < 10 || laneLeaseSeconds > 3600) {
            throw new IllegalStateException(
                "SYNC_LANE_LEASE_SECONDS must be between 10 and 3600");
        }
    }

    @Bean(name = "freshnessTaskExecutor")
    public ThreadPoolTaskExecutor freshnessTaskExecutor() {
        return executor(
            freshnessThreads, freshnessQueueCapacity, "mailbox-freshness-");
    }

    @Bean(name = "backfillRescanTaskExecutor")
    public ThreadPoolTaskExecutor backfillRescanTaskExecutor() {
        return executor(
            backfillThreads, backfillQueueCapacity, "mailbox-backfill-");
    }

    public Duration laneLeaseDuration() {
        return Duration.ofSeconds(laneLeaseSeconds);
    }

    public long freshnessCycleMs() {
        return freshnessCycleMs;
    }

    private static ThreadPoolTaskExecutor executor(
            int threads, int queueCapacity, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalStateException(name + " must be positive");
        }
    }
}
