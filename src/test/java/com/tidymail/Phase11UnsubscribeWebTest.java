package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import com.tidymail.unsubscribe.DestinationResolver;
import com.tidymail.unsubscribe.ResolvedDestination;
import com.tidymail.unsubscribe.UnsubscribeIntentService;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
class Phase11UnsubscribeWebTest {
    private static final String EMAIL = "owner@example.com";

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired UnsubscribeCandidateService candidates;
    @Autowired UnsubscribeIntentService unsubscribeIntents;
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoUtil crypto;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @MockBean(name = "gmailMailService")
    MailProvider gmailProvider;

    @MockBean(name = "outlookMailService")
    MailProvider outlookProvider;

    @MockBean
    UnsubscribeHttpTransport unsubscribeTransport;

    @MockBean
    DestinationResolver destinationResolver;

    @SpyBean
    UnsubscribeCandidateRepository candidateRepository;

    @BeforeEach
    void clean() {
        reset(gmailProvider, outlookProvider, unsubscribeTransport, destinationResolver);
        clearInvocations(candidateRepository);
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void fullPageResultFragmentAndInspectionRenderSamePrivacySafeCandidate() throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "PRIVATE_PROVIDER_MESSAGE_ID", true);
        String key = candidates.query(
            List.of(account.getId()), UnsubscribeQuery.parse(Map.of()))
            .rows().getFirst().candidateKey();

        String full = mockMvc.perform(get("/unsubscribe")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(full)
            .contains("Bulk Unsubscribe", "Read %", "Individual sender results")
            .contains("Example News", "news@example.com", "View stats")
            .contains("Date range:", "Settings", "Apply controls")
            .doesNotContain(
                "URI_TOKEN_CANARY", "AUTH_HEADER_CANARY", "SIGNATURE_CANARY",
                "PRIVATE_PROVIDER_MESSAGE_ID", "https://unsubscribe.example",
                "Send to Review", "Save selection", "staged", "immediate",
                "/unsubscribe/execute", "href=\"#\"",
                ".csv", "download", "Export");

        String fragment = mockMvc.perform(get("/unsubscribe/results")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fragment.trim()).startsWith("<section id=\"unsubscribe-results\"")
            .endsWith("</section>");
        assertThat(fragment).doesNotContain("<html", "URI_TOKEN_CANARY");

        String inspection = mockMvc.perform(get("/unsubscribe/candidates/{key}", key)
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inspection)
            .contains(
                "Inspect unsubscribe method",
                "unsubscribe.example",
                "/... (2 segments)",
                "Query token hidden",
                "Trusted DKIM passed",
                "DKIM covers List-Unsubscribe",
                "Available after the explicit confirmation below",
                "Stage for Review",
                "Run after this confirmation",
                "Save exact candidate or confirm and run",
                "A 2xx response means accepted, not independently confirmed",
                "Unavailable: this candidate qualifies for RFC 8058 POST",
                "List-Unsubscribe=One-Click")
            .doesNotContain(
                "URI_TOKEN_CANARY", "AUTH_HEADER_CANARY", "SIGNATURE_CANARY",
                "PRIVATE_PROVIDER_MESSAGE_ID", "https://unsubscribe.example",
                "name=\"method\" value=\"LEGACY_HTTPS_GET\"");

        String inspectionFragment = mockMvc.perform(get("/unsubscribe/candidates/{key}", key)
                .param("account", String.valueOf(account.getId()))
                .header("HX-Request", "true")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inspectionFragment.trim())
            .startsWith("<dialog id=\"unsubscribe-inspection\"")
            .endsWith("</dialog>");
        assertThat(inspectionFragment).contains("unsubscribe.example")
            .doesNotContain("URI_TOKEN_CANARY", "<html");
        verifyNoInteractions(gmailProvider, outlookProvider);
        verifyNoInteractions(unsubscribeTransport);
    }

