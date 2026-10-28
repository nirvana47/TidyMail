# Phase 2 — Baseline and persistence design

Started 2026-10-28 at source revision `04f4180`. Phase 1's
[approved design](wireframes/sender-dashboard-approved.html) remains locked.
This phase adds measurement tooling and a design; it does not install migrations,
change the retained development database, or dispatch live mailbox actions.

## Task tracking

| Task | Status | Dependency | Deliverable / checks |
|---|---|---|---|
| Inspect baseline | Complete | Approved Phase 1 | Source mapping and metadata gaps below |
| Existing automated baseline | Complete (baseline recorded) | Isolated PostgreSQL | 451 tests: 448 passed, 3 baseline failures, zero errors/skips; details below |
| Measure indexed browsing | Complete | Baseline suite | `DashboardBaselinePerformanceTest` passed: 10k/50k messages, 500 addresses, idle/concurrent writes |
| Persistence and state design | Complete (design) | Component mapping | Additive migration contracts, cancellation and recovery below |
| Upgrade fixtures and recovery plan | Complete (defined) | Schema design | Fresh/retained fixtures and backup/restore procedure below; execution deferred until migrations exist |
| Phase gate | Complete (verification/design) | All above | Baseline, mapping, design, fixtures and targets recorded. Baseline defects remain open; no application release gate is passed. |

## Existing components and reuse boundary

| Component | Current contract | Dashboard decision |
|---|---|---|
| `SenderDecisionQueryService` / `DecisionSurfaceQueryRepository` | Account-scoped sender aggregation; 25-row pages; multiple queries for count, metrics and rows; categories/classification | Reuse parameter binding and ownership patterns. Add a dashboard query contract with 10-row groups and one shared received/filter predicate. Current sender search does not match individual subjects. |
| `SelectionMaterializationService` / `SelectionPreviewTransactionService` | Validates account selector and decoded row ownership; materializes exact scopes from legacy surface locators; currently limits exact selections to 1,000 messages per account | Reuse exact materialization, hashing and scope persistence. Add group/address/message locators; deduplicate IDs and use dashboard predicate. Remove legacy selection-size coupling to provider batch size through bounded materialization; do not silently truncate larger groups. Never expose legacy all-results selection in dashboard. |
| `ActionScopeService` | Hash-bound immutable message arrays, target index and integrity validation | Reuse `action_scopes` and `action_scope_targets`. Bind preview to filter, grouping revision, action and owner; every confirmed ID including known no-ops remains in the scope. |
| `ExecutionReservationService` | Account/action row locks, idempotency, two-minute lease, per-target creation | Existing execution is whole-scope; `markDispatched` marks every target. Do not call this as a batch dispatcher. Reuse locking/idempotency patterns in a separate durable dashboard coordinator. |
| `ExecutionCompletionService` | Requires whole-scope result, valid lease and dispatched targets; creates legacy undo window | Retain for historical/legacy flows. New coordinator persists incremental six-bucket results without undo; do not map skips to legacy successes. |
| `ExecutionRecoveryService` | Expired reservation can redispatch; dispatched pending targets become verification pending | Reuse conservative uncertainty principle. New recovery must distinguish each batch, respect cancellation and fence stale workers. |
| `ExactScopeExecutor` / `MailboxMutationHandler` | Synchronous dispatch around transactional reservation/completion; handler rejects provider calls inside a database transaction | Reuse provider/action mapping and I/O separation, with batch-specific context and new result classification. Existing whole-scope executor is not a durable queue. |
| `GmailMailService.batchModify` | One provider request for supplied IDs; treats normal response as all succeeded and IOException as all failed | Requires new ambiguity handling and reconciliation before six-bucket accuracy can be claimed. Current optimistic result is not proof that every target changed. Request types/limits must be verified against official Gmail docs in Phase 5. |
| `GmailRateLimiter` | In-process semaphores, sync-lane capacity, metadata pacing and blocking read backoff | Reuse shared provider capacity; add cancellable scheduling and persisted retry time for jobs. No sleeping with job row locks. Restart cannot rely on in-memory permits as durable state. |
| `ActivityQueryService` | Index coverage, sync lanes and recent sync events | Reuse sync display. Cleanup history comes from new job/target records, with existing `ActionHistoryService` supplying legacy history separately. |
| `OwnedAccountScopeService` | Resolve authenticated owner to account | Keep ownership at query, preview, confirm, cancel, corrections, activity and target-detail boundaries; clients never supply authoritative account IDs. |
| `UserDataDeletionService` | Deletes all owner accounts transactionally with cascades | Add deletion barrier: request cancellation and refuse final deletion while dispatch/verification leases remain. Sign out retains jobs and data. |

