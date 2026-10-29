# Sender dashboard MVP

## Desktop-first scope — 2026-10-28

The user confirmed that TidyMail is a desktop-first application. Mobile layouts,
mobile browser checks, narrow-screen acceptance and a mobile version are outside
the MVP. Do not spend further implementation or testing effort on mobile unless
the user explicitly brings it back into scope. Preserve the work already done;
it creates no continuing mobile requirement. Desktop keyboard/accessibility checks
remain in scope. This decision supersedes earlier mobile/responsive requirements,
including those in the approved wireframe and historical phase documents.

Agreed product direction, 2026-10-28. Planning only: this document does not claim
the new dashboard or background cleanup workflow is implemented. It supersedes
older product priorities where they conflict; current implementation evidence
remains in [Project](../PROJECT.md), [Codebase](CODEBASE.md), and [Status](STATUS.md).

## Purpose and scope

Make TidyMail a dashboard for finding repeat senders, inspecting their messages,
and applying bulk Gmail actions. Keep the main journey on one screen.

| Area | MVP decision |
|---|---|
| Default scope | Inbox, including both read and unread messages |
| Scope toggle | All received mail, including archived mail, excluding Spam and Trash |
| Exclusions | Spam and Trash are excluded from both scopes; sent-only mail and drafts are not received mail |
| Main view | Sender groups, collapsed by default, highest matching message count first |
| Group expansion | Individual sender addresses, counts, and message previews using subjects and dates |
| Selection | Entire groups, particular addresses, or individual messages; support current-page selection and explicit group/address/message selection |
| Filters | Search and date range, defaulting to all indexed dates; applied consistently to counts, previews, selection, and actions |
| Actions | Archive, Move to Trash, Mark as Read; confirmation required, no Undo |
| Action scope | Existing selected messages only; no rules for future messages |
| Account | One connected Gmail account in the UI, with its address and Sign out |
| Feedback | Sync status, live bulk-action progress, results, and recent activity |

“Sender group” identifies who mail comes from, not its category or legal company
identity. Amazon and Nextdoor are both sender groups. Receipts, promotions, and
social updates describe kinds of messages; one group can contain several kinds.
Categories are deferred until the core dashboard works well.

Use reliable domain or mailing-list evidence to bring related addresses
together. Do not merge solely on display-name similarity, or put unrelated
senders using shared domains such as gmail.com into one group. Provide manual
merge/separate corrections. Exact automatic grouping rules remain a design task;
uncertain matches should stay separate and contributing addresses must be visible.

## Screen and action journey

| Area | Contents |
|---|---|
| Header | Connected email, sync status, Sign out |
| Controls | Inbox / All received mail, search, date range |
| Summary | Matching messages, unread messages, sender groups |
| Table | Sender group, message count, unread count, latest received; expandable details |
| Selection toolbar | Mark as Read, Archive, Move to Trash |
| Progress/activity | Bottom progress row plus Activity drawer: running work, Cancel, waiting states, results, and failures |

Select → preview exact scope and affected count → confirm on the dashboard →
background execution → live progress and results. No separate approval queue is
required in the normal journey. Reuse ownership checks, exact message selection,
execution records, and applicable safety checks behind this simpler flow.

Freeze the confirmed message IDs so later filter changes, new mail, or grouping
corrections cannot silently expand an in-flight action. Recheck eligibility before
dispatch; report skipped or changed targets. Archive affects selected messages
still in Inbox; Mark as Read affects selected unread messages. Show effective
counts and explain no-op selections. Move to Trash is not permanent deletion.

## Visual direction — reference-led revision (2026-10-28)

The user rejected the first wireframe and prefers the supplied “TidyMail Inbox
Cleanup Dashboard” image. The revised [wireframe](wireframes/sender-dashboard.html)
uses that image as visual direction; its depicted data and selection counts are
not requirements or authorization to mutate mail. Phase 1 review was approved on 2026-10-28; the current design is locked.

| Area | Revised MVP behavior |
|---|---|
| Desktop layout | Compact header, scope/search/date controls and summary above a sender table; a right-hand detail pane opens for the focused group. |
| Sender rows | Initial badge, readable label, address count, matching/unread counts, latest date, and inline address expansion. Focus, expansion, and selection are independent. |
| Detail pane | Messages and Sender addresses tabs. Messages show indexed subject, sender, received date, read state and checkbox. Grouping corrections remain available through a compact control in Sender addresses. |
| Action dock | Persistent bottom selection count, Clear selection, mutually exclusive action choice, then Review and confirm. Choosing an action does not execute it. |
| Confirmation | On-dashboard modal with exact targets, effective/no-op counts and explicit action-specific confirmation. No separate Review navigation. |
| Job feedback | Compact progress row beneath the selection bar; Cancel remains reachable, with full outcome accounting in Activity. The job and current draft selection are separate. |
| Small screens | Deferred; no MVP implementation or acceptance requirement unless the user explicitly requests it later. |

