---
phase: 12-review-dual-execution-history-undo
status: verified
requirements:
  - SEND-06
  - UNRD-07
  - UNSB-07
  - ACTN-01
  - ACTN-04
  - ACTN-05
  - ACTN-06
  - ACTN-07
  - ACTN-08
  - ACTN-09
  - ACTN-10
automated_architecture:
  - pure-unit
  - h2-integration
  - mockmvc
  - postgres-16-testcontainers
  - provider-contract
  - phase-11-transport-contract
manual_browser_blocking: false
---

# Phase 12: Validation Strategy

## Validation Goal

Prove that staged and opt-in immediate modes preserve one immutable exact scope and
one safety/execution path; approvals and conflicts are account-owned and race-safe;
outcomes record only known per-target facts; undo uses only IDs proven applied; and
history/compensation remain truthful, private, accessible, and queryable.

Full-suite green is necessary but insufficient. Every mapped requirement needs named
positive evidence plus negative ownership, CSRF, privacy, concurrency, provider
result, stale-state, and mode-bypass evidence.

## Core Invariants

1. Immediate mode never calls a provider before the same preflight, durable action,
   execution reservation, target ledger, and lease used by staged approval.
2. The provider/transport request target set equals the immutable owned scope or the
   explicitly ledger-selected applied subset for undo.
3. No external I/O occurs inside the reservation/completion database transaction.
4. Duplicate/concurrent POSTs produce one forward dispatch per action.
5. Unknown external results are verification-pending, never fabricated success.
6. History events are append-only and legacy rows are not backfilled with invented
   per-target outcomes.
7. Provider IDs and network secrets never cross the presentation/log boundary.

## Test Layers

### Layer 1: Pure Deterministic Tests

No Spring context, database, provider, DNS, HTTP, or wall clock.

Target:

- action compatibility matrix;
- lifecycle and execution state machines;
- count invariant calculator;
- provider result validator;
- idempotency binding/fingerprint;
- target ordinal/fingerprint resolver;
- undo eligibility/applied-ordinal selector;
- history query parser;
- safe reason/copy mapper.

Use fixed clocks and table-driven cases. Property tests should cover arbitrary
requested/succeeded/failed/missing result partitions and matrix symmetry.

### Layer 2: H2 Migration, Repository, Service, And MVC

H2 is the fast exhaustive behavior dialect.

Cover:

- clean V1-V9 and V8-to-V9 upgrades;
- Hibernate validation;
- action/intents/executions/targets/events/conflicts persistence;
- exact mode parity and handler dispatch;
- Review queue/history rendering and canonical filters;
- selected/all approval, cancellation, conflict resolution;
- CSRF, ownership, stale confirmation, idempotency, and lease behavior;
- partial/verification-pending/undo/compensation flows;
- privacy reflection, model, template, and captured-log assertions.

H2 is not evidence for PostgreSQL constraints, locks, partial indexes, or plans.

### Layer 3: PostgreSQL 16 Testcontainers

Run production Flyway migrations and repository SQL.

Cover:

- clean V1-V9 and real V8 fixture upgrade;
- unchanged V8 checksums;
- old action/scope/attempt/event/nonce preservation and identity continuation;
- V9 checks, composite FKs, uniqueness, defaults, indexes, and Hibernate validation;
- concurrent action reservation, idempotency, lease expiration/takeover, conflict
  overlap, and append-only ordering;
- H2/PostgreSQL queue/history/filter/count parity;
- deterministic representative performance dataset.

The suite must force Spring/Flyway to the exact container JDBC URL and assert it. No
skip on unavailable Docker/PostgreSQL.

### Layer 4: Provider And Transport Contracts

Use mocked `MailProvider` plus Phase 11 fake transport/resolver boundaries.

Prove:

- provider request IDs equal the exact scope;
- undo request IDs equal only forward succeeded ordinals;
- malformed provider results cannot broaden or overstate outcomes;
- local actions perform zero provider calls;
- unsubscribe uses the Phase 11 method-specific safety path and V8 events;
- no automatic unsubscribe fallback or retry.

### Layer 5: Full Regression

Run all Phase 9-11 suites to retain:

- metadata privacy/idempotency;
- exact scope hash and compatibility;
- sender/unread canonical query/materialization;
- account isolation and protected override audit;
- unsubscribe parsing, SSRF, DNS pinning, redirects, honest outcomes;
- shared shell/accessibility/provider-free GET behavior.