## Metadata feasibility and gaps

| Approved requirement | Retained evidence | Required change / conservative behavior |
|---|---|---|
| Inbox and exclusions | Per-message `labels` | Spam/Trash/Draft exclusions first; Inbox positive evidence. Existing sender SQL is not the approved received predicate. |
| Archived received mail | Labels, account identity and `delivered_to_values` | Non-Sent with known labels qualifies; Sent requires independent delivery evidence to primary/verified alias. To/Cc alone never qualifies Sent. |
| Unknown labels | Database has non-null array; mapper converts absent provider labels to empty array | Empty legitimate labels and unknown labels are indistinguishable historically. Add evidence state default `UNKNOWN` for retained rows, `KNOWN` only after successful metadata observation. Historical nonempty labels may be used as observed label evidence; historical empty labels remain excluded pending targeted metadata refresh. Show excluded/unknown count. |
| Case-preserving From identity | `from_address`, display name, domain; parser currently lowercases entire address and picks the last angle mailbox | Original local-part case and multi-From ambiguity cannot be recovered from retained address. Add raw From values plus strict parsing/version state. Retained unverified identity uses isolated message units until observed again; never silently merge using lossy values. No full mailbox download required for browsing. |
| List units | `list_id_values` array retained | Parse one valid identifier; multiple/invalid values cannot become a domain merge. Keep uncertain messages separate and report reason. |
| Shared/dedicated domains | Domain retained; no dedicated-domain registry | Versioned shared registry takes precedence; unknown defaults exact address. Dedicated classifications require explicit reviewed registry entries; no parent-domain inference. |
| Verified aliases | Account email present; no verified alias registry found | Start with primary account address only. Add account-owned verified aliases with provenance and verified timestamp; do not infer verification from To/Cc. |
| Dates/timezone | Nullable provider `received_at`; no stored account timezone found | Add explicit IANA display timezone, default visibly to UTC until selected. Convert inclusive local start / exclusive next-day boundary, including DST; unbounded results include undated messages, bounded results exclude them. |
| Search | Subject, display name and address retained | Literal case-insensitive substring with escaped SQL LIKE wildcards. Match group label OR message From/name/subject; group label match includes all eligible children. |

Refetching missing evidence is a later explicit sync task using metadata-only reads;
never overwrite old credentials, cursors or history to repair evidence. Coverage
must distinguish excluded unknown evidence from a genuinely empty mailbox.

## Additive migration design

Migration names below are proposed after current V15. Do not place draft SQL in
the production Flyway directory. Final numbering must be checked immediately
before implementation. Every owned foreign key includes account ID; IDs in public
requests are opaque UUIDs. Account deletion cascades only after the deletion barrier.

### V16 — Evidence and grouping foundations (Phase 3)

