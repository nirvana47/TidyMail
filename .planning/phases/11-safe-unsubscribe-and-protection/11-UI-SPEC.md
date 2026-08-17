# Phase 11: Safe Unsubscribe And Protection - UI Specification

**Status:** Approved for planning
**Date:** 2026-08-09
**Reference:** `tidymail-ux-rehaul/pages/unsubscribe-compact-suite.html`

## Design Intent

The unsubscribe page is a safety review surface, not a promise of instant cleanup.
Users should understand:

- Why a sender is a candidate.
- Which methods were actually detected.
- Where an automated request will go.
- Why a method is safe, blocked, or manual.
- What the latest outcome proves and does not prove.

The page reuses the Phase 9 shell and Phase 10 decision-surface vocabulary. It remains
compact and information-dense, but inspection and execution are deliberately separate.

## Phase Boundary In The UI

Phase 11 may show and execute one explicitly confirmed candidate method.

It must not render:

- `Send to Review`, `Save selection`, staged/immediate policy, approve/apply controls,
  or multi-candidate execution.
- A queued action count caused by Phase 11 direct attempts.
- Archive/trash/mark-read/keep/undo controls.
- Analytics/export controls.

The mock's Review-oriented buttons are replaced with `Inspect` and method-specific
confirmation. Phase 12 will restore selection/policy controls using the same candidate
facts.

## Visual System

Reuse existing local tokens in `workspace.css`.

| Role | Existing token |
|---|---|
| Page background | `--tm-bg` |
| Card/surface | `--tm-surface`, `--tm-surface-subtle` |
| Primary text | `--tm-text`, `--tm-text-strong` |
| Secondary text | `--tm-text-muted` |
| Borders/focus | `--tm-border`, `--tm-primary` |
| Accepted/confirmed | `--tm-success`, `--tm-success-soft` |
| Manual/review/pending | `--tm-warning`, `--tm-warning-soft` |
| Failed/security block | `--tm-danger`, `--tm-danger-soft` |
| Informational/verification | `--tm-info`, `--tm-info-soft` |

No color is the sole carrier of method, confidence, protection, or outcome. Every
badge includes visible text.

Typography remains the existing system stack. Use:

- `22px` route `h1`.
- `14px` section headings.
- `12px` body/table text.
- `11px` metadata/help.
- `10px` uppercase eyebrow/column labels.
- Monospace only for short fingerprints or redacted technical facts.

Spacing uses `--space-1/2/3/4/6`; cards use existing radii/shadow. Do not introduce a
new visual framework or remote icon/font dependency.

## Page Composition

### Shared Shell

- Shared top navigation with Unsubscribe marked `aria-current="page"`.
- Existing account scope, queue count, metadata readiness, privacy note, and sync
  behavior remain.
- Exactly one `<main>` and one `h1`.
- Browse GETs and state fragments perform no outbound unsubscribe request or provider
  API call.

### Route Header

- Eyebrow: `Unsubscribe safety`
- `h1`: `Unsubscribe candidates`
- Purpose: `Inspect detected unsubscribe methods and destinations before choosing one.`
- Keep shared metadata state directly below the heading. Partial/stale data remains
  browseable with its existing qualification.

### Metric Strip

Metrics share the current candidate predicate and state their denominator:

1. `Matching candidates`
2. `RFC 8058 eligible`
3. `Manual or review required`
4. `Protected or verification pending`

Each metric includes a one-line definition. Do not show projected monthly reduction,
success rate without a denominator, or fabricated sample counts.

### Filter Rail

Use real GET forms/controls:

- Method: All, RFC 8058 POST, Legacy HTTPS GET, Mailto, Manual/account page.
- Minimum monthly volume: All, 10+, 20+, 50+, 100+.
- Confidence: All, High, Medium, Needs review.
- Review: All, Ready, Needs review.
- Last received: All, 7 days, 30 days, 90 days, Older than 90 days.
- Protection: Exclude protected (default), Include protected, Protected only.
- Outcome: All, Not attempted, Pending, Accepted, Confirmed, Failed,
  Manual required, Verification pending.