Phase 12 deliberately changes old queue rejection and placeholder expectations; update
only assertions superseded by mapped Phase 12 behavior.

## Suggested Test Suites

| Suite | Purpose |
|---|---|
| `Phase12ActionStateMachineTest` | Matrix, lifecycle, counts, result validation, undo selection |
| `Phase12PostgresMigrationTest` | V9 production upgrade, constraints, locks, parity, budget |
| `Phase12ExecutionServiceTest` | Shared preflight/reservation/handler/completion path |
| `Phase12ReviewWebTest` | Queue groups, approval, conflict, history, CSRF, accessibility |
| `Phase12MultiAccountTest` | Ownership, stable lock order, selected/all behavior |
| `Phase12UndoCompensationTest` | Applied-ID-only undo and non-reversible compensation |
| `Phase12PrivacyTest` | Schema/entity/model/DOM/log allowlists and canaries |

Avoid one monolithic Spring test. Concurrency and migration tests need isolated
classes/contexts.

## Migration Acceptance

### V8 Freeze

- Record and assert SHA-256 checksums of both V8 migration files.
- V9 tests fail if either V8 file differs from the completed Phase 11 baseline.
- Real V8 fixtures include metadata, scope, queued/applied/cancelled actions,
  unsubscribe attempts/events/nonces, protection, and audits.

### V9 Schema

Positive:

- Public action/execution IDs are unique and non-null after backfill.
- Composite account FKs reject cross-account intent, execution, target, event,
  conflict, compensation, and V8 attempt links.
- One forward and one undo execution maximum per action.
- Idempotency fingerprint unique within account/operation contract.
- Scope ordinal unique per execution and scope target.
- Count checks reject negative, over-requested, or non-partitioning summaries.
- Status/action/mode/reason/source checks reject unknown values.
- Required indexes exist with correct leading account/filter/time columns.

Negative:

- Migration creates no generic action JSON/blob beyond inherited typed immutable
  summaries.
- V9 adds no full unsubscribe URI/header/body, provider ID copy, response text,
  exception text, token, DNS address, or OAuth column.
- Existing V8 rows and IDs are unchanged.
- Legacy APPLIED rows gain no fake succeeded-target or action event.

## Detailed Requirement Matrix

### SEND-06: Sender Decisions

Positive:

- Individual, visible-page, and all-matching sender scopes can choose archive, trash,
  mark-read, keep, protect, or review.
- Staged creates owned queued actions and no provider call.
- Immediate creates the same scope/action facts and runs only after shared preflight.
- Keep/review/protect local outcomes are durable and provider-free.
- Protect requires one unambiguous normalized sender and writes account-owned
  protection.

Negative:

- Client target/count/account/action tampering cannot change the stored scope.
- Missing/ambiguous sender cannot protect.
- Protected/high-risk override still requires existing Phase 10 audit semantics.
- Sender GET/preview does not call a provider.

### UNRD-07: Unread Decisions

Positive:

- Individual, visible-page, and all-matching unread scopes support archive, trash,
  mark-read, keep, and review in both modes.
- Exact IDs are materialized once per preview and reused by queue/immediate execution.
- Provider calls match the per-account immutable scope.

Negative:

- No browser-submitted provider IDs.
- Foreign/stale/filter-mismatched row keys fail before action creation.
- A crafted immediate POST cannot bypass cap, protection, conflict, or ownership.

### UNSB-07: Unsubscribe Selection

Positive:

- Saving selection creates one owned action per candidate/method and sends it to the
  Unsubscribe Review group.
- Immediate uses the same action/preflight/executor and consumes a fresh V8 intent.
- Phase 11 attempt/events link to Phase 12 execution target.
- 2xx produces accepted 1, succeeded 0, verification-pending 1.

Negative:

- Protected, stale, changed-destination, ineligible-method, expired/replayed intent,
  unsafe destination, and conflict cases create no outbound call.
- Immediate does not bypass inspection/preflight.
- No multi-candidate request shares one consent/nonce or silently falls back/retries.
- No raw URI/query/header/provider ID enters Phase 12 rows or HTML.

### ACTN-01: Dual Modes, Same Guarantees

Tracer test one exact scope through staged and immediate:

| Checkpoint | Staged | Immediate |
|---|---|---|
| Scope ID/hash/count/IDs | identical fixture | identical fixture |
| Ownership/preflight calls | once | once |
| Conflict/protection check | required | required |
| Durable UserAction | yes | yes |
| Reservation/target rows | on approval | before dispatch |
| Executor class/handler | same | same |
| Provider target set | same | same |
| Outcome/event schema | same | same |

