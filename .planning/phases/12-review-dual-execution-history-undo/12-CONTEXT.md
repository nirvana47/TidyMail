# Phase 12: Review, Dual Execution Modes, History, And Undo - Context

**Gathered:** 2026-08-09
**Status:** Ready for research and planning
**Mode:** Autonomous conservative resolution; no user questions

<domain>
## Phase Boundary

Phase 12 turns the immutable exact scopes created by Phases 9-11 into one truthful
action system. It delivers staged Review and explicitly opted-in immediate execution,
conflict handling, selected/all approval, durable outcomes, action history, reversible
undo, and compensation guidance for non-reversible actions.

Phase 12 owns:

- Sender and unread decisions for archive, trash, mark-read, keep, protect, and review.
- Saving unsubscribe selections as exact account-owned scopes and executing the
  explicitly selected Phase 11 method.
- One shared exact-scope preflight and executor for staged and immediate modes.
- Queue grouping, removal, selected approval, eligible-all approval, conflicts, leases,
  idempotency, partial outcomes, verification-pending outcomes, history, and undo.
- Durable analytics-ready action, execution, event, target-outcome, conflict, and
  compensation facts.

Phase 12 does not own analytics dashboards or export UI (Phase 13), new provider
capabilities, message-body ingestion, automated resubscribe, or long-horizon
unsubscribe confirmation.
</domain>

<decisions>
## Locked Decisions

### One Action Model And One Executor

- `ActionScope` remains the immutable authority for account, action type, target kind,
  canonical exact provider IDs, count, hash, and bounded summaries. V8 and prior
  migrations are frozen and must not be edited.
- Staged and immediate modes differ only in dispatch timing:
  1. authorize the same owned scope;
  2. run the same compatibility, freshness, protection, conflict, cap, and
     method-specific preflight;
  3. create the same durable `UserAction` and execution intent;
  4. staged stops at `QUEUED`; immediate continues through the same executor.
- Immediate mode is never a different controller-to-provider shortcut. It cannot skip
  preview, exact-scope persistence, unsubscribe intent validation, conflict checks,
  protection checks, execution lease acquisition, or durable write-before-I/O facts.
- Staged is the default. Immediate requires an explicit, visible opt-in on the current
  confirmation form. The chosen mode is persisted on the action and never inferred
  from a query parameter, stale preference, hidden default, or action type.
- A request that chooses `REVIEW` creates a durable local review decision over the
  exact scope and remains in a `Needs decision` section until replaced by a new
  explicit action. It causes no provider mutation. `KEEP` is also a durable local
  decision with no provider call.
- New Phase 12 code replaces the legacy controller apply loop with a single
  `ExactScopeExecutor`. Legacy endpoints may delegate to it during migration but may
  not retain independent mutation semantics.

### Creation, Grouping, And Approval

- Every account produces its own action and scope. An `account=all` request may create
  a batch envelope for presentation, but no scope, action, lease, provider request, or
  undo operation spans accounts.
- Review groups queued actions by `ARCHIVE`, `TRASH`, `MARK_READ`, `UNSUBSCRIBE`,
  `KEEP`, and `PROTECT`, then by account. `REVIEW` appears separately as `Needs
  decision` and is never included in executable approval totals.
- Removing one queued action is a CSRF-protected cancellation. It never deletes the
  immutable scope or history and cannot cancel an executing or completed action.
- `Approve selected` accepts only opaque owned action public IDs explicitly selected
  by the user. Duplicate, foreign, stale, non-queued, or ineligible IDs fail the whole
  approval request before any execution is reserved.
- `Approve all eligible` means all conflict-free executable queued actions in the
  current authorized account filter at server evaluation time, not only the visible
  page. The confirmation displays a server-derived action/account/target count.
  Conflicted, needs-decision, cancelled, executing, and otherwise blocked rows remain
  queued and are reported as excluded.
- Multi-account approval locks account rows in ascending ID order and actions in
  ascending ID order. Reservation is atomic; execution is independently durable per
  account/action so one account failure cannot misstate another account's result.

### Preflight And Conflicts

- Preflight always reloads the owned action and scope, recomputes scope integrity, and
  rejects any account/action/type/ID/count/hash mismatch before reservation.
- Active overlap is computed from exact IDs, never summaries or browser counts.
  Conflicts are account-local; identical provider IDs in different accounts never
  conflict.
