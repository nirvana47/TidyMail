package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.ActionHistoryModel;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.entity.ActionIntent;
import com.tidymail.entity.ActionScope;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.BulkActionRequest;
import com.tidymail.mail.BulkActionResult;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.ActionConflictRepository;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionIntentRepository;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.ReviewActionQueryRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.ActionApprovalService;
import com.tidymail.service.ActionConflictService;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.ExactScopeExecutor;
import com.tidymail.service.EmailStatsModalService;
import com.tidymail.service.ReviewOperationIntentService;
import com.tidymail.service.ActionFingerprints;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "tidymail.review.max-actions=100",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET="
})
class Phase12ReviewWebTest {
    private static final String EMAIL = "review-owner@example.com";
    private static final String OWNER = EMAIL + ":google";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository accounts;
    @Autowired UserActionRepository actions;
    @Autowired ActionScopeRepository scopes;
    @Autowired ActionIntentRepository actionIntents;
    @Autowired ActionConflictRepository conflictRows;
    @Autowired ActionExecutionRepository executions;
    @Autowired MessageMetadataRepository metadata;
    @Autowired ReviewActionQueryRepository reviewQueries;
    @Autowired ActionScopeService scopeService;
    @Autowired ActionConflictService conflicts;
    @Autowired ActionApprovalService approvals;
    @Autowired ExactScopeExecutor executor;
    @Autowired EmailStatsModalService emailStats;
    @Autowired ReviewOperationIntentService operationIntents;

