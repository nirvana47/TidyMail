package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tidymail.dto.AnalyticsSourcePage.SourceRow;
import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsQuery.AnalyticsAction;
import com.tidymail.dto.AnalyticsQuery.BucketGrouping;
import com.tidymail.dto.AnalyticsQuery.Comparison;
import com.tidymail.dto.AnalyticsQuery.DateRange;
import com.tidymail.dto.AnalyticsSourceQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailProvider;
import com.tidymail.repository.AnalyticsReceivedRepository;
import com.tidymail.repository.AnalyticsCoverageRepository;
import com.tidymail.repository.AnalyticsLedgerRepository;
import com.tidymail.repository.AnalyticsLedgerRepository.LedgerSnapshot;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.AnalyticsService;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
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
class Phase13AnalyticsRepositoryTest {
    @Autowired UserAccountRepository accounts;
    @Autowired MessageMetadataRepository metadata;
    @Autowired AnalyticsReceivedRepository received;
    @Autowired AnalyticsLedgerRepository ledger;
    @Autowired AnalyticsCoverageRepository coverage;
    @Autowired AnalyticsService analyticsService;
    @Autowired JdbcTemplate jdbc;

    @MockBean(name = "gmailMailService") MailProvider gmail;
    @MockBean(name = "outlookMailService") MailProvider outlook;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_actions");
        jdbc.update("DELETE FROM action_scopes");
        metadata.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void ownedHalfOpenPredicateExcludesForeignNullAndEndBoundaryRows() {
        UserAccount first = account("first@example.com");
        UserAccount second = account("second@example.com");
        UserAccount foreign = account("foreign@example.com");
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-02T00:00:00Z");
        message(first, "start", start, "First");
        message(second, "inside", end.minusMillis(1), "Second");
        message(first, "end", end, "End");
        message(first, "null", null, "No time");
        message(foreign, "foreign", start.plusSeconds(1), "Foreign");

        var fact = received.receivedFact(List.of(first.getId(), second.getId()), start, end);

        assertThat(fact.count()).isEqualTo(2);
        assertThat(fact.page(25, 0)).extracting(SourceRow::senderDisplay)
            .containsExactly("First", "Second");
        assertThat(received.nullReceivedCount(List.of(first.getId(), second.getId())))
            .isEqualTo(1);
        assertThat(received.earliestReceivedByAccount(List.of(first.getId(), second.getId())))
            .containsEntry(first.getId(), start)
            .containsEntry(second.getId(), end.minusMillis(1))
            .doesNotContainKey(foreign.getId());
    }

    @Test
    void aggregateCountEqualsStablePageUnionAndFullStream() {
        UserAccount first = account("one@example.com");
        UserAccount second = account("two@example.com");
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        for (int index = 0; index < 61; index++) {
            UserAccount account = index % 2 == 0 ? first : second;
            message(account, "id-" + index, start.plusSeconds(index / 3),
                String.format(Locale.ROOT, "Sender %02d", index));
        }
        var fact = received.receivedFact(
            List.of(second.getId(), first.getId()), start, start.plusSeconds(100));

        List<SourceRow> pages = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            pages.addAll(fact.page(25, page * 25L));
        }
        List<SourceRow> streamed;
        try (var stream = fact.stream()) {
            streamed = stream.toList();
        }

