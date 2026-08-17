package com.tidymail.service;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.UnreadCategoryGroup;
import com.tidymail.dto.UnreadDecisionRow;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.UnreadSenderRow;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UnreadDecisionQueryService {
    private final DecisionSurfaceQueryRepository repository;
    private final DecisionClassifier classifier;
    private final ProviderDeepLinkService deepLinks;
    private final Clock clock;

    @Autowired
    public UnreadDecisionQueryService(
            DecisionSurfaceQueryRepository repository,
            DecisionClassifier classifier,
            ProviderDeepLinkService deepLinks) {
        this(repository, classifier, deepLinks, Clock.systemUTC());
    }

    UnreadDecisionQueryService(
            DecisionSurfaceQueryRepository repository,
            DecisionClassifier classifier,
            ProviderDeepLinkService deepLinks,
            Clock clock) {
        this.repository = repository;
        this.classifier = classifier;
        this.deepLinks = deepLinks;
        this.clock = clock;
    }

    public DecisionPage<UnreadDecisionRow> query(
            Collection<Long> authorizedAccountIds, UnreadQuery query) {
        Instant now = clock.instant();
        DecisionSurfaceQueryRepository.QueryResult<
            DecisionSurfaceQueryRepository.UnreadFact> result =
                repository.unread(requireScope(authorizedAccountIds, query.account()), query, now);
        List<UnreadDecisionRow> rows = result.rows().stream()
            .map(fact -> withProviderLink(classifier.unread(
                DecisionRowKey.unread(fact.accountId(), fact.metadataId()), fact, now), fact))
            .toList();
        int pages = totalPages(result.count());
        Map<String, String> previous = query.page() > 0
            ? query.withPage(query.page() - 1).canonicalParameters() : Map.of();
        Map<String, String> next = query.page() + 1 < pages
            ? query.withPage(query.page() + 1).canonicalParameters() : Map.of();
        return new DecisionPage<>(
            rows, result.count(), result.metrics(), query.page(), UnreadQuery.PAGE_SIZE,
            pages, previous, next);
    }

    public UnreadScopeCounts unreadScopeCounts(Collection<Long> authorizedAccountIds) {
        return repository.unreadScopeCounts(requireScope(authorizedAccountIds, "all"));
    }

    public List<UnreadCategoryGroup> categories(
            Collection<Long> authorizedAccountIds, UnreadQuery query) {
        Instant now = clock.instant();
        LinkedHashMap<SenderIdentity, UnreadSenderAccumulator> senders = new LinkedHashMap<>();
        try (Stream<DecisionSurfaceQueryRepository.UnreadFact> facts =
                repository.streamUnread(
                    requireScope(authorizedAccountIds, query.account()),
                    query.resetPage(), now)) {
            facts.forEach(fact -> senders.computeIfAbsent(
                    new SenderIdentity(fact.accountId(), fact.senderKey()),
                    ignored -> new UnreadSenderAccumulator(fact, query))
                .add(fact, now));
        }
        Comparator<UnreadSenderRow> volumeOrder =
            Comparator.comparingLong(UnreadSenderRow::unreadCount).reversed()
                .thenComparingLong(UnreadSenderRow::accountId)
                .thenComparing(UnreadSenderRow::senderAddress);
        List<UnreadSenderAccumulator> values = List.copyOf(senders.values());
        return List.of(
            group("lists-automated", "Lists and automated mail",
                "Identified by backend rules; senders may also appear in other groups.",
                values, UnreadSenderAccumulator::listsOrAutomated, volumeOrder, false, false, true),
            group("older-than-30-days", "Older than 30 days",
                "Unread mail that has probably gone stale.",
                values, UnreadSenderAccumulator::olderThanThirtyDays,
                volumeOrder, false, false, true),
            group("direct-mail", "Direct mail",
                "Likely 1:1 senders, sorted by unread volume. Change type if this looks wrong.",
                values, UnreadSenderAccumulator::direct, volumeOrder, true, false, true),
            group("attachments", "Has attachments",
                "Unread mail with attachment metadata in the approved index.",
                values, UnreadSenderAccumulator::hasAttachments,
                volumeOrder, false, false, true),
            group("other", "Other",
                "Select senders here, then move them or create a category.",
                values, UnreadSenderAccumulator::other, volumeOrder, false, true, true));
    }

    private UnreadDecisionRow withProviderLink(
            UnreadDecisionRow row,
            DecisionSurfaceQueryRepository.UnreadFact fact) {
        return new UnreadDecisionRow(
            row.rowKey(), row.accountId(), row.senderAddress(), row.displayName(), row.domain(),
            row.subject(), row.receivedAt(), row.category(), row.threadState(), row.waitingOnMe(),
            row.recommendation(), row.protectedSender(), row.reasonCodes(), row.reasons(),
            deepLinks.gmail(
                fact.provider(), fact.accountEmail(), fact.providerThreadId(),
                fact.providerMessageId()).orElse(null));
    }

    private static int totalPages(long count) {
        long pages = (count + UnreadQuery.PAGE_SIZE - 1) / UnreadQuery.PAGE_SIZE;
        return Math.toIntExact(pages);
    }

    private static List<Long> requireScope(Collection<Long> accountIds, String accountSelector) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        List<Long> scope = accountIds.stream().distinct().sorted().toList();
        if ("current".equals(accountSelector) && scope.size() != 1
                || !"current".equals(accountSelector) && !"all".equals(accountSelector)
                    && (scope.size() != 1
                        || scope.getFirst().longValue() != Long.parseLong(accountSelector))) {
            throw new IllegalArgumentException("Authorized account scope does not match query");
        }
        return scope;
    }

    private static UnreadCategoryGroup group(
            String key,
            String title,
            String description,
            List<UnreadSenderAccumulator> values,
            java.util.function.Predicate<UnreadSenderAccumulator> membership,
            Comparator<UnreadSenderRow> order,
            boolean direct,
            boolean other,
            boolean dataAvailable) {
        List<UnreadSenderRow> rows = values.stream()
            .filter(membership)
            .map(UnreadSenderAccumulator::row)
            .sorted(order)
            .toList();
        return new UnreadCategoryGroup(
            key, title, description, rows, direct, other, dataAvailable);
    }

    private record SenderIdentity(long accountId, String senderKey) {
    }

    private static final class UnreadSenderAccumulator {
        private final long accountId;
        private final String senderKey;
        private final UnreadQuery query;
        private String displayName;
        private String domain;
        private long unreadCount;
        private long estimatedSize;
        private boolean allSizesKnown = true;
        private boolean listsOrAutomated;
        private boolean olderThanThirtyDays;
        private boolean direct;
        private boolean hasAttachments;
        private boolean protectedSender;
        private final ArrayList<String> rowKeys = new ArrayList<>();

        private UnreadSenderAccumulator(
                DecisionSurfaceQueryRepository.UnreadFact fact, UnreadQuery query) {
            accountId = fact.accountId();
            senderKey = fact.senderKey();
            this.query = query;
            displayName = fact.displayName();
            domain = fact.domain();
        }

        private void add(
                DecisionSurfaceQueryRepository.UnreadFact fact, Instant now) {
            unreadCount++;
            if (fact.sizeEstimate() == null) {
                allSizesKnown = false;
            } else {
                estimatedSize = Math.addExact(estimatedSize, fact.sizeEstimate());
            }
            listsOrAutomated |= fact.category() == DecisionRuleSet.UnreadCategory.LIST
                || fact.category() == DecisionRuleSet.UnreadCategory.AUTOMATED;
            olderThanThirtyDays |= fact.receivedAt() != null
                && fact.receivedAt().isBefore(now.minus(30, ChronoUnit.DAYS));
            direct |= fact.category() == DecisionRuleSet.UnreadCategory.ONE_TO_ONE
                && !"__missing__".equals(senderKey);
            hasAttachments |= fact.hasAttachments();
            protectedSender |= fact.protectedSender();
            rowKeys.add(DecisionRowKey.unread(fact.accountId(), fact.metadataId()));
            if ("Unknown sender".equals(displayName)
                    && !"Unknown sender".equals(fact.displayName())) {
                displayName = fact.displayName();
            }
            if (domain == null && fact.domain() != null) domain = fact.domain();
        }

        private boolean listsOrAutomated() {
            return listsOrAutomated;
        }

        private boolean olderThanThirtyDays() {
            return olderThanThirtyDays;
        }

        private boolean direct() {
            return direct;
        }

        private boolean hasAttachments() {
            return hasAttachments;
        }

        private boolean other() {
            return "__missing__".equals(senderKey);
        }

        private UnreadSenderRow row() {
            DecisionRuleSet.UnreadCategory inferred = listsOrAutomated
                ? DecisionRuleSet.UnreadCategory.AUTOMATED
                : DecisionRuleSet.UnreadCategory.ONE_TO_ONE;
            return new UnreadSenderRow(
                accountId,
                "__missing__".equals(senderKey) ? "" : senderKey,
                displayName,
                domain,
                unreadCount,
                allSizesKnown ? estimatedSize : null,
                inferred,
                protectedSender,
                rowKeys,
                actionQuery());
        }

        private Map<String, String> actionQuery() {
            if ("__missing__".equals(senderKey)) return Map.of();
            return new UnreadQuery(
                Long.toString(accountId),
                query.search(),
                query.type(),
                query.age(),
                query.waiting(),
                query.thread(),
                query.recommendation(),
                senderKey,
                query.provider(),
                query.sort(),
                0).canonicalParameters();
        }
    }
}
