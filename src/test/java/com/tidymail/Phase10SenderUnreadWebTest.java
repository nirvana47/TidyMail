package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.controller.WorkspaceController;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SenderCategoryRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.ProviderDeepLinkService;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.UnreadDecisionQueryService;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
class Phase10SenderUnreadWebTest {
    private static final String EMAIL = "owner+tag@example.com";

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired SenderCategoryRepository senderCategories;
    @Autowired CryptoUtil crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderDeepLinkService deepLinks;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @SpyBean SenderDecisionQueryService senderQueryService;
    @SpyBean UnreadDecisionQueryService unreadQueryService;

    @MockBean(name = "gmailMailService")
    MailProvider gmailProvider;

    @MockBean(name = "outlookMailService")
    MailProvider outlookProvider;

    @BeforeEach
    void clean() {
        reset(gmailProvider, outlookProvider);
        clearInvocations(senderQueryService, unreadQueryService);
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void unrd06ProviderLinksPreferThreadEncodeExactSegmentsAndFailClosed() {
        String link = deepLinks.gmail(
            "google", "owner+tag@example.com", "thread ?#%", "message-fallback").orElseThrow();
        URI uri = URI.create(link);

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("mail.google.com");
        assertThat(uri.getRawUserInfo()).isNull();
        assertThat(uri.getPort()).isEqualTo(-1);
        assertThat(uri.getRawQuery()).isNull();
        assertThat(URLDecoder.decode(uri.getRawPath().split("/")[3], StandardCharsets.UTF_8))
            .isEqualTo("owner+tag@example.com");
        assertThat(URLDecoder.decode(uri.getRawFragment().substring(4), StandardCharsets.UTF_8))
            .isEqualTo("thread ?#%");
        assertThat(deepLinks.gmail("google", EMAIL, null, "message-id").orElseThrow())
            .endsWith("#all/message-id");
        assertThat(deepLinks.gmail("google", EMAIL, "   ", "message-id").orElseThrow())
            .endsWith("#all/message-id");
        assertThat(deepLinks.gmail("microsoft", EMAIL, "thread", "message")).isEmpty();
        assertThat(deepLinks.gmail(" google ", EMAIL, "thread", "message")).isEmpty();
        assertThat(deepLinks.gmail("google", "not-an-email", "thread", "message")).isEmpty();
        assertThat(deepLinks.gmail("google", "owner\uFFFD@example.com", "thread", "message"))
            .isEmpty();
        assertThat(deepLinks.gmail("google", EMAIL, "bad/id", "message")).isEmpty();
        assertThat(deepLinks.gmail("google", EMAIL, "bad\\id", "message")).isEmpty();
        assertThat(deepLinks.gmail("google", EMAIL, "\n", "message")).isEmpty();
        assertThat(deepLinks.gmail("google", EMAIL, "\ud800", "message")).isEmpty();

        for (Constructor<?> constructor : ProviderDeepLinkService.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).doesNotContain(MailProvider.class);
        }
        assertThat(SenderDecisionQueryService.class.getDeclaredFields())
            .noneMatch(field -> MailProvider.class.isAssignableFrom(field.getType()));
        assertThat(UnreadDecisionQueryService.class.getDeclaredFields())
            .noneMatch(field -> MailProvider.class.isAssignableFrom(field.getType()));
    }

