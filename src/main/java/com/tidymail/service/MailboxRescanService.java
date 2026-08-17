package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.PageResult;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.BackfillRequest;
import com.tidymail.mail.sync.MailboxSnapshot;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import com.tidymail.service.MailboxSyncCommitService.RescanRepairUnit;
import com.tidymail.service.MailboxSyncCommitService.SyncUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MailboxRescanService {
    private static final String START = "__START__";

    private final MailboxSyncStateRepository states;
    private final MailboxSyncCommitService commits;
    private final GmailSyncConfigProperties config;

    public MailboxRescanService(
            MailboxSyncStateRepository states,
            MailboxSyncCommitService commits,
            GmailSyncConfigProperties config) {
        this.states = states;
        this.commits = commits;
        this.config = config;
    }

    public SyncOutcome synchronizeRescanPage(
            UserAccount account, MailboxSyncProvider provider, String leaseOwner) {
        StateSnapshot state = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state not found"));
        if ("COMPLETE".equals(state.rescanStatus())) {
            return SyncOutcome.complete();
        }
        if ("REQUIRED".equals(state.rescanStatus())) {
            return repairAndBegin(account, provider, leaseOwner);
        }
        if (!"RUNNING".equals(state.rescanStatus())
                || state.rescanGeneration() == null
                || state.rescanStartedAt() == null) {
            throw new IllegalStateException("Rescan is not ready");
        }

        String pageToken = START.equals(state.rescanAnchor()) ? null : state.rescanAnchor();
        MailboxFreshnessService.assertProviderOutsideTransaction();
        PageResult<MailMessage> page = provider.listBackfill(
            account, new BackfillRequest(pageToken, config.getBackfillPageSize()));
        String nextPageToken = page.nextPageToken();
        StateSnapshot commitState = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        commits.commitRescanUnit(new SyncUnit(
            account.getId(), leaseOwner, commitState.version(),
            page.items(), List.of(), null, nextPageToken, Instant.now(),
            page.items().size(), state.providerTotal(), state.rescanGeneration(),
            state.rescanStartedAt(), nextPageToken == null));
        return nextPageToken == null ? SyncOutcome.complete() : SyncOutcome.requeue();
    }

    private SyncOutcome repairAndBegin(
            UserAccount account, MailboxSyncProvider provider, String leaseOwner) {
        Instant startedAt = Instant.now();
        UUID generation = UUID.randomUUID();
        MailboxFreshnessService.assertProviderOutsideTransaction();
        MailboxSnapshot anchor = provider.profile(account);
        if (provider.capabilities().changeFeedKind()
                != SyncCapabilities.ChangeFeedKind.HISTORY
                || anchor.cursorKind() != SyncCapabilities.ChangeFeedKind.HISTORY
                || anchor.cursor() == null) {
            throw new IllegalStateException("Rescan requires a complete History anchor");
        }
        List<MailMessage> repair =
            provider.listRecentBootstrap(account, config.getRepairLimit());
        StateSnapshot commitState = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        commits.commitRescanRepair(new RescanRepairUnit(
            account.getId(), leaseOwner, commitState.version(), repair,
            MailboxBootstrapService.cursorKind(anchor.cursorKind()),
            anchor.cursor(), generation, startedAt));
        return SyncOutcome.requeue();
    }
}
