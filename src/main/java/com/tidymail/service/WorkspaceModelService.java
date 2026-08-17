package com.tidymail.service;

import com.tidymail.dto.AccountScopeOption;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import com.tidymail.entity.SyncProgress;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Builds the shared workspace page model rendered across all pages: resolves the
 * selected account scope, aggregates per-account sync progress into an overall
 * state, and supplies account options, queued counts, and unread counts.
 *
 * <p>Product functionality supported: the workspace shell shared across the
 * Senders, Unread, Unsubscribe, Review, Activity, and Analytics pages.
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceModelService {

    static final Duration FRESHNESS_WINDOW = Duration.ofMinutes(15);
    private static final String JOB_TYPE = MessageMetadataSyncService.JOB_TYPE;
    private static final String LEGACY_JOB_TYPE = "INCREMENTAL_SYNC";

    /** A per-account summary of sync progress and indexed count. */
    public record AccountSummary(
        Long accountId,
        long indexedCount,
        long scannedCount,
        long pagesCompleted,
        String latestStatus,
        Instant lastSuccessfulAt,
        String safeDetail
    ) {
    }

    /** The aggregate workspace sync state across selected accounts. */
    public record Aggregate(
        WorkspaceState state,
        long indexedCount,
        long scannedCount,
        long pagesCompleted,
        Instant lastSuccessfulAt,
        String safeDetail,
        boolean syncActive,
        boolean neverStarted,
        boolean completedEmpty
    ) {
    }

    private final OwnedAccountScopeService accountScopeService;
    private final UserAccountRepository accountRepository;
    private final UserActionRepository actionRepository;
    private final SyncProgressRepository progressRepository;
    private final MessageMetadataQueryService metadataQueryService;
    private final DecisionSurfaceQueryRepository decisionQueryRepository;
    private final Clock clock;

    @Autowired
    public WorkspaceModelService(
            OwnedAccountScopeService accountScopeService,
            UserAccountRepository accountRepository,
            UserActionRepository actionRepository,
            SyncProgressRepository progressRepository,
            MessageMetadataQueryService metadataQueryService,
            DecisionSurfaceQueryRepository decisionQueryRepository) {
        this(accountScopeService, accountRepository, actionRepository, progressRepository,
            metadataQueryService, decisionQueryRepository, Clock.systemUTC());
    }

    WorkspaceModelService(
            OwnedAccountScopeService accountScopeService,
            UserAccountRepository accountRepository,
            UserActionRepository actionRepository,
            SyncProgressRepository progressRepository,
            MessageMetadataQueryService metadataQueryService,
            DecisionSurfaceQueryRepository decisionQueryRepository,
            Clock clock) {
        this.accountScopeService = accountScopeService;
        this.accountRepository = accountRepository;
        this.actionRepository = actionRepository;
        this.progressRepository = progressRepository;
        this.metadataQueryService = metadataQueryService;
        this.decisionQueryRepository = decisionQueryRepository;
        this.clock = clock;
    }

    /** Builds the workspace model for a route and account selection. */
    public WorkspacePageModel build(
            WorkspaceRoute route,
            String accountSelection,
            OAuth2AuthenticationToken authentication) {
        List<Long> selectedIds = accountScopeService.resolve(authentication, accountSelection);
        return buildResolved(route, accountSelection, selectedIds, authentication);
    }

    /** Builds the workspace model from already-resolved account IDs. */
    public WorkspacePageModel buildResolved(
            WorkspaceRoute route,
            String accountSelection,
            List<Long> resolvedAccountIds,
            OAuth2AuthenticationToken authentication) {
        List<Long> selectedIds = requireResolvedScope(accountSelection, resolvedAccountIds);
        List<UserAccount> selectedAccounts = accountRepository.findAllByIdInOrderByIdAsc(selectedIds);
        if (selectedAccounts.size() != selectedIds.size()) {
            throw new IllegalArgumentException("Resolved account scope no longer exists");
        }
        String principalEmail = normalizedEmail(authentication);
        List<UserAccount> allOwned = accountRepository.findAllByOwnerKeyOrderByIdAsc(
            selectedAccounts.getFirst().getOwnerKey());

        String selectedQuery = String.valueOf(selectedIds.getFirst());
        String selectedLabel = accountLabel(selectedAccounts.getFirst());

        Map<Long, List<SyncProgress>> progressByAccount = new LinkedHashMap<>();
        selectedIds.forEach(accountId ->
            progressByAccount.put(accountId, new ArrayList<>()));
        for (String jobType : List.of(JOB_TYPE, LEGACY_JOB_TYPE)) {
            progressRepository.findByAccountIdInAndJobTypeOrderByIdDesc(selectedIds, jobType)
                .forEach(progress -> progressByAccount.get(progress.getAccountId()).add(progress));
        }
        progressByAccount.values().forEach(rows ->
            rows.sort(Comparator.comparing(SyncProgress::getId).reversed()));

        List<AccountSummary> summaries = selectedIds.stream()
            .map(accountId -> summary(accountId, progressByAccount.get(accountId)))
            .toList();
        Aggregate aggregate = aggregate(summaries, clock.instant());

        Long queuedCount;
        try {
            queuedCount = actionRepository.countByAccountIdInAndStatus(selectedIds, "QUEUED");
        } catch (RuntimeException unavailable) {
            queuedCount = null;
            aggregate = new Aggregate(
                aggregate.indexedCount() > 0 ? WorkspaceState.PARTIAL : WorkspaceState.ERROR,
                aggregate.indexedCount(), aggregate.scannedCount(), aggregate.pagesCompleted(),
                aggregate.lastSuccessfulAt(), "Queued action status is temporarily unavailable.",
                aggregate.syncActive(), aggregate.neverStarted(), aggregate.completedEmpty());
        }

        UnreadScopeCounts unreadScopeCounts = unreadScopeCounts(selectedIds);

        String displayName = authentication.getPrincipal().getAttribute("name");
        if (!StringUtils.hasText(displayName)) {
            displayName = principalEmail;
        }

        return new WorkspacePageModel(
            route,
            displayName,
            principalEmail,
            accountOptions(allOwned, selectedQuery),
            selectedIds,
            selectedQuery,
            selectedLabel,
            queuedCount,
            unreadScopeCounts,
            aggregate.indexedCount(),
            aggregate.scannedCount(),
            aggregate.pagesCompleted(),
            aggregate.safeDetail(),
            aggregate.lastSuccessfulAt(),
            aggregate.state(),
            aggregate.syncActive(),
            aggregate.neverStarted(),
            aggregate.completedEmpty());
    }

    /** Returns the unread scope counts, or a pending marker if unavailable. */
    public UnreadScopeCounts unreadScopeCounts(List<Long> accountIds) {
        try {
            return decisionQueryRepository.unreadScopeCounts(accountIds);
        } catch (RuntimeException unavailable) {
            return UnreadScopeCounts.pending();
        }
    }

    /** Validates that resolved account IDs match the workspace selection. */
    private static List<Long> requireResolvedScope(
            String accountSelection, List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Resolved account scope is required");
        }
        List<Long> resolved = accountIds.stream().distinct().sorted().toList();
        String selection = accountSelection == null ? "" : accountSelection.trim();
        boolean current = selection.isEmpty()
            || "current".equalsIgnoreCase(selection)
            || "default".equalsIgnoreCase(selection);
        if ("all".equalsIgnoreCase(selection)
                || resolved.size() != 1
                || !current && !selection.equals(Long.toString(resolved.getFirst()))) {
            throw new IllegalArgumentException(
                "Resolved account scope does not match workspace selection");
        }
        return resolved;
    }

    /** Builds a per-account summary from its sync progress rows. */
    private AccountSummary summary(Long accountId, List<SyncProgress> progressRows) {
        long indexed = metadataQueryService.freshness(List.of(accountId)).messageCount();
        if (progressRows.isEmpty()) {
            return new AccountSummary(accountId, indexed, 0, 0, null, null, null);
        }
        SyncProgress latest = progressRows.getFirst();
        boolean active = progressRows.stream().anyMatch(progress ->
            "QUEUED".equals(progress.getStatus()) || "RUNNING".equals(progress.getStatus()));
        Instant lastSuccessfulAt = progressRows.stream()
            .map(SyncProgress::getLastSuccessfulAt)
            .filter(value -> value != null)
            .max(Comparator.naturalOrder())
            .orElse(null);
        String safeDetail = progressRows.stream()
            .map(SyncProgress::getErrorMessage)
            .map(WorkspaceModelService::safeDetail)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);
        return new AccountSummary(
            accountId,
            indexed,
            value(latest.getMessagesScanned()),
            value(latest.getPagesCompleted()),
            active ? "RUNNING" : latest.getStatus(),
            lastSuccessfulAt,
            safeDetail);
    }

    /** Aggregates account summaries into an overall workspace state. */
    public static Aggregate aggregate(List<AccountSummary> summaries, Instant now) {
        if (summaries == null || summaries.isEmpty()) {
            throw new IllegalArgumentException("At least one account summary is required");
        }
        long indexed = summaries.stream().mapToLong(AccountSummary::indexedCount).sum();
        long scanned = summaries.stream().mapToLong(AccountSummary::scannedCount).sum();
        long pages = summaries.stream().mapToLong(AccountSummary::pagesCompleted).sum();
        boolean active = summaries.stream().anyMatch(summary ->
            "QUEUED".equals(summary.latestStatus()) || "RUNNING".equals(summary.latestStatus()));
        boolean failed = summaries.stream().anyMatch(summary ->
            "FAILED".equals(summary.latestStatus()));
        boolean incomplete = summaries.stream().anyMatch(summary ->
            summary.latestStatus() == null
                || "PARTIAL".equals(summary.latestStatus())
                || "FAILED".equals(summary.latestStatus())
                || "QUEUED".equals(summary.latestStatus())
                || "RUNNING".equals(summary.latestStatus()));
        boolean neverStarted = summaries.stream().allMatch(summary -> summary.latestStatus() == null);
        boolean completedEmpty = indexed == 0 && summaries.stream().allMatch(summary ->
            "COMPLETE".equals(summary.latestStatus()));
        Instant leastRecentSuccess = summaries.stream()
            .map(AccountSummary::lastSuccessfulAt)
            .filter(value -> value != null)
            .min(Comparator.naturalOrder())
            .orElse(null);
        boolean missingSuccess = summaries.stream()
            .anyMatch(summary -> summary.lastSuccessfulAt() == null);
        String detail = summaries.stream()
            .map(AccountSummary::safeDetail)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);

        WorkspaceState state;
        if (indexed == 0) {
            if (active) {
                state = WorkspaceState.LOADING;
            } else if (failed) {
                state = WorkspaceState.ERROR;
            } else {
                state = WorkspaceState.EMPTY;
            }
        } else if (incomplete) {
            state = WorkspaceState.PARTIAL;
        } else if (missingSuccess
            || leastRecentSuccess == null
            || leastRecentSuccess.isBefore(now.minus(FRESHNESS_WINDOW))) {
            state = WorkspaceState.STALE;
        } else {
            state = WorkspaceState.READY;
        }
        return new Aggregate(
            state, indexed, scanned, pages, leastRecentSuccess, detail, active,
            neverStarted, completedEmpty);
    }

    /** Builds the account-scope options for the workspace nav. */
    private static List<AccountScopeOption> accountOptions(
            List<UserAccount> accounts, String selectedQuery) {
        List<AccountScopeOption> options = new ArrayList<>();
        accounts.forEach(account -> options.add(new AccountScopeOption(
            String.valueOf(account.getId()),
            accountLabel(account),
            account.getProvider(),
            String.valueOf(account.getId()).equals(selectedQuery))));
        return List.copyOf(options);
    }

    /** Formats an account label for display. */
    private static String accountLabel(UserAccount account) {
          return account.getEmail();
    }

    /** Returns the normalized principal email. */
    private static String normalizedEmail(OAuth2AuthenticationToken authentication) {
        String email = authentication.getPrincipal().getAttribute("email");
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Returns the integer value, defaulting to zero when null. */
    private static long value(Integer value) {
        return value == null ? 0 : value.longValue();
    }

    /** Sanitizes a sync error detail for safe display. */
    static String safeDetail(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String lowered = raw.toLowerCase(Locale.ROOT);
        if (lowered.contains("token")
                || lowered.contains("http://")
                || lowered.contains("https://")
                || lowered.contains("authorization:")
                || lowered.contains("header")
                || lowered.contains("\n")
                || lowered.contains("\r")) {
            return "Metadata sync encountered a provider error.";
        }
        String compact = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(compact.length(), 160));
    }
}