Negative:

- Omitting mode defaults to staged.
- Immediate without explicit opt-in fails.
- Query parameter/header cannot force immediate.
- Direct handler/provider invocation is not reachable from MVC.

### ACTN-04: Grouped Review

Positive:

- Archive, trash, mark-read, unsubscribe, keep, and protect groups render in stable
  order with account subgroups.
- Needs-decision review rows are separate and excluded from executable totals.
- Group action/account/target/conflict counts use one server predicate.

Negative:

- No sample data, unsupported Delete label, or blanket cross-account row.
- Provider IDs/scope IDs are absent from model and HTML.
- GET `/review` causes zero provider/transport interactions.

### ACTN-05: Remove, Selected, All Eligible

Remove:

- Queued owned action becomes cancelled, appends event, keeps scope/history.
- Executing/completed/foreign actions cannot be removed.
- Duplicate cancel is idempotent and causes no I/O.

Selected:

- Unique selected public IDs are fully authorized and eligible before reservation.
- One stale/foreign/conflicted item reserves none.
- Stable account/action lock order is asserted.
- Duplicate form IDs fail closed.

All eligible:

- Applies all server-matched conflict-free queued actions across authorized selected
  accounts, beyond the visible page.
- Excludes conflicts, review decisions, cancelled/executing rows and reports counts.
- Set-change invalidates confirmation.
- Over configured maximum returns 422 and does not truncate.

Every mutation requires CSRF.

### ACTN-06: Conflicts

Matrix tests cover every ordered pair and symmetry:

- archive + mark-read compatible;
- trash conflicts with archive/mark-read/keep/protect/unsubscribe;
- keep conflicts with archive/trash/mark-read/unsubscribe;
- protect conflicts with trash/unsubscribe;
- unsubscribe conflicts with keep/protect;
- review blocks overlapping execution.

Positive:

- Overlap uses exact owned scope targets.
- Conflict records safe reason and overlap count only.
- Cancelling one side resolves conflict and leaves scopes immutable.
- Compatible keep-both resolution is allowlisted and durable.

Negative:

- Same provider ID in different accounts does not conflict.
- Target summaries cannot manufacture or hide overlap.
- Resolution never edits scope IDs or silently subtracts targets.
- Approve-all never executes unresolved conflicts.

### ACTN-07: Honest Outcomes

Provider result matrix:

| Result | Expected |
|---|---|
| all requested succeeded | applied |
| some succeeded, some failed | partial |
| some succeeded, some missing after dispatch | partial + verification pending |
| exception before dispatch | failed/not dispatched |
| exception after dispatch | verification pending |
| foreign/duplicate returned ID | contract failure; no broadened success |
| unsubscribe 2xx | accepted + verification pending |
| unsubscribe timeout after dispatch | verification pending; no retry |

Assert:

- requested partition invariant;
- accepted may overlap succeeded/pending but never exceed requested;
- safe per-reason counts;
- events append in time/ID order;
- prior facts are not overwritten;
- UI arithmetic equals persisted counts.

### ACTN-08: Applied-ID-Only Undo

Positive tracer:

1. Forward scope has IDs A, B, C, D.
2. Ledger records A/B succeeded, C failed, D verification-pending.
3. Undo confirmation says 2 applied.
4. Captured inverse provider request contains exactly A/B.
5. Per-target undo results and history append without rewriting forward facts.

Cover archive -> add INBOX, trash -> remove TRASH, mark-read -> mark unread.

Negative:

- No undo for queued, failed-only, pending-only, expired, already-undone, foreign,
  protect, unsubscribe, keep, or review actions.
- Browser cannot submit IDs or count.
- Scope-wide `message_ids` is not used as the undo request for new actions.
- Partial undo does not set blanket undone status.
- Concurrent duplicate undo yields one inverse dispatch.

### ACTN-09: Non-Reversible Compensation

Unsubscribe:

- History explains that resubscription is external/not guaranteed.
- Provider/source/account-page link is explicit and safe.
- No automatic reverse request or success claim.

Protect:

- `Review removing protection` creates a new owned compensation action after fresh
  preflight.
- Compensation links to original and has independent mode/outcome.
- Original protect fact remains unchanged.

Negative:

- UI never labels either operation Undo.
- Compensation cannot bypass ownership, CSRF, conflicts, or preflight.
- External link exposes no provider ID/token and performs no server fetch.

