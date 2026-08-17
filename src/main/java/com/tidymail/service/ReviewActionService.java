package com.tidymail.service;

import com.tidymail.dto.ReviewActionGroup;
import com.tidymail.dto.ReviewActionRow;
import com.tidymail.dto.ReviewQuery;
import com.tidymail.dto.ReviewQueueModel;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.repository.ReviewActionQueryRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds the Review queue view model: the filtered action rows, their grouped
 * presentation by action kind, metrics, and pagination URLs, with per-row
 * cancellation intents.
 *
 * <p>Product functionality supported: the <em>Review</em> flow's queue listing,
 * grouping, and pagination.
 */
@Service
public class ReviewActionService {
    private final ReviewActionQueryRepository repository;
    private final ReviewOperationIntentService intents;

    public ReviewActionService(
            ReviewActionQueryRepository repository,
            ReviewOperationIntentService intents) {
        this.repository = repository;
        this.intents = intents;
    }

    /** Builds the review queue model for the workspace and query. */
    public ReviewQueueModel queue(
            String ownerKey, WorkspacePageModel workspace, ReviewQuery query) {
        List<Long> accountIds = workspace.selectedAccountIds();
        long filteredCount = repository.count(accountIds, query);
        int totalPages = filteredCount == 0 ? 0
            : Math.toIntExact((filteredCount + ReviewQuery.PAGE_SIZE - 1)
                / ReviewQuery.PAGE_SIZE);
        if (query.page() > 0 && query.page() >= totalPages) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "review_page_not_found");
        }

        List<ReviewActionRow> pageRows = repository.page(accountIds, query).stream()
            .map(row -> row.withCancellationIntent(intents.issue(
                ownerKey, "CANCEL",
                ReviewOperationIntentService.subject(row.actionPublicId()))))
            .toList();
        Map<ReviewActionGroup.Kind, ReviewActionQueryRepository.GroupTotal> totals =
            new EnumMap<>(ReviewActionGroup.Kind.class);
        repository.groupTotals(accountIds, query)
            .forEach(total -> totals.put(total.kind(), total));

        List<ReviewActionGroup> groups = new ArrayList<>();
        for (ReviewActionGroup.Kind kind : ReviewActionGroup.Kind.values()) {
            var total = totals.getOrDefault(kind,
                new ReviewActionQueryRepository.GroupTotal(kind, 0, 0, 0, 0, 0));
            LinkedHashMap<String, List<ReviewActionRow>> byAccount = new LinkedHashMap<>();
            pageRows.stream().filter(row -> row.group() == kind).forEach(row ->
                byAccount.computeIfAbsent(row.accountLabel(), ignored -> new ArrayList<>())
                    .add(row));
            List<ReviewActionGroup.AccountGroup> accountGroups = byAccount.entrySet()
                .stream().map(entry -> new ReviewActionGroup.AccountGroup(
                    entry.getKey(), entry.getValue())).toList();
            groups.add(new ReviewActionGroup(
                kind, kind.label(), total.actionCount(), total.accountCount(),
                total.targetCount(), total.conflictCount(), total.eligibleCount(),
                accountGroups));
        }

        Map<String, String> clear = Map.of(
            "view", "queued", "account", query.account());
        return new ReviewQueueModel(
            workspace, query, repository.metrics(accountIds, query), pageRows, groups,
            filteredCount, totalPages,
            url(query.canonicalParameters()),
            url(clear),
            query.page() > 0 ? url(page(query, query.page() - 1)) : null,
            query.page() + 1 < totalPages ? url(page(query, query.page() + 1)) : null,
            null);
    }

    /** Returns the query parameters with the given page applied. */
    private static Map<String, String> page(ReviewQuery query, int page) {
        LinkedHashMap<String, String> values =
            new LinkedHashMap<>(query.withoutPage());
        if (page > 0) values.put("page", String.valueOf(page));
        return values;
    }

    /** Builds a /review URL from the given query parameters. */
    private static String url(Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/review");
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }
}
