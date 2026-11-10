package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.PageResult;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.BackfillRequest;
import com.tidymail.mail.sync.MailboxSnapshot;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.repository.MessageMetadataSyncRepository;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import com.tidymail.service.MailboxSyncCommitService.RescanRepairUnit;
import com.tidymail.service.MailboxSyncCommitService.SyncUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Drives the rescan phase of mailbox sync: repairs the history anchor and begins
 * a full rescan when required, then pages through the provider's messages and
 * commits each rescan unit.
 *
 * <p>Product functionality supported: the metadata-only sync's rescan recovery
 * after history expiration.
 */
@Service
public class MailboxRescanService {
    private static final String START = "__START__";
    private static final String VERIFY_MISSING = "__VERIFY_MISSING__";

    private final MailboxSyncStateRepository states;
    private final MailboxSyncCommitService commits;
    private final GmailSyncConfigProperties config;
    private final MessageMetadataSyncRepository metadata;

    public MailboxRescanService(
            MailboxSyncStateRepository states,
            MailboxSyncCommitService commits,
            GmailSyncConfigProperties config,
            MessageMetadataSyncRepository metadata) {
        this.states = states;
        this.commits = commits;
        this.config = config;
        this.metadata = metadata;
    }

    /** Synchronizes one rescan page, returning whether more work remains. */
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
        if (provider instanceof GmailMailService gmail) {
            return synchronizeGmailRescan(account, gmail, leaseOwner, state, pageToken);
        }
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

    private SyncOutcome synchronizeGmailRescan(UserAccount account, GmailMailService gmail,
            String owner, StateSnapshot state, String token) {
        int size = config.getBackfillPageSize();
        List<String> ids;
        String next;
        boolean verify = VERIFY_MISSING.equals(token);
        if (verify) {
            // Absence from the scoped listing can mean Spam, Trash, or deletion.
            ids = metadata.unverifiedRescanIds(account.getId(), state.rescanGeneration(),
                state.rescanStartedAt(), size);
            next = ids.isEmpty() ? null : VERIFY_MISSING;
        } else {
            var page = GmailInitialSyncService.listPage(gmail, account, token, size, false, false);
            ids = page.ids().stream().distinct().toList();
            next = page.nextPageToken() == null ? VERIFY_MISSING : page.nextPageToken();
        }
        var messages = gmail.hydrateSyncPage(account, ids, false);
        var returned = messages.stream().map(MailMessage::id).collect(java.util.stream.Collectors.toSet());
        var deleted = verify ? ids.stream().filter(id -> !returned.contains(id)).toList() : List.<String>of();
        StateSnapshot commitState = states.findByAccountId(account.getId()).orElseThrow();
        commits.commitRescanUnit(new SyncUnit(
            account.getId(), owner, commitState.version(), messages, deleted, null, next, Instant.now(),
            ids.size(), null, state.rescanGeneration(), state.rescanStartedAt(), false));
        return next == null ? new SyncOutcome(false, true, false) : SyncOutcome.requeue();
    }

    /** Repairs the history anchor and begins a new rescan generation. */
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
        List<MailMessage> repair = provider instanceof GmailMailService
            ? List.of() : provider.listRecentBootstrap(account, config.getRepairLimit());
        StateSnapshot commitState = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        commits.commitRescanRepair(new RescanRepairUnit(
            account.getId(), leaseOwner, commitState.version(), repair,
            MailboxBootstrapService.cursorKind(anchor.cursorKind()),
            anchor.cursor(), generation, startedAt));
        return provider instanceof GmailMailService ? SyncOutcome.bootstrapComplete() : SyncOutcome.requeue();
    }
}
