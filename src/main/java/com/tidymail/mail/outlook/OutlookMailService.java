package com.tidymail.mail.outlook;

import com.fasterxml.jackson.databind.JsonNode;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.*;
import com.tidymail.repository.MessageMetadataSyncRepository;
import com.tidymail.service.MessageMetadataMapper;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

@Service("outlookGraphMail")
public class OutlookMailService {
    private static final String ME = "/v1.0/me/";
    private static final String MESSAGE_FIELDS = "id,conversationId,parentFolderId,receivedDateTime,lastModifiedDateTime,"
        + "internetMessageId,internetMessageHeaders,from,toRecipients,subject,isRead,isDraft,hasAttachments,categories,webLink";
    private static final String FOLDER_FIELDS = "?$select=id,displayName,childFolderCount";
    private static final Map<String,String> SYSTEM_FOLDERS = Map.of(
        "inbox","INBOX", "archive","OUTLOOK_ARCHIVE", "sentitems","SENT",
        "drafts","DRAFT", "deleteditems","TRASH", "junkemail","SPAM",
        "outbox","OUTLOOK_EXCLUDED", "searchfolders","OUTLOOK_EXCLUDED");
    private final OutlookGraph graph;
    private final JdbcTemplate jdbc;
    private final MessageMetadataSyncRepository metadata;
    private final MessageMetadataMapper mapper;

    public OutlookMailService(OutlookGraph graph, JdbcTemplate jdbc,
            MessageMetadataSyncRepository metadata, MessageMetadataMapper mapper) {
        this.graph = graph;
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.mapper = mapper;
    }

    public record Folder(String id, String path, String role) {
        public boolean scansMessages() {
            return Set.of("MAIL", "INBOX", "OUTLOOK_ARCHIVE", "SENT").contains(role);
        }
    }

    /** An inventory is published only after every listed child page has succeeded. */
    public List<Folder> inventory(UserAccount account) {
        var roles = new HashMap<String,String>();
        SYSTEM_FOLDERS.forEach((name, role) -> {
            try {
                var folder = get(account, ME + "mailFolders/" + name + "?$select=id");
                roles.put(required(folder, "id"), role);
            } catch (CleanupProviderException error) {
                if (error.status() != 404 || name.equals("inbox") || name.equals("deleteditems")
                        || name.equals("junkemail") || name.equals("drafts") || name.equals("sentitems")) throw error;
            }
        });
        var folders = new LinkedHashMap<String,Folder>();
        var pending = new ArrayDeque<Folder>();
        pending.add(new Folder("", "", "MAIL"));
        while (!pending.isEmpty()) {
            var parent = pending.removeFirst();
            String link = parent.id().isEmpty() ? ME + "mailFolders" + FOLDER_FIELDS
                : ME + "mailFolders/" + segment(parent.id()) + "/childFolders" + FOLDER_FIELDS;
            var visited = new HashSet<String>();
            while (link != null) {
                if (!visited.add(link)) throw new IllegalStateException("OUTLOOK_FOLDER_PAGE_LOOP");
                var page = get(account, link);
                for (var item : values(page)) {
                    String id = required(item, "id");
                    String path = parent.path().isEmpty() ? required(item, "displayName")
                        : parent.path() + " / " + required(item, "displayName");
                    String role = roles.getOrDefault(id, parent.scansMessages() && !parent.role().equals("SENT")
                        ? "MAIL" : "OUTLOOK_EXCLUDED");
                    if (item.path("@odata.type").asText().equals("#microsoft.graph.mailSearchFolder"))
                        role = "OUTLOOK_EXCLUDED";
                    var folder = new Folder(id, path, role);
                    if (folders.putIfAbsent(id, folder) != null)
                        throw new IllegalStateException("OUTLOOK_FOLDER_INVENTORY_CHANGED");
                    if (item.path("childFolderCount").asInt() > 0) pending.add(folder);
                }
                link = text(page, "@odata.nextLink");
            }
        }
        if (folders.values().stream().noneMatch(f -> f.role().equals("INBOX")))
            throw new IllegalStateException("OUTLOOK_INBOX_MISSING");
        // A well-known folder can be hidden from the tree. Its ID still governs exclusions.
        roles.forEach((id, role) -> folders.putIfAbsent(id, new Folder(id, role, role)));
        return List.copyOf(folders.values());
    }

    public Map<String,String> roles(long account) {
        var result = new HashMap<String,String>();
        jdbc.query("SELECT folder_id,role FROM outlook_folders WHERE account_id=?",
            row -> { result.put(row.getString(1), row.getString(2)); }, account);
        return result;
    }