- Account: existing authorized selector.
- Search: sender name/address/domain/list identity.
- Sort: volume, confidence, method, newest, oldest, review need, sender, outcome.

Rules:

- Controls have visible labels and submit canonical query state.
- Filter/search/sort/account changes reset page.
- `Clear filters` is a real canonical link, never `href="#"`.
- Unknown/duplicate/invalid state renders a safe bounded error and no broadened query.
- Phase 11 does not use segmented buttons without form semantics.

### Result Header And Pagination

Show:

- Exact filtered count.
- Active sort text.
- Partial/stale qualifier when applicable.
- Canonical Previous/Next links.
- `aria-live="polite"` result-count announcement after fragment updates.

Use fixed 25-row pages. No infinite scroll.

## Candidate Table

Desktop columns:

1. Sender/list
2. Method
3. Estimated monthly
4. Last received
5. Confidence
6. Protection
7. Latest outcome
8. Recommendation
9. Action

Requirements:

- Semantic `<table>`, visible caption, `th scope="col"`.
- Sender/list cell shows display name, full accessible address/domain, and bounded list
  identity when present.
- Method text distinguishes `RFC 8058 POST`, `Legacy HTTPS GET`, `Mailto`, and
  `Manual/account page`; never label legacy GET "one-click".
- Volume says `Estimated from indexed messages in the last 30 days`.
- Confidence has visible reason text.
- Protected state says `Protected - unsubscribe blocked`.
- Latest outcome includes timestamp and source label where applicable.
- Recommendation examples: `Inspect one-click destination`, `Review legacy page`,
  `Open mail client`, `Open provider message`, `Protected - keep`.
- Action is a real `Inspect` link. Protected rows still allow inspection but never
  render an enabled execute control.
- Long content wraps or ellipsizes visually while preserving complete accessible text.
- Raw URI, URI query, unsubscribe token, provider message ID, and authentication
  header never enter the DOM.

Default sort is `Estimated monthly, highest first`, with stable tie behavior.

## Destination Inspection

Inspection is a separate full route with optional HTMX fragment enhancement.

### Summary

- `h1` remains the page heading; inspection uses `h2`:
  `Inspect unsubscribe method`
- Sender/list identity and account label.
- Latest indexed message time and candidate confidence.
- Protected warning before any method list.

### Detected Methods

For each method show:

- Method label.
- `Automated request` or `Opens another app/page`.
- Normalized destination host, or mail recipient for `mailto:`.
- Redacted path summary such as `/unsubscribe/...` and `Query token hidden`.
- Source facts:
  - `List-Unsubscribe detected`
  - `List-Unsubscribe-Post: One-Click detected`
  - `DKIM passed`
  - `DKIM covers both unsubscribe headers`
- Eligibility/rejection reason in plain language.

Do not offer an ineligible method as an enabled execution choice. It may remain visible
with `Unavailable` and associated reason.

### Method Choices

#### RFC 8058 POST

Primary button: `Send one-click POST`

Confirmation copy:

`TidyMail will send one HTTPS POST to {host}. No mailbox credentials, cookies, or
message content will be sent. A 2xx response means accepted, not independently
confirmed.`

The confirmation displays the exact fixed body label:
`List-Unsubscribe=One-Click`, but not the tokenized destination URI.

#### Legacy HTTPS GET

Secondary/destructive-caution button: `Send legacy HTTPS GET`

Copy:

`TidyMail will make one HTTPS GET request to {host}. The page may require more steps.
TidyMail will not follow redirects or automatically try another method.`

GET is never presented as safer or equivalent to RFC 8058.

#### Mailto

Link: `Open unsubscribe email`

- Shows recipient and whether subject/body instructions are included.
- Announces that the default mail application opens.
- Does not auto-send.
- Adjacent action after return: `I completed this manually`, producing a clearly
  sourced user-confirmed/verification event.

#### Provider/Account Page

Link: `Open source message in Gmail` or `Open account page on {host}`.

