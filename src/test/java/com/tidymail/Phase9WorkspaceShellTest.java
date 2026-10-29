package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.MessageMetadataQueryService;
import com.tidymail.service.WorkspaceModelService;
import com.tidymail.service.ReviewOperationIntentService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common"
})
class Phase9WorkspaceShellTest {

    private static final String EMAIL = "owner@example.com";

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository accountRepository;
    @Autowired UserActionRepository actionRepository;
    @Autowired SyncProgressRepository progressRepository;
    @Autowired MessageMetadataRepository metadataRepository;
    @Autowired MessageMetadataQueryService metadataQueryService;
    @Autowired CryptoUtil cryptoUtil;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReviewOperationIntentService reviewOperationIntents;

    @MockBean(name = "gmailMailService")
    MailProvider gmailProvider;

    @MockBean(name = "outlookMailService")
    MailProvider outlookProvider;

    @BeforeEach
    void clean() {
        reset(gmailProvider, outlookProvider);
        jdbcTemplate.update("DELETE FROM user_accounts");
    }

    @Test
    void allFiveRoutesRenderTruthfulSharedShellWithoutProviderCalls() throws Exception {
        UserAccount account = account(EMAIL, "google");
        OAuth2AuthenticationToken authentication = auth(EMAIL, "google");

        for (WorkspaceRoute route : WorkspaceRoute.values()) {
            reset(gmailProvider, outlookProvider);
            String body = perform(route.path(), null, authentication).getResponse().getContentAsString();

            assertThat(body)
                .contains("href=\"#workspace-main\"")
                .contains("<main id=\"workspace-main\">")
                .contains("<h1")
                .contains("Review, 0 queued actions")
                .contains(account.getEmail())
                .contains("name=\"_csrf\"")
                .contains("aria-label=\"Switch account\"")
                .contains(">Settings</summary>")
                .contains("value=\"" + account.getId() + "\"")
                .doesNotContain("href=\"#\"")
                .doesNotContain("/api/senders/_sync")
                .doesNotContain("sample@example.com")
                .doesNotContain("data-lucide")
                .doesNotContain("Configure view")
                .doesNotContain("Available in Phase");
            if (route == WorkspaceRoute.SENDERS || route == WorkspaceRoute.UNREAD) {
                assertThat(body)
                    .contains("Settings", "Expand all")
                    .doesNotContain("Export filtered")
                    .doesNotContain("decision-filters")
                    .doesNotContain("<button type=\"button\" disabled");
            } else if (route == WorkspaceRoute.UNSUBSCRIBE
                      || route == WorkspaceRoute.REVIEW) {
                assertThat(body)
                    .contains(route == WorkspaceRoute.REVIEW
                        ? "Selected actions" : "Unsubscribe controls")
                    .doesNotContain("<button type=\"button\" disabled");
                if (route == WorkspaceRoute.REVIEW) {
                    assertThat(body).contains("aria-label=\"Switch account\"", "Apply filters");
                }
            } else if (route == WorkspaceRoute.ANALYTICS) {
                assertThat(body)
                    .contains("Time period", "Group by")
                    .doesNotContain("<button type=\"button\" disabled");
            } else {
                assertThat(body)
                    .contains("Indexed messages", "Initial batch", "Freshness")
                    .doesNotContain("Time period", "Group by");
            }
            for (WorkspaceRoute linked : WorkspaceRoute.values()) {
                assertThat(body).contains("href=\"" + linked.path() + "\"");
            }
            assertThat(body)
                .contains("aria-current=\"page\"")
                .contains(">" + navLabel(route) + "</span>");
            assertThat(count(body, "<main")).isEqualTo(1);
            assertThat(count(body, "<h1")).isEqualTo(1);
            verifyNoInteractions(gmailProvider, outlookProvider);
        }
    }