| Record | Fields and constraints |
|---|---|
| `message_metadata` additions | Nullable raw From array; `identity_evidence_state` and `label_evidence_state` default UNKNOWN, observed timestamp and parser/rule version. No fabricated reconstruction of lost raw headers. Existing rows remain readable by legacy code. |
| `dashboard_account_settings` | Account PK/FK; display timezone; grouping revision; rule/registry version; deletion-requested timestamp. Validate timezone in application. |
| `dashboard_verified_aliases` | Account + exact verified mailbox identity unique; verification source and timestamp required. |
| `sender_units` | ID, account, public UUID, kind LIST/ADDRESS/MESSAGE, canonical key, rule version and evidence reason. Unique `(account_id, kind, canonical_key)` and `(id,account_id)`; message fallback key includes provider message ID. |
| `sender_groups` | ID/account/public UUID, stable group key, label, rule version, revision; unique account/key. Domain labels literal; manual labels bounded and escaped. |
| `sender_group_members` | Account, unit, group; unique `(account_id,unit_id)`; composite owned FKs. Corrected group assignment never duplicates a message. |
| `message_sender_units` | Account, metadata ID, unit ID, evidence version; unique `(account_id,metadata_id)`; owned FKs. Rebuildable derivation; do not cascade-delete action targets when metadata disappears. |
| `sender_group_corrections` | Account, public UUID, revision, kind MERGE/SEPARATE/RESET, bounded optional label, created time; correction-unit join table and canonical unordered separation-pair table with unique account/pair and low < high. Preserve constraints and audit history across regrouping. |

Use stable atomic unit identity independent of group label/domain registry revision.
On correction, lock settings/account revision, validate all separation pairs across
the prospective merged components (including transitive paths), update membership
and increment revision atomically. RESET removes the specified active correction,
recomputes constraints/membership from remaining corrections and automatic rules,
and records history; it does not erase unrelated corrections. Separate an address
spanning list units only through explicitly previewed units. Selections are ID sets;
corrections invalidate unconfirmed previews but cannot rewrite confirmed scopes.

Indexes: unit account/key, membership account/group/unit, metadata membership
account/unit/metadata, account/received/message identity for message paging.
Measure query plans before adding optional trigram/label indexes; no unmeasured
extension requirement. Summary/page/selection queries share the same SQL predicate
and a consistent database snapshot per response. Include grouping revision in
responses to reject stale pane/filter replies.

### V17 — Durable cleanup (Phase 4)

Keep legacy execution CHECK constraints and history unchanged. Reuse exact scope
records; add dashboard-specific job/batch/outcome tables because legacy statuses
cannot represent skips, cancelled targets or incremental completion truthfully.

| Record | Fields and constraints |
|---|---|
| `cleanup_jobs` | ID, public UUID, account, scope ID, action enum MARK_READ/ARCHIVE/TRASH, confirmation fingerprint, immutable selected count, action/filter/timezone/coverage/group-revision snapshot, status, cancel-requested timestamp, next-attempt time, worker UUID, lease generation/expiry, created/started/settled times. Unique account/confirmation fingerprint; owned scope FK; positive fixed total; lease owner/expiry paired. |
| `cleanup_job_targets` | Job/account/scope ordinal/fingerprint plus immutable provider ID (or exact ordinal lookup); state, reason, desired/pre-dispatch label facts, attempt count, batch ID, result/verification times. Unique job/ordinal and job/provider ID; composite owned scope-target/job FKs. Fixed rows never expand after confirmation. |
| `cleanup_batches` | Job/account, ordinal, lease generation, request UUID, state, created/dispatch/completion times, next attempt, attempt count, bounded error code. Unique job/ordinal and request UUID. Dispatch membership fixed through batch-target join/assignment. |
| `cleanup_active_targets` | Primary key `(account_id,target_fingerprint)`, owned job FK, immutable provider-ID verification on collision. Insert all claims in confirmation transaction; collision rejects overlapping active jobs. Hold unresolved claims until verified or explicitly resolved, even after cancellation settlement. |
| `cleanup_job_events` | Job/account, monotonic event ID, state/reason/count snapshot, time; immutable audit. Progress can poll by last event/version; no external broker needed. |

