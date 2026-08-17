# Phase 12: Review, Dual Execution Modes, History, And Undo - UI Specification

**Status:** Approved for planning
**Date:** 2026-08-09
**Reference:** `tidymail-ux-rehaul/pages/review-timeline-compact-suite.html`

## Design Intent

Review is the control and evidence surface for mailbox decisions. It must answer:

- What exact action did I choose, for which owned account and target summary?
- Is it staged or immediate, and did both modes receive the same safety checks?
- What is blocked by a conflict?
- What was requested, accepted, succeeded, failed, or left verification-pending?
- Which applied targets are still eligible for undo?
- Why is unsubscribe or protect not mechanically reversible, and what can I do next?

The compact mock guides density, grouping, metric placement, and side-summary layout.
Its sample data, decorative controls, `href="#"`, unsupported delete wording, and
single blanket apply behavior are not implementation truth.

## Phase Boundary

Phase 12 UI includes:

- Action controls on Senders, Unread, and Unsubscribe.
- Explicit staged/immediate choice after exact-scope preview.
- Queued Review grouped by action and account.
- Remove, approve selected, approve all eligible, and conflict resolution.
- Outcome summaries, durable History filters, undo, and compensation.

It excludes Phase 13 charts/export and does not claim long-horizon unsubscribe
confirmation.

## Visual System

Reuse the Phase 9-11 local `workspace.css` tokens and system font. No remote font,
icon, CSS framework, or new SPA pipeline.

| Meaning | Token treatment |
|---|---|
| Queued/informational | `--tm-info`, `--tm-info-soft` |
| Warning/conflict/pending | `--tm-warning`, `--tm-warning-soft` |
| Succeeded/reverted | `--tm-success`, `--tm-success-soft` |
| Failed/destructive | `--tm-danger`, `--tm-danger-soft` |
| Neutral/local decision | existing surface/border/muted tokens |

Color never carries status alone. Every badge includes visible text.

Use existing type and spacing scale:

- route `h1`: 22px;
- section/group headings: 14px;
- row/body text: 12px;
- metadata/help: 11px;
- uppercase eyebrow/column labels: 10px.

## Source-Surface Action Flow

### Exact-Scope Preview

Phase 10 sender/unread previews and Phase 11 unsubscribe inspection gain a shared
confirmation section only after server preflight returns ready.

Show:

- action label;
- safe target summary;
- account label;
- exact message/candidate count;
- protected/high-risk exclusions or explicit override audit state;
- conflict warning if an active action already overlaps;
- scope fingerprint shortened for human comparison, never exact IDs;
- statement: `Both modes use this exact scope and the same safety checks.`

The template model contains an ID-free preview DTO. Provider message/thread IDs and
full unsubscribe destinations never enter the DOM.

### Mode Choice

Use a real `fieldset` and `legend`:

`When should TidyMail run this action?`

Options:

- `Stage for Review` (default): `Nothing changes until you approve it on Review.`
- `Run after this confirmation`: `Uses the same exact scope, conflict checks, and
  outcome tracking, then starts now.`

Immediate mode requires an unchecked confirmation:

`I understand this action will start after the same preflight completes.`

The submit labels are mode-specific:

- `Send exact scope to Review`
- `Confirm and run exact scope`

Changing mode does not create a new scope or hide risk/conflict facts. A stale preview,
new conflict, protection change, or expired unsubscribe intent returns to preview with
no side effect.

### Surface Actions

Sender controls: Archive, Trash, Mark read, Keep, Protect, Review.

Unread controls: Archive, Trash, Mark read, Keep, Review. Protect may link to the
sender-level confirmation when a normalized sender is available; it must not infer a
sender from missing/ambiguous metadata.

Unsubscribe controls: Save selection to Review or run the explicitly selected eligible
method now. One candidate/method is one consent item. Protected or ineligible methods
remain non-executable.

## Review Page Composition

### Shared Shell

- Shared top navigation with Review marked `aria-current="page"`.
- Existing owned account selector, queue count, metadata state, privacy note, and sync
  behavior remain.
- Exactly one `main` and one `h1`.
- GET rendering performs no provider or unsubscribe transport call.

### Header And View Tabs

- Eyebrow: `Approval and action history`
- `h1`: `Review actions`
- Purpose: `Approve staged exact scopes, resolve conflicts, and verify what happened.`

Two real links or submit-free tabs:

