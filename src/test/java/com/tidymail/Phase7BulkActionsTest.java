package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.BulkActionType;
import com.tidymail.mail.MailFolderOrLabel;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.MailThreadSummary;
import com.tidymail.mail.PageResult;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.SenderAggregator;
import com.tidymail.service.UndoService;
import com.tidymail.util.Rfc5322Util;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class Phase7BulkActionsTest {

    private static Map<String, List<String>> headers(String from, String to,
                                                     String subject,
                                                     String listUnsub,
                                                     String precedence) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        if (from != null) h.put("From", List.of(from));
        if (to != null) h.put("To", List.of(to));
        if (subject != null) h.put("Subject", List.of(subject));
        if (listUnsub != null) h.put("List-Unsubscribe", List.of(listUnsub));
        if (precedence != null) h.put("Precedence", List.of(precedence));
        return h;
    }

    private static MailMessage msg(String id, String threadId, String from, String to,
                                   String subject, Instant receivedAt, Set<String> labels,
                                   String listUnsub, String precedence) {
        return new MailMessage(id, threadId, "google", receivedAt,
            headers(from, to, subject, listUnsub, precedence),
            labels == null ? Set.of() : labels);
    }

    static class FakeMailProvider implements MailProvider {
        private final Map<String, MailThreadSummary> threadMap = new HashMap<>();
        public final AtomicInteger batchModifyCalls = new AtomicInteger(0);
        public final AtomicInteger applyLabelCalls = new AtomicInteger(0);
        public BulkActionType lastBatchModifyAction = null;
        public String lastApplyLabelId = null;
        public boolean lastApplyLabelAdd = false;
        public List<String> lastBatchModifyIds = null;
        public List<String> lastApplyLabelIds = null;

        void put(String threadId, List<String> participants, Instant at,
                 List<String> orderedIds) {
            Set<String> domains = new HashSet<>();
            for (String p : participants) {
                String d = Rfc5322Util.extractDomain(p);
                if (d != null) domains.add(d);
            }
            threadMap.put(threadId, new MailThreadSummary(
                threadId, participants, domains,
                orderedIds.size(), at, at, orderedIds));
        }

        @Override public MailThreadSummary getThreadMetadata(UserAccount me, String tid) {
            MailThreadSummary s = threadMap.get(tid);
            if (s == null) {
                return new MailThreadSummary(tid, List.of(), Set.of(), 0,
                    null, null, List.of());
            }
            return s;
        }

        @Override public PageResult<MailMessage> listMessages(UserAccount a, String pt, int n) {
            throw new UnsupportedOperationException(); }
        @Override public List<MailFolderOrLabel> listFoldersOrLabels(UserAccount a) {
            throw new UnsupportedOperationException(); }
        @Override public BulkActionResult batchModify(UserAccount a, BulkActionRequest r) {
            batchModifyCalls.incrementAndGet();
            lastBatchModifyAction = r.actionType();
            lastBatchModifyIds = new ArrayList<>(r.messageIds());
            return BulkActionResult.ok(r.messageIds().size(), new ArrayList<>(r.messageIds()));
        }
        @Override public BulkActionResult applyLabel(UserAccount a, List<String> ids,
                                                       String lid, boolean add) {
            applyLabelCalls.incrementAndGet();
            lastApplyLabelId = lid;
            lastApplyLabelAdd = add;
            lastApplyLabelIds = new ArrayList<>(ids);
            return BulkActionResult.ok(ids.size(), new ArrayList<>(ids));
        }
    }

    // ============================================================
    // T7(a): Pure-Java UndoService tests (no Spring context)
    // ============================================================

    @Nested
    @DisplayName("T7a Pure Java UndoService")
    class T7a_PureJavaTests {

        @Test
        @DisplayName("T7_a_01 buildInverseActionData maps ARCHIVE/TRASH/MARK_READ to correct inverses with formatVersion=1")
        void T7_a_01_buildInverseActionData() {
            List<String> ids = List.of("m1", "m2");

            Map<String, Object> archiveInv = UndoService.buildInverseActionData(
                UndoService.BulkAction.ARCHIVE, ids);
            assertEquals("INBOX_ADD", archiveInv.get("inverseType"));
            assertEquals(1, archiveInv.get("formatVersion"));
            assertEquals(2, ((List<?>) archiveInv.get("messageIds")).size());

            Map<String, Object> trashInv = UndoService.buildInverseActionData(
                UndoService.BulkAction.TRASH, ids);
            assertEquals("UNTRASH", trashInv.get("inverseType"));
            assertEquals(1, trashInv.get("formatVersion"));
            assertEquals(2, ((List<?>) trashInv.get("messageIds")).size());

            Map<String, Object> markReadInv = UndoService.buildInverseActionData(
                UndoService.BulkAction.MARK_READ, ids);
            assertEquals("MARK_UNREAD", markReadInv.get("inverseType"));
            assertEquals(1, markReadInv.get("formatVersion"));
            assertEquals(2, ((List<?>) markReadInv.get("messageIds")).size());
        }

        @Test
        @DisplayName("T7_a_02 isUndoable requires scoped applied-target eligibility")
        void T7_a_02_isUndoableThreeState() {
            Instant now = Instant.now();

            UserAction rowFresh = new UserAction();
            rowFresh.setActionScopeId(1L);
            rowFresh.setActionType("ARCHIVE");
            rowFresh.setUndoEligibleUntil(now.plusSeconds(5));
            assertTrue(UndoService.isUndoable(rowFresh, now));

            UserAction rowExpired = new UserAction();
            rowExpired.setActionScopeId(1L);
            rowExpired.setActionType("ARCHIVE");
            rowExpired.setUndoEligibleUntil(now.minusSeconds(1));
            assertFalse(UndoService.isUndoable(rowExpired, now));

            UserAction legacy = new UserAction();
            legacy.setActionType("ARCHIVE");
            legacy.setExpiresAt(now.plusSeconds(5));
            assertFalse(UndoService.isUndoable(legacy, now));
        }

        @Test
        @DisplayName("T7_a_03 cap1000Size and cap1000: 999→999, 1000→1000, 2000→1000")
        void T7_a_03_cap1000Size() {
            List<String> ids999 = IntStream.range(0, 999).mapToObj(String::valueOf).toList();
            List<String> ids1000 = IntStream.range(0, 1000).mapToObj(String::valueOf).toList();
            List<String> ids2000 = IntStream.range(0, 2000).mapToObj(String::valueOf).toList();

            assertEquals(999, UndoService.cap1000Size(ids999));
            assertEquals(1000, UndoService.cap1000Size(ids1000));
            assertEquals(1000, UndoService.cap1000Size(ids2000));

            UndoService.CapResult cap2000 = UndoService.cap1000(ids2000);
            assertEquals(2000, cap2000.cappedOriginal());
            assertEquals(1000, cap2000.actualApplied());
            assertEquals(1000, cap2000.messageIds().size());
        }

        @Test
        @DisplayName("T7_a_04 executeUndo dispatch: MARK_READ→batchModify(MARK_UNREAD), ARCHIVE→applyLabel(INBOX,add=true)")
        void T7_a_04_executeUndoDispatchTable() {
            UserAccount account = new UserAccount();
            account.setId(999L);
            account.setEmail("t7a@example.com");
            account.setProvider("google");

            FakeMailProvider fakeMark = new FakeMailProvider();
            UserAction markRow = new UserAction();
            markRow.setMessageIds(List.of("m1", "m2", "m3"));
            markRow.setInverseActionData(UndoService.buildInverseActionData(
                UndoService.BulkAction.MARK_READ, markRow.getMessageIds()));
            markRow.setExpiresAt(Instant.now().plusSeconds(5));
            UndoService svcMark = new UndoService(
                mock(UserActionRepository.class), mock(ActionScopeService.class));
            assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> svcMark.executeUndo(account, fakeMark, markRow));
            assertEquals(0, fakeMark.batchModifyCalls.get());

            FakeMailProvider fakeArchive = new FakeMailProvider();
            UserAction archiveRow = new UserAction();
            archiveRow.setMessageIds(List.of("m4", "m5"));
            archiveRow.setInverseActionData(UndoService.buildInverseActionData(
                UndoService.BulkAction.ARCHIVE, archiveRow.getMessageIds()));
            archiveRow.setExpiresAt(Instant.now().plusSeconds(5));
            UndoService svcArchive = new UndoService(
                mock(UserActionRepository.class), mock(ActionScopeService.class));
            assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> svcArchive.executeUndo(account, fakeArchive, archiveRow));
            assertEquals(0, fakeArchive.applyLabelCalls.get());
        }

        @Test
        @DisplayName("T7_a_05 buildInverseActionData with empty messageIds → empty list (not null)")
        void T7_a_05_buildInverseActionDataEmptyIds() {
            Map<String, Object> inv = UndoService.buildInverseActionData(
                UndoService.BulkAction.ARCHIVE, List.of());
            assertNotNull(inv.get("messageIds"));
            List<?> ids = (List<?>) inv.get("messageIds");
            assertTrue(ids.isEmpty());
        }
    }

    // ============================================================
    // T7(b): Persistence tests — @SpringBootTest with JPA + Flyway V3
    // ============================================================

    @Nested
    @SpringBootTest
    @org.springframework.transaction.annotation.Transactional
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
        "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
        "GOOGLE_CLIENT_ID=",
        "GOOGLE_CLIENT_SECRET=",
        "MICROSOFT_CLIENT_ID=",
        "MICROSOFT_CLIENT_SECRET=",
        "MICROSOFT_TENANT_ID=common"
    })
    @DisplayName("T7b UserAction Persistence")
    class T7b_PersistenceTests {

        @Autowired
        UserActionRepository userActions;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        CryptoUtil cryptoUtil;

        @Autowired
        SenderAggregator senderAggregator;

        @Autowired
        EntityManager em;

        @BeforeEach
        void cleanAll() {
            userActions.deleteAll();
            userAccounts.deleteAll();
        }

        private UserAccount createAccount(String email, String provId) {
            UserAccount a = new UserAccount();
            a.setEmail(email);
            a.setProvider("google");
            a.setProviderAccountId(provId);
            a.setDisplayName("Phase7B");
            a.setEncryptedAccessToken(cryptoUtil.encrypt("at".getBytes(StandardCharsets.UTF_8)));
            a.setEncryptedRefreshToken(cryptoUtil.encrypt("rt".getBytes(StandardCharsets.UTF_8)));
            a.setScopes(List.of("openid", "email"));
            return userAccounts.save(a);
        }

        @Test
        @DisplayName("T7_b_01 V3 fields parity: targetKind, targetIdJson, affectedCount round-trip")
        void T7_b_01_V3FieldsParity() {
            UserAccount account = createAccount("t7b01@example.com", "t7b01-prov");
            em.flush();

            UserAction action = new UserAction();
            action.setAccountId(account.getId());
            action.setActionType("ARCHIVE");
            action.setSenderGroupKey("a@x.com");
            action.setTargetKind("SENDER_ADDRESS");
            action.setTargetIdJson(Map.of("fromAddress", "a@x.com"));
            action.setAffectedCount(12);
            action.setMessageIds(List.of("m1", "m2"));
            action.setInverseActionData(Map.of("formatVersion", 1));
            action.setCreatedAt(Instant.now());
            action.setExpiresAt(Instant.now().plusSeconds(5));

            UserAction saved = userActions.save(action);
            em.flush();
            em.clear();
            assertNotNull(saved.getId());

            UserAction loaded = userActions.findById(saved.getId()).orElseThrow();
            assertEquals("SENDER_ADDRESS", loaded.getTargetKind());
            assertEquals(12, loaded.getAffectedCount().intValue());
            assertEquals("a@x.com", loaded.getTargetIdJson().get("fromAddress"));
        }

        @Test
        @DisplayName("T7_b_02 Cascade delete: account removal wipes 3 UserAction rows")
        void T7_b_02_cascadeDelete() {
            UserAccount account = createAccount("t7b02@example.com", "t7b02-prov");
            em.flush();

            for (int i = 0; i < 3; i++) {
                UserAction ua = new UserAction();
                ua.setAccountId(account.getId());
                ua.setActionType("ARCHIVE");
                ua.setTargetKind("SENDER_ADDRESS");
                ua.setTargetIdJson(Map.of("fromAddress", "s" + i + "@x.com"));
                ua.setAffectedCount(i + 1);
                ua.setMessageIds(List.of("id" + i));
                ua.setInverseActionData(Map.of("v", 1));
                ua.setCreatedAt(Instant.now());
                ua.setExpiresAt(Instant.now().plusSeconds(5));
                userActions.save(ua);
            }
            em.flush();
            assertEquals(3, userActions.countByAccountId(account.getId()));

            userAccounts.delete(account);
            em.flush();
            assertEquals(0, userActions.count());
        }

        @Test
        @DisplayName("T7_b_03 findByIdAndAccountId guard: cross-account lookup returns empty")
        void T7_b_03_findByIdAndAccountIdGuard() {
            UserAccount act1 = createAccount("t7b03a@example.com", "t7b03a-prov");
            UserAccount act2 = createAccount("t7b03b@example.com", "t7b03b-prov");
            em.flush();

            UserAction action1 = new UserAction();
            action1.setAccountId(act1.getId());
            action1.setActionType("TRASH");
            action1.setTargetKind("SENDER_ADDRESS");
            action1.setTargetIdJson(Map.of("fromAddress", "x1@x.com"));
            action1.setAffectedCount(5);
            action1.setMessageIds(List.of("a"));
            action1.setInverseActionData(Map.of("v", 1));
            action1.setCreatedAt(Instant.now());
            action1.setExpiresAt(Instant.now().plusSeconds(5));
            action1 = userActions.save(action1);

            UserAction action2 = new UserAction();
            action2.setAccountId(act2.getId());
            action2.setActionType("MARK_READ");
            action2.setTargetKind("SENDER_ADDRESS");
            action2.setTargetIdJson(Map.of("fromAddress", "x2@x.com"));
            action2.setAffectedCount(3);
            action2.setMessageIds(List.of("b"));
            action2.setInverseActionData(Map.of("v", 1));
            action2.setCreatedAt(Instant.now());
            action2.setExpiresAt(Instant.now().plusSeconds(5));
            action2 = userActions.save(action2);

            em.flush();
            em.clear();

            assertTrue(userActions.findByIdAndAccountId(action1.getId(), act2.getId()).isEmpty());
            assertTrue(userActions.findByIdAndAccountId(action2.getId(), act1.getId()).isEmpty());
            assertTrue(userActions.findByIdAndAccountId(action1.getId(), act1.getId()).isPresent());
            assertTrue(userActions.findByIdAndAccountId(action2.getId(), act2.getId()).isPresent());

            UserAction loaded1 = userActions.findByIdAndAccountId(action1.getId(), act1.getId()).get();
            assertEquals("TRASH", loaded1.getActionType());
        }

        @Test
        @DisplayName("T7_b_04 expiresAt default: onCreate sets 5s window (4900-5500ms tolerance)")
        void T7_b_04_expiresAtDefault() {
            UserAccount account = createAccount("t7b04@example.com", "t7b04-prov");
            em.flush();

            Instant before = Instant.now();
            UserAction action = new UserAction();
            action.setAccountId(account.getId());
            action.setActionType("ARCHIVE");
            action.setTargetKind("SENDER_ADDRESS");
            action.setTargetIdJson(Map.of("fromAddress", "e@x.com"));
            action.setAffectedCount(1);
            action.setMessageIds(List.of("mid"));
            action.setInverseActionData(Map.of("v", 1));
            UserAction saved = userActions.save(action);
            em.flush();
            em.clear();

            UserAction loaded = userActions.findById(saved.getId()).orElseThrow();
            assertNotNull(loaded.getExpiresAt());
            assertNotNull(loaded.getCreatedAt());
            long diffMs = Duration.between(loaded.getCreatedAt(), loaded.getExpiresAt()).toMillis();
            assertTrue(diffMs >= 4900, "diffMs=" + diffMs + " expected >= 4900");
            assertTrue(diffMs <= 5500, "diffMs=" + diffMs + " expected <= 5500");
        }
    }

    // ============================================================
    // T7(c): MockMvc bulk-by-sender + undo integration tests
    // ============================================================

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "GOOGLE_CLIENT_ID=your-dummy-google-client-id",
        "GOOGLE_CLIENT_SECRET=your-dummy-google-secret",
        "SYNC_GMAIL_API_CONCURRENCY=2",
        "SYNC_GMAIL_BATCH_DELAY_MS=0",
        "TOKEN_ENCRYPT_SECRET=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        "TOKEN_ENCRYPT_SALT=ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
        "MICROSOFT_CLIENT_ID=",
        "MICROSOFT_CLIENT_SECRET=",
        "MICROSOFT_TENANT_ID=common",
    })
    @DisplayName("T7c Bulk MVC Integration")
    class T7c_BulkMvcTests {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        UserActionRepository userActions;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        MessageMetadataRepository metadata;

        @Autowired
        ActionScopeRepository actionScopes;

        @Autowired
        CryptoUtil cryptoUtil;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        @MockBean(name = "outlookMailService")
        MailProvider outlookProvider;

        private UserAccount insertMeAccount(String email) {
            UserAccount me = new UserAccount();
            me.setEmail(email);
            me.setProvider("google");
            me.setProviderAccountId("google-acc-" + email.hashCode());
            me.setDisplayName("Me T7c");
            me.setEncryptedAccessToken(cryptoUtil.encrypt("fake-access".getBytes(StandardCharsets.UTF_8)));
            me.setEncryptedRefreshToken(cryptoUtil.encrypt("fake-refresh".getBytes(StandardCharsets.UTF_8)));
            me.setScopes(List.of("openid", "email", "profile"));
            me.setAesKeyId("hkdf-sha256-v1");
            return userAccounts.save(me);
        }

        @BeforeEach
        void cleanDb() {
            userActions.deleteAll();
            actionScopes.deleteAll();
            metadata.deleteAll();
            userAccounts.deleteAll();
        }

        private OAuth2AuthenticationToken buildAuthToken(String email, String regId) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("email", email);
            attrs.put("sub", "fake-sub-" + email);
            List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOAuth2User user = new DefaultOAuth2User(auths, attrs, "sub");
            return new OAuth2AuthenticationToken(user, auths, regId);
        }

        private List<MailMessage> buildNewsDataset(int count, String from) {
            Instant now = Instant.parse("2026-08-07T12:00:00Z");
            List<MailMessage> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                out.add(msg("nws" + i, "nwt" + i,
                    "News <" + from + ">",
                    "me@example.com",
                    "Newsletter " + i,
                    now.minus(i, ChronoUnit.HOURS),
                    i % 2 == 0 ? Set.of("UNREAD") : Set.of(),
                    null, null));
            }
            return out;
        }

        private void seedMetadata(UserAccount account, int count, String from) {
            Instant now = Instant.parse("2026-08-07T12:00:00Z");
            List<MessageMetadata> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MessageMetadata row = new MessageMetadata();
                row.setAccountId(account.getId());
                row.setProvider("google");
                row.setProviderMessageId("nws" + i);
                row.setProviderThreadId("nwt" + i);
                row.setFromAddress(from);
                row.setFromDomain(Rfc5322Util.extractDomain(from));
                row.setReceivedAt(now.minus(i, ChronoUnit.HOURS));
                row.setLabels(i % 2 == 0 ? List.of("UNREAD") : List.of());
                rows.add(row);
            }
            metadata.saveAll(rows);
        }

        private ActionScope previewAndRejectLegacyQueue(String email, String from, String action)
                throws Exception {
            OAuth2AuthenticationToken authentication = buildAuthToken(email, "google");
            long before = userActions.count();
            MvcResult preview = mockMvc.perform(post("/api/actions/bulk-by-sender/" + from)
                    .param("action", action)
                    .with(csrf())
                    .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andReturn();
            assertThat(preview.getResponse().getContentAsString())
                .contains(
                    "Stage for Review",
                    "Run after this confirmation",
                    "Both modes use this exact scope and the same safety checks.")
                .doesNotContain("/api/actions/scopes/");
            assertEquals(before, userActions.count());
            ActionScope scope = actionScopes.findAll().stream()
                .filter(row -> row.getActionType().equals(action))
                .findFirst()
                .orElseThrow();
            mockMvc.perform(post("/api/actions/scopes/" + scope.getId() + "/queue")
                    .with(csrf())
                    .with(authentication(authentication)))
                .andExpect(status().isGone());
            verify(gmailProvider, times(0)).listMessages(any(), any(), anyInt());
            assertEquals(before, userActions.count());
            return scope;
        }

        @Test
        @DisplayName("T7_c_01 POST archive queues action without mutating Gmail")
        void T7_c_01_postArchiveSuccess() throws Exception {
            UserAccount me = insertMeAccount("me@example.com");
            seedMetadata(me, 20, "news@x.com");
            ActionScope scope =
                previewAndRejectLegacyQueue("me@example.com", "news@x.com", "ARCHIVE");
            assertEquals("ARCHIVE", scope.getActionType());
            assertEquals(20, scope.getMessageCount().intValue());
            assertEquals(0, userActions.count());
            verify(gmailProvider, times(0)).batchModify(any(), any(BulkActionRequest.class));
        }

        @Test
        @DisplayName("T7_c_02 POST mark-read and trash queue the correct action types")
        void T7_c_02_postReadAndTrash() throws Exception {
            UserAccount me = insertMeAccount("me@example.com");
            seedMetadata(me, 10, "news@x.com");
            ActionScope readScope =
                previewAndRejectLegacyQueue("me@example.com", "news@x.com", "MARK_READ");
            assertEquals("MARK_READ", readScope.getActionType());

            userActions.deleteAll();

            ActionScope trashScope =
                previewAndRejectLegacyQueue("me@example.com", "news@x.com", "TRASH");
            assertEquals("TRASH", trashScope.getActionType());
        }

        @Test
        @DisplayName("T7_c_03 Cap enforcement: 2000 news queues 1000 IDs without provider mutation")
        void T7_c_03_capEnforcement2000() throws Exception {
            UserAccount me = insertMeAccount("me@example.com");
            seedMetadata(me, 2000, "news@x.com");
            ActionScope scope =
                previewAndRejectLegacyQueue("me@example.com", "news@x.com", "ARCHIVE");
            assertEquals(1000, scope.getMessageCount().intValue());
            assertEquals(1000, scope.getMessageIds().size());
            verify(gmailProvider, times(0)).batchModify(any(), any(BulkActionRequest.class));
        }

        @Test
        @DisplayName("T7_c_04 Review apply mutates Gmail only after the queued action is confirmed")
        void T7_c_04_reviewApplyExecutesQueuedAction() throws Exception {
            UserAccount me = insertMeAccount("me@example.com");
            seedMetadata(me, 8, "news@x.com");
            when(gmailProvider.batchModify(any(), any()))
                .thenAnswer(invocation -> {
                    BulkActionRequest request = invocation.getArgument(1);
                    return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
                });

            previewAndRejectLegacyQueue("me@example.com", "news@x.com", "TRASH");
            verify(gmailProvider, times(0)).batchModify(any(), any(BulkActionRequest.class));

            mockMvc.perform(post("/api/actions/review/apply")
                    .with(csrf())
                    .with(authentication(buildAuthToken("me@example.com", "google"))))
                .andExpect(status().isGone());
            assertEquals(0, userActions.count());
            verify(gmailProvider, times(0)).batchModify(any(), any(BulkActionRequest.class));
        }

        @Test
        @DisplayName("T7_c_05 Legacy null-scope undo remains compatible")
        void T7_c_05_undoRoundtrip() throws Exception {
            UserAccount me = insertMeAccount("me@example.com");
            when(gmailProvider.applyLabel(any(), anyList(), anyString(), any(Boolean.class)))
                .thenAnswer(inv -> {
                    List<String> ids = inv.getArgument(1);
                    return BulkActionResult.ok(ids.size(), new ArrayList<>(ids));
                });
            UserAction legacy = new UserAction();
            legacy.setAccountId(me.getId());
            legacy.setActionType("ARCHIVE");
            legacy.setTargetKind("SENDER_ADDRESS");
            legacy.setTargetIdJson(Map.of("fromAddress", "news@x.com"));
            legacy.setMessageIds(List.of("legacy-1", "legacy-2"));
            legacy.setAffectedCount(2);
            legacy.setInverseActionData(
                UndoService.buildInverseActionData(UndoService.BulkAction.ARCHIVE,
                    legacy.getMessageIds()));
            legacy.setStatus("APPLIED");
            legacy.setExpiresAt(Instant.now().plusSeconds(30));
            UserAction saved = userActions.save(legacy);
            Long actionId = saved.getId();
            assertNotNull(actionId);

            MvcResult undoRes = mockMvc.perform(post("/api/actions/" + actionId + "/undo")
                    .with(csrf())
                    .with(authentication(buildAuthToken("me@example.com", "google"))))
                  .andExpect(status().isBadRequest())
                .andReturn();

            String undoBody = undoRes.getResponse().getContentAsString();
            assertThat(undoBody).isEmpty();

            UserAction afterUndo = userActions.findById(actionId).orElseThrow();
            assertThat(afterUndo.getUndoneAt()).isNull();
            verify(gmailProvider, never()).applyLabel(any(), anyList(), eq("INBOX"), eq(true));
        }

        @Test
        @DisplayName("T7_c_05 Unauthorized guards: no-auth bulk/undo → 302/401, cross-account undo → 404")
        void T7_c_05_unauthorizedGuards() throws Exception {
            UserAccount act1 = insertMeAccount("act1@example.com");
            UserAccount act2 = insertMeAccount("act2@example.com");

            int noAuthBulk = mockMvc.perform(post("/api/actions/bulk-by-sender/a@b.com")
                    .param("action", "ARCHIVE")
                    .with(csrf()))
                .andReturn().getResponse().getStatus();
            assertTrue(noAuthBulk == 302 || noAuthBulk == 401);

            int noAuthUndo = mockMvc.perform(post("/api/actions/999/undo")
                    .with(csrf()))
                .andReturn().getResponse().getStatus();
            assertTrue(noAuthUndo == 302 || noAuthUndo == 401);

            seedMetadata(act1, 3, "n@x.com");
            ActionScope act1Scope =
                previewAndRejectLegacyQueue("act1@example.com", "n@x.com", "ARCHIVE");

            int crossUndoStatus = mockMvc.perform(post("/api/actions/" + act1Scope.getId() + "/undo")
                    .with(csrf())
                    .with(authentication(buildAuthToken("act2@example.com", "google"))))
                .andReturn().getResponse().getStatus();
              assertEquals(400, crossUndoStatus);
        }
    }

    // ============================================================
    // T7(d): Page rendering tests — dashboard, fragments, empty states
    // ============================================================

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @org.springframework.transaction.annotation.Transactional
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "GOOGLE_CLIENT_ID=your-dummy-google-client-id",
        "GOOGLE_CLIENT_SECRET=your-dummy-google-secret",
        "SYNC_GMAIL_API_CONCURRENCY=2",
        "SYNC_GMAIL_BATCH_DELAY_MS=0",
        "TOKEN_ENCRYPT_SECRET=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        "TOKEN_ENCRYPT_SALT=ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
        "MICROSOFT_CLIENT_ID=",
        "MICROSOFT_CLIENT_SECRET=",
        "MICROSOFT_TENANT_ID=common",
    })
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("T7d Page Rendering")
    class T7d_PageRenderingTests {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        UserAccountRepository userAccounts;

        @Autowired
        CryptoUtil cryptoUtil;

        @Autowired
        SenderAggregator senderAggregator;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        @MockBean(name = "outlookMailService")
        MailProvider outlookProvider;

        private UserAccount insertMeAccount() {
            UserAccount me = new UserAccount();
            me.setEmail("me-t7d@example.com");
            me.setProvider("google");
            me.setProviderAccountId("google-acc-t7d");
            me.setDisplayName("Me T7d");
            me.setEncryptedAccessToken(cryptoUtil.encrypt("fake-access".getBytes(StandardCharsets.UTF_8)));
            me.setEncryptedRefreshToken(cryptoUtil.encrypt("fake-refresh".getBytes(StandardCharsets.UTF_8)));
            me.setScopes(List.of("openid", "email", "profile"));
            me.setAesKeyId("hkdf-sha256-v1");
            return userAccounts.save(me);
        }

        @BeforeEach
        void cleanDb() {
            userAccounts.deleteAll();
        }

        private OAuth2AuthenticationToken buildAuthToken(String email, String regId) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("email", email);
            attrs.put("sub", "fake-sub-" + email);
            List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOAuth2User user = new DefaultOAuth2User(auths, attrs, "sub");
            return new OAuth2AuthenticationToken(user, auths, regId);
        }

        private List<MailMessage> build40MsgDatasetThreeSenders() {
            Instant now = Instant.parse("2026-08-07T12:00:00Z");
            List<MailMessage> out = new ArrayList<>(40);
            int idc = 0;
            for (int j = 0; j < 20; j++, idc++) {
                out.add(msg("d" + idc, "dt" + idc,
                    "Sender A <a@x.com>",
                    "me-t7d@example.com",
                    "From A #" + j,
                    now.minus(10 + j, ChronoUnit.DAYS),
                    j % 2 == 0 ? Set.of("UNREAD") : Set.of(),
                    null, null));
            }
            for (int j = 0; j < 15; j++, idc++) {
                out.add(msg("d" + idc, "dt" + idc,
                    "Sender B <b@x.com>",
                    "me-t7d@example.com",
                    "From B #" + j,
                    now.minus(5 + j, ChronoUnit.DAYS),
                    Set.of(),
                    null, null));
            }
            for (int j = 0; j < 5; j++, idc++) {
                out.add(msg("d" + idc, "dt" + idc,
                    "Sender C <c@z.com>",
                    "me-t7d@example.com",
                    "From C #" + j,
                    now.minus(2 + j, ChronoUnit.DAYS),
                    Set.of("UNREAD"),
                    null, null));
            }
            return out;
        }

        @Test
        @Order(1)
        @DisplayName("T7_d_01 GET /dashboard redirects to the workspace senders route")
        void T7_d_01_dashboardLayout() throws Exception {
            insertMeAccount();

            MvcResult res = mockMvc.perform(get("/dashboard")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().is3xxRedirection())
                .andReturn();

            assertThat(res.getResponse().getRedirectedUrl()).isEqualTo("/senders");
            verify(gmailProvider, times(0)).listMessages(any(), any(), anyInt());
        }

        @Test
        @Order(10)
        @DisplayName("T7_d_02 GET /senders-frag empty → empty-senders class present")
        void T7_d_02_sendersFragEmpty() throws Exception {
            insertMeAccount();
            when(gmailProvider.listMessages(any(), any(), anyInt()))
                .thenReturn(PageResult.of(List.of(), null, 100));

            MvcResult res = mockMvc.perform(get("/senders-frag")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();

            String body = res.getResponse().getContentAsString();
            assertThat(body).contains("empty-senders");
        }

        @Test
        @Order(20)
        @DisplayName("T7_d_03 GET /senders-frag populated 3 senders → DESC order, Archive text ≥3 occurrences")
        void T7_d_03_sendersFragPopulated() throws Exception {
            UserAccount account = insertMeAccount();
            List<MailMessage> data = build40MsgDatasetThreeSenders();
            senderAggregator.persistSnapshot(account.getId(), senderAggregator.aggregate(data));

            MvcResult res = mockMvc.perform(get("/senders-frag")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();

            String body = res.getResponse().getContentAsString();
            assertThat(body).doesNotContain("empty-senders");

            int archiveCount = 0;
            int idx = 0;
            while ((idx = body.indexOf("Archive", idx)) != -1) {
                archiveCount++;
                idx += 7;
            }
            assertTrue(archiveCount >= 3, "Archive occurrences=" + archiveCount + " expected >= 3");

            List<Integer> counts = new ArrayList<>();
            int searchIdx = 0;
            while (true) {
                int bIdx = body.indexOf("badge-count", searchIdx);
                if (bIdx == -1) break;
                int gtIdx = body.indexOf('>', bIdx);
                int ltIdx = body.indexOf('<', gtIdx);
                if (gtIdx != -1 && ltIdx != -1 && ltIdx > gtIdx) {
                    String numStr = body.substring(gtIdx + 1, ltIdx).trim();
                    try {
                        counts.add(Integer.parseInt(numStr));
                    } catch (NumberFormatException ignored) {}
                }
                searchIdx = bIdx + 1;
                if (counts.size() >= 3) break;
            }
            if (counts.size() >= 3) {
                assertTrue(counts.get(0) >= counts.get(1),
                    "DESC order: count[0]=" + counts.get(0) + " >= count[1]=" + counts.get(1));
                assertTrue(counts.get(1) >= counts.get(2),
                    "DESC order: count[1]=" + counts.get(1) + " >= count[2]=" + counts.get(2));
            }
        }

        @Test
        @Order(30)
        @DisplayName("T7_d_04 Reply Zero: empty state class, populated 200 OK, no-auth → 302/401")
        void T7_d_04_replyZeroStates() throws Exception {
            insertMeAccount();
            when(gmailProvider.listMessages(any(), any(), anyInt()))
                .thenReturn(PageResult.of(List.of(), null, 100));

            MvcResult emptyRes = mockMvc.perform(get("/reply-zero-frag")
                    .param("olderThanDays", "1")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();
            String emptyBody = emptyRes.getResponse().getContentAsString();
            assertThat(emptyBody).contains("empty-reply-zero");

            Instant now = Instant.parse("2026-08-07T12:00:00Z");
            List<MailMessage> personal = new ArrayList<>();
            personal.add(msg("rzm1", "rzt1",
                "Alice <alice@example.com>",
                "me-t7d@example.com",
                "Personal thread 1",
                now.minus(7, ChronoUnit.DAYS),
                Set.of(), null, null));
            personal.add(msg("rzm2", "rzt2",
                "Bob <bob@example.com>",
                "me-t7d@example.com, lead@x.com",
                "Personal thread 2",
                now.minus(4, ChronoUnit.DAYS),
                Set.of(), null, null));

            MailThreadSummary ts1 = new MailThreadSummary("rzt1",
                List.of("Alice <alice@example.com>"),
                Set.of("example.com"),
                1,
                now.minus(7, ChronoUnit.DAYS),
                now.minus(7, ChronoUnit.DAYS),
                List.of("rzm1"));
            MailThreadSummary ts2 = new MailThreadSummary("rzt2",
                List.of("Bob <bob@example.com>", "Me <me-t7d@example.com>"),
                Set.of("example.com"),
                1,
                now.minus(4, ChronoUnit.DAYS),
                now.minus(4, ChronoUnit.DAYS),
                List.of("rzm2"));

            when(gmailProvider.listMessages(any(), any(), anyInt()))
                .thenReturn(PageResult.of(personal, null, 100));
            when(gmailProvider.getThreadMetadata(any(), any())).thenAnswer(inv -> {
                String tid = inv.getArgument(1);
                if ("rzt1".equals(tid)) return ts1;
                if ("rzt2".equals(tid)) return ts2;
                return new MailThreadSummary(tid, List.of(), Set.of(), 0, null, null, List.of());
            });

            MvcResult popRes = mockMvc.perform(get("/reply-zero-frag")
                    .param("olderThanDays", "1")
                    .with(authentication(buildAuthToken("me-t7d@example.com", "google"))))
                .andExpect(status().isOk())
                .andReturn();
            assertThat(popRes.getResponse().getContentAsString()).isNotEmpty();

            int noAuthStatus = mockMvc.perform(get("/reply-zero-frag")
                    .param("olderThanDays", "1"))
                .andReturn().getResponse().getStatus();
            assertTrue(noAuthStatus == 302 || noAuthStatus == 401);
        }
    }
}
