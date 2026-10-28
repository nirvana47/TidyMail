# Sender dashboard implementation strategy

Planning date: 2026-10-28. Product agreement: [MVP plan](MVP-PLAN.md).
This document defines planned work, not implemented or verified behavior.

## Working agreement

Complete phases sequentially. Within each phase, work through its tasks in order,
run the relevant checks, and record results before advancing. If a gate fails,
resolve it or explicitly document an agreed scope change; do not mark it passed.
Review the wireframe with the user before implementing the interface. This
planning change does not start application implementation or authorize mailbox
mutations. Subsequent implementation can proceed within the agreed phase scope.

| Decision | Agreed behavior |
|---|---|
| Browsing | Inbox by default; All received mail includes archived received messages. Exclude Spam/Trash, sent-only mail, and drafts. |
| Dates | All indexed dates by default, with visible coverage and freshness. |
| Grouping | Strong domain/mailing-list evidence; uncertain matches separate; manual merge/separate. |
| Actions | Mark as Read, Archive, Move to Trash; exact preview and confirmation before execution. No permanent deletion. |
| Cancellation | Cancel queued/active work; no pause, resume, or Undo. Completed changes remain. |
| Feedback | Compact dashboard progress panel and Activity drawer, restored after reload. |
| Testing | Synthetic large-job/failure tests, then small live selections, then a larger user-confirmed live Archive/Trash selection if small tests pass. |

## Reference-led layout revision