- `Queued`
- `History`

Use canonical `view=queued|history`; preserve authorized account filter. Active view
uses `aria-current="page"`.

### Queued Metric Strip

Metrics use the same authorized queued predicate:

1. `Queued actions`
2. `Exact targets`
3. `Conflicts to resolve`
4. `Eligible to approve`

Each has a visible denominator/definition. Do not count needs-decision `REVIEW` rows
as eligible. Do not show projected cleanup or fabricated mailbox changes.

### Queue Filters

Real GET controls:

- account: owned account or all;
- action: all, archive, trash, mark read, unsubscribe, keep, protect, needs decision;
- state: all, eligible, conflicted, needs decision;
- target: bounded safe target search.

Unknown, duplicate, foreign, or malformed recognized values fail closed. `Clear
filters` is a canonical link.

## Queued Groups And Rows

Required groups, in stable order:

1. Archive
2. Trash
3. Mark read
4. Unsubscribe
5. Keep
6. Protect
7. Needs decision

Each group header shows action count, account count, exact target count, and
conflict/eligibility summary. Within a group, account subheadings prevent a
cross-account action from appearing atomic.

Each row contains:

- native selection checkbox for eligible executable rows;
- safe target label and bounded explanation;
- owned account label;
- exact target count;
- mode badge (`Staged`);
- status badge;
- conflict text/link when blocked;
- queued time;
- CSRF-protected `Remove` form.

Rows never contain exact/provider IDs, scope database IDs, target fingerprints,
unsubscribe tokens, lease values, or idempotency values. Form paths use opaque action
public IDs.

`Needs decision` rows have no approval checkbox. Primary action is `Choose action`,
which returns to a scoped decision page without changing the immutable scope.

## Conflict UI

A conflicted row says, for example:

`Conflicts with Trash for 12 of 12 targets. Resolve this before execution.`

`Review conflict` opens an in-page/full-page detail with:

- both action labels and account;
- safe target summaries;
- exact overlap count;
- plain-language incompatibility;
- creation times;
- resolution choices.

Allowed resolution controls:

- `Keep Archive; remove Trash`
- `Keep Trash; remove Archive`
- `Keep both` only when the server compatibility matrix permits it
- `Return without changes`

Resolution is a CSRF POST. It cancels an action or records compatibility; it never
edits a scope, subtracts hidden IDs, or auto-selects a winner.

## Approval Controls

### Approve Selected

Sticky/side summary displays selected action, account, and exact target counts.

Button: `Approve selected actions`

Confirmation states:

`You are approving {actions} actions across {accounts} accounts and {targets} exact
targets. Each action will record its own outcome.`

If any selected row becomes stale/ineligible, none are reserved and focus moves to an
alert naming safe row labels.

### Approve All Eligible

Button: `Approve all eligible`

This is not a visible-page action. First submit renders a confirmation with
server-derived:

- eligible actions;
- accounts;
- exact targets;
- excluded conflict/needs-decision counts.

Final button: `Approve {N} eligible actions`

If the server-side set changes, confirmation expires and the user reviews the new
count. Over-limit sets require narrowing filters and are never truncated.

### Execution Feedback

After reservation:

- `Execution started` with action/account counts;
- a polite live region for updates;
- no promise that all targets succeeded.

HTMX may refresh result fragments, but full-page POST/redirect/GET remains complete.
Focus moves to the batch outcome heading.

## Outcome Summary

Every execution summary displays:

- Requested
- Accepted
- Succeeded
- Failed
- Verification pending

If reserved targets were not dispatched, show `Not dispatched` as well.

Use explicit arithmetic copy:

`24 requested: 20 succeeded, 2 failed, 2 verification pending.`

Status labels:

| State | Visible label | Required meaning |
|---|---|---|
| Reserved/running | `In progress` | Durable reservation exists; completion unknown |
| Applied | `Applied` | All targets have affirmative success |
| Partial | `Partially applied` | Mixed succeeded and failed/pending |
| Verification pending | `Verification pending` | Accepted or unknown; not proven complete |
| Failed | `Failed` | No target affirmatively succeeded/pending |
| Cancelled | `Removed from Review` | Never executed |

Per-target failure summaries are grouped by bounded safe reason and count, such as
`2 targets: provider rejected the request`. Provider IDs and raw exception text are
never shown.

Unsubscribe accepted copy:

`The endpoint accepted the request. Future mail is not independently confirmed.`

## History View

### Metric Strip

Use facts in the current filter:

1. `Actions`
2. `Succeeded targets`
3. `Failed targets`
4. `Verification pending`

Metrics show the date/account denominator and do not reinterpret accepted as success.

### Filters

- From date
- To date
- Account
- Action type
- Status
- Target search

Visible labels, canonical GET submission, fixed 25-row pages, and `Clear filters`.
Date errors and overlarge ranges render bounded alerts without querying broadly.

### History Rows

Desktop columns:

1. Time
2. Target
3. Account
4. Action
5. Mode
6. Outcome
7. Counts
8. Follow-up

Target is approved safe summary text, never a provider ID. Outcome may expand to an
append-only event timeline:

```text
Queued -> Execution started -> Partially applied -> Undo partially applied
```

Legacy rows show:

`Legacy action - per-target outcome detail is unavailable.`

Do not fabricate requested/accepted/succeeded breakdowns for legacy rows.

## Undo UI

Undo appears only when the server says an owned archive/trash/mark-read action has
ledger-recorded succeeded targets and remains within its window.

Copy:

`Undo will target only the {N} messages recorded as applied. Failed and
verification-pending messages are excluded.`

Show remaining eligibility time as text, but server time is authoritative.

Button:

- `Undo {N} applied messages`

Confirmation repeats original action, account, applied count, excluded failed/pending
count, and inverse. Undo is a CSRF POST with opaque action ID and server intent only.

Partial undo copy:

`Undo reverted {N}; {M} failed; {P} need verification. The original action remains in
history.`

Expired, already-used, foreign, or zero-applied undo controls are absent. Crafted
submits produce safe alerts and no provider call.

## Non-Reversible Compensation

For Unsubscribe:

- label: `No automatic undo`
- copy: `TidyMail cannot guarantee resubscription. Open the source message or account
  page to review options.`
- action: `Open provider options` with announced new-tab behavior.

For Protect:

- label: `No automatic undo`
- copy: `Protection is a durable decision. Removing it creates a new reviewed action
  and does not erase this history.`
- action: `Review removing protection`

Compensation rows link visibly to the original action and have their own outcome.
Never relabel compensation as Undo.

## Empty, Loading, Partial, And Error States

| State | Heading | Contract |
|---|---|---|
| No queued actions | `No actions are waiting for approval` | Link to decision surfaces; no fake metrics |
| Filters exclude queue | `No queued actions match these filters` | Preserve filters; clear link |
| Only needs decision | `Choose an action before approval` | No approve-all eligibility |
| Conflicts only | `Resolve conflicts before approval` | Conflict count and links |
| Reservation active | `Actions are in progress` | Poll facts; do not resubmit |
| Partial result | `Some targets were not applied` | Exact counts and safe reasons |
| Verification pending | `Some results still need verification` | No retry promise |
| Empty history | `No action history matches` | Preserve date/account filters |
| Stale confirmation | `Action set changed` | No execution; regenerate counts |
| Ownership/CSRF failure | bounded security response | No existence or token detail |
| Provider unavailable | `Provider unavailable` | No false failure for undispatched targets |

Server-rendered content is authoritative. Skeletons and optimistic success are not
used.

## Responsive Contract

### Desktop >980px

- Preserve 226px rail and workspace shell.
- Metrics use four columns.
- Groups and history use dense semantic tables/cards.
- Approval summary may be sticky, but never obscures the final row or focus target.

### Compact 721-980px

- Rail stacks above content.
- Metrics use two columns.
- Group toolbars wrap.
- Wide tables use a labeled keyboard-focusable internal scroll region.
- Page itself does not horizontally scroll.

### Narrow <=720px

- Filters use the established `Filters and account scope` details pattern.
- Metrics and summaries stack.
- Rows use accessible card/table treatment with visible field labels.
- Batch controls stack with destructive/approval buttons separated.
- All enabled controls and checkbox labels are at least 44px.
- Long sender/domain/reason text wraps without exposing hidden identifiers.

### 375px Acceptance

- `document.documentElement.scrollWidth <= document.documentElement.clientWidth`.
- Brand, active view, account, action, target summary, status, counts, and primary
  follow-up remain understandable.
- No sticky control traps content or keyboard focus.
- Alerts and outcome arithmetic remain readable at 200% zoom.