- Opens a new tab when browser navigation is used.
- `target="_blank"` with `rel="noopener noreferrer"`.
- Visible or screen-reader copy says `opens in a new tab`.
- No server fetch occurs.

### Confirmation Mechanics

- Execution form is authenticated, CSRF protected, and has one primary submit.
- It submits opaque candidate key, explicit method, and signed intent only.
- Stale/expired/replayed intent errors leave the user on inspection with no request.
- During submission, button copy may become `Sending request...`; JavaScript is not
  required for correctness.
- Focus moves to the outcome heading after an HTMX response; full-page fallback lands
  on the same heading.

## Protected Handling

Protected candidates:

- Remain discoverable when the filter includes them.
- Have visible `Protected` badge plus explanation:
  `This sender is protected. Phase 11 will not send or open an unsubscribe method.`
- Inspection may show detected methods and redacted destination facts.
- Every execute/manual-complete control is absent or native disabled with
  `aria-describedby`.
- No override checkbox or hidden override parameter exists.

If protection changes between inspection and submit, render:

`Unsubscribe blocked because this sender is now protected. No request was sent.`

with `role="alert"`.

## Outcome UI

Render an append-only event list for the candidate's latest attempt.

| State | Label | Required copy |
|---|---|---|
| `PENDING` | `Request pending` | Method and host; do not imply delivery |
| `ACCEPTED` | `Request accepted` | `The endpoint returned 2xx. Future mail has not been independently verified.` |
| `CONFIRMED` | `Confirmed` | Include source, e.g. `Confirmed by you`; never omit source |
| `FAILED` | `Request failed` | Safe reason plus one next step; no exception/network detail |
| `MANUAL_REQUIRED` | `Manual action required` | Name the explicit available alternative |
| `VERIFICATION_PENDING` | `Verification pending` | `The request was accepted or completed manually; suppression is not yet independently confirmed.` |

An accepted attempt may show both `Accepted` and subsequent
`Verification pending` events. Never collapse them into `Success`.

Safe failure copy examples:

- `Destination did not pass network safety checks. No request was sent.`
- `The endpoint redirected, so TidyMail stopped without following it.`
- `The request timed out. The remote result is unknown; TidyMail did not retry.`
- `The endpoint returned a non-success response. No fallback was attempted.`
- `The candidate changed after inspection. Inspect it again before continuing.`

## Empty, Loading, Partial, And Error States

| State | Heading | Behavior |
|---|---|---|
| No index | existing `No indexed messages yet` | Keep real metadata sync action |
| Index running/no rows | existing loading state | No candidate skeletons or fabricated counts |
| Partial rows | `Showing partial unsubscribe candidates` | Render real rows with incomplete-data notice |
| Zero candidates | `No unsubscribe candidates match` | Preserve filters; offer clear filters or sync |
| Zero after filters | `No candidates match these filters` | Do not imply inbox has no subscriptions |
| Parse/manual only | `Manual review required` | Explain malformed/unsupported metadata safely |
| Inspection stale | `Candidate changed` | No request; return to refreshed inspection |
| Security rejection | `Destination blocked` | Alert semantics; no internal IP/DNS detail |
| Transport error | `Request failed` | Safe reason and explicit manual alternatives |

The current shared workspace error state remains authoritative when no usable indexed
rows exist.

## Responsive Contract

### Desktop >980px

- Preserve 226px rail and independent shell scrolling.
- Metric strip is four columns.
- Candidate table uses stable columns and an internally scrollable result region only
  if viewport height requires it.
- Inspection may use two columns: method facts and outcome/context.

### Compact 721-980px

- Existing rail stacks above main.
- Metrics become two columns.
- Toolbar and method cards wrap.
- Table is in a labeled, keyboard-focusable internal horizontal scroll region.
- Page itself does not horizontally scroll.

### Narrow <=720px

- Existing `Filters and account scope` `<details>` pattern.
- Metrics and inspection become one column.
- Candidate rows use an accessible card/table treatment while retaining table
  semantics, or one labeled focusable internal scroll region.
