package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.gmail.GmailClientInitException;
import com.tidymail.mail.sync.ChangePage;
import com.tidymail.mail.sync.ChangeRequest;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.service.MailboxSyncCommitService.NoChangeUnit;
import com.tidymail.service.MailboxSyncCommitService.SyncUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Synchronizes incremental mailbox changes (new/deleted messages) from the
 * provider's change feed, committing freshness units and handling history
 * expiration by triggering a rescan.
 *
 * <p>Product functionality supported: the metadata-only sync's incremental
 * freshness updates.
 */
@Service
public class MailboxFreshnessService {
    private static final int MAX_CHANGE_IDS_PER_UNIT = 500;

    private final MailboxSyncStateRepository states;
    private final MailboxSyncCommitService commits;

    public MailboxFreshnessService(
            MailboxSyncStateRepository states,
            MailboxSyncCommitService commits) {
        this.states = states;
        this.commits = commits;
    }

    /** Synchronizes one freshness pass, returning the sync outcome. */
    public SyncOutcome synchronizeFreshness(
            UserAccount account, MailboxSyncProvider provider, String leaseOwner) {
        StateSnapshot state = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state not found"));
        if (state.incrementalCursorValue() == null) {
            return SyncOutcome.complete();
        }
        SyncCapabilities.ChangeFeedKind cursorKind =
            parseCursorKind(state.incrementalCursorKind());
        if (provider.capabilities().changeFeedKind() != cursorKind) {
            throw new IllegalStateException("Stored cursor kind is not supported by provider");
        }

        assertProviderOutsideTransaction();
        Set<String> changed = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();
        String pageToken = null;
        String returnedCursor = state.incrementalCursorValue();
        long fetched = 0;
        try {
            do {
                ChangePage page = provider.listChanges(account, new ChangeRequest(
                    state.incrementalCursorValue(), cursorKind, pageToken,
                    MAX_CHANGE_IDS_PER_UNIT));
                changed.addAll(page.changedIds());
                deleted.addAll(page.deletedIds());
                returnedCursor = page.returnedCursor();
                pageToken = page.nextPageToken();
                fetched += page.changedIds().size() + page.deletedIds().size();
                if (changed.size() + deleted.size() > MAX_CHANGE_IDS_PER_UNIT) {
                    throw new IllegalStateException(
                        "Freshness unit exceeded its bounded change limit");
                }
            } while (pageToken != null);
        } catch (GmailClientInitException exception) {
            if (!"HISTORY_EXPIRED".equals(exception.getMessage())) {
                throw exception;
            }
            StateSnapshot expired = states.findByAccountId(account.getId())
                .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
            if (!states.markRescanRequired(
                    account.getId(), leaseOwner, expired.version(), Instant.now())) {
                throw new IllegalStateException("Mailbox state changed before rescan transition");
            }
            return SyncOutcome.rescan();
        }

        List<MailMessage> current = changed.isEmpty()
            ? List.of() : provider.getCurrentMetadata(account, List.copyOf(changed));
        Set<String> returnedIds = new LinkedHashSet<>();
        current.forEach(message -> returnedIds.add(message.id()));
        changed.stream()
            .filter(id -> !returnedIds.contains(id))
            .forEach(deleted::add);

        Instant completedAt = Instant.now();
        StateSnapshot commitState = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        if (current.isEmpty() && deleted.isEmpty()) {
            commits.commitNoChangeSuccess(new NoChangeUnit(
                account.getId(), leaseOwner, commitState.version(),
                state.incrementalCursorKind(), returnedCursor,
                completedAt, state.providerTotal()));
        } else {
            commits.commitFreshnessUnit(new SyncUnit(
                account.getId(), leaseOwner, commitState.version(),
                new ArrayList<>(current), new ArrayList<>(deleted),
                state.incrementalCursorKind(), returnedCursor,
                completedAt, fetched, state.providerTotal(), null, null, false));
        }
        return SyncOutcome.complete();
    }

    /** Maps a stored cursor-kind label to its change-feed kind. */
    private static SyncCapabilities.ChangeFeedKind parseCursorKind(String cursorKind) {
        return switch (cursorKind) {
            case "GMAIL_HISTORY_ID" -> SyncCapabilities.ChangeFeedKind.HISTORY;
            case "MICROSOFT_DELTA_LINK" -> SyncCapabilities.ChangeFeedKind.DELTA;
            default -> throw new IllegalStateException("Unsupported stored cursor kind");
        };
    }

    /** Requires provider work to run outside a transaction. */
    static void assertProviderOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider work must run outside a transaction");
        }
    }

    /** The outcome of a sync pass: whether more work, freshness, or rescan is queued. */
    public record SyncOutcome(
            boolean moreWork, boolean queueFreshness, boolean queueRescan) {
        public static SyncOutcome complete() {
            return new SyncOutcome(false, false, false);
        }

        public static SyncOutcome requeue() {
            return new SyncOutcome(true, false, false);
        }

        public static SyncOutcome bootstrapComplete() {
            return new SyncOutcome(true, true, false);
        }

        public static SyncOutcome rescan() {
            return new SyncOutcome(false, false, true);
        }
    }
}