## Accessibility Contract

- Skip link is first focusable element.
- Exactly one `main` and one `h1`.
- View tabs, filters, action groups, and confirmation fieldsets have semantic labels.
- Tables have captions and scoped headers; card transformations retain associations.
- Checkboxes name target, action, account, and count.
- Selection count and outcome changes use `aria-live="polite"`.
- Conflict, stale, security, and partial failure summaries use `role="alert"` when
  newly rendered.
- Disabled/unavailable controls use native semantics and associated reason text.
- Focus moves to confirmation/outcome/conflict headings after navigation or HTMX
  replacement and returns to the triggering row when a detail is dismissed.
- Status/action/mode/conflict is never color- or icon-only.
- Existing 2px focus style, contrast, reduced motion, keyboard operation, and 200%
  zoom support remain.

## Copywriting Contract

| Avoid | Use |
|---|---|
| `Run immediately` without qualification | `Run after this confirmation` |
| `Apply all` | `Approve all eligible` |
| `Success` for mixed/accepted outcomes | `Applied`, `Partially applied`, or `Verification pending` |
| `Unsubscribed` after 2xx | `Request accepted; verification pending` |
| `Undo all` | `Undo {N} applied messages` |
| `Undo protection` | `Review removing protection` |
| `Undo unsubscribe` | `Open provider options` |
| `Delete` | `Trash` |
| Raw exception/provider reason | Bounded safe reason and count |
| `IDs` in user copy | `Exact targets` or `applied messages` |

## UI Considerations

| Category | Element | Status | Resolution |
|---|---|---|---|
| Empty | Queue/history/groups | Covered | Distinct no-data, filtered, conflict-only, and needs-decision states |
| Loading | Reservation/execution | Covered | Durable in-progress state; no optimistic success |
| Partial | Execution/undo | Covered | Exact arithmetic and per-reason counts |
| Stale | Preview/selected/all confirmation | Covered | Set change blocks execution and regenerates confirmation |
| Conflict | Queue rows/detail | Covered | Explicit overlap, choices, and no silent scope editing |
| Disabled | Ineligible/needs decision/expired undo | Covered | Native semantics plus associated explanation |
| Destructive | Trash/unsubscribe/approval | Covered | Exact scope, mode, account, and count confirmation |
| External | Unsubscribe compensation | Covered | Announced new tab; no server fetch |
| Overflow | Tables/senders/reasons | Covered | Responsive cards or internal focusable scroll |
| Privacy | Forms/DOM/history | Covered | Opaque action IDs only; no provider IDs, tokens, fingerprints, or URIs |

## Requirement Coverage

| Requirement | UI evidence |
|---|---|
| SEND-06 | Sender preview exposes all required decisions and two modes |
| UNRD-07 | Unread preview uses identical mode/preflight contract |
| UNSB-07 | Save to Review or explicit immediate method after Phase 11 inspection |
| ACTN-01 | Default staged plus explicit immediate opt-in; same-scope copy |
| ACTN-04 | Six required grouped queue sections |
| ACTN-05 | Remove, selected approval, server-counted eligible-all confirmation |
| ACTN-06 | Conflict badges, detail, and explicit resolution controls |
| ACTN-07 | Five required counts, partial copy, safe failure summaries |
| ACTN-08 | Applied-count-only undo confirmation and outcome |
| ACTN-09 | Distinct non-reversible explanation and compensation controls |
| ACTN-10 | Date/account/action/status/target history filters |

## Verification Expectations

- MockMvc renders every queue group, mode, conflict, status, outcome, undo, compensation,
  filter, empty, stale, and partial state from database fixtures.
- Rendered HTML contains no provider message/thread ID, exact scope ID, target
  fingerprint, raw unsubscribe URI/query/header, OAuth value, lease/idempotency token,
  `href="#"`, mock metric, or Phase 13 control.
- All GETs make zero provider/transport calls.
- Every mutation requires CSRF and rechecks ownership/preflight.
- Static CSS checks cover breakpoints, focus, reduced motion, touch targets, and
  overflow. Integrated browser layout judgment remains Phase 13, but no Phase 12
  safety behavior depends on it.

## Approval

Autonomously approved for Phase 12 planning. It applies the review mock's compact
grouping to the existing local workspace system while preserving exact-scope,
ownership, truthful outcome, accessibility, and DOM privacy constraints.
