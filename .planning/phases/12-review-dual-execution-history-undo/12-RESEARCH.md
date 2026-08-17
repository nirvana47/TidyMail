# Phase 12: Review, Dual Execution Modes, History, And Undo - Research

**Researched:** 2026-08-09
**Scope:** Architecture and implementation inputs only
**Schema baseline:** Frozen PostgreSQL/H2 V8

## Executive Finding

Phase 12 should not extend the current `ReviewActionsController` loop incrementally.
That loop loses provider partial results, has no reservation lease, applies every
queued row for one account, and marks blanket `APPLIED`. The safe design is an
additive V9 execution ledger around existing immutable `ActionScope` rows.

Both staged and immediate requests must call one command path:

```text
materialize/reload exact scope
  -> authorize + preflight
  -> create UserAction + typed intent
  -> staged: QUEUED
  -> immediate/approval: reserve execution
  -> commit PENDING targets + lease
  -> dispatch exact scope
  -> validate provider/transport result
  -> append outcomes + release lease
```

The source surface and chosen mode never call a provider directly.

## Inherited Facts

### Phase 9

- `ActionScopeHasher` hashes version, account, action type, target kind, and sorted
  deduplicated exact IDs with length-prefixed UTF-8.
- `ActionScope` is immutable and account-owned. `UserAction.action_scope_id` has a
  composite account FK.
- Preview, queue, apply, and legacy undo already have compatibility assertions.
- Exact provider IDs are persisted server-side but intentionally excluded from HTML.

### Phase 10

- Sender/unread selection supports individual, visible-page, and all-matching modes.
- Materialization reruns canonical server queries under repeatable read, locks account
  rows in ascending order, produces one scope per account, and enforces 1,000 IDs per
  account without truncation.
- `SENDER_SELECTION` and `UNREAD_SELECTION` are preview-only until Phase 12.
- Protection override is explicit, CSRF-protected, server-counted, and audited.

### Phase 11 And Frozen V8

- Unsubscribe candidates are account-owned and provider-free on browse/inspection.
- V8 contains derived DKIM safety facts plus privacy-bounded attempts, append-only
  outcome events, and one-time intent nonces.
- RFC 8058 and legacy GET require explicit method choice and fresh safety checks.
  Accepted requests become verification-pending, not confirmed.
- V8 is frozen. Phase 12 must link to V8 attempts/events and add V9; rewriting V8
  would break applied checksums and invalidate the completed upgrade evidence.

## Recommended Domain Model

### `UserAction`

Keep `user_actions` as the durable user decision and compatibility anchor. Add:

| Column | Purpose |
|---|---|
| `public_id` | Random opaque locator used in routes/forms |
| `execution_mode` | `STAGED` or `IMMEDIATE` |
| `lifecycle_status` | Expanded current status |
| `decision_source` | `SENDERS`, `UNREAD`, `UNSUBSCRIBE`, `REVIEW`, `LEGACY` |
| `batch_public_id` | Optional presentation envelope for one user command |
| `undo_eligible_until` | Persisted eligibility boundary |
| `completed_at` | Stable history time |

Replace the V5 status check in V9 with:

```text
QUEUED
BLOCKED
EXECUTING
APPLIED
PARTIAL
VERIFICATION_PENDING
FAILED
CANCELLED
UNDONE
PARTIALLY_UNDONE
COMPENSATED
```

Keep the existing `status` Java/database field if renaming would add migration risk;
the important point is one allowlisted lifecycle vocabulary. Existing
`QUEUED/APPLIED/CANCELLED` rows remain valid.

### Typed Action Intent

`ActionScope.target_summary` is display-oriented and is not enough to execute
unsubscribe or protection safely. Add `action_intents` with a one-to-one composite
account/action FK:

| Field | Notes |
|---|---|
| `action_id`, `account_id` | Ownership |
| `subject_kind` | `MESSAGES`, `SENDER`, `UNSUBSCRIBE_CANDIDATE`, `LOCAL_REVIEW` |
| `safe_target_label` | Bounded approved history label |
| `target_search_key` | Bounded normalized sender/domain/list search value |
| `normalized_sender_address` | Only for protect/unprotect; nullable |
| `unsubscribe_candidate_fingerprint` | Lowercase SHA-256; nullable |
| `unsubscribe_method` | V8 method enum; nullable |
| `unsubscribe_intent_nonce_id` | Owned V8 nonce FK when execution requires it |
| `compensates_action_id` | Original action for compensation; nullable |

Use columns and checks, not arbitrary JSON. Never store a full destination URI,
provider ID, raw header, OAuth value, or response body here.

### Execution Ledger

`action_executions` represents one reserved operation:

| Field | Notes |
|---|---|
| `id`, `public_id`, `account_id`, `action_id` | Composite ownership |
| `operation_kind` | `FORWARD`, `UNDO`, `COMPENSATION` |
| `status` | `RESERVED`, `RUNNING`, `APPLIED`, `PARTIAL`, `VERIFICATION_PENDING`, `FAILED` |
| `idempotency_fingerprint` | SHA-256 only; unique per account/operation |
| `lease_fingerprint`, `lease_expires_at` | Bounded worker ownership |
| `requested_count` | Exact target count |
| `accepted_count` | Downstream accepted count |
| `succeeded_count` | Affirmatively complete count |
| `failed_count` | Known failed count |
| `verification_pending_count` | Accepted/unknown count |
| `not_dispatched_count` | Reserved but not sent |
| timestamps and safe reason | Analytics-ready |

Constraints should enforce nonnegative counts, each count no greater than requested,
and:

```text
requested_count =
  succeeded_count + failed_count
  + verification_pending_count + not_dispatched_count
accepted_count <= requested_count
```

`accepted_count` is intentionally not part of the partition because an accepted
target can be succeeded or verification-pending.

Unique indexes:

- one `FORWARD` execution per action;
- one `UNDO` execution per action;
- `(account_id, idempotency_fingerprint)`;
- `(account_id, status, started_at desc, id desc)`.

### Per-Target Outcomes

`action_execution_targets` stores:

- execution/account composite FK;
- zero-based `scope_ordinal`;
- SHA-256 target fingerprint;
- `dispatch_state`: `NOT_DISPATCHED` or `DISPATCHED`;
- status: `PENDING`, `ACCEPTED`, `SUCCEEDED`, `FAILED`,
  `VERIFICATION_PENDING`, `REVERTED`, or `COMPENSATED`;
- bounded reason/source and timestamps;
- nullable owned V8 `unsubscribe_attempt_id`.

Use `(execution_id, scope_ordinal)` as the unique identity. Do not duplicate provider
IDs. At dispatch or undo, resolve each ordinal against the immutable owned
`ActionScope.messageIds`. A target fingerprint detects ordinal/scope corruption.

### Append-Only Events And Conflicts

`action_events` should include action, optional execution, account, event type,
resulting status, execution mode, aggregate counts, bounded reason/source, and
occurred time. Events are append-only at the service layer and indexed by account/time,
action/time, type/time, and status/time.

`action_conflicts` should include account, canonical lower/higher action IDs, reason,
overlap count, `OPEN/RESOLVED`, resolution code, and timestamps. A unique open-pair
constraint prevents duplicate conflict cards. Never store overlapping provider IDs.

These tables give Phase 13 stable facts without parsing mutable UI copy.

## Shared Executor Architecture

Recommended service boundaries:

```java
interface ActionPreflightService {
    PreflightResult check(OwnedAction action, Operation operation);
}

interface ExactScopeExecutor {
    ExecutionReceipt execute(long accountId, UUID actionPublicId,
                             UUID idempotencyToken);
}

interface ActionHandler {
    ActionType supports();
    HandlerResult execute(ExecutionContext context);
}
```

Handlers:

- `MailboxMutationHandler`: archive, trash, mark-read.
- `LocalDecisionHandler`: keep and review, with zero provider calls.
- `ProtectionHandler`: account-owned protected-sender write.
- `UnsubscribeActionHandler`: delegates to the completed Phase 11 intent/execution
  service and links its V8 attempt/events.

Do not add unsubscribe HTTP methods to `MailProvider`.

### Reservation Transaction

1. Resolve principal-owned account and action by public ID.
2. Lock action, execution, and required account row in deterministic order.
3. Revalidate scope hash, account, type, IDs, count, target kind, and typed intent.
4. Recompute active conflicts and method/protection/provider eligibility.
5. Find existing execution by action or idempotency fingerprint; return it if present.
6. Insert execution, one target row per scope ordinal, and `EXECUTION_RESERVED` event.
7. Mark action `EXECUTING`, set lease, and commit.

External I/O starts only after step 7 commits.

### Completion Transaction

1. Lock execution and verify the lease fingerprint.
2. Validate handler output:
   - every returned target belongs to the exact request;
   - no duplicate ID;
   - succeeded and failed sets are disjoint;
   - all requested targets are accounted for or classified unknown.
3. Update target outcome rows and aggregate counts.
4. Append outcome event(s).
5. Set action/execution summary and undo eligibility when applicable.
6. Clear lease and commit.

Unknown or malformed handler output fails closed. Known facts remain; dispatched
unresolved targets become verification-pending.

## Exact Handler Semantics

### Archive, Trash, Mark Read