    @Test
    void accountSelectionQueueCountAndAuthorizationFailClosed() throws Exception {
        UserAccount owned = account(EMAIL, "google");
        UserAccount microsoft = account(EMAIL, "microsoft");
        UserAccount foreign = account("foreign@example.com", "google");
        queuedAction(owned.getId());

        String selected = perform("/senders", String.valueOf(owned.getId()), auth(EMAIL, "google"))
            .getResponse().getContentAsString();
        assertThat(selected)
            .contains("aria-label=\"Switch account\"")
            .contains(">" + EMAIL + "</button>")
            .contains("Review, 1 queued action")
            .doesNotContain("All linked accounts")
            .doesNotContain("<option value=\"" + microsoft.getId() + "\"")
            .doesNotContain("foreign@example.com");

        mockMvc.perform(get("/senders").param("account", "all")
                .with(authentication(auth(EMAIL, "google"))))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/senders").param("account", "not-a-number")
                .with(authentication(auth(EMAIL, "google"))))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/senders").param("account", String.valueOf(microsoft.getId()))
                .with(authentication(auth(EMAIL, "google"))))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/senders").param("account", String.valueOf(foreign.getId()))
                .with(authentication(auth(EMAIL, "google"))))
            .andExpect(status().isNotFound());
        String microsoftShell = mockMvc.perform(get("/senders")
                .with(authentication(auth(EMAIL, "microsoft"))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(microsoftShell)
            .contains(EMAIL)
            .doesNotContain("(Outlook)", "(Gmail)");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void longHtmlSpecialAccountLabelRendersEscapedInsideRailMenu() throws Exception {
        String specialEmail = "owner+<script>&very.long.account.label.for.rail.truncation@example.com";
        UserAccount account = account(specialEmail, "google");

        String body = perform("/senders", String.valueOf(account.getId()), auth(specialEmail, "google"))
            .getResponse().getContentAsString();

        assertThat(body)
            .contains("class=\"rail-account-menu\"", "class=\"rail-account-email\"")
            .contains("owner+&lt;script&gt;&amp;very.long.account.label.for.rail.truncation@example.com")
            .contains("value=\"" + account.getId() + "\"")
            .doesNotContain(
                "owner+<script>&very.long.account.label.for.rail.truncation@example.com",
                "(Gmail)",
                "rail-nav-badge");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void dashboardAndStateFragmentHaveSingleTypedRouteOwnership() throws Exception {
        UserAccount account = account(EMAIL, "google");
        OAuth2AuthenticationToken authentication = auth(EMAIL, "google");

        mockMvc.perform(get("/dashboard").with(authentication(authentication)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("dashboard"));

        String fragment = mockMvc.perform(get("/workspace/state")
                .param("route", "senders")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(authentication)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(fragment.trim()).startsWith("<section").endsWith("</section>");
        assertThat(fragment).doesNotContain("<html").doesNotContain("<body").doesNotContain("<main");

        mockMvc.perform(get("/workspace/state")
                .param("route", "unknown")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(authentication)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void reviewCancellationEndpointRemainsMappedAndCsrfProtected() throws Exception {
        UserAccount account = account(EMAIL, "google");
        UserAction action = queuedAction(account.getId());
        OAuth2AuthenticationToken authentication = auth(EMAIL, "google");
        String intent = reviewOperationIntents.issue(
            EMAIL + ":google", "CANCEL",
            ReviewOperationIntentService.subject(action.getPublicId()));

        mockMvc.perform(post("/api/actions/" + action.getPublicId() + "/cancel")
                .param("account", String.valueOf(account.getId()))
                .param("operationIntent", intent)
                .with(authentication(authentication)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/actions/" + action.getPublicId() + "/cancel")
                .param("account", String.valueOf(account.getId()))
                .param("operationIntent", intent)
                .with(authentication(authentication))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(
                "/review?view=queued&account=" + account.getId()));

        assertThat(actionRepository.findById(action.getId()).orElseThrow().getStatus())
            .isEqualTo("CANCELLED");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void everyWorkspaceStateHasTruthfulSemanticsAndPollingContract() throws Exception {
        UserAccount account = account(EMAIL, "google");
        OAuth2AuthenticationToken authentication = auth(EMAIL, "google");

        assertState(authentication, WorkspaceState.EMPTY, "No indexed messages yet",
            "Metadata sync has not started", false, true);

        progress(account, "COMPLETE", Instant.now(), null);
        assertState(authentication, WorkspaceState.EMPTY, "No indexed messages yet",
            "completed sync with no metadata rows", false, true);

        resetState();
        progress(account, "RUNNING", null, null);
        assertState(authentication, WorkspaceState.LOADING, "Building your metadata index",
            "messages scanned", true, false);

        resetState();
        metadata(account, "active-partial");
        progress(account, "RUNNING", null, null);
        assertState(authentication, WorkspaceState.PARTIAL, "Showing partial indexed data",
            "indexed messages are usable", true, false);

        resetState();
        metadata(account, "two-lane-partial");
        progress(account, "COMPLETE", Instant.now(), null, "FRESHNESS");
        progress(account, "RUNNING", null, null, "BACKFILL");
        assertState(authentication, WorkspaceState.PARTIAL, "Showing partial indexed data",
            "indexed messages are usable", true, false);

        resetState();
        metadata(account, "terminal-partial");
        progress(account, "PARTIAL", null, null);
        assertState(authentication, WorkspaceState.PARTIAL, "Showing partial indexed data",
            "Sync metadata", false, true);

        resetState();
        metadata(account, "stale");
        progress(account, "COMPLETE", Instant.now().minus(Duration.ofHours(1)), null);
        assertState(authentication, WorkspaceState.STALE, "Indexed data may be stale",
            "Last successful metadata sync", false, true);

        resetState();
        progress(account, "FAILED", null, "temporary provider failure");
        assertState(authentication, WorkspaceState.ERROR, "Metadata sync failed",
            "temporary provider failure", false, true);

        resetState();
        metadata(account, "ready");
        progress(account, "COMPLETE", Instant.now(), null);
        assertState(authentication, WorkspaceState.READY, "Metadata index is ready",
            "messages are indexed", false, false);

        resetState();
        metadata(account, "failed-usable");
        progress(account, "FAILED", null, "https://provider.invalid/?token=secret");
        String partial = assertState(authentication, WorkspaceState.PARTIAL,
            "Showing partial indexed data", "Metadata sync encountered a provider error.",
            false, true);
        assertThat(partial).doesNotContain("provider.invalid").doesNotContain("token=secret");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void aggregateRulesCoverMixedAccountsAndLeastRecentFreshness() {
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        WorkspaceModelService.AccountSummary ready = summary(
            1L, 10, "COMPLETE", now.minusSeconds(60), null);
        WorkspaceModelService.AccountSummary active = summary(
            2L, 0, "RUNNING", null, null);
        WorkspaceModelService.Aggregate mixedActive =
            WorkspaceModelService.aggregate(List.of(ready, active), now);
        assertThat(mixedActive.state()).isEqualTo(WorkspaceState.PARTIAL);
        assertThat(mixedActive.syncActive()).isTrue();

        WorkspaceModelService.AccountSummary failed = summary(
            2L, 0, "FAILED", null, "Safe failure");
        WorkspaceModelService.Aggregate mixedFailed =
            WorkspaceModelService.aggregate(List.of(ready, failed), now);
        assertThat(mixedFailed.state()).isEqualTo(WorkspaceState.PARTIAL);
        assertThat(mixedFailed.safeDetail()).isEqualTo("Safe failure");

        WorkspaceModelService.AccountSummary stale = summary(
            2L, 5, "COMPLETE", now.minus(Duration.ofMinutes(16)), null);
        assertThat(WorkspaceModelService.aggregate(List.of(ready, stale), now).state())
            .isEqualTo(WorkspaceState.STALE);

        WorkspaceModelService.AccountSummary missingSuccess = summary(
            2L, 5, "COMPLETE", null, null);
        assertThat(WorkspaceModelService.aggregate(List.of(ready, missingSuccess), now).state())
            .isEqualTo(WorkspaceState.STALE);

        WorkspaceModelService.AccountSummary never = summary(2L, 0, null, null, null);
        WorkspaceModelService.Aggregate mixedNever =
            WorkspaceModelService.aggregate(List.of(ready, never), now);
        assertThat(mixedNever.state()).isEqualTo(WorkspaceState.PARTIAL);
        assertThat(mixedNever.completedEmpty()).isFalse();
    }

    @Test
    void templatesAndCssLockAccessibilityResponsiveAndLocalContracts() throws Exception {
        Path templates = Path.of("src/main/resources/templates");
        for (String name : List.of("senders", "unread", "unsubscribe", "review", "analytics")) {
            String source = Files.readString(templates.resolve(name + ".html"));
            assertThat(source)
                .contains("fragments/workspace-nav")
                .contains("fragments/workspace-rail")
                .contains("/static/css/workspace.css")
                .doesNotContain("tailwind")
                .doesNotContain("lucide")
                .doesNotContain("href=\"#\"");
        }
        assertThat(Files.readString(templates.resolve("review.html")))
            .contains("<h1>Review</h1>", "aria-label=\"Review views\"", ">Queue</a>", ">History</a>")
            .doesNotContain(
                "fragments/workspace-state",
                "Showing partial indexed data",
                "Sync metadata",
                "Approve staged exact scopes");
        assertThat(Files.readString(templates.resolve("unsubscribe.html")))
            .contains("class=\"unsubscribe-top-controls\"")
            .doesNotContain("fragments/workspace-state");
        String css = Files.readString(Path.of("src/main/resources/static/css/workspace.css"));
        assertThat(css)
            .contains("--shell-rail: 248px")
            .contains("--content-max: 1180px")
            .contains("grid-template-columns: var(--shell-rail) minmax(0, 1fr)")
            .contains("@media (max-width: 980px)")
            .contains("@media (max-width: 720px)")
            .contains("@media (max-width: 375px)")
            .contains("min-height: 44px")
            .contains(":focus-visible")
            .contains("outline: 2px solid var(--tm-primary)")
            .contains("@media (prefers-reduced-motion: reduce)")
            .contains(".rail-navigation a")
            .contains(".skip-link")
            .contains("overflow-x: auto", "overscroll-behavior-inline: contain")
            .doesNotContain("overflow-x: hidden");
    }

    @Test
    void indexedQueryAndWarmShellStayInsideCoarseBudgets() throws Exception {
        UserAccount account = account(EMAIL, "google");
        List<MessageMetadata> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 10_000; i++) {
            MessageMetadata row = metadataRow(account, "budget-" + i);
            row.setReceivedAt(now.minusSeconds(i));
            rows.add(row);
        }
        metadataRepository.saveAll(rows);
        progress(account, "COMPLETE", now, null);
        OAuth2AuthenticationToken authentication = auth(EMAIL, "google");

        metadataQueryService.freshness(List.of(account.getId()));
        long queryStarted = System.nanoTime();
        assertThat(metadataQueryService.freshness(List.of(account.getId())).messageCount())
            .isEqualTo(10_000);
        assertThat(Duration.ofNanos(System.nanoTime() - queryStarted))
            .isLessThan(Duration.ofSeconds(2));

        for (WorkspaceRoute route : WorkspaceRoute.values()) {
            perform(route.path(), null, authentication);
            long started = System.nanoTime();
            perform(route.path(), null, authentication);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("warm render time for %s", route.path())
                .isLessThan(Duration.ofSeconds(1));
        }
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    private MvcResult perform(
            String route, String account, OAuth2AuthenticationToken authentication) throws Exception {
        var request = get(route).with(authentication(authentication));
        if (account != null) {
            request.param("account", account);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private String assertState(
            OAuth2AuthenticationToken authentication,
            WorkspaceState expected,
            String heading,
            String detail,
            boolean polls,
            boolean hasSyncForm) throws Exception {
        reset(gmailProvider, outlookProvider);
        String body = mockMvc.perform(get("/workspace/state")
                .param("route", "senders")
                .with(authentication(authentication)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("state-" + expected.name().toLowerCase()).contains(heading).contains(detail);
        if (expected == WorkspaceState.ERROR) {
            assertThat(body).contains("role=\"alert\"").contains("aria-live=\"assertive\"");
        } else {
            assertThat(body).contains("role=\"status\"").contains("aria-live=\"polite\"");
        }
        if (polls) {
            assertThat(body).contains("hx-trigger=\"every 15s\"").contains("hx-get=\"/workspace/state");
        } else {
            assertThat(body).doesNotContain("hx-trigger=\"every 15s\"");
        }
        if (hasSyncForm) {
            assertThat(body).contains("action=\"/api/metadata-sync\"");
        } else {
            assertThat(body).doesNotContain("action=\"/api/metadata-sync\"");
        }
        verifyNoInteractions(gmailProvider, outlookProvider);
        return body;
    }

    private void resetState() {
        progressRepository.deleteAll();
        metadataRepository.deleteAll();
    }

    private UserAccount account(String email, String provider) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider(provider);
        account.setProviderAccountId(provider + "-" + email);
        account.setDisplayName("Workspace Owner");
        account.setEncryptedAccessToken(
            cryptoUtil.encrypt("access".getBytes(StandardCharsets.UTF_8)));
        account.setEncryptedRefreshToken(
            cryptoUtil.encrypt("refresh".getBytes(StandardCharsets.UTF_8)));
        account.setScopes(List.of("openid", "email", "profile"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accountRepository.save(account);
    }

    private UserAction queuedAction(Long accountId) {
        UserAction action = new UserAction();
        action.setAccountId(accountId);
        action.setActionType("ARCHIVE");
        action.setSenderGroupKey("sender@example.com");
        action.setMessageIds(List.of("message-1"));
        action.setAffectedCount(1);
        action.setStatus("QUEUED");
        return actionRepository.save(action);
    }

    private void progress(
            UserAccount account, String status, Instant lastSuccessfulAt, String error) {
        progress(account, status, lastSuccessfulAt, error, "FRESHNESS");
    }

    private void progress(
            UserAccount account,
            String status,
            Instant lastSuccessfulAt,
            String error,
            String lane) {
        SyncProgress progress = new SyncProgress();
        progress.setAccountId(account.getId());
        progress.setJobType("MAILBOX_SYNC");
        progress.setLane(lane);
        progress.setStatus(status);
        progress.setMessagesScanned(12);
        progress.setMessagesUpserted(8);
        progress.setPagesCompleted(3);
        progress.setLastSuccessfulAt(lastSuccessfulAt);
        progress.setErrorMessage(error);
        if ("COMPLETE".equals(status) || "FAILED".equals(status) || "PARTIAL".equals(status)) {
            progress.setFinishedAt(Instant.now());
        }
        progressRepository.save(progress);
    }

    private void metadata(UserAccount account, String providerId) {
        metadataRepository.save(metadataRow(account, providerId));
    }

    private MessageMetadata metadataRow(UserAccount account, String providerId) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId("thread-" + providerId);
        row.setFromAddress("sender@example.com");
        row.setFromDomain("example.com");
        row.setSubjectHeader("Indexed subject");
        row.setReceivedAt(Instant.now());
        row.setUnread(true);
        return row;
    }

    private static WorkspaceModelService.AccountSummary summary(
            Long id, long indexed, String status, Instant success, String detail) {
        return new WorkspaceModelService.AccountSummary(
            id, indexed, indexed, indexed == 0 ? 0 : 1, status, success, detail);
    }

    private static OAuth2AuthenticationToken auth(String email, String registrationId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Workspace Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, registrationId);
    }

    private static String navLabel(WorkspaceRoute route) {
        return switch (route) {
            case SENDERS -> "Bulk Archive/Delete";
            case UNREAD -> "Unread Only";
            case UNSUBSCRIBE -> "Bulk Unsubscribe";
            case REVIEW -> "Review";
            case ACTIVITY -> "Sync / Activity";
            case ANALYTICS -> "Analytics";
        };
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