Indexes: runnable job `(status,next_attempt_at,lease_expires_at,id)`, activity
`(account_id,created_at DESC,id DESC)`, targets `(job_id,state,scope_ordinal)`,
batches `(job_id,state,next_attempt_at)`. Exact target details page by ordinal.
Do not duplicate mutable counters as an independent truth: aggregate target states
in a single snapshot first; if cached later, update counts and target transitions
in the same transaction. Enforce fixed total and complete target set at confirmation;
row CHECK constraints alone cannot enforce a cross-row sum. Prevent scope/target
updates after confirmation in service boundaries and database guards.

## State transitions, dispatch and recovery

| Target state | Bucket | Allowed next state |
|---|---|---|
| QUEUED / RETRY_WAIT / RECONNECT_WAIT | Remaining | DISPATCH_INTENT, SKIPPED, FAILED, CANCELLED_BEFORE_DISPATCH |
| DISPATCH_INTENT | Remaining while live lease; uncertain on lost worker | CHANGED, FAILED (definite rejection only), AWAITING_VERIFICATION |
| CHANGED | Changed | Terminal |
| SKIPPED | Skipped | Terminal; known no-op, excluded, missing or stale, with reason |
| FAILED | Failed | Terminal; request definitely rejected with no ambiguous dispatch |
| AWAITING_VERIFICATION | Awaiting verification | CHANGED / SKIPPED if read proves result; otherwise remains unresolved. Never automatic mutation replay. |
| CANCELLED_BEFORE_DISPATCH | Not processed — cancelled | Terminal; never had a dispatch intent |

Job states: QUEUED → RUNNING → COMPLETED / COMPLETED_WITH_EXCEPTIONS /
VERIFYING; RUNNING ↔ RETRY_WAIT / RECONNECT_WAIT; any nonterminal mutation state
→ CANCELLING → CANCELLED. Cancellation is also accepted during VERIFYING while
work remains; read-only verification may continue after cancellation. Cancelled
jobs never re-enter runnable mutation states. Completed means no remaining or
unresolved targets; exception results keep their distinct counts.

Confirmation transaction validates owner and preview fingerprint/expiry/revision,
inserts job + exact targets + overlap claims + event, and consumes the existing
command/confirmation intent atomically. A repeated token returns the original job;
a different token with active overlapping IDs conflicts. Known no-ops are SKIPPED
from the start but remain part of the total. Zero effective targets cannot confirm.

Claim short job transactions with `FOR UPDATE SKIP LOCKED`, renewing a generation-
fenced worker lease. Preflight reads provider metadata outside transactions, then
under the job lock check cancellation, lease and current eligibility before writing
batch DISPATCH_INTENT and committing. Dispatch only those immutable batch IDs outside
transactions; persist acknowledged results in a fresh fenced transaction. Recheck
before each batch, not just once per job. Start with one active batch per job and
bounded batches; final provider sizes/limits are a Phase 5 documentation decision.

Cancel locks the same job row used for dispatch claims, records its timestamp and
cancellation event, and cancels all targets with no dispatch intent. A batch whose
intent committed first counts as already in flight and may finish after the click.
There is inevitably a commit-to-network gap: a crash there is uncertain, never
evidence that a request was not sent. No new intent may commit after cancellation.
Do not hold a database transaction over network I/O to hide that gap. Before-send
checks reduce but cannot eliminate it. UI copy says “Stopping new requests;
requests already sent may finish. Completed changes stay.”

On worker loss, queued/unclaimed work can be reclaimed only if uncancelled.
Expired dispatch intents become AWAITING_VERIFICATION, not queued. Fence stale
workers from completing/claiming after lease generation changes; late responses
can be retained as reconciliation evidence. Read current labels to reconcile
unknown outcomes; an unchanged observation alone never authorizes automatic replay.
If no reliable pre-dispatch evidence proves a change, classify the observed desired
state conservatively as SKIPPED with explanation rather than inventing attribution.
Persist retry schedule only for known non-dispatch/definite transient rejection;
exhausted retries become FAILED. Cancel bypasses retry/reconnect delays.

For every snapshot: changed + failed + skipped + awaiting verification + cancelled
before dispatch + remaining = fixed selected total. At cancellation settlement,
remaining = 0; uncertainty remains explicitly visible and can resolve read-only.
Settled cancellation releases only resolved target claims. Sign out/reload/tab close
does not cancel; jobs are restored from the database on return.

