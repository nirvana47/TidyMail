package com.tidymail.mail.gmail;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.BatchModifyMessagesRequest;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryLabelAdded;
import com.google.api.services.gmail.model.HistoryLabelRemoved;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.HistoryMessageDeleted;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.ListThreadsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.Thread;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailFolderOrLabel;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.MailThreadSummary;
import com.tidymail.mail.PageResult;
import com.tidymail.mail.sync.AttachmentMembershipPage;
import com.tidymail.mail.sync.BackfillRequest;
import com.tidymail.mail.sync.ChangePage;
import com.tidymail.mail.sync.ChangeRequest;
import com.tidymail.mail.sync.MailboxSnapshot;
import com.tidymail.mail.sync.MailboxSyncProvider;
import com.tidymail.mail.sync.SyncCapabilities;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Gmail implementation of the provider-agnostic {@link MailProvider} interface.
 *
 * <p>Privacy contract (REQUIREMENTS §GML-1 / D-4.2): every message-fetching call
 * made through this service uses Gmail API {@code format=METADATA} combined with
 * an explicit {@code metadataHeaders} whitelist (24 headers, exactly).  Both
 * layers of the guard are enforced here at the API call site AND inside the
 * {@link MailMessage} DTO via its {@code body()} throwing
 * {@link UnsupportedOperationException}.  If a future maintainer changes this
 * class to use {@code format=FULL}, {@code format=RAW}, or any format other
 * than METADATA, tracer T4(a) in {@code Phase4GmailProviderTest} will fail.
 */
@Service
public class GmailMailService implements MailProvider, MailboxSyncProvider {

    /**
     * The ONLY headers TidyMail ever downloads from Gmail.  Hard-coded to a
     * static final set (D-4.3: NOT env-configurable) so the privacy boundary
     * cannot be widened without a code change plus a corresponding test change.
     *
     * <p>List is copied verbatim from REQUIREMENTS.md §GML-1.  Any new header
     * must be justified there and added here with a comment; in addition the
     * Phase-4 tracer T4(a) validates {@code size == 24}.
     */
    public static final Set<String> METADATA_HEADERS = Set.of(
        "From",
        "To",
        "Cc",
        "Bcc",
        "Subject",
        "Date",
        "List-Unsubscribe",
        "List-Unsubscribe-Post",
        "List-Id",
        "Precedence",
        "Auto-Submitted",
        "Return-Path",
        "Reply-To",
        "Message-ID",
        "References",
        "In-Reply-To",
        "Delivered-To",
        "Received-SPF",
        "Authentication-Results",
        "DKIM-Signature",
        "X-Auto-Response-Suppress",
        "X-Mailer",
        "X-Mailing-List",
        "Feedback-ID"
        // NOTE: if you add a header here, also update REQUIREMENTS §GML-1 and
        // change the T4(a) tracer assertion size from 24.
    );

    private static final int METADATA_HEADERS_SIZE_EXPECTED = 24;

    /** Gmail API reserved literal "me" resolves the credential owner — NOT the
     *  user's email address.  Using email aliases or delegated accounts fails
     *  otherwise.  (Pitfall G2.)
     */
    private static final String USER_ID_ME = "me";

    /**
     * Maps each provider-agnostic {@link BulkActionType} to (addLabelIds,
     * removeLabelIds) arrays for Gmail's batch-modify JSON endpoint.
     * System-label IDs used below are Gmail reserved strings, not DB IDs:
     *   INBOX — the user's Inbox label; removing it = archive
     *   TRASH — Trash folder; adding it = move to trash
     *   UNREAD — virtual label applied to unread messages
     */
    private static final Map<BulkActionType, LabelDelta> BULK_ACTION_LABELS;

    static {
        Map<BulkActionType, LabelDelta> m = new EnumMap<>(BulkActionType.class);
        m.put(BulkActionType.ARCHIVE,      LabelDelta.remove("INBOX"));
        m.put(BulkActionType.TRASH,        LabelDelta.add("TRASH"));
        m.put(BulkActionType.MARK_READ,    LabelDelta.remove("UNREAD"));
        m.put(BulkActionType.MARK_UNREAD,  LabelDelta.add("UNREAD"));
        BULK_ACTION_LABELS = Collections.unmodifiableMap(m);
    }

