package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.UnreadDecisionRow;
import com.tidymail.dto.UnreadCategoryGroup;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.entity.ProtectedSender;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.DecisionSurfaceSql;
import com.tidymail.service.DecisionClassifier;
import com.tidymail.service.DecisionRuleSet;
import com.tidymail.service.DecisionRuleSet.SenderFacts;
import com.tidymail.service.DecisionRuleSet.UnreadFacts;
import com.tidymail.service.SenderDecisionQueryService;
import com.tidymail.service.UnreadDecisionQueryService;
import com.tidymail.support.UserAccountFixtures;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

class Phase10DecisionQueryTest {

    @Nested
    @DisplayName("Canonical query and V1 rule contracts")
    class PureContracts {

        @Test
        void senderDefaultsAndCanonicalValuesRoundTripInStableOrder() {
            SenderQuery defaults = SenderQuery.parse(Map.of());

            assertThat(defaults).isEqualTo(SenderQuery.parse(toMulti(defaults.canonicalParameters())));
            assertThat(defaults).isEqualTo(SenderQuery.parse(Map.of(
                "account", List.of("current"),
                "window", List.of("30d"),
                "type", List.of("all"),
                "risk", List.of("all"),
                "unread", List.of("all"),
                "queue", List.of("open"),
                "protected", List.of("exclude"),
                "sort", List.of("volume_desc"),
                "page", List.of("0"))));
            assertThat(defaults.canonicalParameters().keySet()).containsExactly(
                "account", "search", "window", "type", "risk", "unread",
                "queue", "protected", "sort", "page");
            assertThat(defaults.withPage(8).resetPage()).isEqualTo(defaults);
            assertThat(defaults.offset()).isZero();
        }

        @Test
        void unreadNormalizesExactSenderAndRoundTripsEveryValue() {
            UnreadQuery query = UnreadQuery.parse(Map.ofEntries(
                Map.entry("account", List.of(" +0012 ")),
                Map.entry("search", List.of("  Quarterly % report  ")),
                Map.entry("type", List.of("list")),
                Map.entry("age", List.of("older_30d")),
                Map.entry("waiting", List.of("not_waiting")),
                Map.entry("thread", List.of("threaded")),
                Map.entry("recommendation", List.of("archive")),
                Map.entry("sender", List.of("  Sender@Example.COM ")),
                Map.entry("provider", List.of("google")),
                Map.entry("sort", List.of("newest_first")),
                Map.entry("page", List.of("3"))));

            assertThat(query.search()).isEqualTo("Quarterly % report");
            assertThat(query.account()).isEqualTo("12");
            assertThat(query.sender()).isEqualTo("sender@example.com");
            assertThat(UnreadQuery.parse(toMulti(query.canonicalParameters()))).isEqualTo(query);
            assertThat(query.resetPage().page()).isZero();
            assertThat(query.offset()).isEqualTo(75);
        }

        @Test
        void recognizedDuplicatesUnknownsMalformedTextAndInvalidValuesFailClosed() {
            assertBad(() -> SenderQuery.parse(Map.of("page", List.of("-1"))), "page");
            assertBad(() -> SenderQuery.parse(Map.of("sort", List.of("not-a-sort"))), "sort");
            assertBad(() -> SenderQuery.parse(Map.of("search", List.of("x".repeat(201)))), "search");
            assertBad(() -> SenderQuery.parse(Map.of("search", List.of("ok\nunsafe"))), "search");
            assertBad(() -> SenderQuery.parse(Map.of("search", List.of("bad\uFFFDtext"))), "search");
            assertBad(() -> SenderQuery.parse(Map.of("unknown", List.of("raw-secret"))), "query");
            assertBad(() -> SenderQuery.parse(Map.of("page", List.of("0", "1"))), "page");
            assertBad(() -> UnreadQuery.parse(Map.of("provider", List.of("outlook"))), "provider");
            assertBad(() -> UnreadQuery.parse(Map.of("account", List.of("foreign"))), "account");
            assertBad(() -> UnreadQuery.parse(Map.of("sender", List.of("__missing__"))), "sender");
            assertBad(() -> UnreadQuery.parse(Map.of("sender", List.of("two@@example.com"))), "sender");
        }