- Destination host, sender, reason, and outcome wrap safely.
- All enabled buttons, links, selects, and checkbox labels are at least 44px.
- Confirmation buttons remain separated to prevent accidental adjacent activation.

### 375px Acceptance

- `document.documentElement.scrollWidth <= document.documentElement.clientWidth`.
- Brand, active route, account, result count, method, destination host, and outcome are
  understandable without overlap.
- No hidden raw URI creates overflow.
- Alerts and confirmation copy wrap at 200% zoom.

## Accessibility Contract

- Skip link remains first focusable element.
- Exactly one `<main>` and one `h1`.
- Filter groups use `fieldset`/`legend` or explicit labels.
- Semantic table caption and scoped headers.
- Active sort uses a labeled select or `aria-sort`.
- Every badge has visible text; confidence/method/outcome are not color-only.
- Protected and unavailable controls use native `disabled` plus visible,
  programmatically associated explanation.
- Result counts and pending-to-outcome changes use `aria-live="polite"`.
- Security failures and protection changes use `role="alert"`.
- Confirmation warning is associated with submit via `aria-describedby`.
- External application/new-tab behavior is announced.
- Focus returns to the triggering `Inspect` link when inspection is dismissed and moves
  to the outcome heading after submit.
- Preserve existing 2px focus indicator, reduced-motion handling, contrast thresholds,
  keyboard operation, and 200% zoom support.

## Copywriting Contract

| Avoid | Use |
|---|---|
| `One click` for a legacy URL | `Legacy HTTPS GET` |
| `Success` after 2xx | `Request accepted` |
| `Unsubscribed` without evidence | `Verification pending` |
| `Safe URL` | `Destination passed current network checks` |
| `Automatic fallback` | `Choose another method` |
| `Monthly reduction` | `Estimated messages in the last 30 days` |
| `Override protection` | `Protected - unsubscribe blocked` |
| Raw technical exception | Bounded safe reason and next step |

All copy distinguishes detected metadata, request acceptance, user attestation, and
independent confirmation.

## UI Considerations

| Category | Element | Status | Resolution |
|---|---|---|---|
| Empty | Candidate results | Covered | Distinguish no index, zero candidates, and zero filtered matches |
| Loading | Shared state/submit | Covered | Server content first; pending copy never promises completion |
| Partial/stale | Results/inspection | Covered | Real rows remain usable; stale intent blocks execution |
| Error | Parse/security/transport | Covered | Safe alerts with no raw network or URI detail |
| Zero/one/many | Metrics/results/events | Covered | Exact counts, fixed pages, append-only event ordering |
| Disabled | Protected/ineligible methods | Covered | Native disabled or absent controls plus visible reason |
| Destructive/irreversible | POST/GET confirmation | Covered | Separate inspect and explicit method confirmation |
| External navigation | Mailto/provider/account page | Covered | Announce app/new tab and never auto-send/fetch |
| Overflow | Hosts/senders/reasons/table | Covered | Wrap/ellipsis with accessible full text and internal table region |
| Privacy | Destination/auth facts | Covered | Host/redacted shape only; no URI tokens/raw headers/provider IDs |

## Verification Expectations

- MockMvc renders every filter, sort, state, method, protected state, and outcome from
  real fixtures with canonical URL replay.
- Rendered HTML contains no raw URI query/token, provider message ID, raw
  authentication header, `href="#"`, or Phase 12 controls.
- Browse/inspection GETs cause zero `MailProvider` and zero unsubscribe transport calls.
- Protected and ineligible methods cannot submit.
- Static CSS assertions cover breakpoints, focus, reduced motion, target sizes, and
  overflow declarations; browser layout proof remains integrated Phase 13 UAT.
- Security behavior is verified at service/transport boundaries, not inferred from UI
  labels.

## Approval

Autonomously approved for Phase 11 planning. It follows the compact unsubscribe mock,
Phase 9 shell, Phase 10 decision-surface patterns, metadata-only privacy constraints,
and the locked Phase 11/12 boundary.