        assertThat(fact.count()).isEqualTo(61);
        assertThat(pages).hasSize(61).containsExactlyElementsOf(streamed);
        assertThat(fact.page(25, 0)).containsExactlyElementsOf(streamed.subList(0, 25));
    }

    @Test
    void groupedBucketCountsPreserveHalfOpenEqualityAtMaximumRangeShape() {
        UserAccount owned = account("bucketed@example.com");
        Instant start = Instant.parse("2025-08-10T00:00:00Z");
        List<AnalyticsQuery.DateRange> buckets = IntStream
            .range(0, AnalyticsQuery.MAX_DATES)
            .mapToObj(index -> new AnalyticsQuery.DateRange(
                start.plusSeconds(index * 86_400L),
                start.plusSeconds((index + 1L) * 86_400L)))
            .toList();
        message(owned, "first", buckets.getFirst().startInclusive(), "First");
        message(owned, "second", buckets.get(200).startInclusive(), "Second");
        message(owned, "end", buckets.getLast().endExclusive(), "End");

        List<Long> counts = received.countByBuckets(List.of(owned.getId()), buckets);

        assertThat(counts).hasSize(AnalyticsQuery.MAX_DATES);
        assertThat(counts.getFirst()).isEqualTo(1);
        assertThat(counts.get(200)).isEqualTo(1);
        assertThat(counts.stream().mapToLong(Long::longValue).sum())
            .isEqualTo(received.receivedFact(
                List.of(owned.getId()),
                buckets.getFirst().startInclusive(),
                buckets.getLast().endExclusive()).count())
            .isEqualTo(2);
    }

    @Test
    void safeProjectionHasNoPrivateIdentifierOrContentFields() {
        assertThat(Arrays.stream(SourceRow.class.getRecordComponents())
            .map(RecordComponent::getName)
            .map(name -> name.toLowerCase(Locale.ROOT)))
            .noneMatch(name -> name.equals("id")
                || name.contains("messageid")
                || name.contains("threadid")
                || name.contains("accountid")
                || name.contains("actionid")
                || name.contains("subject")
                || name.contains("header")
                || name.contains("scope")
                || name.contains("fingerprint")
                || name.contains("token")
                || name.contains("error")
                || name.contains("body")
                || name.contains("attachment"));
    }

    @Test
    void ledgerFactsPreserveFirstDecisionGrossSuccessUndoAndConfirmedSemantics() {
        UserAccount first = account("ledger-one@example.com");
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-02T00:00:00Z");
        message(first, "archive-message", start.minusSeconds(60), "Shared");
        message(first, "unsubscribe-message", start.minusSeconds(30), "List");

        long archiveAction = action(first, "archive-message", "ARCHIVE", "STAGED", start);
        long archiveExecution =
            execution(first, archiveAction, "FORWARD", start.plusSeconds(10));
        target(first, archiveExecution, scopeId(archiveAction), "SUCCEEDED",
            start.plusSeconds(20), null, true);
        event(first, archiveAction, archiveExecution, "EXECUTION_RESERVED", "ARCHIVE",
            "STAGED", start.plusSeconds(10));

        long duplicateAction =
              action(first, "archive-message", "MARK_READ", "IMMEDIATE",
                  start.plusSeconds(30));

        long undoExecution = execution(first, archiveAction, "UNDO", start.plusSeconds(40));
          target(first, undoExecution, scopeId(archiveAction), "REVERTED",
            start.plusSeconds(50), null, true);
          long markReadUndo =
              execution(first, duplicateAction, "UNDO", start.plusSeconds(51));
          target(first, markReadUndo, scopeId(duplicateAction), "REVERTED",
              start.plusSeconds(52), null, true);

        long unsubscribeAction = action(
            first, "unsubscribe-message", "UNSUBSCRIBE", "IMMEDIATE",
            start.plusSeconds(60));
        long unsubscribeExecution =
            execution(first, unsubscribeAction, "FORWARD", start.plusSeconds(61));
        long attempt = attempt(first, start.plusSeconds(62));
        target(first, unsubscribeExecution, scopeId(unsubscribeAction),
            "VERIFICATION_PENDING", start.plusSeconds(70), attempt, true);
        event(first, unsubscribeAction, unsubscribeExecution, "EXECUTION_RESERVED",
            "UNSUBSCRIBE", "IMMEDIATE", start.plusSeconds(61));
        unsubscribeEvent(first, attempt, "ACCEPTED", start.plusSeconds(63));
        unsubscribeEvent(first, attempt, "VERIFICATION_PENDING", start.plusSeconds(64));
        unsubscribeEvent(first, attempt, "CONFIRMED", start.plusSeconds(80));

        LedgerSnapshot snapshot = ledger.snapshot(
            List.of(first.getId()), new DateRange(start, end), AnalyticsAction.ALL);
        var outcomes = ledger.outcomes(
            List.of(first.getId()), new DateRange(start, end), AnalyticsAction.ALL);
        var cohort = ledger.cohortCounts(
            List.of(first.getId()), new DateRange(start, end), AnalyticsAction.ALL);
        var unsubscribe = ledger.unsubscribeCounts(
            List.of(first.getId()), new DateRange(start, end));

        assertThat(snapshot.triaged()).isEqualTo(2);
        assertThat(snapshot.archived()).isEqualTo(1);
        assertThat(snapshot.trashed()).isZero();
        assertThat(snapshot.unsubscribed()).isEqualTo(1);
          assertThat(snapshot.unsubscribeAcceptedOrPending()).isEqualTo(1);
        assertThat(snapshot.inverseSucceeded()).isEqualTo(1);
        assertThat(outcomes.requested()).isEqualTo(2);
        assertThat(outcomes.succeeded()).isEqualTo(1);
        assertThat(outcomes.verificationPending()).isEqualTo(1);
        assertThat(outcomes.accepted()).isEqualTo(2);
        assertThat(cohort.triaged()).isEqualTo(2);
        assertThat(cohort.cleanedTriaged()).isEqualTo(2);
        assertThat(unsubscribe.confirmed()).isEqualTo(1);
        assertThat(unsubscribe.eligible()).isEqualTo(1);
          assertThat(ledger.detailCount(
              List.of(first.getId()), start, end, AnalyticsAction.ALL,
              AnalyticsSourceQuery.SourceSeries.TARGET_UNSUB_CONFIRMED))
              .isEqualTo(snapshot.unsubscribed());
          assertThat(ledger.detailCount(
              List.of(first.getId()), start, end, AnalyticsAction.ALL,
              AnalyticsSourceQuery.SourceSeries.UNSUB_ACCEPTED_OR_PENDING))
              .isEqualTo(snapshot.unsubscribeAcceptedOrPending());
          assertThat(ledger.detailCount(
              List.of(first.getId()), start, end, AnalyticsAction.ALL,
              AnalyticsSourceQuery.SourceSeries.DELAY_SAMPLE))
              .isEqualTo(ledger.eligibleDelaySeconds(
                  List.of(first.getId()), new DateRange(start, end),
                  AnalyticsAction.ALL).size());
          long delayBuckets = List.of(
                  AnalyticsSourceQuery.SourceSeries.DELAY_UNDER_15M,
                  AnalyticsSourceQuery.SourceSeries.DELAY_15M_TO_1H,
                  AnalyticsSourceQuery.SourceSeries.DELAY_1H_TO_24H,
                  AnalyticsSourceQuery.SourceSeries.DELAY_24H_TO_7D,
                  AnalyticsSourceQuery.SourceSeries.DELAY_7D_OR_MORE)
              .stream()
              .mapToLong(series -> ledger.detailCount(
                  List.of(first.getId()), start, end, AnalyticsAction.ALL, series))
              .sum();
          assertThat(delayBuckets).isEqualTo(
              ledger.eligibleDelaySeconds(
                  List.of(first.getId()), new DateRange(start, end),
                  AnalyticsAction.ALL).size());
          assertThat(ledger.detailCount(
              List.of(first.getId()), start, end, AnalyticsAction.ALL,
              AnalyticsSourceQuery.SourceSeries.CLEANED_TRIAGED))
              .isEqualTo(cohort.cleanedTriaged());
          assertThat(ledger.detailCount(
              List.of(first.getId()), start, end, AnalyticsAction.ALL,
              AnalyticsSourceQuery.SourceSeries.OUTCOME_RETAINED))
              .isEqualTo(outcomes.retainedConfidence());
          assertThat(ledger.detailCount(
              List.of(first.getId()), start, end, AnalyticsAction.ALL,
              AnalyticsSourceQuery.SourceSeries.INVERSE_INBOX_REMOVAL))
              .isEqualTo(snapshot.inverseSucceeded());
    }

      @Test
      void headlineSourcesPreserveTargetUnitsDuplicatesAndActiveActionIntersection() {
          UserAccount account = account("headline-source@example.com");
          Instant start = Instant.parse("2026-08-04T00:00:00Z");
          Instant end = start.plusSeconds(86_400);
          message(account, "repeat", start.minusSeconds(20), "Repeat");
          message(account, "trash", start.minusSeconds(10), "Trash");

          successfulTarget(account, "repeat", "ARCHIVE", start.plusSeconds(1));
          successfulTarget(account, "repeat", "ARCHIVE", start.plusSeconds(10));
          successfulTarget(account, "trash", "TRASH", start.plusSeconds(20));

          assertHeadlineSourceEquality(
              account, start, end, AnalyticsAction.ALL, 2, 1);
          assertHeadlineSourceEquality(
              account, start, end, AnalyticsAction.ARCHIVE, 2, 0);
          assertHeadlineSourceEquality(
              account, start, end, AnalyticsAction.TRASH, 0, 1);
          assertHeadlineSourceEquality(
              account, start, end, AnalyticsAction.MARK_READ, 0, 0);
      }

    @Test
    void cleanedAggregateEqualsPageAndStreamAndTopSendersStayAccountLocal() {
        UserAccount first = account("sender-one@example.com");
        UserAccount second = account("sender-two@example.com");
        Instant start = Instant.parse("2026-08-03T00:00:00Z");
        Instant end = start.plusSeconds(86_400);
        message(first, "one", start.minusSeconds(10), "Same sender");
        message(second, "two", start.minusSeconds(10), "Same sender");
        long firstAction = action(first, "one", "ARCHIVE", "STAGED", start);
        long firstExecution = execution(first, firstAction, "FORWARD", start.plusSeconds(1));
        target(first, firstExecution, scopeId(firstAction), "SUCCEEDED",
            start.plusSeconds(2), null, true);
        long secondAction = action(second, "two", "MARK_READ", "STAGED", start);
        long secondExecution =
            execution(second, secondAction, "FORWARD", start.plusSeconds(1));
        target(second, secondExecution, scopeId(secondAction), "SUCCEEDED",
            start.plusSeconds(2), null, true);

        long count = ledger.cleanedCount(
            List.of(first.getId(), second.getId()), start, end, AnalyticsAction.ALL);
        var page = ledger.cleanedPage(
            List.of(first.getId(), second.getId()), start, end,
            AnalyticsAction.ALL, 25, 0);
        List<SourceRow> streamed;
        try (var rows = ledger.streamCleaned(
                List.of(first.getId(), second.getId()), start, end,
                AnalyticsAction.ALL)) {
            streamed = rows.toList();
        }

        assertThat(count).isEqualTo(2);
        assertThat(page).containsExactlyElementsOf(streamed);
        assertThat(page).allSatisfy(row -> {
            assertThat(row.action()).isIn("ARCHIVE", "MARK_READ");
            assertThat(row.outcome()).isEqualTo("SUCCEEDED");
        });
        assertThat(coverage.topSenders(
            List.of(first.getId(), second.getId()),
            new DateRange(start.minusSeconds(20), end), 5))
            .hasSize(2)
            .extracting(row -> row.accountLabel())
            .containsExactlyInAnyOrder(
                "sender-one@example.com (Gmail)",
                "sender-two@example.com (Gmail)");
    }

    @Test
    void cleanedPredicateFiltersIntervalAndActionBeforeMessageDedupe() {
        UserAccount account = account("repeat-cleanup@example.com");
        Instant firstStart = Instant.parse("2026-08-03T00:00:00Z");
        Instant secondStart = firstStart.plusSeconds(86_400);
        Instant end = secondStart.plusSeconds(86_400);
        message(account, "repeat", firstStart.minusSeconds(10), "Repeat sender");

        long archive = action(account, "repeat", "ARCHIVE", "STAGED", firstStart);
        long archiveExecution = execution(account, archive, "FORWARD", firstStart);
        target(account, archiveExecution, scopeId(archive), "SUCCEEDED",
            firstStart.plusSeconds(1), null, true);
        long trash = action(account, "repeat", "TRASH", "STAGED", secondStart);
        long trashExecution = execution(account, trash, "FORWARD", secondStart);
        target(account, trashExecution, scopeId(trash), "SUCCEEDED",
            secondStart.plusSeconds(1), null, true);

        assertThat(ledger.cleanedCount(
            List.of(account.getId()), firstStart, secondStart, AnalyticsAction.ALL))
            .isEqualTo(1);
        assertThat(ledger.cleanedCount(
            List.of(account.getId()), secondStart, end, AnalyticsAction.TRASH))
            .isEqualTo(1);
        assertThat(ledger.cleanedCountByBuckets(
            List.of(account.getId()),
            List.of(new DateRange(firstStart, secondStart), new DateRange(secondStart, end)),
            AnalyticsAction.ALL))
            .extracting(AnalyticsLedgerRepository.CleanedBucketCount::count)
            .containsExactly(1L, 1L);
    }

    @Test
    void cleanedAggregateAndSourcesShareTimestampedMetadataPredicate() {
        UserAccount account = account("metadata-gap@example.com");
        Instant start = Instant.parse("2026-08-05T00:00:00Z");
        Instant end = start.plusSeconds(86_400);
        message(account, "null-time", null, "Null time");
        long nullAction = action(account, "null-time", "ARCHIVE", "STAGED", start);
        long nullExecution = execution(account, nullAction, "FORWARD", start);
        target(account, nullExecution, scopeId(nullAction), "SUCCEEDED",
            start.plusSeconds(1), null, true);
        long missingAction = action(account, "missing", "TRASH", "STAGED", start);
        long missingExecution = execution(account, missingAction, "FORWARD", start);
        target(account, missingExecution, scopeId(missingAction), "SUCCEEDED",
            start.plusSeconds(2), null, true);

        assertThat(ledger.cleanedCount(
            List.of(account.getId()), start, end, AnalyticsAction.ALL)).isZero();
        assertThat(ledger.cleanedPage(
            List.of(account.getId()), start, end, AnalyticsAction.ALL, 25, 0)).isEmpty();
        try (var rows = ledger.streamCleaned(
                List.of(account.getId()), start, end, AnalyticsAction.ALL)) {
            assertThat(rows).isEmpty();
        }
        assertThat(ledger.cleanedMetadataExclusionCount(
            List.of(account.getId()), start, end, AnalyticsAction.ALL)).isEqualTo(2);

        AnalyticsQuery query = new AnalyticsQuery(
            LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-05"),
            account.getId().toString(), AnalyticsAction.ALL, BucketGrouping.DAY,
            Comparison.NONE);
        AnalyticsSourceQuery source = new AnalyticsSourceQuery(
            query, AnalyticsSourceQuery.SourceSeries.CLEANED, start, end, 0);
        WorkspacePageModel workspace = new WorkspacePageModel(
            WorkspaceRoute.ANALYTICS, "Owner", account.getEmail(), List.of(),
            List.of(account.getId()), account.getId().toString(), account.getEmail(),
            0L, UnreadScopeCounts.available(0, 0),
            1, 1, 1, "Ready", end, WorkspaceState.READY, false, false, false);
        assertThat(analyticsService.sourceAvailability(workspace, source))
            .extracting(AnalyticsAvailability::state, AnalyticsAvailability::reason,
                AnalyticsAvailability::excludedCount)
            .containsExactly(
                AnalyticsAvailability.State.PARTIAL,
                AnalyticsAvailability.Reason.CLEANED_METADATA_GAP,
                2L);
    }

    @Test
    void legacyGapUsesRetainedOutcomeTimeAndUnknownTimesRemainConservative() {
        UserAccount account = account("legacy-gap@example.com");
        Instant start = Instant.parse("2026-08-06T00:00:00Z");
        Instant end = start.plusSeconds(86_400);
        long retained = action(
            account, "retained", "ARCHIVE", "STAGED", start.minusSeconds(86_400));
        jdbc.update("DELETE FROM action_events WHERE action_id=?", retained);
        jdbc.update("""
            UPDATE user_actions
               SET decision_source='LEGACY', created_at=?, applied_at=?
             WHERE id=?
            """, timestamp(start.minusSeconds(86_400)), timestamp(start.plusSeconds(10)), retained);
        long unknown = action(
            account, "unknown", "TRASH", "STAGED", start.minusSeconds(172_800));
        jdbc.update("DELETE FROM action_events WHERE action_id=?", unknown);
        jdbc.update("""
            UPDATE user_actions
               SET decision_source='LEGACY', created_at=?, applied_at=NULL,
                   completed_at=NULL, undone_at=NULL
             WHERE id=?
            """, timestamp(start.minusSeconds(172_800)), unknown);
        long laterUndo = action(
            account, "later-undo", "ARCHIVE", "STAGED", start.minusSeconds(172_800));
        jdbc.update("DELETE FROM action_events WHERE action_id=?", laterUndo);
        jdbc.update("""
            UPDATE user_actions
               SET decision_source='LEGACY', created_at=?, applied_at=?, undone_at=?
             WHERE id=?
            """, timestamp(start.minusSeconds(172_800)),
            timestamp(start.minusSeconds(86_400)), timestamp(end.plusSeconds(10)), laterUndo);

        assertThat(coverage.legacyGapCount(
            List.of(account.getId()), new DateRange(start, end), AnalyticsAction.ALL))
            .isEqualTo(2);
        assertThat(coverage.legacyGapCount(
            List.of(account.getId()), new DateRange(end, end.plusSeconds(86_400)),
            AnalyticsAction.ALL)).isEqualTo(2);
    }

    @Test
    void v10IsAdditiveAndFrozenV9HashesRemainExact() throws Exception {
        assertThat(sha256(Path.of(
            "src/main/resources/db/migration/V9__action_execution_history_and_undo.sql")))
            .isEqualTo("c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379");
        assertThat(jdbc.queryForList("""
            SELECT indexname FROM pg_indexes
             WHERE schemaname='public'
            """, String.class)).contains(
                "idx_action_events_analytics_fact",
                "idx_action_executions_analytics_fact",
                "idx_unsubscribe_outcome_events_analytics_fact",
                "idx_action_execution_targets_execution_status",
                "idx_message_metadata_account_received");
        assertThat(Files.readString(Path.of(
            "src/main/resources/db/migration/V10__analytics_fact_indexes.sql")))
            .doesNotContain("CREATE TABLE", "ALTER TABLE", "provider_message_id", "JSON");
    }

    private long action(
            UserAccount account,
            String messageId,
            String actionType,
            String mode,
            Instant occurredAt) {
        jdbc.update("""
            INSERT INTO action_scopes(
                account_id,action_type,target_kind,message_ids,scope_hash,
                message_count,target_summary,risk_summary,created_at
            ) VALUES (?,?,'ANALYTICS_TEST',ARRAY[CAST(? AS VARCHAR)],?,1,'{}','{}',?)
            """, account.getId(), actionType, messageId,
            hash(account.getId() + actionType + messageId + occurredAt),
            java.sql.Timestamp.from(occurredAt));
        long scopeId = jdbc.queryForObject(
            "SELECT MAX(id) FROM action_scopes WHERE account_id=?",
            Long.class, account.getId());
        jdbc.update("""
            INSERT INTO action_scope_targets(
                scope_id,account_id,scope_ordinal,target_fingerprint
            ) VALUES (?,?,0,?)
            """, scopeId, account.getId(), hash(messageId));
        jdbc.update("""
            INSERT INTO user_actions(
                account_id,action_scope_id,action_type,message_ids,
                inverse_action_data,target_kind,target_id_json,affected_count,
                status,execution_mode,decision_source,created_at
            ) VALUES (?,?,?,ARRAY[CAST(? AS VARCHAR)],'{}','ANALYTICS_TEST','{}',1,
                      'QUEUED',?,'UNREAD',?)
            """, account.getId(), scopeId, actionType, messageId, mode,
            java.sql.Timestamp.from(occurredAt));
        long actionId = jdbc.queryForObject(
            "SELECT MAX(id) FROM user_actions WHERE account_id=?",
            Long.class, account.getId());
        event(account, actionId, null, "ACTION_QUEUED", actionType, mode, occurredAt);
        return actionId;
    }

      private void successfulTarget(
              UserAccount account, String messageId, String actionType, Instant completedAt) {
          long action = action(
              account, messageId, actionType, "STAGED", completedAt.minusSeconds(2));
          long execution =
              execution(account, action, "FORWARD", completedAt.minusSeconds(1));
          target(
              account, execution, scopeId(action), "SUCCEEDED", completedAt, null, true);
          event(
              account, action, execution, "EXECUTION_RESERVED", actionType, "STAGED",
              completedAt.minusSeconds(1));
      }

      @Test
      void unsubscribeSourceAndExportAvailabilityShareLinkageGapState() {
          UserAccount account = account("linkage-gap@example.com");
          Instant start = Instant.parse("2026-08-05T00:00:00Z");
          Instant end = start.plusSeconds(86_400);
          long baseline = action(
              account, "baseline", "UNSUBSCRIBE", "IMMEDIATE", start.minusSeconds(20));
          long baselineExecution =
              execution(account, baseline, "FORWARD", start.minusSeconds(19));
          target(
              account, baselineExecution, scopeId(baseline), "FAILED",
              start.minusSeconds(18), null, false);
          event(
              account, baseline, baselineExecution, "EXECUTION_RESERVED", "UNSUBSCRIBE",
              "IMMEDIATE", start.minusSeconds(19));
          message(account, "unlinked", start.minusSeconds(10), "Unlinked");
          long action = action(
              account, "unlinked", "UNSUBSCRIBE", "IMMEDIATE", start.plusSeconds(1));
          long execution =
              execution(account, action, "FORWARD", start.plusSeconds(2));
          target(
              account, execution, scopeId(action), "VERIFICATION_PENDING",
              start.plusSeconds(3), null, true);
          event(
              account, action, execution, "EXECUTION_RESERVED", "UNSUBSCRIBE",
              "IMMEDIATE", start.plusSeconds(2));

          AnalyticsQuery query = new AnalyticsQuery(
              LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-05"),
              account.getId().toString(), AnalyticsAction.UNSUBSCRIBE,
              BucketGrouping.DAY, Comparison.NONE);
          AnalyticsSourceQuery source = new AnalyticsSourceQuery(
              query, AnalyticsSourceQuery.SourceSeries.TARGET_UNSUB_CONFIRMED,
              start, end, 0);
          WorkspacePageModel workspace = new WorkspacePageModel(
              WorkspaceRoute.ANALYTICS, "Owner", account.getEmail(), List.of(),
              List.of(account.getId()), account.getId().toString(), account.getEmail(),
              0L, UnreadScopeCounts.available(0, 0),
              1, 1, 1, "Ready", end, WorkspaceState.READY, false, false, false);

          AnalyticsAvailability availability =
              analyticsService.sourceAvailability(workspace, source);
          assertThat(availability.state()).isEqualTo(AnalyticsAvailability.State.PARTIAL);
          assertThat(availability.reason())
              .isEqualTo(AnalyticsAvailability.Reason.UNSUBSCRIBE_LINKAGE_GAP);
          assertThat(analyticsService.source(workspace, source).availability())
              .isEqualTo(availability);
      }

      private void assertHeadlineSourceEquality(
              UserAccount account,
              Instant start,
              Instant end,
              AnalyticsAction action,
              long archived,
              long trashed) {
          LedgerSnapshot snapshot =
              ledger.snapshot(List.of(account.getId()), new DateRange(start, end), action);
          long archiveSource = ledger.detailCount(
              List.of(account.getId()), start, end, action,
              AnalyticsSourceQuery.SourceSeries.TARGET_ARCHIVED);
          long trashSource = ledger.detailCount(
              List.of(account.getId()), start, end, action,
              AnalyticsSourceQuery.SourceSeries.TARGET_TRASHED);

          assertThat(snapshot.archived()).as(action + " aggregate archived")
              .isEqualTo(archived);
          assertThat(archiveSource).as(action + " source archived")
              .isEqualTo(archived);
          assertThat(snapshot.trashed()).as(action + " aggregate trashed")
              .isEqualTo(trashed);
          assertThat(trashSource).as(action + " source trashed")
              .isEqualTo(trashed);
          assertThat(ledger.detailPage(
              List.of(account.getId()), start, end, action,
              AnalyticsSourceQuery.SourceSeries.TARGET_ARCHIVED, 25, 0))
              .hasSize(Math.toIntExact(archiveSource));
          try (var rows = ledger.streamDetail(
                  List.of(account.getId()), start, end, action,
                  AnalyticsSourceQuery.SourceSeries.TARGET_ARCHIVED)) {
              assertThat(rows).hasSize(Math.toIntExact(archiveSource));
          }
      }

    private long scopeId(long actionId) {
        return jdbc.queryForObject(
            "SELECT action_scope_id FROM user_actions WHERE id=?",
            Long.class, actionId);
    }

    private long execution(
            UserAccount account, long actionId, String operation, Instant reservedAt) {
        jdbc.update("""
            INSERT INTO action_executions(
                account_id,action_id,operation_kind,status,idempotency_fingerprint,
                requested_count,accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,reserved_at,
                completed_at
            ) VALUES (?,?,?,'APPLIED',?,1,0,1,0,0,0,?,?)
            """, account.getId(), actionId, operation,
            hash(actionId + operation), timestamp(reservedAt),
            timestamp(reservedAt.plusSeconds(1)));
        return jdbc.queryForObject(
            "SELECT id FROM action_executions WHERE action_id=? AND operation_kind=?",
            Long.class, actionId, operation);
    }

    private void target(
            UserAccount account,
            long executionId,
            long scopeId,
            String status,
            Instant completedAt,
            Long attemptId,
            boolean accepted) {
        String fingerprint = jdbc.queryForObject("""
            SELECT target_fingerprint FROM action_scope_targets
             WHERE scope_id=? AND scope_ordinal=0
            """, String.class, scopeId);
        jdbc.update("""
            INSERT INTO action_execution_targets(
                execution_id,account_id,scope_id,scope_ordinal,target_fingerprint,
                dispatch_state,status,accepted_at,dispatched_at,completed_at,
                unsubscribe_attempt_id
            ) VALUES (?,?,?,0,?,'DISPATCHED',?,?,?,?,?)
            """, executionId, account.getId(), scopeId, fingerprint,
            status, timestamp(accepted ? completedAt.minusSeconds(1) : null),
            timestamp(completedAt.minusSeconds(1)), timestamp(completedAt), attemptId);
    }

    private void event(
            UserAccount account,
            long actionId,
            Long executionId,
            String eventType,
            String actionType,
            String mode,
            Instant occurredAt) {
        jdbc.update("""
            INSERT INTO action_events(
                account_id,action_id,execution_id,event_type,action_type,
                execution_mode,decision_source,resulting_status,requested_count,
                accepted_count,succeeded_count,failed_count,
                verification_pending_count,not_dispatched_count,occurred_at
            ) VALUES (?,?,?,?,?,?,'UNREAD','QUEUED',1,0,0,0,0,1,?)
            """, account.getId(), actionId, executionId, eventType,
            actionType, mode, java.sql.Timestamp.from(occurredAt));
    }

    private long attempt(UserAccount account, Instant createdAt) {
        jdbc.update("""
            INSERT INTO unsubscribe_attempts(
                account_id,candidate_fingerprint,sender_key_fingerprint,
                method,created_at,latest_status
            ) VALUES (?,?,?,'RFC8058_POST',?,'VERIFICATION_PENDING')
            """, account.getId(), hash("candidate" + createdAt),
            hash("sender" + createdAt), java.sql.Timestamp.from(createdAt));
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM unsubscribe_attempts WHERE account_id=?",
            Long.class, account.getId());
    }

    private void unsubscribeEvent(
            UserAccount account, long attemptId, String status, Instant occurredAt) {
        jdbc.update("""
            INSERT INTO unsubscribe_outcome_events(
                account_id,attempt_id,status,occurred_at
            ) VALUES (?,?,?,?)
            """, account.getId(), attemptId, status, java.sql.Timestamp.from(occurredAt));
    }

    private static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
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
            UserAccount account,
            String providerId,
            Instant receivedAt,
            String displayName) {
        MessageMetadata row = new MessageMetadata();
        row.setAccountId(account.getId());
        row.setProvider("google");
        row.setProviderMessageId(providerId);
        row.setReceivedAt(receivedAt);
        row.setFromDisplayName(displayName);
        row.setFromAddress("sender@example.com");
        row.setFromDomain("example.com");
        metadata.saveAndFlush(row);
    }
}
