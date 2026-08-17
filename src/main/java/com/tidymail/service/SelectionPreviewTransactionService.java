package com.tidymail.service;

import com.tidymail.dto.ActionScopePreview;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.dto.SenderQuery;
import com.tidymail.dto.SelectionRequest;
import com.tidymail.dto.SelectionScopeDigest;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.dto.SelectionScopePreview.State;
import com.tidymail.dto.SelectionSurface;
import com.tidymail.dto.UnreadQuery;
import com.tidymail.entity.UserAccount;
import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.DecisionSurfaceQueryRepository.AccountMaterialization;
import com.tidymail.repository.DecisionSurfaceQueryRepository.MaterializationResult;
import com.tidymail.repository.DecisionSurfaceQueryRepository.SelectionLocator;
import com.tidymail.service.DecisionRowKey.UnreadSenderLocator;
import com.tidymail.repository.MessageMetadataRepository;
import com.tidymail.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * Materializes bulk-action selections into exact scopes within repeatable-read
 * transactions: computes the preview (with protection/cap/ambiguity checks),
 * records protected overrides, and prepares per-sender cleanup stages with
 * confirmation intents.
 *
 * <p>Product functionality supported: the <em>Bulk Action Preview</em> flow's
 * exact-scope materialization, preview, and cleanup staging.
 */
public class SelectionPreviewTransactionService {
    private static final int SCOPE_LIMIT = 1000;

    private final UserAccountRepository accountRepository;
    private final DecisionSurfaceQueryRepository queryRepository;
    private final MessageMetadataRepository metadataRepository;
    private final ProtectedOverrideAuditService auditService;
    private final ActionScopeService actionScopeService;
    private final ActionConfirmationIntentService confirmationIntents;
    private final Clock clock;

    @Autowired
    public SelectionPreviewTransactionService(
            UserAccountRepository accountRepository,
            DecisionSurfaceQueryRepository queryRepository,
            MessageMetadataRepository metadataRepository,
            ProtectedOverrideAuditService auditService,
            ActionScopeService actionScopeService,
            ActionConfirmationIntentService confirmationIntents) {
        this(accountRepository, queryRepository, metadataRepository,
            auditService, actionScopeService,
            confirmationIntents, Clock.systemUTC());
    }

    SelectionPreviewTransactionService(
            UserAccountRepository accountRepository,
            DecisionSurfaceQueryRepository queryRepository,
            MessageMetadataRepository metadataRepository,
            ProtectedOverrideAuditService auditService,
            ActionScopeService actionScopeService,
            ActionConfirmationIntentService confirmationIntents,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.queryRepository = queryRepository;
        this.metadataRepository = metadataRepository;
        this.auditService = auditService;
        this.actionScopeService = actionScopeService;
        this.confirmationIntents = confirmationIntents;
        this.clock = clock;
    }