    public List<MailMessage> recent(UserAccount account, Map<String,String> roles) {
        var messages = new LinkedHashMap<String,MailMessage>();
        var observed = Instant.now();
        var inboxId = roles.entrySet().stream().filter(entry -> entry.getValue().equals("INBOX"))
            .findFirst().orElseThrow().getKey();
        for (var path : List.of("messages", "mailFolders/" + segment(inboxId) + "/messages")) {
            var page = get(account, ME + path + "?$orderby=receivedDateTime%20desc&$top=50&$select=" + MESSAGE_FIELDS);
            for (var row : values(page)) {
                var message = project(row, roles, observed);
                if (!Collections.disjoint(message.labelIds(), Set.of("SPAM", "TRASH", "DRAFT", "SENT", "OUTLOOK_EXCLUDED"))) continue;
                messages.put(message.id(), message);
            }
        }
        return List.copyOf(messages.values());
    }

    public record Delta(List<MailMessage> messages, Set<String> reconcile, String cursor, boolean finished) {}

    public Delta delta(UserAccount account, String folder, String cursor, Map<String,String> roles) {
        var observed = Instant.now();
        var page = get(account, cursor == null ? ME + "mailFolders/" + segment(folder)
            + "/messages/delta?$select=" + MESSAGE_FIELDS + "&$orderby=receivedDateTime%20desc" : cursor);
        var messages = new LinkedHashMap<String,MailMessage>();
        var reconcile = new LinkedHashSet<String>();
        for (var row : values(page)) {
            String id = required(row, "id");
            if (row.has("@removed") || !folder.equals(text(row, "parentFolderId"))
                    || !row.hasNonNull("lastModifiedDateTime") || !row.hasNonNull("isRead")
                    || !row.hasNonNull("isDraft")) {
                reconcile.add(id);
            } else {
                var projected = project(row, roles, observed);
                if (messages.put(id, projected) != null) reconcile.add(id);
            }
        }
        String next = text(page, "@odata.nextLink");
        String delta = text(page, "@odata.deltaLink");
        if ((next == null) == (delta == null) || Objects.equals(next, cursor) && next != null)
            throw new IllegalStateException("OUTLOOK_DELTA_LINK_INVALID");
        return new Delta(List.copyOf(messages.values()), Set.copyOf(reconcile), next == null ? delta : next, next == null);
    }

    public MailMessage current(UserAccount account, String id, Map<String,String> roles) {
        var observed = Instant.now();
        try {
            var row = get(account, messagePath(id) + "?$select=" + MESSAGE_FIELDS);
            if (!id.equals(required(row, "id"))) throw new IllegalStateException("OUTLOOK_MESSAGE_ID_CHANGED");
            return project(row, roles, observed);
        } catch (CleanupProviderException error) {
            if (error.status() == 404) return null;
            throw error;
        }
    }

    public List<MailMessage> getCurrentMetadata(UserAccount account, List<String> ids) {
        var roles = roles(account.getId());
        var messages = new ArrayList<MailMessage>();
        for (String id : ids) {
            var current = current(account, id, roles);
            if (current != null) messages.add(current);
        }
        return messages;
    }

