package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryLabelAdded;
import com.google.api.services.gmail.model.HistoryLabelRemoved;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.HistoryMessageDeleted;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.Profile;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.PageResult;
import com.tidymail.mail.gmail.GmailClientFactory;
import com.tidymail.mail.gmail.GmailClientInitException;
import com.tidymail.mail.gmail.GmailMailService;
import com.tidymail.mail.gmail.GmailRateLimiter;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.mail.sync.AttachmentMembershipPage;
import com.tidymail.mail.sync.BackfillRequest;
import com.tidymail.mail.sync.ChangePage;
import com.tidymail.mail.sync.ChangeRequest;
import com.tidymail.mail.sync.MailboxSnapshot;
import com.tidymail.mail.sync.SyncCapabilities;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GmailSynchronizationContractTest {

    @Test
    @DisplayName("providerProfilesBootstrapsAndBackfillsMetadataOnly")
    void providerProfilesBootstrapsAndBackfillsMetadataOnly() throws Exception {
        Fixture fixture = fixture();
        when(fixture.profileRequest.execute()).thenReturn(
            new Profile().setHistoryId(new BigInteger("9223372036854775808123"))
                .setMessagesTotal(321));

        MailboxSnapshot profile = fixture.service.profile(fixture.account);
        assertThat(profile.cursor()).isEqualTo("9223372036854775808123");
        assertThat(profile.cursorKind()).isEqualTo(SyncCapabilities.ChangeFeedKind.HISTORY);
        assertThat(profile.reliableTotal()).isEqualTo(321L);

        List<Message> recentIds = java.util.stream.IntStream.rangeClosed(1, 11)
            .mapToObj(index -> new Message().setId("recent-" + index))
            .toList();
        when(fixture.listRequest.execute()).thenReturn(
            new ListMessagesResponse().setMessages(recentIds));
        List<MailMessage> recent = fixture.service.listRecentBootstrap(fixture.account, 200);
        assertThat(recent).hasSize(11);
        verify(fixture.listRequest).setMaxResults(200L);
        verify(fixture.listRequest, never()).setIncludeSpamTrash(true);
        verify(fixture.gmail, times(1)).batch();

        when(fixture.listRequest.execute()).thenReturn(
            new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("old-1")))
                .setNextPageToken("backfill-next"));
        PageResult<MailMessage> backfill = fixture.service.listBackfill(
            fixture.account, new BackfillRequest("backfill-page", 500));
        assertThat(backfill.items()).extracting(MailMessage::id).containsExactly("old-1");
        assertThat(backfill.nextPageToken()).isEqualTo("backfill-next");
        verify(fixture.listRequest).setIncludeSpamTrash(true);
        verify(fixture.listRequest).setPageToken("backfill-page");
        assertThat(fixture.metadataFormats).containsOnly("METADATA");
        assertThat(fixture.metadataHeaders)
            .allSatisfy(headers -> assertThat(headers)
                .containsExactlyInAnyOrderElementsOf(GmailMailService.METADATA_HEADERS));
    }

    @Test
    @DisplayName("providerConsumesOnlySpecificUnfilteredHistoryEventsAcrossPages")
    void providerConsumesOnlySpecificUnfilteredHistoryEventsAcrossPages() throws Exception {
        Fixture fixture = fixture();
        History first = new History()
            .setId(new BigInteger("101"))
            .setMessages(List.of(new Message().setId("generic-must-be-ignored")))
            .setMessagesAdded(List.of(
                new HistoryMessageAdded().setMessage(new Message().setId("added"))))
            .setLabelsAdded(List.of(
                new HistoryLabelAdded().setMessage(new Message().setId("relabeled"))))
            .setLabelsRemoved(List.of(
                new HistoryLabelRemoved().setMessage(new Message().setId("unlabeled"))));
        History second = new History()
            .setId(new BigInteger("102"))
            .setMessagesDeleted(List.of(
                new HistoryMessageDeleted().setMessage(new Message().setId("deleted"))));
        when(fixture.historyRequest.execute()).thenReturn(
            new ListHistoryResponse()
                .setHistory(List.of(first, second))
                .setHistoryId(new BigInteger("110"))
                .setNextPageToken("history-next"));

        ChangePage page = fixture.service.listChanges(
            fixture.account,
            new ChangeRequest(
                "100", SyncCapabilities.ChangeFeedKind.HISTORY, "history-page", 250));

        assertThat(page.changedIds())
            .containsExactlyInAnyOrder("added", "relabeled", "unlabeled");
        assertThat(page.changedIds()).doesNotContain("generic-must-be-ignored");
        assertThat(page.deletedIds()).containsExactly("deleted");
        assertThat(page.lastCompletelyAppliedRevision()).isEqualTo("102");
        assertThat(page.returnedCursor()).isEqualTo("110");
        assertThat(page.nextPageToken()).isEqualTo("history-next");
        assertThat(page.hasMore()).isTrue();
        verify(fixture.historyRequest).setStartHistoryId(new BigInteger("100"));
        verify(fixture.historyRequest).setPageToken("history-page");
    }

    @Test
    @DisplayName("providerClassifiesMetadataDeletionAndExpiredHistoryWithoutReplacingCursor")
    void providerClassifiesMetadataDeletionAndExpiredHistoryWithoutReplacingCursor()
            throws Exception {
        Fixture fixture = fixture();
        fixture.notFoundMetadataIds.add("gone");

        List<MailMessage> current = fixture.service.getCurrentMetadata(
            fixture.account, List.of("kept", "gone"));
        assertThat(current).extracting(MailMessage::id).containsExactly("kept");

        when(fixture.historyRequest.execute()).thenThrow(notFound());
        assertThatThrownBy(() -> fixture.service.listChanges(
            fixture.account,
            new ChangeRequest("100", SyncCapabilities.ChangeFeedKind.HISTORY, null, 100)))
            .isInstanceOf(GmailClientInitException.class)
            .hasMessage("HISTORY_EXPIRED");
    }

    @Test
    @DisplayName("providerReconcilesAttachmentPresenceFromIdMembershipOnly")
    void providerReconcilesAttachmentPresenceFromIdMembershipOnly() throws Exception {
        Fixture fixture = fixture();
        when(fixture.listRequest.execute()).thenReturn(
            new ListMessagesResponse()
                .setMessages(List.of(
                    new Message().setId("with-attachment"),
                    new Message().setId("another")))
                .setNextPageToken("attachment-next"));

        AttachmentMembershipPage page =
            fixture.service.listAttachmentMembership(fixture.account, "attachment-page");

        assertThat(page.messageIds())
            .containsExactlyInAnyOrder("with-attachment", "another");
        assertThat(page.nextPageToken()).isEqualTo("attachment-next");
        verify(fixture.listRequest).setQ("has:attachment");
        verify(fixture.listRequest).setIncludeSpamTrash(true);
        verify(fixture.listRequest).setPageToken("attachment-page");
    }

    @Test
    @DisplayName("gmailMetadataMapsLabelsSizeAndUnsignedRevisionWithoutSnippetOrMimeInference")
    void gmailMetadataMapsLabelsSizeAndUnsignedRevisionWithoutSnippetOrMimeInference() {
        MessagePart payload = new MessagePart()
            .setHeaders(List.of(
                new MessagePartHeader().setName("Subject").setValue("fixture subject")))
            .setParts(List.of(new MessagePart()
                .setFilename("should-not-prove-attachment.pdf")
                .setBody(new MessagePartBody().setAttachmentId("opaque-id"))));
        Message fixture = new Message()
            .setId("metadata-1")
            .setThreadId("thread-1")
            .setInternalDate(1_700_000_000_000L)
            .setHistoryId(new BigInteger("18446744073709551616"))
            .setLabelIds(List.of("INBOX", "UNREAD"))
            .setSizeEstimate(4096)
            .setSnippet("body-derived text that must be ignored")
            .setPayload(payload);

        MailMessage converted = GmailMailService.toMailMessage("google", fixture);

        assertThat(converted.labelIds()).containsExactlyInAnyOrder("INBOX", "UNREAD");
        assertThat(converted.isUnread()).isTrue();
        assertThat(converted.sizeEstimate()).isEqualTo(4096L);
        assertThat(converted.providerRevision())
            .isEqualTo(new BigInteger("18446744073709551616"));
        assertThat(converted.attachmentState()).isEqualTo(AttachmentState.UNKNOWN);
        assertThat(converted.subject()).contains("fixture subject");
        assertThat(converted.headers().values())
            .allSatisfy(values -> assertThat(values).doesNotContain(fixture.getSnippet()));
    }

    @Test
    @DisplayName("configurationDefaultsToProgressiveUnlimitedSyncAndReservesFreshnessCapacity")
    void configurationDefaultsToProgressiveUnlimitedSyncAndReservesFreshnessCapacity()
            throws Exception {
        GmailSyncConfigProperties config = configuredProperties();
        assertThat(config.getInitialPageSize()).isEqualTo(200);
        assertThat(config.isMessageLimitUnlimited()).isTrue();
        config.validate();

        GmailRateLimiter limiter = new GmailRateLimiter(config);
        limiter.init();
        GmailRateLimiter.Permit firstBackfill = limiter.acquireBackfillRescanPermit();
        GmailRateLimiter.Permit secondBackfill = limiter.acquireBackfillRescanPermit();
        GmailRateLimiter.Permit freshness = limiter.acquireFreshnessPermit();
        FutureTask<GmailRateLimiter.Permit> blockedBackfill =
            new FutureTask<>(limiter::acquireBackfillRescanPermit);
        java.lang.Thread waiter = java.lang.Thread.ofPlatform().start(blockedBackfill);
        assertThatThrownBy(() -> blockedBackfill.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        freshness.close();
        assertThatThrownBy(() -> blockedBackfill.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        firstBackfill.close();
        blockedBackfill.get(1, TimeUnit.SECONDS).close();
        secondBackfill.close();
        waiter.join(1_000);

        config.setInitialPageSize(99);
        assertThatThrownBy(config::validate)
            .hasMessageContaining("SYNC_INITIAL_PAGE_SIZE");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optOutGuards")
    @DisplayName("phase19SyncPathDoesNotUseOptedOutCapability")
    void phase19SyncPathDoesNotUseOptedOutCapability(
            String capability, String forbiddenPattern) throws Exception {
        String syncContracts = Files.readString(Path.of(
            "src/main/java/com/tidymail/mail/sync/MailboxSyncProvider.java"));
        String service = Files.readString(Path.of(
            "src/main/java/com/tidymail/mail/gmail/GmailMailService.java"));
        String syncMethods = syncMethodBodies(service);

        assertThat(syncContracts + syncMethods)
            .as("Phase 19 opt-out: " + capability)
            .doesNotContain(forbiddenPattern);
    }

    private static Stream<Arguments> optOutGuards() {
        return Stream.of(
            Arguments.of("message mutation", ".batchModify("),
            Arguments.of("FULL retrieval", "\"FULL\""),
            Arguments.of("RAW retrieval", "\"RAW\""),
            Arguments.of("body, snippet, or MIME content", "getSnippet("),
            Arguments.of("attachment content", ".attachments().get("),
            Arguments.of("watch or stop", ".watch("),
            Arguments.of("settings", ".settings()"),
            Arguments.of("filters", ".filters()"),
            Arguments.of("label management", ".labels()"),
            Arguments.of("Contacts or People", "PeopleService"),
            Arguments.of("drafts", ".drafts()"),
            Arguments.of("send", ".send("),
            Arguments.of("threads", ".threads()"));
    }

    private static String syncMethodBodies(String source) {
        List<String> names = List.of(
            "profile", "listRecentBootstrap", "listBackfill", "listChanges",
            "getCurrentMetadata", "listAttachmentMembership");
        StringBuilder bodies = new StringBuilder();
        for (String name : names) {
            int start = source.indexOf(" " + name + "(");
            int end = source.indexOf("\n    @Override", start + 1);
            bodies.append(source, start, end < 0 ? source.length() : end);
        }
        return bodies.toString();
    }

    private static GmailSyncConfigProperties configuredProperties() {
        GmailSyncConfigProperties config = new GmailSyncConfigProperties();
        config.setApiConcurrency(3);
        config.setBatchDelayMs(0);
        config.setInitialPageSize(200);
        config.setBackfillPageSize(500);
        config.setMaxMessagesPerAccount(0);
        config.setRepairLimit(100);
        config.setFreshnessReservedCapacity(1);
        return config;
    }

    private static GoogleJsonResponseException notFound() {
        HttpResponseException.Builder builder =
            new HttpResponseException.Builder(404, "Not Found", new HttpHeaders());
        GoogleJsonError error = new GoogleJsonError();
        error.setCode(404);
        error.setMessage("discarded provider detail");
        return new GoogleJsonResponseException(builder, error);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() throws IOException {
        GmailClientFactory clientFactory = mock(GmailClientFactory.class);
        GmailRateLimiter rateLimiter = mock(GmailRateLimiter.class);
        Gmail gmail = mock(Gmail.class);
        Gmail.Users users = mock(Gmail.Users.class);
        Gmail.Users.Messages messages = mock(Gmail.Users.Messages.class);
        Gmail.Users.History history = mock(Gmail.Users.History.class);
        Gmail.Users.GetProfile profileRequest = mock(Gmail.Users.GetProfile.class);
        Gmail.Users.Messages.List listRequest = mock(Gmail.Users.Messages.List.class);
        Gmail.Users.History.List historyRequest = mock(Gmail.Users.History.List.class);
        BatchRequest batch = mock(BatchRequest.class);

        when(gmail.users()).thenReturn(users);
        when(gmail.batch()).thenReturn(batch);
        when(users.messages()).thenReturn(messages);
        when(users.history()).thenReturn(history);
        when(users.getProfile("me")).thenReturn(profileRequest);
        when(messages.list("me")).thenReturn(listRequest);
        when(history.list("me")).thenReturn(historyRequest);
        when(listRequest.setMaxResults(anyLong())).thenReturn(listRequest);
        when(listRequest.setIncludeSpamTrash(any())).thenReturn(listRequest);
        when(listRequest.setPageToken(anyString())).thenReturn(listRequest);
        when(listRequest.setQ(anyString())).thenReturn(listRequest);
        when(historyRequest.setStartHistoryId(any())).thenReturn(historyRequest);
        when(historyRequest.setMaxResults(anyLong())).thenReturn(historyRequest);
        when(historyRequest.setPageToken(anyString())).thenReturn(historyRequest);
        when(clientFactory.buildFor(any(UserAccount.class))).thenReturn(gmail);
        when(rateLimiter.supplyWithFreshnessPermit(any())).thenAnswer(invocation ->
            ((Supplier<Object>) invocation.getArgument(0)).get());
        when(rateLimiter.supplyWithBackfillRescanPermit(any())).thenAnswer(invocation ->
            ((Supplier<Object>) invocation.getArgument(0)).get());

        Fixture fixture = new Fixture();
        fixture.account = new UserAccount();
        fixture.account.setProvider("google");
        fixture.account.setProviderAccountId("provider-account");
        fixture.account.setEmail("fixture@example.invalid");
        fixture.profileRequest = profileRequest;
        fixture.listRequest = listRequest;
        fixture.historyRequest = historyRequest;
        fixture.gmail = gmail;

        when(messages.get(eq("me"), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(1);
            Gmail.Users.Messages.Get get = mock(Gmail.Users.Messages.Get.class);
            when(get.setFormat(anyString())).thenAnswer(setter -> {
                fixture.metadataFormats.add(setter.getArgument(0));
                return get;
            });
            when(get.setMetadataHeaders(anyList())).thenAnswer(setter -> {
                fixture.metadataHeaders.add(List.copyOf(setter.getArgument(0)));
                return get;
            });
            doAnswer(queue -> {
                JsonBatchCallback<Message> callback = queue.getArgument(1);
                if (fixture.notFoundMetadataIds.contains(id)) {
                    GoogleJsonError error = new GoogleJsonError();
                    error.setCode(404);
                    callback.onFailure(error, new HttpHeaders());
                } else {
                    callback.onSuccess(metadataMessage(id), new HttpHeaders());
                }
                return null;
            }).when(get).queue(eq(batch), any(JsonBatchCallback.class));
            return get;
        });

        fixture.service = new GmailMailService(
            clientFactory, rateLimiter, configuredProperties());
        return fixture;
    }

    private static Message metadataMessage(String id) {
        return new Message()
            .setId(id)
            .setThreadId("thread-" + id)
            .setInternalDate(Instant.parse("2026-08-14T00:00:00Z").toEpochMilli())
            .setHistoryId(new BigInteger("12345678901234567890"))
            .setLabelIds(List.of("INBOX", "UNREAD"))
            .setSizeEstimate(2048)
            .setPayload(new MessagePart().setHeaders(List.of(
                new MessagePartHeader().setName("Subject").setValue("fixture"))));
    }

    private static final class Fixture {
        GmailMailService service;
        UserAccount account;
        Gmail.Users.GetProfile profileRequest;
        Gmail.Users.Messages.List listRequest;
        Gmail.Users.History.List historyRequest;
        Gmail gmail;
        final Set<String> notFoundMetadataIds = new java.util.HashSet<>();
        final List<String> metadataFormats = new ArrayList<>();
        final List<List<String>> metadataHeaders = new ArrayList<>();
    }
}
