package com.tidymail.service;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.CategoryPage;
import com.tidymail.dto.SenderCategoryGroup;
import com.tidymail.dto.SenderDecisionRow;
import com.tidymail.dto.SenderQuery;
import com.tidymail.entity.SenderCategory;
import com.tidymail.entity.SenderCategoryAssignment;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.SenderCategoryAssignmentRepository;
import com.tidymail.repository.SenderCategoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class SenderDecisionQueryService {
    private final DecisionSurfaceQueryRepository repository;
    private final SenderCategoryRepository senderCategories;
    private final SenderCategoryAssignmentRepository categoryAssignments;
    private final DecisionClassifier classifier;
    private final Clock clock;

    @Autowired
    public SenderDecisionQueryService(
            DecisionSurfaceQueryRepository repository,
            SenderCategoryRepository senderCategories,
            SenderCategoryAssignmentRepository categoryAssignments,
            DecisionClassifier classifier) {
        this(repository, senderCategories, categoryAssignments, classifier, Clock.systemUTC());
    }

    SenderDecisionQueryService(
            DecisionSurfaceQueryRepository repository,
            SenderCategoryRepository senderCategories,
            SenderCategoryAssignmentRepository categoryAssignments,
            DecisionClassifier classifier,
            Clock clock) {
        this.repository = repository;
        this.senderCategories = senderCategories;
        this.categoryAssignments = categoryAssignments;
        this.classifier = classifier;
        this.clock = clock;
    }

    public DecisionPage<SenderDecisionRow> query(
            Collection<Long> authorizedAccountIds, SenderQuery query) {
        return query(authorizedAccountIds, query, null);
    }

    public DecisionPage<SenderDecisionRow> query(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope) {
        Instant now = clock.instant();
        DecisionSurfaceQueryRepository.QueryResult<
            DecisionSurfaceQueryRepository.SenderFact> result =
                repository.senders(
                    requireScope(authorizedAccountIds, query.account()),
                    query, now, analyticsScope);
        List<SenderDecisionRow> rows = result.rows().stream()
            .map(fact -> classifier.sender(
                DecisionRowKey.sender(fact.accountId(), fact.senderKey()),
                fact,
                now,
                fact.manualType()))
            .toList();
        int pages = totalPages(result.count());
        Map<String, String> previous = query.page() > 0
            ? query.withPage(query.page() - 1).canonicalParameters() : Map.of();
        Map<String, String> next = query.page() + 1 < pages
            ? query.withPage(query.page() + 1).canonicalParameters() : Map.of();
        return new DecisionPage<>(
            rows, result.count(), result.metrics(), query.page(), SenderQuery.PAGE_SIZE,
            pages, previous, next);
    }

    public List<SenderCategoryGroup> categories(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope) {
        Instant now = clock.instant();
        List<Long> scope = requireScope(authorizedAccountIds, query.account());
        Map<String, DecisionSurfaceQueryRepository.SenderCategorySummary> summaries =
            repository.senderCategorySummaries(scope, query, now, analyticsScope).stream()
                .collect(Collectors.toMap(
                    DecisionSurfaceQueryRepository.SenderCategorySummary::categoryKey,
                    Function.identity()));
        List<SenderCategoryGroup> groups = new ArrayList<>(List.of(
            group("lists", "Lists and newsletters",
                "Recurring lists and publication mail identified from indexed headers.",
                summaries.get("lists"), false, scope, query, analyticsScope, now),
            group("automated", "Automated notifications",
                "Automated delivery and product notification senders.",
                summaries.get("automated"), false, scope, query, analyticsScope, now),
            group("transactional", "Receipts and transactions",
                "Transactional senders inferred from delivery metadata.",
                summaries.get("transactional"), false, scope, query, analyticsScope, now),
            group("personal", "Personal senders",
                "Likely personal senders retained for deliberate review.",
                summaries.get("personal"), false, scope, query, analyticsScope, now)
            ));
        groups.addAll(userGeneratedGroups(scope, query, analyticsScope, now));
        groups.add(
            group("other", "Other",
                "Select uncategorized senders, then move them or create a category.",
                summaries.get("other"), true, scope, query, analyticsScope, now));
        return List.copyOf(groups);
    }

    public CategoryPage<SenderDecisionRow> categoryPage(
            Collection<Long> authorizedAccountIds,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope,
            String categoryKey,
            long offset,
            boolean loadAll) {
        if (offset < 0 || offset > 1_000 || offset % 5 != 0 || loadAll && offset < 10) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid category paging state");
        }
        Instant now = clock.instant();
        List<Long> scope = requireScope(authorizedAccountIds, query.account());
        if (categoryKey != null && categoryKey.startsWith("custom-")) {
            return customCategoryPage(
                scope, query, analyticsScope, categoryKey, offset, loadAll, now);
        }
        long exactTotal = repository.senderCategorySummaries(
                scope, query, now, analyticsScope).stream()
            .filter(summary -> summary.categoryKey().equals(categoryKey))
            .mapToLong(DecisionSurfaceQueryRepository.SenderCategorySummary::senderCount)
            .findFirst()
            .orElse(0);
        if (loadAll && exactTotal > 1_000) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "This category has more than 1,000 senders. Continue with Load more.");
        }
        return categoryPage(
            scope, query, analyticsScope, categoryKey, offset, loadAll, exactTotal, now);
    }

    private CategoryPage<SenderDecisionRow> categoryPage(
            List<Long> scope,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope,
            String categoryKey,
            long offset,
            boolean loadAll,
            long exactTotal,
            Instant now) {
        int limit = loadAll
            ? Math.toIntExact(Math.max(0, exactTotal - offset))
            : Math.toIntExact(Math.min(5, Math.max(0, exactTotal - offset)));
        List<SenderDecisionRow> rows = limit == 0
            ? List.of()
            : repository.senderCategoryRows(
                    scope, query, categoryKey, offset, limit, now, analyticsScope).stream()
                .map(fact -> classifier.sender(
                    DecisionRowKey.sender(fact.accountId(), fact.senderKey()),
                    fact,
                    now,
                    fact.manualType()))
                .toList();
        long loadedCount = Math.min(exactTotal, offset + rows.size());
        boolean hasMore = loadedCount < exactTotal;
        return new CategoryPage<>(
            categoryKey,
            rows,
            loadedCount,
            exactTotal,
            hasMore,
            hasMore && loadedCount >= 10 && exactTotal <= 1_000);
    }

    private SenderCategoryGroup group(
            String key,
            String title,
            String description,
            DecisionSurfaceQueryRepository.SenderCategorySummary summary,
            boolean other,
            List<Long> scope,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope,
            Instant now) {
        long senderCount = summary == null ? 0 : summary.senderCount();
        return new SenderCategoryGroup(
            key,
            title,
            description,
            senderCount,
            summary == null ? null : summary.estimatedSize(),
            false,
            other,
            categoryPage(
                scope, query, analyticsScope, key, 0, false, senderCount, now));
    }

    private List<SenderCategoryGroup> userGeneratedGroups(
            List<Long> scope,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope,
            Instant now) {
        List<SenderCategory> categories = scope.stream()
            .flatMap(accountId -> senderCategories
                .findAllByAccountIdOrderByCreatedAtAscIdAsc(accountId).stream())
            .toList();
        if (categories.isEmpty()) return List.of();
        Map<String, DecisionSurfaceQueryRepository.SenderFact> facts =
            senderFactsByAccountAndSender(scope, query, analyticsScope, now);
        List<SenderCategoryGroup> groups = new ArrayList<>();
        for (SenderCategory category : categories) {
            CategoryPage<SenderDecisionRow> page = customCategoryPage(
                category, facts, 0, false);
            groups.add(new SenderCategoryGroup(
                customCategoryKey(category.getId()),
                category.getName(),
                "Account-owned category.",
                page.exactTotal(),
                null,
                true,
                false,
                page));
        }
        return groups;
    }

    private CategoryPage<SenderDecisionRow> customCategoryPage(
            List<Long> scope,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope,
            String categoryKey,
            long offset,
            boolean loadAll,
            Instant now) {
        if (offset < 0 || offset > 1_000 || offset % 5 != 0 || loadAll && offset < 10) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid category paging state");
        }
        long categoryId = customCategoryId(categoryKey);
        SenderCategory category = scope.stream()
            .flatMap(accountId -> senderCategories
                .findByIdAndAccountId(categoryId, accountId).stream())
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Sender category not found"));
        Map<String, DecisionSurfaceQueryRepository.SenderFact> facts =
            senderFactsByAccountAndSender(scope, query, analyticsScope, now);
        CategoryPage<SenderDecisionRow> page = customCategoryPage(
            category, facts, offset, loadAll);
        if (loadAll && page.exactTotal() > 1_000) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "This category has more than 1,000 senders. Continue with Load more.");
        }
        return page;
    }

    private CategoryPage<SenderDecisionRow> customCategoryPage(
            SenderCategory category,
            Map<String, DecisionSurfaceQueryRepository.SenderFact> facts,
            long offset,
            boolean loadAll) {
        List<SenderDecisionRow> rows = categoryAssignments
            .findAllByAccountIdAndCategoryIdOrderBySenderAddressAsc(
                category.getAccountId(), category.getId())
            .stream()
            .map(SenderCategoryAssignment::getSenderAddress)
            .map(address -> facts.get(factKey(category.getAccountId(), address)))
            .filter(fact -> fact != null)
            .map(fact -> classifier.sender(
                DecisionRowKey.sender(fact.accountId(), fact.senderKey()),
                fact,
                clock.instant(),
                fact.manualType()))
            .toList();
        long exactTotal = rows.size();
        int start = Math.toIntExact(Math.min(offset, exactTotal));
        int limit = loadAll
            ? Math.toIntExact(exactTotal - start)
            : Math.toIntExact(Math.min(5, exactTotal - start));
        List<SenderDecisionRow> pageRows = rows.subList(start, Math.addExact(start, limit));
        long loadedCount = Math.min(exactTotal, offset + pageRows.size());
        boolean hasMore = loadedCount < exactTotal;
        return new CategoryPage<>(
            customCategoryKey(category.getId()),
            pageRows,
            loadedCount,
            exactTotal,
            hasMore,
            hasMore && loadedCount >= 10 && exactTotal <= 1_000);
    }

    private Map<String, DecisionSurfaceQueryRepository.SenderFact> senderFactsByAccountAndSender(
            List<Long> scope,
            SenderQuery query,
            AnalyticsSenderScope analyticsScope,
            Instant now) {
        try (Stream<DecisionSurfaceQueryRepository.SenderFact> stream =
                 repository.streamSenders(scope, query.resetPage(), now, analyticsScope)) {
            return stream.collect(Collectors.toMap(
                fact -> factKey(fact.accountId(), fact.senderKey()),
                Function.identity(),
                (existing, replacement) -> existing,
                LinkedHashMap::new));
        }
    }

    private static String customCategoryKey(long categoryId) {
        return "custom-" + categoryId;
    }

    private static long customCategoryId(String categoryKey) {
        try {
            return Long.parseLong(categoryKey.substring("custom-".length()));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Sender category not found");
        }
    }

    private static String factKey(long accountId, String senderKey) {
        return accountId + "\n" + senderKey;
    }

    private static int totalPages(long count) {
        long pages = (count + SenderQuery.PAGE_SIZE - 1) / SenderQuery.PAGE_SIZE;
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
}
