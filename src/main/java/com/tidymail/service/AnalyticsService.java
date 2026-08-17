package com.tidymail.service;

import com.tidymail.dto.AnalyticsAvailability;
import com.tidymail.dto.AnalyticsCoverage;
import com.tidymail.dto.AnalyticsDashboard;
import com.tidymail.dto.AnalyticsMetric;
import com.tidymail.dto.AnalyticsMailboxDashboard;
import com.tidymail.dto.AnalyticsMailboxDashboard.MetricTab;
import com.tidymail.dto.AnalyticsMailboxDashboard.SeriesBucket;
import com.tidymail.dto.AnalyticsOutcomeSummary;
import com.tidymail.dto.AnalyticsPageModel;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsQuery.Comparison;
import com.tidymail.dto.AnalyticsRatio;
import com.tidymail.dto.AnalyticsSourcePage;
import com.tidymail.dto.AnalyticsSourcePage.SourceRow;
import com.tidymail.dto.AnalyticsSourceQuery;
import com.tidymail.dto.AnalyticsTrendBucket;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceState;
import com.tidymail.repository.AnalyticsReceivedRepository;
import com.tidymail.repository.AnalyticsCoverageRepository;
import com.tidymail.repository.AnalyticsLedgerRepository;
import com.tidymail.repository.AnalyticsMailboxRepository;
import com.tidymail.repository.AnalyticsMailboxRepository.MailboxBucket;
import com.tidymail.repository.AnalyticsLedgerRepository.CleanedBucketCount;
import com.tidymail.repository.AnalyticsLedgerRepository.CohortCounts;
import com.tidymail.repository.AnalyticsLedgerRepository.LedgerSnapshot;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Transactional(readOnly = true)
/**
 * Computes the Analytics dashboard and source pages from received-evidence and
 * ledger facts, deriving availability/coverage bounds, metrics, ratios, trends,
 * and the optional mailbox dashboard.
 *
 * <p>Product functionality supported: the <em>Analytics</em> flow's dashboard,
 * trend charts, comparisons, source drilldowns, and availability semantics.
 */
public class AnalyticsService {
    private final AnalyticsReceivedRepository receivedRepository;
    private final AnalyticsLedgerRepository ledgerRepository;
    private final AnalyticsCoverageRepository coverageRepository;
    private final AnalyticsMetricService metricService;
    private final AnalyticsMailboxRepository mailboxRepository;
    private final Optional<AnalyticsMailboxCapability> mailboxCapability;
    private final Clock clock;

    public AnalyticsService(AnalyticsReceivedRepository receivedRepository) {
        this(receivedRepository, null, null, null, null, Optional.empty(), Clock.systemUTC());
    }

    public AnalyticsService(
            AnalyticsReceivedRepository receivedRepository,
            AnalyticsLedgerRepository ledgerRepository,
            AnalyticsCoverageRepository coverageRepository,
            AnalyticsMetricService metricService) {
        this(receivedRepository, ledgerRepository, coverageRepository, metricService,
            null, Optional.empty(), Clock.systemUTC());
    }

    @Autowired
    public AnalyticsService(
            AnalyticsReceivedRepository receivedRepository,
            AnalyticsLedgerRepository ledgerRepository,
            AnalyticsCoverageRepository coverageRepository,
            AnalyticsMetricService metricService,
            AnalyticsMailboxRepository mailboxRepository,
            ObjectProvider<AnalyticsMailboxCapability> mailboxCapabilities) {
        this(receivedRepository, ledgerRepository, coverageRepository, metricService,
            mailboxRepository, Optional.ofNullable(mailboxCapabilities.getIfAvailable()),
            Clock.systemUTC());
    }

