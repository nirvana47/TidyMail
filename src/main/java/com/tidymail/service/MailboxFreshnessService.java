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
        String pageToken=null;
        String durableCursor=state.incrementalCursorValue();
        Set<String> visitedPages=new java.util.HashSet<>();
        try {
            do {
                ChangePage page=provider.listChanges(account,new ChangeRequest(
                    state.incrementalCursorValue(),cursorKind,pageToken,MAX_CHANGE_IDS_PER_UNIT));
                // A history record may contain thousands of message IDs after bulk cleanup.
                // Bound DB commits and hydration, not the total number of IDs in that record.
                Set<String> touched=new LinkedHashSet<>(page.changedIds());touched.addAll(page.deletedIds());
                List<String> ids=new ArrayList<>(touched);
                String finishedCursor=page.hasMore()
                    ?page.lastCompletelyAppliedRevision()==null?durableCursor:page.lastCompletelyAppliedRevision()
                    :page.returnedCursor();
                if(ids.isEmpty())commitChanges(account,leaseOwner,List.of(),Set.of(),finishedCursor,0);
                for(int start=0;start<ids.size();start+=MAX_CHANGE_IDS_PER_UNIT) {
                    List<String> chunk=ids.subList(start,Math.min(start+MAX_CHANGE_IDS_PER_UNIT,ids.size()));
                    List<String> changed=chunk.stream().filter(page.changedIds()::contains).toList();
                    List<MailMessage> current=changed.isEmpty()?List.of():provider.getCurrentMetadata(account,changed);
                    Set<String> returned=new java.util.HashSet<>();current.forEach(message->returned.add(message.id()));
                    Set<String> deleted=new LinkedHashSet<>();
                    for(String id:chunk)if(page.deletedIds().contains(id)||!returned.contains(id))deleted.add(id);
                    deleted.removeAll(returned);
                    // Keep the previous cursor until every target in the history page is committed.
                    // A crash may re-read committed chunks but cannot skip the uncommitted suffix.
                    boolean last=start+MAX_CHANGE_IDS_PER_UNIT>=ids.size();
                    commitChanges(account,leaseOwner,current,deleted,last?finishedCursor:durableCursor,chunk.size());
                }
                durableCursor=finishedCursor;
                if(page.hasMore()&&page.lastCompletelyAppliedRevision()!=null
                        &&!durableCursor.equals(state.incrementalCursorValue()))return SyncOutcome.requeue();
                pageToken=page.nextPageToken();
                if(pageToken!=null&&!visitedPages.add(pageToken))throw new IllegalStateException("History continuation did not advance");
            } while(pageToken!=null);
            return SyncOutcome.complete();
        } catch(GmailClientInitException exception) {
            if(!"HISTORY_EXPIRED".equals(exception.getMessage()))throw exception;
            StateSnapshot expired=states.findByAccountId(account.getId()).orElseThrow();
            if(!states.markRescanRequired(account.getId(),leaseOwner,expired.version(),Instant.now()))
                throw new IllegalStateException("Mailbox state changed before rescan transition");
            return SyncOutcome.rescan();
        }
    }

    private void commitChanges(UserAccount account,String leaseOwner,List<MailMessage> current,
            Set<String> deleted,String cursor,long fetched) {
        Instant completedAt=Instant.now();
        StateSnapshot state=states.findByAccountId(account.getId()).orElseThrow();
        if(current.isEmpty()&&deleted.isEmpty()) {
            commits.commitNoChangeSuccess(new NoChangeUnit(account.getId(),leaseOwner,state.version(),
                state.incrementalCursorKind(),cursor,completedAt,state.providerTotal()));
        } else {
            commits.commitFreshnessUnit(new SyncUnit(account.getId(),leaseOwner,state.version(),
                current,new ArrayList<>(deleted),state.incrementalCursorKind(),cursor,
                completedAt,fetched,state.providerTotal(),null,null,false));
        }
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
