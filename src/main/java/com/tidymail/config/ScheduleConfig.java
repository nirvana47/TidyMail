package com.tidymail.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration that enables Spring scheduling.
 *
 * <p>Product functionality supported: scheduled background jobs — activates the
 * scheduling machinery that drives the incremental mailbox sync, sender-stats
 * vacuum, and user-action cleanup jobs, and can be switched off via the
 * {@code spring.task.scheduling.enabled} property.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ScheduleConfig {
}