    AnalyticsService(
            AnalyticsReceivedRepository receivedRepository,
            AnalyticsLedgerRepository ledgerRepository,
            AnalyticsCoverageRepository coverageRepository,
            AnalyticsMetricService metricService,
            AnalyticsMailboxRepository mailboxRepository,
            Optional<AnalyticsMailboxCapability> mailboxCapability,
            Clock clock) {
        this.receivedRepository = receivedRepository;
        this.ledgerRepository = ledgerRepository;
        this.coverageRepository = coverageRepository;
        this.metricService = metricService;
        this.mailboxRepository = mailboxRepository;
        this.mailboxCapability = mailboxCapability;
        this.clock = clock;
    }

    /** Builds the analytics dashboard page model for the query. */
    public AnalyticsPageModel dashboard(
            WorkspacePageModel workspace, AnalyticsQuery query) {
        Coverage coverage = coverage(workspace);
        AnalyticsAvailability currentAvailability =
            availability(query.range(), workspace, coverage);
        long current = fact(workspace, query.range()).count();

        Long prior = null;
        AnalyticsAvailability priorAvailability = null;
        if (query.comparison() == Comparison.PRIOR) {
            priorAvailability = availability(query.priorRange(), workspace, coverage);
            prior = fact(workspace, query.priorRange()).count();
        }
        AnalyticsMetric metric = AnalyticsMetric.received(
            current, prior, currentAvailability, priorAvailability);

        List<AnalyticsQuery.DateRange> buckets = query.buckets();
        List<Long> bucketCounts = receivedRepository.countByBuckets(
            workspace.selectedAccountIds(), buckets);
        List<CleanedBucketCount> cleanedBucketCounts = ledgerRepository == null
            ? java.util.Collections.nCopies(buckets.size(), new CleanedBucketCount(0, 0))
            : ledgerRepository.cleanedCountByBuckets(
                workspace.selectedAccountIds(), buckets, query.action());
        List<Long> bucketLegacyGaps = coverageRepository == null
            ? java.util.Collections.nCopies(buckets.size(), 0L)
            : coverageRepository.legacyGapCountByBuckets(
                workspace.selectedAccountIds(), buckets, query.action());
        List<Long> bucketUnsubscribeGaps =
            coverageRepository == null
                || query.action() != AnalyticsQuery.AnalyticsAction.ALL
                    && query.action() != AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE
            ? java.util.Collections.nCopies(buckets.size(), 0L)
            : coverageRepository.unsubscribeLinkageGapCountByBuckets(
                workspace.selectedAccountIds(), buckets);
        List<AnalyticsTrendBucket> trend = new ArrayList<>();
        for (int index = 0; index < buckets.size(); index++) {
            AnalyticsQuery.DateRange bucket = buckets.get(index);
            AnalyticsAvailability receivedBucketAvailability =
                availability(bucket, workspace, coverage);
            CleanedBucketCount cleaned = cleanedBucketCounts.get(index);
            AnalyticsAvailability cleanedBucketAvailability = ledgerRepository == null
                ? AnalyticsAvailability.unavailable(
                    AnalyticsAvailability.Reason.NO_LEDGER_HISTORY, null, null, 0)
                : cleanedAvailability(
                    workspace, bucket, query.action(), bucketLegacyGaps.get(index),
                    bucketUnsubscribeGaps.get(index), cleaned.metadataExcluded());
            trend.add(new AnalyticsTrendBucket(
                bucket.startInclusive(), bucket.endExclusive(), bucketCounts.get(index),
                cleaned.count(), receivedBucketAvailability, cleanedBucketAvailability,
                sourceUrl(query, bucket, "received", 0),
                ledgerRepository == null ? null
                    : sourceUrl(query, bucket, "cleaned", 0)));
        }

        AnalyticsDashboard complete = ledgerRepository == null ? null
            : completeDashboard(
                workspace, query, coverage, currentAvailability, current, prior);

        AnalyticsQuery defaults = new AnalyticsQuery(
            query.to().minusDays(29), query.to(), query.account(),
            AnalyticsQuery.AnalyticsAction.ALL,
            AnalyticsQuery.BucketGrouping.DAY,
            Comparison.PRIOR);
        AnalyticsPageModel page = new AnalyticsPageModel(
            workspace,
            workspace.unreadScopeCounts(),
            query,
            metric,
            trend,
            complete,
            currentAvailability,
            query.canonicalParameters(),
            url("/analytics", query.canonicalParameters()),
            url("/analytics", defaults.canonicalParameters()),
            query.from() + " through " + query.to() + " UTC ("
                + query.range().duration().toDays() + " dates)");
        return mailboxRepository == null ? page
            : page.withMailbox(mailboxDashboard(workspace, query), loadingCopy(workspace));
    }