    static {
        // Static assertion matching tracer T4(a).  If this trips at class load
        // time, REQUIREMENTS §GML-1 drifted from the whitelist above.
        if (METADATA_HEADERS.size() != METADATA_HEADERS_SIZE_EXPECTED) {
            throw new ExceptionInInitializerError(
                "METADATA_HEADERS whitelist drifted: size=" + METADATA_HEADERS.size()
                    + " but REQUIREMENTS §GML-1 contract requires size="
                    + METADATA_HEADERS_SIZE_EXPECTED);
        }
    }

    private final GmailClientFactory clientFactory;
    private final GmailRateLimiter rateLimiter;
    private final GmailSyncConfigProperties syncConfig;

    public GmailMailService(GmailClientFactory clientFactory, GmailRateLimiter rateLimiter) {
        this(clientFactory, rateLimiter, new GmailSyncConfigProperties());
    }

    @Autowired
    public GmailMailService(
            GmailClientFactory clientFactory,
            GmailRateLimiter rateLimiter,
            GmailSyncConfigProperties syncConfig) {
        this.clientFactory = clientFactory;
        this.rateLimiter = rateLimiter;
        this.syncConfig = syncConfig;
    }

    /**
     * Returns the sync capabilities this Gmail implementation supports.
     */
    @Override
    public SyncCapabilities capabilities() {
        return new SyncCapabilities(
            SyncCapabilities.ChangeFeedKind.HISTORY, true, true);
    }

    /**
     * Profiles the account, returning a snapshot with the current history
     * cursor and total message count.
     */
    @Override
    public MailboxSnapshot profile(UserAccount account) {
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithFreshnessPermit(() -> {
            try {
                var profile = gmail.users().getProfile(USER_ID_ME).execute();
                String cursor = requiredRevision(profile.getHistoryId(), "PROFILE_CURSOR_MISSING");
                Long total = profile.getMessagesTotal() == null
                    ? null : profile.getMessagesTotal().longValue();
                return new MailboxSnapshot(
                    cursor, SyncCapabilities.ChangeFeedKind.HISTORY, total);
            } catch (IOException ex) {
                throw syncFailure("PROFILE_FAILED", ex);
            }
        });
    }

    /**
     * Lists the most recent messages for an initial bootstrap, fetching
     * metadata for each.
     */
    @Override
    public List<MailMessage> listRecentBootstrap(UserAccount account, int maxResults) {
        if (maxResults < 100 || maxResults > 250) {
            throw new IllegalArgumentException(
                "Recent bootstrap max results must be between 100 and 250");
        }
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithFreshnessPermit(() -> {
            try {
                ListMessagesResponse response = gmail.users().messages().list(USER_ID_ME)
                    .setMaxResults((long) maxResults)
                    .execute();
                return fetchMetadata(
                    gmail, ids(response.getMessages()), account.getProvider(), false);
            } catch (IOException ex) {
                throw syncFailure("BOOTSTRAP_FAILED", ex);
            }
        });
    }

    /**
     * Backfills a page of messages (including spam/trash) for the account.
     */
    @Override
    public PageResult<MailMessage> listBackfill(
            UserAccount account, BackfillRequest request) {
        if (request.maxResults() > syncConfig.getBackfillPageSize()
                || request.maxResults() > 500) {
            throw new IllegalArgumentException(
                "Backfill max results exceeds configured Gmail page size");
        }
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithBackfillRescanPermit(() -> {
            try {
                Gmail.Users.Messages.List list = gmail.users().messages().list(USER_ID_ME)
                    .setIncludeSpamTrash(true)
                    .setMaxResults((long) request.maxResults());
                setPageToken(list, request.pageToken());
                ListMessagesResponse response = list.execute();
                List<MailMessage> messages = fetchMetadata(
                    gmail, ids(response.getMessages()), account.getProvider(), false);
                return PageResult.of(
                    messages, response.getNextPageToken(), request.maxResults());
            } catch (IOException ex) {
                throw syncFailure("BACKFILL_FAILED", ex);
            }
        });
    }

