package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.UnsubscribeCandidateRow;
import com.tidymail.dto.UnsubscribeInspection;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.gmail.GmailSyncConfigProperties;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.security.crypto.CryptoUtil;
import com.tidymail.service.UnsubscribeCandidateService;
import com.tidymail.unsubscribe.ApacheUnsubscribeHttpTransport;
import com.tidymail.unsubscribe.UnsubscribeHttpTransport;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "TOKEN_ENCRYPT_SECRET=0000000000000000000000000000000000000000000000000000000000000000",
    "TOKEN_ENCRYPT_SALT=00000000000000000000000000000000",
    "GOOGLE_CLIENT_ID=",
    "GOOGLE_CLIENT_SECRET=",
    "MICROSOFT_CLIENT_ID=",
    "MICROSOFT_CLIENT_SECRET=",
    "MICROSOFT_TENANT_ID=common",
    "SYNC_INITIAL_PAGE_SIZE=500"
})
class Phase11CandidateQueryTest {
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired UnsubscribeCandidateService candidates;
    @Autowired UnsubscribeCandidateRepository candidateRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired CryptoUtil crypto;
    @Autowired UnsubscribeHttpTransport unsubscribeTransport;
    @Autowired GmailSyncConfigProperties gmailSyncConfig;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void productionContextHasOneHardenedUnsubscribeTransport() {
        assertThat(unsubscribeTransport)
            .isExactlyInstanceOf(ApacheUnsubscribeHttpTransport.class);
        assertThat(gmailSyncConfig.getInitialPageSize()).isEqualTo(200);
    }

