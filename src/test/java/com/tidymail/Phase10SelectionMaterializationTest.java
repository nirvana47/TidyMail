package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.SelectionMode;
import com.tidymail.dto.SelectionRequest;
import com.tidymail.dto.SelectionRequest.OverrideReason;
import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.SelectionScopePreview.State;
import com.tidymail.dto.SelectionSurface;
import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.UnreadDecisionRow;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.ProtectedOverrideAudit;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.ProtectedOverrideAuditRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.DecisionRowKey;
import com.tidymail.service.ActionScopePersistenceService;
import com.tidymail.service.ProtectedOverrideAuditService;
import com.tidymail.service.SelectionMaterializationService;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.UnreadDecisionQueryService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
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
class Phase10SelectionMaterializationTest {
    @Autowired MockMvc mockMvc;
    @Autowired SelectionMaterializationService materialization;
    @Autowired SenderDecisionQueryService senderQueries;
    @Autowired UnreadDecisionQueryService unreadQueries;
    @Autowired ProtectedOverrideAuditService auditService;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ActionScopeRepository scopes;
    @Autowired ProtectedOverrideAuditRepository audits;
    @Autowired UserActionRepository actions;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService")
    MailProvider gmailProvider;

    @MockBean(name = "outlookMailService")
    MailProvider outlookProvider;

    @SpyBean
    ActionScopePersistenceService scopePersistence;

    @BeforeEach
    void clean() {
        actions.deleteAll();
        audits.deleteAll();
        scopes.deleteAll();
        metadata.deleteAll();
        accounts.deleteAll();
        reset(gmailProvider, outlookProvider, scopePersistence);
    }

