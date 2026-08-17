package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import org.springframework.stereotype.Service;

@Service
public class MessageMetadataSyncService {
    public static final String JOB_TYPE = "MAILBOX_SYNC";

    private final MailboxSyncStateRepository states;
    private final MailboxBootstrapService bootstrap;
    private final MailboxFreshnessService freshness;
    private final MailboxBackfillService backfill;
    private final MailboxRescanService rescan;

    public MessageMetadataSyncService(
            MailboxSyncStateRepository states,
            MailboxBootstrapService bootstrap,
            MailboxFreshnessService freshness,
            MailboxBackfillService backfill,
            MailboxRescanService rescan) {
        this.states = states;
        this.bootstrap = bootstrap;
        this.freshness = freshness;
        this.backfill = backfill;
        this.rescan = rescan;
    }

    public SyncOutcome synchronize(
            UserAccount account,
            MailboxSyncProvider provider,
            String leaseOwner,
            Lane lane) {
        return switch (lane) {
            case FRESHNESS -> freshness.synchronizeFreshness(account, provider, leaseOwner);
            case BACKFILL -> synchronizeBootstrapOrBackfill(account, provider, leaseOwner);
            case RESCAN -> rescan.synchronizeRescanPage(account, provider, leaseOwner);
        };
    }

    private SyncOutcome synchronizeBootstrapOrBackfill(
            UserAccount account, MailboxSyncProvider provider, String leaseOwner) {
        StateSnapshot state = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state not found"));
        return state.initialBatchCompletedAt() == null
            ? bootstrap.bootstrap(account, provider, leaseOwner)
            : backfill.synchronizeBackfillPage(account, provider, leaseOwner);
    }
}
