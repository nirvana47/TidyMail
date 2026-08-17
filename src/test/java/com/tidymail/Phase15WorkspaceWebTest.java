package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.UnreadCategoryGroup;
import com.tidymail.dto.UnreadSenderRow;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SenderCategory;
import com.tidymail.entity.SenderTypeOverride.SenderType;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.AttachmentState;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SenderCategoryAssignmentRepository;
import com.tidymail.repository.SenderCategoryRepository;
import com.tidymail.repository.SenderTypeOverrideRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.SenderCategoryService;
import com.tidymail.service.DecisionRuleSet.UnreadCategory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

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
    "MICROSOFT_CLIENT_SECRET="
})
class Phase15WorkspaceWebTest {
    private static final String OWNER_EMAIL = "workspace-owner@example.com";
    private static final String OWNER_KEY = OWNER_EMAIL + ":google";
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository accounts;
    @Autowired SenderTypeOverrideRepository overrides;
    @Autowired SenderCategoryRepository categories;
    @Autowired SenderCategoryAssignmentRepository assignments;
    @Autowired SenderCategoryService categoryService;
    @Autowired MessageMetadataRepository metadata;

    @BeforeEach
    void clean() {
        metadata.deleteAll();
        overrides.deleteAll();
        assignments.deleteAll();
        categories.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void senderMarkupUsesDirectExactStagingQuietControlsAndResponsiveReconciliation()
            throws Exception {
        String page = Files.readString(TEMPLATES.resolve("senders.html"));
        String rows = Files.readString(
            TEMPLATES.resolve("fragments/senders-results-frag.html"));
        String script = Files.readString(
            Path.of("src/main/resources/static/js/cleanup-categories.js"));
        String css = Files.readString(
            Path.of("src/main/resources/static/css/workspace.css"));

        assertThat(page)
            .contains("Change type", "Archive", "Delete selected", "Mark as Read")
            .contains("data-category-toggle-all", "sender-bulk-stage")
            .contains("Gmail Trash can typically be restored for up to 30 days")
            .contains("action=\"/api/cleanup-actions\"", "data-cleanup-stage",
                "data-cleanup-feedback", "data-selection-bar hidden",
                "name=\"action\" value=\"ARCHIVE\"",
                "name=\"action\" value=\"MARK_READ\"")
            .doesNotContain("selection-preview", "workspace-download",
                "Export current senders", "filter-summary", "next-route",
                "cleanup-action-result-frag :: status", "cleanup-action-status",
                "Ready to stage for Review", "Choose the default action",
                "sender-settings-dialog");
        assertThat(rows)
            .contains("Sender", "Domain", "All Mail unread", "Size (Estimated)")
            .contains("form=\"sender-bulk-stage\"")
            .doesNotContain("/api/selection-preview", "name=\"mode\"",
                "Estimated size", "data-delete-source=\"closest\"",
                "<span>Action</span>", "class=\"cleanup-row-state\"");
        assertThat(script)
            .contains("htmx:afterRequest", "reconcileCleanupResult",
                "category.open", "content.replaceChildren()",
                "deleteAcknowledged", "openButton.dataset.accountId",
                "row.classList.add(\"is-staged\")",
                "Already staged", "Staged", "data-cleanup-feedback",
                "updateSelectionBars", "data-selection-bar",
                "button.dataset.deleteAction || \"TRASH\"")
            .doesNotContain("document.getElementById(\"cleanup-action-status\")",
                "Queued actions:");
        assertThat(css)
            .contains(".cleanup-row-head", ".cleanup-row.is-staged",
                ".cleanup-bulk-stage", ".cleanup-bulk-stage[hidden]",
                ".cleanup-stage-feedback", "min-height: 44px")
            .contains("@media (max-width: 720px)", "[data-label]::before")
            .doesNotContain("overflow-x: hidden");
    }

    @Test
    void strictCorrectionRoutingKeepsSemanticAndCustomFactsOwnedAndSeparate()
            throws Exception {
        UserAccount first = account("first@example.com", OWNER_KEY);
        UserAccount second = account("second@example.com", OWNER_KEY);
        SenderCategory travel = categoryService.create(
            OWNER_KEY, first.getId(), "Travel");

        mockMvc.perform(correction(first, "semantic:PERSONAL")
                .header("HX-Request", "true"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mockMvc.perform(correction(second, "semantic:AUTOMATED"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/senders?account=" + second.getId()));
        mockMvc.perform(correction(first, "custom:" + travel.getId()))
            .andExpect(status().is3xxRedirection());
        mockMvc.perform(correction(first, "semantic:LIST"))
            .andExpect(status().is3xxRedirection());

        assertThat(overrides.findByAccountIdAndSenderAddress(
            first.getId(), "same@example.com"))
            .get().extracting(value -> value.getSenderType()).isEqualTo(SenderType.LIST);
        assertThat(overrides.findByAccountIdAndSenderAddress(
            second.getId(), "same@example.com"))
            .get().extracting(value -> value.getSenderType()).isEqualTo(SenderType.AUTOMATED);
        assertThat(assignments.findByAccountIdAndSenderAddress(
            first.getId(), "same@example.com"))
            .get().extracting(value -> value.getCategoryId()).isEqualTo(travel.getId());
        assertThat(assignments.findByAccountIdAndSenderAddress(
            second.getId(), "same@example.com")).isEmpty();
    }

    @Test
    void categoryOrganizeMovesSelectedSendersToSemanticOrNewCustomCategory()
            throws Exception {
        UserAccount account = account("organize@example.com", OWNER_KEY);

        mockMvc.perform(post("/api/sender-categories/organize")
                .with(csrf())
                .with(authentication(auth()))
                .header("HX-Request", "true")
                .param("accountId", account.getId().toString())
                .param("senderAddress", "Costco <costco@digital.costco.com>")
                .param("categoryTarget", "semantic:AUTOMATED")
                .param("returnTo", "senders")
                .param("returnAccount", "current"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        assertThat(overrides.findByAccountIdAndSenderAddress(
            account.getId(), "costco@digital.costco.com"))
            .get().extracting(value -> value.getSenderType()).isEqualTo(SenderType.AUTOMATED);

        mockMvc.perform(post("/api/sender-categories/organize")
                .with(csrf())
                .with(authentication(auth()))
                .header("HX-Request", "true")
                .param("accountId", account.getId().toString())
                .param("senderAddress", "noreply@redditmail.com")
                .param("categoryName", "Shopping")
                .param("returnTo", "senders")
                .param("returnAccount", "current"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        SenderCategory created = categories.findAll().stream()
            .filter(category -> category.getName().equals("Shopping"))
            .findFirst()
            .orElseThrow();
        assertThat(assignments.findByAccountIdAndSenderAddress(
            account.getId(), "noreply@redditmail.com"))
            .get().extracting(value -> value.getCategoryId()).isEqualTo(created.getId());
    }

    @Test
    void senderPageUsesManualOverridesAfterCategoryChangeWithoutParityCrash()
            throws Exception {
        UserAccount account = account(OWNER_EMAIL, OWNER_KEY);
        metadata.save(message(
            account,
            "costco@digital.costco.com",
            "costco-1",
            Instant.now().minus(4, ChronoUnit.DAYS)));

        mockMvc.perform(post("/api/sender-categories/organize")
                .with(csrf())
                .with(authentication(auth()))
                .header("HX-Request", "true")
                .param("accountId", account.getId().toString())
                .param("senderAddress", "costco@digital.costco.com")
                .param("categoryTarget", "semantic:AUTOMATED")
                .param("returnTo", "senders")
                .param("returnAccount", "current"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/senders")
                .with(authentication(auth()))
                .param("account", "current"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString("costco@digital.costco.com")));
    }

    @Test
    void correctionRejectsForeignOwnershipSmugglingAndDisplayLabels() throws Exception {
        UserAccount owned = account("owned@example.com", OWNER_KEY);
        UserAccount foreign = account("foreign@example.com", "other-owner:google");

        mockMvc.perform(correction(foreign, "semantic:PERSONAL"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(correction(owned, "Personal"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(correction(owned, "semantic:PERSONAL")
                .param("count", "999"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/sender-categories/correct?accountId=" + owned.getId())
                .with(csrf())
                .with(authentication(auth()))
                .param("senderAddress", "same@example.com")
                .param("correctionTarget", "semantic:PERSONAL")
                .param("returnTo", "senders"))
            .andExpect(status().isBadRequest());

        assertThat(overrides.findAll()).isEmpty();
        assertThat(assignments.findAll()).isEmpty();
    }

    @Test
    void unreadShellIsFreshnessFirstEmptyAndLoadsStableFiveRowOverlappingChunks()
            throws Exception {
        UserAccount account = account(OWNER_EMAIL, OWNER_KEY);
        for (int index = 0; index < 12; index++) {
            metadata.save(message(
                account,
                "direct-%02d@example.com".formatted(index),
                "direct-" + index,
                Instant.now().minus(2, ChronoUnit.DAYS)));
        }
        MessageMetadata overlap = message(
            account, "overlap@example.com", "overlap",
            Instant.now().minus(40, ChronoUnit.DAYS));
        overlap.setListIdValues(List.of("list.example.com"));
        overlap.setHasAttachments(true);
        overlap.setAttachmentState(AttachmentState.PRESENT);
        overlap.setAttachmentObservedAt(Instant.now());
        overlap.setAttachmentSource("GMAIL_SEARCH");
        metadata.saveAndFlush(overlap);

        String page = mockMvc.perform(get("/unread")
                .param("account", account.getId().toString())
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page)
            .contains("How Unread Only staging works",
                "12 senders", "12 Inbox unread", "1 sender", "1 Inbox unread",
                  "data-category-key=\"unread-direct-mail\"",
                  "id=\"unread-bulk-stage\"", "name=\"surface\" value=\"UNREAD\"",
                  "action=\"/api/cleanup-actions\"", "data-cleanup-feedback",
                  "Gmail Trash can typically be restored for up to 30 days")
            .doesNotContain("direct-00@example.com", "overlap@example.com",
                "Tidy up your unread emails",
                "Counts and rows reflect the last completed metadata sync",
                "Unread groups are not mutually exclusive",
                "workspace-download", "/api/selection-preview",
                "cleanup-action-status", "Ready to stage for Review");

        String first = unreadChunk(account, "direct-mail", 0, false);
        assertThat(first)
            .contains("direct-00@example.com", "direct-04@example.com",
                  "Load more", "form=\"unread-bulk-stage\"", "value=\"us1.",
                  "cleanup-row-head", "<span>Sender</span>",
                  "<span>Inbox unread</span>")
            .doesNotContain("direct-05@example.com", "Load all", "<span>Action</span>");
        String second = unreadChunk(account, "direct-mail", 5, false);
        assertThat(second)
            .contains("direct-05@example.com", "direct-09@example.com",
                "Load more", "Load all")
            .doesNotContain("direct-00@example.com", "direct-10@example.com",
                "cleanup-row-head", "<span>Sender</span>",
                "<span>Inbox unread</span>",
                "<span>Action</span>");
        for (String category : List.of(
                "lists-automated", "older-than-30-days", "attachments")) {
            assertThat(unreadChunk(account, category, 0, false))
                .contains("overlap@example.com", "form=\"unread-bulk-stage\"");
        }

        mockMvc.perform(post("/api/sender-categories/correct")
                .with(csrf())
                .with(authentication(auth()))
                .param("accountId", account.getId().toString())
                .param("senderAddress", "direct-00@example.com")
                .param("correctionTarget", "semantic:AUTOMATED")
                .param("returnTo", "unread"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/unread?account=" + account.getId()));
        assertThat(unreadChunk(account, "lists-automated", 0, false))
            .contains("direct-00@example.com");
        assertThat(unreadChunk(account, "direct-mail", 0, false))
            .doesNotContain("direct-00@example.com");
    }

    @Test
    void userGeneratedSenderCategoryRendersAsOwnedFirstClassCategory()
            throws Exception {
        UserAccount owned = account(OWNER_EMAIL, OWNER_KEY);
        UserAccount foreign = account("foreign-category@example.com", "other-owner:google");
        metadata.save(message(
            owned,
            "owned-special@example.com",
            "owned-special",
            Instant.now().minus(3, ChronoUnit.DAYS)));
        metadata.save(message(
            foreign,
            "foreign-special@example.com",
            "foreign-special",
            Instant.now().minus(3, ChronoUnit.DAYS)));
        SenderCategory ownedCategory = categoryService.create(
            OWNER_KEY, owned.getId(), "Trips \"Receipts\"");
        SenderCategory foreignCategory = categoryService.create(
            "other-owner:google", foreign.getId(), "Foreign Category");
        categoryService.assign(
            OWNER_KEY, owned.getId(), ownedCategory.getId(),
            List.of("owned-special@example.com"));
        categoryService.assign(
            "other-owner:google", foreign.getId(), foreignCategory.getId(),
            List.of("foreign-special@example.com"));

        String page = mockMvc.perform(get("/senders")
                .param("account", owned.getId().toString())
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page)
            .contains("Trips &quot;Receipts&quot;", "User generated",
                "data-category-key=\"custom-" + ownedCategory.getId() + "\"")
            .doesNotContain("Foreign", "foreign-special@example.com", "<script>");
        String chunk = mockMvc.perform(get("/senders/categories/custom-" + ownedCategory.getId())
                .param("account", owned.getId().toString())
                .param("offset", "0")
                .param("loadAll", "false")
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(chunk)
            .contains("owned-special@example.com", "form=\"sender-bulk-stage\"")
            .doesNotContain("foreign-special@example.com");
    }

    @Test
    void unreadPagingEnforcesTheSharedOneThousandLoadAllBoundary() {
        List<UnreadSenderRow> acceptedRows = unreadRows(1_000);
        UnreadCategoryGroup accepted = new UnreadCategoryGroup(
            "direct-mail", "Direct mail", "Direct", acceptedRows,
            true, false, true);
        assertThat(categoryService.unreadCategoryPage(
            List.of(accepted), "direct-mail", 10, true).rows()).hasSize(990);

        List<UnreadSenderRow> rejectedRows = unreadRows(1_001);
        UnreadCategoryGroup rejected = new UnreadCategoryGroup(
            "direct-mail", "Direct mail", "Direct", rejectedRows,
            true, false, true);
        assertThatThrownBy(() -> categoryService.unreadCategoryPage(
            List.of(rejected), "direct-mail", 10, true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(422))
            .hasMessageContaining("more than 1,000 senders");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder correction(
            UserAccount account, String target) {
        return post("/api/sender-categories/correct")
            .with(csrf())
            .with(authentication(auth()))
            .param("accountId", account.getId().toString())
            .param("senderAddress", "Same <SAME@example.com>")
            .param("correctionTarget", target)
            .param("returnTo", "senders");
    }

    private String unreadChunk(
            UserAccount account, String category, long offset, boolean loadAll)
            throws Exception {
        return mockMvc.perform(get("/senders/categories/unread-" + category)
                .param("account", account.getId().toString())
                .param("offset", Long.toString(offset))
                .param("loadAll", Boolean.toString(loadAll))
                .with(authentication(auth())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<UnreadSenderRow> unreadRows(int count) {
        ArrayList<UnreadSenderRow> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(new UnreadSenderRow(
                1,
                "sender-%04d@example.com".formatted(index),
                "Sender " + index,
                "example.com",
                1,
                null,
                UnreadCategory.ONE_TO_ONE,
                false,
                List.of(),
                Map.of()));
        }
        return rows;
    }

    private static MessageMetadata message(
            UserAccount account, String sender, String messageId, Instant receivedAt) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(messageId);
        row.setProviderThreadId("thread-" + messageId);
        row.setFromAddress(sender);
        row.setFromDisplayName(sender);
        row.setFromDomain("example.com");
        row.setSubjectHeader(messageId);
        row.setReceivedAt(receivedAt);
        row.setUnread(true);
        row.setLabels(List.of("INBOX"));
        return row;
    }

    private UserAccount account(String email, String ownerKey) {
        UserAccount account = new UserAccount();
        account.setOwnerKey(ownerKey);
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("provider-" + email);
        account.setEncryptedAccessToken(new byte[] {1});
        account.setEncryptedRefreshToken(new byte[] {2});
        account.setScopes(List.of("gmail.modify"));
        return accounts.save(account);
    }

    private static OAuth2AuthenticationToken auth() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", OWNER_EMAIL);
        attributes.put("name", "Workspace Owner");
        attributes.put("sub", "workspace-owner");
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
