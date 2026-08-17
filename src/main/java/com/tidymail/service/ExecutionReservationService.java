package com.tidymail.service;

import com.tidymail.action.ActionReasonCode;
import com.tidymail.action.ActionStatus;
import com.tidymail.action.ExecutionStatus;
import com.tidymail.action.OperationKind;
import com.tidymail.entity.ActionEvent;
import com.tidymail.entity.ActionExecution;
import com.tidymail.entity.ActionExecutionTarget;
import com.tidymail.entity.ActionScopeTarget;
import com.tidymail.entity.UserAccount;
import com.tidymail.entity.UserAction;
import com.tidymail.repository.ActionEventRepository;
import com.tidymail.repository.ActionExecutionRepository;
import com.tidymail.repository.ActionExecutionTargetRepository;
import com.tidymail.repository.ActionScopeTargetRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionReservationService {
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final UserAccountRepository accounts;
    private final UserActionRepository actions;
    private final ActionScopeTargetRepository scopeTargets;
    private final ActionExecutionRepository executions;
    private final ActionExecutionTargetRepository executionTargets;
    private final ActionEventRepository events;
    private final ActionPreflightService preflight;
    private final ExecutionRecoveryService recovery;
    private final Clock clock;

    @Autowired
    public ExecutionReservationService(
            UserAccountRepository accounts,
            UserActionRepository actions,
            ActionScopeTargetRepository scopeTargets,
            ActionExecutionRepository executions,
            ActionExecutionTargetRepository executionTargets,
            ActionEventRepository events,
            ActionPreflightService preflight,
            ExecutionRecoveryService recovery) {
        this(accounts, actions, scopeTargets, executions, executionTargets,
            events, preflight, recovery, Clock.systemUTC());
    }

    ExecutionReservationService(
            UserAccountRepository accounts,
            UserActionRepository actions,
            ActionScopeTargetRepository scopeTargets,
            ActionExecutionRepository executions,
            ActionExecutionTargetRepository executionTargets,
            ActionEventRepository events,
            ActionPreflightService preflight,
            ExecutionRecoveryService recovery,
            Clock clock) {
        this.accounts = accounts;
        this.actions = actions;
        this.scopeTargets = scopeTargets;
        this.executions = executions;
        this.executionTargets = executionTargets;
        this.events = events;
        this.preflight = preflight;
        this.recovery = recovery;
        this.clock = clock;
    }

    @Transactional
    public Reservation reserve(
            long accountId, UUID actionPublicId, UUID idempotencyToken) {
        return reserveBatch(List.of(
            new ReservationRequest(accountId, actionPublicId, idempotencyToken)))
            .getFirst();
    }

    @Transactional
    public List<Reservation> reserveBatch(List<ReservationRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Reservation requests are required");
        }
        Set<String> unique = new HashSet<>();
        for (ReservationRequest request : requests) {
            if (request == null || request.accountId() <= 0
                    || request.actionPublicId() == null
                    || request.idempotencyToken() == null
                    || !unique.add(request.accountId() + ":" + request.actionPublicId())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "duplicate_or_invalid_action");
            }
        }
        List<Long> accountIds = requests.stream().map(ReservationRequest::accountId)
            .distinct().sorted().toList();
        List<UserAccount> lockedAccounts =
            accounts.findAllForUpdateByIdInOrderByIdAsc(accountIds);
        if (lockedAccounts.size() != accountIds.size()) throw notFound();

        List<UUID> publicIds = requests.stream()
            .map(ReservationRequest::actionPublicId).toList();
        List<UserAction> lockedActions =
            actions.findAllForUpdateByAccountIdInAndPublicIdIn(accountIds, publicIds);
        if (lockedActions.size() != requests.size()) throw notFound();
        Map<String, UserAction> byLocator = new HashMap<>();
        lockedActions.forEach(action -> byLocator.put(
            action.getAccountId() + ":" + action.getPublicId(), action));

        Instant now = clock.instant();
        List<RequestAndAction> ordered = requests.stream()
            .map(request -> new RequestAndAction(request, byLocator.get(
                request.accountId() + ":" + request.actionPublicId())))
            .sorted(Comparator.comparingLong(
                    (RequestAndAction value) -> value.action().getAccountId())
                .thenComparing(value -> value.action().getId()))
            .toList();
        ArrayList<Reservation> reserved = new ArrayList<>();
        for (RequestAndAction item : ordered) {
            if (item.action() == null) throw notFound();
            reserved.add(reserveLocked(
                item.action(), item.request().idempotencyToken(), now));
        }
        return List.copyOf(reserved);
    }

    private Reservation reserveLocked(
            UserAction action, UUID idempotencyToken, Instant now) {
        long accountId = action.getAccountId();
        UUID actionPublicId = action.getPublicId();
        String idempotency = ActionFingerprints.bind(
            "execution-idempotency-v1", accountId, actionPublicId, idempotencyToken);
        ActionExecution existing = executions
            .findByAccountIdAndIdempotencyFingerprint(accountId, idempotency)
            .orElseGet(() -> executions.findByActionIdAndOperationKind(
                action.getId(), OperationKind.FORWARD.name()).orElse(null));
        if (existing != null) {
            var recovered = recovery.recover(accountId, existing.getId());
            return new Reservation(
                recovered.executionId(), recovered.executionPublicId(),
                recovered.actionId(), recovered.actionPublicId(),
                recovered.accountId(), recovered.scopeId(), recovered.actionType(),
                recovered.leaseFingerprint(), recovered.dispatchRequired());
        }

        ActionPreflightService.PreflightResult checked = preflight.check(action);
        String lease = ActionFingerprints.bind(
            "execution-lease-v1", UUID.randomUUID(), accountId, action.getId(), now);
        ActionExecution execution = executions.saveAndFlush(ActionExecution.reserve(
            accountId, action.getId(), OperationKind.FORWARD, idempotency, lease,
            checked.scope().getMessageCount(), now, now.plus(LEASE)));
        List<ActionScopeTarget> targets = scopeTargets
            .findByScopeIdAndAccountIdOrderByScopeOrdinal(
                checked.scope().getId(), accountId);
        if (targets.size() != checked.scope().getMessageCount()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "scope_target_index_incompatible");
        }
        executionTargets.saveAll(
            targets.stream().map(target -> ActionExecutionTarget.pending(
                execution.getId(), target)).toList());
        action.setStatus(ActionStatus.EXECUTING.name());
        actions.save(action);
        events.save(ActionEvent.from(
            action, execution, "EXECUTION_RESERVED", null, now));
        return receipt(execution, action, true);
    }

    @Transactional
    public Reservation markDispatched(long accountId, long executionId, String lease) {
        ActionExecution execution = executions.findForUpdate(executionId, accountId)
            .orElseThrow(ExecutionReservationService::notFound);
        Instant now = clock.instant();
        if (lease == null || !lease.equals(execution.getLeaseFingerprint())
                || execution.executionStatus() != ExecutionStatus.RESERVED
                || execution.getLeaseExpiresAt() == null
                || !now.isBefore(execution.getLeaseExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale_execution_lease");
        }
        execution.start(now);
        executions.save(execution);
        List<ActionExecutionTarget> targets = executionTargets
            .findByExecutionIdAndAccountIdOrderByScopeOrdinal(executionId, accountId);
        targets.forEach(target -> target.markDispatched(now));
        executionTargets.saveAll(targets);
        UserAction action = actions.findByIdAndAccountId(
            execution.getActionId(), accountId).orElseThrow(
                ExecutionReservationService::notFound);
        events.save(ActionEvent.from(
            action, execution, "EXECUTION_STARTED", null, now));
        return receipt(execution, action, true);
    }

    private static Reservation receipt(
            ActionExecution execution, UserAction action, boolean dispatchRequired) {
        return new Reservation(
            execution.getId(), execution.getPublicId(), action.getId(),
            action.getPublicId(), action.getAccountId(), action.getActionScopeId(),
            action.getActionType(), execution.getLeaseFingerprint(), dispatchRequired);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
    }

    public record Reservation(
        long executionId,
        UUID executionPublicId,
        long actionId,
        UUID actionPublicId,
        long accountId,
        long scopeId,
        String actionType,
        String leaseFingerprint,
        boolean dispatchRequired
    ) {
    }

    public record ReservationRequest(
        long accountId,
        UUID actionPublicId,
        UUID idempotencyToken
    ) {
    }

    private record RequestAndAction(
        ReservationRequest request,
        UserAction action
    ) {
    }
}