    /** Builds the optional mailbox-level dashboard. */
    private AnalyticsMailboxDashboard mailboxDashboard(
            WorkspacePageModel workspace, AnalyticsQuery query) {
        long accountId = requireSingleAccount(workspace);
        List<AnalyticsQuery.DateRange> ranges = query.buckets();
        List<MailboxBucket> facts = mailboxRepository.countsByBuckets(accountId, ranges);
        EnumMap<MetricTab, List<SeriesBucket>> series = new EnumMap<>(MetricTab.class);
        series.put(MetricTab.RECEIVED, metricSeries(ranges, facts, MailboxBucket::received));
        series.put(MetricTab.SENT, metricSeries(ranges, facts, MailboxBucket::sent));
        series.put(MetricTab.READ, metricSeries(ranges, facts, MailboxBucket::read));

        List<CleanedBucketCount> cleaned = ledgerRepository == null
            ? java.util.Collections.nCopies(ranges.size(), new CleanedBucketCount(0, 0))
            : ledgerRepository.cleanedCountByBuckets(
                List.of(accountId), ranges, AnalyticsQuery.AnalyticsAction.ALL);
        List<SeriesBucket> cleanedSeries = new ArrayList<>(ranges.size());
        for (int index = 0; index < ranges.size(); index++) {
            cleanedSeries.add(new SeriesBucket(ranges.get(index), cleaned.get(index).count()));
        }
        series.put(MetricTab.ARCHIVED_DELETED, List.copyOf(cleanedSeries));

        EnumMap<MetricTab, Long> totals = new EnumMap<>(MetricTab.class);
        series.forEach((metric, values) -> totals.put(
            metric, values.stream().mapToLong(SeriesBucket::value).sum()));
        LedgerSnapshot actions = ledgerRepository == null
            ? new LedgerSnapshot(0, 0, 0, 0, 0, 0, 0)
            : ledgerRepository.snapshot(
                List.of(accountId), query.range(), AnalyticsQuery.AnalyticsAction.ALL);
        var sizes = mailboxRepository.sizeMetrics(accountId, query.range())
            .or(() -> mailboxCapability
                .flatMap(capability -> capability.sizeMetrics(accountId, query.range())))
            .orElseGet(() -> AnalyticsMailboxDashboard.SizeMetrics.unavailable(
                "Unavailable because full message size is missing for this period."));
        return new AnalyticsMailboxDashboard(
            totals, series,
            mailboxRepository.topReceivedEmails(accountId, query.range(), 10),
            mailboxRepository.topReceivedDomains(accountId, query.range(), 10),
            mailboxRepository.topSentEmails(accountId, query.range(), 10),
            sizes, actions.archived(), actions.trashed());
    }

    /** Produces the loading/freshness copy for the mailbox dashboard. */
    String loadingCopy(WorkspacePageModel workspace) {
        long accountId = requireSingleAccount(workspace);
        String loaded = mailboxCapability
            .map(capability -> capability.reliableMailboxMessageTotal(accountId))
            .filter(OptionalLong::isPresent)
            .map(total -> "Loaded " + workspace.indexedCount() + " out of "
                + total.getAsLong() + " messages")
            .orElse("Loaded " + workspace.indexedCount() + " messages");
        if (workspace.lastSuccessfulAt() == null) return loaded + ".";
        Duration age = Duration.between(workspace.lastSuccessfulAt(), clock.instant());
        long seconds = Math.max(0, age.toSeconds());
        String relative = seconds < 60 ? seconds + " seconds ago"
            : seconds < 3_600 ? seconds / 60 + " minutes ago"
            : seconds < 86_400 ? seconds / 3_600 + " hours ago"
            : seconds / 86_400 + " days ago";
        return loaded + ". Last updated " + relative + ".";
    }