    @Test
    void work02EachDecisionGetRouteHasExactlyOneOwner() {
        for (String route : List.of(
                "/senders", "/senders/results", "/unread", "/unread/results")) {
            var owners = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().contains(route))
                .map(entry -> entry.getValue().getBeanType())
                .toList();
            assertThat(owners).as(route).hasSize(1);
            assertThat(owners.getFirst()).as(route).isEqualTo(WorkspaceController.class);
        }
    }

    @Test
    void work02CanonicalFullAndFragmentGetsPreserveRecognizedValuesWithoutPageFilters()
            throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "sender-message", "sender@example.com", "Sender Name", "Sender subject",
            Instant.now().minus(40, ChronoUnit.DAYS), "sender-thread");
        OAuth2AuthenticationToken auth = auth(EMAIL);

        String sender = mockMvc.perform(get("/senders")
                .params(new org.springframework.util.LinkedMultiValueMap<>(Map.ofEntries(
                    Map.entry("account", List.of(String.valueOf(account.getId()))),
                    Map.entry("search", List.of("Sender")),
                    Map.entry("window", List.of("all")),
                    Map.entry("type", List.of("all")),
                    Map.entry("risk", List.of("all")),
                    Map.entry("unread", List.of("all")),
                    Map.entry("queue", List.of("all")),
                    Map.entry("protected", List.of("include")),
                    Map.entry("sort", List.of("recent_desc")),
                    Map.entry("page", List.of("0")))))
                .with(authentication(auth)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sender)
            .contains("<h1>Bulk Archive/Delete</h1>")
            .contains("sender@example.com");
        assertThat(sender)
            .doesNotContain("id=\"sender-search\"", "id=\"sender-sort\"",
                "class=\"metric-strip\"", "class=\"pagination\"", "class=\"next-route\"");

        String fragment = mockMvc.perform(get("/unread/results")
                .param("account", String.valueOf(account.getId()))
                .param("sender", "sender@example.com")
                .param("provider", "google")
                .param("sort", "newest_first")
                .with(authentication(auth)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fragment.trim()).startsWith("<section id=\"unread-results\"")
            .endsWith("</section>");
        assertThat(fragment)
            .doesNotContain("<html", ">sender@example.com<")
            .contains("1 sender", "1 Inbox unread",
                "data-category-key=\"unread-direct-mail\"");
        String unreadRows = mockMvc.perform(get("/senders/categories/unread-direct-mail")
                .param("account", String.valueOf(account.getId()))
                .param("sender", "sender@example.com")
                .param("provider", "google")
                .param("sort", "newest_first")
                .param("offset", "0")
                .param("loadAll", "false")
                .with(authentication(auth)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unreadRows)
            .contains("sender@example.com", "form=\"unread-bulk-stage\"")
            .doesNotContain("Select row", "<span>Action</span>");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void work02InvalidDuplicateUnknownAndForeignValuesFailClosed() throws Exception {
        UserAccount owned = account(EMAIL);
        UserAccount foreign = account("foreign@example.com");
        OAuth2AuthenticationToken auth = auth(EMAIL);

        mockMvc.perform(get("/senders").param("page", "0", "1")
                .with(authentication(auth))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/senders").param("rawSecret", "do-not-echo")
                .with(authentication(auth))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/unread").param("provider", "microsoft")
                .with(authentication(auth))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/unread").param("cleanupAction", "execute-now")
                .with(authentication(auth))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/unread").param("sender", "bad\nsender")
                .with(authentication(auth))).andExpect(status().isBadRequest());
        verifyNoInteractions(senderQueryService, unreadQueryService);
        mockMvc.perform(get("/senders").param("page", "1")
                .with(authentication(auth))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/senders").param("account", String.valueOf(foreign.getId()))
                .with(authentication(auth))).andExpect(status().isNotFound());
        assertThat(owned.getId()).isPositive();
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void send01ThroughSend04RenderQuietClosedCategoriesAndExactActions() throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "sender-1", "news@example.com", "Daily News", "Newsletter",
            Instant.now().minus(10, ChronoUnit.DAYS), "thread-news");
        MessageMetadata second = seed(account, "sender-2", "news@example.com", "Daily News",
            "Another header", Instant.now().minus(20, ChronoUnit.DAYS), "thread-news-2");
        second.setUnread(false);
        metadata.save(second);

        String body = page("/senders", account);
        assertThat(body)
            .contains("Bulk Archive/Delete")
            .contains("Change type", "Expand all")
            .contains("Lists and newsletters", "Automated notifications",
                "Receipts and transactions", "Personal senders", "Other")
            .contains("Daily News", "news@example.com", "example.com")
            .contains("data-label=\"All Mail unread\">1</span>")
            .contains("name=\"surface\" value=\"SENDERS\"")
            .contains("name=\"action\" value=\"ARCHIVE\"")
            .contains("class=\"cleanup-action\"")
            .contains("Change type", "sender-category-dialog", "Create a new category")
            .contains("data-selection-bar hidden")
            .doesNotContain("id=\"sender-search\"", "id=\"sender-sort\"",
                "decision-filters", "metric-strip", "pagination", "next-route",
                "Current filters:", "/api/selection-preview", "Choose the default action",
                "Move selected", "Create category",
                "Select senders first, then choose where they belong.");

        String delete = mockMvc.perform(get("/senders")
                .param("account", String.valueOf(account.getId()))
                .param("cleanupAction", "delete")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(delete)
            .contains("data-delete-action=\"TRASH\"")
            .contains("data-delete-source=\"sender-bulk-stage\"", "Delete selected")
            .contains("id=\"sender-delete-dialog\"", "Stage Delete for Review?")
            .contains("Gmail Trash can typically be restored for up to 30 days");
    }

    @Test
    void send05AndSend07RenderProtectedRowsWithoutExecutableAction() throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "protected-message", "protected@example.com", "Protected",
            "Protected subject", Instant.now(), "protected-thread");
        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source, created_at)
            VALUES (?, ?, 'USER', CURRENT_TIMESTAMP)
            """, account.getId(), "protected@example.com");

        String body = mockMvc.perform(get("/senders")
                .param("account", String.valueOf(account.getId()))
                .param("protected", "include")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body)
            .contains("Protected sender")
            .contains("disabled=\"disabled\"")
            .doesNotContain("/queue", "immediate", "Run now", "Undo",
                "class=\"cleanup-row-state\"", "<span>Action</span>");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void unrd01ThroughUnrd04RenderNonExclusiveGroupsAndExactSenderActions() throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "private-provider-message", "person@example.com", "Person",
            "Visible subject header", Instant.now().minus(2, ChronoUnit.DAYS), "thread ?#%");

        String body = page("/unread", account);
        assertThat(body)
            .contains("<h1>Unread Only</h1>")
            .contains("How Unread Only staging works")
            .contains("Lists and automated mail", "Older than 30 days",
                "Direct mail", "Has attachments", "Other")
            .contains("1 sender", "1 Inbox unread")
            .contains("unread-correction-dialog")
            .doesNotContain(">Person<", ">person@example.com<", "/api/selection-preview",
                "workspace-download", "Export current unread")
            .doesNotContain("id=\"unread-search\"", "id=\"unread-sort\"",
                "decision-filters", "metric-strip", "pagination", "next-route",
                "Counts and rows reflect the last completed metadata sync",
                "Unread groups are not mutually exclusive",
                "cleanup-action-status", "Ready to stage for Review",
                "Visible subject header", "private-provider-message", "message body",
                "snippet", "attachment content", "Open in Gmail");

        String rows = mockMvc.perform(get("/senders/categories/unread-direct-mail")
                .param("account", String.valueOf(account.getId()))
                .param("offset", "0")
                .param("loadAll", "false")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rows)
            .contains("Person", "person@example.com")
            .contains("form=\"unread-bulk-stage\"")
            .doesNotContain("Visible subject header", "private-provider-message",
                "Select row", "<span>Action</span>");
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void qual03TemplatesAndCssLockQuietCategoryAndResponsiveContracts() throws Exception {
        String sender = Files.readString(Path.of("src/main/resources/templates/fragments/senders-results-frag.html"));
        String unread = Files.readString(Path.of("src/main/resources/templates/fragments/unread-results-frag.html"));
        String css = Files.readString(Path.of("src/main/resources/static/css/workspace.css"));

        assertThat(sender).contains("<details class=\"cleanup-category\"",
            "data-category-key", "form=\"sender-bulk-stage\"")
            .doesNotContain("<details class=\"cleanup-category\" open", "metric-strip",
                "pagination", "cleanup-row-state", "<span>Action</span>");
        assertThat(unread).contains("<details class=\"cleanup-category\"",
            "Load more", "Load all",
              "form=\"unread-bulk-stage\"", "DecisionRowKey).unreadSender")
            .doesNotContain("<details class=\"cleanup-category\" open", "metric-strip",
                "pagination", "/api/selection-preview", "unread-category-dialog",
                "cleanup-row-state", "<span>Action</span>",
                "Unread groups are not mutually exclusive",
                "Counts and rows reflect the last completed metadata sync");
        assertThat(Files.readString(Path.of("src/main/resources/templates/senders.html")))
            .contains("Expand all", "cleanup-categories.js", "<dialog", "data-selection-bar hidden")
            .doesNotContain("sender-search", "filter-summary", "next-route");
        assertThat(Files.readString(Path.of("src/main/resources/templates/unread.html")))
            .contains("Expand all", "cleanup-categories.js",
                  "action=\"/api/sender-categories/correct\"",
                  "id=\"unread-category-dialog\"",
                  "id=\"unread-bulk-stage\"", "action=\"/api/cleanup-actions\"",
                  "page-header",
                  "How Unread Only staging works",
                  "data-cleanup-feedback",
                  "Gmail Trash can typically be restored for up to 30 days")
            .doesNotContain("unread-search", "filter-summary", "next-route",
                "Tidy up your unread emails",
                "workspace-download", "selection-preview",
                "cleanup-action-result-frag :: status", "cleanup-action-status",
                "Ready to stage for Review");
        assertThat(Files.readString(Path.of(
            "src/main/resources/templates/fragments/selection-preview-frag.html")))
            .contains("aria-labelledby=\"selection-preview-heading\"")
            .contains(
                "id=\"selection-preview-heading\" tabindex=\"-1\"",
                "When should TidyMail run this action?",
                "Stage for Review",
                "Run after this confirmation",
                "Send exact scope to Review",
                "Confirm and run exact scope",
                "#strings.substring(scope.scopeHash, 0, 12)");
        assertThat(css)
            .contains("@media (max-width: 980px)")
            .contains("@media (max-width: 720px)")
            .contains("@media (max-width: 375px)")
            .contains("min-height: 44px")
            .contains(".cleanup-action")
            .contains(".cleanup-dialog::backdrop")
            .contains("overflow-x: auto")
            .contains(":focus-visible")
            .contains("@media (prefers-reduced-motion: reduce)")
            .doesNotContain("@import url", "cdn.jsdelivr", "unpkg.com");
    }

    @Test
    void previewPostRejectsUnknownValuesAndNeverCallsProviders() throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "preview-message", "preview@example.com", "Preview",
            "Preview subject", Instant.now(), "preview-thread");

        mockMvc.perform(post("/api/selection-preview")
                .with(authentication(auth(EMAIL))).with(csrf())
                .param("surface", "UNREAD")
                .param("mode", "ALL_MATCHING")
                .param("action", "REVIEW")
                .param("account", String.valueOf(account.getId()))
                .param("unknown", "private"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void categoryEndpointRejectsMaliciousNamesWithoutPersistence() throws Exception {
        UserAccount account = account(EMAIL);

        mockMvc.perform(post("/api/sender-categories/organize")
                .with(authentication(auth(EMAIL))).with(csrf())
                .param("accountId", String.valueOf(account.getId()))
                .param("categoryName", "<script>alert(1)</script>")
                .param("returnTo", "senders"))
            .andExpect(status().isBadRequest());

        assertThat(senderCategories.count()).isZero();
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    @Test
    void d14WarmTenThousandRowFullAndFragmentRequestsStayUnderTwoSeconds() throws Exception {
        UserAccount account = account(EMAIL);
        Instant now = Instant.now();
        ArrayList<MessageMetadata> rows = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            MessageMetadata row = new MessageMetadata();
            row.setAccountId(account.getId());
            row.setProvider("google");
            row.setProviderMessageId("budget-message-" + index);
            row.setProviderThreadId("budget-thread-" + index);
            row.setFromAddress("budget-" + index + "@example.com");
            row.setFromDisplayName("Budget Sender " + index);
            row.setFromDomain("example.com");
            row.setSubjectHeader("Budget subject " + index);
            row.setReceivedAt(now.minusSeconds(index));
            row.setUnread(true);
            row.setLabels(List.of("INBOX"));
            rows.add(row);
        }
        metadata.saveAll(rows);
        OAuth2AuthenticationToken authentication = auth(EMAIL);

        for (String route : List.of("/senders", "/senders/results", "/unread", "/unread/results")) {
            mockMvc.perform(get(route).param("account", String.valueOf(account.getId()))
                    .with(authentication(authentication)))
                .andExpect(status().isOk());
            long started = System.nanoTime();
            String body = mockMvc.perform(get(route)
                    .param("account", String.valueOf(account.getId()))
                    .with(authentication(authentication)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as(route)
                .isLessThan(Duration.ofSeconds(2));
            assertThat(body).contains("10,000".replace(",", ""));
        }
        verifyNoInteractions(gmailProvider, outlookProvider);
    }

    private String page(String path, UserAccount account) throws Exception {
        reset(gmailProvider, outlookProvider);
        String body = mockMvc.perform(get(path)
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        verifyNoInteractions(gmailProvider, outlookProvider);
        return body;
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("google-" + email);
        account.setDisplayName("Workspace Owner");
        account.setEncryptedAccessToken(crypto.encrypt("access".getBytes(StandardCharsets.UTF_8)));
        account.setEncryptedRefreshToken(crypto.encrypt("refresh".getBytes(StandardCharsets.UTF_8)));
        account.setScopes(List.of("openid", "email", "profile"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.save(account);
    }

    private MessageMetadata seed(
            UserAccount account,
            String providerId,
            String sender,
            String displayName,
            String subject,
            Instant receivedAt,
            String threadId) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId(threadId);
        row.setFromAddress(sender);
        row.setFromDisplayName(displayName);
        row.setFromDomain(sender.substring(sender.indexOf('@') + 1));
        row.setSubjectHeader(subject);
        row.setReceivedAt(receivedAt);
        row.setUnread(true);
        row.setLabels(List.of("INBOX"));
        return metadata.save(row);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Workspace Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
