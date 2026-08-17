package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.dto.AnalyticsMetric;
import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsQuery.AnalyticsAction;
import com.tidymail.dto.AnalyticsQuery.BucketGrouping;
import com.tidymail.dto.AnalyticsQuery.Comparison;
import com.tidymail.dto.AnalyticsSourceQuery;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.repository.AnalyticsReceivedRepository;
import com.tidymail.service.AnalyticsQueryService;
import com.tidymail.service.AnalyticsService;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.service.WorkspaceModelService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class Phase13AnalyticsQueryTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-09T18:45:00Z"), ZoneOffset.UTC);

    private final OwnedAccountScopeService accounts = mock(OwnedAccountScopeService.class);
    private final AnalyticsQueryService service = new AnalyticsQueryService(accounts, CLOCK);

    @Test
    void defaultsAreLatestThirtyUtcDatesAndSerializeAllSixKeysInOrder() {
        AnalyticsQuery query = service.parse(Map.of());

        assertThat(query.from()).isEqualTo(LocalDate.parse("2026-07-11"));
        assertThat(query.to()).isEqualTo(LocalDate.parse("2026-08-09"));
        assertThat(query.canonicalParameters().keySet())
            .containsExactly("from", "to", "account", "action", "group", "compare");
        assertThat(query.canonicalParameters().values())
            .containsExactly("2026-07-11", "2026-08-09", "current", "all", "day", "prior");
        assertThat(query.range().startInclusive())
            .isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
        assertThat(query.range().endExclusive())
            .isEqualTo(Instant.parse("2026-08-10T00:00:00Z"));
    }

    @Test
    void strictParserRejectsUnknownDuplicateMalformedReversedAndOverlargeInputs() {
        List<Map<String, List<String>>> invalid = List.of(
            Map.of("unknown", List.of("x")),
            Map.of("from", List.of("2026-08-01", "2026-08-02")),
            Map.of("from", List.of("2026-02-30")),
            Map.of("from", List.of("2026-08-10"), "to", List.of("2026-08-09")),
            Map.of("from", List.of("2025-08-08"), "to", List.of("2026-08-09")),
            Map.of("action", List.of("delete")),
            Map.of("group", List.of("quarter")),
            Map.of("compare", List.of("yes")),
            Map.of("account", List.of("-1")));

        invalid.forEach(parameters ->
            assertThatThrownBy(() -> service.parse(parameters))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400"));
        verifyNoInteractions(accounts);
    }

    @Test
    void oneAndExactlyThreeHundredSixtySixDatesAreAccepted() {
        assertThat(service.parse(Map.of(
            "from", List.of("2026-02-28"), "to", List.of("2026-02-28")))
            .range().duration()).isEqualTo(Duration.ofDays(1));
        assertThat(service.parse(Map.of(
            "from", List.of("2024-02-29"), "to", List.of("2025-02-28")))
            .range().duration()).isEqualTo(Duration.ofDays(366));
    }

    @Test
    void priorHasIdenticalDurationAndEndsAtCurrentStartAcrossLeapBoundary() {
        AnalyticsQuery query = query("2024-02-28", "2024-03-01", BucketGrouping.DAY);

        assertThat(query.priorRange().endExclusive()).isEqualTo(query.range().startInclusive());
        assertThat(query.priorRange().duration()).isEqualTo(query.range().duration());
        assertThat(query.priorRange().startInclusive())
            .isEqualTo(Instant.parse("2024-02-25T00:00:00Z"));
    }

    @Test
    void dayWeekAndMonthBucketsAreContiguousClippedAndCoverRangeExactly() {
        AnalyticsQuery days = query("2026-01-30", "2026-02-02", BucketGrouping.DAY);
        AnalyticsQuery weeks = query("2026-01-30", "2026-02-10", BucketGrouping.WEEK);
        AnalyticsQuery months = query("2024-02-28", "2024-04-02", BucketGrouping.MONTH);

        assertThat(days.buckets()).hasSize(4);
        assertThat(weeks.buckets()).extracting(AnalyticsQuery.DateRange::startInclusive)
            .containsExactly(
                Instant.parse("2026-01-30T00:00:00Z"),
                Instant.parse("2026-02-02T00:00:00Z"),
                Instant.parse("2026-02-09T00:00:00Z"));
        assertThat(months.buckets()).extracting(AnalyticsQuery.DateRange::endExclusive)
            .containsExactly(
                Instant.parse("2024-03-01T00:00:00Z"),
                Instant.parse("2024-04-01T00:00:00Z"),
                Instant.parse("2024-04-03T00:00:00Z"));
        assertCoverage(days);
        assertCoverage(weeks);
        assertCoverage(months);
    }

    @Test
    void sourceRequiresCompleteFieldsAndAnExactGeneratedBucket() {
        Map<String, List<String>> valid = Map.of(
            "from", List.of("2026-08-01"),
            "to", List.of("2026-08-03"),
            "account", List.of("all"),
            "action", List.of("archive"),
            "group", List.of("day"),
            "compare", List.of("none"),
            "series", List.of("received"),
            "bucketStart", List.of("2026-08-02T00:00:00Z"),
            "bucketEnd", List.of("2026-08-03T00:00:00Z"),
            "page", List.of("2"));

        AnalyticsSourceQuery source = service.parseSource(valid);
        assertThat(source.page()).isEqualTo(2);
        assertThat(source.canonicalParameters().keySet()).containsExactly(
            "from", "to", "account", "action", "group", "compare",
            "series", "bucketStart", "bucketEnd", "page");
        assertThatThrownBy(() -> service.parseSource(Map.of(
            "from", List.of("2026-08-01"), "to", List.of("2026-08-03"),
            "series", List.of("received"),
            "bucketStart", List.of("2026-08-01T12:00:00Z"),
            "bucketEnd", List.of("2026-08-02T00:00:00Z"))))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.parseSource(Map.of(
            "from", List.of("2026-08-01"), "to", List.of("2026-08-03"),
            "bucketStart", List.of("2026-08-01T00:00:00Z"),
            "bucketEnd", List.of("2026-08-02T00:00:00Z"))))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void availabilityCombinesConservativelyAndPriorZeroHasNoPercentage() {
        AnalyticsAvailability available = AnalyticsAvailability.available(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-08-10T00:00:00Z"), 0);
        AnalyticsAvailability partial = AnalyticsAvailability.partial(
            AnalyticsAvailability.Reason.SYNC_INCOMPLETE,
            Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-08-09T00:00:00Z"), 2);
        AnalyticsAvailability unavailable = AnalyticsAvailability.unavailable(
            AnalyticsAvailability.Reason.BEFORE_INDEXED_HISTORY,
            Instant.parse("2026-03-01T00:00:00Z"), null, 0);

        assertThat(available.combine(partial).state())
            .isEqualTo(AnalyticsAvailability.State.PARTIAL);
        assertThat(partial.combine(unavailable).state())
            .isEqualTo(AnalyticsAvailability.State.UNAVAILABLE);
        AnalyticsMetric priorZero = AnalyticsMetric.received(7, 0L, available, available);
        assertThat(priorZero.absoluteDelta()).isEqualTo(7);
        assertThat(priorZero.percentDelta()).isNull();
    }

    @Test
    void comparisonPercentageRoundsOnlyAtDisplayBoundary() {
        AnalyticsAvailability available = AnalyticsAvailability.available(null, null, 0);
        AnalyticsMetric metric = AnalyticsMetric.received(4, 3L, available, available);

        assertThat(metric.percentDelta()).isEqualByComparingTo("33.3");
    }

    @Test
    void mixedAccountCoverageUsesLeastRecentSuccessfulEnd() {
        Instant earlyEnd = Instant.parse("2026-08-02T12:00:00Z");
        WorkspaceModelService.Aggregate aggregate = WorkspaceModelService.aggregate(
            List.of(
                new WorkspaceModelService.AccountSummary(
                    1L, 1, 1, 1, "COMPLETE",
                    Instant.parse("2026-08-04T12:00:00Z"), null),
                new WorkspaceModelService.AccountSummary(
                    2L, 1, 1, 1, "COMPLETE", earlyEnd, null)),
            Instant.parse("2026-08-04T12:01:00Z"));
        WorkspacePageModel workspace = new WorkspacePageModel(
            WorkspaceRoute.ANALYTICS, "Owner", "owner@example.com",
            List.of(), List.of(1L, 2L), "all", "All linked accounts (2)",
            0L, UnreadScopeCounts.available(0, 0),
            2, 2, 2, null, aggregate.lastSuccessfulAt(), aggregate.state(),
            false, false, false);
        AnalyticsReceivedRepository repository = mock(AnalyticsReceivedRepository.class);
        AnalyticsReceivedRepository.ReceivedFact fact =
            mock(AnalyticsReceivedRepository.ReceivedFact.class);
        when(repository.earliestReceivedByAccount(List.of(1L, 2L))).thenReturn(Map.of(
            1L, Instant.parse("2026-08-01T00:00:00Z"),
            2L, Instant.parse("2026-08-01T00:00:00Z")));
        when(repository.nullReceivedCount(List.of(1L, 2L))).thenReturn(0L);
        when(repository.receivedFact(
            org.mockito.ArgumentMatchers.eq(List.of(1L, 2L)),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(fact);
        when(repository.countByBuckets(
            org.mockito.ArgumentMatchers.eq(List.of(1L, 2L)),
            org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                List<?> buckets = invocation.getArgument(1);
                return java.util.Collections.nCopies(buckets.size(), 0L);
            });
        AnalyticsService analytics = new AnalyticsService(repository);

        AnalyticsPageModel overlapping = analytics.dashboard(
            workspace, query("2026-08-02", "2026-08-02", BucketGrouping.DAY));
        AnalyticsPageModel afterEnd = analytics.dashboard(
            workspace, query("2026-08-03", "2026-08-03", BucketGrouping.DAY));

        assertThat(aggregate.lastSuccessfulAt()).isEqualTo(earlyEnd);
        assertThat(overlapping.availability().state())
            .isEqualTo(AnalyticsAvailability.State.PARTIAL);
        assertThat(afterEnd.availability().state())
            .isEqualTo(AnalyticsAvailability.State.UNAVAILABLE);
        assertThat(afterEnd.availability().reason())
            .isEqualTo(AnalyticsAvailability.Reason.AFTER_SYNC_COVERAGE);
    }

    private static AnalyticsQuery query(
            String from, String to, BucketGrouping grouping) {
        return new AnalyticsQuery(
            LocalDate.parse(from), LocalDate.parse(to), "all", AnalyticsAction.ALL,
            grouping, Comparison.PRIOR);
    }

    private static void assertCoverage(AnalyticsQuery query) {
        assertThat(query.buckets().getFirst().startInclusive())
            .isEqualTo(query.range().startInclusive());
        assertThat(query.buckets().getLast().endExclusive())
            .isEqualTo(query.range().endExclusive());
        for (int index = 1; index < query.buckets().size(); index++) {
            assertThat(query.buckets().get(index - 1).endExclusive())
                .isEqualTo(query.buckets().get(index).startInclusive());
        }
        assertThat(query.buckets().stream()
            .map(AnalyticsQuery.DateRange::duration)
            .reduce(Duration.ZERO, Duration::plus))
            .isEqualTo(query.range().duration());
    }
}