    /**
     * Pulls a page of changes from the Gmail history feed since the given
     * cursor, returning changed/deleted IDs and the next cursor.
     */
    @Override
    public ChangePage listChanges(UserAccount account, ChangeRequest request) {
        if (request.cursorKind() != SyncCapabilities.ChangeFeedKind.HISTORY) {
            throw new IllegalArgumentException("Gmail requires a HISTORY cursor");
        }
        BigInteger startRevision;
        try {
            startRevision = new BigInteger(request.cursor());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Gmail change cursor must be unsigned", ex);
        }
        if (startRevision.signum() < 0) {
            throw new IllegalArgumentException("Gmail change cursor must be unsigned");
        }

        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithFreshnessPermit(() -> {
            try {
                Gmail.Users.History.List list = gmail.users().history().list(USER_ID_ME)
                    .setStartHistoryId(startRevision)
                    .setMaxResults((long) request.maxResults());
                if (request.pageToken() != null) {
                    list.setPageToken(request.pageToken());
                }
                ListHistoryResponse response = list.execute();
                Set<String> changed = new TreeSet<>();
                Set<String> deleted = new TreeSet<>();
                String completeRevision = null;
                if (response.getHistory() != null) {
                    for (History history : response.getHistory()) {
                        collectChanged(
                            history.getMessagesAdded(),
                            HistoryMessageAdded::getMessage, changed, deleted);
                        collectChanged(
                            history.getLabelsAdded(),
                            HistoryLabelAdded::getMessage, changed, deleted);
                        collectChanged(
                            history.getLabelsRemoved(),
                            HistoryLabelRemoved::getMessage, changed, deleted);
                        collectDeleted(
                            history.getMessagesDeleted(),
                            HistoryMessageDeleted::getMessage, changed, deleted);
                        if (history.getId() != null) {
                            completeRevision = history.getId().toString();
                        }
                    }
                }
                String returnedCursor = response.getHistoryId() == null
                    ? request.cursor() : response.getHistoryId().toString();
                String nextPageToken = response.getNextPageToken();
                return new ChangePage(
                    changed, deleted, completeRevision, returnedCursor,
                    nextPageToken, nextPageToken != null);
            } catch (GoogleJsonResponseException ex) {
                if (ex.getStatusCode() == 404) {
                    throw syncFailure("HISTORY_EXPIRED", ex);
                }
                throw syncFailure("CHANGES_FAILED", ex);
            } catch (IOException ex) {
                throw syncFailure("CHANGES_FAILED", ex);
            }
        });
    }

    /**
     * Fetches current metadata for the given message IDs.
     */
    @Override
    public List<MailMessage> getCurrentMetadata(
            UserAccount account, List<String> messageIds) {
        if (messageIds == null || messageIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Current metadata IDs must be non-blank");
        }
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithFreshnessPermit(() -> {
            try {
                return fetchMetadata(gmail, messageIds, account.getProvider(), true);
            } catch (IOException ex) {
                throw syncFailure("METADATA_FAILED", ex);
            }
        });
    }

    /**
     * Lists a page of message IDs that have attachments.
     */
    @Override
    public AttachmentMembershipPage listAttachmentMembership(
            UserAccount account, String pageToken) {
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithBackfillRescanPermit(() -> {
            try {
                Gmail.Users.Messages.List list = gmail.users().messages().list(USER_ID_ME)
                    .setQ("has:attachment")
                    .setIncludeSpamTrash(true)
                    .setMaxResults((long) syncConfig.getBackfillPageSize());
                setPageToken(list, pageToken);
                ListMessagesResponse response = list.execute();
                return AttachmentMembershipPage.of(
                    Set.copyOf(ids(response.getMessages())), response.getNextPageToken());
            } catch (IOException ex) {
                throw syncFailure("ATTACHMENT_MEMBERSHIP_FAILED", ex);
            }
        });
    }

