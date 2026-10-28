# Sender dashboard MVP

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
| Selection | Entire groups, particular addresses, or individual messages; distinguish visible rows from all matching messages |
| Filters | Search and date range, applied consistently to counts, previews, selection, and actions |
| Actions | Archive, Move to Trash, Mark as Read, with eligible Undo |
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
| Progress/activity | Running work, waiting states, results, failures, eligible Undo |

Select → preview exact scope and affected count → confirm on the dashboard →
background execution → live progress and results. No separate approval queue is
required in the normal journey. Reuse ownership checks, exact message selection,
execution records, and applicable safety checks behind this simpler flow.

Freeze the confirmed message IDs so later filter changes, new mail, or grouping
corrections cannot silently expand an in-flight action. Recheck eligibility before
dispatch; report skipped or changed targets. Archive affects selected messages
still in Inbox; Mark as Read affects selected unread messages. Show effective
counts and explain no-op selections. Move to Trash is not permanent deletion.

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
| Visible states | Queued, running, waiting for Gmail/retry, needs reconnection, completed, partially completed, failed, or awaiting verification |
| Outcome counts | Keep successful, failed, skipped/no-op, unresolved, and remaining counts distinct; “finished” must not imply every target succeeded |
| Usable dashboard | Keep browsing available; mark pending changes and reconcile displayed counts with confirmed results |
| Reopen/reload | Recover job status after navigation or refresh; closing the browser does not cancel server work |
| Restart recovery | Persist completed work and reconcile uncertain requests before resuming; never silently duplicate or assume success |
| Rate limiting | Coordinate sync, backfill, cleanup, and verification budgets; bound concurrency and batch sizes |
| Retry behavior | Respect provider retry guidance, use bounded backoff with jitter for retryable failures, retain completed work, and expose exhausted retries |
| Ambiguous outcomes | A timeout after dispatch can leave the result unknown; verify before replaying a mutation |
| Undo | Apply only to confirmed eligible changes; make availability/expiry clear and prevent racing Undo against active work |

A compact progress panel on the dashboard is the proposed default, with a small
Activity view or drawer for details and history. Final presentation remains open;
live visibility and recoverable status are mandatory. This refines the earlier
idea of removing Activity entirely: simplify navigation without hiding long work.

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
Disable unsubscribe execution in MVP mode, including direct routes and previously
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
| Selection/actions | Exact IDs across pages/filters, effective action counts, duplicate submissions, stale labels, and Undo |
| Long-running work | More than 500 synthetic targets with multiple batches, throttling, partial failure, ambiguous response, reconnect, reload, and worker restart |
| Performance | Representative retained-index and synthetic sizes; measure summary/filter/paging/expansion responsiveness while sync and cleanup run |
| Retained-data upgrade | Existing account/index remains usable after migration; fresh install also works |
| Live Gmail | Controlled selected messages verify actual changes, progress reconciliation, and eligible Undo; separate from simulated-provider evidence |
| Removed surfaces | Old navigation is absent and unsubscribe cannot execute through alternate paths or legacy queued work |

Use the isolated PostgreSQL test harness for destructive fixtures. Do not direct
test cleanup at the retained development database. A 500+ message live mutation
is not required merely to prove the synthetic long-job path. Define measured
performance targets during implementation after establishing a baseline; record
dataset size and results rather than claiming unmeasured throughput.

## Implementation sequence and open decisions

First finalize a wireframe, grouping rules, default date range, and Activity
presentation. Then map reusable query/action components and data migration needs;
implement indexed scope/group queries and durable action execution with progress;
connect the simplified UI and disable deferred actions; validate retained-data
upgrade, fresh setup, simulated failures, browser behavior, and controlled Gmail
changes. Plan Undo timing for long jobs before exposing it in the new flow.

Current source already provides metadata indexing, Gmail label mutations,
execution records, Undo, and Activity polling. `ExactScopeExecutor` currently
dispatches a handler and records completion synchronously; existing sync retries
and Activity polling do not establish durable per-batch cleanup progress. Treat
that capability as work to implement and verify, not a cosmetic progress bar.
