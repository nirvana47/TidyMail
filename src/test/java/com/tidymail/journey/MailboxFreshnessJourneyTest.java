package com.tidymail.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.PageResult;
import com.tidymail.mail.gmail.GmailClientInitException;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.ChangePage;
import com.tidymail.mail.sync.MailboxSnapshot;
import com.tidymail.mail.sync.SyncCapabilities;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.MailboxBootstrapService;
import com.tidymail.service.MailboxBackfillService;
import com.tidymail.service.MailboxSyncCommitService;
import com.tidymail.service.MailboxFreshnessService;
import com.tidymail.service.MailboxRescanService;
import com.tidymail.service.DecisionRowKey;
import com.tidymail.support.PostgresJourneyTest;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MailboxFreshnessJourneyTest extends PostgresJourneyTest {
    private static final SyncCapabilities GMAIL_CAPABILITIES =
        new SyncCapabilities(SyncCapabilities.ChangeFeedKind.HISTORY, true, true);

    @Autowired MailboxSyncStateRepository states;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired MailboxBootstrapService bootstrap;
    @Autowired MailboxBackfillService backfill;
    @Autowired MailboxFreshnessService freshness;
    @Autowired MailboxRescanService rescan;
    @Autowired MailboxSyncCommitService commits;
    @Autowired GmailSyncConfigProperties config;

    @Test
    void newGmailMessageAppearsWhileHistoricalBackfillIsStillRunning() {
        long accountId = userAccounts.createAccount("fresh@example.test");
        readyForFreshness(accountId, "10");
        acquire(accountId, Lane.FRESHNESS, "freshness-worker");
        when(gmailProvider.capabilities()).thenReturn(GMAIL_CAPABILITIES);
        when(gmailProvider.listChanges(any(), any())).thenReturn(
            new ChangePage(Set.of("new-message"), Set.of(), "11", "11", null, false));
        when(gmailProvider.getCurrentMetadata(any(), eq(List.of("new-message"))))
            .thenReturn(List.of(message("new-message", "Fresh Sender")));

        freshness.synchronizeFreshness(
            accounts.findById(accountId).orElseThrow(), gmailProvider, "freshness-worker");

        assertThat(metadata.findByAccountIdAndProviderMessageIdIn(
            accountId, List.of("new-message"))).hasSize(1);
        assertThat(states.findByAccountId(accountId).orElseThrow().backfillStatus())
            .isEqualTo("RUNNING");
    }

    @Test
    void newUserSeesFirstBatchBeforeFullMailboxBackfillCompletes() throws Exception {
        String email = "bootstrap@example.test";
        long accountId = userAccounts.createAccount(email);
        states.ensureState(accountId, "google");
        acquire(accountId, Lane.BACKFILL, "bootstrap-worker");
        when(gmailProvider.capabilities()).thenReturn(GMAIL_CAPABILITIES);
        when(gmailProvider.profile(any())).thenReturn(
            new MailboxSnapshot("100", SyncCapabilities.ChangeFeedKind.HISTORY, 1_000L));
        when(gmailProvider.listRecentBootstrap(any(), eq(200)))
            .thenReturn(messages(200, "Batch Sender"));

        Instant startedAt = Instant.now();
        bootstrap.bootstrap(
            accounts.findById(accountId).orElseThrow(), gmailProvider, "bootstrap-worker");
        String body = mockMvc.perform(get("/dashboard/api/page")
                .param("scope", "inbox")
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(10));
        var page = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(page.path("summary").path("messages").asInt()).isEqualTo(200);
        assertThat(page.path("groups").get(0).path("messages").asInt()).isEqualTo(200);
        assertThat(page.path("groups").get(0).path("label").asText()).isEqualTo("sender@example.test");
        assertThat(page.path("sync").path("backfill_status").asText()).isEqualTo("RUNNING");
        assertThat(metadata.countByAccountIdIn(List.of(accountId))).isEqualTo(200);
        var state = states.findByAccountId(accountId).orElseThrow();
        assertThat(state.initialBatchCompletedAt()).isNotNull();
        assertThat(state.backfillStatus()).isEqualTo("RUNNING");
        assertThat(state.backfillPageToken()).isNull();

        config.setMaxMessagesPerAccount(202);
        try {
            when(gmailProvider.listBackfill(any(), any())).thenReturn(
                PageResult.of(
                    List.of(message("bootstrap-0", "Batch Sender"),
                        message("bootstrap-1", "Batch Sender")), "next", 1_000),
                PageResult.of(
                    messages("history-", 10, "Historical Sender"), "ignored", 1_000));
            UserAccount account = accounts.findById(accountId).orElseThrow();
            assertThat(backfill.synchronizeBackfillPage(
                account, gmailProvider, "bootstrap-worker").moreWork()).isTrue();
            assertThat(backfill.synchronizeBackfillPage(
                account, gmailProvider, "bootstrap-worker").moreWork()).isFalse();
            assertThat(metadata.countByAccountIdIn(List.of(accountId))).isEqualTo(202);
        } finally {
            config.setMaxMessagesPerAccount(0);
        }
    }

    @Test
    void expiredGmailHistoryTriggersBoundedRepairAndProgressiveRescan() {
        long accountId = userAccounts.createAccount("rescan@example.test");
        readyForFreshness(accountId, "20");
        insertStoredMessage(accountId, "stale-before-rescan", Instant.now().minusSeconds(600));
        acquire(accountId, Lane.FRESHNESS, "freshness-worker");
        when(gmailProvider.capabilities()).thenReturn(GMAIL_CAPABILITIES);
        when(gmailProvider.listChanges(any(), any()))
            .thenThrow(new GmailClientInitException("HISTORY_EXPIRED"));

        var outcome = freshness.synchronizeFreshness(
            accounts.findById(accountId).orElseThrow(), gmailProvider, "freshness-worker");
        assertThat(outcome.queueRescan()).isTrue();
        assertThat(states.findByAccountId(accountId).orElseThrow().incrementalCursorValue())
            .isEqualTo("20");

        acquire(accountId, Lane.RESCAN, "rescan-worker");
        when(gmailProvider.profile(any())).thenReturn(
            new MailboxSnapshot("30", SyncCapabilities.ChangeFeedKind.HISTORY, 3L));
        when(gmailProvider.listRecentBootstrap(any(), eq(100)))
            .thenReturn(List.of(message("repair", "Repair Sender")));
        when(gmailProvider.listBackfill(any(), any()))
            .thenReturn(
                PageResult.of(List.of(message("scan-1", "Scan Sender")), "page-2", 2),
                PageResult.of(List.of(message("scan-2", "Scan Sender")), null, 2));

        UserAccount account = accounts.findById(accountId).orElseThrow();
        assertThat(rescan.synchronizeRescanPage(account, gmailProvider, "rescan-worker").moreWork())
            .isTrue();
        var running = states.findByAccountId(accountId).orElseThrow();
        assertThat(running.incrementalCursorValue()).isEqualTo("30");
        assertThat(running.rescanStatus()).isEqualTo("RUNNING");
        insertStoredMessage(accountId, "post-start-fresh", Instant.now());

        assertThat(rescan.synchronizeRescanPage(account, gmailProvider, "rescan-worker").moreWork())
            .isTrue();
        assertThat(metadata.findByAccountIdAndProviderMessageIdIn(
            accountId, List.of("stale-before-rescan"))).hasSize(1);
        assertThat(rescan.synchronizeRescanPage(account, gmailProvider, "rescan-worker").moreWork())
            .isFalse();

        assertThat(states.findByAccountId(accountId).orElseThrow().rescanStatus())
            .isEqualTo("COMPLETE");
        assertThat(metadata.findByAccountIdAndProviderMessageIdIn(
            accountId, List.of("stale-before-rescan"))).isEmpty();
        assertThat(metadata.findByAccountIdAndProviderMessageIdIn(
            accountId, List.of("post-start-fresh"))).hasSize(1);
    }

    @Test
    void emptyMailboxRemainsEligibleForFreshness() {
        long accountId = userAccounts.createAccount("empty@example.test");
        states.ensureState(accountId, "google");
        acquire(accountId, Lane.BACKFILL, "bootstrap-worker");
        when(gmailProvider.capabilities()).thenReturn(GMAIL_CAPABILITIES);
        when(gmailProvider.profile(any())).thenReturn(
            new MailboxSnapshot("40", SyncCapabilities.ChangeFeedKind.HISTORY, 0L));
        when(gmailProvider.listRecentBootstrap(any(), eq(200))).thenReturn(List.of());

        bootstrap.bootstrap(
            accounts.findById(accountId).orElseThrow(), gmailProvider, "bootstrap-worker");
        when(gmailProvider.listBackfill(any(), any()))
            .thenReturn(PageResult.of(List.of(), null, 0));
        backfill.synchronizeBackfillPage(
            accounts.findById(accountId).orElseThrow(), gmailProvider, "bootstrap-worker");
        acquire(accountId, Lane.FRESHNESS, "freshness-worker");
        when(gmailProvider.listChanges(any(), any())).thenReturn(
            new ChangePage(Set.of(), Set.of(), null, "41", null, false));
        freshness.synchronizeFreshness(
            accounts.findById(accountId).orElseThrow(), gmailProvider, "freshness-worker");

        var state = states.findByAccountId(accountId).orElseThrow();
        assertThat(state.providerTotal()).isZero();
        assertThat(state.initialBatchCompletedAt()).isNotNull();
        assertThat(state.backfillStatus()).isEqualTo("COMPLETE");
        assertThat(state.incrementalCursorValue()).isEqualTo("41");
        assertThat(state.incrementalLastSuccessAt()).isNotNull();
        assertThat(metadata.countByAccountIdIn(List.of(accountId))).isZero();
    }

    @Test
    void gmailMetadataFlowsFromProviderThroughStorageToVisibleWorkspace()
            throws Exception {
        String email = "metadata-flow@example.test";
        long accountId = userAccounts.createAccount(email);
        states.ensureState(accountId, "google");
        acquire(accountId, Lane.BACKFILL, "metadata-flow-worker");

        MailMessage presentUnknown = GmailMailService.toMailMessage(
            "google", gmailMetadata("metadata-present", "Present attachment"));
        MailMessage absentUnknown = GmailMailService.toMailMessage(
            "google", gmailMetadata("metadata-absent", "No attachment"));
        MailMessage checking = GmailMailService.toMailMessage(
            "google", gmailMetadata("metadata-checking", "Checking attachment"));
        Set<String> attachmentMembership = Set.of("metadata-present");
        Set<String> reconciledIds = Set.of("metadata-present", "metadata-absent");
        List<MailMessage> reconciled = List.of(
            withAttachmentState(
                presentUnknown,
                attachmentMembership.contains(presentUnknown.id())
                    ? AttachmentState.PRESENT : AttachmentState.ABSENT),
            withAttachmentState(
                absentUnknown,
                attachmentMembership.contains(absentUnknown.id())
                    ? AttachmentState.PRESENT : AttachmentState.ABSENT),
            checking);
        assertThat(reconciledIds).containsExactlyInAnyOrder(
            presentUnknown.id(), absentUnknown.id());

        var state = states.findByAccountId(accountId).orElseThrow();
        commits.commitBootstrapUnit(new MailboxSyncCommitService.BootstrapUnit(
            accountId,
            "metadata-flow-worker",
            state.version(),
            reconciled,
            "GMAIL_HISTORY_ID",
            "900",
            3L,
            Instant.now()));

        assertThat(jdbc.queryForList("""
            SELECT provider_message_id, unread, labels, size_estimate, attachment_state
              FROM message_metadata
             WHERE account_id = ?
             ORDER BY provider_message_id
            """, accountId))
            .extracting(
                row -> row.get("provider_message_id"),
                row -> row.get("unread"),
                row -> row.get("size_estimate"),
                row -> row.get("attachment_state"))
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    "metadata-absent", true, 4096L, "ABSENT"),
                org.assertj.core.groups.Tuple.tuple(
                    "metadata-checking", true, 4096L, "UNKNOWN"),
                org.assertj.core.groups.Tuple.tuple(
                    "metadata-present", true, 4096L, "PRESENT"));

        String rowKey = DecisionRowKey.sender(accountId, "metadata@example.test");
        String body = mockMvc.perform(get(
                "/unsubscribe/senders/{senderRowKey}/stats", rowKey)
                .param("account", Long.toString(accountId))
                .param("from", "2026-08-14")
                .param("to", "2026-08-14")
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String visible = body.replaceAll("(?s)<[^>]*>", " ")
            .replaceAll("\\s+", " ").trim();

        assertThat(visible).contains(
            "Attachment", "Present", "None", "Checking",
            "Attachment coverage is still being checked; Present and None apply only to reconciled messages.");
        assertThat(visible).doesNotContain("body-derived", "snippet");

        jdbc.update("""
            UPDATE message_metadata
               SET attachment_state = 'ABSENT',
                   has_attachments = FALSE,
                   attachment_observed_at = CURRENT_TIMESTAMP,
                   attachment_source = 'GMAIL_SEARCH'
             WHERE account_id = ? AND provider_message_id = 'metadata-checking'
            """, accountId);
        String reconciledBody = mockMvc.perform(get(
                "/unsubscribe/senders/{senderRowKey}/stats", rowKey)
                .param("account", Long.toString(accountId))
                .param("from", "2026-08-14")
                .param("to", "2026-08-14")
                .with(authenticatedAs(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(reconciledBody)
            .doesNotContain(
                "Attachment coverage is still being checked; Present and None apply only to reconciled messages.");
    }

    private void readyForFreshness(long accountId, String cursor) {
        states.ensureState(accountId, "google");
        jdbc.update("""
            UPDATE mailbox_sync_state
               SET lifecycle = 'BACKFILLING',
                   incremental_cursor_kind = 'GMAIL_HISTORY_ID',
                   incremental_cursor_value = ?,
                   initial_batch_completed_at = CURRENT_TIMESTAMP,
                   backfill_status = 'RUNNING'
             WHERE account_id = ?
            """, cursor, accountId);
    }

    private void acquire(long accountId, Lane lane, String owner) {
        var state = states.findByAccountId(accountId).orElseThrow();
        Instant now = Instant.now();
        assertThat(states.acquireLaneLease(
            accountId, lane, owner, now.plusSeconds(60), now, state.version())).isTrue();
    }

    private void insertStoredMessage(long accountId, String id, Instant lastSyncedAt) {
        jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, received_at,
                first_seen_at, last_synced_at, unread
            ) VALUES (?, 'google', ?, ?, ?, ?, false)
            """, accountId, id, Timestamp.from(lastSyncedAt),
            Timestamp.from(lastSyncedAt), Timestamp.from(lastSyncedAt));
    }

    private static List<MailMessage> messages(int count, String sender) {
        return messages("bootstrap-", count, sender);
    }

    private static List<MailMessage> messages(String prefix, int count, String sender) {
        List<MailMessage> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(message(prefix + index, sender));
        }
        return result;
    }

    private static MailMessage message(String id, String sender) {
        return new MailMessage(
            id, "thread-" + id, "google", Instant.parse("2026-08-14T12:00:00Z"),
            Map.of(
                "From", List.of(sender + " <sender@example.test>"),
                "To", List.of("owner@example.test"),
                "Subject", List.of("Metadata " + id)),
            Set.of("INBOX", "UNREAD"));
    }

    private static Message gmailMetadata(String id, String subject) {
        return new Message()
            .setId(id)
            .setThreadId("thread-" + id)
            .setInternalDate(Instant.parse("2026-08-14T12:00:00Z").toEpochMilli())
            .setHistoryId(new BigInteger("900"))
            .setLabelIds(List.of("INBOX", "UNREAD", "CATEGORY_UPDATES"))
            .setSizeEstimate(4096)
            .setPayload(new MessagePart().setHeaders(List.of(
                new MessagePartHeader().setName("From")
                    .setValue("Metadata Sender <metadata@example.test>"),
                new MessagePartHeader().setName("To")
                    .setValue("metadata-flow@example.test"),
                new MessagePartHeader().setName("Subject").setValue(subject))));
    }

    private static MailMessage withAttachmentState(
            MailMessage message, AttachmentState attachmentState) {
        return new MailMessage(
            message.id(),
            message.threadId(),
            message.provider(),
            message.receivedAt(),
            message.headers(),
            message.labelIds(),
            message.sizeEstimate(),
            message.providerRevision(),
            attachmentState);
    }
}