        @Test
        void sqlUsesRootLocaleBoundValuesAndCompleteUnreadTieBreakers() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                SenderQuery sender = SenderQuery.parse(Map.of(
                    "search", List.of("I%' OR 1=1 --"),
                    "window", List.of("all"),
                    "queue", List.of("all"),
                    "protected", List.of("include")));
                DecisionSurfaceSql.QuerySpec senderSpec =
                    DecisionSurfaceSql.sender(List.of(1L), sender, Instant.EPOCH);

                assertThat(senderSpec.parameters().get("search"))
                    .isEqualTo("%i!%' or 1=1 --%");
                assertThat(senderSpec.cte()).doesNotContain("I%' OR 1=1 --");
            } finally {
                Locale.setDefault(original);
            }

            for (UnreadQuery.Sort sort : UnreadQuery.Sort.values()) {
                UnreadQuery query = new UnreadQuery(
                    "all", "", UnreadQuery.Type.ALL, UnreadQuery.Age.ALL,
                    UnreadQuery.Waiting.ALL, UnreadQuery.ThreadShape.ALL,
                    UnreadQuery.Recommendation.ALL, "", UnreadQuery.Provider.ALL, sort, 0);
                assertThat(DecisionSurfaceSql.unread(
                    List.of(1L, 2L), query, Instant.EPOCH).orderBy())
                    .contains("account_id ASC", "metadata_id");
            }
        }

        @Test
        void v1SenderRulesCoverOrderedBranchesAndExactThresholds() {
            Instant now = Instant.parse("2026-08-09T12:00:00Z");

            assertThat(DecisionRuleSet.V1.classify(sender(true, false, false, false, false,
                false, 3, 1, now.minus(100, ChronoUnit.DAYS)), now).type())
                .isEqualTo(DecisionRuleSet.SenderType.LIST);
            assertThat(DecisionRuleSet.V1.classify(sender(false, true, false, false, false,
                false, 3, 3, now.minus(90, ChronoUnit.DAYS)), now).recommendation())
                .isEqualTo(DecisionRuleSet.SenderRecommendation.MARK_READ);
            assertThat(DecisionRuleSet.V1.classify(sender(false, true, false, false, false,
                false, 3, 1, now.minus(90, ChronoUnit.DAYS).minusNanos(1)), now).recommendation())
                .isEqualTo(DecisionRuleSet.SenderRecommendation.TRASH);
            assertThat(DecisionRuleSet.V1.classify(sender(false, false, true, false, false,
                false, 1, 0, now), now).type())
                .isEqualTo(DecisionRuleSet.SenderType.TRANSACTIONAL);
            assertThat(DecisionRuleSet.V1.classify(sender(false, false, false, true, true,
                false, 1, 0, now), now).type())
                .isEqualTo(DecisionRuleSet.SenderType.PERSONAL);
            assertThat(DecisionRuleSet.V1.classify(sender(false, false, false, false, false,
                false, 1, 0, null), now).recommendation())
                .isEqualTo(DecisionRuleSet.SenderRecommendation.REVIEW);
            assertThat(DecisionRuleSet.V1.classify(sender(true, false, false, false, false,
                true, 1, 1, now.minus(200, ChronoUnit.DAYS)), now).recommendation())
                .isEqualTo(DecisionRuleSet.SenderRecommendation.KEEP);
        }

        @Test
        void v1UnreadRulesAreConservativeAndEveryReasonHasVisibleCopy() {
            Instant now = Instant.parse("2026-08-09T12:00:00Z");
            assertThat(DecisionRuleSet.V1.classify(
                new UnreadFacts(false, false, true, false, false, 1, now), now).recommendation())
                .isEqualTo(DecisionRuleSet.UnreadRecommendation.REPLY_REQUIRED);
            assertThat(DecisionRuleSet.V1.classify(
                new UnreadFacts(false, false, false, false, false, 2, now), now).recommendation())
                .isEqualTo(DecisionRuleSet.UnreadRecommendation.REVIEW);
            assertThat(DecisionRuleSet.V1.classify(
                new UnreadFacts(false, false, false, false, false, 1, null), now).recommendation())
                .isEqualTo(DecisionRuleSet.UnreadRecommendation.KEEP);
            assertThat(DecisionRuleSet.V1.classify(
                new UnreadFacts(true, false, false, false, false, 1,
                    now.minus(90, ChronoUnit.DAYS)), now).recommendation())
                .isEqualTo(DecisionRuleSet.UnreadRecommendation.ARCHIVE);
            assertThat(DecisionRuleSet.V1.classify(
                new UnreadFacts(true, false, false, false, false, 1,
                    now.minus(90, ChronoUnit.DAYS).minusNanos(1)), now).recommendation())
                .isEqualTo(DecisionRuleSet.UnreadRecommendation.TRASH);
            assertThat(DecisionRuleSet.V1.classify(
                new UnreadFacts(true, false, false, false, true, 1,
                    now.minus(200, ChronoUnit.DAYS)), now).recommendation())
                .isEqualTo(DecisionRuleSet.UnreadRecommendation.MARK_READ);
            assertThat(List.of(DecisionRuleSet.ReasonCode.values()))
                .allSatisfy(reason -> assertThat(reason.copy()).isNotBlank());
        }

        @Test
        void servicesAndDtosHaveNoProviderOrPrivatePayloadSurface() {
            for (Class<?> type : List.of(
                    SenderDecisionQueryService.class, UnreadDecisionQueryService.class,
                    DecisionSurfaceQueryRepository.class, DecisionClassifier.class)) {
                for (Constructor<?> constructor : type.getConstructors()) {
                    assertThat(constructor.getParameterTypes())
                        .noneMatch(MailProvider.class::isAssignableFrom);
                }
                assertThat(List.of(type.getDeclaredFields()))
                    .noneMatch(field -> MailProvider.class.isAssignableFrom(field.getType()));
            }
            for (Class<?> type : List.of(SenderDecisionRow.class, UnreadDecisionRow.class)) {
                assertThat(List.of(type.getRecordComponents()))
                    .extracting(component -> component.getName().toLowerCase())
                    .noneMatch(name -> Set.of(
                        "body", "snippet", "attachment", "token", "rawheaders",
                        "providermessageid", "recipient", "unsubscribe").stream()
                        .anyMatch(name::contains));
            }
            assertThat(ProtectedSender.class.getAnnotation(
                org.hibernate.annotations.Immutable.class)).isNotNull();
            for (Field field : ProtectedSender.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase())
                    .doesNotContain("label", "starred", "important");
            }
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
    @DisplayName("Postgres classified query integration")
    class PostgresQueryIntegration {
        @Autowired JdbcTemplate jdbc;
        @Autowired SenderDecisionQueryService senderService;
        @Autowired UnreadDecisionQueryService unreadService;

        @MockBean(name = "gmailMailService")
        MailProvider gmailProvider;

        @MockBean(name = "outlookMailService")
        MailProvider outlookProvider;

        @BeforeEach
        void clean() {
            jdbc.update("DELETE FROM user_accounts");
        }

        @Test
        void senderAndUnreadQueriesClassifyFilterSortPageAndIsolateAccounts() throws Exception {
            long first = account("first-p10@example.com");
            long second = account("second-p10@example.com");
            Instant now = Instant.now();
            for (int i = 0; i < 27; i++) {
                insertMetadata(first, "first-" + i, "thread-" + i,
                    i == 0 ? "literal%_!@example.com" : "sender" + i + "@example.com",
                    "Sender " + i, "example.com", "Subject " + i,
                    now.minus(i + 1, ChronoUnit.HOURS), true,
                    i == 0 || i >= 4 && i % 3 == 0 ? array("list.example") : array(),
                    i == 1 ? array("auto-generated") : i == 4 ? array("no") : array(),
                    i == 5 ? array("personal") : array(),
                    array(),
                    i == 3 ? array("<parent>") : array(),
                    i == 3 ? array("<id>") : array(),
                    i == 2 ? array("<bounce@example.com>") : array());
            }
            insertMetadata(second, "same-address", null, "sender1@example.com",
                "Other account sender", "example.com", "Other subject",
                now.minus(2, ChronoUnit.HOURS), true,
                array(), array(), array(), array(), array(), array("<id>"), array());
            insertMetadata(first, "missing-sender", null, null,
                null, null, null, null, true,
                array(), array(), array(), array(), array(), array(), array());
            jdbc.update("""
                INSERT INTO protected_senders(account_id,sender_address,source)
                VALUES (?,?, 'USER')
                """, first, "sender2@example.com");

            SenderQuery include = SenderQuery.parse(Map.of(
                "account", List.of("all"), "window", List.of("all"),
                "queue", List.of("all"), "protected", List.of("include")));
            DecisionPage<SenderDecisionRow> senderPage = senderService.query(List.of(first, second), include);
            assertThat(senderPage.filteredCount()).isEqualTo(29);
            assertThat(senderPage.rows()).hasSize(25);
            assertThat(senderPage.nextQuery()).containsEntry("page", "1");
            assertThat(senderService.query(List.of(first, second), include).rows())
                .extracting(SenderDecisionRow::rowKey)
                .containsExactlyElementsOf(
                    senderPage.rows().stream().map(SenderDecisionRow::rowKey).toList());
            DecisionPage<SenderDecisionRow> senderPage2 =
                senderService.query(List.of(first, second), include.withPage(1));
            assertThat(senderPage2.rows()).hasSize(4);
            assertThat(senderPage.rows()).extracting(SenderDecisionRow::rowKey)
                .doesNotContainAnyElementsOf(
                    senderPage2.rows().stream().map(SenderDecisionRow::rowKey).toList());
            assertThat(senderService.query(List.of(first), include).filteredCount()).isEqualTo(28);
            assertThatThrownBy(() -> senderService.query(
                List.of(first), new SenderQuery(
                    Long.toString(second), "", SenderQuery.Window.ALL, SenderQuery.Type.ALL,
                    SenderQuery.Risk.ALL, SenderQuery.UnreadPressure.ALL,
                    SenderQuery.QueueState.ALL, SenderQuery.Protection.INCLUDE,
                    SenderQuery.Sort.VOLUME_DESC, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authorized account scope does not match query");
            Map<String, DecisionRuleSet.SenderType> types = new HashMap<>();
            senderPage.rows().stream().filter(row -> row.accountId() == first)
                .forEach(row -> types.put(row.senderAddress(), row.type()));
            senderPage2.rows().stream().filter(row -> row.accountId() == first)
                .forEach(row -> types.put(row.senderAddress(), row.type()));
            assertThat(types)
                .containsEntry("sender1@example.com", DecisionRuleSet.SenderType.AUTOMATED)
                .containsEntry("sender2@example.com", DecisionRuleSet.SenderType.TRANSACTIONAL)
                .containsEntry("sender3@example.com", DecisionRuleSet.SenderType.PERSONAL)
                .containsEntry("sender4@example.com", DecisionRuleSet.SenderType.UNKNOWN)
                .containsEntry("sender5@example.com", DecisionRuleSet.SenderType.UNKNOWN);
            assertThat(senderService.query(List.of(first), SenderQuery.parse(Map.of(
                "window", List.of("all"), "queue", List.of("all"),
                "protected", List.of("only")))).rows())
                .extracting(SenderDecisionRow::senderAddress)
                .containsExactly("sender2@example.com");
            assertThat(senderService.query(List.of(first), SenderQuery.parse(Map.of(
                "search", List.of("%_!"), "window", List.of("all"),
                "queue", List.of("all"), "protected", List.of("include")))).rows())
                .extracting(SenderDecisionRow::senderAddress)
                .containsExactly("literal%_!@example.com");
            exerciseEverySenderBranch(first, include);

            DecisionPage<UnreadDecisionRow> unread = unreadService.query(
                List.of(first, second), UnreadQuery.parse(Map.of(
                    "account", List.of("all"), "sort", List.of("sender_asc"))));
            assertThat(unread.filteredCount()).isEqualTo(29);
            assertThat(unread.rows()).hasSize(25);
            assertThat(unread.rows()).allSatisfy(row -> {
                assertThat(row.reasonCodes()).isNotEmpty();
                assertThat(row.reasons()).allSatisfy(copy -> assertThat(copy).isNotBlank());
                assertThat(row.rowKey()).startsWith("u1.");
            });
            assertThat(unreadService.query(List.of(first), UnreadQuery.parse(Map.of(
                "type", List.of("list")))).filteredCount()).isEqualTo(9);
            assertThat(unreadService.query(List.of(first), UnreadQuery.parse(Map.of(
                "waiting", List.of("waiting_on_me")))).rows())
                .extracting(UnreadDecisionRow::senderAddress)
                .containsExactly("sender3@example.com");
            assertThat(unreadService.query(List.of(second), UnreadQuery.parse(Map.of(
                "sender", List.of("SENDER1@EXAMPLE.COM")))).filteredCount()).isEqualTo(1);
            assertThatThrownBy(() -> unreadService.query(
                List.of(first, second), UnreadQuery.parse(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authorized account scope does not match query");
            exerciseEveryUnreadBranch(first);
            verifyNoInteractions(gmailProvider, outlookProvider);
        }

        @Test
        void warmedTenThousandRowQueriesStayWithinBudget() throws Exception {
            long account = account("budget-p10@example.com");
            Instant now = Instant.now();
            try (Connection connection = jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO message_metadata(
                        account_id,provider,provider_message_id,from_address,from_display_name,
                        from_domain,subject_header,received_at,last_synced_at,unread,labels)
                    VALUES (?,'google',?,?,?,?,?,?,?,?,ARRAY['INBOX'])
                    """)) {
                    for (int i = 0; i < 10_000; i++) {
                        statement.setLong(1, account);
                        statement.setString(2, "budget-" + i);
                        statement.setString(3, "sender" + (i % 500) + "@example.com");
                        statement.setString(4, "Sender " + (i % 500));
                        statement.setString(5, "example.com");
                        statement.setString(6, "Budget subject " + i);
                        statement.setTimestamp(
                            7, Timestamp.from(now.minus(i % 100, ChronoUnit.HOURS)));
                        statement.setTimestamp(8, Timestamp.from(now));
                        statement.setBoolean(9, i % 2 == 0);
                        statement.addBatch();
                        if (i % 500 == 499) statement.executeBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            }
            SenderQuery senderQuery = SenderQuery.parse(Map.of(
                "window", List.of("all"), "queue", List.of("all"),
                "protected", List.of("include"), "sort", List.of("unread_desc")));
            UnreadQuery unreadQuery = UnreadQuery.parse(Map.of(
                "search", List.of("example.com"), "sort", List.of("newest_first")));
            assertThat(senderService.query(List.of(account), senderQuery).filteredCount()).isEqualTo(500);
            assertThat(unreadService.query(List.of(account), unreadQuery).filteredCount()).isEqualTo(5_000);

            assertTimeout(Duration.ofSeconds(2),
                () -> senderService.query(List.of(account), senderQuery));
            assertTimeout(Duration.ofSeconds(2),
                () -> unreadService.query(List.of(account), unreadQuery));
            verifyNoInteractions(gmailProvider, outlookProvider);
        }

        @Test
        void emptyAndNullFirstHeaderArraysDoNotCreateAutomatedSignals() throws Exception {
            long account = account("array-semantics-p10@example.com");
            Instant now = Instant.now();
            insertMetadata(account, "empty-arrays", null, "empty@example.com",
                "Empty", "example.com", "Empty arrays", now, true,
                array(), array(), array(), array(), array(), array(), array());
            insertMetadata(account, "null-first", null, "null@example.com",
                "Null", "example.com", "Null first values", now, true,
                array(), new String[] {null}, new String[] {null},
                array(), array(), array(), array());

            SenderQuery query = SenderQuery.parse(Map.of(
                "window", List.of("all"), "type", List.of("unknown"),
                "queue", List.of("all"), "protected", List.of("include")));
            assertThat(senderService.query(List.of(account), query).rows())
                .extracting(SenderDecisionRow::senderAddress)
                .containsExactly("empty@example.com", "null@example.com");
            assertThat(unreadService.query(List.of(account), UnreadQuery.parse(Map.of(
                "type", List.of("one_to_one")))).rows())
                .extracting(UnreadDecisionRow::senderAddress)
                .containsExactly("empty@example.com", "null@example.com");
        }

        @Test
        void unreadGroupsAreNonExclusiveAndDirectMailIsSortedBySenderVolume()
                throws Exception {
            long account = account("grouping-p10@example.com");
            Instant now = Instant.now();
            insertMetadata(account, "list-old", "list-thread", "list@example.com",
                "Old List", "example.com", "Old list", now.minus(40, ChronoUnit.DAYS), true,
                array("list.example"), array(), array(), array(), array(), array(), array());
            for (int index = 0; index < 3; index++) {
                insertMetadata(account, "direct-high-" + index, "direct-high-" + index,
                    "high@example.com", "High Volume", "example.com", "Direct high",
                    now.minus(index, ChronoUnit.HOURS), true,
                    array(), array(), array(), array(), array(), array(), array());
            }
            insertMetadata(account, "direct-low", "direct-low", "low@example.com",
                "Low Volume", "example.com", "Direct low", now, true,
                array(), array(), array(), array(), array(), array(), array());

            List<UnreadCategoryGroup> groups =
                unreadService.categories(List.of(account), UnreadQuery.parse(Map.of()));
            UnreadCategoryGroup lists = group(groups, "lists-automated");
            UnreadCategoryGroup old = group(groups, "older-than-30-days");
            UnreadCategoryGroup direct = group(groups, "direct-mail");

            assertThat(lists.rows()).extracting(row -> row.senderAddress())
                .containsExactly("list@example.com");
            assertThat(old.rows()).extracting(row -> row.senderAddress())
                .containsExactly("list@example.com");
            assertThat(direct.rows()).extracting(row -> row.senderAddress())
                .containsExactly("high@example.com", "low@example.com");
            assertThat(direct.rows()).extracting(row -> row.unreadCount())
                .containsExactly(3L, 1L);
            assertThat(group(groups, "attachments").dataAvailable()).isTrue();
            assertThat(group(groups, "attachments").rows()).isEmpty();
        }

        private UnreadCategoryGroup group(
                List<UnreadCategoryGroup> groups, String key) {
            return groups.stream()
                .filter(group -> group.key().equals(key))
                .findFirst()
                .orElseThrow();
        }

        private long account(String email) throws SQLException {
            return new UserAccountFixtures(jdbc).createAccount(email);
        }

        private void exerciseEverySenderBranch(long accountId, SenderQuery base) {
            for (SenderQuery.Window value : SenderQuery.Window.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", value, SenderQuery.Type.ALL, SenderQuery.Risk.ALL,
                    SenderQuery.UnreadPressure.ALL, SenderQuery.QueueState.ALL,
                    SenderQuery.Protection.INCLUDE, SenderQuery.Sort.VOLUME_DESC, 0));
            }
            for (SenderQuery.Type value : SenderQuery.Type.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", SenderQuery.Window.ALL, value, SenderQuery.Risk.ALL,
                    SenderQuery.UnreadPressure.ALL, SenderQuery.QueueState.ALL,
                    SenderQuery.Protection.INCLUDE, SenderQuery.Sort.VOLUME_DESC, 0));
            }
            for (SenderQuery.Risk value : SenderQuery.Risk.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", SenderQuery.Window.ALL, SenderQuery.Type.ALL, value,
                    SenderQuery.UnreadPressure.ALL, SenderQuery.QueueState.ALL,
                    SenderQuery.Protection.INCLUDE, SenderQuery.Sort.VOLUME_DESC, 0));
            }
            for (SenderQuery.UnreadPressure value : SenderQuery.UnreadPressure.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", SenderQuery.Window.ALL, SenderQuery.Type.ALL,
                    SenderQuery.Risk.ALL, value, SenderQuery.QueueState.ALL,
                    SenderQuery.Protection.INCLUDE, SenderQuery.Sort.VOLUME_DESC, 0));
            }
            for (SenderQuery.QueueState value : SenderQuery.QueueState.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", SenderQuery.Window.ALL, SenderQuery.Type.ALL,
                    SenderQuery.Risk.ALL, SenderQuery.UnreadPressure.ALL, value,
                    SenderQuery.Protection.INCLUDE, SenderQuery.Sort.VOLUME_DESC, 0));
            }
            for (SenderQuery.Protection value : SenderQuery.Protection.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", SenderQuery.Window.ALL, SenderQuery.Type.ALL,
                    SenderQuery.Risk.ALL, SenderQuery.UnreadPressure.ALL,
                    SenderQuery.QueueState.ALL, value, SenderQuery.Sort.VOLUME_DESC, 0));
            }
            for (SenderQuery.Sort value : SenderQuery.Sort.values()) {
                senderService.query(List.of(accountId), new SenderQuery(
                    base.account(), "", SenderQuery.Window.ALL, SenderQuery.Type.ALL,
                    SenderQuery.Risk.ALL, SenderQuery.UnreadPressure.ALL,
                    SenderQuery.QueueState.ALL, SenderQuery.Protection.INCLUDE, value, 0));
            }
        }

        private void exerciseEveryUnreadBranch(long accountId) {
            UnreadQuery base = UnreadQuery.parse(Map.of());
            for (UnreadQuery.Type value : UnreadQuery.Type.values()) {
                unreadService.query(List.of(accountId), unread(base, value, base.age(),
                    base.waiting(), base.thread(), base.recommendation(), base.provider(), base.sort()));
            }
            for (UnreadQuery.Age value : UnreadQuery.Age.values()) {
                unreadService.query(List.of(accountId), unread(base, base.type(), value,
                    base.waiting(), base.thread(), base.recommendation(), base.provider(), base.sort()));
            }
            for (UnreadQuery.Waiting value : UnreadQuery.Waiting.values()) {
                unreadService.query(List.of(accountId), unread(base, base.type(), base.age(),
                    value, base.thread(), base.recommendation(), base.provider(), base.sort()));
            }
            for (UnreadQuery.ThreadShape value : UnreadQuery.ThreadShape.values()) {
                unreadService.query(List.of(accountId), unread(base, base.type(), base.age(),
                    base.waiting(), value, base.recommendation(), base.provider(), base.sort()));
            }
            for (UnreadQuery.Recommendation value : UnreadQuery.Recommendation.values()) {
                unreadService.query(List.of(accountId), unread(base, base.type(), base.age(),
                    base.waiting(), base.thread(), value, base.provider(), base.sort()));
            }
            for (UnreadQuery.Provider value : UnreadQuery.Provider.values()) {
                unreadService.query(List.of(accountId), unread(base, base.type(), base.age(),
                    base.waiting(), base.thread(), base.recommendation(), value, base.sort()));
            }
            for (UnreadQuery.Sort value : UnreadQuery.Sort.values()) {
                unreadService.query(List.of(accountId), unread(base, base.type(), base.age(),
                    base.waiting(), base.thread(), base.recommendation(), base.provider(), value));
            }
        }

        private UnreadQuery unread(
                UnreadQuery base, UnreadQuery.Type type, UnreadQuery.Age age,
                UnreadQuery.Waiting waiting, UnreadQuery.ThreadShape thread,
                UnreadQuery.Recommendation recommendation, UnreadQuery.Provider provider,
                UnreadQuery.Sort sort) {
            return new UnreadQuery(
                base.account(), "", type, age, waiting, thread, recommendation,
                "", provider, sort, 0);
        }

        private void insertMetadata(
                long accountId, String messageId, String threadId, String address,
                String displayName, String domain, String subject, Instant received,
                boolean unread, String[] listIds, String[] autoSubmitted,
                String[] precedence, String[] feedback, String[] references,
                String[] messageIds, String[] returnPath) throws SQLException {
            try (Connection connection = jdbc.getDataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO message_metadata(
                        account_id,provider,provider_message_id,provider_thread_id,
                        from_address,from_display_name,from_domain,subject_header,
                        received_at,last_synced_at,unread,list_id_values,
                        auto_submitted_values,precedence_values,feedback_id_values,
                        references_values,message_id_values,return_path_values,labels)
                    VALUES (?,'google',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setLong(1, accountId);
                statement.setString(2, messageId);
                statement.setString(3, threadId);
                statement.setString(4, address);
                statement.setString(5, displayName);
                statement.setString(6, domain);
                statement.setString(7, subject);
                statement.setTimestamp(8, received == null ? null : Timestamp.from(received));
                statement.setTimestamp(9, Timestamp.from(Instant.now()));
                statement.setBoolean(10, unread);
                statement.setArray(11, connection.createArrayOf("VARCHAR", listIds));
                statement.setArray(12, connection.createArrayOf("VARCHAR", autoSubmitted));
                statement.setArray(13, connection.createArrayOf("VARCHAR", precedence));
                statement.setArray(14, connection.createArrayOf("VARCHAR", feedback));
                statement.setArray(15, connection.createArrayOf("VARCHAR", references));
                statement.setArray(16, connection.createArrayOf("VARCHAR", messageIds));
                statement.setArray(17, connection.createArrayOf("VARCHAR", returnPath));
                statement.setArray(18, connection.createArrayOf(
                    "VARCHAR", unread ? array("INBOX") : array()));
                statement.executeUpdate();
            }
        }
    }

    private static Map<String, List<String>> toMulti(Map<String, String> values) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.of(value)));
        return result;
    }

    private static void assertBad(Runnable action, String field) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> {
                ResponseStatusException response = (ResponseStatusException) error;
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(response.getReason()).isEqualTo(
                    "Invalid " + field + " query parameter");
                assertThat(response.getReason()).doesNotContain("raw-secret");
            });
    }

    private static SenderFacts sender(
            boolean list, boolean automated, boolean returnPath, boolean messageId,
            boolean thread, boolean protectedSender, long total, long unread, Instant newest) {
        return new SenderFacts(
            list, automated, returnPath, messageId, thread, protectedSender,
            total, unread, newest);
    }

    private static String[] array(String... values) {
        return values;
    }

    private static void insertMinimalMetadata(
            Connection connection, long accountId, String providerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO message_metadata(
                    account_id,provider,provider_message_id,last_synced_at)
                VALUES (?,'google',?,CURRENT_TIMESTAMP)
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, providerId);
            statement.executeUpdate();
        }
    }

    private static long insertProtected(
            Connection connection, long accountId, String address) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO protected_senders(account_id,sender_address,source)
                VALUES (?,?,'USER')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, accountId);
            statement.setString(2, address);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertAudit(
            Connection connection, long accountId, String fingerprint, int senderCount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO protected_override_audits(
                    account_id,selection_surface,selection_mode,action_type,
                    query_fingerprint,protected_sender_count,protected_message_count,reason_code)
                VALUES (?,'SENDERS','ALL_MATCHING','ARCHIVE',?,?,0,'USER_CONFIRMED')
                """)) {
            statement.setLong(1, accountId);
            statement.setString(2, fingerprint);
            statement.setInt(3, senderCount);
            statement.executeUpdate();
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        ArrayList<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name=? ORDER BY ordinal_position
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1));
            }
        }
        return values;
    }

    private static List<String> indexes(Connection connection, String table) throws SQLException {
        ArrayList<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT index_name FROM information_schema.index_columns
                WHERE table_name=?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1).toLowerCase());
            }
        }
        return values;
    }
}
