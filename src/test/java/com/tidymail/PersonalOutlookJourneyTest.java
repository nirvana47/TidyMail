package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.entity.UserAccount;
import com.tidymail.job.DashboardCleanupWorker;
import com.tidymail.mail.CleanupProviderException;
import com.tidymail.mail.outlook.OutlookGraph;
import com.tidymail.mail.outlook.OutlookSyncService;
import com.tidymail.repository.DashboardRepository;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"MICROSOFT_CLIENT_ID=fixture-client", "MICROSOFT_CLIENT_SECRET=fixture-secret"})
class PersonalOutlookJourneyTest extends PostgresJourneyTest {
    private static final String ME = "/v1.0/me/";
    private static final String ROOT = "https://graph.microsoft.com" + ME;
    private static final String OUTLOOK = "personal@example.test";
    private static final String LEASE = "outlook-fixture";
    private static final String WEB_LINK = "https://outlook.live.com/mail/0/inbox/id/fixture";
    private static final DashboardFilter ALL = DashboardFilter.from(Map.of("scope", "all"));
    private static final DashboardFilter INBOX = DashboardFilter.from(Map.of("scope", "inbox"));

    @MockBean OutlookGraph graph;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired MailboxSyncStateRepository states;
    @Autowired OutlookSyncService sync;
    @Autowired DashboardRepository dashboard;
    @Autowired DashboardPreviewService previews;
    @Autowired DashboardJobService jobs;
    @Autowired DashboardCleanupWorker cleanup;
    @Autowired RecommendationProcessingService labels;
    @Autowired RecommendationService recommendations;

    private long accountId;
    private UserAccount account;
    private GraphFixture fixture;

    @BeforeEach void outlookAccount() {
        accountId = userAccounts.createAccount(OUTLOOK, "microsoft");
        account = accounts.findById(accountId).orElseThrow();
        states.ensureState(accountId, "microsoft");
        var now = Instant.now();
        assertThat(states.acquireLaneLease(accountId, Lane.BACKFILL, LEASE, now.plusSeconds(120), now, 0)).isTrue();
        fixture = new GraphFixture();
        when(graph.request(any(), any(), anyString(), nullable(Object.class))).thenAnswer(invocation ->
            fixture.request(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));
    }

