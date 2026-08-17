package com.tidymail.job;

import com.tidymail.entity.UserAccount;
import com.tidymail.repository.SenderStatRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that vacuums per-account sender statistics for stale accounts.
 *
 * <p>Product functionality supported: inbox cleanup data retention — nightly purges
 * sender stats, user actions, and sync progress for accounts that have not
 * logged in within 90 days, keeping derived cleanup data bounded.
 */
@Component
public class SenderStatsVacuumJob {

    private final UserAccountRepository userAccountRepository;
    private final SenderStatRepository senderStatRepository;
    private final UserActionRepository userActionRepository;
    private final SyncProgressRepository syncProgressRepository;

    public SenderStatsVacuumJob(UserAccountRepository userAccountRepository,
                                SenderStatRepository senderStatRepository,
                                UserActionRepository userActionRepository,
                                SyncProgressRepository syncProgressRepository) {
        this.userAccountRepository = userAccountRepository;
        this.senderStatRepository = senderStatRepository;
        this.userActionRepository = userActionRepository;
        this.syncProgressRepository = syncProgressRepository;
    }

    /** Deletes sender-stats, user-action, and sync-progress rows for inactive accounts. */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void run() {
        Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
        List<Long> staleIds = userAccountRepository.findAll().stream()
            .filter(a -> a.getLastLoginAt() != null && a.getLastLoginAt().isBefore(ninetyDaysAgo))
            .map(UserAccount::getId)
            .toList();
        senderStatRepository.deleteByAccountIdIn(staleIds);
        userActionRepository.deleteByAccountIdIn(staleIds);
        syncProgressRepository.deleteByAccountIdIn(staleIds);
    }
}
