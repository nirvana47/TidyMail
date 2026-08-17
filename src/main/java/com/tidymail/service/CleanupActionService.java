package com.tidymail.service;

import com.tidymail.dto.SelectionMode;
import com.tidymail.dto.SelectionRequest;
import com.tidymail.dto.SelectionRequest.PreviewAction;
import com.tidymail.dto.SelectionSurface;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.DecisionSurfaceQueryRepository.SelectionLocator;
import com.tidymail.service.DecisionRowKey.UnreadSenderLocator;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.SelectionPreviewTransactionService.CleanupPreparation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

@Service
public class CleanupActionService {
    public static final String TRASH_RECOVERY_NOTICE =
        "Messages moved to Gmail Trash can typically be restored for up to 30 days.";

    private final SelectionPreviewTransactionService previews;
    private final ActionCommandService commands;
    private final UserActionRepository actions;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<Long, ReentrantLock> accountLocks =
        new ConcurrentHashMap<>();

    public CleanupActionService(
            SelectionPreviewTransactionService previews,
            ActionCommandService commands,
            UserActionRepository actions,
            PlatformTransactionManager transactionManager) {
        this.previews = previews;
        this.commands = commands;
        this.actions = actions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(
            TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public StageResult stage(
            String ownerKey,
            List<Long> authorizedAccountIds,
            StageRequest request) {
        requireRequest(request);
        List<Long> orderedAccountIds =
            authorizedAccountIds.stream().distinct().sorted().toList();
        List<ReentrantLock> locks = orderedAccountIds.stream()
            .map(accountId -> accountLocks.computeIfAbsent(
                accountId, ignored -> new ReentrantLock()))
            .toList();
        locks.forEach(ReentrantLock::lock);
        try {
            return transactions.execute(status ->
                stageTransaction(
                    ownerKey, orderedAccountIds, request));
        } finally {
            for (int index = locks.size() - 1; index >= 0; index--) {
                locks.get(index).unlock();
            }
        }
    }

    private StageResult stageTransaction(
            String ownerKey,
            List<Long> authorizedAccountIds,
            StageRequest request) {
        CleanupPreparation prepared;
        if (request.surface() == SelectionSurface.SENDERS) {
            List<SelectionLocator> locators =
                normalizedSenderLocators(request.rowKeys(), authorizedAccountIds);
            SelectionRequest selection = new SelectionRequest(
                SelectionSurface.SENDERS,
                SelectionMode.INDIVIDUAL,
                request.senderQuery(),
                null,
                locators.stream()
                    .map(locator -> DecisionRowKey.sender(
                        locator.accountId(), locator.target()))
                    .toList(),
                request.action(),
                false,
                null);
            prepared = previews.prepareCleanupStages(
                ownerKey, authorizedAccountIds, selection, locators);
        } else {
            List<UnreadSenderLocator> locators =
                normalizedUnreadLocators(request.rowKeys(), authorizedAccountIds);
            prepared = previews.prepareUnreadCleanupStages(
                ownerKey, authorizedAccountIds, request.unreadQuery(),
                request.action(), locators);
        }

        List<SenderStage> staged = new ArrayList<>();
        for (var candidate : prepared.stages()) {
            boolean reused = !actions.findActiveForUpdate(
                candidate.accountId(),
                candidate.scope().scopeId(),
                request.action().name()).isEmpty();
            ActionCommandService.CommandResult command = commands.submit(
                ownerKey,
                authorizedAccountIds,
                candidate.confirmation(),
                "STAGED",
                false);
            if (command.actions().size() != 1) {
                throw new IllegalStateException(
                    "Per-sender cleanup staging must reconcile one action");
            }
            UserAction action = command.actions().getFirst();
            staged.add(new SenderStage(
                candidate.senderAddress(),
                action.getPublicId(),
                candidate.scope().scopeHash(),
                candidate.scope().messageCount(),
                reused));
        }
        long reviewCount = authorizedAccountIds.stream()
            .mapToLong(accountId -> actions.countByAccountIdAndStatus(
                accountId, "QUEUED"))
            .sum();
        return new StageResult(
            request.requestToken(),
            request.action(),
            staged,
            prepared.distinctMessageCount(),
            reviewCount,
            request.action() == PreviewAction.TRASH
                ? TRASH_RECOVERY_NOTICE : null);
    }

    private static void requireRequest(StageRequest request) {
        if (request == null
                || request.rowKeys() == null
                || request.rowKeys().isEmpty()
                || request.requestToken() == null) {
            throw new IllegalArgumentException("Complete cleanup staging request is required");
        }
        boolean senderRequest = request.surface() == SelectionSurface.SENDERS
            && request.senderQuery() != null && request.unreadQuery() == null;
        boolean unreadRequest = request.surface() == SelectionSurface.UNREAD
            && request.unreadQuery() != null && request.senderQuery() == null;
        if (!senderRequest && !unreadRequest) {
            throw new IllegalArgumentException(
                "Cleanup surface and canonical query must match");
        }
        if (request.action() != PreviewAction.ARCHIVE
                && request.action() != PreviewAction.TRASH
                && request.action() != PreviewAction.MARK_READ) {
            throw new IllegalArgumentException("Unsupported cleanup action");
        }
        if (request.action() == PreviewAction.TRASH != request.deleteAcknowledged()) {
            throw new IllegalArgumentException(
                request.action() == PreviewAction.TRASH
                    ? "Delete recovery acknowledgement is required"
                    : "Delete recovery acknowledgement is only valid for Delete");
        }
    }

    private static List<SelectionLocator> normalizedSenderLocators(
            List<String> rowKeys,
            List<Long> authorizedAccountIds) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        LinkedHashMap<String, SelectionLocator> unique = new LinkedHashMap<>();
        for (String rowKey : rowKeys) {
            DecisionRowKey.Locator decoded =
                DecisionRowKey.decode(SelectionSurface.SENDERS, rowKey);
            if (!authorizedAccountIds.contains(decoded.accountId())) {
                throw new IllegalArgumentException(
                    "Selection row is outside the authorized scope");
            }
            SelectionLocator locator =
                new SelectionLocator(decoded.accountId(), decoded.target());
            unique.putIfAbsent(
                locator.accountId() + "\n" + locator.target(), locator);
        }
        return unique.values().stream()
            .sorted(Comparator.comparingLong(SelectionLocator::accountId)
                .thenComparing(SelectionLocator::target))
            .toList();
    }

    private static List<UnreadSenderLocator> normalizedUnreadLocators(
            List<String> rowKeys,
            List<Long> authorizedAccountIds) {
        if (authorizedAccountIds == null || authorizedAccountIds.isEmpty()) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        LinkedHashMap<String, UnreadSenderLocator> unique = new LinkedHashMap<>();
        for (String rowKey : rowKeys) {
            UnreadSenderLocator decoded =
                DecisionRowKey.decodeUnreadSender(rowKey);
            if (!authorizedAccountIds.contains(decoded.accountId())) {
                throw new IllegalArgumentException(
                    "Unread selection row is outside the authorized scope");
            }
            unique.putIfAbsent(
                decoded.accountId() + "\n" + decoded.categoryKey()
                    + "\n" + decoded.senderKey(),
                decoded);
        }
        return unique.values().stream()
            .sorted(Comparator.comparingLong(UnreadSenderLocator::accountId)
                .thenComparing(UnreadSenderLocator::senderKey)
                .thenComparing(UnreadSenderLocator::categoryKey))
            .toList();
    }

    public record StageRequest(
        SelectionSurface surface,
        SenderQuery senderQuery,
        UnreadQuery unreadQuery,
        PreviewAction action,
        List<String> rowKeys,
        UUID requestToken,
        boolean deleteAcknowledged
    ) {
        public StageRequest(
                SenderQuery query,
                PreviewAction action,
                List<String> rowKeys,
                UUID requestToken,
                boolean deleteAcknowledged) {
            this(
                SelectionSurface.SENDERS, query, null, action, rowKeys,
                requestToken, deleteAcknowledged);
        }

        public StageRequest {
            rowKeys = rowKeys == null ? List.of() : List.copyOf(rowKeys);
        }

        @Override
        public List<String> rowKeys() {
            return List.copyOf(rowKeys);
        }
    }

    public record SenderStage(
        String senderAddress,
        UUID actionPublicId,
        String scopeHash,
        int exactMessageCount,
        boolean reused
    ) {
    }

    public record StageResult(
        UUID requestToken,
        PreviewAction action,
        List<SenderStage> senders,
        int distinctMessageCount,
        long reviewCount,
        String recoveryNotice
    ) {
        public StageResult {
            senders = List.copyOf(senders);
        }

        @Override
        public List<SenderStage> senders() {
            return List.copyOf(senders);
        }
    }
}