Use mixed-state parent checkboxes: 438 selected means all 438 targets, not a partially
checked child list. Deselecting an address/message updates every representation and
the exact count. Opening a sender or switching detail tabs never selects messages.
An active job retains its original count even when the current selection changes.
Prevent overlapping active mutation targets and explain the conflict.

This adds moderate front-end state/layout work, not a new queue, service, or Gmail
sync architecture. Reuse the same paged local-index queries and selection IDs for
both panes; do not build independent selection models. Defer body snippets, remote
brand/logo lookup, company enrichment and a full message reader. Initial badges
are the baseline; readable names may use conservative local labels or manual names,
with actual contributing domains/addresses visible. Names/icons never establish
grouping identity. The reference's account caret does not add account switching.
Its second preview line is sender metadata in the MVP, not downloaded body text.

## Performance and live progress are MVP requirements

Browsing must query the local metadata index. Switching Inbox / All received
mail must not trigger a full Gmail download. Use server-side aggregation and
paging, load expanded details on demand, and show index coverage/freshness so
partially indexed results are not presented as complete mailbox totals.

Bulk actions, including selections of more than 500 messages, must execute as
persisted background work rather than holding a browser request open until all
provider calls finish. Reuse the existing application/database where practical;
this requirement does not prescribe a new queue service or infrastructure.

| Requirement | Expected behavior |
|---|---|
| Honest progress | Show confirmed outcomes against the fixed target count; update after acknowledged batches, not a timer-driven animation |
| Visible states | Queued, running, waiting for Gmail/retry, needs reconnection, cancel requested, cancelled, completed, partially completed, failed, or awaiting verification |
| Outcome counts | Keep successful, failed, skipped/no-op, unresolved, cancelled-before-dispatch, and remaining counts distinct; “finished” must not imply every target succeeded |
| Usable dashboard | Keep browsing available; mark pending changes and reconcile displayed counts with confirmed results |
| Reopen/reload | Recover job status after navigation or refresh; closing the browser does not cancel server work |
| Restart recovery | Persist completed work and reconcile uncertain requests before resuming; never silently duplicate or assume success |
| Rate limiting | Coordinate sync, backfill, cleanup, and verification budgets; bound concurrency and batch sizes |
| Retry behavior | Respect provider retry guidance, use bounded backoff with jitter for retryable failures, retain completed work, and expose exhausted retries |
| Ambiguous outcomes | A timeout after dispatch can leave the result unknown; verify before replaying a mutation |
| Cancellation | Offer Cancel while work is queued or active; stop further mutation dispatch, preserve completed changes, reconcile in-flight requests, and report final affected counts. No pause, resume, or Undo |

Use a compact progress panel on the dashboard and an Activity drawer for details
and recent history. Cancel is available immediately while work is queued or active;
it does not wait for a three-minute threshold. Persist the cancellation request
and show “Cancellation requested” while already dispatched requests settle.
Cancellation does not reverse completed changes or retract requests already sent
to Gmail. Stop new mutation batches and mutation retries after cancellation takes
effect; allow read-only verification of uncertain outcomes. Cancellation survives
reloads and worker restarts, and cancelled work cannot resume automatically.

Show how many messages were confirmed changed before cancellation took effect,
including already dispatched requests that finish after the click. Keep failed,
skipped, unresolved, and cancelled-before-dispatch targets separate. Never report
an unknown outcome as unchanged. Example: “Cancelled: 120 changed, 380 not
processed”; if needed, add “5 outcomes still being checked” with consistent totals.
There is no Undo in the MVP. Confirmation must explain that cancellation stops
remaining work and does not reverse changes already made.