    // ---------------------------------------------------------------------
    // listMessages (Pitfall G2, G3, D-4.1, D-4.2)
    // ---------------------------------------------------------------------
    @Override
    /**
     * Lists a page of messages with metadata, batching the per-message
     * metadata fetches to limit concurrent Gmail requests.
     */
    public PageResult<MailMessage> listMessages(UserAccount account, String pageToken, int maxResults) {
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithPermit(() -> {
            try {
                Gmail.Users.Messages.List list = gmail.users().messages().list(USER_ID_ME)
                    .setMaxResults(Long.valueOf(maxResults));
                if (pageToken != null && !pageToken.isBlank()) {
                    list.setPageToken(pageToken);
                }

                ListMessagesResponse resp = list.execute();
                String nextPage = resp.getNextPageToken();
                List<Message> gmailMessages = resp.getMessages();
                if (gmailMessages == null) {
                    return PageResult.of(List.of(), nextPage, maxResults);
                }

                // Gmail executes subrequests in a batch concurrently. Keep batches small:
                // a 100-item batch caused "Too many concurrent requests for user" while
                // 10-item batches still avoid the old 100 serial HTTP round trips.
                final int metadataBatchSize = 10;
                Map<String, Message> fetchedById = new HashMap<>();
                for (int start = 0; start < gmailMessages.size(); start += metadataBatchSize) {
                    BatchRequest batch = gmail.batch();
                    List<String> failures = new ArrayList<>();
                    int end = Math.min(start + metadataBatchSize, gmailMessages.size());
                    for (Message slim : gmailMessages.subList(start, end)) {
                        gmail.users().messages()
                            .get(USER_ID_ME, slim.getId())
                            .setFormat("METADATA")
                            .setMetadataHeaders(new ArrayList<>(METADATA_HEADERS))
                            .queue(batch, new JsonBatchCallback<>() {
                                @Override
                                public void onSuccess(Message full, HttpHeaders responseHeaders) {
                                    fetchedById.put(full.getId(), full);
                                }

                                @Override
                                public void onFailure(GoogleJsonError error, HttpHeaders responseHeaders) {
                                    failures.add(error == null ? "unknown Gmail metadata error" : error.getMessage());
                                }
                            });
                    }
                    batch.execute();
                    if (!failures.isEmpty()) {
                        throw new IOException("Gmail metadata batch failed: " + failures.get(0));
                    }
                }

                List<MailMessage> items = new ArrayList<>(gmailMessages.size());
                for (Message slim : gmailMessages) {
                    Message full = fetchedById.get(slim.getId());
                    if (full == null) {
                        throw new IOException("Gmail metadata batch did not return message " + slim.getId());
                    }
                    items.add(toMailMessage(account.getProvider(), full));
                }
                return PageResult.of(items, nextPage, maxResults);
            } catch (IOException ex) {
                throw new GmailClientInitException(
                    "Gmail listMessages failed for account " + account.getEmail(), ex);
            }
        });
    }

    // ---------------------------------------------------------------------
    // listFoldersOrLabels
    // ---------------------------------------------------------------------
    /**
     * Lists the account's folders/labels.
     */
    @Override
    public List<MailFolderOrLabel> listFoldersOrLabels(UserAccount account) {
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithPermit(() -> {
            try {
                ListLabelsResponse resp = gmail.users().labels().list(USER_ID_ME).execute();
                List<Label> labels = resp.getLabels();
                if (labels == null) {
                    return List.of();
                }
                return labels.stream()
                    .map(l -> {
                        MailFolderOrLabel entry = MailFolderOrLabel.forLabel(l.getId(), l.getName());
                        // Gmail API also exposes messagesTotal / messagesUnread
                        // on individual Label resources when fetched singly;
                        // the list response only has a type field.  We map the
                        // type field to MailFolderOrLabel.type so callers can
                        // tell user vs system labels apart.
                        // Construct with proper type by re-building:
                        String type = l.getType() == null ? "label" : labelTypeHuman(l.getType());
                        return new MailFolderOrLabel(
                            l.getId(),
                            l.getName(),
                            type,
                            l.getMessagesTotal() == null ? 0L : l.getMessagesTotal(),
                            l.getMessagesUnread() == null ? 0L : l.getMessagesUnread());
                    })
                    .collect(Collectors.toList());
            } catch (IOException ex) {
                throw new GmailClientInitException(
                    "Gmail listLabels failed for account " + account.getEmail(), ex);
            }
        });
    }