### ACTN-10: Durable History

For date, account, action type, status, and target:

- positive fixture included;
- near-match excluded;
- every combination round-trips canonical URL;
- page links preserve complete state;
- stable descending time/ID tie ordering across pages.

Target search covers safe sender/list/address/domain summaries with wildcard escaping.
Unknown/duplicate parameters, invalid dates/ranges, foreign accounts, unsupported
status/action, and overlong target fail closed before broad query.

History renders staged/immediate, counts, safe reasons, conflict, undo, compensation,
and V8 unsubscribe linkage. Legacy rows show unavailable detail and no fabricated
counts.

## Shared Executor Tracers

### Staged

```text
preview exact scope
-> create STAGED action
-> no provider
-> Review selected approval
-> reserve execution + PENDING targets + lease
-> provider exact IDs
-> target outcomes + append event
```

### Immediate

```text
preview same exact scope
-> explicit opt-in
-> create IMMEDIATE action
-> same preflight
-> same reserve method + PENDING targets + lease
-> same provider handler and completion method
```

Use spies/architecture assertions to prove both invoke the same service methods and
that source controllers have no `MailProvider` or unsubscribe transport dependency.

## Concurrency And Crash Matrix

| Race/failure | Required result |
|---|---|
| Two immediate submits, same token | one action/execution/dispatch; same receipt |
| Immediate and staged submit same scope/action | deduplicated or durable conflict; never double dispatch |
| Two workers reserve same action | one lease winner |
| Selected and approve-all overlap | one forward execution per action |
| Cancel races reservation | one locked winner; either cancelled/no I/O or executing |
| Conflict introduced before final approval | stale confirmation; no reservation |
| Crash before dispatch marker | recoverable not-dispatched |
| Crash after dispatch marker/before result | verification-pending; no automatic unsubscribe retry |
| Lease expires while worker alive | lease fingerprint prevents stale completion |
| Two undo submits | one undo execution/inverse dispatch |

PostgreSQL must exercise real concurrent transactions with latches/barriers, not only
mocked repository order.

## Multi-Account Matrix

| Situation | Expected |
|---|---|
| One owned account | one scope/action/execution |
| All owned accounts | separate scopes/actions; aggregate batch presentation |
| Same provider ID in two accounts | independent targets, no conflict |
| One foreign account public ID | 404 and no reservation |
| One selected account becomes disabled | whole selected reservation fails |
| One account provider fails after reservation | other actions retain independent facts |
| All eligible over multiple accounts | lock accounts then actions in ascending order |
| Multi-account undo | separate per-action/account operations only |

## CSRF And Authorization Matrix

Require authentication and CSRF for:

- create staged action;
- create immediate action;
- immediate opt-in confirmation;
- cancel;
- approve selected;
- approve all confirmation/final submit;
- conflict resolution;
- undo;
- compensation;
- protect/unprotect.

For each route test:

- no authentication;
- missing/invalid CSRF;
- foreign public ID;
- owned public ID with foreign scope/account link fixture;
- stale/expired intent;
- duplicate/replay.

Failures occur before provider/transport calls and before unauthorized writes.

## Privacy Assertions

### Allowed Durable Facts

- Internal account/action/scope/execution/attempt FKs.
- Opaque public IDs and SHA-256 fingerprints.
- Bounded safe target labels/search keys.
- Action, mode, status, reason, source, operation enums.
- Counts and timestamps.
- Scope ordinal and safe target fingerprint.

### Forbidden In New Tables, Models, DOM, URLs, And Logs

- Additional provider message/thread ID copy.
- Exact scope message IDs in presentation/logs.
- Full unsubscribe URI/query, raw list/auth/DKIM header.
- OAuth/access/refresh token, cookie, authorization, referrer.
- Response body/title/HTML or raw exception text.
- DNS address or internal network detail.
- Idempotency token, lease token, CSRF token in logs.
- Arbitrary action/outcome JSON blobs.

Tests:

- entity reflection and information-schema allowlists;
- DTO field/type allowlists;
- rendered HTML and URL canaries;
- captured log canaries;
- database round-trip with secret canaries proving only allowed fingerprints/summaries
  persist.

Provider IDs may remain in inherited `ActionScope.message_ids` and V1 `UserAction`
compatibility storage; tests specifically prove they never enter new duplicated
ledger columns or the DOM.

## Accessibility And Responsive Validation