- Compatibility is allowlisted:
  - duplicate same-action/same-scope requests are deduplicated;
  - `ARCHIVE` and `MARK_READ` may coexist;
  - `TRASH` conflicts with archive, mark-read, keep, protect, and unsubscribe;
  - `KEEP` conflicts with archive, trash, mark-read, and unsubscribe;
  - `PROTECT` conflicts with trash and unsubscribe;
  - `UNSUBSCRIBE` conflicts with keep and protect;
  - unresolved `REVIEW` blocks execution for its overlapping scope.
- A conflict is a durable fact with the two action IDs, reason code, overlap count,
  creation time, and resolution. It stores no provider ID.
- Resolution never mutates an `ActionScope` or silently subtracts IDs. The user
  cancels one action, keeps both only where the matrix says compatible, or creates a
  new explicit action/scope. Approve-all skips unresolved conflicts.

### Locking, Leases, And Idempotency

- Database transactions never remain open during provider or outbound HTTP I/O.
  Reservation uses row locks, writes a durable execution and per-target `PENDING`
  ledger, assigns a bounded lease, then commits before dispatch.
- One forward execution is allowed per action. A unique database constraint enforces
  that rule. Undo is a separate operation with its own unique execution.
- Every mutation POST carries a server-issued opaque idempotency token bound to
  authenticated owner, operation, selected action set, and expiry. Only its SHA-256
  fingerprint is stored. Replays return the existing result and never dispatch twice.
- Concurrent workers acquire the action/execution row with pessimistic locking and a
  compare-and-set lease. A live lease returns `already in progress`.
- A crashed lease is not evidence of failure or success. Recovery marks dispatched
  unknown targets `VERIFICATION_PENDING`; it may automatically resume only targets
  durably proven not dispatched. Unsubscribe is never automatically retried after
  dispatch. Provider mutations may be manually retried only through the same
  idempotent exact-scope operation and only for targets with a known retry-safe state.

### Outcome Semantics

- Counts are durable and satisfy:
  `requested = succeeded + failed + verification_pending + not_dispatched`.
  `accepted` is an additional fact and may overlap `verification_pending`; it is not
  a synonym for succeeded.
- `REQUESTED` is the exact scope count. `ACCEPTED` means the downstream boundary
  accepted a target/request. `SUCCEEDED` requires affirmative completion evidence.
  `FAILED` requires a bounded reason. `VERIFICATION_PENDING` means the remote result
  is accepted or unknown but not independently established.
- Synchronous mailbox provider successes may be both accepted and succeeded.
  Per-target provider failures remain failed. A mixed result is `PARTIAL`.
- RFC 8058/legacy unsubscribe 2xx is accepted and verification-pending, not succeeded
  or confirmed. Phase 12 links to the append-only V8 attempt/events rather than
  duplicating or rewriting them.
- Local `KEEP`, `REVIEW`, and successful protection writes are succeeded without a
  mailbox provider call. Their history explicitly labels them as local decisions.
- Provider exceptions and malformed result sets cannot produce blanket failure or
  success. Known per-target facts are persisted; all dispatched-but-unresolved
  targets become verification-pending with a safe reason code.
- Events are append-only. Current summaries may be maintained transactionally for
  query speed, but no accepted, failed, succeeded, pending, undo, or compensation
  event is overwritten.
- Durable rows use bounded enums, counts, timestamps, mode, account, action/scope
  references, safe target summaries/fingerprints, reason codes, and source. They do
  not store exception text, response bodies, OAuth data, raw unsubscribe URI/header,
  DNS addresses, or arbitrary JSON.

### Undo And Compensation

- Mechanical undo is available only for completed `ARCHIVE`, `TRASH`, and
  `MARK_READ` forward targets that the Phase 12 target ledger records as
  `SUCCEEDED`.
- Undo resolves target ordinals against the immutable scope and sends only those
  applied IDs. It never uses requested IDs, accepted IDs, failed IDs, scope-wide
  `message_ids`, browser IDs, or a reconstructed query as the undo set.
- The default undo eligibility window is ten minutes from the forward execution's
  completion and is persisted as `undo_eligible_until`. Expiry, prior undo, foreign
  ownership, unresolved forward state, and zero applied IDs block undo before I/O.
- Undo has its own lease, idempotency token, per-target outcomes, partial status, and
  history events. A partial undo does not set a blanket `undone_at`; only confirmed
  inverse successes are marked reverted.
- `UNSUBSCRIBE` and `PROTECT` are not mechanically reversible. The UI explains why
  and offers compensation, not Undo:
  - unsubscribe: open the provider/source or account page and explain that
    resubscription is external and not guaranteed;
  - protect: create a new explicit `Remove protection` decision after fresh preflight.
