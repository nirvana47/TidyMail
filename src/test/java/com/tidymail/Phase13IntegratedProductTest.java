package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.dto.AnalyticsMetric;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsRatio;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.AnalyticsQueryService;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;

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
    @Autowired AnalyticsQueryService analyticsQueries;
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired SyncProgressRepository progress;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService") MailProvider gmail;
    @MockBean(name = "outlookMailService") MailProvider outlook;
    @MockBean UnsubscribeHttpTransport unsubscribeTransport;

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
        clearInvocations(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void work05_allRepresentedControlsExecute_andDecorativePatternsAreAbsent()
            throws Exception {
        Map<String, List<String>> pages = Map.of(
            "/senders", List.of("Settings", "Expand all"),
            "/unread", List.of("Settings", "Expand all"),
            "/unsubscribe", List.of(
                "Bulk Unsubscribe", "Apply controls"),
            "/review", List.of("Review", "History"),
            "/analytics", List.of("Time period", "Group by"));
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
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void anly01_factTimeMetrics_includeProvenFacts_andExcludeMutableOrPendingFacts()
            throws Exception {
        AnalyticsPageModel page = analyticsPage(defaultAnalytics());
        assertThat(page.dashboard().metrics().get(AnalyticsMetric.MetricId.RECEIVED).value())
            .isEqualTo(2);
        assertThat(page.dashboard().metrics().get(AnalyticsMetric.MetricId.ARCHIVED).value())
            .isZero();
        assertThat(page.toString()).doesNotContain("foreign-canary", "owned-one");
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void anly02_groupAndFilters_roundTrip_andRejectInvalidBroadening() throws Exception {
        AnalyticsQuery query = analyticsQueries.parse(defaultAnalytics());
        assertThat(query.canonicalParameters()).containsExactly(
            Map.entry("from", "2026-08-01"),
            Map.entry("to", "2026-08-03"),
            Map.entry("account", owned.getId().toString()),
            Map.entry("action", "archive"),
            Map.entry("group", "day"),
            Map.entry("compare", "prior"));
        mvc.perform(get("/analytics")
                .param("from", "2026-08-01")
                .param("to", "2026-08-03")
                .param("account", foreign.getId().toString())
                .with(authentication(auth(OWNER))))
            .andExpect(status().isNotFound());
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void anly03_priorInterval_isEqualDuration_andSuppressesInvalidPercent() {
        AnalyticsQuery query = analyticsQueries.parse(defaultAnalytics());
        assertThat(query.priorRange().duration()).isEqualTo(query.range().duration());
        assertThat(query.priorRange().endExclusive()).isEqualTo(query.range().startInclusive());
        AnalyticsAvailability available = AnalyticsAvailability.available(null, null, 0);
        assertThat(AnalyticsMetric.count(
            AnalyticsMetric.MetricId.RECEIVED, 1, 0L, available, available)
            .percentDelta()).isNull();
        assertThat(AnalyticsMetric.count(
            AnalyticsMetric.MetricId.RECEIVED, 2, 1L, available, available)
            .percentDelta()).isEqualByComparingTo("100.0");
    }

    @Test
    void anly04_eachTrendBucketEqualsItsSourcePage() throws Exception {
        LinkedMultiValueMap<String, String> source = sourceParameters();
        String html = mvc.perform(get("/analytics/source").params(source)
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("2</strong> indexed source rows match this bucket");
        assertThat(html).contains("Safe Sender", "=Formula Sender")
            .doesNotContain("foreign-canary");
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
    }

    @Test
    void anly05_summaryOmitsRemovedSenderDrilldown_andRejectsForeignScope()
            throws Exception {
        String html = mvc.perform(get("/analytics").params(defaultAnalytics())
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain(
            "Open sender queue", "analyticsKey=", "sender%40example.com",
            "foreign-integrated@example.com");
        mvc.perform(get("/senders")
                .param("account", foreign.getId().toString())
                .with(authentication(auth(OWNER))))
            .andExpect(status().isNotFound());
    }

    @Test
    void anly06_outcomesAndCoverage_equalExactIntegerPartitions() throws Exception {
        AnalyticsPageModel page = analyticsPage(defaultAnalytics());
        var outcomes = page.dashboard().outcomes();
        assertThat(outcomes.requested()).isEqualTo(
            outcomes.succeeded() + outcomes.failed()
                + outcomes.verificationPending() + outcomes.notDispatched());
        assertThat(List.of(
            page.dashboard().reviewCoverage(),
            page.dashboard().cleanupCoverage(),
            page.dashboard().unsubscribeCompletion()))
            .allSatisfy(ratio -> {
                assertThat(ratio.numerator()).isGreaterThanOrEqualTo(0);
                assertThat(ratio.denominator()).isGreaterThanOrEqualTo(0);
            });
        assertThat(outcomes.accepted()).isGreaterThanOrEqualTo(outcomes.succeeded());
    }

    @Test
    void anly07_efficiencyAndDelay_useLockedDenominatorsAndBoundaries()
            throws Exception {
        AnalyticsPageModel page = analyticsPage(defaultAnalytics());
        List<AnalyticsRatio> ratios = List.of(
            page.dashboard().inboxReduction(),
            page.dashboard().cleanupRate(),
            page.dashboard().confidenceRetention());
        assertThat(ratios)
            .allSatisfy(ratio -> {
                assertThat(ratio.definition()).isNotBlank();
                assertThat(ratio.numerator()).isGreaterThanOrEqualTo(0);
            });
        assertThat(page.dashboard().triageDelay().buckets())
            .extracting(bucket -> bucket.key())
            .containsExactly(
                "under_15m", "15m_to_1h", "1h_to_24h", "24h_to_7d",
                "7d_or_more");
        assertThat(page.dashboard().triageDelay().excludedCount())
            .isGreaterThanOrEqualTo(0);
    }

    @Test
    void anly08_zeroPartialAndUnavailable_remainDistinct() throws Exception {
        AnalyticsPageModel available = analyticsPage(defaultAnalytics());
        assertThat(available.dashboard().metrics()
            .get(AnalyticsMetric.MetricId.RECEIVED).availability().state())
            .isEqualTo(AnalyticsAvailability.State.PARTIAL);
        assertThat(available.dashboard().metrics()
            .get(AnalyticsMetric.MetricId.ARCHIVED).availability().state())
            .isEqualTo(AnalyticsAvailability.State.UNAVAILABLE);

        progress.deleteAll();
        completeProgress(owned, START.plusSeconds(3_600));
        var future = defaultAnalytics();
        future.set("from", "2026-08-10");
        future.set("to", "2026-08-10");
        AnalyticsPageModel unavailable = analyticsPage(future);
        assertThat(unavailable.dashboard().metrics().values()).anyMatch(metric ->
            !metric.availability().available());
        assertThat(unavailable.toString()).doesNotContain("value=0, availability=UNAVAILABLE");
    }

    @Test
    void qual02_allFivePagesMeetResponsiveAccessibleControlContract() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/workspace.css"));
        for (String template : List.of(
                "senders.html", "unread.html", "unsubscribe.html",
                "review.html", "analytics.html")) {
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

    private AnalyticsPageModel analyticsPage(LinkedMultiValueMap<String, String> parameters)
            throws Exception {
        MvcResult result = mvc.perform(get("/analytics")
                .params(parameters)
                .with(authentication(auth(OWNER))))
            .andExpect(status().isOk()).andReturn();
        verifyNoInteractions(gmail, outlook, unsubscribeTransport);
        return (AnalyticsPageModel) result.getModelAndView().getModel().get("page");
    }

    private LinkedMultiValueMap<String, String> defaultAnalytics() {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("from", "2026-08-01");
        parameters.add("to", "2026-08-03");
        parameters.add("account", owned.getId().toString());
        parameters.add("action", "archive");
        parameters.add("group", "day");
        parameters.add("compare", "prior");
        return parameters;
    }

    private LinkedMultiValueMap<String, String> sourceParameters() {
        var parameters = defaultAnalytics();
        parameters.set("action", "all");
        parameters.set("compare", "none");
        parameters.add("series", "received");
        parameters.add("bucketStart", "2026-08-01T00:00:00Z");
        parameters.add("bucketEnd", "2026-08-02T00:00:00Z");
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