    // ---------------------------------------------------------------------
    // batchModify (GML-4, D-4.4, D-4.6 partial-failure)
    // ---------------------------------------------------------------------
    /**
     * Applies a bulk label add/remove action to the given messages, reporting
     * partial failures per message.
     */
    @Override
    public BulkActionResult batchModify(UserAccount account, BulkActionRequest request) {
        LabelDelta delta = BULK_ACTION_LABELS.get(request.actionType());
        if (delta == null) {
            // Any BulkActionType we don't know about → fail the entire
            // request with a clear error (no silent no-op).  Known types map
            // exhaustively above via EnumMap so this is a future-type guard.
            Map<String,String> failed = new LinkedHashMap<>();
            for (String id : request.messageIds()) failed.put(id, "unsupported action: " + request.actionType());
            return BulkActionResult.withPartialFailures(request.messageIds().size(), List.of(), failed);
        }

        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithPermit(() -> {
            try {
                BatchModifyMessagesRequest body = new BatchModifyMessagesRequest();
                body.setIds(new ArrayList<>(request.messageIds()));
                body.setAddLabelIds(delta.addLabelIds);
                body.setRemoveLabelIds(delta.removeLabelIds);

                gmail.users().messages().batchModify(USER_ID_ME, body).execute();

                // Gmail's JSON batch API returns HTTP 204 No Content on
                // success and does not report per-id successes; if any id is
                // 404 the whole call still returns 204 (documented behavior).
                // We therefore treat every id in the request as succeeded
                // (matching the optimistic pattern of Google's API), and any
                // caller that wants finer granularity can re-query.
                return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
            } catch (IOException ex) {
                // Translate transport-level failure into a partial-failure
                // result where every id is tagged with the transport error
                // message.  This preserves D-4.6 fail-slow contract.
                Map<String,String> failed = new LinkedHashMap<>();
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                for (String id : request.messageIds()) failed.put(id, reason);
                return BulkActionResult.withPartialFailures(request.messageIds().size(), List.of(), failed);
            }
        });
    }

    // ---------------------------------------------------------------------
    // applyLabel
    // ---------------------------------------------------------------------
    /**
     * Adds or removes a single label on the given messages.
     */
    @Override
    public BulkActionResult applyLabel(UserAccount account, List<String> messageIds, String labelId, boolean add) {
        if (labelId == null || labelId.isBlank()) {
            Map<String,String> failed = new LinkedHashMap<>();
            for (String id : messageIds) failed.put(id, "labelId is blank");
            return BulkActionResult.withPartialFailures(messageIds.size(), List.of(), failed);
        }
        LabelDelta delta = add
            ? LabelDelta.add(labelId)
            : LabelDelta.remove(labelId);

        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithPermit(() -> {
            try {
                BatchModifyMessagesRequest body = new BatchModifyMessagesRequest();
                body.setIds(new ArrayList<>(messageIds));
                body.setAddLabelIds(delta.addLabelIds);
                body.setRemoveLabelIds(delta.removeLabelIds);
                gmail.users().messages().batchModify(USER_ID_ME, body).execute();
                return BulkActionResult.ok(messageIds.size(), messageIds);
            } catch (IOException ex) {
                Map<String,String> failed = new LinkedHashMap<>();
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                for (String id : messageIds) failed.put(id, reason);
                return BulkActionResult.withPartialFailures(messageIds.size(), List.of(), failed);
            }
        });
    }

