package com.tidymail.service;

import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.PageResult;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.BackfillRequest;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.service.MailboxFreshnessService.SyncOutcome;
import com.tidymail.service.MailboxSyncCommitService.SyncUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MailboxBackfillService {
    private final MailboxSyncStateRepository states;
    private final MailboxSyncCommitService commits;
    private final MessageMetadataRepository metadata;
    private final GmailSyncConfigProperties config;

    public MailboxBackfillService(
            MailboxSyncStateRepository states,
            MailboxSyncCommitService commits,
            MessageMetadataRepository metadata,
            GmailSyncConfigProperties config) {
        this.states = states;
        this.commits = commits;
        this.metadata = metadata;
        this.config = config;
    }

    public SyncOutcome synchronizeBackfillPage(
            UserAccount account, MailboxSyncProvider provider, String leaseOwner) {
        StateSnapshot state = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state not found"));
        if ("COMPLETE".equals(state.backfillStatus())) {
            return SyncOutcome.complete();
        }

        long indexed = metadata.countByAccountIdIn(List.of(account.getId()));
        long ceiling = config.getMaxMessagesPerAccount();
        if (ceiling > 0 && indexed >= ceiling) {
            commitPage(account, leaseOwner, state, List.of(), null, 0);
            return SyncOutcome.complete();
        }
        int pageSize = config.getBackfillPageSize();
        if (ceiling > 0) {
            pageSize = (int) Math.min(pageSize, ceiling - indexed);
        }

        MailboxFreshnessService.assertProviderOutsideTransaction();
        PageResult<MailMessage> page = provider.listBackfill(
            account, new BackfillRequest(state.backfillPageToken(), pageSize));
        PageSelection selection =
            selectMessages(account.getId(), page.items(), ceiling, indexed);
        List<MailMessage> messages = selection.messages();
        String nextPageToken = page.nextPageToken();
        if (ceiling > 0 && indexed + selection.newMessages() >= ceiling) {
            nextPageToken = null;
        }
        commitPage(
            account, leaseOwner, state, messages, nextPageToken, page.items().size());
        return nextPageToken == null ? SyncOutcome.complete() : SyncOutcome.requeue();
    }

    private void commitPage(
            UserAccount account,
            String leaseOwner,
            StateSnapshot fetchedState,
            List<MailMessage> messages,
            String nextPageToken,
            long fetched) {
        StateSnapshot commitState = states.findByAccountId(account.getId())
            .orElseThrow(() -> new IllegalStateException("Mailbox sync state disappeared"));
        commits.commitBackfillUnit(new SyncUnit(
            account.getId(), leaseOwner, commitState.version(),
            messages, List.of(), null, nextPageToken, Instant.now(),
            fetched, fetchedState.providerTotal(), null, null, false));
    }

    private PageSelection selectMessages(
            long accountId, List<MailMessage> page, long ceiling, long indexed) {
        Map<String, MailMessage> unique = new LinkedHashMap<>();
        page.forEach(message -> unique.putIfAbsent(message.id(), message));
        Set<String> existing = metadata.findByAccountIdAndProviderMessageIdIn(
                accountId, unique.keySet())
            .stream()
            .map(row -> row.getProviderMessageId())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long remaining = ceiling > 0 ? ceiling - indexed : Long.MAX_VALUE;
        long newMessages = 0;
        List<MailMessage> selected = new ArrayList<>(unique.size());
        for (MailMessage message : unique.values()) {
            if (existing.contains(message.id())) {
                selected.add(message);
            } else if (newMessages < remaining) {
                selected.add(message);
                newMessages++;
            }
        }
        return new PageSelection(List.copyOf(selected), newMessages);
    }

    private record PageSelection(List<MailMessage> messages, long newMessages) {
    }
}