- Construct provider requests only from immutable scope IDs selected by target
  ordinals.
- Split only if provider API limits require it; scope identity remains unchanged.
- Convert `BulkActionResult.succeededMessageIds` to `SUCCEEDED` and
  `failedMessageIdToReason` to bounded `FAILED` reasons.
- Any requested ID absent from both result collections after dispatch becomes
  `VERIFICATION_PENDING`, not assumed failure.
- Any returned ID outside the request is a contract violation and never enters
  history or undo.

### Keep And Review

- `KEEP` writes local succeeded target facts and can close/cancel incompatible queued
  actions only through an explicit conflict-resolution command.
- `REVIEW` records local succeeded decision facts but remains non-executable in the
  Review `Needs decision` section until a user chooses another action. It must not be
  counted in approve-all.

### Protect

- Revalidate normalized sender ownership from typed intent and scope metadata.
- Upsert the account-owned `protected_senders` row idempotently.
- Mark local success without calling `MailProvider`.
- Removal is a later compensation action, never an edit to the original event.

### Unsubscribe

- One action should represent one candidate/method so each irreversible destination
  receives distinct consent, intent, attempt, and outcome.
- Revalidate V8 nonce, candidate fingerprint, method, current source, protection, and
  destination safety immediately before reservation/dispatch.
- Delegate one request to Phase 11 transport. Link the target row to the V8 attempt.
- V8 `ACCEPTED` plus `VERIFICATION_PENDING` maps to accepted count 1, succeeded 0,
  verification-pending 1.
- Never retry after a dispatched timeout, redirect, or unknown result.

## Conflict Detection

For correctness at the current 1,000-ID cap, compare active account-owned scopes by
exact set intersection. PostgreSQL may use `unnest(message_ids)` or an additive
normalized scope-ordinal index in V9; H2 can use Java/repository parity logic.

Prefer materializing `action_scope_targets(scope_id, account_id, scope_ordinal,
target_fingerprint)` in V9 for indexed overlap without duplicating provider IDs.
Backfill it from existing V6 scopes and verify fingerprints against the scope hash
input. It supports:

- fast overlap count;
- account-local conflict query;
- target-ordinal ledger FK;
- provider-ID-free conflict persistence.

The executor still resolves actual IDs from `ActionScope`, preserving one authority.

## Approval Semantics

### Selected

- Parse a bounded set of unique action public IDs.
- Resolve all ownership and eligibility before reserving any.
- Lock accounts/actions in stable order.
- If one selected item is stale, foreign, conflicted, or not queued, reserve none.
- After reservation, execute each action independently and return a batch summary.

### All Eligible

- Query all queued executable actions in the current authorized account filter with a
  server count and deterministic order.
- Re-evaluate under lock at confirmation.
- Reserve conflict-free rows; leave excluded rows unchanged with reason counts.
- The command is bounded by a configured maximum action count. Over-limit returns
  guidance to narrow account/type filters; it never truncates silently.

## Undo Architecture

Undo selection query:

```text
forward action is owned
AND type IN (ARCHIVE, TRASH, MARK_READ)
AND now < undo_eligible_until
AND no prior undo execution
AND target status = SUCCEEDED
```

Map only those target ordinals to scope IDs. Build the inverse from the forward type:

- archive -> add `INBOX`;
- trash -> remove `TRASH`;
- mark-read -> `MARK_UNREAD`.

Persist an undo execution and target rows before I/O. Record per-target reverted,
failed, or verification-pending facts. Never call the current
`UndoService.executeUndo` with the whole `UserAction.messageIds` for new actions.

Legacy null-scope actions may retain the old compatibility path only when their
existing expiry/inverse data is valid. They must be labeled legacy and cannot be
represented as Phase 12 applied-ID proof.

## History Query Architecture

Typed query:

| Parameter | Contract |
|---|---|
| `view` | `queued` or `history` |
| `account` | owned ID or `all` |
| `from`, `to` | ISO local dates interpreted as UTC bounds; max bounded range |
| `action` | all plus required action enums |
| `status` | all plus allowlisted lifecycle/outcome groups |
| `target` | bounded normalized safe target search |
| `page` | nonnegative bounded integer |

Use one fixed-fragment, bound-parameter query pipeline for count, metrics, and page.
Default history sort is `occurred_at desc, action_id desc`. Return DTOs with public
action ID, account label, action/mode/status, safe target label, counts, timestamps,
undo/compensation affordances, and safe reason summaries. DTOs must not contain exact
IDs or secret fingerprints.

## V9 Migration Plan

Create equivalent:

- `V9__action_execution_history_and_undo.sql`
- test dialect `V9__action_execution_history_and_undo.sql`

Migration order:

1. Add bounded `user_actions` columns and backfill public IDs/mode/source/status.
2. Replace status check while retaining all old values.
3. Create typed intents, scope target fingerprints, executions, execution targets,
   append-only events, and conflicts.
4. Add composite account FKs and uniqueness constraints.
5. Backfill scope target ordinal/fingerprint rows without fabricating outcomes.
6. Add account/status/time, account/type/time, action/time, execution/status, conflict,
   and target-search indexes.

PostgreSQL should use UUID, `TIMESTAMPTZ`, regex checks, and partial/functional indexes
where useful. H2 should use behaviorally equivalent types/checks and explicit parity
tests. Do not force syntax identity.

Legacy backfill:

- mode `STAGED`;
- source `LEGACY`;
- retain current lifecycle;
- public ID generated;
- no execution target/event rows claiming success;
- history labels outcome detail unavailable.

## Security And Privacy

- Spring Security CSRF on every mutation route, including immediate opt-in, queue,
  cancel, selected/all approval, conflict resolution, undo, compensation, and
  protect/unprotect.
- GET routes are read-only and provider/network-free.
- Public IDs and idempotency tokens are unguessable locators but never authorization.
- Account composite FKs exist on every child table.
- Logs contain public action ID, safe enum/reason, account internal ID, and counts only.
- Never render or log provider IDs, exact scope IDs, target fingerprints, full
  unsubscribe URI/query, raw headers, DNS answers, OAuth values, response content,
  lease tokens, or idempotency tokens.

## Testing Architecture

### Pure Unit

- Compatibility matrix symmetry and every action pair.
- Lifecycle and per-target outcome state machines.
- Count invariants and malformed provider result classification.
- Idempotency fingerprint binding and expiry.
- Undo applied-ordinal selection.
- Safe history filter parser and copy mapping.

### H2 Integration And MVC

- V1-V9 clean migration and V8-to-V9 upgrade.
- Exact staged/immediate parity through one executor spy.
- Queue grouping, cancellation, selected/all approval, conflict resolution.
- Concurrent duplicate submit and lease behavior.
- Partial provider results and verification-pending recovery.
- Multi-account ownership/ordering/rollback/reservation.
- CSRF and foreign public-ID rejection.
- History filter combinations, stable pagination, accessibility, and DOM privacy.

### PostgreSQL 16 Testcontainers

- Production V1-V9 and actual V8-to-V9 upgrades with V8 checksum unchanged.
- Checks, composite FKs, unique forward/undo/idempotency constraints, indexes, and
  Hibernate validation.
- Concurrent reservation, idempotency, lease takeover, and conflict overlap queries.
- H2/PostgreSQL history/filter/count parity.
- Deterministic 10,000-metadata/representative-action query budget under the inherited
  two-second warm target.

### Provider And Phase 11 Contracts

- Capture exact provider request IDs and compare with scope ordinals.
- Partial/missing/duplicate/foreign provider result IDs.
- No provider call for keep, review, protect, GET pages, or failed preflight.
- Unsubscribe immediate/staged paths both consume fresh Phase 11 intent and append V8
  events; no automatic fallback/retry and no V8 rewrite.
- Undo provider request equals only forward `SUCCEEDED` ordinals.

## Requirement-To-Architecture Map

| Requirement | Primary architecture evidence |
|---|---|
| SEND-06 | Phase 10 sender scopes adopted by typed actions and shared handlers |
| UNRD-07 | Phase 10 unread scopes adopted without changing scope semantics |
| UNSB-07 | Candidate/method intent, one candidate action, V8-linked handler |
| ACTN-01 | Persisted mode with identical command/preflight/reservation/executor path |
| ACTN-04 | Grouped Review query/read model |
| ACTN-05 | Cancellation plus atomic selected and bounded eligible-all reservation |
| ACTN-06 | Scope-target overlap index, compatibility matrix, durable conflicts |
| ACTN-07 | Execution and target ledgers with partitioned counts and safe reasons |
| ACTN-08 | Undo execution from succeeded ordinals only |
| ACTN-09 | Typed compensation links and non-reversible copy |
| ACTN-10 | Indexed typed history query and append-only events |

## Planning Recommendation

Split plans by invariant:

1. V9 migration, domain state machines, scope-target index, and exact executor
   reservation/completion core.
2. Sender/unread/unsubscribe command integration and staged/immediate parity.
3. Review grouping, conflicts, cancellation, selected/all approval, and CSRF.
4. Partial outcomes, history, applied-ID-only undo, and compensation.
5. PostgreSQL concurrency/parity, privacy, accessibility, performance, and full
   regression closure.

The executor and ledger must be complete before immediate mode or approval controls
are enabled.