- Compensation is a new durable action linked to the original. It never rewrites or
  erases the original fact and never claims restoration until its own outcome proves
  it.

### History And Analytics-Ready Facts

- History is an account-owned server-rendered read model over actions, executions,
  append-only events, conflicts, per-target outcome counts, undo, compensation, and
  linked unsubscribe outcomes.
- Required canonical filters are date range, owned account, action type, status, and
  target search. They combine, survive pagination, and fail closed on duplicate or
  invalid recognized parameters.
- Target search uses bounded approved metadata such as sender/list label, normalized
  address/domain, and safe target summary. It never searches or renders provider IDs,
  raw URI/query values, subject/body content beyond already approved UI metadata, or
  secret fingerprints.
- Default history order is event/action time descending, then stable action ID
  descending. Fixed 25-row pages use complete tie-breakers.
- Legacy actions remain visible with an explicit `Legacy outcome detail unavailable`
  label. Migration must not fabricate per-target success or analytics events for old
  rows.
- Phase 13 can aggregate the durable ledger directly by time, account, action, mode,
  current status, counts, reason code, and source without parsing display copy or
  provider IDs.

### Multi-Account Ownership And CSRF

- Every GET resolves the principal's owned accounts before query execution. Foreign
  account/action/scope/execution/conflict/undo IDs return 404 without revealing
  existence.
- Every POST that creates, opts into immediate mode, removes, approves, resolves,
  executes, undoes, compensates, or changes protection requires authentication and
  Spring Security CSRF.
- Opaque public IDs are locators, not authorization. The server always rejoins action,
  scope, execution, and account ownership.
- All-account operations resolve a server-owned set and split by account. A missing,
  disabled, or unsupported account fails reservation conservatively; it never falls
  back to the current account or broadens scope.

### UI And Privacy

- Reuse the Phase 9 workspace shell, local `workspace.css`, canonical GET forms,
  Thymeleaf, HTMX enhancement, semantic tables, and server-first fallback.
- The Review page has `Queued` and `History` views. Queued is the default and shows
  groups, conflicts, counts, selected/all approval, and non-executable review
  decisions. History contains completed/in-progress facts and filters.
- Provider message/thread IDs, exact scope IDs, raw unsubscribe destinations,
  authentication headers, tokens, target fingerprints, and idempotency/lease values
  never enter HTML, DOM attributes, URLs, hidden inputs, accessible names, logs, or
  analytics labels.
- Forms carry only opaque action public IDs, bounded enum choices, canonical filters,
  CSRF, and server-issued intent/idempotency tokens.
- No `href="#"`, JavaScript-only action, fabricated metric, or color-only status.
  Narrow controls are at least 44px and all dynamic results preserve focus/status
  semantics.

### Migration And Testing

- Add paired behaviorally equivalent V9 migrations after frozen V8. PostgreSQL 16 is
  production truth; H2 is the fast behavioral dialect.
- V9 is additive except for an explicit replacement of the `user_actions.status`
  check and bounded new columns. Preserve V1-V8 rows, IDs, scopes, and V8 checksums.
- Normalize per-target outcomes by scope ordinal plus fingerprint so no additional
  provider ID copy is required. Resolve provider IDs only server-side from the
  immutable owned scope.
- Test pure compatibility/state machines, H2 services/MVC/concurrency, PostgreSQL 16
  upgrade/constraints/locking/query parity, provider partial results, Phase 11
  unsubscribe linkage, privacy, CSRF, accessibility, and full regression.
- No production test may skip because PostgreSQL, Docker, or a provider mock is
  unavailable. Real mailbox/network mutation remains outside automated tests.
</decisions>

<code_context>
## Existing Foundations To Reuse

- `ActionScope`, `ActionScopeHasher`, and `ActionScopeService` provide immutable
  account-owned exact IDs, stable hashes, integrity checks, and typed summaries.
- `SelectionMaterializationService` and `SelectionPreviewTransactionService` provide
  individual/page/all-matching materialization, per-account scopes, repeatable-read
  preflight, account lock ordering, protected override audit, and the 1,000-ID cap.
- `UserAction`, `UserActionRepository`, `ReviewActionsController`, and `UndoService`
  are compatibility surfaces to migrate. Their current coarse statuses, all-queued
  apply loop, and scope-wide inverse data are insufficient as Phase 12 truth.
- `BulkActionResult` already carries succeeded IDs and per-ID failure reasons. The new
  executor must validate that every returned ID belongs to the exact requested set
  and classify missing/duplicate/foreign result IDs conservatively.