    @MockBean(name = "gmailMailService")
    MailProvider gmail;
    @MockBean(name = "outlookMailService")
    MailProvider outlook;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_actions");
        jdbc.update("DELETE FROM action_scopes");
        jdbc.update("DELETE FROM message_metadata");
        jdbc.update("DELETE FROM user_accounts");
        reset(gmail, outlook);
    }

    @Test
    @DisplayName("ACTN-04 grouped Review uses real owned facts and accessible markup")
    void ACTN_04_groupedQueueUsesRealOwnedFactsAndPrivateAccessibleMarkup()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAccount other = account("foreign@example.com");
        create(account, "ARCHIVE", "Archive digest", List.of("a", "shared"), "QUEUED");
        create(account, "TRASH", "Trash digest", List.of("shared", "t"), "QUEUED");
        create(account, "MARK_READ", "Read digest", List.of("m"), "QUEUED");
        create(account, "UNSUBSCRIBE", "List sender", List.of("u"), "QUEUED");
        create(account, "KEEP", "Keep sender", List.of("k"), "QUEUED");
        create(account, "PROTECT", "Protected sender", List.of("p"), "QUEUED");
        create(account, "REVIEW", "Ambiguous sender", List.of("r"), "BLOCKED");
        create(other, "ARCHIVE", "Foreign canary", List.of("foreign-id"), "QUEUED");
        conflicts.recompute(List.of(account.getId()));
        ReviewQuery query = query(account);
        assertThat(reviewQueries.metrics(List.of(account.getId()), query))
            .satisfies(metrics -> {
                assertThat(metrics.queuedActions()).isEqualTo(7);
                assertThat(metrics.exactTargets()).isEqualTo(9);
                assertThat(metrics.openConflicts()).isOne();
                assertThat(metrics.eligibleActions()).isEqualTo(3);
            });

        String body = mvc.perform(get("/review")
                .param("view", "queued")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("id=\"review-queue\"", "class=\"review-data-view review-queue\"",
                ">Queue</h2>", "class=\"review-filter-form\"",
                "class=\"review-data-table review-table\"", "Archive digest", "Trash digest",
                "Read digest", "List sender", "Keep sender", "Protected sender",
                "Ambiguous sender", "Review conflict", "Fresh unsubscribe consent required",
                "name=\"_csrf\"", ">Execute<", ">Undo<", "View Emails",
                "Select all visible eligible rows", "aria-live=\"polite\"",
                "tabindex=\"0\"", "view=history", ">History<",
                "<th scope=\"col\">Sender</th>", "<th scope=\"col\">Source</th>",
                "<th scope=\"col\">Scope</th>", "<th scope=\"col\">Actions</th>")
            .doesNotContain("Foreign canary", "foreign-id", "href=\"#\"",
                  "metric-strip", "Queued action metrics", "Exact targets",
                  "Conflicts to resolve", "Eligible to execute",
                  "fragments/workspace-state", "workspace-state", "Showing partial indexed data",
                  "state-summary", "sync-form",
                  "Apply approved actions", ">Delete<", "<th scope=\"col\">Queued</th>",
                "cdn.jsdelivr", "unpkg.com");
        assertThat(body.indexOf("class=\"page-header\"")).isLessThan(
            body.indexOf("aria-label=\"Review views\""));
        assertThat(body.indexOf("aria-label=\"Review views\"")).isLessThan(
            body.indexOf("id=\"review-queue\""));
        assertThat(body.indexOf("class=\"review-filter-form\"")).isLessThan(
            body.indexOf("class=\"review-data-table-wrap table-scroll\""));
        assertThat(count(body, "<main")).isEqualTo(1);
        assertThat(count(body, "<h1")).isEqualTo(1);
        verifyNoInteractions(gmail, outlook);

        UserAction unsubscribe = actions.findAll().stream()
            .filter(action -> "UNSUBSCRIBE".equals(action.getActionType()))
            .findFirst().orElseThrow();
        String confirmation = mvc.perform(post(
                "/review/unsubscribe/" + unsubscribe.getPublicId() + "/confirm")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(confirmation).contains(
            "Confirm unsubscribe request", "name=\"confirmation\"",
            "name=\"unsubscribeMethod\"", "name=\"_csrf\"");
        verifyNoInteractions(gmail, outlook);

        mvc.perform(get("/review").param("account", String.valueOf(account.getId()))
                .param("action", "archive", "trash")
                .with(authentication(auth())))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/review").param("account", String.valueOf(other.getId()))
                .with(authentication(auth())))
            .andExpect(status().isNotFound());
        mvc.perform(post("/review/approve-all/confirm")
                .param("account", String.valueOf(account.getId()))
                .param("unexpectedAuthority", "true")
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/review/approve-all")
                .param("account", String.valueOf(account.getId()))
                .param("operationIntent", "first", "second")
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reviewContractUsesStrictLifoNormalizedSourcesAndMaterializedCounts()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAction senders = create(
            account, "ARCHIVE", "Old sender", List.of("old-1", "old-2"), "QUEUED");
        UserAction unread = create(
            account, "MARK_READ", "New unread", List.of("new-1"), "QUEUED");
        UserAction unsubscribe = create(
            account, "UNSUBSCRIBE", "List sender", List.of("list-1"), "QUEUED");
        UserAction legacy = create(
            account, "KEEP", "Legacy sender", List.of("legacy-1"), "QUEUED");
        Instant tied = Instant.parse("2026-08-10T10:00:00Z");
          jdbc.update("""
              UPDATE user_actions
                 SET created_at=?,
                     queued_at=CASE WHEN id=? THEN
                         CAST(? AS TIMESTAMP WITH TIME ZONE)
                         ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,
                     "sequence"=CASE id
                         WHEN ? THEN 103 WHEN ? THEN 104
                         WHEN ? THEN 101 ELSE 102 END
               WHERE account_id=?
              """,
              java.sql.Timestamp.from(tied.minusSeconds(60)),
              unsubscribe.getId(), java.sql.Timestamp.from(tied.plusSeconds(1)),
              java.sql.Timestamp.from(tied),
              senders.getId(), unread.getId(), unsubscribe.getId(), account.getId());
        jdbc.update("""
            UPDATE user_actions SET decision_source=CASE id
                WHEN ? THEN 'SENDERS' WHEN ? THEN 'UNREAD'
                WHEN ? THEN 'UNSUBSCRIBE' ELSE 'LEGACY' END
             WHERE account_id=?
            """, senders.getId(), unread.getId(), unsubscribe.getId(), account.getId());
        jdbc.update("""
            DELETE FROM action_scope_targets
             WHERE scope_id=? AND scope_ordinal=1
            """, senders.getActionScopeId());

        var rows = reviewQueries.page(List.of(account.getId()), query(account));
        assertThat(rows).extracting(row -> row.actionPublicId())
            .containsExactly(
                  unsubscribe.getPublicId(), unread.getPublicId(),
                  senders.getPublicId(), legacy.getPublicId());
        assertThat(rows).extracting(row -> row.sourceLabel())
              .containsExactly("Unsubscribe", "Unread", "By Sender", "Legacy");
          assertThat(rows.get(2).targetCount()).isOne();

        String body = mvc.perform(get("/review")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
          assertThat(body.indexOf("List sender"))
              .isLessThan(body.indexOf("New unread"));
          assertThat(body.indexOf("New unread"))
              .isLessThan(body.indexOf("Old sender"));
          assertThat(body.indexOf("Old sender"))
              .isLessThan(body.indexOf("Legacy sender"));
        assertThat(body).doesNotContain(tied.toString());
    }

    @Test
    void executeAndUndoConfirmationsUseExactCountsAndActionSpecificWarnings()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAction trash = create(
            account, "TRASH", "Trash sender", List.of("trash-1", "trash-2"), "QUEUED");
        jdbc.update("""
            DELETE FROM action_scope_targets
             WHERE scope_id=? AND scope_ordinal=1
            """, trash.getActionScopeId());

        String execute = mvc.perform(post("/review/approve-selected/confirm")
                .param("account", String.valueOf(account.getId()))
                .param("selectedAction", trash.getPublicId().toString())
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(execute).contains(
            "affecting exactly <strong>1</strong>", "Trash and provider recovery window",
            "provider controls the recovery window", ">Execute 1<");

        String undo = mvc.perform(post("/review/undo-selected/confirm")
                .param("account", String.valueOf(account.getId()))
                .param("selectedAction", trash.getPublicId().toString())
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(undo).contains(
            "Confirm undo", "affecting exactly", "<strong>1</strong>",
            "No mailbox change has run", ">Undo 1<");

        UserAction archive = create(
            account, "ARCHIVE", "Archive sender", List.of("archive-1"), "QUEUED");
        String standard = mvc.perform(post("/review/approve-selected/confirm")
                .param("account", String.valueOf(account.getId()))
                .param("selectedAction", archive.getPublicId().toString())
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(standard).contains("Archive warning")
            .doesNotContain("provider recovery window");
    }

    @Test
    void reusableEmailStatsUsesAffectedEmailsSenderHistoryAndOwnedActions()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAccount foreign = account("foreign@example.com");
        UserAction archive = create(
            account, "ARCHIVE", "Scoped sender", List.of("scope-1", "scope-2"), "QUEUED");
        MessageMetadata outside = metadata.findByAccountIdAndProviderMessageIdIn(
            account.getId(), List.of("scope-1")).getFirst();
        MessageMetadata extra = new MessageMetadata();
        extra.setAccountId(account.getId());
        extra.setProvider("google");
        extra.setProviderMessageId("outside-scope");
        extra.setFromAddress(outside.getFromAddress());
        extra.setFromDomain("example.com");
        extra.setSubjectHeader("Outside scope");
        extra.setReceivedAt(Instant.parse("2026-08-01T12:00:00Z"));
        metadata.saveAndFlush(extra);

        UserAction unsubscribe = create(
            account, "UNSUBSCRIBE", "List sender", List.of("list-source"), "QUEUED");
        String listAddress = "list-sender@example.com";
        unsubscribe.setSenderGroupKey(listAddress);
        actions.saveAndFlush(unsubscribe);
        MessageMetadata listHistory = new MessageMetadata();
        listHistory.setAccountId(account.getId());
        listHistory.setProvider("google");
        listHistory.setProviderMessageId("list-history");
        listHistory.setFromAddress(listAddress);
        listHistory.setFromDomain("example.com");
        listHistory.setSubjectHeader("Earlier list email");
        listHistory.setReceivedAt(Instant.parse("2026-07-01T12:00:00Z"));
        metadata.saveAndFlush(listHistory);
        UserAction foreignAction = create(
            foreign, "ARCHIVE", "Foreign sender", List.of("foreign-message"), "QUEUED");

        var affected = emailStats.review(
            List.of(account.getId()), String.valueOf(account.getId()),
            archive.getPublicId());
        assertThat(affected.scopeLabel()).isEqualTo("Affected emails");
        assertThat(affected.emailCount()).isEqualTo(2);
        assertThat(affected.emails()).extracting(row -> row.subject())
            .doesNotContain("Outside scope");

        var history = emailStats.review(
            List.of(account.getId()), String.valueOf(account.getId()),
            unsubscribe.getPublicId());
        assertThat(history.scopeLabel()).isEqualTo("Sender history");
        assertThat(history.emails()).extracting(row -> row.subject())
            .contains("Earlier list email");
        assertThat(history.distribution()).isNotEmpty();

        mvc.perform(get("/review/actions/" + foreignAction.getPublicId() + "/emails")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isNotFound());
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    @DisplayName("ACTN-05 selected cancellation and all-eligible approval are atomic")
    void ACTN_05_selectedIsAtomicCancellationIsIntentBoundAndAllIncludesPageTwo()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAction first = create(
            account, "ARCHIVE", "First action", List.of("first"), "QUEUED");
        UserAction second = create(
            account, "MARK_READ", "Second action", List.of("second"), "QUEUED");
        for (int index = 0; index < 24; index++) {
            create(account, "KEEP", "Keep " + index, List.of("keep-" + index), "QUEUED");
        }
        when(gmail.batchModify(any(), any())).thenAnswer(invocation -> {
            BulkActionRequest request = invocation.getArgument(1);
            return BulkActionResult.ok(request.messageIds().size(), request.messageIds());
        });
        ReviewQuery query = query(account);

        var all = approvals.confirmAll(OWNER, List.of(account.getId()), query);
        assertThat(all.actionCount()).isEqualTo(26);
        assertThat(all.targetCount()).isEqualTo(26);

        assertThatThrownBy(() -> approvals.confirmSelected(
            OWNER, List.of(account.getId()), query,
            List.of(first.getPublicId(), first.getPublicId())))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(executions.count()).isZero();

        var confirmation = approvals.confirmSelected(
            OWNER, List.of(account.getId()), query,
            List.of(second.getPublicId(), first.getPublicId()));
        var receipt = approvals.executeSelected(
            OWNER, List.of(account.getId()), query,
            confirmation.actionPublicIds(), confirmation.intent());
        assertThat(receipt.actionCount()).isEqualTo(2);
        assertThat(receipt.requested()).isEqualTo(2);
        assertThat(executions.count()).isEqualTo(2);
        var replay = approvals.executeSelected(
            OWNER, List.of(account.getId()), query,
            confirmation.actionPublicIds(), confirmation.intent());
        assertThat(replay.actions()).allSatisfy(action ->
            assertThat(action.dispatched()).isFalse());
        assertThat(executions.count()).isEqualTo(2);

        ReviewQuery keepQuery = new ReviewQuery(
            "queued", String.valueOf(account.getId()), "keep", "all", "", 0);
        var keepConfirmation = approvals.confirmAll(
            OWNER, List.of(account.getId()), keepQuery);
        var keepReceipt = approvals.executeAll(
            OWNER, List.of(account.getId()), keepQuery,
            keepConfirmation.actionPublicIds(), keepConfirmation.intent());
        assertThat(keepReceipt.actionCount()).isEqualTo(24);
        var keepReplay = approvals.executeAll(
            OWNER, List.of(account.getId()), keepQuery,
            keepConfirmation.actionPublicIds(), keepConfirmation.intent());
        assertThat(keepReplay.actions()).allSatisfy(action ->
            assertThat(action.dispatched()).isFalse());
        assertThat(executions.count()).isEqualTo(26);

        UserAction cancellable = create(
            account, "KEEP", "Cancel me", List.of("cancel"), "QUEUED");
        String cancelIntent = operationIntents.issue(
            OWNER, "CANCEL",
            ReviewOperationIntentService.subject(cancellable.getPublicId()));
        mvc.perform(post("/api/actions/" + cancellable.getPublicId() + "/cancel")
                .param("account", String.valueOf(account.getId()))
                .param("operationIntent", cancelIntent)
                .with(authentication(auth())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/actions/" + cancellable.getPublicId() + "/cancel")
                .param("account", String.valueOf(account.getId()))
                .param("operationIntent", cancelIntent)
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(actions.findById(cancellable.getId()).orElseThrow().getStatus())
            .isEqualTo("CANCELLED");
        assertThat(scopes.findById(cancellable.getActionScopeId())).isPresent();
    }

    @Test
    void selectedApprovalConfirmationRendersOneCsrfParameter() throws Exception {
        UserAccount account = account(EMAIL);
        UserAction action = create(
            account, "MARK_READ", "Single unread message",
            List.of("single-message"), "QUEUED");

        String body = mvc.perform(post("/review/approve-selected/confirm")
                .param("view", "queued")
                .param("account", String.valueOf(account.getId()))
                .param("selectedAction", action.getPublicId().toString())
                .with(authentication(auth())).with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        int start = body.indexOf("<section id=\"review-confirmation\"");
        int end = body.indexOf("</section>", start);
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        String confirmation = body.substring(start, end);
        assertThat(confirmation.split("name=\"_csrf\"", -1)).hasSize(2);
    }

    @Test
    @DisplayName("ACTN-06 exact conflicts are account-local and scope preserving")
    void ACTN_06_exactConflictsAreAccountLocalAndResolutionNeverChangesScopes() {
        UserAccount account = account(EMAIL);
        UserAccount second = account("other-owned@example.com");
        UserAction archive = create(
            account, "ARCHIVE", "Summary lies",
            List.of("provider-secret-one", "provider-secret-shared"), "QUEUED");
        UserAction trash = create(
            account, "TRASH", "Different summary",
            List.of("provider-secret-shared", "provider-secret-two"), "QUEUED");
        create(second, "TRASH", "Cross account",
            List.of("provider-secret-shared"), "QUEUED");
        List<String> archiveIds =
            scopes.findById(archive.getActionScopeId()).orElseThrow().getMessageIds();
        List<String> trashIds =
            scopes.findById(trash.getActionScopeId()).orElseThrow().getMessageIds();

        conflicts.recompute(List.of(account.getId(), second.getId()));
        assertThat(conflictRows.findAll()).singleElement().satisfies(conflict -> {
            assertThat(conflict.getAccountId()).isEqualTo(account.getId());
            assertThat(conflict.getOverlapCount()).isEqualTo(1);
            assertThat(conflict.getReasonCode()).isEqualTo("INCOMPATIBLE_ACTIONS");
        });
        var detail = conflicts.detail(
            OWNER, List.of(account.getId()), conflictRows.findAll().getFirst().getPublicId());
        assertThat(detail.toString()).doesNotContain(
            "provider-secret-one", "provider-secret-shared", "provider-secret-two");
        conflicts.resolve(
            OWNER, List.of(account.getId()), detail.conflictPublicId(),
            "CANCEL_HIGHER", detail.resolutionIntent());

        assertThat(conflictRows.findById(conflictRows.findAll().getFirst().getId())
            .orElseThrow().getStatus()).isEqualTo("RESOLVED");
        assertThat(scopes.findById(archive.getActionScopeId()).orElseThrow().getMessageIds())
            .containsExactlyElementsOf(archiveIds);
        assertThat(scopes.findById(trash.getActionScopeId()).orElseThrow().getMessageIds())
            .containsExactlyElementsOf(trashIds);
    }

    @Test
    @DisplayName("ACTN-10 History filters legacy truth and provider-free rendering")
    void ACTN_10_historyIsProviderFreeFilteredAndTruthfulForLegacyRows()
            throws Exception {
        UserAccount account = account(EMAIL);
        create(account, "ARCHIVE", "history@example.com",
            List.of("PRIVATE_HISTORY_PROVIDER_ID"), "APPLIED");
        create(account, "TRASH", "other@example.com",
            List.of("OTHER_PRIVATE_ID"), "FAILED");

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .param("action", "archive")
                .param("status", "applied")
                .param("target", "history@example.com")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains(">History</h2>", "class=\"review-data-view\"",
                "class=\"review-filter-form review-filters\"",
                "class=\"review-data-table history-table\"", "history@example.com",
                "Legacy action - per-target outcome detail is unavailable.",
                  "view=history", "value=\"archive\" selected=\"selected\"",
                  "value=\"applied\" selected=\"selected\"")
            .doesNotContain(
                "review-metrics", "History metrics", "Succeeded targets",
                "workspace-state", "Showing partial indexed data", "state-summary", "sync-form",
                "other@example.com", "PRIVATE_HISTORY_PROVIDER_ID",
                "OTHER_PRIVATE_ID", "Export CSV", "<canvas");
        assertThat(body.indexOf("aria-label=\"Review views\"")).isLessThan(
            body.indexOf("id=\"review-history\""));
        assertThat(body.indexOf("class=\"review-filter-form review-filters\"")).isLessThan(
            body.indexOf("class=\"review-data-table-wrap history-table-wrap\""));
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_10_historySumsSafeReasonGroupsAndUsesProviderNeutralLabels()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAction action = create(
            account, "ARCHIVE", "reason@example.com", List.of("R1", "R2"), "QUEUED");
        when(gmail.batchModify(any(), any())).thenReturn(new BulkActionResult(
            2, 0, 2, List.of(), Map.of("R1", "one", "R2", "two"), Instant.now()));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());
        long executionId = executions.findByActionIdAndOperationKind(
            action.getId(), "FORWARD").orElseThrow().getId();
        jdbc.update("""
            UPDATE action_execution_targets
               SET reason_code = CASE scope_ordinal
                   WHEN 0 THEN 'PROVIDER_RESULT_INVALID'
                   ELSE 'NOT_DISPATCHED' END
             WHERE execution_id = ?
            """, executionId);

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("2 targets: Request could not be completed",
                EMAIL + " (Google)", "value=\"review\"",
                "value=\"in_progress\"", "value=\"undone\"")
            .doesNotContain("1 targets: Request could not be completed");
        verify(gmail).batchModify(any(), any());
        verifyNoInteractions(outlook);
    }

    @Test
    void ACTN_10_historyDoesNotAggregateSuccessfulTargetsAsFailureReasons()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAction action = create(
            account, "ARCHIVE", "partial@example.com", List.of("R1", "R2"), "QUEUED");
        when(gmail.batchModify(any(), any())).thenReturn(
            BulkActionResult.withPartialFailures(
                2, List.of("R1"), Map.of("R2", "provider rejection")));
        executor.execute(account.getId(), action.getPublicId(), UUID.randomUUID());

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("1 targets: Provider rejected the request")
            .doesNotContain("Request could not be completed");
        verify(gmail).batchModify(any(), any());
        verifyNoInteractions(outlook);
    }

    @Test
    void ACTN_10_everyHistoryFilterCombinationWildcardAndStablePageRoundTrips()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAccount other = account("history-other@example.com");
        Instant tied = Instant.parse("2026-08-09T12:00:00Z");
        for (int index = 0; index < 30; index++) {
            create(account, index % 2 == 0 ? "ARCHIVE" : "TRASH",
                "history-%02d@example.com".formatted(index),
                List.of("history-" + index),
                index % 3 == 0 ? "FAILED" : "APPLIED");
        }
        create(account, "ARCHIVE", "literal%_target@example.com",
            List.of("literal-wildcard"), "APPLIED");
        create(other, "ARCHIVE", "foreign-history@example.com",
            List.of("foreign-history"), "APPLIED");
        jdbc.update("""
            UPDATE user_actions
               SET created_at=?, completed_at=?
             WHERE account_id IN (?,?)
            """, java.sql.Timestamp.from(tied), java.sql.Timestamp.from(tied),
            account.getId(), other.getId());

        var pageZero = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .param("from", "2026-08-09")
                .param("to", "2026-08-09")
                .param("action", "archive")
                .param("status", "applied")
                .param("target", "history")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn();
        ActionHistoryModel first = (ActionHistoryModel) pageZero
            .getModelAndView().getModel().get("history");
        assertThat(first.rows()).isNotEmpty()
            .allSatisfy(row -> {
                assertThat(row.action()).isEqualTo("Archive");
                assertThat(row.status()).isEqualTo("Applied");
                assertThat(row.accountLabel()).contains(EMAIL);
                assertThat(row.targetLabel()).contains("history");
                assertThat(row.occurredAt()).isEqualTo(tied);
            });
        assertThat(first.canonicalUrl()).contains(
            "view=history", "account=" + account.getId(), "from=2026-08-09",
            "to=2026-08-09", "action=archive", "status=applied", "target=history");

        ActionHistoryModel allFirst = historyModel(account, 0);
        ActionHistoryModel allSecond = historyModel(account, 1);
        assertThat(allFirst.rows()).hasSize(25);
        assertThat(allSecond.rows()).hasSize(6);
        assertThat(allFirst.rows()).extracting(row -> row.actionPublicId())
            .doesNotContainAnyElementsOf(
                allSecond.rows().stream().map(row -> row.actionPublicId()).toList());
        assertThat(allFirst.nextUrl()).contains(
            "view=history", "account=" + account.getId(), "page=1");
        assertThat(allSecond.previousUrl()).contains(
            "view=history", "account=" + account.getId());
        assertThat(allFirst.rows().getLast().occurredAt())
            .isEqualTo(allSecond.rows().getFirst().occurredAt());

        String literal = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .param("target", "%_")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(literal).contains("literal%_target@example.com")
            .doesNotContain("history-00@example.com", "foreign-history@example.com");
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_10_historyLinksTheOwnedV8UnsubscribeOutcomeAndCompensationAnchor()
            throws Exception {
        UserAccount account = account(EMAIL);
        UserAction unsubscribe = create(
            account, "UNSUBSCRIBE", "linked-list@example.com",
            List.of("linked-source"), "VERIFICATION_PENDING");
        long attemptId = insertUnsubscribeAttempt(account.getId());
        long executionId = insertLinkedExecution(unsubscribe, attemptId);
        jdbc.update("""
            UPDATE user_actions SET completed_at=? WHERE id=?
            """, java.sql.Timestamp.from(Instant.parse("2026-08-09T12:00:00Z")),
            unsubscribe.getId());

        String body = mvc.perform(get("/review")
                .param("view", "history")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body)
            .contains("Linked unsubscribe outcome: Verification pending",
                "No automatic undo", "opens in a new tab")
            .doesNotContain("linked-source", "unsubscribe.example/private");
        assertThat(executions.findById(executionId)).isPresent();
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void ACTN_05_allMutationRoutesRequireAuthenticationAndCsrfBeforeIo()
            throws Exception {
        UUID locator = UUID.randomUUID();
        List<String> routes = List.of(
            "/logout",
            "/api/metadata-sync",
            "/senders/_sync",
            "/api/action-commands",
            "/api/selection-preview",
            "/unsubscribe/execute",
            "/unsubscribe/manual-complete",
            "/api/actions/bulk-by-sender/sender@example.com",
            "/api/actions/scopes/1/queue",
            "/api/actions/" + locator + "/cancel",
            "/review/approve-selected/confirm",
            "/review/approve-all/confirm",
            "/review/approve-selected",
            "/review/approve-all",
            "/review/unsubscribe/" + locator + "/confirm",
            "/api/actions/" + locator + "/approve",
            "/review/actions/" + locator + "/undo/confirm",
            "/api/actions/" + locator + "/undo",
            "/review/actions/" + locator + "/remove-protection/confirm",
            "/api/actions/" + locator + "/remove-protection",
            "/review/conflicts/" + locator + "/resolve",
            "/api/actions/review/apply");
        for (String route : routes) {
            mvc.perform(post(route).with(authentication(auth())))
                .andExpect(status().isForbidden());
            mvc.perform(post(route)
                    .with(authentication(auth()))
                    .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
            int unauthenticated = mvc.perform(post(route).with(csrf()))
                .andReturn().getResponse().getStatus();
            assertThat(unauthenticated).as(route).isIn(302, 401);
        }
        assertThat(actions.count()).isZero();
        assertThat(executions.count()).isZero();
        verifyNoInteractions(gmail, outlook);
    }

    private ActionHistoryModel historyModel(UserAccount account, int page)
            throws Exception {
        var request = get("/review")
            .param("view", "history")
            .param("account", String.valueOf(account.getId()))
            .with(authentication(auth()));
        if (page > 0) request.param("page", String.valueOf(page));
        return (ActionHistoryModel) mvc.perform(request)
            .andExpect(status().isOk()).andReturn()
            .getModelAndView().getModel().get("history");
    }

    private long insertUnsubscribeAttempt(long accountId) {
        jdbc.update("""
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,
                method,latest_status
            ) VALUES (?,?,?,'RFC8058_POST','VERIFICATION_PENDING')
            """, accountId, "a".repeat(64), "b".repeat(64));
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM unsubscribe_attempts WHERE account_id=?",
            Long.class, accountId);
    }

    private long insertLinkedExecution(UserAction action, long attemptId) {
        jdbc.update("""
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,reserved_at,
                started_at,completed_at
            ) VALUES (?,?,'FORWARD','VERIFICATION_PENDING',?,1,1,0,0,1,0,
                      CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, action.getAccountId(), action.getId(),
            ActionFingerprints.sha256("linked-execution"));
        long executionId = jdbc.queryForObject(
            "SELECT MAX(id) FROM action_executions WHERE action_id=?",
            Long.class, action.getId());
        jdbc.update("""
            INSERT INTO action_execution_targets(
                execution_id,account_id,scope_id,scope_ordinal,target_fingerprint,
                dispatch_state,status,accepted_at,dispatched_at,completed_at,
                reason_code,unsubscribe_attempt_id
            )
            SELECT ?,account_id,scope_id,scope_ordinal,target_fingerprint,
                   'DISPATCHED','VERIFICATION_PENDING',CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                   'RESULT_UNKNOWN_AFTER_DISPATCH',?
              FROM action_scope_targets
             WHERE scope_id=? AND scope_ordinal=0
            """, executionId, attemptId, action.getActionScopeId());
        return executionId;
    }

    private UserAction create(
            UserAccount account,
            String actionType,
            String label,
            List<String> providerIds,
            String status) {
        providerIds.forEach(id -> {
            if (metadata.findByAccountIdAndProviderMessageIdIn(
                    account.getId(), List.of(id)).isEmpty()) {
                MessageMetadata row = new MessageMetadata();
                row.setAccountId(account.getId());
                row.setProvider("google");
                row.setProviderMessageId(id);
                row.setProviderThreadId("thread-" + id);
                row.setFromAddress(label.toLowerCase().replace(' ', '-') + "@example.com");
                row.setFromDomain("example.com");
                row.setLabels(List.of());
                row.setReceivedAt(Instant.now());
                metadata.saveAndFlush(row);
            }
        });
        var preview = scopeService.createOrGet(
            account.getId(), actionType,
            "UNSUBSCRIBE".equals(actionType) ? "UNSUBSCRIBE_CANDIDATE" : "UNREAD_SELECTION",
            providerIds,
            new ActionScopeTargetSummary(label, label + "@example.com", null, providerIds.size()),
            new ActionScopeRiskSummary(0, 0, List.of()));
        ActionScope scope = scopeService.requireScope(account.getId(), preview.scopeId());
        UserAction action = new UserAction();
        action.setAccountId(account.getId());
        action.setActionScopeId(scope.getId());
        action.setActionType(actionType);
        action.setMessageIds(scope.getMessageIds());
        action.setInverseActionData(new HashMap<>());
        action.setTargetKind(scope.getTargetKind());
        action.setTargetIdJson(Map.of());
        action.setAffectedCount(scope.getMessageCount());
        action.setStatus(status);
        action.setExecutionMode("STAGED");
        action.setDecisionSource("REVIEW");
        action.setExpiresAt(null);
        action = actions.saveAndFlush(action);
        ActionIntent intent = "UNSUBSCRIBE".equals(actionType)
            ? ActionIntent.unsubscribe(
                action.getId(), account.getId(), label, label,
                ActionFingerprints.sha256("candidate-" + label),
                ActionFingerprints.sha256("destination-" + label),
                "RFC8058_POST", Instant.now())
            : ActionIntent.messages(
                action.getId(), account.getId(), label, label, Instant.now());
        actionIntents.saveAndFlush(intent);
        return action;
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

    private static ReviewQuery query(UserAccount account) {
        LinkedMultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        values.add("view", "queued");
        values.add("account", String.valueOf(account.getId()));
        return ReviewQuery.parse(values);
    }

    private static OAuth2AuthenticationToken auth() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", EMAIL);
        attributes.put("name", "Review Owner");
        attributes.put("sub", "review-owner");
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
