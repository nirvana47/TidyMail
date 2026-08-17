package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.MailboxSnapshot;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import com.tidymail.service.MailboxSyncCommitService.BootstrapUnit;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MailboxBootstrapService {
    private final MailboxSyncStateRepository states;
    private final MailboxSyncCommitService commits;
    private final GmailSyncConfigProperties config;

    public MailboxBootstrapService(
            MailboxSyncStateRepository states,
            MailboxSyncCommitService commits,
            GmailSyncConfigProperties config) {
        this.states = states;
        this.commits = commits;
        this.config = config;
    }

    public SyncOutcome bootstrap(
            UserAccount account, MailboxSyncProvider provider, String leaseOwner) {
        StateSnapshot state = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state not found"));
        if (state.initialBatchCompletedAt() != null) {
            return SyncOutcome.requeue();
        }

        MailboxFreshnessService.assertProviderOutsideTransaction();
        MailboxSnapshot anchor = provider.profile(account);
        requireHistoryAnchor(provider, anchor);
        List<MailMessage> recent =
            provider.listRecentBootstrap(account, config.getInitialPageSize());

        StateSnapshot commitState = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        commits.commitBootstrapUnit(new BootstrapUnit(
            account.getId(), leaseOwner, commitState.version(), recent,
            cursorKind(anchor.cursorKind()), anchor.cursor(), anchor.reliableTotal(),
            Instant.now()));
        return SyncOutcome.bootstrapComplete();
    }

    private static void requireHistoryAnchor(
            MailboxSyncProvider provider, MailboxSnapshot anchor) {
        if (provider.capabilities().changeFeedKind()
                != SyncCapabilities.ChangeFeedKind.HISTORY
                || anchor.cursorKind() != SyncCapabilities.ChangeFeedKind.HISTORY
                || anchor.cursor() == null) {
            throw new IllegalStateException("Bootstrap requires a complete History anchor");
        }
    }

    static String cursorKind(SyncCapabilities.ChangeFeedKind kind) {
        return switch (kind) {
            case HISTORY -> "GMAIL_HISTORY_ID";
            case DELTA -> "MICROSOFT_DELTA_LINK";
            case NONE -> throw new IllegalStateException("Provider has no freshness cursor");
        };
    }
}