## Upgrade fixtures and backup/rollback

Fixtures are defined here; they become executable with the corresponding migrations.

| Fixture | Setup and required assertions |
|---|---|
| Fresh | Empty isolated PostgreSQL 16 → V1 through new migrations; Hibernate validation; ownership/FKs, unique confirmation, overlap and state checks; empty index has no invented coverage. |
| Retained V15 | Two owners, dummy encrypted tokens, 10k metadata rows, raw-evidence gaps, every sync lane/cursor, exact scopes and historical applied/partial/verification/undo events. Upgrade; compare credential bytes, IDs, metadata counts, cursor/revision values and history checksums. Unknown evidence is excluded visibly; no provider calls during migration. |
| Scope/group fixtures | Read/unread Inbox; archived; Sent self with primary/verified alias evidence vs To/Cc-only; mixed thread; Spam/Trash/Draft overriding Inbox; unknown labels; null date; DST boundaries; literal `%_!` search; shared/unknown/dedicated domains; distinct lists; mixed-case local parts; invalid/multiple From; transitive correction conflicts. |
| Job fixtures | 501+ fixed IDs; known no-ops; duplicate confirmation; cross-owner access; overlapping jobs; cancel before claim and around intent commit; retry/reconnect cancel; worker generation rollover; crash before/after send; late result; tab reload; verification after cancel. Assert exact target set and six buckets at every checkpoint. |
| Rollback rehearsal | Upgrade a copy, stop writers, restore encrypted backup into a separate database, run old source and validate fixture checksums/history/cursors. Never delete the original volume. |

Before any retained-database migration, stop the local application and all sync/job
writers; record source revision and Flyway versions without credentials. With a
private output directory and umask 077, create a custom-format dump via
`docker compose exec -T db pg_dump -U tidymail -d tidymail -Fc` and capture it into
an encrypted backup outside Git. The dump contains OAuth tokens and mail metadata;
never attach it to reports. Check the dump catalog, compute its checksum and restore
to a separate temporary PostgreSQL database with `pg_restore --exit-on-error`.
Compare table counts, referential integrity, credentials/cursors/history within the
private verification process; publish only redacted pass/fail evidence.

Record the tested backup path/checksum privately before applying migrations.
Use a forward repair for additive failures where possible. If restoration is needed,
stop writers, preserve the failed database and restore the verified dump into a
separate database, then point the matching old application revision at it. Do not
use Flyway clean, erase migration history, remove the Compose volume or downgrade
constraints in place. Local backup cannot undo provider mutations; reconcile any
in-flight intents before dispatch resumes. No retained backup or migration was
needed or performed during Phase 2.

## Baseline evidence and performance targets

The new benchmark records the existing sender
repository's combined summary/count/page, search/date filter and tenth sender page.
Message expansion is explicitly a ten-row SQL prototype because the current app
does not implement the approved general message pane. Both sizes use 500 addresses,
one warmup and ten samples per operation. Concurrent load updates 200 metadata rows
per committed transaction on a separate connection; this models write contention,
not Gmail network throughput, full sync reconciliation or cleanup capacity.

The benchmark checks result counts/page bounds and writer completion, and reports
timings without declaring future dashboard performance passed. Phase 3 must repeat
the measured workload with 10-row groups, received eligibility, subject/group search,
corrections, shared predicates and real selection/expansion paths.

### Existing suite — 2026-10-28

Java 21.0.13, PostgreSQL 16.15 (`postgres:16-alpine`), Colima, Maven offline after
dependencies were resolved. `mvn -o test` completed in 5 min 12 s with **451 tests,
448 passed, 3 failed, 0 errors, 0 skipped**. This ran before the new benchmark was
compiled/discovered. Application source was unchanged from `04f4180`; these are
baseline failures, not regressions introduced by Phase 2.

