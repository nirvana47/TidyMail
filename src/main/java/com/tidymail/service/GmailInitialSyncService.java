package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.GmailClientInitException;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.SyncCapabilities.ChangeFeedKind;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.repository.MessageMetadataSyncRepository;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GmailInitialSyncService {
    private final GmailSyncConfigProperties config;
    private final MessageMetadataSyncRepository metadata;
    private final MailboxSyncCommitService commits;

    public GmailInitialSyncService(GmailSyncConfigProperties config,
            MessageMetadataSyncRepository metadata, MailboxSyncCommitService commits) {
        this.config = config;
        this.metadata = metadata;
        this.commits = commits;
    }

    SyncOutcome synchronizePage(UserAccount account, GmailMailService gmail, String owner, StateSnapshot state) {
        if ("COMPLETE".equals(state.gmailBackfillPhase())) return SyncOutcome.complete();
        MailboxFreshnessService.assertProviderOutsideTransaction();
        boolean bootstrap = state.initialBatchCompletedAt() == null || state.incrementalCursorValue() == null;
        boolean inbox = "INBOX".equals(state.gmailBackfillPhase());
        String historyId = null;
        if (state.incrementalCursorValue() == null) {
            var anchor = gmail.profile(account);
            if (anchor.cursorKind() != ChangeFeedKind.HISTORY || anchor.cursor() == null) {
                throw new IllegalStateException("Gmail bootstrap requires a History anchor");
            }
            historyId = anchor.cursor();
        }
        int size = bootstrap ? config.getInitialPageSize() : config.getBackfillPageSize();
        var page = listPage(gmail, account, state.backfillPageToken(), size, inbox, bootstrap);
        List<String> ids = page.ids().stream().distinct().toList();
        var hydrate = metadata.gmailIdsToHydrate(account.getId(), ids);
        var messages = gmail.hydrateSyncPage(account, hydrate, bootstrap);
        String phase = state.gmailBackfillPhase();
        if (page.nextPageToken() == null) phase = inbox ? "ALL_MAIL" : "COMPLETE";
        // Only the unlabelled, Spam/Trash-excluding listing estimates the full scope.
        Long estimate = inbox ? null : page.estimate();
        commits.commitGmailBackfillPage(account.getId(), owner, phase, page.nextPageToken(),
            estimate, historyId, ids, messages, hydrate.size());
        if (bootstrap) return SyncOutcome.bootstrapComplete();
        return "COMPLETE".equals(phase) ? SyncOutcome.complete() : SyncOutcome.requeue();
    }

    static GmailMailService.IdPage listPage(GmailMailService gmail, UserAccount account, String token,
            int size, boolean inbox, boolean bootstrap) {
        try {
            return gmail.listSyncIds(account, token, size, inbox, bootstrap);
        } catch (GmailClientInitException failure) {
            if (!"GMAIL_LIST_CURSOR_INVALID".equals(failure.getMessage())) throw failure;
            // A provider cursor can expire between attempts. Restart this listing with
            // the durable set of already processed IDs and the History anchor intact.
            return gmail.listSyncIds(account, null, size, inbox, bootstrap);
        }
    }
}
