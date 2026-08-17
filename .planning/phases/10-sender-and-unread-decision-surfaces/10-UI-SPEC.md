---
phase: 10-sender-and-unread-decision-surfaces
status: approved-contract
created: 2026-08-09
frontend: true
framework: thymeleaf-htmx
---

# Phase 10 UI Specification

## Intent

Enable dense, explainable sender-level and unread-message triage using the Phase 9
workspace shell. The interface helps users inspect and preview exact impact; it does
not queue or execute mailbox actions in this phase.

Canonical visual references:

- `tidymail-ux-rehaul/pages/by-sender-compact-suite.html`
- `tidymail-ux-rehaul/pages/unread-compact-suite.html`
- `.planning/phases/09-metadata-index-and-action-foundations/09-UI-SPEC.md`
- `src/main/resources/static/css/workspace.css`

## Design System

Reuse Phase 9 tokens and local CSS only:

| Token | Value |
|---|---|
| Background | `--tm-bg: #f4f6fb` |
| Surface | `--tm-surface: #ffffff` |
| Strong text | `--tm-text-strong: #0f172a` |
| Muted text | `--tm-text-muted: #5b6478` |
| Border | `--tm-border: #d9e0ee` |
| Primary | `--tm-primary: #3f5cff` |
| Success | `--tm-success: #16794f` |
| Warning | `--tm-warning: #8a5a16` |
| Danger | `--tm-danger: #b83232` |
| Spacing | 4, 8, 12, 16, 24px |
| Radius | 6, 10, 16px |
| Top/rail/max | 58px / 226px / 1480px |

Typography remains system sans-serif with compact desktop density: 22px route `h1`,
14px section headings, 12px body/table text, and 10-11px eyebrow/helper text.
Narrow layouts use 14px minimum body/control text. Provider IDs/hashes may use the
system monospace stack.

No Tailwind runtime, icon CDN, component library, SPA, or frontend build is added.
Text labels remain present if icons are later introduced.

## Shared Page Anatomy

Both routes retain:

1. Skip link.
2. Shared top navigation with active route, selected account, and queued count.
3. Shared left rail, now populated with real GET filters.
4. Exactly one `<main>` and `<h1>`.
5. Existing workspace sync/state fragment.
6. Four-cell filtered summary strip.
7. Search/sort toolbar.
8. Sticky selection/preview strip.
9. Semantic result table and pagination.
10. Privacy note and real next-route footer.

The initial response contains complete server-rendered results. htmx may replace one
single-root results/preview fragment but is enhancement only; all controls work as
normal GET/POST forms without JavaScript. A normal preview POST re-renders the full
preview response page in the shared shell with a canonical `Change selection` link
back to the originating filtered route; only an htmx POST returns the standalone
single-root preview fragment.

## URL And Form Behavior

- Every browse control is a named GET input.
- Applying search/filter/sort emits the canonical query and resets `page=0`.
- Account selection preserves route-specific normalized filters and resets page.
- Pagination links preserve all normalized query values.
- Canonical links/forms include one value for every normalized parameter in stable
  order. Duplicate recognized parameters, unknown parameters, invalid UTF-8/control
  text, and out-of-range pages return bounded HTTP 400 errors rather than broadening.
- The rendered result count describes the full filtered set, not only the page.
- Invalid URL values return the shared error treatment with useful correction copy;
  they never silently broaden to all accounts or all rows.
- Search labels are visible. Placeholder text is supplementary.
- Filter groups use `<fieldset>` and `<legend>` where related controls are grouped.

## Senders Surface

### Heading

- Eyebrow: `Sender cleanup`
- `h1`: `Sender decisions`
- Purpose: `Review indexed sender patterns and preview exact cleanup impact. No mailbox changes are applied here.`

### Filters

Left rail order:

1. Account scope.
2. Window: 7 days, 30 days, 90 days, All indexed.
3. Sender type.
4. Risk.
5. Unread pressure.
6. Queue state.
7. Protected state.

Toolbar:

- Search label: `Search senders`
- Hint: `Display name, address, or domain`
- Sort select with all six required sorts.
- `Apply filters` and real `Clear filters` link preserving account only.

The protected default visibly reads `Protected: excluded`. `Include` affects browse
visibility only; it does not authorize bulk materialization. Override authorization
is a separate POST control in the exact preview flow.

### Metrics

The four cells are computed from the current filtered predicate:

1. `Matching senders`
2. `Matching messages`
3. `Unread messages`
4. `Protected senders`

Each metric includes helper copy defining its denominator. No archive/trash candidate
count is shown unless it is derived by the same deterministic recommendation rules.

### Sender Table

Caption: `Sender results for the current filters`

Columns:

1. Selection checkbox.
2. Sender: display name, full address, domain, protected text marker.
3. Type with explanation.
4. Volume in selected window.
5. Unread count and percentage.
6. Last activity.
7. Recommendation.
8. Risk.
9. Signals: visible plain-English reasons.