The first wireframe was rejected on 2026-10-28. Use the supplied dashboard image
and the revised [MVP visual direction](MVP-PLAN.md#visual-direction--reference-led-revision-2026-10-28).
The seven phases remain appropriate. The incremental work is a synchronized
list/detail interface, shared selection state, and responsive bottom action/progress
dock. Body previews, external logo services, company enrichment and account
switching are deferred. No new service or infrastructure is prescribed.

## Phase 1 — Specify the experience

Deliver a reference-led dashboard wireframe with sender table, inline address
expansion, right-hand Messages / Sender addresses pane, bottom action and
progress dock, and modal confirmation. Show collapsed/expanded groups, filters,
selection, confirmation, progress, cancellation, and Activity. Include empty,
partially indexed, reconnect, partial-failure, and uncertain-outcome states.
Specify received-mail eligibility, deterministic grouping evidence and shared-domain
exceptions, correction precedence, and selection behavior across pages/filters.
Changing filters must never silently expand a confirmed selection.

Define cancellation wording and the outcome counts before designing the progress
panel. Cancel is available immediately, including while queued or waiting for a
retry; it is not triggered by an elapsed-time threshold.

**Gate:** user reviews the wireframe; grouping and scope examples have explicit
expected results. No interface implementation before this review.

## Phase 2 — Verify the baseline and design persistence

Inspect current source and run relevant existing tests using isolated PostgreSQL.
Record baseline failures separately from regressions. Measure local-index summary,
filter, paging, and expansion performance with dataset sizes and concurrent sync
conditions; set numerical performance targets from these measurements.

Map reusable code: SenderDecisionQueryService, SelectionMaterializationService,
ActionScopeService, execution reservation/completion/recovery services,
MailboxMutationHandler, GmailRateLimiter, and ActivityQueryService. Check current
contracts rather than assuming historical documentation is current.

Design additive migrations for derived groups/corrections and durable job,
batch/target outcome, cancellation, retry, and worker-lease state. Reuse existing
records where their semantics fit. Preserve account credentials, metadata, cursors,
and history. Keep provider calls outside database transactions. Define rollback
and backup procedures before changing the retained development database.

**Gate:** baseline report, component mapping, migration design, state transitions,
and measurable performance targets are recorded. Fresh and retained-data migration
fixtures are defined. No local reset is necessary without a recorded reason and
recoverable backup.

## Phase 3 — Build indexed browsing and exact selections

Implement a shared scope/filter contract used by summaries, groups, previews,
selection materialization, and action eligibility. Implement server-side grouping,
paging, on-demand expansion, and account-owned merge/separate corrections.
Add the reviewed browsing interface, account identity, scope toggle, date/search
controls, and coverage indicators. Build the desktop split view and responsive
fallback with shared selection IDs across table, expanded addresses and detail tabs.
Keep row focus/expansion independent of selection; support mixed parent checkboxes.
Use initial badges and indexed subject/sender/date previews, without body fetching
or remote logo discovery. Include selected-action state and exact review modal.

Implement exact selection preview and immutable confirmed target IDs, including
current-page versus individual group/address/message selection. Recheck eligibility at dispatch and
report no-op/stale targets. Enforce ownership on every query and action boundary.

**Gate:** scope/grouping fixtures pass, counts and selections agree across pages,
shared-domain senders remain separate, corrections work, and browsing meets the
phase-2 targets without a new full Gmail download. Verify cross-pane checkbox/count
agreement, tab switches without selection changes, filter invalidation and stale
response handling when rapidly switching focused groups.

## Phase 4 — Deliver one complete background action

Implement Mark as Read first: selection → preview → confirmation → persisted job
→ bounded background batches → acknowledged bottom-dock progress → reconciled dashboard.
Use the existing application and PostgreSQL rather than adding queue infrastructure
without demonstrated need. Make duplicate confirmation submissions idempotent.

Implement cancellation as part of this first action, not as later UI polish.
Persist the request and coordinate it with dispatch claims so no new mutation is
claimed after cancellation takes effect. Already dispatched requests may finish;
show cancellation pending while settling those requests. Prevent mutation retries
or automatic restart of cancelled work. Read-only verification may continue.

Persist target outcomes and enforce this accounting invariant:

confirmed changed + failed + skipped/no-op + unresolved + cancelled before dispatch
+ remaining = fixed confirmed target count.

At terminal cancellation, remaining is zero; unresolved outcomes may still need
verification. Report confirmed changes including in-flight completions after the
click, rather than freezing the displayed count at click time. Do not claim all
unprocessed messages are unchanged if their dispatch outcome is uncertain.

**Gate:** the full journey works with simulated Gmail, duplicate submissions,
cancel-before-start, cancel-during-dispatch, reload, restart, and ambiguous responses.
Closing a browser does not cancel work. Cancellation does not reverse changes.

## Phase 5 — Complete actions and resilience

Extend the proven flow to Archive and Move to Trash. Archive removes Inbox
membership; archived received messages remain visible under All received mail.
Trash removes messages from both dashboard scopes. Reconcile local labels and
counts from confirmed results while preserving uncertain/pending indicators.

Coordinate sync, backfill, cleanup, and verification capacity. Verify current
official Gmail documentation when choosing request types, batch sizes, and limits.
Add bounded backoff with jitter, provider retry guidance, reconnect handling,
worker leases, recovery, and verification before replaying uncertain mutations.
Cancellation must interrupt waiting/retry scheduling without re-enabling mutations.

**Gate:** more than 500 synthetic targets pass multi-batch success, partial failure,
throttling, timeout-after-dispatch, reconnect, worker restart, and cancellation
races for the supported actions. Outcome totals remain consistent. No guessed
success or blind replay. These are simulated-provider results, not live throughput.

## Phase 6 — Complete the simplified experience

Finish Activity history, progress details, cancellation/results messages, pending
row indicators, and responsive/keyboard behavior. Verify the bottom dock never
obscures rows/focus, the narrow-screen detail pane remains usable, and confirmation
and Activity restore focus correctly. Remove Unread Only, Unsubscribe,
multi-account management, separate Review, and standalone analytics navigation.
Keep Sign out and confirmed local-data deletion accessible.

Disable unsubscribe execution through direct routes and legacy queued work in
MVP mode. Disable legacy Undo entry points in MVP mode. Retain useful underlying
code and records without exposing unsupported actions. Confirmation must name
the action and effective count, state that Cancel stops remaining work, and explain
that completed changes are not reversed.

**Gate:** authenticated browser checks pass; removed actions cannot bypass the new
flow; browsing stays usable during active jobs. Sign out retains indexed data.

## Phase 7 — Validate the upgrade and live behavior

Run affected tests and the full suite, distinguishing baseline failures from new
regressions. Validate fresh setup and retained-data upgrades using isolated fixtures
before applying migrations to the existing index. Repeat representative performance
checks with sync and cleanup active; record dataset sizes and measured results.

First validate small, explicitly selected live Gmail actions and compare actual
labels with local counts and job results. Then, if these pass, support the user's
larger live Archive or Move to Trash test with exact scope/count preview and explicit
confirmation. This plan does not authorize selecting or mutating live messages on
the user's behalf. The larger live test is desired but separate from the required
500+ synthetic test; do not assume an exact live count or run permanent deletion.

Observe cancellation during the larger test if requested and work remains active.
A job completing before cancellation is clicked is not evidence of cancellation;
retain deterministic simulated tests for that behavior. Record confirmed affected,
failed, skipped, unresolved, and unprocessed counts without mailbox identifiers,
subjects, addresses, or credentials in reports.

**Gate:** acceptance matrix records automated, browser, small-live, and larger-live
results separately, with remaining gaps explicit. No release-readiness claim while
required gates remain unverified. Deployment/publication is a separate decision.

## Tracking each phase

For each task, record status (not started / in progress / blocked / complete),
dependency, concrete deliverable, affected components, acceptance checks, and evidence.
At phase completion, record what changed, checks run/results, limitations, and the
next phase. Phase 1 is complete: the current revised wireframe was approved and locked on 2026-10-28. See
[Phase 1 experience and review gate](PHASE-1-EXPERIENCE.md) and the
[dashboard wireframe](wireframes/sender-dashboard.html). Phase 2 verification and
persistence design is complete; see the [baseline report](PHASE-2-BASELINE-AND-PERSISTENCE.md)
for measurements, open baseline failures and migration contracts. Phases 3–7 remain not started.

## Review refinements — 2026-10-28

Remove the About tab from the MVP. Retain manual merge/separate requirements, with
correction controls to live in Sender addresses rather than a dedicated About section.
Keep the detail pane open; selecting another sender changes its contents. Remove
the close button and the redundant About ellipsis. Keep only “Clean up your inbox.”
in the subtitle. Align Activity and Sign out to the same right edge and width; inset
the date dropdown arrow.

Header sync status must reflect actual indexing state: “Fetching mail…” for initial
fetch, “Fetching older mail…” during backfill, and connection/error status when
blocked. Show “Synced…” only with an actual successful sync timestamp. Do not invent
percentages or an estimated completion time. Empty-index coverage stays unavailable
until metadata arrives.

Single-address senders use ordinary rows labeled “1 sender address.” Sender pagination
has explicit previous/next buttons and a visible range. The design sample has 11
senders over two pages (10 per page). The MVP sender page size is 10. Page selection affects that page only; existing
selections persist on page changes. These refinements are included in the design approved on 2026-10-28. Application
implementation has not begun.

## Selection visibility and pagination refinement

Show the selection/action row only when at least one message is selected. Keep
job progress and Cancel independently visible when work exists; hide the entire
dock when neither selection nor job exists. Place the sender page range and
previous/next controls immediately below the sender list, not in the summary area.

Date changes discard the draft selection and invalidate any open preview. Never
cache selections per date range: all dates → last 7 days → all dates leaves zero
selected throughout both transitions. The same clearing policy applies to scope
and search changes. Confirmed jobs retain their fixed targets. Acceptance check:
select two senders, change dates, switch back, verify all checkboxes remain clear
and the action row stays hidden while existing job controls remain accessible.

## Message paging and all-matching selection clarification

The Messages tab needs independent previous/next controls and a visible message
range so every matching message can be inspected. Show 10 messages per page.
Message paging preserves selected IDs; changing the focused sender resets message
paging but preserves selection. Filter changes reset both pagers and clear selection.

The sender header checkbox selects only messages from sender groups on the current
page. A group checkbox includes all matching children, including message pages not
loaded. There is no whole-result selection button. Explicit selections on additional
sender pages accumulate and still require exact preview and confirmation.

Acceptance: move to message page two without losing selections; verify that page selection stays limited to the current sender page.

## Review refinement — remove whole-result selection

Remove the “Select all matching” button and its explanatory section from the MVP.
Keep the sender-table header checkbox limited to the current page, plus individual
group/address/message checkboxes. Additional pages require explicit selection;
paging preserves prior choices and filter changes still discard them.

Known mockup limitation: Inbox and All received mail currently use identical
synthetic mail counts. Implementation must include archived received messages only
in All received mail, and apply that scope consistently to counts, previews and
selection. Verify with an archived-message fixture in the implementation phases.

Sender pagination: show 10 sender rows per page. Expanded address rows do not count
toward this limit. Message pagination remains independent.

Message pagination: show 10 messages per page in the right-hand detail pane,
independently of the sender list pager.

## Approved design baseline — 2026-10-28

The user approved the current wireframe: “ok i think this looks good. Let's lock it
for now.” Phase 1 is complete. Preserve the
[approved snapshot](wireframes/sender-dashboard-approved.html) as the implementation
reference. Later design changes require an explicit scope update.

The locked baseline includes 10 senders per page and 10 messages per detail page,
Messages and Sender addresses tabs, no About tab or detail close button, current-page
and individual selection without a whole-result selection button, permanent draft
selection clearing on filter changes, a selection row visible only when selected,
and independent job progress/Cancel controls. Sender pagination sits below the list.

Approval covers the design baseline. Mockup behavior remains synthetic; identical
Inbox/All received counts remain a documented limitation to resolve in implementation.
At design lock, Phase 2 had not started. It is now complete as recorded in the
[Phase 2 report](PHASE-2-BASELINE-AND-PERSISTENCE.md). No application implementation,
mailbox action, or publication occurred as part of locking this design.