    // ---------------------------------------------------------------------
    // getThreadMetadata
    // ---------------------------------------------------------------------
    /**
     * Fetches metadata for a thread and summarizes its participants, domains,
     * and message count.
     */
    @Override
    public MailThreadSummary getThreadMetadata(UserAccount account, String threadId) {
        Gmail gmail = clientFactory.buildFor(account);
        return rateLimiter.supplyWithPermit(() -> {
            try {
                Thread thread = gmail.users().threads()
                    .get(USER_ID_ME, threadId)
                    .setFormat("METADATA")
                    .setMetadataHeaders(new ArrayList<>(METADATA_HEADERS))
                    .execute();

                List<Message> msgs = thread.getMessages();
                if (msgs == null || msgs.isEmpty()) {
                    return new MailThreadSummary(
                        threadId, List.of(), Set.of(), 0, null, null, List.of());
                }

                List<String> participants = new ArrayList<>();
                Set<String> domains = new HashSet<>();
                List<String> orderedIds = new ArrayList<>(msgs.size());
                Instant oldest = null;
                Instant newest = null;

                for (Message m : msgs) {
                    orderedIds.add(m.getId());
                    MailMessage wrapped = toMailMessage(account.getProvider(), m);
                    wrapped.from().ifPresent(from -> {
                        participants.add(from);
                        String domain = extractDomain(from);
                        if (domain != null) domains.add(domain);
                    });
                    wrapped.to().ifPresent(to -> {
                        for (String piece : to.split(",")) {
                            String t = piece.trim();
                            if (!t.isEmpty()) {
                                participants.add(t);
                                String d = extractDomain(t);
                                if (d != null) domains.add(d);
                            }
                        }
                    });
                    Instant ts = wrapped.receivedAt();
                    if (ts != null) {
                        if (oldest == null || ts.isBefore(oldest)) oldest = ts;
                        if (newest == null || ts.isAfter(newest)) newest = ts;
                    }
                }
                // De-duplicate participants preserving insertion order
                List<String> uniqueParticipants = participants.stream()
                    .distinct()
                    .collect(Collectors.toList());

                return new MailThreadSummary(
                    threadId,
                    uniqueParticipants,
                    Collections.unmodifiableSet(domains),
                    msgs.size(),
                    oldest,
                    newest,
                    List.copyOf(orderedIds));
            } catch (IOException ex) {
                throw new GmailClientInitException(
                    "Gmail getThreadMetadata failed for thread=" + threadId
                        + " account=" + account.getEmail(), ex);
            }
        });
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private List<MailMessage> fetchMetadata(
            Gmail gmail,
            List<String> requestedIds,
            String provider,
            boolean ignoreNotFound) throws IOException {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return List.of();
        }

        List<String> uniqueIds = new ArrayList<>(new java.util.LinkedHashSet<>(requestedIds));
        Map<String, Message> fetchedById = new java.util.concurrent.ConcurrentHashMap<>();
        List<Integer> failures = Collections.synchronizedList(new ArrayList<>());
        final int metadataBatchSize = 10;

        for (int start = 0; start < uniqueIds.size(); start += metadataBatchSize) {
            BatchRequest batch = gmail.batch();
            int end = Math.min(start + metadataBatchSize, uniqueIds.size());
            for (String messageId : uniqueIds.subList(start, end)) {
                gmail.users().messages()
                    .get(USER_ID_ME, messageId)
                    .setFormat("METADATA")
                    .setMetadataHeaders(new ArrayList<>(METADATA_HEADERS))
                    .queue(batch, new JsonBatchCallback<>() {
                        @Override
                        public void onSuccess(Message message, HttpHeaders responseHeaders) {
                            fetchedById.put(messageId, message);
                        }

                        @Override
                        public void onFailure(
                                GoogleJsonError error, HttpHeaders responseHeaders) {
                            int code = error == null ? 0 : error.getCode();
                            if (!(ignoreNotFound && code == 404)) {
                                failures.add(code);
                            }
                        }
                    });
            }
            batch.execute();
        }

        if (!failures.isEmpty()) {
            throw new IOException("Gmail metadata request failed with status " + failures.getFirst());
        }

        List<MailMessage> messages = new ArrayList<>(fetchedById.size());
        for (String messageId : uniqueIds) {
            Message message = fetchedById.get(messageId);
            if (message != null) {
                messages.add(toMailMessage(provider, message));
            } else if (!ignoreNotFound) {
                throw new IOException("Gmail metadata response was incomplete");
            }
        }
        return List.copyOf(messages);
    }

