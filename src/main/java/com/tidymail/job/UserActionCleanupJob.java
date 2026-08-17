package com.tidymail.job;

import com.tidymail.repository.UserActionRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that cleans up expired, unresolved user actions.
 *
 * <p>Product functionality supported: sender cleanup actions — hourly purges
 * user actions that have passed their expiry without being undone, so expired
 * cleanup decisions are removed from the system.
 */
@Component
public class UserActionCleanupJob {

    private final UserActionRepository userActionRepository;

    public UserActionCleanupJob(UserActionRepository userActionRepository) {
        this.userActionRepository = userActionRepository;
    }

    /** Deletes all expired, not-yet-undone user actions. */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void run() {
        userActionRepository.deleteAll(
            userActionRepository.findAll().stream()
                .filter(row -> (row.getExpiresAt() != null && row.getExpiresAt().isBefore(Instant.now()))
                    && row.getUndoneAt() == null)
                .toList()
        );
    }
}