Rows contain no archive/trash/keep/review execution buttons. A single row may submit
`Preview exact impact` using its opaque row key and selected preview action. The
action selector is described as preview intent, not an execution control.

### Selection Strip

Selection controls:

- `Select this page`
- `Select all {matchingSenderCount} matching senders`
- `Clear selection`
- Preview action select: Archive, Move to trash, Mark read, Keep, Review
- `Preview exact impact`

Visible copy distinguishes row targets from message impact:
`{senderCount} senders selected; exact message count is calculated on the server.`

Protected rows are unchecked and disabled in bulk mode by default with associated
copy: `Protected senders are excluded from bulk selection.` A server-derived preview
states how many protected senders/messages were excluded. When protected matches
exist, it offers a separate explicit override form with reason selector and warning;
when only protected targets remain it returns the protected-blocked state. It never
auto-checks the override. Browse `protected=include|only` changes visibility only.

## Unread Surface

### Heading

- Eyebrow: `Unread cleanup`
- `h1`: `Unread emails`
- Purpose: `Review indexed unread metadata, understand each recommendation, and preview exact impact.`

### Filters

Left rail order:

1. Account scope.
2. Unread type: All, One-to-one, List.
3. Age.
4. Waiting state.
5. Thread shape.
6. Recommendation.
7. Provider.

Toolbar:

- Search label: `Search unread`
- Hint: `Sender, subject header, or domain`
- Optional exact sender filter shown as a removable filter chip.
- Sort select.
- `Apply filters` and `Clear filters`.

### Metrics

1. `Matching unread`
2. `One-to-one`
3. `List or automated`
4. `Older than 30 days`

Every metric says `within current filters` and derives from the same predicate.

### Unread Table

Caption: `Unread messages for the current filters`

Columns:

1. Selection checkbox.
2. Age.
3. Sender: display name/address/domain.
4. Subject header. This is labeled `Subject header`; no body-derived context line.
5. Thread state.
6. Category.
7. Recommendation.
8. Reasons.
9. Provider link.

Provider link text is `Open in Gmail`, includes visually hidden `opens in a new tab`,
uses `target="_blank"` and `rel="noopener noreferrer"`, and is absent with visible
`Provider link unavailable` copy when exact identity is unsupported or missing.
The final server-built URI is rendered as one value; templates never concatenate
account or provider identity.

### Selection Strip

- `Select this page`
- `Select all {matchingUnreadCount} matching unread messages`
- `Clear selection`
- Preview action select.
- `Preview exact impact`

The same cap and preview-only semantics apply. Unread selection never changes read
state in Phase 10.

## Exact Preview Fragment

Single root: `#selection-preview`.

Success content:

- Heading: `Exact impact preview`
- `{messageCount} exact messages across {targetCount} selected targets`
- Account/scope summary; for all-account results, `{scopeCount} immutable scopes`
- Protected/high-risk counts, including excluded protected counts, and visible warnings
- One scope hash per account, visually truncatable but fully available to assistive
  technology
- Copy: `Preview only. Nothing has been queued, approved, or applied.`
- `Change selection` focus-return control

The fragment contains no queue, approve, immediate-run, mailbox mutation, or undo
form.

Cap error (the limit applies to each account-owned scope):

- `role="alert"`
- Heading: `Selection is too large for one exact preview`
- Copy: `{count} messages match for {accountLabel}. The exact per-account scope limit is 1,000. Narrow the filters and try again.`
- No scope hash and no success language.

Protected override:

- `role="alert"` warning block.
- Exact protected sender/message counts calculated by server.
- Checkbox: `Include protected senders in this preview`
- Required reason selector with bounded options.
- Copy: `This exception is recorded in the audit log. It still creates a preview only.`
- CSRF-protected resubmission.

## State Contracts

| State | Contract |
|---|---|
| Loading sync | Existing Phase 9 state remains above results; zero indexed rows show no fake table. |
| Partial/stale | Existing warning remains visible; available indexed rows stay browsable. |
| No indexed rows | Existing sync state and real sync CTA; result controls are not fabricated. |
| Zero filtered results | `No senders match these filters` or `No unread messages match these filters`; retain filter controls and clear link. |
| One result | Same table/selection semantics; no special bulk wording. |
| Many pages | Result count, current page, total pages, Previous/Next links with full query state. |
| Invalid query | Shared error/alert copy names the invalid field without echoing unsafe raw input. |
| Preview working | Submit button disables only after submission when htmx is available; status text says `Calculating exact impact`. |
| Preview empty | Alert: selection no longer matches current indexed data; no scope created. |
| Preview cap | Exact count/limit and narrowing guidance; no truncation. |
| Protected excluded | READY unprotected preview, server-derived excluded counts, and optional audited override form. |
| Protected blocked | HTTP 409, no scope; server counts and explicit audited override form. |