    @Test
    void strictContractsAreImmutableAndFingerprintIsDeterministic() {
        SenderQuery query = senderQuery("current", SenderQuery.Protection.INCLUDE);
        ArrayList<String> keys = new ArrayList<>(List.of(DecisionRowKey.sender(1, "a@example.com")));
        SelectionRequest request = new SelectionRequest(
            SelectionSurface.SENDERS, SelectionMode.INDIVIDUAL, query, null,
            keys, PreviewAction.ARCHIVE, true, OverrideReason.USER_CONFIRMED);
        keys.add("changed");

        assertThat(request.rowKeys()).hasSize(1);
        assertThatThrownBy(() -> request.rowKeys().add("changed"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(auditService.fingerprint(request))
            .isEqualTo(auditService.fingerprint(request))
            .matches("[0-9a-f]{64}");
        assertThat(DecisionRowKey.decode(
            SelectionSurface.UNREAD, DecisionRowKey.unread(1, 42)).metadataId()).isEqualTo(42);
        String leadingZeroUnread = "u1." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("1:042".getBytes(StandardCharsets.UTF_8));
        String malformedUtf8 = "s1." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[] {'1', ':', (byte) 0xc3, 0x28});
        assertThatThrownBy(() -> DecisionRowKey.decode(
            SelectionSurface.UNREAD, leadingZeroUnread))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionRowKey.decode(
            SelectionSurface.UNREAD, DecisionRowKey.unread(1, 42) + "="))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionRowKey.decode(
            SelectionSurface.SENDERS, malformedUtf8))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SelectionRequest(
            SelectionSurface.SENDERS, SelectionMode.INDIVIDUAL, query, null,
            List.of(), PreviewAction.ARCHIVE, false, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SelectionRequest(
            SelectionSurface.SENDERS, SelectionMode.ALL_MATCHING, query, null,
            List.of("unexpected"), PreviewAction.ARCHIVE, false, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SelectionRequest(
            SelectionSurface.SENDERS, SelectionMode.ALL_MATCHING, query, null,
            List.of(), PreviewAction.ARCHIVE, true, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allSixModesMaterializeExactServerIdsAndRejectUntrustedKeys() {
        UserAccount account = account("six@example.com");
        seed(account, "a-1", "a@example.com", true);
        seed(account, "a-2", "a@example.com", true);
        seed(account, "b-1", "b@example.com", true);
        SenderQuery senderQuery = senderQuery(
            String.valueOf(account.getId()), SenderQuery.Protection.INCLUDE);
        UnreadQuery unreadQuery = unreadQuery(String.valueOf(account.getId()));
        DecisionPage<SenderDecisionRow> senderPage =
            senderQueries.query(List.of(account.getId()), senderQuery);
        DecisionPage<UnreadDecisionRow> unreadPage =
            unreadQueries.query(List.of(account.getId()), unreadQuery);

        String senderA = senderPage.rows().stream()
            .filter(row -> row.senderAddress().equals("a@example.com"))
            .findFirst().orElseThrow().rowKey();
        String unreadA = unreadPage.rows().stream()
            .filter(row -> row.subject().equals("a-1"))
            .findFirst().orElseThrow().rowKey();

        assertExact(request(
            senderQuery, SelectionMode.INDIVIDUAL, List.of(senderA)), List.of("a-1", "a-2"));
        assertExact(request(
            senderQuery, SelectionMode.VISIBLE_PAGE, List.of()), List.of("a-1", "a-2", "b-1"));
        assertExact(request(
            senderQuery, SelectionMode.ALL_MATCHING, List.of()), List.of("a-1", "a-2", "b-1"));
        assertExact(request(
            unreadQuery, SelectionMode.INDIVIDUAL, List.of(unreadA)), List.of("a-1"));
        assertExact(request(
            unreadQuery, SelectionMode.VISIBLE_PAGE, List.of()), List.of("a-1", "a-2", "b-1"));
        assertExact(request(
            unreadQuery, SelectionMode.ALL_MATCHING, List.of()), List.of("a-1", "a-2", "b-1"));

        assertThatThrownBy(() -> materialization.preview(
            List.of(account.getId()),
            request(senderQuery, SelectionMode.INDIVIDUAL, List.of(senderA, senderA))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> materialization.preview(
            List.of(account.getId()),
            request(senderQuery, SelectionMode.INDIVIDUAL, List.of(unreadA))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materialization.preview(
            List.of(account.getId()),
            request(senderQuery, SelectionMode.INDIVIDUAL,
                List.of(DecisionRowKey.sender(account.getId(), "missing@example.com")))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("stale");
        assertThat(actions.count()).isZero();
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void phase12ProtectRequiresOneUnambiguousSenderPerExactAction() {
        UserAccount account = account("protect-preview@example.com");
        seed(account, "protect-a", "a@example.com", true);
        seed(account, "protect-b", "b@example.com", true);
        SenderQuery query = senderQuery(
            String.valueOf(account.getId()), SenderQuery.Protection.INCLUDE);

        SelectionScopePreview broad = materialization.preview(
            List.of(account.getId()),
            new SelectionRequest(
                SelectionSurface.SENDERS, SelectionMode.ALL_MATCHING,
                query, null, List.of(), PreviewAction.PROTECT, false, null));
        assertThat(broad.state()).isEqualTo(State.INVALID);
        assertThat(broad.errorMessage()).contains("one sender at a time");
        assertThat(scopes.count()).isZero();

        String rowKey = senderQueries.query(List.of(account.getId()), query)
            .rows().stream()
            .filter(row -> row.senderAddress().equals("a@example.com"))
            .findFirst().orElseThrow().rowKey();
        SelectionScopePreview exact = materialization.preview(
            List.of(account.getId()),
            new SelectionRequest(
                SelectionSurface.SENDERS, SelectionMode.INDIVIDUAL,
                query, null, List.of(rowKey), PreviewAction.PROTECT, false, null));
        assertThat(exact.state()).isEqualTo(State.READY);
        assertThat(exact.scopes()).singleElement()
            .satisfies(scope -> assertThat(scope.messageCount()).isOne());
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void visiblePageUsesGlobalPageAndAllAccountsProduceSeparateScopes() {
        UserAccount first = account("first@example.com");
        UserAccount second = account("second@example.com");
        for (int index = 0; index < 13; index++) {
            seed(first, "f-" + index, "sender-" + index + "@first.test", true);
            seed(second, "s-" + index, "sender-" + index + "@second.test", true);
        }
        SenderQuery page = new SenderQuery(
            "all", "", SenderQuery.Window.ALL, SenderQuery.Type.ALL, SenderQuery.Risk.ALL,
            SenderQuery.UnreadPressure.ALL, SenderQuery.QueueState.ALL,
            SenderQuery.Protection.INCLUDE, SenderQuery.Sort.VOLUME_DESC, 0);
        SelectionScopePreview preview = materialization.preview(
            List.of(second.getId(), first.getId()),
            request(page, SelectionMode.VISIBLE_PAGE, List.of()));

        assertThat(preview.state()).isEqualTo(State.READY);
        assertThat(preview.messageCount()).isEqualTo(25);
        assertThat(preview.scopes()).hasSize(2);
        assertThat(scopes.findAll()).hasSize(2);
        assertThat(scopes.findAll())
            .allSatisfy(scope -> assertThat(scope.getMessageCount()).isLessThanOrEqualTo(13));
    }

    @Test
    void perAccountCapAccepts1000Rejects1001WithoutTruncationOrWrites() throws Exception {
        UserAccount account = account("cap@example.com");
        seedMany(account, 1000, "bulk@example.com", "cap-");
        SenderQuery query = senderQuery(
            String.valueOf(account.getId()), SenderQuery.Protection.INCLUDE);

        SelectionScopePreview exact = materialization.preview(
            List.of(account.getId()), request(query, SelectionMode.ALL_MATCHING, List.of()));
        assertThat(exact.state()).isEqualTo(State.READY);
        assertThat(scopes.findAll().getFirst().getMessageCount()).isEqualTo(1000);

        scopes.deleteAll();
        seed(account, "cap-1000", "bulk@example.com", true);
        SelectionScopePreview rejected = materialization.preview(
            List.of(account.getId()), request(query, SelectionMode.ALL_MATCHING, List.of()));
        assertThat(rejected.state()).isEqualTo(State.CAP_EXCEEDED);
        assertThat(rejected.capMessageCount()).isEqualTo(1001);
        assertThat(scopes.count()).isZero();
        assertThat(audits.count()).isZero();
        assertThat(actions.count()).isZero();

        mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "SENDERS")
                .param("mode", "ALL_MATCHING")
                .param("action", "ARCHIVE")
                .param("window", "all")
                .param("queue", "all")
                .param("protected", "include"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Selection is too large for one exact preview")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "The exact per-account scope limit is 1,000")));
    }

    @Test
    void aggregateOver1000SucceedsWhenEachAccountIsWithinCap() {
        UserAccount first = account("aggregate-first@example.com");
        UserAccount second = account("aggregate-second@example.com");
        seedMany(first, 600, "bulk@first.test", "af-");
        seedMany(second, 600, "bulk@second.test", "as-");
        SenderQuery query = senderQuery("all", SenderQuery.Protection.INCLUDE);

        SelectionScopePreview preview = materialization.preview(
            List.of(first.getId(), second.getId()),
            request(query, SelectionMode.ALL_MATCHING, List.of()));
        assertThat(preview.state()).isEqualTo(State.READY);
        assertThat(preview.messageCount()).isEqualTo(1200);
        assertThat(preview.scopes()).extracting(scope -> scope.messageCount())
            .containsExactly(600, 600);
        assertThat(scopes.findAll()).allSatisfy(scope ->
            assertThat(scope.getMessageCount()).isEqualTo(600));
    }

    @Test
    void protectedRowsAreExcludedThenServerAuditedOnExplicitOverride() {
        UserAccount account = account("protected@example.com");
        seed(account, "open-1", "open@example.com", true);
        seed(account, "protected-1", "protected@example.com", true);
        seed(account, "protected-2", "protected@example.com", true);
        protect(account, "protected@example.com");
        SenderQuery query = senderQuery(
            String.valueOf(account.getId()), SenderQuery.Protection.INCLUDE);

        SelectionScopePreview excluded = materialization.preview(
            List.of(account.getId()), request(query, SelectionMode.ALL_MATCHING, List.of()));
        assertThat(excluded.state()).isEqualTo(State.READY);
        assertThat(excluded.messageCount()).isEqualTo(1);
        assertThat(excluded.excludedProtectedSenderCount()).isEqualTo(1);
        assertThat(excluded.excludedProtectedMessageCount()).isEqualTo(2);
        assertThat(scopes.findAll().getFirst().getMessageIds()).containsExactly("open-1");
        assertThat(audits.count()).isZero();

        SelectionRequest override = new SelectionRequest(
            SelectionSurface.SENDERS, SelectionMode.ALL_MATCHING, query, null, List.of(),
            PreviewAction.ARCHIVE, true, OverrideReason.BUSINESS_REQUIRED);
        SelectionScopePreview included =
            materialization.preview(List.of(account.getId()), override);
        assertThat(included.messageCount()).isEqualTo(3);
        assertThat(scopes.findAll()).anySatisfy(scope ->
            assertThat(scope.getMessageIds())
                .containsExactly("open-1", "protected-1", "protected-2"));
        ProtectedOverrideAudit audit = audits.findAll().getFirst();
        assertThat(audit.getProtectedSenderCount()).isEqualTo(1);
        assertThat(audit.getProtectedMessageCount()).isEqualTo(2);
        assertThat(audit.getReasonCode()).isEqualTo("BUSINESS_REQUIRED");
        assertThat(audit.getQueryFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void secondAccountScopeFailureRollsBackEveryScopeAndOverrideAudit() {
        UserAccount first = account("rollback-first@example.com");
        UserAccount second = account("rollback-second@example.com");
        seed(first, "rollback-first", "protected@first.test", true);
        seed(second, "rollback-second", "protected@second.test", true);
        protect(first, "protected@first.test");
        protect(second, "protected@second.test");
        SenderQuery query = senderQuery("all", SenderQuery.Protection.INCLUDE);
        SelectionRequest override = new SelectionRequest(
            SelectionSurface.SENDERS, SelectionMode.ALL_MATCHING, query, null, List.of(),
            PreviewAction.ARCHIVE, true, OverrideReason.USER_CONFIRMED);
        doAnswer(invocation -> {
            ActionScope candidate = invocation.getArgument(0);
            if (candidate.getAccountId().equals(second.getId())) {
                throw new DataIntegrityViolationException("forced non-unique failure");
            }
            return invocation.callRealMethod();
        }).when(scopePersistence).insertRequired(any(ActionScope.class));

        assertThatThrownBy(() -> materialization.preview(
            List.of(first.getId(), second.getId()), override))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("forced");
        assertThat(scopes.count()).isZero();
        assertThat(audits.count()).isZero();
        assertThat(actions.count()).isZero();
    }

    @Test
    void protectedOnlySelectionReturnsConflictAndOverrideFormThroughMvc() throws Exception {
        UserAccount account = account("blocked@example.com");
        seed(account, "secret-provider-id", "protected@example.com", true);
        protect(account, "protected@example.com");

        mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "SENDERS")
                .param("mode", "ALL_MATCHING")
                .param("action", "ARCHIVE")
                .param("protected", "include"))
            .andExpect(status().isConflict())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Protected senders blocked this preview")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Include protected senders in this preview")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret-provider-id"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("/scopes/"))));

        mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "SENDERS")
                .param("mode", "ALL_MATCHING")
                .param("action", "ARCHIVE")
                .param("protected", "include")
                .param("includeProtected", "true")
                .param("reasonCode", "USER_CONFIRMED"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Exact impact preview")));
        assertThat(audits.count()).isEqualTo(1);
        assertThat(scopes.count()).isEqualTo(1);
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void mvcRequiresAuthenticationAndCsrfAndRendersFullPageOrSingleFragment() throws Exception {
        UserAccount account = account("mvc@example.com");
        seed(account, "private-message-id", "sender@example.com", true);

        mockMvc.perform(post("/api/selection-preview")
                .with(authentication(auth(account.getEmail())))
                .param("surface", "UNREAD")
                .param("mode", "ALL_MATCHING")
                .param("action", "REVIEW"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .param("surface", "UNREAD")
                .param("mode", "ALL_MATCHING")
                .param("action", "REVIEW"))
            .andExpect(status().is3xxRedirection());

        String full = mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .param("surface", "UNREAD")
                .param("mode", "ALL_MATCHING")
                .param("action", "REVIEW")
                .param("search", "sender")
                .param("sort", "oldest_first"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(full).contains("<!DOCTYPE html>", "<main", "<h1>", "Exact impact preview");
        assertThat(full).contains("search=sender", "sort=oldest_first", "Change selection");
        assertThat(full).contains(
            "Stage for Review", "Run after this confirmation",
            "Both modes use this exact scope and the same safety checks.");
        assertThat(full).doesNotContain(
            "private-message-id", "/scopes/", "/queue", "/undo");

        String fragment = mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .with(authentication(auth(account.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "UNREAD")
                .param("mode", "ALL_MATCHING")
                .param("action", "REVIEW"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(fragment.strip()).startsWith("<section").endsWith("</section>");
        assertThat(fragment).doesNotContain("<html", "<main", "private-message-id");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void malformedAndForeignSelectionsFailClosedWithoutRows() throws Exception {
        UserAccount owner = account("owner@example.com");
        UserAccount foreign = account("foreign@example.com");
        MessageMetadata foreignRow =
            seed(foreign, "foreign-provider-id", "foreign@example.com", true);

        mockMvc.perform(post("/api/selection-preview")
                .with(csrf())
                .with(authentication(auth(owner.getEmail())))
                .header("HX-Request", "true")
                .param("surface", "UNREAD")
                .param("mode", "INDIVIDUAL")
                .param("action", "ARCHIVE")
                .param("rowKey", DecisionRowKey.unread(foreign.getId(), foreignRow.getId())))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Selection could not be previewed")));
        assertThat(scopes.count()).isZero();
        assertThat(audits.count()).isZero();
        assertThat(actions.count()).isZero();
    }

    @Test
    void storedScopesDoNotDriftAndNewKindsCannotReachLegacyQueue() throws Exception {
        UserAccount account = account("immutable@example.com");
        MessageMetadata row = seed(account, "immutable-id", "sender@example.com", true);
        UnreadQuery query = unreadQuery(String.valueOf(account.getId()));
        SelectionScopePreview preview = materialization.preview(
            List.of(account.getId()), request(query, SelectionMode.ALL_MATCHING, List.of()));
        ActionScope scope = scopes.findAll().getFirst();
        row.setUnread(false);
        metadata.saveAndFlush(row);

        assertThat(scope.getMessageIds()).containsExactly("immutable-id");
        assertThat(materialization.preview(
            List.of(account.getId()), request(query, SelectionMode.ALL_MATCHING, List.of()))
            .state()).isEqualTo(State.EMPTY);
        mockMvc.perform(post("/api/actions/scopes/" + scope.getId() + "/queue")
                .with(csrf())
                .with(authentication(auth(account.getEmail()))))
            .andExpect(status().isGone());
        assertThat(actions.count()).isZero();
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void concurrentEquivalentPreviewsReuseOneImmutableScope() throws Exception {
        UserAccount account = account("race@example.com");
        seed(account, "race-id", "race@example.com", true);
        UnreadQuery query = unreadQuery(String.valueOf(account.getId()));
        SelectionRequest request = request(query, SelectionMode.ALL_MATCHING, List.of());
        Callable<SelectionScopePreview> work =
            () -> materialization.preview(List.of(account.getId()), request);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<SelectionScopePreview> results =
                executor.invokeAll(List.of(work, work)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }).toList();
            assertThat(results).allSatisfy(result ->
                assertThat(result.state()).isEqualTo(State.READY));
            assertThat(results).extracting(result -> result.scopes().getFirst().scopeHash())
                .containsOnly(results.getFirst().scopes().getFirst().scopeHash());
        }
        assertThat(scopes.count()).isEqualTo(1);
    }

    private void assertExact(SelectionRequest request, List<String> expected) {
        SelectionScopePreview preview =
            materialization.preview(accountIds(request), request);
        assertThat(preview.state()).isEqualTo(State.READY);
        String hash = preview.scopes().getFirst().scopeHash();
        assertThat(scopes.findByAccountIdAndScopeHash(
            accountIds(request).getFirst(), hash).orElseThrow().getMessageIds())
            .containsExactlyElementsOf(expected.stream().sorted().toList());
    }

    private List<Long> accountIds(SelectionRequest request) {
        long account = Long.parseLong(request.accountSelection());
        return List.of(account);
    }

    private SelectionRequest request(
            SenderQuery query, SelectionMode mode, List<String> rowKeys) {
        return new SelectionRequest(
            SelectionSurface.SENDERS, mode, query, null, rowKeys,
            PreviewAction.ARCHIVE, false, null);
    }

    private SelectionRequest request(
            UnreadQuery query, SelectionMode mode, List<String> rowKeys) {
        return new SelectionRequest(
            SelectionSurface.UNREAD, mode, null, query, rowKeys,
            PreviewAction.REVIEW, false, null);
    }

    private static SenderQuery senderQuery(
            String account, SenderQuery.Protection protection) {
        return new SenderQuery(
            account, "", SenderQuery.Window.ALL, SenderQuery.Type.ALL, SenderQuery.Risk.ALL,
            SenderQuery.UnreadPressure.ALL, SenderQuery.QueueState.ALL,
            protection, SenderQuery.Sort.VOLUME_DESC, 0);
    }

    private static UnreadQuery unreadQuery(String account) {
        return new UnreadQuery(
            account, "", UnreadQuery.Type.ALL, UnreadQuery.Age.ALL,
            UnreadQuery.Waiting.ALL, UnreadQuery.ThreadShape.ALL,
            UnreadQuery.Recommendation.ALL, "", UnreadQuery.Provider.ALL,
            UnreadQuery.Sort.OLDEST_FIRST, 0);
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("provider-" + email);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.saveAndFlush(account);
    }

    private MessageMetadata seed(
            UserAccount account, String messageId, String sender, boolean unread) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(messageId);
        row.setProviderThreadId("thread-" + messageId);
        row.setFromAddress(sender);
        row.setFromDisplayName(sender);
        row.setFromDomain(sender.substring(sender.indexOf('@') + 1));
        row.setSubjectHeader(messageId);
        row.setReceivedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        row.setUnread(unread);
        row.setLabels(List.of("INBOX"));
        row.setMessageIdValues(List.of("<" + messageId + ">"));
        return metadata.saveAndFlush(row);
    }

    private void seedMany(
            UserAccount account, int count, String sender, String prefix) {
        ArrayList<MessageMetadata> rows = new ArrayList<>(count);
        Instant now = Instant.now().minus(1, ChronoUnit.DAYS);
        for (int index = 0; index < count; index++) {
            MessageMetadata row = new MessageMetadata();
            row.setAccountId(account.getId());
            row.setProvider("google");
            row.setProviderMessageId(prefix + index);
            row.setFromAddress(sender);
            row.setFromDisplayName(sender);
            row.setFromDomain(sender.substring(sender.indexOf('@') + 1));
            row.setSubjectHeader(prefix + index);
            row.setReceivedAt(now);
            row.setUnread(true);
            row.setLabels(List.of("INBOX"));
            rows.add(row);
        }
        metadata.saveAllAndFlush(rows);
    }

    private void protect(UserAccount account, String sender) {
        jdbc.update("""
            INSERT INTO protected_senders(account_id,sender_address,source)
            VALUES (?,?,'USER')
            """, account.getId(), sender);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("sub", "sub-" + email);
        attributes.put("name", email);
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