Automated HTML/static assertions:

- one `main`/`h1`, skip link, active Review view;
- fieldset/legend for mode and filters;
- captions/scoped headers or equivalent card labels;
- checkbox accessible names include action/target/account/count;
- polite selection/outcome live regions;
- alert conflict/stale/partial errors;
- native disabled states and associated reasons;
- external new-tab announcement and noopener/noreferrer;
- focus targets after confirm/conflict/outcome fragments;
- no color-only status;
- existing 980/720/375 breakpoints, 44px targets, focus outline, reduced motion, and
  overflow containment.

Manual Phase 13 browser UAT remains responsible for visual judgment, real 375px width,
keyboard traversal after live HTMX swaps, screen-reader wording, and 200% zoom. It
does not replace Phase 12 automated safety/accessibility checks.

## Performance And Determinism

- Review/history GETs are provider/transport-free.
- Warm PostgreSQL/H2 query target remains under two seconds for a deterministic
  10,000-message dataset plus representative scopes/actions/events/targets.
- Correct counts and boundary identities are asserted before timing.
- Queue/history order has complete stable tie-breakers.
- Approval set size is bounded and never silently truncated.
- Conflict overlap is indexed by account/scope target fingerprint.
- Execution dispatch respects inherited 1,000-target per-account scope cap.

## Suggested Verification Commands

Use Java 21:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q \
  -Dtest=Phase12ActionStateMachineTest,Phase12ExecutionServiceTest,Phase12UndoCompensationTest test
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q \
  -Dtest=Phase12ReviewWebTest,Phase12MultiAccountTest,Phase12PrivacyTest test
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q \
  -Dtest=Phase12PostgresMigrationTest,Phase9ActionScopeTest,Phase10SelectionMaterializationTest,Phase11PostgresMigrationTest test
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q test
git diff --check
```

No PostgreSQL test may silently skip.

## Requirement Traceability

| Requirement | Primary suites | Required proof |
|---|---|---|
| SEND-06 | execution, web, multi-account | All sender decisions in both modes over exact scopes |
| UNRD-07 | execution, web | All unread decisions with no mode/preflight bypass |
| UNSB-07 | execution, Phase 11 regression | Staged/immediate explicit candidate method with V8 outcomes |
| ACTN-01 | state machine, execution tracer | Same scope/preflight/executor/ledger, timing only differs |
| ACTN-04 | web | Six groups plus account subgroups and needs-decision |
| ACTN-05 | web, multi-account, concurrency | Remove, atomic selected, bounded eligible-all |
| ACTN-06 | state machine, PostgreSQL, web | Exact overlap, durable conflict, explicit resolution |
| ACTN-07 | execution, web, PostgreSQL | Required counts, partial/pending, safe reasons/events |
| ACTN-08 | undo tracer, provider contract | Only ledger-succeeded ordinals reach inverse request |
| ACTN-09 | compensation, web | Honest non-reversible copy and linked new action |
| ACTN-10 | web, PostgreSQL | All filters, stable pages, legacy truth, analytics facts |

## Completion Gate

Phase 12 is complete only when:

- Every mapped requirement has named positive and negative automated evidence.
- Staged and immediate tracer tests prove one exact-scope executor and identical
  preflight/safety behavior.
- PostgreSQL 16 V1-V9 and actual V8-to-V9 upgrades pass with zero skips and unchanged
  V8 checksums.
- Concurrency tests prove one forward and one undo dispatch per action.
- Every partial, missing, malformed, and unknown provider result remains truthful.
- Undo provider requests contain only forward ledger `SUCCEEDED` targets.
- Unsubscribe/protect compensation never masquerades as mechanical undo.
- Multi-account ownership and CSRF matrices pass before I/O.
- History filters and analytics-ready facts are durable and deterministic.
- No forbidden provider/network/auth fact enters new schema, HTML, URLs, or logs.
- Responsive/accessibility static contracts and the full regression suite pass.

## Planned Validation Status

| Area | Status |
|---|---|
| Requirement mapping | Complete |
| Shared executor tracer | Specified |
| H2 architecture | Specified |
| PostgreSQL migration/concurrency architecture | Specified |
| Partial and verification-pending outcomes | Specified |
| Applied-ID-only undo | Specified |
| Multi-account/CSRF/privacy | Specified |
| Accessible responsive UI | Specified |
| Execution evidence | Complete — see `12-VERIFICATION.md` and `12-INDEPENDENT-VERIFICATION.md` |