    @Test
    void canonicalQueryRoundTripsEveryTypedDimensionAndFailsClosed() {
        assertThat(UnsubscribeQuery.parse(Map.of()).canonicalParameters().keySet())
            .containsExactly(
                "account", "category", "from", "to", "range", "q",
                "defaultAction", "sort", "page");

        for (UnsubscribeQuery.Category category : UnsubscribeQuery.Category.values()) {
            for (UnsubscribeQuery.DefaultAction action
                    : UnsubscribeQuery.DefaultAction.values()) {
                for (UnsubscribeQuery.Sort sort : UnsubscribeQuery.Sort.values()) {
                    UnsubscribeQuery query = UnsubscribeQuery.parse(Map.ofEntries(
                        Map.entry("account", List.of("all")),
                        Map.entry("category", List.of(category.value())),
                        Map.entry("from", List.of("2026-05-10")),
                        Map.entry("to", List.of("2026-08-10")),
                        Map.entry("q", List.of("News")),
                        Map.entry("defaultAction", List.of(action.value())),
                        Map.entry("sort", List.of(sort.value())),
                        Map.entry("page", List.of("3"))));
                    assertThat(UnsubscribeQuery.parseCanonical(query.canonicalParameters()))
                        .isEqualTo(query);
                    assertThat(query.resetPage().page()).isZero();
                    assertThat(query.withPage(2).canonicalParameters())
                        .containsAllEntriesOf(query.canonicalParameters()
                            .entrySet().stream()
                            .filter(entry -> !"page".equals(entry.getKey()))
                            .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue)));
                }
            }
        }
        assertThat(UnsubscribeQuery.parse(
            Map.of("protected", List.of("include"))).protection())
            .isEqualTo(UnsubscribeQuery.Protection.INCLUDE);

        for (Map<String, List<String>> invalid : List.of(
                Map.of("unknown", List.of("x")),
                Map.of("method", List.of("all", "mailto")),
                Map.of("method", List.of("automatic")),
                Map.of("volume", List.of("9")),
                Map.of("confidence", List.of("trusted")),
                Map.of("review", List.of("maybe")),
                Map.of("age", List.of("today")),
                Map.of("protected", List.of("override")),
                Map.of("state", List.of("success")),
                Map.of("category", List.of("pending")),
                Map.of("defaultAction", List.of("archive_delete")),
                Map.of("from", List.of("2026-08-11"), "to", List.of("2026-08-10")),
                Map.of("sort", List.of("random")),
                Map.of("page", List.of("-1")),
                Map.of("q", List.of("x".repeat(201))),
                Map.of("q", List.of("\uFFFD")))) {
            assertThatThrownBy(() -> UnsubscribeQuery.parse(invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("automatic")
                .hasMessageNotContaining("trusted")
                .hasMessageNotContaining("override")
                .hasMessageNotContaining("success");
        }
    }

    @Test
    void v8CleanSchemaIsCompletePrivacyBoundedAndRejectsUnknownEnums() {
        assertThat(columns("message_metadata"))
            .contains(
                "unsubscribe_dkim_pass",
                "unsubscribe_dkim_covers_list_unsubscribe",
                "unsubscribe_dkim_covers_list_unsubscribe_post",
                "unsubscribe_auth_reason")
            .doesNotContain(
                "authentication_results", "dkim_signature", "raw_authentication",
                "unsubscribe_uri", "response_body", "provider_message_id_copy", "payload");
        assertThat(columns("unsubscribe_attempts")).containsExactlyInAnyOrder(
            "id", "account_id", "candidate_fingerprint", "sender_key_fingerprint",
            "method", "destination_host", "destination_fingerprint", "created_at",
            "completed_at", "latest_status", "http_status_class", "reason_code",
            "confirmation_source");
        assertThat(columns("unsubscribe_outcome_events")).containsExactlyInAnyOrder(
            "id", "account_id", "attempt_id", "status", "occurred_at", "reason_code",
            "http_status_class", "confirmation_source");
        assertThat(columns("unsubscribe_intent_nonces")).containsExactlyInAnyOrder(
            "id", "account_id", "nonce_fingerprint", "candidate_fingerprint",
            "destination_fingerprint", "method", "issued_at", "expires_at", "consumed_at");
        assertThat(indexes()).contains(
            "idx_message_metadata_unsubscribe_candidate",
            "idx_message_metadata_unsubscribe_list_source",
            "idx_unsubscribe_attempts_account_status_created",
            "idx_unsubscribe_outcome_events_attempt_time",
            "idx_unsubscribe_intent_nonces_account_expiry");

        UserAccount account = account("schema@example.com");
        MessageMetadata row = seed(account, "schema-message", Instant.now(), "schema-list",
            "<https://schema.example/u>", false);
        assertThat(row.getUnsubscribeDkimPass()).isFalse();
        assertThat(row.getUnsubscribeDkimCoversListUnsubscribe()).isFalse();
        assertThat(row.getUnsubscribeDkimCoversListUnsubscribePost()).isFalse();
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE message_metadata
            SET unsubscribe_auth_reason = 'RAW_SECRET_REASON'
            WHERE id = ?
            """, row.getId())).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void queryGroupsNewestSourceCountsThirtyDaysAndEnforcesProtectionAndOwnership() {
        UserAccount owner = account("owner@example.com");
        UserAccount foreign = account("foreign@example.com");
        Instant now = Instant.now();
        seed(owner, "old", now.minus(40, ChronoUnit.DAYS), "offers.example",
            "<https://old.example/u?token=OLD_CANARY>", false);
        seed(owner, "new", now.minus(1, ChronoUnit.DAYS), "offers.example",
            "<https://new.example/u?token=NEW_CANARY>, <mailto:leave@example.com>", true);
        seed(owner, "recent", now.minus(2, ChronoUnit.DAYS), "offers.example",
            "<https://new.example/u?token=OTHER_CANARY>", false);
        seed(foreign, "foreign-private-id", now, "foreign.example",
            "<https://foreign.example/u?token=FOREIGN_CANARY>", true);

        DecisionPage<UnsubscribeCandidateRow> page = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of()));
        assertThat(page.filteredCount()).isEqualTo(1);
        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(row.estimatedThirtyDayMessages()).isEqualTo(3);
            assertThat(row.methodLabels()).containsExactly(
                "RFC 8058 POST", "Legacy HTTPS GET", "Mailto", "Manual/account page");
            assertThat(row.confidence()).isEqualTo("REVIEW");
            assertThat(row.needsReview()).isTrue();
            assertThat(row.senderAddress()).isEqualTo("news@example.com");
            assertThat(row.toString()).doesNotContain(
                "NEW_CANARY", "foreign-private-id", "https://");
        });
        UnsubscribeInspection inspection = candidates.inspect(
            List.of(owner.getId()), page.rows().getFirst().candidateKey());
        assertThat(inspection.methods()).extracting(UnsubscribeInspection.MethodFact::destinationHost)
            .contains("new.example", "leave@example.com", "mail.google.com");
        assertThat(inspection.toString()).doesNotContain(
            "NEW_CANARY", "foreign-private-id", "https://");

        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source)
            VALUES (?, 'news@example.com', 'USER')
            """, owner.getId());
        assertThat(candidates.query(List.of(owner.getId()), UnsubscribeQuery.parse(Map.of()))
            .filteredCount()).isZero();
        UnsubscribeQuery included = UnsubscribeQuery.parse(
            Map.of("protected", List.of("include")));
        DecisionPage<UnsubscribeCandidateRow> protectedPage = candidates.query(
            List.of(owner.getId()), included);
        UnsubscribeCandidateRow protectedRow = protectedPage.rows().getFirst();
        assertThat(protectedRow.protectedSender()).isTrue();
        assertThat(protectedRow.confidence()).isEqualTo("REVIEW");
        assertThat(protectedPage.metrics().primaryCount()).isZero();
        assertThat(protectedPage.metrics().protectedCount()).isZero();
        assertThat(candidates.inspect(List.of(owner.getId()), protectedRow.candidateKey())
            .methods()).allMatch(method -> !method.eligible());

        assertThatThrownBy(() -> candidates.inspect(
            List.of(foreign.getId()), protectedRow.candidateKey()))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void equalTimestampGroupsSelectHighestMetadataIdDeterministically() {
        UserAccount owner = account("tie-owner@example.com");
        Instant received = Instant.now();
        MessageMetadata olderId = seed(
            owner, "tie-first", received, "tie.example",
            "<https://first.example/u>", false);
        MessageMetadata newerId = seed(
            owner, "tie-second", received, "tie.example",
            "<https://second.example/u>", false);
        assertThat(newerId.getId()).isGreaterThan(olderId.getId());

        DecisionPage<UnsubscribeCandidateRow> first = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of()));
        DecisionPage<UnsubscribeCandidateRow> repeated = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of()));
        assertThat(repeated.rows()).isEqualTo(first.rows());
        assertThat(first.rows()).singleElement().satisfies(row -> {
            assertThat(row.estimatedThirtyDayMessages()).isEqualTo(2);
            assertThat(row.candidateKey()).doesNotContain(
                "tie-first", "tie-second", "news@example.com", "tie.example");
        });
        assertThat(candidates.inspect(
            List.of(owner.getId()), first.rows().getFirst().candidateKey()).methods())
            .extracting(UnsubscribeInspection.MethodFact::destinationHost)
            .contains("second.example", "mail.google.com");
    }

    @Test
    void newestUnsubscribeSourceIgnoresNewerOrdinaryMailWithoutCreatingAConflict() {
        UserAccount owner = account("source-owner@example.com");
        Instant now = Instant.now();
        MessageMetadata unsubscribeSource = seed(
            owner, "unsubscribe-source", now.minus(2, ChronoUnit.DAYS), "source.list",
            "<https://source.example/u?token=PRIVATE>", false);
        MessageMetadata ordinary = seed(
            owner, "ordinary-newer", now.minus(1, ChronoUnit.DAYS), "source.list",
            "<https://must-not-be-used.example/u>", false);
        ordinary.setListUnsubscribeValues(List.of());
        ordinary.setListUnsubscribePostValues(List.of());
        metadata.save(ordinary);

        UnsubscribeCandidateRow row = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of())).rows().getFirst();
        assertThat(row.sourceMetadataId()).isEqualTo(unsubscribeSource.getId());
        assertThat(row.estimatedThirtyDayMessages()).isEqualTo(2);
        assertThat(row.lastReceivedAt()).isEqualTo(ordinary.getReceivedAt());
        assertThat(row.confidence()).isEqualTo("HIGH");
        assertThat(row.needsReview()).isFalse();
        assertThat(candidates.inspect(List.of(owner.getId()), row.candidateKey()).methods())
            .extracting(UnsubscribeInspection.MethodFact::destinationHost)
            .contains("source.example")
            .doesNotContain("must-not-be-used.example");
    }

    @Test
    void ambiguousHeadersNeedReviewAndMethodsUseSafetyPreferenceOrder() {
        UserAccount owner = account("ambiguous-owner@example.com");
        MessageMetadata row = seed(
            owner, "ambiguous", Instant.now(), "first.list",
            "<mailto:leave@example.com>, <https://second.example/u>, "
                + "<https://first.example/u>",
            false);
        row.setListIdValues(List.of("first.list", "second.list"));
        row.setListUnsubscribePostValues(List.of("List-Unsubscribe=invalid"));
        metadata.save(row);

        UnsubscribeCandidateRow candidate = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of())).rows().getFirst();
        assertThat(candidate.confidence()).isEqualTo("REVIEW");
        assertThat(candidate.needsReview()).isTrue();
        assertThat(candidate.reason()).contains("malformed, ambiguous");
        assertThat(candidate.methodLabels()).containsExactly(
            "RFC 8058 POST", "Legacy HTTPS GET", "Mailto", "Manual/account page");
        assertThat(candidates.inspect(List.of(owner.getId()), candidate.candidateKey()).methods())
            .extracting(UnsubscribeInspection.MethodFact::label)
            .containsExactly(
                "RFC 8058 POST", "Legacy HTTPS GET", "Legacy HTTPS GET",
                "Mailto", "Manual/account page");
    }

    @Test
    void everyFilterHasPositiveAndNearMatchExclusionAndOutcomeIsIdentityNeutral() {
        UserAccount owner = account("filters@example.com");
        Instant now = Instant.now();
        seed(owner, "rfc", now.minus(2, ChronoUnit.DAYS),
            "rfc.list", "<https://rfc.example/u>", true);
        seed(owner, "legacy", now.minus(20, ChronoUnit.DAYS), "legacy.list",
            "legacy@example.com", "Legacy", "<https://legacy.example/u>", List.of(), false);
        seed(owner, "mailto", now.minus(100, ChronoUnit.DAYS), "mailto.list",
            "mailto@example.com", "Mail", "<mailto:leave@example.com?subject=stop>",
            List.of(), false);
        seed(owner, "manual", now.minus(1, ChronoUnit.DAYS), "manual.list",
            "manual@example.com", "Manual", "<ftp://manual.example/u>", List.of(), false);
        for (int index = 0; index < 11; index++) {
            seed(owner, "rfc-volume-" + index, now.minus(index, ChronoUnit.HOURS),
                "rfc.list", "<https://rfc.example/u>", true);
        }
        jdbc.update("""
            INSERT INTO protected_senders(account_id, sender_address, source)
            VALUES (?, 'manual@example.com', 'USER')
            """, owner.getId());

        assertOnlySender(owner, Map.of("q", List.of("Legacy")), "legacy@example.com");

        UnsubscribeCandidateRow beforeOutcome = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of(
                "q", List.of("news@example.com")))).rows().getFirst();
        String fingerprint = UnsubscribeCandidateService.candidateFingerprint(
            owner.getId(), "news@example.com", "rfc.list");
        jdbc.update("""
            INSERT INTO unsubscribe_attempts(
                account_id, candidate_fingerprint, sender_key_fingerprint,
                method, latest_status, created_at
            ) VALUES (?, ?, ?, 'RFC8058_POST', 'VERIFICATION_PENDING', ?)
            """, owner.getId(), fingerprint, "a".repeat(64),
            java.sql.Timestamp.from(now));
        DecisionPage<UnsubscribeCandidateRow> outcome = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(Map.of(
                "q", List.of("news@example.com"))));
        assertThat(outcome.rows()).singleElement().satisfies(row -> {
            assertThat(row.latestOutcome()).isEqualTo("VERIFICATION_PENDING");
            assertThat(row.candidateKey()).isEqualTo(beforeOutcome.candidateKey());
        });
    }

    @Test
    void allSortsAreStableAcrossTwoFixedPagesWithCompleteTieBreakers() {
        UserAccount owner = account("pages@example.com");
        Instant received = Instant.now().minus(5, ChronoUnit.DAYS);
        for (int index = 0; index < 55; index++) {
            String sender = "sender%02d@example.com".formatted(index);
            seed(owner, "page-" + index, received, "list-%02d".formatted(index),
                sender, "Sender " + index, "<https://page.example/u>", List.of(), false);
        }
        for (UnsubscribeQuery.Sort sort : UnsubscribeQuery.Sort.values()) {
            UnsubscribeQuery firstQuery = UnsubscribeQuery.parse(Map.of(
                "sort", List.of(sort.value()), "page", List.of("0")));
            UnsubscribeQuery secondQuery = firstQuery.withPage(1);
            List<String> first = candidates.query(List.of(owner.getId()), firstQuery).rows()
                .stream().map(UnsubscribeCandidateRow::candidateKey).toList();
            List<String> repeated = candidates.query(List.of(owner.getId()), firstQuery).rows()
                .stream().map(UnsubscribeCandidateRow::candidateKey).toList();
            List<String> second = candidates.query(List.of(owner.getId()), secondQuery).rows()
                .stream().map(UnsubscribeCandidateRow::candidateKey).toList();
            assertThat(first).hasSize(25).isEqualTo(repeated);
            assertThat(second).hasSize(25);
            assertThat(first).doesNotContainAnyElementsOf(second);
            assertThat(Set.copyOf(first)).hasSize(25);
            assertThat(Set.copyOf(second)).hasSize(25);
        }
    }

    @Test
    void pageSourceFetchRejectsOversizedOrForeignIdentitiesAndExcludesOffPageSenders() {
        UserAccount owner = account("page-source-owner@example.com");
        UserAccount foreign = account("page-source-foreign@example.com");
        Instant now = Instant.now();
        seed(owner, "visible-source", now, "visible.list",
            "visible@example.com", "Visible",
            "<https://visible.example/u>", List.of(), false);
        seed(owner, "off-page-source", now, "hidden.list",
            "hidden@example.com", "Hidden",
            "<https://hidden.example/u>", List.of(), false);
        seed(foreign, "foreign-source", now, "foreign.list",
            "foreign@example.com", "Foreign",
            "<https://foreign.example/u>", List.of(), false);

        List<UnsubscribeCandidateRepository.SourceCandidate> page =
            candidateRepository.findCandidatesForSenders(
                List.of(owner.getId()),
                List.of(new UnsubscribeCandidateRepository.SenderIdentity(
                    owner.getId(), "visible@example.com")),
                now);
        assertThat(page).singleElement().satisfies(source -> {
            assertThat(source.accountId()).isEqualTo(owner.getId());
            assertThat(source.senderKey()).isEqualTo("visible@example.com");
        });
        assertThat(page).noneMatch(source ->
            source.senderKey().equals("hidden@example.com")
                || source.senderKey().equals("foreign@example.com"));

        assertThatThrownBy(() -> candidateRepository.findCandidatesForSenders(
            List.of(owner.getId()),
            List.of(new UnsubscribeCandidateRepository.SenderIdentity(
                foreign.getId(), "foreign@example.com")),
            now)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        List<UnsubscribeCandidateRepository.SenderIdentity> oversized =
            java.util.stream.LongStream.rangeClosed(1, 26)
                .mapToObj(index -> new UnsubscribeCandidateRepository.SenderIdentity(
                    owner.getId(), "sender" + index + "@example.com"))
                .toList();
        assertThatThrownBy(() -> candidateRepository.findCandidatesForSenders(
            List.of(owner.getId()), oversized, now))
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidateRepositoryAndServiceHaveNoMailboxProviderDependency() {
        for (Class<?> type : List.of(
                UnsubscribeCandidateRepository.class, UnsubscribeCandidateService.class)) {
            assertThat(type.getDeclaredFields())
                .noneMatch(field -> MailProvider.class.isAssignableFrom(field.getType()));
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                    .noneMatch(MailProvider.class::isAssignableFrom);
            }
        }
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """, String.class, table);
    }

    private List<String> indexes() {
        return jdbc.queryForList("""
            SELECT DISTINCT indexname FROM pg_indexes
            WHERE schemaname = 'public'
            """, String.class).stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
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

    private MessageMetadata seed(
            UserAccount account,
            String providerId,
            Instant received,
            String listIdentity,
            String destination,
            boolean trusted) {
        return seed(account, providerId, received, listIdentity,
            "news@example.com", "Example News", destination,
            List.of("List-Unsubscribe=One-Click"), trusted);
    }

    private MessageMetadata seed(
            UserAccount account,
            String providerId,
            Instant received,
            String listIdentity,
            String sender,
            String displayName,
            String destination,
            List<String> post,
            boolean trusted) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setProviderThreadId("thread-" + providerId);
        row.setFromAddress(sender);
        row.setFromDisplayName(displayName);
        row.setFromDomain("example.com");
        row.setReceivedAt(received);
        row.setListIdValues(List.of(listIdentity));
        row.setListUnsubscribeValues(List.of(destination));
        row.setListUnsubscribePostValues(post);
        row.setUnsubscribeDkimPass(trusted);
        row.setUnsubscribeDkimCoversListUnsubscribe(trusted);
        row.setUnsubscribeDkimCoversListUnsubscribePost(trusted);
        row.setUnsubscribeAuthReason(trusted ? "TRUSTED_PASS" : "NO_TRUSTED_RESULT");
        return metadata.save(row);
    }

    private void assertOnlySender(
            UserAccount owner,
            Map<String, List<String>> parameters,
            String expectedSender) {
        DecisionPage<UnsubscribeCandidateRow> page = candidates.query(
            List.of(owner.getId()), UnsubscribeQuery.parse(parameters));
        assertThat(page.rows()).singleElement()
            .extracting(UnsubscribeCandidateRow::senderAddress)
            .isEqualTo(expectedSender);
    }
}