    /** Materializes a selection and returns its exact-scope preview. */
    @Transactional(
        propagation = Propagation.REQUIRED,
        isolation = Isolation.REPEATABLE_READ)
    public SelectionScopePreview createPreview(
            List<Long> authorizedAccountIds,
            SelectionRequest request,
            List<SelectionLocator> locators) {
        List<Long> orderedIds = authorizedAccountIds.stream().distinct().sorted().toList();
        List<UserAccount> locked =
            accountRepository.findAllForUpdateByIdInOrderByIdAsc(orderedIds);
        if (locked.size() != orderedIds.size()
                || !locked.stream().map(UserAccount::getId).toList().equals(orderedIds)) {
            throw new IllegalArgumentException("Authorized account scope changed");
        }
        Map<Long, UserAccount> accounts = new LinkedHashMap<>();
        locked.forEach(account -> accounts.put(account.getId(), account));

        MaterializationResult materialized = preflight(
            orderedIds, request, locators, clock.instant());
        AccountMaterialization overCap = materialized.accounts().stream()
            .filter(account -> account.exactMessageCount() > SCOPE_LIMIT)
            .findFirst()
            .orElse(null);
        if (overCap != null) {
            return result(
                State.CAP_EXCEEDED, List.of(), materialized, request,
                accountLabel(accounts.get(overCap.accountId())),
                overCap.exactMessageCount(), null);
        }
        for (AccountMaterialization account : materialized.accounts()) {
            if (account.messageIds().size() != account.exactMessageCount()) {
                throw new IllegalStateException("Exact-count and cap guard disagree");
            }
        }
        if (request.action() == SelectionRequest.PreviewAction.PROTECT) {
            AccountMaterialization ambiguous = materialized.accounts().stream()
                .filter(account -> account.includedTargetCount() > 1)
                .findFirst().orElse(null);
            if (request.surface() != SelectionSurface.SENDERS || ambiguous != null) {
                return result(
                    State.INVALID, List.of(), materialized, request, null, 0,
                    "Protect one sender at a time so the protected address is unambiguous.");
            }
        }
        long exactMessages = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::exactMessageCount).sum();
        long protectedMessages = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::protectedMessageCount).sum();
        if (exactMessages == 0) {
            State state = !request.includeProtected() && protectedMessages > 0
                ? State.PROTECTED_BLOCKED : State.EMPTY;
            return result(state, List.of(), materialized, request, null, 0, null);
        }

        if (request.includeProtected()) {
            materialized.accounts().stream()
                .filter(account -> account.protectedMessageCount() > 0)
                .forEach(account -> auditService.insert(
                    account.accountId(), request,
                    account.protectedSenderCount(), account.protectedMessageCount()));
        }

        List<SelectionScopeDigest> digests = new ArrayList<>();
        List<ActionConfirmationIntentService.ScopeBinding> bindings = new ArrayList<>();
        for (AccountMaterialization account : materialized.accounts()) {
            if (account.exactMessageCount() == 0) continue;
            int selectedTargets = Math.toIntExact(account.includedTargetCount());
            List<String> warningCodes = warnings(account, request.includeProtected());
            ActionScopePreview scope = actionScopeService.createOrGetRequired(
                account.accountId(),
                request.action().name(),
                request.surface() == SelectionSurface.SENDERS
                    ? "SENDER_SELECTION" : "UNREAD_SELECTION",
                account.messageIds(),
                new ActionScopeTargetSummary(
                    selectedTargets + (request.surface() == SelectionSurface.SENDERS
                        ? " selected senders" : " selected unread messages"),
                    null,
                    null,
                    selectedTargets),
                new ActionScopeRiskSummary(
                    request.includeProtected()
                        ? Math.toIntExact(account.protectedMessageCount()) : 0,
                    Math.toIntExact(account.highRiskCount()),
                    warningCodes));
            digests.add(new SelectionScopeDigest(
                accountLabel(accounts.get(account.accountId())),
                scope.scopeHash(),
                scope.messageCount()));
            bindings.add(new ActionConfirmationIntentService.ScopeBinding(
                account.accountId(), scope.scopeHash()));
        }
        String ownerKey = locked.getFirst().getEmail().strip().toLowerCase()
            + ":" + locked.getFirst().getProvider();
        String confirmation = confirmationIntents.issue(
            ownerKey,
            ActionType.valueOf(request.action().name()),
            request.surface() == SelectionSurface.SENDERS
                ? DecisionSource.SENDERS : DecisionSource.UNREAD,
            bindings);
        return result(
            State.READY, digests, materialized, request, null, 0, null, confirmation);
    }

    /** Prepares per-sender cleanup stages for a Senders selection. */
    @Transactional(
        propagation = Propagation.REQUIRED,
        isolation = Isolation.REPEATABLE_READ)
    public CleanupPreparation prepareCleanupStages(
            String ownerKey,
            List<Long> authorizedAccountIds,
            SelectionRequest request,
            List<SelectionLocator> locators) {
        if (ownerKey == null || ownerKey.isBlank()
                || request.surface() != SelectionSurface.SENDERS
                || request.mode() != com.tidymail.dto.SelectionMode.INDIVIDUAL
                || request.includeProtected()
                || locators == null
                || locators.isEmpty()) {
            throw new IllegalArgumentException(
                "Cleanup staging requires individual unprotected sender selections");
        }
        List<Long> orderedIds = authorizedAccountIds.stream().distinct().sorted().toList();
        List<UserAccount> locked =
            accountRepository.findAllForUpdateByIdInOrderByIdAsc(orderedIds);
        if (locked.size() != orderedIds.size()
                || !locked.stream().map(UserAccount::getId).toList().equals(orderedIds)) {
            throw new IllegalArgumentException("Authorized account scope changed");
        }
        Map<Long, UserAccount> accounts = new LinkedHashMap<>();
        locked.forEach(account -> accounts.put(account.getId(), account));

        List<SelectionLocator> orderedLocators = locators.stream()
            .distinct()
            .sorted(Comparator.comparingLong(SelectionLocator::accountId)
                .thenComparing(SelectionLocator::target))
            .toList();
        if (orderedLocators.size() != locators.size()) {
            throw new IllegalArgumentException("Cleanup sender selections must be unique");
        }

        MaterializationResult materialized = queryRepository.materializeSenders(
            orderedIds,
            request.senderQuery(),
            request.mode(),
            orderedLocators,
            false,
            clock.instant(),
            request.analyticsSenderScope());
        Set<AccountMessageId> union = new LinkedHashSet<>();
        for (AccountMaterialization account : materialized.accounts()) {
            if (account.messageIds().size() != account.exactMessageCount()) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cleanup action exceeds the exact 1,000-message limit");
            }
            account.messageIds().forEach(messageId ->
                union.add(new AccountMessageId(account.accountId(), messageId)));
            if (union.size() > SCOPE_LIMIT) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cleanup action exceeds the distinct 1,000-message limit");
            }
        }
        Map<AccountSender, List<String>> idsBySender = new LinkedHashMap<>();
        for (AccountMaterialization account : materialized.accounts()) {
            metadataRepository.findByAccountIdAndProviderMessageIdIn(
                    account.accountId(), account.messageIds())
                .forEach(row -> {
                    String sender = normalizeSender(row.getFromAddress());
                    idsBySender.computeIfAbsent(
                        new AccountSender(account.accountId(), sender),
                        ignored -> new ArrayList<>())
                        .add(row.getProviderMessageId());
                });
        }
        List<SenderMaterialization> senders = new ArrayList<>();
        for (SelectionLocator locator : orderedLocators) {
            if (!accounts.containsKey(locator.accountId())) {
                throw new IllegalArgumentException(
                    "Selection row is outside the authorized scope");
            }
            List<String> messageIds = idsBySender.getOrDefault(
                new AccountSender(locator.accountId(), locator.target()), List.of());
            if (messageIds.isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Selected sender is protected or has no matching messages");
            }
            senders.add(new SenderMaterialization(
                locator.target(), locator.accountId(), messageIds));
        }

        List<PreparedCleanupStage> prepared = new ArrayList<>();
        for (SenderMaterialization sender : senders) {
            ActionScopePreview scope = actionScopeService.createOrGetRequired(
                sender.accountId(),
                request.action().name(),
                "SENDER_SELECTION",
                sender.messageIds(),
                new ActionScopeTargetSummary(
                    sender.senderAddress(),
                    sender.senderAddress(),
                    senderDomain(sender.senderAddress()),
                    1),
                new ActionScopeRiskSummary(
                    0,
                    0,
                    List.of()));
            String confirmation = confirmationIntents.issue(
                ownerKey,
                ActionType.valueOf(request.action().name()),
                DecisionSource.SENDERS,
                List.of(new ActionConfirmationIntentService.ScopeBinding(
                    sender.accountId(), scope.scopeHash())));
            prepared.add(new PreparedCleanupStage(
                sender.accountId(), sender.senderAddress(), scope, confirmation));
        }
        return new CleanupPreparation(prepared, union.size());
    }

    /** Prepares per-sender cleanup stages for an Unread selection. */
    @Transactional(
        propagation = Propagation.REQUIRED,
        isolation = Isolation.REPEATABLE_READ)
    public CleanupPreparation prepareUnreadCleanupStages(
            String ownerKey,
            List<Long> authorizedAccountIds,
            UnreadQuery query,
            SelectionRequest.PreviewAction action,
            List<UnreadSenderLocator> locators) {
        if (ownerKey == null || ownerKey.isBlank() || query == null
                || action == null || locators == null || locators.isEmpty()) {
            throw new IllegalArgumentException(
                "Unread cleanup staging requires query and sender selections");
        }
        List<Long> orderedIds = authorizedAccountIds.stream().distinct().sorted().toList();
        List<UserAccount> locked =
            accountRepository.findAllForUpdateByIdInOrderByIdAsc(orderedIds);
        if (locked.size() != orderedIds.size()
                || !locked.stream().map(UserAccount::getId).toList().equals(orderedIds)) {
            throw new IllegalArgumentException("Authorized account scope changed");
        }
        List<UnreadSenderLocator> orderedLocators = locators.stream()
            .distinct()
            .sorted(Comparator.comparingLong(UnreadSenderLocator::accountId)
                .thenComparing(UnreadSenderLocator::senderKey)
                .thenComparing(UnreadSenderLocator::categoryKey))
            .toList();
        if (orderedLocators.stream()
                .anyMatch(locator -> !orderedIds.contains(locator.accountId()))) {
            throw new IllegalArgumentException(
                "Unread selection row is outside the authorized scope");
        }

        MaterializationResult materialized = queryRepository.materializeUnreadSenders(
            orderedIds, query, orderedLocators, clock.instant());
        Set<AccountMessageId> union = new LinkedHashSet<>();
        for (AccountMaterialization account : materialized.accounts()) {
            if (account.messageIds().size() != account.exactMessageCount()) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unread cleanup action exceeds the exact 1,000-message limit");
            }
            account.messageIds().forEach(messageId ->
                union.add(new AccountMessageId(account.accountId(), messageId)));
            if (union.size() > SCOPE_LIMIT) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unread cleanup action exceeds the distinct 1,000-message limit");
            }
        }

        Map<AccountSender, List<String>> idsBySender = new LinkedHashMap<>();
        for (AccountMaterialization account : materialized.accounts()) {
            metadataRepository.findByAccountIdAndProviderMessageIdIn(
                    account.accountId(), account.messageIds())
                .forEach(row -> {
                    String sender = normalizeSender(row.getFromAddress());
                    idsBySender.computeIfAbsent(
                        new AccountSender(account.accountId(), sender),
                        ignored -> new ArrayList<>())
                        .add(row.getProviderMessageId());
                });
        }
        List<AccountSender> selectedSenders = orderedLocators.stream()
            .map(locator -> new AccountSender(
                locator.accountId(), locator.senderKey()))
            .distinct()
            .toList();
        List<PreparedCleanupStage> prepared = new ArrayList<>();
        for (AccountSender sender : selectedSenders) {
            List<String> messageIds =
                idsBySender.getOrDefault(sender, List.of());
            if (messageIds.isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Selected unread sender is protected or has no matching Inbox-unread messages");
            }
            ActionScopePreview scope = actionScopeService.createOrGetRequired(
                sender.accountId(),
                action.name(),
                "UNREAD_SELECTION",
                messageIds,
                new ActionScopeTargetSummary(
                    sender.senderAddress(),
                    sender.senderAddress(),
                    senderDomain(sender.senderAddress()),
                    1),
                new ActionScopeRiskSummary(0, 0, List.of()));
            String confirmation = confirmationIntents.issue(
                ownerKey,
                ActionType.valueOf(action.name()),
                DecisionSource.UNREAD,
                List.of(new ActionConfirmationIntentService.ScopeBinding(
                    sender.accountId(), scope.scopeHash())));
            prepared.add(new PreparedCleanupStage(
                sender.accountId(), sender.senderAddress(), scope, confirmation));
        }
        return new CleanupPreparation(prepared, union.size());
    }

    /** Materializes the selection via the query repository. */
    private MaterializationResult preflight(
            List<Long> accountIds,
            SelectionRequest request,
            List<SelectionLocator> locators,
            Instant now) {
        return request.surface() == SelectionSurface.SENDERS
            ? queryRepository.materializeSenders(
                accountIds, request.senderQuery(), request.mode(), locators,
                request.includeProtected(), now, request.analyticsSenderScope())
            : queryRepository.materializeUnread(
                accountIds, request.unreadQuery(), request.mode(), locators,
                request.includeProtected(), now);
    }

    /** Builds the warning codes for an account's materialization. */
    private static List<String> warnings(
            AccountMaterialization account, boolean includeProtected) {
        List<String> warnings = new ArrayList<>();
        if (includeProtected && account.protectedMessageCount() > 0) {
            warnings.add("PROTECTED_OVERRIDE");
        }
        if (account.highRiskCount() > 0) warnings.add("HIGH_RISK_TARGETS");
        return List.copyOf(warnings);
    }

    /** Builds a preview result without a confirmation. */
    private static SelectionScopePreview result(
            State state,
            List<SelectionScopeDigest> digests,
            MaterializationResult materialized,
            SelectionRequest request,
            String capAccountLabel,
            long capCount,
            String error) {
        return result(
            state, digests, materialized, request, capAccountLabel, capCount, error, null);
    }

    /** Builds a preview result with aggregated counts and a confirmation. */
    private static SelectionScopePreview result(
            State state,
            List<SelectionScopeDigest> digests,
            MaterializationResult materialized,
            SelectionRequest request,
            String capAccountLabel,
            long capCount,
            String error,
            String confirmation) {
        long targets = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::includedTargetCount).sum();
        long messages = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::exactMessageCount).sum();
        long protectedSenders = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::protectedSenderCount).sum();
        long protectedMessages = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::protectedMessageCount).sum();
        long highRisk = materialized.accounts().stream()
            .mapToLong(AccountMaterialization::highRiskCount).sum();
        long excludedSenders = request.includeProtected() ? 0 : protectedSenders;
        long excludedMessages = request.includeProtected() ? 0 : protectedMessages;
        return new SelectionScopePreview(
            state, digests, targets, messages, protectedSenders, protectedMessages,
            highRisk, excludedSenders, excludedMessages, capAccountLabel, capCount,
            error, confirmation);
    }

    /** Formats an account label for display. */
    private static String accountLabel(UserAccount account) {
        return account.getEmail() + " (Gmail)";
    }

    /** Extracts the domain from a sender address. */
    private static String senderDomain(String senderAddress) {
        int separator = senderAddress.lastIndexOf('@');
        return separator < 0 || separator == senderAddress.length() - 1
            ? null : senderAddress.substring(separator + 1);
    }

    /** Normalizes a sender address for grouping. */
    private static String normalizeSender(String senderAddress) {
        return senderAddress == null || senderAddress.isBlank()
            ? "__missing__"
            : senderAddress.strip().toLowerCase(Locale.ROOT);
    }

    /** A sender's materialized message IDs for one account. */
    private record SenderMaterialization(
        String senderAddress,
        long accountId,
        List<String> messageIds
    ) {
        private SenderMaterialization {
            messageIds = List.copyOf(messageIds);
        }
    }

    /** A distinct (account, message) pair. */
    private record AccountMessageId(long accountId, String messageId) {
    }

    /** A distinct (account, sender) pair. */
    private record AccountSender(long accountId, String senderAddress) {
    }

    /** One prepared cleanup stage with its scope and confirmation. */
    public record PreparedCleanupStage(
        long accountId,
        String senderAddress,
        ActionScopePreview scope,
        String confirmation
    ) {
    }

    /** The prepared cleanup stages and distinct message count. */
    public record CleanupPreparation(
        List<PreparedCleanupStage> stages,
        int distinctMessageCount
    ) {
        public CleanupPreparation {
            stages = List.copyOf(stages);
            if (stages.isEmpty() || distinctMessageCount < 1 || distinctMessageCount > SCOPE_LIMIT) {
                throw new IllegalArgumentException("Valid cleanup preparation is required");
            }
        }

        @Override
        public List<PreparedCleanupStage> stages() {
            return List.copyOf(stages);
        }
    }
}