## Copywriting Contract

| Concept | Required wording |
|---|---|
| Delete action | `Move to trash`, never permanent delete |
| Preview | `Preview exact impact` |
| Selection modes | `This page` and `All matching` |
| Protection | `Protected sender`; `Protected senders are excluded from bulk selection` |
| Determinism | `Based on indexed headers, labels, sender pattern, and thread metadata` |
| Privacy | `Only approved message metadata is indexed. Bodies and attachments are never stored.` |
| No execution | `Preview only. Nothing has been queued, approved, or applied.` |
| Cap | `The exact per-account scope limit is 1,000. Narrow the filters and try again.` |
| Provider link | `Open in Gmail` |

Avoid `AI`, `smart`, `guaranteed`, `safe to delete`, body-summary language, and mock
phrases that imply unseen content. Recommendations are advice with visible reasons,
not claims of certainty.

## Responsive Contract

Reuse Phase 9 breakpoints.

### Desktop >980px

- 226px filter rail and independently scrollable workspace.
- Metric strip has four columns.
- Result table uses fixed/stable columns; signals may wrap.
- Selection strip remains visible at the top of the result panel.

### Compact 721-980px

- Rail stacks above main.
- Metric strip becomes two columns.
- Toolbar wraps.
- Table lives in an explicitly labeled internal scroll region with keyboard focus;
  the page itself does not scroll horizontally.

### Narrow <=720px

- Filter rail uses the existing `Filters and account scope` details pattern.
- Metrics are one column.
- Each result row becomes a labeled card or an accessible table row with internal
  horizontal scroll; implementation must retain table semantics.
- Every enabled control/link/checkbox label target is at least 44px.
- Selection strip is normal-flow sticky only when it does not obscure focused content.

### 375px Acceptance

- `document.documentElement.scrollWidth <= document.documentElement.clientWidth`.
- Long addresses, subjects, reasons, and hashes wrap or ellipsize without losing
  accessible full text.
- Filter labels, result count, selection mode, and preview CTA remain understandable.

## Accessibility Contract

- Exactly one `main` and `h1`.
- Semantic table caption, `th scope="col"`, and row checkbox labels.
- Filter groups have fieldset/legend or explicit labels.
- Current sort direction uses `aria-sort` on the active sortable column where column
  headers trigger sort; otherwise the labeled select is authoritative.
- Filter/result updates announce a concise count through `aria-live="polite"`.
- Cap, invalid selection, and protected-block errors use `role="alert"`.
- Recommendation/risk/type/protection are text, not color-only badges.
- Help text is visible or linked with `aria-describedby`; tooltip-only explanations
  are forbidden.
- Focus moves to preview heading after htmx replacement and returns to the invoking
  control on dismissal.
- Scope hashes are available to assistive technology, but raw provider message IDs
  never enter the template model or rendered DOM.
- Existing 2px focus indicator, reduced-motion behavior, 200% zoom support, and
  contrast thresholds remain.
- External provider links announce the new tab.

## UI Considerations

| Category | Element | Status | Resolution |
|---|---|---|---|
| Empty | Results | Covered | Distinguish no indexed data from zero matches; keep real recovery/filter controls. |
| Loading | Preview | Covered | Announce exact-impact calculation without hiding existing results. |
| Error | Query/preview | Covered | Alert semantics, bounded safe copy, no unsafe input echo. |
| Zero/one/many | Results and selection | Covered | Stable table semantics, exact count, deterministic pages, explicit all-matching mode. |
| Disabled | Protected selection | Covered | Native disabled checkbox plus associated visible reason; override is separate. |
| Overflow | Tables/long metadata | Covered | Internal labeled table scrolling and full accessible text; no page overflow. |
| External navigation | Gmail links | Covered | HTTPS, encoded exact target, new-tab announcement and noopener. |
| Destructive intent | Trash preview | Covered | `Move to trash`, visible risk/reasons, preview-only copy, no execution. |
| Stale/partial | Indexed data | Covered | Existing shared state remains visible while usable rows render. |

## Verification

- MockMvc proves canonical query round-trips, filter reset, pagination preservation,
  zero/one/many states, exact preview states, and no provider interactions.
- Rendered HTML proves semantic labels/captions, visible explanations, alert/live
  regions, no `href="#"`, and no Phase 12 execution endpoint.
- Static CSS tests lock local-only assets, breakpoints, declared internal overflow,
  44px minimum targets, focus, reduced motion, and long-text rules. They do not claim
  browser-layout proof; manual Phase 13 UAT owns actual 375px/zoom visual measurement.
- Manual Phase 13 integrated UAT remains responsible for visual judgment at desktop,
  980px, 720px, 375px, keyboard-only, and 200% zoom.

## Approval

Autonomously approved for planning. The contract is consistent with Phase 9's shared
shell, the sender/unread mocks, metadata-only privacy, and the Phase 10/12 boundary.