    boolean conflictingDelta(long account, MailMessage message) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM message_metadata WHERE account_id=? AND provider_message_id=?
            AND provider_revision=? AND (outlook_folder_id IS DISTINCT FROM ? OR unread IS DISTINCT FROM ?
                OR outlook_categories IS DISTINCT FROM ?))
            """, Boolean.class, account, message.id(), new java.math.BigDecimal(message.providerRevision()),
            message.outlook().folderId(), message.isUnread(), new SqlArrayValue("text",message.outlook().categories().toArray())));
    }

    /** Called in the sync or cleanup transaction, after acquiring the appropriate lease and row locks. */
    public void save(long account, MailMessage message, UUID baseline) {
        var row = mapper.apply(account, message, null, Instant.now());
        row.setFullScanGeneration(baseline);
        var details = Objects.requireNonNull(message.outlook());
        if (metadata.upsertMetadata(row, message.observationStartedAt()) != 0) {
            jdbc.update("""
                UPDATE message_metadata SET outlook_folder_id=?,outlook_categories=?,outlook_web_link=?
                WHERE account_id=? AND provider_message_id=? AND provider='microsoft'
                """, details.folderId(), new SqlArrayValue("text", details.categories().toArray()),
                details.webLink(), account, message.id());
        }
        // A later observation in the same folder is also evidence of presence in this baseline.
        if (baseline != null) jdbc.update("""
            UPDATE message_metadata SET full_scan_generation=?
            WHERE account_id=? AND provider_message_id=? AND outlook_folder_id=?
              AND full_scan_generation IS DISTINCT FROM ?
            """, baseline, account, message.id(), details.folderId(), baseline);
    }

    /** Run once for one claimed target. A move is never repeated by this method. */
    public void apply(UserAccount account, MailMessage before, BulkActionType action, Runnable readCompleted) {
        String source = ME + "mailFolders/" + segment(before.outlook().folderId()) + "/messages/" + segment(before.id());
        if ((action == BulkActionType.MARK_READ || action == BulkActionType.MARK_READ_ARCHIVE) && before.isUnread()) {
            graph.request(account, HttpMethod.PATCH, source, Map.of("isRead", true));
            if (action == BulkActionType.MARK_READ_ARCHIVE) readCompleted.run();
        }
        boolean trash = action == BulkActionType.TRASH;
        boolean archive = action == BulkActionType.ARCHIVE || action == BulkActionType.MARK_READ_ARCHIVE;
        if (trash || archive && !before.labelIds().contains("OUTLOOK_ARCHIVE")) {
            graph.request(account, HttpMethod.POST, source + "/move", Map.of("destinationId", trash ? "deleteditems" : "archive"));
        }
    }

    private MailMessage project(JsonNode row, Map<String,String> roles, Instant observed) {
        String folder = required(row, "parentFolderId");
        if (!row.path("isRead").isBoolean() || !row.path("isDraft").isBoolean())
            throw new IllegalStateException("OUTLOOK_MESSAGE_STATE_MISSING");
        var labels = new LinkedHashSet<String>();
        String role = roles.getOrDefault(folder, "OUTLOOK_EXCLUDED");
        if (!role.equals("MAIL")) labels.add(role);
        if (!row.path("isRead").asBoolean()) labels.add("UNREAD");
        if (row.path("isDraft").asBoolean()) labels.add("DRAFT");
        var headers = new TreeMap<String,List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (var header : row.path("internetMessageHeaders")) {
            String name = text(header, "name"), value = clean(text(header, "value"));
            if (name != null && value != null)
                headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        var from = row.path("from").path("emailAddress");
        String address = clean(text(from, "address")), name = clean(text(from, "name"));
        if (address != null) headers.put("From", List.of(name == null || name.equals(address) ? address
            : "\"" + name.replace("\"", "") + "\" <" + address + ">"));
        if (!headers.containsKey("To")) {
            var recipients = new ArrayList<String>();
            for (var to : row.path("toRecipients")) {
                String recipient = clean(text(to.path("emailAddress"), "address"));
                if (recipient != null) recipients.add(recipient);
            }
            if (!recipients.isEmpty()) headers.put("To", List.of(String.join(", ", recipients)));
        }
        String subject = clean(text(row, "subject")), messageId = clean(text(row, "internetMessageId"));
        if (subject != null) headers.put("Subject", List.of(subject));
        if (messageId != null) headers.put("Message-ID", List.of(messageId));
        var categories = new ArrayList<String>();
        for (var category : row.path("categories")) if (category.isTextual())
            categories.add(clean(category.asText()));
        String received = text(row, "receivedDateTime");
        Instant modified = Instant.parse(required(row, "lastModifiedDateTime"));
        var revision = BigInteger.valueOf(modified.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000))
            .add(BigInteger.valueOf(modified.getNano()));
        return new MailMessage(required(row, "id"), text(row, "conversationId"), "microsoft",
            received == null ? null : Instant.parse(received), headers, labels, null, revision,
            row.path("hasAttachments").asBoolean() ? AttachmentState.PRESENT : AttachmentState.UNKNOWN,
            observed, EmailSnippet.unknown(), new MailMessage.Outlook(folder, List.copyOf(categories),
            safeLink(text(row, "webLink"))));
    }

    static String safeLink(String link) {
        if (link == null) return null;
        try {
            var url = URI.create(link);
            if ("https".equals(url.getScheme()) && url.getHost() != null
                    && Set.of("outlook.live.com", "outlook.office.com", "outlook.office365.com").contains(url.getHost())
                    && url.getRawUserInfo() == null && url.getPort() == -1) return url.toASCIIString();
        } catch (IllegalArgumentException ignored) {
            // Keep browsing available when Graph does not return a usable link.
        }
        return null;
    }

    private JsonNode get(UserAccount account, String path) {
        JsonNode value = graph.request(account, HttpMethod.GET, path, null);
        if (value == null || !value.isObject()) throw new IllegalStateException("OUTLOOK_RESPONSE_MISSING");
        return value;
    }

    private static JsonNode values(JsonNode page) {
        if (!page.path("value").isArray()) throw new IllegalStateException("OUTLOOK_PAGE_MISSING");
        return page.path("value");
    }

    private static String required(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) throw new IllegalStateException("OUTLOOK_FIELD_MISSING");
        return value;
    }

    private static String text(JsonNode row, String field) {
        return row.path(field).isTextual() ? row.path(field).asText() : null;
    }

    private static String clean(String value) {
        return value == null ? null : value.replace("\u0000", "");
    }

    private static String segment(String id) { return UriUtils.encodePathSegment(id, java.nio.charset.StandardCharsets.UTF_8); }
    private static String messagePath(String id) { return ME + "messages/" + segment(id); }

}