    /** Requires exactly one active account for analytics. */
    private static long requireSingleAccount(WorkspacePageModel workspace) {
        if (workspace.selectedAccountIds().size() != 1) {
            throw new IllegalArgumentException("Analytics requires one active account");
        }
        return workspace.selectedAccountIds().getFirst();
    }

    /** Maps mailbox facts into per-range series buckets. */
    private static List<SeriesBucket> metricSeries(
            List<AnalyticsQuery.DateRange> ranges,
            List<MailboxBucket> facts,
            java.util.function.ToLongFunction<MailboxBucket> metric) {
        ArrayList<SeriesBucket> values = new ArrayList<>(ranges.size());
        for (int index = 0; index < ranges.size(); index++) {
            values.add(new SeriesBucket(ranges.get(index), metric.applyAsLong(facts.get(index))));
        }
        return List.copyOf(values);
    }

    /** Builds a paginated analytics source page for a series. */
    public AnalyticsSourcePage source(
            WorkspacePageModel workspace, AnalyticsSourceQuery query) {
        AnalyticsAvailability sourceAvailability = sourceAvailability(workspace, query);
        if (sourceAvailability.state() == AnalyticsAvailability.State.UNAVAILABLE) {
            if (query.page() > 0) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid analytics source page");
            }
            return new AnalyticsSourcePage(
                query,
                sourceAvailability,
                0,
                List.of(),
                url("/analytics", query.analytics().canonicalParameters()),
                null,
                null);
        }
        long total;
        if (query.receivedSeries()) {
            total = receivedRepository.receivedFact(
                workspace.selectedAccountIds(), query.bucketStart(), query.bucketEnd()).count();
        } else if (query.cleanedSeries()) {
            total = ledgerRepository.cleanedCount(
                workspace.selectedAccountIds(), query.bucketStart(), query.bucketEnd(),
                query.analytics().action());
        } else {
            total = ledgerRepository.detailCount(
                workspace.selectedAccountIds(), query.bucketStart(), query.bucketEnd(),
                query.analytics().action(), query.series());
        }
        if (query.page() > 0
                && query.offset() >= total) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid analytics source page");
        }
        List<SourceRow> rows;
        if (query.receivedSeries()) {
            rows = receivedRepository.receivedFact(
                workspace.selectedAccountIds(), query.bucketStart(), query.bucketEnd())
                .page(AnalyticsSourceQuery.PAGE_SIZE, query.offset());
        } else if (query.cleanedSeries()) {
            rows = ledgerRepository.cleanedPage(
                workspace.selectedAccountIds(), query.bucketStart(), query.bucketEnd(),
                query.analytics().action(), AnalyticsSourceQuery.PAGE_SIZE, query.offset());
        } else {
            rows = ledgerRepository.detailPage(
                workspace.selectedAccountIds(), query.bucketStart(), query.bucketEnd(),
                query.analytics().action(), query.series(),
                AnalyticsSourceQuery.PAGE_SIZE, query.offset());
        }
        String previous = query.page() == 0 ? null
            : url("/analytics/source", query.withPage(query.page() - 1).canonicalParameters());
        String next = query.offset() + rows.size() >= total ? null
            : url("/analytics/source", query.withPage(query.page() + 1).canonicalParameters());
        return new AnalyticsSourcePage(
            query,
            sourceAvailability,
            total,
            rows,
            url("/analytics", query.analytics().canonicalParameters()),
            previous,
            next);
    }

    /** Computes the availability for an analytics source query. */
    public AnalyticsAvailability sourceAvailability(
            WorkspacePageModel workspace, AnalyticsSourceQuery query) {
        AnalyticsQuery.DateRange range =
            new AnalyticsQuery.DateRange(query.bucketStart(), query.bucketEnd());
        if (query.receivedSeries()) {
            return availability(range, workspace, coverage(workspace));
        }
        if (!query.cleanedSeries()) {
            AnalyticsAvailability availability = ledgerAvailability(
                workspace, range,
                coverageRepository.legacyGapCount(
                    workspace.selectedAccountIds(), range, query.analytics().action()));
            if (query.unsubscribeLinkageSeries()
                    && (query.analytics().action() == AnalyticsQuery.AnalyticsAction.ALL
                        || query.analytics().action()
                            == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE)) {
                long gaps = coverageRepository.unsubscribeLinkageGapCount(
                    workspace.selectedAccountIds(), range);
                if (gaps > 0) {
                    availability = availability.combine(AnalyticsAvailability.partial(
                        AnalyticsAvailability.Reason.UNSUBSCRIBE_LINKAGE_GAP,
                        availability.coverageStart(), availability.coverageEnd(), gaps));
                }
            }
            return availability;
        }
        return cleanedAvailability(
                workspace, range, query.analytics().action(),
                coverageRepository.legacyGapCount(
                    workspace.selectedAccountIds(), range, query.analytics().action()),
                query.analytics().action() == AnalyticsQuery.AnalyticsAction.ALL
                        || query.analytics().action()
                            == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE
                    ? coverageRepository.unsubscribeLinkageGapCount(
                        workspace.selectedAccountIds(), range)
                    : 0,
                ledgerRepository.cleanedMetadataExclusionCount(
                    workspace.selectedAccountIds(), range.startInclusive(),
                    range.endExclusive(), query.analytics().action()));
    }

    /** Loads the received-evidence fact for a range. */
    private AnalyticsReceivedRepository.ReceivedFact fact(
            WorkspacePageModel workspace, AnalyticsQuery.DateRange range) {
        return receivedRepository.receivedFact(
            workspace.selectedAccountIds(), range.startInclusive(), range.endExclusive());
    }

    /** Computes the conservative received-evidence coverage bounds. */
    private Coverage coverage(WorkspacePageModel workspace) {
        Map<Long, Instant> earliest =
            receivedRepository.earliestReceivedByAccount(workspace.selectedAccountIds());
        Instant conservativeStart = null;
        boolean missing = false;
        for (Long accountId : workspace.selectedAccountIds()) {
            Instant value = earliest.get(accountId);
            if (value == null) {
                missing = true;
            } else if (conservativeStart == null || value.isAfter(conservativeStart)) {
                conservativeStart = value;
            }
        }
        return new Coverage(
            conservativeStart,
            workspace.lastSuccessfulAt(),
            receivedRepository.nullReceivedCount(workspace.selectedAccountIds()),
            missing);
    }

    /** Derives availability for a range from the coverage bounds and sync state. */
    private static AnalyticsAvailability availability(
            AnalyticsQuery.DateRange range,
            WorkspacePageModel workspace,
            Coverage coverage) {
        if (coverage.missingTimestampedAccount() || coverage.start() == null) {
            return AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.NO_TIMESTAMPED_HISTORY,
                coverage.start(), coverage.end(), coverage.nullCount());
        }
        if (coverage.end() == null) {
            return AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.NO_COMPLETED_SYNC,
                coverage.start(), null, coverage.nullCount());
        }
        if (!range.endExclusive().isAfter(coverage.start())) {
            return AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.BEFORE_INDEXED_HISTORY,
                coverage.start(), coverage.end(), coverage.nullCount());
        }
        if (!range.startInclusive().isBefore(coverage.end())) {
            return AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.AFTER_SYNC_COVERAGE,
                coverage.start(), coverage.end(), coverage.nullCount());
        }
        boolean overlapsMissingHistory = range.startInclusive().isBefore(coverage.start());
        boolean overlapsIncompleteFuture = range.endExclusive().isAfter(coverage.end());
        boolean incompleteSync = workspace.state() != WorkspaceState.READY
            && workspace.state() != WorkspaceState.EMPTY;
        if (overlapsMissingHistory || overlapsIncompleteFuture || incompleteSync) {
            return AnalyticsAvailability.partial(
                AnalyticsAvailability.Reason.SYNC_INCOMPLETE,
                coverage.start(), coverage.end(), coverage.nullCount());
        }
        return AnalyticsAvailability.available(
            coverage.start(), coverage.end(), coverage.nullCount());
    }

    /** Builds the full dashboard from ledger snapshots and derived ratios. */
    private AnalyticsDashboard completeDashboard(
            WorkspacePageModel workspace,
            AnalyticsQuery query,
            Coverage receivedCoverage,
            AnalyticsAvailability receivedAvailability,
            long received,
            Long priorReceived) {
        List<Long> accountIds = workspace.selectedAccountIds();
        AnalyticsQuery.DateRange currentRange = query.range();
        long legacyGaps = coverageRepository.legacyGapCount(
            accountIds, currentRange, query.action());
        AnalyticsAvailability ledgerAvailability =
            ledgerAvailability(workspace, currentRange, legacyGaps);
        LedgerSnapshot current =
            ledgerRepository.snapshot(accountIds, currentRange, query.action());

        LedgerSnapshot prior = null;
        AnalyticsAvailability priorLedgerAvailability = null;
        if (query.comparison() == Comparison.PRIOR) {
            long priorGaps = coverageRepository.legacyGapCount(
                accountIds, query.priorRange(), query.action());
            priorLedgerAvailability =
                ledgerAvailability(workspace, query.priorRange(), priorGaps);
            prior = ledgerRepository.snapshot(
                accountIds, query.priorRange(), query.action());
        }

        EnumMap<AnalyticsMetric.MetricId, AnalyticsMetric> metrics =
            new EnumMap<>(AnalyticsMetric.MetricId.class);
        metrics.put(AnalyticsMetric.MetricId.RECEIVED, AnalyticsMetric.received(
            received, priorReceived, receivedAvailability,
            query.comparison() == Comparison.PRIOR
                ? availability(query.priorRange(), workspace, receivedCoverage) : null));
        metrics.put(AnalyticsMetric.MetricId.TRIAGED, metric(
            AnalyticsMetric.MetricId.TRIAGED, current.triaged(),
            prior == null ? null : prior.triaged(),
            ledgerAvailability, priorLedgerAvailability));
        metrics.put(AnalyticsMetric.MetricId.ARCHIVED, metric(
            AnalyticsMetric.MetricId.ARCHIVED, current.archived(),
            prior == null ? null : prior.archived(),
            ledgerAvailability, priorLedgerAvailability));
        metrics.put(AnalyticsMetric.MetricId.TRASHED, metric(
            AnalyticsMetric.MetricId.TRASHED, current.trashed(),
            prior == null ? null : prior.trashed(),
            ledgerAvailability, priorLedgerAvailability));
        metrics.put(AnalyticsMetric.MetricId.MARKED_READ, metric(
            AnalyticsMetric.MetricId.MARKED_READ, current.markedRead(),
            prior == null ? null : prior.markedRead(),
            ledgerAvailability, priorLedgerAvailability));

        long unsubscribeLinkageGaps =
            query.action() == AnalyticsQuery.AnalyticsAction.ALL
                    || query.action() == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE
                ? coverageRepository.unsubscribeLinkageGapCount(accountIds, currentRange)
                : 0;
        AnalyticsAvailability unsubscribeAvailability = unsubscribeLinkageGaps == 0
            ? ledgerAvailability
            : AnalyticsAvailability.partial(
                AnalyticsAvailability.Reason.UNSUBSCRIBE_LINKAGE_GAP,
                ledgerAvailability.coverageStart(), ledgerAvailability.coverageEnd(),
                unsubscribeLinkageGaps);
        AnalyticsAvailability priorUnsubscribeAvailability =
            priorLedgerAvailability;
        if (prior != null) {
            long priorLinkageGaps =
                query.action() == AnalyticsQuery.AnalyticsAction.ALL
                        || query.action() == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE
                    ? coverageRepository.unsubscribeLinkageGapCount(
                        accountIds, query.priorRange())
                    : 0;
            if (priorLinkageGaps > 0) {
                priorUnsubscribeAvailability = AnalyticsAvailability.partial(
                    AnalyticsAvailability.Reason.UNSUBSCRIBE_LINKAGE_GAP,
                    priorLedgerAvailability.coverageStart(),
                    priorLedgerAvailability.coverageEnd(), priorLinkageGaps);
            }
        }
        metrics.put(AnalyticsMetric.MetricId.UNSUBSCRIBED, metric(
            AnalyticsMetric.MetricId.UNSUBSCRIBED, current.unsubscribed(),
            prior == null ? null : prior.unsubscribed(),
            unsubscribeAvailability, priorUnsubscribeAvailability));

        AnalyticsOutcomeSummary outcomes = metricService.validatePartition(
            ledgerRepository.outcomes(accountIds, currentRange, query.action()));
        CohortCounts cohort =
            ledgerRepository.cohortCounts(accountIds, currentRange, query.action());
        var review =
            ledgerRepository.reviewCounts(accountIds, currentRange, query.action());
        var unsubscribe =
            query.action() == AnalyticsQuery.AnalyticsAction.ALL
                    || query.action() == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE
                ? ledgerRepository.unsubscribeCounts(accountIds, currentRange)
                : new AnalyticsLedgerRepository.UnsubscribeCounts(0, 0, 0);
        AnalyticsAvailability combined =
            receivedAvailability.combine(ledgerAvailability);
        AnalyticsRatio reviewCoverage = metricService.reviewCoverage(
            review.staged(), review.executed(), ledgerAvailability);
        AnalyticsRatio cleanupCoverage = metricService.cleanupCoverage(
            cohort.decidedReceived(), cohort.received(), combined);
        AnalyticsRatio unsubscribeCompletion = metricService.unsubscribeCompletion(
            unsubscribe.confirmed(), unsubscribe.eligible(), unsubscribe.manualOnly(),
            unsubscribeAvailability);
        AnalyticsRatio inboxReduction = metricService.inboxReduction(
            current.archived(), current.trashed(), current.inverseSucceeded(),
            received, combined);
        AnalyticsRatio cleanupRate = metricService.cleanupRate(
            cohort.cleanedTriaged(), cohort.triaged(), ledgerAvailability);
        AnalyticsRatio confidenceRetention =
            metricService.confidenceRetention(outcomes, ledgerAvailability);
        var delay = metricService.delay(
            ledgerRepository.eligibleDelaySeconds(accountIds, currentRange, query.action()),
            ledgerRepository.excludedDelayCount(accountIds, currentRange, query.action()),
            combined,
            ledgerRepository.medianDelaySeconds(accountIds, currentRange, query.action()));

        Instant ledgerStart = ledgerStart(workspace);
        AnalyticsCoverage coverage = new AnalyticsCoverage(
            receivedCoverage.start(), ledgerStart, receivedCoverage.end(),
            receivedAvailability, ledgerAvailability, unsubscribeAvailability,
            legacyGaps, unsubscribe.manualOnly());
        return new AnalyticsDashboard(
            metrics, outcomes, reviewCoverage, cleanupCoverage,
            unsubscribeCompletion, inboxReduction, cleanupRate,
            confidenceRetention, delay, current.unsubscribeAcceptedOrPending(),
            coverageRepository.topSenders(accountIds, currentRange, 5),
            coverage);
    }

    /** Builds a count metric with its prior-period comparison. */
    private static AnalyticsMetric metric(
            AnalyticsMetric.MetricId id,
            long current,
            Long prior,
            AnalyticsAvailability availability,
            AnalyticsAvailability priorAvailability) {
        return AnalyticsMetric.count(
            id, current, prior, availability, priorAvailability);
    }

    /** Derives availability for ledger facts given legacy gaps. */
    private AnalyticsAvailability ledgerAvailability(
            WorkspacePageModel workspace,
            AnalyticsQuery.DateRange range,
            long legacyGaps) {
        Instant start = ledgerStart(workspace);
        if (start == null) {
            return AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.NO_LEDGER_HISTORY, null, null, legacyGaps);
        }
        if (!range.endExclusive().isAfter(start)) {
            return AnalyticsAvailability.unavailable(
                AnalyticsAvailability.Reason.NO_LEDGER_HISTORY, start, null, legacyGaps);
        }
        if (range.startInclusive().isBefore(start) || legacyGaps > 0) {
            return AnalyticsAvailability.partial(
                legacyGaps > 0
                    ? AnalyticsAvailability.Reason.LEGACY_LEDGER_GAP
                    : AnalyticsAvailability.Reason.NO_LEDGER_HISTORY,
                start, null, legacyGaps);
        }
        return AnalyticsAvailability.available(start, null, 0);
    }

    /** Derives availability for cleaned facts, combining gap reasons. */
    private AnalyticsAvailability cleanedAvailability(
            WorkspacePageModel workspace,
            AnalyticsQuery.DateRange range,
            AnalyticsQuery.AnalyticsAction action,
            long legacyGaps,
            long unsubscribeGaps,
            long metadataExclusions) {
        AnalyticsAvailability availability =
            ledgerAvailability(workspace, range, legacyGaps);
        if (unsubscribeGaps > 0
                && (action == AnalyticsQuery.AnalyticsAction.ALL
                    || action == AnalyticsQuery.AnalyticsAction.UNSUBSCRIBE)) {
            availability = availability.combine(AnalyticsAvailability.partial(
                AnalyticsAvailability.Reason.UNSUBSCRIBE_LINKAGE_GAP,
                availability.coverageStart(), availability.coverageEnd(),
                unsubscribeGaps));
        }
        if (metadataExclusions > 0) {
            availability = availability.combine(AnalyticsAvailability.partial(
                AnalyticsAvailability.Reason.CLEANED_METADATA_GAP,
                availability.coverageStart(), availability.coverageEnd(),
                metadataExclusions));
        }
        return availability;
    }

    /** Computes the conservative earliest ledger start across accounts. */
    private Instant ledgerStart(WorkspacePageModel workspace) {
        Map<Long, Instant> starts =
            coverageRepository.earliestLedgerByAccount(workspace.selectedAccountIds());
        Instant conservative = null;
        for (Long accountId : workspace.selectedAccountIds()) {
            Instant value = starts.get(accountId);
            if (value == null) return null;
            if (conservative == null || value.isAfter(conservative)) {
                conservative = value;
            }
        }
        return conservative;
    }

    /** Builds an analytics source URL for a series bucket. */
    private static String sourceUrl(
            AnalyticsQuery query,
            AnalyticsQuery.DateRange bucket,
            String series,
            int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/analytics/source");
        query.canonicalParameters().forEach(builder::queryParam);
        builder.queryParam("series", series);
        builder.queryParam("bucketStart", bucket.startInclusive().toString());
        builder.queryParam("bucketEnd", bucket.endExclusive().toString());
        builder.queryParam("page", page);
        return builder.build().encode().toUriString();
    }

    /** Builds an encoded URL from a path and query parameters. */
    public static String url(String path, Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }

    /** Internal received-evidence coverage bounds. */
    private record Coverage(
        Instant start,
        Instant end,
        long nullCount,
        boolean missingTimestampedAccount
    ) {
    }
}
