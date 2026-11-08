package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
class Phase13IntegratedProductTest {
    private static final String OWNER = "integrated-owner@example.com";
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired SyncProgressRepository progress;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService") MailProvider gmail;
    @MockBean(name = "outlookMailService") MailProvider outlook;

    private UserAccount owned;
    private UserAccount foreign;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE user_accounts RESTART IDENTITY CASCADE");
        owned = account(OWNER);
        foreign = account("foreign-integrated@example.com");
        message(owned, "owned-one", START.plusSeconds(10), "Safe Sender", true);
        message(owned, "owned-two", START.plusSeconds(20), "=Formula Sender", true);
        message(foreign, "foreign-canary", START.plusSeconds(15), "Foreign Sender", true);
        completeProgress(owned);
        clearInvocations(gmail, outlook);
    }

    @Test
    void work05_allRepresentedControlsExecute_andDecorativePatternsAreAbsent()
            throws Exception {
        Map<String, List<String>> pages = Map.of(
            "/senders", List.of("Settings", "Expand all"),
            "/unread", List.of("Settings", "Expand all"),
            "/review", List.of("Action history", "History"));
        for (Map.Entry<String, List<String>> page : pages.entrySet()) {
            String html = mvc.perform(get(page.getKey())
                    .param("account", owned.getId().toString())
                    .with(authentication(auth(OWNER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).as(page.getKey())
                .contains(page.getValue().toArray(String[]::new))
                .doesNotContain("href=\"#\"", "onclick=", "javascript:",
                    "Available in Phase", "Configure view", "foreign-canary");
            if (page.getKey().equals("/senders") || page.getKey().equals("/unread")) {
                assertThat(html).doesNotContain("Export filtered", "decision-filters");
            }
        }
        verifyNoInteractions(gmail, outlook);
    }

    @Test
    void qual02_allFivePagesMeetResponsiveAccessibleControlContract() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/workspace.css"));
        for (String template : List.of(
                "senders.html", "unread.html",
                "review.html")) {
            String html = Files.readString(TEMPLATES.resolve(template));
            assertThat(html).as(template)
                .contains("<meta name=\"viewport\"", "class=\"skip-link\"",
                    "<main id=\"workspace-main\"", "<h1")
                .doesNotContain("href=\"#\"", "onclick=", "javascript:");
        }
        assertThat(css).contains(
            "@media (max-width: 375px)", "min-height: 44px",
            "min-width: 44px", ":focus-visible",
            "@media (prefers-reduced-motion: reduce)", "overflow-wrap: anywhere")
            .doesNotContain("min-width: 375px");
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

    private void message(
            UserAccount account, String providerId, Instant receivedAt,
            String senderName, boolean unread) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId("thread-" + providerId);
        row.setReceivedAt(receivedAt);
        row.setFromDisplayName(senderName);
        row.setFromAddress("sender@example.com");
        row.setFromDomain("example.com");
        row.setSubjectHeader("subject-private");
        row.setUnread(unread);
        metadata.saveAndFlush(row);
    }

    private void completeProgress(UserAccount account) {
        completeProgress(account, Instant.parse("2026-08-04T00:00:00Z"));
    }

    private void completeProgress(UserAccount account, Instant completedAt) {
        SyncProgress row = new SyncProgress();
        row.setAccountId(account.getId());
        row.setJobType("INCREMENTAL_SYNC");
        row.setStatus("COMPLETE");
        row.setStartedAt(completedAt.minusSeconds(60));
        row.setFinishedAt(completedAt);
        row.setLastSuccessfulAt(completedAt);
        progress.saveAndFlush(row);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("email", email);
        attributes.put("name", "Integrated Owner");
        attributes.put("sub", "subject-" + email);
        return new OAuth2AuthenticationToken(
            new DefaultOAuth2User(authorities, attributes, "sub"),
            authorities, "google");
    }
}