- `WorkspaceController`, `WorkspaceModelService`, workspace fragments, and
  `workspace.css` own authentication-aware shell, account scope, queue count, state,
  responsive tokens, and route GET behavior.
- V8 `unsubscribe_attempts`, `unsubscribe_outcome_events`, and
  `unsubscribe_intent_nonces` are frozen privacy-bounded facts. Phase 12 links to
  them; it does not duplicate URI or network detail.
- `ProtectedSender` and `ProtectedOverrideAudit` remain the canonical protection and
  override sources.

## Current Gaps To Replace

- `/api/actions/review/apply` executes every queued action for only the current account,
  holds no durable execution lease, ignores `BulkActionResult`, and marks blanket
  `APPLIED`.
- Current undo sends every requested ID and uses a scope-wide five-second/nullable
  compatibility window. It has no applied-ID ledger or partial inverse outcome.
- `UserAction.status` supports only `QUEUED`, `APPLIED`, and `CANCELLED`.
- Phase 10 selection scopes deliberately reject queueing and Phase 11 has no shared
  action integration yet.
- The current Review page is a Phase 9 placeholder; the compact mock is visual/content
  guidance only and contains decorative controls and sample data.
</code_context>

<canonical_refs>
## Canonical References

- `.planning/PROJECT.md`
- `.planning/ROADMAP.md`
- `.planning/REQUIREMENTS.md`
- `.planning/STATE.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-CONTEXT.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-RESEARCH.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-UI-SPEC.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-CONTEXT.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-RESEARCH.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-UI-SPEC.md`
- `.planning/phases/10-sender-and-unread-decision-surfaces/10-VERIFICATION.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-CONTEXT.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-RESEARCH.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-UI-SPEC.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-VALIDATION.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-01-SUMMARY.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-02-PLAN.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-03-PLAN.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-04-PLAN.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-05-PLAN.md`
- `src/main/resources/db/migration/V8__safe_unsubscribe.sql`
- `src/test/resources/db/test-migration/V8__safe_unsubscribe.sql`
- `src/main/java/com/tidymail/entity/ActionScope.java`
- `src/main/java/com/tidymail/entity/UserAction.java`
- `src/main/java/com/tidymail/service/ActionScopeService.java`
- `src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java`
- `src/main/java/com/tidymail/service/UndoService.java`
- `src/main/java/com/tidymail/controller/ReviewActionsController.java`
- `src/main/java/com/tidymail/mail/BulkActionResult.java`
- `src/main/resources/templates/review.html`
- `tidymail-ux-rehaul/pages/review-timeline-compact-suite.html`
</canonical_refs>

<deferred>
## Deferred

- Analytics charts, trend interpretation, and CSV export remain Phase 13.
- Real-browser integrated Gmail UAT remains Phase 13.
- Automated long-horizon unsubscribe confirmation remains future work.
- Outlook parity remains governed by `OUTL-01`.
</deferred>

## Requirement Coverage

| Requirement | Locked Phase 12 decision |
|---|---|
| SEND-06 | Sender scopes support staged/immediate archive, trash, mark-read, keep, protect, and review through one preflight/executor |
| UNRD-07 | Unread scopes use the same modes, exact scope, conflict, outcome, and review contracts |
| UNSB-07 | Candidate selection becomes account-owned exact actions; staged and immediate both retain Phase 11 method safety |
| ACTN-01 | Persisted explicit mode changes dispatch timing only; immediate cannot bypass preflight |
| ACTN-04 | Queued actions group by six required action types and account; review decisions are separate |
| ACTN-05 | Durable cancellation plus atomic selected and server-evaluated eligible-all approval |
| ACTN-06 | Exact-ID compatibility matrix, durable conflicts, and explicit non-mutating resolution |
| ACTN-07 | Per-target requested/accepted/succeeded/failed/verification-pending facts and partial summaries |
| ACTN-08 | Ten-minute undo for applied-ID-only archive/trash/mark-read with its own partial ledger |
| ACTN-09 | Unsubscribe/protect explain non-reversibility and create linked compensation actions |
| ACTN-10 | Durable history filters by date/account/type/status/target with stable pagination |

## Autonomous Resolution Note

The user requested no questions. Ambiguity was resolved toward one conservative,
auditable path: immutable exact scopes are never edited; no external side effect is
treated as exactly once; unknown results remain verification-pending; and convenience
features cannot weaken ownership, CSRF, preflight, privacy, or outcome truth.