| Baseline failure | Observed evidence | Disposition |
|---|---|---|
| `Phase10SenderUnreadWebTest.d14WarmTenThousandRowFullAndFragmentRequestsStayUnderTwoSeconds` | Warm `/senders` request took 27.688 s against a 2 s limit | Open performance defect; current full template/category path is substantially slower than the isolated repository benchmark. Phase 3 must profile and replace that path, not treat repository timing as a fix. |
| `MailboxFreshnessJourneyTest.newUserSeesFirstBatchBeforeFullMailboxBackfillCompletes` | Sender page does not contain expected synthetic display label `Batch Sender` | Open sender-rendering/assertion mismatch; reproduce and resolve during Phase 3 browsing work. Sync acceptance is not inferred from this failure. |
| `Phase8DeploymentTest.T8d_lintAndDocTests.T8d_01_deployMdChecks` | DEPLOY.md lacks the historical literal tunnel command required by test | Open documentation/test alignment issue; no deployment was attempted. Resolve before the final full-suite release gate. |

The initial restricted Maven attempt could not write its cache lock; the next run
needed the existing Colima socket configured. These were harness setup failures,
not product failures. The successful configured run used only disposable test
containers. No retained-index reset was needed.

### Measurements — 2026-10-28

The separate benchmark passed **1 test, 0 failures/errors/skips**, 43.845 s Maven
elapsed. Values are milliseconds, shown as median / nearest-rank p95. With only ten
observations p95 is the maximum; these are local samples, not a production SLO.

| Messages / concurrent metadata writer | Summary + first page | Search + 7-day filter | Sender page 10 | Ten-message SQL prototype |
|---|---|---|---|---|
| 10,000 / idle | 87.54 / 106.92 | 109.64 / 113.75 | 91.90 / 102.96 | 10.78 / 12.61 |
| 10,000 / active | 156.47 / 172.18 | 171.70 / 176.05 | 144.83 / 157.07 | 10.75 / 11.95 |
| 50,000 / idle | 275.77 / 286.59 | 348.06 / 349.16 | 271.86 / 279.71 | 9.92 / 13.23 |
| 50,000 / active | 285.20 / 870.21 | 371.83 / 972.46 | 286.69 / 326.73 | 9.84 / 12.04 |

The writers committed 231 and 627 batches respectively (200 updates each),
revisiting the same 200 indexed rows throughout the read measurements. This is
deliberately a contention fixture, not growth or full sync throughput. Current
sender pages contain 25 addresses and are not the future 10-group dashboard.
Archived synthetic mail is included but this benchmark does not validate the
new scope predicate. The future scope/group fixtures remain required.

### Numerical Phase 3 targets

Targets include headroom for the shared eligibility predicate and derived grouping,
based on the observed worst 176 ms / 972 ms combined queries and 14 ms expansion
prototype. Repeat on the same 10k/50k datasets with 500 units, idle and with the
same 200-row writer; one warmup and at least ten observations per operation.

| Path | 10,000 messages p95 | 50,000 messages p95 |
|---|---|---|
| Summary + 10-group page, date/search query, later sender page | ≤500 ms each | ≤2,000 ms each |
| Address expansion / 10-message detail page | ≤250 ms each | ≤500 ms each |
| Warm authenticated dashboard/fragment response | ≤2,000 ms each | ≤3,000 ms each |

HTTP budgets add template/authentication overhead to the measured query budgets;
they are future acceptance targets, not measured passes. Exact preview latency
must be measured in Phase 3 at 501 and 5,000 IDs; preserve complete target lists
and bounded database work rather than impose an unmeasured truncation limit.
No whole-mailbox scale guarantee, browser latency, concurrent cleanup, or actual
Gmail throughput was established here. Repeat at retained-index scale in Phase 7.

Phase 2 is complete as baseline verification and persistence design. Phase 3 is
next; its first tasks are the shared evidence/filter contract and derived grouping,
followed by the locked browsing interface. Production migration SQL and upgrade
execution remain work for the corresponding implementation phases.