    @Test void consumerAuthorizationAndRetainedAccountSwitchUseStableIdentity() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/microsoft"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"),
                org.hamcrest.Matchers.containsString("code_challenge_method=S256"),
                org.hamcrest.Matchers.containsString("offline_access"),
                org.hamcrest.Matchers.containsString("Mail.ReadWrite"),
                org.hamcrest.Matchers.containsString("prompt=select_account"))));
        importMailbox();
        long foreign = userAccounts.createAccount("other@example.test", "microsoft");
        jdbc.update("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,subject_header,labels,label_evidence_known)
            VALUES(?,'microsoft','move','different@example.test','Foreign mailbox',ARRAY['INBOX'],true)
            """, foreign);
        long gmail = userAccounts.createAccount("linked@example.test");
        jdbc.update("UPDATE user_accounts SET owner_key=? WHERE id=?", account.getOwnerKey(), gmail);
        var auth = authentication(outlookAuth(account));
        var session = new MockHttpSession();
        mockMvc.perform(get("/dashboard").session(session).with(auth))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("data-provider=\"microsoft\"")));
        mockMvc.perform(post("/dashboard/accounts/switch").param("account", Long.toString(foreign))
                .session(session).with(auth).with(csrfToken())).andExpect(status().isNotFound());
        mockMvc.perform(post("/dashboard/accounts/switch").param("account", Long.toString(gmail))
                .session(session).with(auth).with(csrfToken())).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/dashboard/api/page").session(session).with(auth))
            .andExpect(jsonPath("$.sync.provider").value("google")).andExpect(jsonPath("$.summary.messages").value(0));
        mockMvc.perform(get("/dashboard/api/page").session(session).with(auth)
                .header("X-TidyMail-Account", Long.toString(accountId))).andExpect(status().isConflict());
        mockMvc.perform(get("/dashboard/api/page").with(auth))
            .andExpect(jsonPath("$.sync.provider").value("microsoft")).andExpect(jsonPath("$.summary.messages").value(2));
        var wrongSubject = new UserAccount();
        wrongSubject.setProviderAccountId("wrong");
        wrongSubject.setEmail(OUTLOOK);
        mockMvc.perform(get("/dashboard/api/page").with(authentication(outlookAuth(wrongSubject))))
            .andExpect(status().isUnauthorized());
    }

    @Test void resumesAnInterruptedBaselineAndReconcilesMovesDeletesAndFolderChanges() {
        unit();
        unit();
        assertThat(count(INBOX)).isEqualTo(2);
        assertThat(states.findByAccountId(accountId).orElseThrow().incrementalLastSuccessAt()).isNull();
        unit();
        fixture.failNextInboxPage = true;
        assertThatThrownBy(this::unit).isInstanceOf(CleanupProviderException.class);
        assertThat(count(INBOX)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT cursor FROM outlook_folders WHERE account_id=? AND folder_id='in'", String.class, accountId))
            .isEqualTo(ROOT + "mailFolders/in/messages/delta?$skiptoken=in2");
        drain();
        assertThat(count(ALL)).isEqualTo(3);
        assertThat(states.findByAccountId(accountId).orElseThrow().incrementalLastSuccessAt()).isNotNull();
        UUID move = id("move");
        assertThat(detail(move).outlookFolder()).isEqualTo("Inbox");
        UUID custom = id("comment");
        assertThat(detail(custom).outlookFolder()).isEqualTo("Projects / Nested");
        assertThat(detail(custom).outlookCategories()).containsExactly("Blue");
        assertThat(detail(custom).outlookWebLink()).isEqualTo(WEB_LINK);

        fixture.round = 2;
        fixture.move("move", "sub");
        fixture.messages.remove("gone");
        fixture.rename = true;
        drain();
        assertThat(count(INBOX)).isZero();
        assertThat(count(ALL)).isEqualTo(2);
        assertThat(id("move")).isEqualTo(move);
        assertThat(detail(move).outlookFolder()).isEqualTo("Renamed / Nested");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE account_id=? AND provider_message_id='gone'",
            Integer.class, accountId)).isZero();

        fixture.round = 3;
        fixture.removeNested = true;
        fixture.failInventory = true;
        fixture.move("move", "arc");
        fixture.messages.remove("comment");
        assertThatThrownBy(this::unit).isInstanceOf(CleanupProviderException.class);
        assertThat(count(ALL)).isEqualTo(2);
        assertThat(detail(move).outlookFolder()).isEqualTo("Renamed / Nested");
        drain();
        assertThat(count(ALL)).isEqualTo(1);
        assertThat(id("move")).isEqualTo(move);
        assertThat(detail(move).outlookFolder()).isEqualTo("Archive");
    }

    @Test void sizesResumeSeparatelyAndDriveTheExactLargeMailPreview() throws Exception {
        fixture.failSizePage = true;
        drain();
        assertThat(states.findByAccountId(accountId).orElseThrow().incrementalLastSuccessAt()).isNotNull();
        assertThat(detail(id("move")).sizeEstimate()).isEqualTo(2_000_000);
        assertThat(detail(id("comment")).sizeEstimate()).isNull();
        assertThat(jdbc.queryForObject("SELECT next_link FROM outlook_size_scan WHERE account_id=?", String.class, accountId))
            .isEqualTo(ROOT+"messages?$skiptoken=sizes2");
        fixture.round = 4;
        drain();
        assertThat(detail(id("move")).sizeEstimate()).isEqualTo(2_000_000);
        assertThat(detail(id("comment")).sizeEstimate()).isEqualTo(3_000_000);
        var filter = DashboardFilter.from(Map.of("scope","all","suggestion","large","sort","size"));
        assertThat(count(filter)).isEqualTo(2);
        assertThat(dashboard.page(accountId, filter).groups().getFirst().knownBytes()).isEqualTo(3_000_000);
        var preview = previews.create(accountId, new DashboardPreviewService.Request(filter,
            List.of(id("move"),id("gone"),id("sent-item")), "TRASH", dashboard.revision(accountId)));
        assertThat(preview.effective()).isEqualTo(1);
        assertThat(preview.effectiveBytes()).isEqualTo(2_000_000);
        assertThat(fixture.writes).isEmpty();
        fixture.put("new","in","new@example.test","New message");
        fixture.sizes.put("new", 1_500_000L);
        drain();
        assertThat(detail(id("new")).sizeEstimate()).isEqualTo(1_500_000);
        assertThat(count(filter)).isEqualTo(3);
        mockMvc.perform(get("/dashboard").with(authentication(outlookAuth(account))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"suggestionsView\" aria-pressed=\"false\"")));
    }

    @Test void localLabelsUseOnlySavedOutlookEvidence() {
        importMailbox();
        assertThat(labels.status(accountId).enabled()).isTrue();
        assertThat(labels.runOnce()).isTrue();
        var view = recommendations.read(accountId, List.of(id("comment"))).getFirst();
        assertThat(view.labelReasons()).containsKeys("DOCUMENT_COMMENT", "OLDER_DOCUMENT_COMMENT");
        assertThat(recommendations.prepareSemantic(accountId, List.of(id("comment")))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_incoming WHERE account_id=?", Integer.class, accountId)).isZero();
        assertThat(count(DashboardFilter.from(Map.of("scope","all","label","OLDER_DOCUMENT_COMMENT")))).isEqualTo(1);
        assertThat(recommendations.read(accountId, List.of(id("gone"))).getFirst().labelReasons())
            .containsKey("SECURITY").doesNotContainKey("DOCUMENT_COMMENT");
    }

    @Test void calendarFilterMatchesNativeMeetingsAndGoogleInvites() {
        fixture.put("meeting","in","organizer@example.test","Quarterly planning")
            .put("@odata.type","#microsoft.graph.eventMessageRequest");
        fixture.put("cancel","in","organizer@example.test","Canceled: Quarterly planning")
            .put("@odata.type","#microsoft.graph.eventMessage");
        fixture.put("google-invite","in","organizer@example.test",
            "Updated invitation: Planning @ Mon Nov 9, 2026 (organizer@example.test)");
        fixture.put("accepted","in","guest@example.test","Accepted: Quarterly planning")
            .put("@odata.type","#microsoft.graph.eventMessageResponse").put("meetingMessageType","meetingAccepted");
        importMailbox();
        assertThat(labels.runOnce()).isTrue();

        var filter = DashboardFilter.from(Map.of("scope","all","labels","CALENDAR_INVITE"));
        assertThat(count(filter)).isEqualTo(3);
        assertThat(recommendations.read(accountId, List.of(id("meeting"), id("cancel"), id("google-invite"))))
            .allSatisfy(view -> assertThat(view.labelReasons()).containsKey("CALENDAR_INVITE").doesNotContainKey("PAST_EVENT"));
        assertThat(recommendations.read(accountId, List.of(id("accepted"))).getFirst().labelReasons())
            .doesNotContainKey("CALENDAR_INVITE");
        assertThat(fixture.writes).isEmpty();
    }

    @Test void partialAndAmbiguousCombinedActionsReconcileWithoutRepeatingTheMove() {
        importMailbox();
        fixture.rejectMove = true;
        UUID job = confirm("move", "MARK_READ_ARCHIVE");
        cleanup.runOnce();
        assertThat(jobs.get(accountId, job).partialRead()).isEqualTo(1);
        assertThat(jobs.get(accountId, job).failed()).isEqualTo(1);
        assertThat(detail(id("move")).unread()).isFalse();
        assertThat(fixture.writes).containsExactly("PATCH:move", "POST:move");
        assertThat(confirm("move", "MARK_READ_ARCHIVE")).isNotEqualTo(job);
        fixture.loseMoveResponse = true;
        fixture.failMessageReadAfterMove = true;
        cleanup.runOnce();
        assertThat(fixture.writes).containsExactly("PATCH:move", "POST:move", "POST:move");
        var retry = jobs.list(accountId).getFirst();
        assertThat(retry.unresolved()).isEqualTo(1);
        assertThatThrownBy(() -> confirm("move", "TRASH")).hasMessageContaining("active or unresolved");
        jdbc.update("UPDATE cleanup_jobs SET next_attempt_at=now() WHERE public_id=?", retry.id());
        cleanup.runOnce();
        assertThat(jobs.get(accountId, retry.id()).changed()).isEqualTo(1);
        assertThat(fixture.writes).containsExactly("PATCH:move", "POST:move", "POST:move");
        assertThat(detail(id("move")).outlookFolder()).isEqualTo("Archive");
    }

    @Test void exactConfirmedActionsRespectProtectionAndMoveToProviderFolders() {
        importMailbox();
        jdbc.update("INSERT INTO protected_senders(account_id,sender_address,source) VALUES(?,?,'USER')", accountId, "alerts@example.test");
        assertThatThrownBy(() -> confirm("gone", "TRASH")).hasMessageContaining("No eligible changes");
        UUID trash = confirm("comment", "TRASH");
        assertThat(fixture.writes).isEmpty();
        cleanup.runOnce();
        assertThat(jobs.get(accountId, trash).changed()).isEqualTo(1);
        assertThat(fixture.messages.get("comment").path("parentFolderId").asText()).isEqualTo("del");
        assertThat(count(ALL)).isEqualTo(2);

        UUID read = confirm("move", "MARK_READ");
        cleanup.runOnce();
        assertThat(jobs.get(accountId, read).changed()).isEqualTo(1);
        assertThat(detail(id("move")).unread()).isFalse();
        UUID archive = confirm("move", "ARCHIVE");
        cleanup.runOnce();
        assertThat(jobs.get(accountId, archive).changed()).isEqualTo(1);
        assertThat(detail(id("move")).outlookFolder()).isEqualTo("Archive");
        assertThat(fixture.writes).containsExactly("POST:comment", "PATCH:move", "POST:move");
    }

    private OAuth2AuthenticationToken outlookAuth(UserAccount user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,
            Map.of("sub", user.getProviderAccountId(), "email", user.getEmail()), "sub"), authorities, "microsoft");
    }

    private UUID confirm(String message, String action) {
        var preview = previews.create(accountId, new DashboardPreviewService.Request(ALL,
            List.of(id(message)), action, dashboard.revision(accountId)));
        return jobs.confirm(accountId, preview.id()).id();
    }

    private void importMailbox() { drain(); }
    private boolean unit() { return sync.synchronize(account, LEASE).moreWork(); }
    private void drain() {
        for (int i=0; i<40; i++) if (!unit()) return;
        fail("Outlook round did not complete");
    }
    private long count(DashboardFilter filter) { return ((Number)dashboard.page(accountId, filter).summary().get("messages")).longValue(); }
    private UUID id(String providerId) {
        return jdbc.queryForObject("SELECT dashboard_message_id FROM message_metadata WHERE account_id=? AND provider_message_id=?",
            UUID.class, accountId, providerId);
    }
    private DashboardRepository.Message detail(UUID id) {
        return dashboard.page(accountId, ALL).groups().stream()
            .flatMap(group -> dashboard.details(accountId, ALL, group.id(), 0).messages().stream())
            .filter(message -> message.id().equals(id)).findFirst().orElseThrow();
    }

    private class GraphFixture {
        final Map<String,com.fasterxml.jackson.databind.node.ObjectNode> messages = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        final Map<String,Long> sizes = new HashMap<>(Map.of("move",2_000_000L,"gone",1_000_000L,
            "comment",3_000_000L,"sent-item",4_000_000L,"draft-item",5_000_000L));
        final Map<String,String> wellKnown = Map.of("inbox","in", "archive","arc", "sentitems","sent",
            "drafts","draft", "deleteditems","del", "junkemail","junk", "outbox","out", "searchfolders","search");
        int round = 1;
        int revision = 1;
        boolean failNextInboxPage, rename, removeNested, failInventory, rejectMove, loseMoveResponse, failMessageReadAfterMove;
        boolean afterMove, failSizePage;

        GraphFixture() {
            put("move","in","user@example.test","Project update");
            put("gone","in","alerts@example.test","Password reset requested");
            put("comment","sub","no-reply@sharepointonline.com","Author mentioned you in \"Quarterly report\".");
            put("sent-item","sent","personal@example.test","Your receipt");
            put("draft-item","in","personal@example.test","Draft").put("isDraft",true);
            put("junk-item","junk","personal@example.test","Spam");
            put("deleted-item","del","personal@example.test","Deleted");
        }

        JsonNode request(UserAccount owner, HttpMethod method, String url, Object payload) {
            assertThat(owner.getId()).isEqualTo(accountId);
            String path = url.replace("https://graph.microsoft.com","").split("\\?",2)[0];
            if (!method.equals(HttpMethod.GET)) {
                var parts = path.split("/");
                String id = parts[parts.length - (method == HttpMethod.POST ? 2 : 1)];
                var message = messages.get(id);
                assertThat(parts[4]).isEqualTo(message.path("parentFolderId").asText());
                writes.add(method+":"+id);
                if (method == HttpMethod.PATCH) {
                    assertThat(payload).isEqualTo(Map.of("isRead",true));
                    message.put("isRead",true).put("lastModifiedDateTime", modified());
                    return message.deepCopy();
                }
                if (rejectMove) { rejectMove=false; throw new CleanupProviderException(403,null); }
                String destination = ((Map<?,?>)payload).get("destinationId").toString();
                move(id,wellKnown.get(destination));
                afterMove=true;
                if (loseMoveResponse) throw new CleanupProviderException(0,null);
                return message.deepCopy();
            }
            if (path.equals(ME+"mailFolders")) {
                if (failInventory) { failInventory=false; throw new CleanupProviderException(503,null); }
                var items = new ArrayList<JsonNode>();
                items.add(folder("projects",rename?"Renamed":"Projects",removeNested?0:1));
                for(var entry : wellKnown.entrySet()) items.add(folder(entry.getValue(), switch(entry.getKey()) {
                    case "inbox" -> "Inbox"; case "archive" -> "Archive"; default -> entry.getKey();
                },0));
                return page(items,null,null);
            }
            if (path.equals(ME+"mailFolders/projects/childFolders"))
                return page(List.of(folder("sub","Nested",0)),null,null);
            if (path.equals(ME+"messages")) {
                if (url.contains("singleValueExtendedProperties"))
                    return page(List.of(size("move")),ROOT+"messages?$skiptoken=sizes2",null);
                if (url.contains("sizes2")) {
                    if (failSizePage) { failSizePage=false; throw new CleanupProviderException(503,null); }
                    return page(messages.keySet().stream().filter(id -> !id.equals("move")).map(this::size).toList(),null,null);
                }
                return page(messages.values().stream().map(JsonNode.class::cast).toList(),null,null);
            }
            if (path.startsWith(ME+"messages/")) {
                if (afterMove && failMessageReadAfterMove) {
                    failMessageReadAfterMove=false;
                    throw new CleanupProviderException(503,null);
                }
                var message = messages.get(path.substring((ME+"messages/").length()));
                if (message == null) throw new CleanupProviderException(404,null);
                return url.contains("singleValueExtendedProperties") ? size(message.path("id").asText()) : message.deepCopy();
            }
            var parts=path.substring((ME+"mailFolders/").length()).split("/");
            String folder=parts[0];
            if(parts.length==1)return json.createObjectNode().put("id",wellKnown.get(folder));
            if(parts.length==2)return page(in(folder),null,null);
            String completed = ROOT + "mailFolders/" + folder + "/messages/delta?$deltatoken=" + round;
            if (round==1 && folder.equals("in")) {
                if (!url.contains("skiptoken"))
                    return page(List.of(messages.get("move")),ROOT+"mailFolders/in/messages/delta?$skiptoken=in2",null);
                if (failNextInboxPage) { failNextInboxPage=false; throw new CleanupProviderException(503,null); }
                return page(List.of(messages.get("gone"),messages.get("draft-item")),null,completed);
            }
            if(round==2 && folder.equals("in"))
                return page(List.of(removed("move"),removed("gone")),null,completed);
            return page(in(folder),null,completed);
        }

        JsonNode size(String id) {
            var row = json.createObjectNode().put("id",id);
            Long bytes = sizes.get(id);
            if (bytes != null) row.putArray("singleValueExtendedProperties").addObject()
                .put("id","Integer 0xe08").put("value",bytes.toString());
            return row;
        }

        void move(String id, String folder) { messages.get(id).put("parentFolderId",folder).put("lastModifiedDateTime",modified()); }
        String modified() { return Instant.parse("2026-10-01T00:00:00Z").plusSeconds(revision++).toString(); }

        com.fasterxml.jackson.databind.node.ObjectNode put(String id, String folder, String sender, String subject) {
            var message=json.createObjectNode().put("id",id).put("parentFolderId",folder).put("subject",subject)
                .put("lastModifiedDateTime",modified()).put("receivedDateTime","2025-09-01T00:00:00Z")
                .put("isRead",false).put("isDraft",false).put("hasAttachments",false).put("webLink",WEB_LINK);
            message.putObject("from").putObject("emailAddress").put("address",sender);
            message.putArray("categories").add("Blue");
            message.putArray("internetMessageHeaders");
            messages.put(id,message);
            return message;
        }
        JsonNode removed(String id) {
            var removed=json.createObjectNode().put("id",id);
            removed.putObject("@removed").put("reason","deleted");
            return removed;
        }
        JsonNode folder(String id,String name,int childCount) {
            return json.createObjectNode().put("id",id).put("displayName",name).put("childFolderCount",childCount);
        }
        List<JsonNode> in(String folder) {
            return messages.values().stream().filter(message -> message.path("parentFolderId").asText().equals(folder))
                .map(JsonNode.class::cast).toList();
        }
        JsonNode page(List<JsonNode> values, String next, String delta) {
            var page=json.createObjectNode();
            page.putArray("value").addAll(values);
            if(next!=null)page.put("@odata.nextLink",next);
            if(delta!=null)page.put("@odata.deltaLink",delta);
            return page.deepCopy();
        }
    }
}