    private static List<String> ids(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
            .map(Message::getId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
    }

    private static void setPageToken(
            Gmail.Users.Messages.List request, String pageToken) {
        if (pageToken != null) {
            request.setPageToken(pageToken);
        }
    }

    private static String requiredRevision(BigInteger revision, String code) {
        if (revision == null || revision.signum() < 0) {
            throw new GmailClientInitException(code);
        }
        return revision.toString();
    }

    private static <T> void collectChanged(
            List<T> events,
            Function<T, Message> messageExtractor,
            Set<String> changed,
            Set<String> deleted) {
        if (events == null) {
            return;
        }
        for (T event : events) {
            Message message = messageExtractor.apply(event);
            if (message != null && message.getId() != null && !message.getId().isBlank()) {
                deleted.remove(message.getId());
                changed.add(message.getId());
            }
        }
    }

    private static <T> void collectDeleted(
            List<T> events,
            Function<T, Message> messageExtractor,
            Set<String> changed,
            Set<String> deleted) {
        if (events == null) {
            return;
        }
        for (T event : events) {
            Message message = messageExtractor.apply(event);
            if (message != null && message.getId() != null && !message.getId().isBlank()) {
                changed.remove(message.getId());
                deleted.add(message.getId());
            }
        }
    }

    private static GmailClientInitException syncFailure(String code, Exception cause) {
        return new GmailClientInitException(code, cause);
    }

    /**
     * Converts a Gmail API {@link Message} (which must already have been
     * fetched with {@code format=METADATA} + our header whitelist) into a
     * provider-agnostic {@link MailMessage}.  Enforces the D-4.2 privacy
     * guard: asserts that {@code raw} is null and every MIME part has no body
     * data. Attachment presence remains UNKNOWN here because Gmail does not
     * promise MIME children in METADATA responses; ID-only search membership
     * is reconciled separately.
     *
     * <p>These assertions throw {@link IllegalStateException} if violated,
     * which is a stronger fail-fast than a silent ignore.
     */
    public static MailMessage toMailMessage(String provider, Message gmailMessage) {
        // --- Format-guards (D-4.2) ---
        if (gmailMessage.getRaw() != null && !gmailMessage.getRaw().isEmpty()) {
            throw new IllegalStateException(
                "Gmail Message.raw was populated — format is not METADATA. "
                    + "Message id=" + gmailMessage.getId());
        }
        var payload = gmailMessage.getPayload();
        if (payload != null) {
            rejectBodyData(payload, gmailMessage.getId());
        }

        String id = gmailMessage.getId();
        String threadId = gmailMessage.getThreadId();
        Instant receivedAt = historyIdOrInternalDateToInstant(gmailMessage.getInternalDate());

        Map<String, List<String>> headers;
        if (payload == null || payload.getHeaders() == null) {
            headers = Collections.emptyMap();
        } else {
            // Preserve insertion order; multi-valued headers like "Received"
            // are stored as separate header entries in Gmail's response, so we
            // collect them into a growing list keyed by original header name.
            Map<String, List<String>> h = new LinkedHashMap<>();
            for (MessagePartHeader hdr : payload.getHeaders()) {
                h.computeIfAbsent(hdr.getName(), k -> new ArrayList<>(1)).add(hdr.getValue());
            }
            headers = Collections.unmodifiableMap(h);
        }

        java.util.List<String> rawLabelIds = gmailMessage.getLabelIds();
        java.util.Set<String> labelIds;
        if (rawLabelIds == null || rawLabelIds.isEmpty()) {
            labelIds = java.util.Set.of();
        } else {
            labelIds = java.util.Set.copyOf(new java.util.HashSet<>(rawLabelIds));
        }

        Long sizeEstimate = gmailMessage.getSizeEstimate() == null
            ? null : gmailMessage.getSizeEstimate().longValue();
        BigInteger providerRevision = gmailMessage.getHistoryId();
        return new MailMessage(
            id, threadId, provider, receivedAt, headers, labelIds,
            sizeEstimate, providerRevision, AttachmentState.UNKNOWN);
    }

    private static void rejectBodyData(MessagePart part, String messageId) {
        if (part.getBody() != null
                && part.getBody().getData() != null
                && !part.getBody().getData().isEmpty()) {
            throw new IllegalStateException(
                "Gmail Message.payload.body.data was populated — format is not METADATA. "
                    + "Message id=" + messageId);
        }
        if (part.getParts() != null) {
            part.getParts().forEach(child -> rejectBodyData(child, messageId));
        }
    }

    /** Converts Gmail {@code internalDate} ms-since-unix-epoch into Instant. */
    private static Instant historyIdOrInternalDateToInstant(Long internalDateMs) {
        if (internalDateMs == null || internalDateMs == 0L) {
            return null;
        }
        return Instant.ofEpochMilli(internalDateMs);
    }

    /**
     * Parses a "Date" header value (RFC 2822 / RFC 5322 form) via Java's
     * DateTimeFormatter built-in RFC_1123_DATE_TIME / RFC_822 parsers.
     * Falls back to null on failure (callers already have internalDate so
     * this is used only as a cross-check / display source of truth).
     *
     * <p>Currently unused externally — retained as helper for any future
     * sender-aggregation logic that wants RFC-Date precedence over
     * internalDate.
     */
    @SuppressWarnings("unused")
    private static Instant parseRfc5322DateOrNull(String rawDateHeader) {
        if (rawDateHeader == null || rawDateHeader.isBlank()) return null;
        try {
            // java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME handles
            // the most common forms; a real inbox can have weird ones, so
            // swallow parse errors rather than crash the thread summary.
            return Instant.from(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(rawDateHeader.trim()));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Extracts domain portion from an RFC5322 address.  Handles both "Name <user@domain>"
     *  and plain "user@domain" forms.  Returns null for unparseable inputs. */
    public static String extractDomain(CharSequence rfc5322Address) {
        if (rfc5322Address == null) return null;
        String s = rfc5322Address.toString();
        int lt = s.lastIndexOf('<');
        int at;
        if (lt >= 0) {
            int gt = s.lastIndexOf('>');
            if (gt <= lt) return null;
            String inner = s.substring(lt + 1, gt).trim();
            at = inner.lastIndexOf('@');
            if (at <= 0 || at == inner.length() - 1) return null;
            return lowerOrNull(inner.substring(at + 1).trim());
        } else {
            at = s.lastIndexOf('@');
            if (at <= 0 || at == s.length() - 1) return null;
            return lowerOrNull(s.substring(at + 1).trim());
        }
    }

    private static String lowerOrNull(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    /** Gmail API label "type" integer → human-readable string. */
    private static String labelTypeHuman(String type) {
        // Gmail docs: "system" or "user".  Values are lowercase strings via
        // the JSON model; older versions returned 0/1 ints but modern API
        // returns strings.  We pass through unchanged if not a known token.
        return switch (type) {
            case "system" -> "system-label";
            case "user"   -> "user-label";
            default       -> type;
        };
    }

    /**
     * Small helper representing a single (addLabelIds, removeLabelIds)
     * mutation pair used in batchModify calls.  Immutable; defensive copies
     * of any caller inputs aren't needed because the only builders are
     * private in this file and pass list-of-one literals.
     */
    private static final class LabelDelta {
        final List<String> addLabelIds;
        final List<String> removeLabelIds;

        LabelDelta(List<String> addLabelIds, List<String> removeLabelIds) {
            this.addLabelIds = (addLabelIds == null || addLabelIds.isEmpty())
                ? null : new ArrayList<>(addLabelIds);
            this.removeLabelIds = (removeLabelIds == null || removeLabelIds.isEmpty())
                ? null : new ArrayList<>(removeLabelIds);
        }

        static LabelDelta add(String... ids) {
            return new LabelDelta(Arrays.asList(ids), List.of());
        }

        static LabelDelta remove(String... ids) {
            return new LabelDelta(List.of(), Arrays.asList(ids));
        }
    }
}