    @Test
    void legacyGetIsEnabledOnlyWhenOneClickPostIsUnavailable() throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "PRIVATE_PROVIDER_MESSAGE_ID", false);
        String key = candidates.query(
            List.of(account.getId()), UnsubscribeQuery.parse(Map.of()))
            .rows().getFirst().candidateKey();

        String inspection = mockMvc.perform(get("/unsubscribe/candidates/{key}", key)
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(inspection)
            .contains(
                "Legacy HTTPS GET",
                "Save exact candidate or confirm and run",
                "The page may require more steps",
                "name=\"unsubscribeMethod\" value=\"LEGACY_HTTPS_GET\"")
            .doesNotContain(
                "name=\"unsubscribeMethod\" value=\"RFC8058_POST\"");
        verifyNoInteractions(gmailProvider, outlookProvider, unsubscribeTransport);
    }

    @Test
    void protectedCandidatesStayInspectableWithoutActionAndForeignKeysDoNotEnumerate()
            throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "protected-provider-id", true);
        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source)
            VALUES (?, 'news@example.com', 'USER')
            """, account.getId());
        String key = candidates.query(
            List.of(account.getId()),
            UnsubscribeQuery.parse(Map.of("protected", List.of("include"))))
            .rows().getFirst().candidateKey();

        String page = mockMvc.perform(get("/unsubscribe/candidates/{key}", key)
                .param("account", String.valueOf(account.getId()))
                .param("protected", "include")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page)
            .contains(
                "This sender is protected",
                "Unavailable")
            .doesNotContain(
                "/unsubscribe/execute", "Send one-click POST", "override",
                "I completed this manually", "/mailto/", "/provider?account=");

        mockMvc.perform(get("/unsubscribe/candidates/{key}", key)
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth("foreign@example.com"))))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/unsubscribe/candidates/{key}", "c1.invalid")
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isNotFound());
        verifyNoInteractions(gmailProvider, outlookProvider);
        verifyNoInteractions(unsubscribeTransport);
    }

    @Test
    void explicitMailtoAndProviderNavigationRedirectOnlyAfterUserChoice() throws Exception {
        UserAccount account = account(EMAIL);
        MessageMetadata row = seed(account, "NAVIGATION_PROVIDER_ID", false);
        row.setListUnsubscribeValues(List.of(
            "<mailto:leave@example.com?subject=SUBJECT_CANARY&body=BODY_CANARY>"));
        row.setListUnsubscribePostValues(List.of());
        metadata.save(row);
        String key = candidates.query(
            List.of(account.getId()), UnsubscribeQuery.parse(Map.of()))
            .rows().getFirst().candidateKey();

        String inspection = mockMvc.perform(get("/unsubscribe/candidates/{key}", key)
                .param("account", String.valueOf(account.getId()))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inspection)
            .contains(
                "Mailto", "leave@example.com", "Open unsubscribe email",
                "Present; value hidden", "Opens your default mail application",
                "Open source message in Gmail", "target=\"_blank\"",
                "rel=\"noopener noreferrer\"")
            .doesNotContain(
                "SUBJECT_CANARY", "BODY_CANARY", "NAVIGATION_PROVIDER_ID",
                "mailto:leave@example.com", "mail.google.com/mail/u/");

        String mailtoLocation = mockMvc.perform(
                get("/unsubscribe/candidates/{key}/mailto/{index}", key, 0)
                    .param("account", String.valueOf(account.getId()))
                    .with(authentication(auth(EMAIL))))
            .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(mailtoLocation)
            .isEqualTo("mailto:leave@example.com?subject=SUBJECT_CANARY&body=BODY_CANARY");

        String providerLocation = mockMvc.perform(
                get("/unsubscribe/candidates/{key}/provider", key)
                    .param("account", String.valueOf(account.getId()))
                    .with(authentication(auth(EMAIL))))
            .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(providerLocation)
            .startsWith("https://mail.google.com/mail/u/")
            .contains("private-thread-id")
            .doesNotContain("NAVIGATION_PROVIDER_ID");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM unsubscribe_attempts", Long.class)).isZero();
        verifyNoInteractions(gmailProvider, outlookProvider, unsubscribeTransport);
    }

    @Test
    void directAutomatedExecutionIsRetiredWithoutConsumingIntentOrCallingTransport()
            throws Exception {
        UserAccount account = account(EMAIL);
        seed(account, "PRIVATE_PROVIDER_MESSAGE_ID", true);
        String key = candidates.query(
            List.of(account.getId()), UnsubscribeQuery.parse(Map.of()))
            .rows().getFirst().candidateKey();
        String intent = unsubscribeIntents.issue(
            List.of(account.getId()), key, UnsubscribeMethod.RFC8058_POST);

        mockMvc.perform(post("/unsubscribe/execute")
                .param("candidateKey", key)
                .param("method", "RFC8058_POST")
                .param("intent", intent)
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isForbidden());
        verifyNoInteractions(destinationResolver, unsubscribeTransport);

        mockMvc.perform(post("/unsubscribe/execute")
                .param("candidateKey", key)
                .param("method", "RFC8058_POST")
                .param("intent", intent)
                .with(csrf())
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isGone());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM unsubscribe_attempts", Long.class)).isZero();
        verifyNoInteractions(destinationResolver, unsubscribeTransport);
    }

    @Test
    void routesHaveOneOwnerAndInvalidCanonicalStateFailsBeforeQuery() throws Exception {
        for (String route : List.of(
                "/unsubscribe", "/unsubscribe/results",
                "/unsubscribe/candidates/{candidateKey}",
                "/unsubscribe/candidates/{candidateKey}/mailto/{methodIndex}",
                "/unsubscribe/candidates/{candidateKey}/provider",
                "/unsubscribe/execute", "/unsubscribe/manual-complete",
                "/unsubscribe/outcomes/{attemptId}")) {
            assertThat(handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().contains(route))
                .toList()).as(route).hasSize(1);
        }
        mockMvc.perform(get("/unsubscribe").param("page", "0", "1")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/unsubscribe").param("secret", "CANARY")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/unsubscribe").param("protected", "override")
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/unsubscribe").param("q", "x".repeat(201))
                .with(authentication(auth(EMAIL))))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(candidateRepository);
        verifyNoInteractions(gmailProvider, outlookProvider);
        verifyNoInteractions(unsubscribeTransport);
    }

    @Test
    void templatesLockSemanticAccessibilityPrivacyAndPhaseBoundary() throws Exception {
        String results = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/fragments/unsubscribe-results-frag.html"));
        String inspection = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/fragments/unsubscribe-inspection-frag.html"));
        String page = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/unsubscribe.html"));
        String rail = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/templates/fragments/workspace-rail.html"));
        String css = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/static/css/workspace.css"));
        assertThat(results)
            .contains(
                "aria-live=\"polite\"",
                "class=\"unsubscribe-sender-list\"",
                "class=\"unsubscribe-list-head\"",
                "class=\"action-button\"",
                "data-label=\"# emails\"",
                "data-label=\"Read %\"",
                "data-label=\"Recommended action\"",
                "data-label=\"More\"")
            .doesNotContain("href=\"#\"", "providerMessageId", "destinationUri");
        assertThat(inspection)
            .contains(
                "<dialog id=\"unsubscribe-inspection\"",
                "aria-labelledby=\"unsubscribe-inspection-heading\"",
                "tabindex=\"-1\"", "method=\"dialog\"",
                "aria-label=\"Close unsubscribe inspection\"")
            .contains(
                "Query token hidden", "th:target=", "noopener noreferrer",
                "type=\"button\" disabled", "th:action=\"@{/api/action-commands}\"",
                "th:action=\"@{/unsubscribe/manual-complete}\"")
            .contains("Stage for Review", "Run after this confirmation")
            .doesNotContain("providerMessageId", "destinationUri");
        assertThat(page)
            .contains(
                "Skip to workspace content", "<main id=\"workspace-main\">",
                "<h1>Bulk Unsubscribe</h1>",
                "class=\"unsubscribe-top-controls\"",
                "name=\"category\"", "name=\"from\"", "name=\"to\"",
                "name=\"q\"", "name=\"defaultAction\"")
            .doesNotContain(
                "unpkg.com", "cdn.jsdelivr", "Send to Review", "Save selection",
                "/unsubscribe/execute", ".csv", "download", "Export");
        assertThat(rail)
            .contains("aria-label=\"Primary\"", "class=\"rail-account-menu\"",
                ">Settings</summary>", "Delete my data and logout")
            .doesNotContain(
                "name=\"method\"", "name=\"volume\"", "name=\"confidence\"",
                "name=\"review\"", "name=\"age\"", "name=\"protected\"", "name=\"state\"");
        assertThat(css).contains(
            "@media (max-width: 980px)", "@media (max-width: 720px)",
            "@media (max-width: 375px)", "@media (prefers-reduced-motion: reduce)",
            "min-height: 44px", "overflow-x: auto", "overflow-wrap: anywhere",
            "overscroll-behavior-inline: contain")
            .doesNotContain("overflow-x: hidden");
    }

    private UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setProvider("google");
        account.setProviderAccountId("google-" + email);
        account.setDisplayName("Owner");
        account.setEncryptedAccessToken(crypto.encrypt("access".getBytes(StandardCharsets.UTF_8)));
        account.setEncryptedRefreshToken(
            crypto.encrypt("refresh".getBytes(StandardCharsets.UTF_8)));
        account.setScopes(List.of("email"));
        account.setAesKeyId("hkdf-sha256-v1");
        return accounts.save(account);
    }

    private MessageMetadata seed(UserAccount account, String providerId, boolean trusted) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId("private-thread-id");
        row.setFromAddress("news@example.com");
        row.setFromDisplayName("Example News");
        row.setFromDomain("example.com");
        row.setReceivedAt(Instant.now());
        row.setListIdValues(List.of("news.list.example"));
        row.setListUnsubscribeValues(List.of(
            "<https://unsubscribe.example/private/URI_TOKEN_CANARY?secret=URI_TOKEN_CANARY>"));
        row.setListUnsubscribePostValues(List.of("List-Unsubscribe=One-Click"));
        row.setUnsubscribeDkimPass(trusted);
        row.setUnsubscribeDkimCoversListUnsubscribe(trusted);
        row.setUnsubscribeDkimCoversListUnsubscribePost(trusted);
        row.setUnsubscribeAuthReason(trusted ? "TRUSTED_PASS" : "NO_TRUSTED_RESULT");
        return metadata.save(row);
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }

}
