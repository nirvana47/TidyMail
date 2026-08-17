package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.AnalyticsReceivedRepository;
import com.tidymail.repository.AnalyticsLedgerRepository;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
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
    "MICROSOFT_CLIENT_SECRET="
})
class Phase13AnalyticsWebTest {
    private static final String OWNER = "analytics-owner@example.com";
    private static final Instant BUCKET_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant BUCKET_END = Instant.parse("2026-08-02T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired SyncProgressRepository progress;

    @SpyBean AnalyticsReceivedRepository analyticsRepository;
    @SpyBean AnalyticsLedgerRepository ledgerRepository;
    @MockBean(name = "gmailMailService") MailProvider gmail;
    @MockBean(name = "outlookMailService") MailProvider outlook;
    @MockBean UnsubscribeHttpTransport unsubscribeTransport;

    private UserAccount owned;
    private UserAccount foreign;

    @BeforeEach
    void setUp() {
        progress.deleteAll();
        metadata.deleteAll();
        accounts.deleteAll();
        owned = account(OWNER);
        foreign = account("foreign@example.com");
        message(owned, "PROVIDER-ID-CANARY", BUCKET_START, " \t=Visible sender");
        message(owned, "owned-second", BUCKET_START.plusSeconds(10), "Safe sender");
        message(foreign, "FOREIGN-PROVIDER-CANARY", BUCKET_START.plusSeconds(5), "Foreign");
        completeProgress(owned);
        clearInvocations(
            analyticsRepository, ledgerRepository, gmail, outlook, unsubscribeTransport);
    }

    @Test
    void authenticationIsRequiredAndForeignAccountFailsBeforeAnalyticsRepository() throws Exception {
        mvc.perform(get("/analytics")).andExpect(status().is3xxRedirection());

        mvc.perform(get("/analytics")
                .param("account", foreign.getId().toString())
                .with(authentication(auth(OWNER))))
            .andExpect(status().isNotFound());
        verifyNoInteractions(analyticsRepository, gmail, outlook, unsubscribeTransport);
    }

    @Test
    void realAnalyticsPageHasCanonicalControlsUtcCoverageAndExactSourceLinks() throws Exception {
        String html = mvc.perform(get("/analytics")
                .param("from", "2026-08-01")
                .param("to", "2026-08-03")
                .param("account", owned.getId().toString())
                .param("action", "archive")
                .param("group", "day")
                .param("compare", "prior")
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).containsOnlyOnce("<main");
        assertThat(html).containsOnlyOnce("<h1");
        assertThat(html).contains(
            "<h1>Analytics</h1>", "Time period", "Group by",
            "Received", "Sent", "Read", "Archived/Deleted",
            "Top Received", "Top Sent", "Inbox unread size",
            "Inbox &gt; 10MB", "Sent &gt; 10MB", "TidyMail actions",
            "analytics-chart-series", "analytics-chart-bar",
            "analytics-chart-y-axis", "data-chart-y-tick=\"1\"",
            "analytics-chart-date", "analytics-chart-tooltip",
            "aria-pressed=\"false\"", "2026-",
            "analytics-action-bars", "analytics-action-bar",
            "type=\"button\"", "aria-label=\"Received:");
        assertThat(html).doesNotContain(
            "Available in Phase 13", "href=\"#\"", "PROVIDER-ID-CANARY",
            "PRIVATE-SUBJECT-CANARY", "FOREIGN-PROVIDER-CANARY",
              "Open filtered History", "Export current analytics",
              "Download sanitized snapshot", "workspace-download", "download=");
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void receivedAndCleanedTrendAvailabilityRemainIndependent() throws Exception {
        MvcResult result = mvc.perform(get("/analytics")
                .param("from", "2026-08-01")
                .param("to", "2026-08-01")
                .param("account", owned.getId().toString())
                .param("action", "all")
                .param("group", "day")
                .param("compare", "none")
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk())
            .andReturn();

        AnalyticsPageModel page =
            (AnalyticsPageModel) result.getModelAndView().getModel().get("page");
        assertThat(page.trend()).singleElement().satisfies(bucket -> {
            assertThat(bucket.receivedAvailability().state())
                .isEqualTo(AnalyticsAvailability.State.AVAILABLE);
            assertThat(bucket.cleanedAvailability().state())
                .isEqualTo(AnalyticsAvailability.State.UNAVAILABLE);
            assertThat(bucket.cleanedAvailability().reason())
                .isEqualTo(AnalyticsAvailability.Reason.NO_LEDGER_HISTORY);
        });
    }

    @Test
    void sourcePageUsesTheExactBucketPredicate() throws Exception {
        String sourcePath = "/analytics/source";
        String html = mvc.perform(get(sourcePath)
                .params(sourceParameters())
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
              "2</strong> indexed source rows match this bucket", "Visible sender", "Safe sender",
              "Back to analytics");
        assertThat(html).doesNotContain(
              "PROVIDER-ID-CANARY", "FOREIGN-PROVIDER-CANARY", "PRIVATE-SUBJECT-CANARY",
              "Export exact source as CSV", "analytics-download", "download=");

        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void currentDayAndRangeCrossingCoverageEndArePartial() throws Exception {
        progress.deleteAll();
        completeProgress(owned, Instant.parse("2026-08-09T12:00:00Z"));

        String currentDay = analyticsHtml("2026-08-09", "2026-08-09", "day", "none",
            owned.getId().toString());
        String crossing = analyticsHtml("2026-08-09", "2026-08-10", "day", "none",
            owned.getId().toString());

        assertThat(currentDay).contains("<h1>Analytics</h1>", "Loaded 2 messages");
        assertThat(crossing).contains("<h1>Analytics</h1>", "Loaded 2 messages");
        assertThat(currentDay).doesNotContain("out of");
        assertThat(crossing).doesNotContain("out of");
        assertThat(crossing).doesNotContain(
            "period_start_utc", "PROVIDER-ID-CANARY", "FOREIGN-PROVIDER-CANARY");
    }

    @Test
    void futureSourceNeverClaimsUnavailableZero() throws Exception {
        progress.deleteAll();
        completeProgress(owned, Instant.parse("2026-08-09T12:00:00Z"));
        var futureSource = sourceParameters(
            "2026-08-10", "2026-08-10",
            "2026-08-10T00:00:00Z", "2026-08-11T00:00:00Z");

        String html = mvc.perform(get("/analytics/source")
                .params(futureSource)
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
            "newer than completed sync coverage",
            "Source rows are unavailable for this bucket; no zero is inferred.",
            "Back to analytics");
        assertThat(html).doesNotContain(
            "<strong>0</strong>", "originating aggregate is zero",
            "Export exact source as CSV", "PROVIDER-ID-CANARY");
    }

    @Test
    void maximumRangesUseOneSetBasedReceivedAndCleanedBucketCallPerDashboard()
            throws Exception {
        clearInvocations(analyticsRepository, ledgerRepository);

        analyticsHtml("2025-08-10", "2026-08-10", "day", "none",
            owned.getId().toString());
        analyticsHtml("2025-08-10", "2026-08-10", "week", "none",
              owned.getId().toString());
        analyticsHtml("2025-08-10", "2026-08-10", "month", "none",
              owned.getId().toString());

        verify(analyticsRepository, times(1)).countByBuckets(
            eq(List.of(owned.getId())),
            argThat(buckets -> buckets.size() == 366));
        verify(analyticsRepository, times(3)).receivedFact(
            eq(List.of(owned.getId())), any(Instant.class), any(Instant.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.tidymail.dto.AnalyticsQuery.DateRange>> cleanedBuckets =
            ArgumentCaptor.forClass(List.class);
        verify(ledgerRepository, times(6)).cleanedCountByBuckets(
              eq(List.of(owned.getId())), cleanedBuckets.capture(),
              eq(com.tidymail.dto.AnalyticsQuery.AnalyticsAction.ALL));
        assertThat(cleanedBuckets.getAllValues())
            .extracting(List::size)
              .containsExactly(366, 366, 54, 54, 13, 13);
        verify(ledgerRepository, times(0)).cleanedCount(
              any(), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void analyticsUsesCompactControlRowAndDrilldownLinksHaveBase44PixelTargets() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/workspace.css"));
        String analytics = Files.readString(Path.of("src/main/resources/templates/analytics.html"));
        String source = Files.readString(
            Path.of("src/main/resources/templates/fragments/analytics-source-frag.html"));

        assertThat(css).contains(
            ".clear-link {\n    display: inline-flex;\n    min-height: 44px",
            ".pagination a {\n    display: inline-flex;\n    min-height: 44px");
        assertThat(analytics)
            .containsOnlyOnce("<span>Time period</span>")
            .containsOnlyOnce("<span>Group by</span>")
            .containsOnlyOnce("class=\"analytics-control-row\"")
            .doesNotContain("Clear filters");
        assertThat(source).contains(
            "class=\"pagination\"", "Previous page", "Next page",
            "class=\"clear-link\"", "Back to analytics");
    }

      @Test
      void authenticatedAnalyticsTemplatesExposeNoExportOrSnapshotAffordance()
              throws Exception {
          for (String template : List.of("analytics.html", "analytics-source.html")) {
              String html = Files.readString(
                  Path.of("src/main/resources/templates").resolve(template));
              assertThat(html)
                  .as(template)
                  .doesNotContain(
                      ".csv", "snapshot", "download", "workspace-download",
                      "analytics-download", "Export");
          }
      }

    @Test
    void repositoryFailureHasBoundedPrivateFreeResponseHeadersAndLogs() throws Exception {
        String privateFailure = "RAW-REPOSITORY-EXCEPTION-TOKEN-COOKIE-CANARY";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        doThrow(new DataAccessResourceFailureException(privateFailure))
            .when(analyticsRepository).receivedFact(
                any(), any(Instant.class), any(Instant.class));
        try {
            var response = mvc.perform(get("/analytics")
                    .param("from", "2026-08-01")
                    .param("to", "2026-08-03")
                    .param("account", owned.getId().toString())
                    .param("action", "all")
                    .param("group", "day")
                    .param("compare", "none")
                    .with(authentication(auth(OWNER))))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse();
            String output = response.getContentAsString(StandardCharsets.UTF_8)
                + response.getHeaderNames()
                + response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(output)
                .contains("temporarily unavailable")
                .doesNotContain(privateFailure, OWNER, "PROVIDER-ID-CANARY");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("DataAccessResourceFailureException"))
                .noneMatch(message -> message.contains(privateFailure));
        } finally {
            root.detachAppender(appender);
            appender.stop();
            doCallRealMethod().when(analyticsRepository).receivedFact(
                any(), any(Instant.class), any(Instant.class));
        }
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void topSenderUsesOpaqueOwnerBoundScope()
            throws Exception {
        String html = analyticsHtml(
            "2026-08-01", "2026-08-03", "day", "none", owned.getId().toString());
        assertThat(html).contains(
            "Top Received", "Email address", "Domain", "Top Sent");
        assertThat(html).doesNotContain(
            "analyticsKey=", "Open sender queue", "Visible sender",
            "FOREIGN-PROVIDER-CANARY");
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    private org.springframework.util.LinkedMultiValueMap<String, String> sourceParameters() {
        return sourceParameters(
            "2026-08-01", "2026-08-03",
            BUCKET_START.toString(), BUCKET_END.toString());
    }

    private org.springframework.util.LinkedMultiValueMap<String, String> sourceParameters(
            String from, String to, String bucketStart, String bucketEnd) {
        var parameters = new org.springframework.util.LinkedMultiValueMap<String, String>();
        parameters.add("from", from);
        parameters.add("to", to);
        parameters.add("account", owned.getId().toString());
        parameters.add("action", "all");
        parameters.add("group", "day");
        parameters.add("compare", "none");
        parameters.add("series", "received");
        parameters.add("bucketStart", bucketStart);
        parameters.add("bucketEnd", bucketEnd);
        parameters.add("page", "0");
        return parameters;
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
            UserAccount account, String providerId, Instant receivedAt, String senderName) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId("THREAD-" + providerId);
        row.setReceivedAt(receivedAt);
        row.setFromDisplayName(senderName);
        row.setFromAddress("sender@example.com");
        row.setFromDomain("example.com");
        row.setSubjectHeader("PRIVATE-SUBJECT-CANARY");
        metadata.saveAndFlush(row);
    }

    private void completeProgress(UserAccount account) {
        completeProgress(account, Instant.now());
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

    private String analyticsHtml(
            String from, String to, String group, String compare, String account)
            throws Exception {
        return mvc.perform(get("/analytics")
                .param("from", from)
                .param("to", to)
                .param("account", account)
                .param("action", "all")
                .param("group", group)
                .param("compare", compare)
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private static OAuth2AuthenticationToken auth(String email) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", email);
        attributes.put("name", "Analytics Owner");
        attributes.put("sub", "subject-" + email);
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        return new OAuth2AuthenticationToken(user, authorities, "google");
    }
}