Google documents rate/concurrency limits, backoff, and the fact that HTTP batching
does not remove individual request costs. Choose request types and batch sizes
against current official documentation, not an assumption that 500 messages is
a provider threshold or that a fixed completion time can be guaranteed.
References: [Gmail errors](https://developers.google.com/workspace/gmail/api/guides/handle-errors),
[usage limits](https://developers.google.com/workspace/gmail/api/reference/quota),
and [HTTP batching](https://developers.google.com/workspace/gmail/api/guides/batch).

## Simplify the existing product

Remove Unread Only, Unsubscribe, multi-account management/settings, the separate
Review journey, and standalone analytics navigation from the MVP experience.
Retain useful underlying code rather than undertaking unrelated deletion/refactoring.
Disable legacy Undo entry points in MVP mode; retain historical records and useful
underlying code. Disable unsubscribe execution in MVP mode, including direct routes and previously
queued unsubscribe actions; hiding navigation alone is insufficient. Retain basic
account identity, Sign out, and access to local-data deletion without a broad
settings area. Do not discard linked-account records just to simplify the UI.

Later iterations may use Spam/Trash as examples to identify similar messages in
Inbox or All received mail. Keep existing indexed data/capabilities useful for that
work, but do not expose those folders in MVP selection or implement learning now.
Unsubscribe and automatic rules for future messages are also outside this MVP.

## Preserve-first implementation and testing

The user reports an already connected inbox and substantial downloaded metadata.
Prefer retaining that connection, index, sync cursors, and useful history for
realistic browsing/performance checks. Do not force a reconnect or full reindex
merely because the UI changes. Prefer additive migrations and derived group data
that can be rebuilt from existing metadata.

Preservation must not compromise code or app quality. The user permits a fresh
start if necessary for end-to-end validation. First prefer an isolated test
database/profile or controlled test mailbox. If a reset of the existing local
index is necessary, record why and make a recoverable backup of local data and
required configuration before resetting. Keep credentials and mailbox data out
of Git and test reports. Resetting local TidyMail data is distinct from deleting
messages in Gmail; fresh-start testing is not authorization for broad live-mail
deletion. No reset or mailbox mutation is part of this planning change.

| Acceptance area | Required evidence |
|---|---|
| Scope correctness | Read and unread Inbox mail; archived mail only in All received mail; Spam/Trash excluded from both, including during selection |
| Grouping | Related address variants, mixed message categories, shared-domain counterexamples, and merge/separate corrections |
| Selection/actions | Exact IDs across pages/filters, effective action counts, duplicate submissions, stale labels, confirmation, and cancellation |
| Long-running work | More than 500 synthetic targets with multiple batches, throttling, partial failure, ambiguous response, reconnect, reload, worker restart, and cancellation before/during dispatch or retry |
| Performance | Representative retained-index and synthetic sizes; measure summary/filter/paging/expansion responsiveness while sync and cleanup run |
| Retained-data upgrade | Existing account/index remains usable after migration; fresh install also works |
| Live Gmail | Small user-selected sets first, then a larger user-confirmed Archive/Trash test if those pass; verify actual changes, progress reconciliation, and cancellation where observable; separate from simulated-provider evidence |
| Removed surfaces | Old navigation is absent; unsubscribe and Undo cannot execute through alternate paths in MVP mode, including legacy queued unsubscribe work |

Use the isolated PostgreSQL test harness for destructive fixtures. Do not direct
test cleanup at the retained development database. A 500+ message live mutation
is not required merely to prove the synthetic long-job path. The user also wants
a larger live Archive or Move to Trash test after small live selections pass;
preview and confirm that selection before execution. Define measured
performance targets during implementation after establishing a baseline; record
dataset size and results rather than claiming unmeasured throughput.

## Implementation sequence and open decisions

Follow [Implementation strategy](IMPLEMENTATION-STRATEGY.md), one phase at a time.
The agreed defaults are all indexed dates, conservative evidence-based grouping
with manual corrections, a compact progress panel and Activity drawer, wireframe
review before interface implementation, and small user-selected live Gmail tests.
Confirmation and cancellation replace Undo. Exact grouping rules and the wireframe
are the first design deliverables; application implementation has not begun here.

Current source already provides metadata indexing, Gmail label mutations,
execution records, Undo, and Activity polling. `ExactScopeExecutor` currently
dispatches a handler and records completion synchronously; existing sync retries
and Activity polling do not establish durable per-batch cleanup progress. Treat
that capability as work to implement and verify, not a cosmetic progress bar.

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
At design lock, Phase 2 had not started. Baseline verification and persistence
design is now complete; see the [Phase 2 report](PHASE-2-BASELINE-AND-PERSISTENCE.md).
No application implementation, mailbox action, or publication occurred as part of
locking this design.
