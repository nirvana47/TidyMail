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
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.HistoryMessageDeleted;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.AttachmentState;
import com.tidymail.mail.MailFolderOrLabel;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
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
import java.util.Collections;
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
    private static final org.slf4j.Logger logger=org.slf4j.LoggerFactory.getLogger(GmailMailService.class);


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
                var profile = readWithRetry(account.getId(),1,false,() -> gmail.users().getProfile(USER_ID_ME).execute());
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
        IdPage page = listSyncIds(account, null, maxResults, true, true);
        return hydrateSyncPage(account, page.ids(), true);
    }

    @Override
    public PageResult<MailMessage> listBackfill(UserAccount account, BackfillRequest request) {
        IdPage page = listSyncIds(account, request.pageToken(), request.maxResults(), false, false);
        return PageResult.of(hydrateSyncPage(account, page.ids(), false),
            page.nextPageToken(), request.maxResults());
    }

    /** Lists IDs before hydration so the caller can reuse indexed messages. */
    public IdPage listSyncIds(
            UserAccount account, String pageToken, int pageSize, boolean inbox, boolean initial) {
        if (pageSize < 1 || pageSize > (initial ? 250 : syncConfig.getBackfillPageSize())) {
            throw new IllegalArgumentException("Invalid Gmail sync page size");
        }
        Gmail gmail = clientFactory.buildFor(account);
        java.util.function.Supplier<IdPage> read = () -> {
            try {
                Gmail.Users.Messages.List list = gmail.users().messages().list(USER_ID_ME)
                    .setIncludeSpamTrash(false)
                    .setMaxResults((long) pageSize);
                if (inbox) list.setLabelIds(List.of("INBOX"));
                setPageToken(list, pageToken);
                ListMessagesResponse response = readWithRetry(account.getId(),5,!initial,list::execute);
                if (response.getNextPageToken() != null && response.getNextPageToken().equals(pageToken)) {
                    throw new IllegalStateException("GMAIL_BACKFILL_PAGE_DID_NOT_ADVANCE");
                }
                return new IdPage(ids(response.getMessages()), response.getNextPageToken(),
                    response.getResultSizeEstimate());
            } catch (GoogleJsonResponseException ex) {
                if (pageToken != null && ex.getStatusCode() == 400) {
                    throw syncFailure("GMAIL_LIST_CURSOR_INVALID", ex);
                }
                throw syncFailure("BACKFILL_FAILED", ex);
            } catch (IOException ex) {
                throw syncFailure("BACKFILL_FAILED", ex);
            }
        };
        return initial ? rateLimiter.supplyWithFreshnessPermit(read)
            : rateLimiter.supplyWithBackfillRescanPermit(read);
    }

    public List<MailMessage> hydrateSyncPage(UserAccount account, List<String> ids, boolean initial) {
        if (ids.isEmpty()) return List.of();
        Gmail gmail = clientFactory.buildFor(account);
        java.util.function.Supplier<List<MailMessage>> read = () -> {
            try {
                return fetchMetadata(gmail, ids, account.getProvider(), true, true, account.getId(), !initial);
            } catch (IOException ex) {
                throw syncFailure("BACKFILL_FAILED", ex);
            }
        };
        return initial ? rateLimiter.supplyWithFreshnessPermit(read)
            : rateLimiter.supplyWithBackfillRescanPermit(read);
    }

    public record IdPage(List<String> ids, String nextPageToken, Long estimate) {}

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
                Instant observedAt=Instant.now();
                Set<String> changed=new TreeSet<>(),deleted=new TreeSet<>(),required=new TreeSet<>();
                var deltas=new java.util.ArrayList<ChangePage.LabelChange>();
                Set<String> visited=new java.util.HashSet<>();
                String token=request.pageToken(),completeRevision=null,returnedCursor=request.cursor();
                if(token!=null)visited.add(token);
                int pages=0;
                do {
                    Gmail.Users.History.List list=gmail.users().history().list(USER_ID_ME)
                        .setStartHistoryId(startRevision).setMaxResults((long)request.maxResults());
                    if(token!=null)list.setPageToken(token);
                    ListHistoryResponse response=readWithRetry(account.getId(),2,false,list::execute);
                    pages++;
                    if(response.getHistory()!=null)for(History history:response.getHistory()) {
                        if(history.getId()!=null && completeRevision!=null
                                && history.getId().compareTo(new BigInteger(completeRevision))<0)
                            throw new IllegalStateException("GMAIL_HISTORY_REVISION_REGRESSED");
                        collectChanged(history.getMessagesAdded(),HistoryMessageAdded::getMessage,changed,deleted);
                        if(history.getMessagesAdded()!=null)for(var event:history.getMessagesAdded())
                            if(event.getMessage()!=null && event.getMessage().getId()!=null && !event.getMessage().getId().isBlank())required.add(event.getMessage().getId());
                        var added=new java.util.HashMap<String,Set<String>>();
                        var removed=new java.util.HashMap<String,Set<String>>();
                        if(history.getLabelsAdded()!=null)for(var event:history.getLabelsAdded())
                            collectLabels(event.getMessage(),event.getLabelIds(),added,changed,deleted,required);
                        if(history.getLabelsRemoved()!=null)for(var event:history.getLabelsRemoved())
                            collectLabels(event.getMessage(),event.getLabelIds(),removed,changed,deleted,required);
                        var labelIds=new java.util.HashSet<>(added.keySet());labelIds.addAll(removed.keySet());
                        for(String id:labelIds) {
                            var add=added.getOrDefault(id,Set.of());var remove=removed.getOrDefault(id,Set.of());
                            if(history.getId()==null || !java.util.Collections.disjoint(add,remove))required.add(id);
                            else deltas.add(new ChangePage.LabelChange(id,history.getId(),add,remove));
                        }
                        collectDeleted(history.getMessagesDeleted(),HistoryMessageDeleted::getMessage,changed,deleted);
                        if(history.getId()!=null)completeRevision=history.getId().toString();
                    }
                    if(response.getHistoryId()!=null)returnedCursor=response.getHistoryId().toString();
                    token=response.getNextPageToken();
                    if(token!=null&&!visited.add(token))throw new IllegalStateException("GMAIL_HISTORY_PAGE_DID_NOT_ADVANCE");
                } while(token!=null && pages<syncConfig.getHistoryWindowPages() && changed.size()+deleted.size()<200000);
                // Only a complete window includes all acknowledged cleanup changes predating observedAt.
                // Truncated windows use the existing metadata path and checkpoint the last complete record.
                logger.info("Gmail history window pages={} changed={} deleted={} complete={}",pages,changed.size(),deleted.size(),token==null);
                return new ChangePage(changed,deleted,completeRevision,returnedCursor,token,token!=null,
                    token==null?deltas:List.of(),required,observedAt);
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
                return fetchMetadata(gmail, messageIds, account.getProvider(), true,true,account.getId(),false);
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
                ListMessagesResponse response = readWithRetry(account.getId(),5,true,list::execute);
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
    @Override
    public void markReadOnce(UserAccount account, List<String> messageIds) {
        cleanupOnce(account,messageIds,BulkActionType.MARK_READ);
    }

    @Override
    public void cleanupOnce(UserAccount account,List<String> messageIds,BulkActionType action) {
        if(messageIds==null||messageIds.isEmpty()||messageIds.size()>1000||messageIds.stream().anyMatch(id->id==null||id.isBlank())||new HashSet<>(messageIds).size()!=messageIds.size())
            throw new IllegalArgumentException("Cleanup requires 1–1000 unique exact IDs");
        if(!java.util.Set.of(BulkActionType.MARK_READ,BulkActionType.ARCHIVE,BulkActionType.TRASH,BulkActionType.MARK_READ_ARCHIVE).contains(action))
            throw new IllegalArgumentException("Unsupported dashboard action");
        Gmail gmail=clientFactory.buildFor(account);
        rateLimiter.supplyWithCleanupPermit(() -> {
            rateLimiter.charge(account.getId(),50,false);
            try {
                var body=new BatchModifyMessagesRequest().setIds(List.copyOf(messageIds));
                if(action==BulkActionType.TRASH)body.setAddLabelIds(List.of("TRASH"));
                else body.setRemoveLabelIds(switch (action) {
                    case ARCHIVE -> List.of("INBOX");
                    case MARK_READ -> List.of("UNREAD");
                    case MARK_READ_ARCHIVE -> List.of("UNREAD", "INBOX");
                    case TRASH -> throw new IllegalStateException();
                });
                var request=gmail.users().messages().batchModify(USER_ID_ME,body);
                var http=request.buildHttpRequest();
                http.setNumberOfRetries(0);
                http.setConnectTimeout(5000);http.setReadTimeout(15000);
                http.setIOExceptionHandler(null);
                http.execute().disconnect();
                return null;
            } catch(com.google.api.client.http.HttpResponseException error) {
                boolean throttled=isThrottled(error);
                if(throttled)rateLimiter.recordThrottle(account.getId(),error.getHeaders());
                throw new com.tidymail.mail.CleanupProviderException(error.getStatusCode(),error.getHeaders().getRetryAfter(),throttled);
            } catch(IOException error) {
                // Never convert transport failure into a definite per-target rejection.
                throw new IllegalStateException("Dashboard mutation needs label verification",error);
            }
        });
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    @Override
    public List<MailMessage> getCleanupMetadata(UserAccount account,List<String> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>50||ids.stream().anyMatch(id->id==null||id.isBlank()))
            throw new IllegalArgumentException("Cleanup metadata requires 1–50 exact IDs");
        return rateLimiter.supplyWithCleanupPermit(()->{
            try{return fetchMetadata(clientFactory.buildFor(account),ids,account.getProvider(),true,false,account.getId(),false);}
            catch(com.google.api.client.http.HttpResponseException error){throw new com.tidymail.mail.CleanupProviderException(error.getStatusCode(),error.getHeaders().getRetryAfter());}
            catch(IOException error){throw new IllegalStateException("Cleanup metadata unavailable",error);}
        });
    }

    private static final int MAX_SYNC_RETRIES = 5;

    private List<MailMessage> fetchMetadata(
            Gmail gmail,
            List<String> requestedIds,
            String provider,
            boolean ignoreNotFound) throws IOException {
        return fetchMetadata(gmail,requestedIds,provider,ignoreNotFound,true,null,false);
    }

    private List<MailMessage> fetchMetadata(Gmail gmail,List<String> requestedIds,String provider,boolean ignoreNotFound,boolean retryReads,Long accountId,boolean background) throws IOException {
        if(requestedIds==null||requestedIds.isEmpty())return List.of();
        List<String> uniqueIds=new ArrayList<>(new java.util.LinkedHashSet<>(requestedIds));
        Map<String,Message> fetchedById=new java.util.concurrent.ConcurrentHashMap<>();
        Map<String,Instant> observedAtById=new java.util.concurrent.ConcurrentHashMap<>();
        record Pending(List<String> ids,int retry) {}
        var queue=new java.util.ArrayDeque<Pending>();
        int size=syncConfig.getMetadataBatchSize();
        for(int i=0;i<uniqueIds.size();i+=size)queue.add(new Pending(List.copyOf(uniqueIds.subList(i,Math.min(i+size,uniqueIds.size()))),0));
        while(!queue.isEmpty()) {
            Pending pending=queue.removeFirst();
            BatchRequest batch = retryReads?gmail.batch():gmail.batch(request->{
                var initializer=gmail.getRequestFactory().getInitializer();if(initializer!=null)initializer.initialize(request);
                request.setNumberOfRetries(0);request.setIOExceptionHandler(null);
                request.setConnectTimeout(5000);request.setReadTimeout(15000);
            });
            List<String> retryIds=Collections.synchronizedList(new ArrayList<>());
            List<MetadataFailure> failures=Collections.synchronizedList(new ArrayList<>());
            Instant[] observedSince={Instant.now()};
            for(String messageId:pending.ids()) {
                var metadataRequest=gmail.users().messages().get(USER_ID_ME,messageId)
                    .setFormat("METADATA").setMetadataHeaders(new ArrayList<>(METADATA_HEADERS));
                metadataRequest.setFields("id,threadId,internalDate,historyId,labelIds,sizeEstimate,snippet,payload(headers)");
                metadataRequest.queue(batch,new JsonBatchCallback<>() {
                    @Override public void onSuccess(Message message,HttpHeaders headers) {
                        if(!messageId.equals(message.getId()))throw new IllegalStateException("GMAIL_METADATA_ID_MISMATCH");
                        fetchedById.put(messageId,message);observedAtById.put(messageId,observedSince[0]);
                    }
                    @Override public void onFailure(GoogleJsonError error,HttpHeaders headers) {
                        int code=error==null?0:error.getCode();
                        if(ignoreNotFound&&code==404)return;
                        boolean retryable=retryableReadFailure(code,error);
                        failures.add(new MetadataFailure(code,retryable,headers));
                        if(retryable)retryIds.add(messageId);
                        if(code==429||(code==403&&retryable))rateLimiter.recordThrottle(accountId,headers);
                    }
                });
            }
            rateLimiter.charge(accountId,pending.ids().size()*20,background);
            rateLimiter.paceMetadataBatch();observedSince[0]=Instant.now();
            try{batch.execute();}
            catch(GoogleJsonResponseException error) {
                if(error.getStatusCode()==429||(error.getStatusCode()==403&&retryableReadFailure(403,error.getDetails())))rateLimiter.recordThrottle(accountId,error.getHeaders());
                if(!retryReads||!retryableReadFailure(error.getStatusCode(),error.getDetails())||pending.retry()>=MAX_SYNC_RETRIES)throw error;
                failures.add(new MetadataFailure(error.getStatusCode(),true,error.getHeaders()));
                pending.ids().stream().filter(id->!fetchedById.containsKey(id)).forEach(retryIds::add);
            }
            if(!retryReads&&!failures.isEmpty()) {
                var failure=failures.stream().filter(f->f.status()==401||f.status()==403).findFirst().orElse(failures.getFirst());
                var guidance=failures.stream().map(MetadataFailure::headers).filter(h->h!=null&&h.getRetryAfter()!=null)
                    .max(java.util.Comparator.comparingLong(GmailMailService::retryAfterSeconds)).orElse(null);
                throw new com.tidymail.mail.CleanupProviderException(failure.status(),guidance==null?null:guidance.getRetryAfter());
            }
            var fatal=failures.stream().filter(f->!f.retryable()).findFirst().orElse(null);
            if(fatal!=null)throw new IOException("Gmail metadata request failed with status "+fatal.status());
            if(retryIds.isEmpty())continue;
            if(pending.retry()>=MAX_SYNC_RETRIES)throw new IOException("Gmail metadata retries exhausted after temporary provider errors");
            rateLimiter.pauseBeforeRetry(pending.retry(),failures.stream().map(MetadataFailure::headers)
                .filter(h->h!=null&&h.getRetryAfter()!=null).max(java.util.Comparator.comparingLong(GmailMailService::retryAfterSeconds)).orElse(null));
            var remaining=retryIds.stream().distinct().toList();
            for(int i=0;i<remaining.size();i+=10)queue.addFirst(new Pending(List.copyOf(remaining.subList(i,Math.min(i+10,remaining.size()))),pending.retry()+1));
        }

        List<MailMessage> messages=new ArrayList<>(fetchedById.size());
        for(String messageId:uniqueIds) {
            Message message=fetchedById.get(messageId);
            if(message!=null)messages.add(toMailMessage(provider,message).observedSince(observedAtById.get(messageId)));
            else if(!ignoreNotFound)throw new IOException("Gmail metadata response was incomplete");
        }
        return List.copyOf(messages);
    }

    private static boolean isThrottled(com.google.api.client.http.HttpResponseException error) {
        if(error.getStatusCode()==429)return true;
        String content=error.getContent();
        return error.getStatusCode()==403&&content!=null&&(content.contains("rateLimitExceeded")||content.contains("userRateLimitExceeded")||content.contains("RESOURCE_EXHAUSTED"));
    }

    private record MetadataFailure(int status, boolean retryable, HttpHeaders headers) {}

    private static long retryAfterSeconds(HttpHeaders headers) {
        try {
            return Long.parseLong(headers.getRetryAfter());
        } catch (NumberFormatException exception) {
            try {
                return java.time.ZonedDateTime.parse(headers.getRetryAfter(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                    - Instant.now().getEpochSecond();
            } catch (java.time.format.DateTimeParseException ignored) {
                return 0;
            }
        }
    }

    private static boolean retryableReadFailure(int status, GoogleJsonError error) {
        if (status == 429 || status >= 500 && status <= 599) return true;
        return status == 403 && error != null && error.getErrors() != null
            && error.getErrors().stream().anyMatch(info ->
                "rateLimitExceeded".equals(info.getReason())
                    || "userRateLimitExceeded".equals(info.getReason()));
    }

    @FunctionalInterface
    private interface SyncRead<T> {
        T execute() throws IOException;
    }

    /** Retry read requests only; mailbox mutations never pass through this helper. */
    private <T> T readWithRetry(Long accountId,int cost,boolean background,SyncRead<T> read) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                rateLimiter.charge(accountId,cost,background);
                return read.execute();
            } catch (GoogleJsonResponseException exception) {
                if (attempt >= MAX_SYNC_RETRIES
                        || !retryableReadFailure(exception.getStatusCode(), exception.getDetails())) {
                    throw exception;
                }
                if(exception.getStatusCode()==429||exception.getStatusCode()==403)rateLimiter.recordThrottle(accountId,exception.getHeaders());
                rateLimiter.pauseBeforeRetry(attempt, exception.getHeaders());
            }
        }
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

    private static void collectLabels(Message message,List<String> labels,java.util.Map<String,Set<String>> output,
            Set<String> changed,Set<String> deleted,Set<String> required) {
        if(message==null || message.getId()==null || message.getId().isBlank())return;
        String id=message.getId();
        if(deleted.contains(id))return;
        changed.add(id);
        if(labels==null || labels.isEmpty() || labels.stream().anyMatch(v->v==null || v.isBlank()))required.add(id);
        else output.computeIfAbsent(id,key->new java.util.HashSet<>()).addAll(labels);
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
        if (rawLabelIds == null) {
            // Unknown membership must not become known empty (archived received mail).
            labelIds = null;
        } else if (rawLabelIds.isEmpty()) {
            labelIds = java.util.Set.of();
        } else {
            labelIds = java.util.Set.copyOf(new java.util.HashSet<>(rawLabelIds));
        }

        Long sizeEstimate = gmailMessage.getSizeEstimate() == null
            ? null : gmailMessage.getSizeEstimate().longValue();
        BigInteger providerRevision = gmailMessage.getHistoryId();
        return new MailMessage(
            id, threadId, provider, receivedAt, headers, labelIds,
            sizeEstimate, providerRevision, AttachmentState.UNKNOWN, null,
            com.tidymail.mail.EmailSnippet.observed(gmailMessage.getSnippet()));
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

}
