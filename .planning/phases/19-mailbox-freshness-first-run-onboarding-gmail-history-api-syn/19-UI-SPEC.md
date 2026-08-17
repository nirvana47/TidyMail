---
phase: 19
slug: mailbox-freshness-first-run-onboarding-gmail-history-api-syn
status: approved
shadcn_initialized: false
preset: none
created: 2026-08-14
---

# Phase 19 - UI Design Contract

> Visual and interaction contract for the dedicated Sync / Activity workspace surface and truthful mailbox metadata labels. This contract extends the accepted server-rendered TidyMail workspace; it does not redesign unrelated routes.

---

## Design System

| Property | Value |
|----------|-------|
| Tool | Existing `workspace.css`; no new design-system dependency |
| Preset | Not applicable |
| Component library | Server-rendered Thymeleaf fragments + htmx |
| Icon library | None detected in the workspace; retain the existing text `?` superscript help icon pattern and do not add an icon dependency for this phase |
| Font | `Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` |
| Source of truth | `src/main/resources/static/css/workspace.css` and existing workspace templates |

### Continuity Rules

- Use the existing `workspace-shell`, workspace rail, account control, skip link, main-content width, focus ring, and page-header patterns.
- This is an operational workspace page, not a landing page. Do not add images, illustrations, hero copy, feature explanations, or decorative polish.
- Keep all content server-rendered. htmx may replace the activity status fragment; do not introduce a SPA, client-side state store, or client-rendered progress model.
- Use semantic HTML (`header`, `section`, `dl`, `table` only where tabular history exists, `time`, `button`, `a`) before adding wrappers.
- Reuse existing controls and tokens. New CSS selectors may describe activity-specific layout but must consume existing custom properties.

---

## Route And Navigation Contract

| Property | Contract |
|----------|----------|
| Canonical route | `GET /activity` |
| Status fragment route | `GET /activity/status?account={selectedAccountQuery}` |
| Workspace route enum | `ACTIVITY`, value `activity`, path `/activity` |
| Rail group | `Manage` |
| Rail position | After `Review`, before `Analytics` |
| Rail label | `Sync / Activity` |
| Page title | `Sync / Activity` |
| Eyebrow | `Mailbox status` |
| Document title | `TidyMail - Sync / Activity` |
| Current-page state | Rail link has `aria-current="page"` when route value is `activity` |
| Account switching | Existing account menu submits to `/activity` and preserves no account-specific state beyond `account` |
| Manual sync return route | `/activity` is added to the safe `returnTo` allowlist |
| First-run destination | A newly connected account redirects to `/activity` while its initial batch is pending |
| Returning-account destination | Existing behavior remains: return to the normal cleanup workspace while freshness work runs in the background |

`Sync / Activity` is a sibling of Analytics, not an Analytics tab, drilldown, or metric panel.

---

## Information Hierarchy

The desktop reading order is fixed:

1. Existing workspace rail.
2. Page header: eyebrow, `Sync / Activity`, and one superscript help icon.
3. Account/provider line: selected account label and provider name.
4. Live status region: lifecycle headline, current lane/phase, factual guidance, and conditional action.
5. Compact progress definition list: Indexed messages, Provider total, Remaining, Initial batch, Freshness, Rescan, and Estimated time when valid.
6. Lane activity rows: Freshness, Backfill, and Rescan. Render only lanes applicable to the current provider/state.
7. Bounded recent activity list, newest first, if at least one activity event exists.

### Header Help

The help icon uses the existing `.page-help` structure and hover/focus tooltip. Tooltip copy:

> Counts and freshness come from the selected provider account. Totals, remaining work, and estimated time appear only when their source is reliable.

Do not use `details`/`summary`, a modal, or click-only interaction for this explanation.

### Surface Structure

- Separate hierarchy levels with whitespace and 1px dividers, not floating cards.
- The live status region is one full-width operational block. It may use a single left status border and semantic tint already defined in `workspace.css`.
- Progress facts are a compact `dl`; each fact is a label/value pair without a card background.
- Lane rows are flat bordered rows with stable columns: Lane, Status, Last activity, and Work. They are not nested cards.
- Recent activity is a compact list or table whenever one or more events exist. Render exactly one row when exactly one event exists; omit the section and use the empty-state behavior only when there are zero events. Do not fabricate history.
- Use tabular numerals for message counts, durations, and timestamps where supported.

---

## Spacing Scale

New activity-specific layout may use only this scale:

| Token | Value | Usage |
|-------|-------|-------|
| `--space-1` | 4px | Inline label/value gaps, help icon offset |
| `--space-2` | 8px | Compact row and control gaps |
| `--space-4` | 16px | Default section padding and vertical rhythm |
| `--space-6` | 24px | Desktop section separation and main horizontal padding |
| `--space-8` | 32px | Desktop main top padding |
| `--space-12` | 48px | Desktop main bottom padding |
| `--space-16` | 64px | Largest permitted activity-section separation |

Exceptions:

- Interactive targets remain at least `44px` high and `44px` wide on touch layouts.
- Status borders remain `1px`; focus outlines remain `2px` with the existing offset.
- Existing inherited component internals, including `.page-header`, `.page-help-tooltip`, controls, rail, and table cells, may retain their current spacing. Do not redeclare those inherited values as part of the activity layout scale.
- No new activity-specific gap, margin, or padding may use `12px` or another value outside `4, 8, 16, 24, 32, 48, 64px`.

---

## Typography

Reuse `.eyebrow` / `.state-label` and `.page-title-stack h1` / `.route-heading h1` exactly as defined in `workspace.css`, including their existing responsive rules. Do not redeclare their font sizes, weights, line heights, letter spacing, or text transform in activity-specific CSS.

New activity-specific content uses only these three sizes and two weights:

| Role | Size | Weight | Line Height |
|------|------|--------|-------------|
| Compact event metadata / timestamps | 12px | 400 or 600 | 1.3 |
| Body / labels / controls | 14px | 400 or 600 | 1.5 |
| Lifecycle headline / section heading / status value | 16px | 600 | 1.3 |

- Allowed weights are `400` and `600`. Do not introduce heavier weights for the new surface.
- Message counts may use the 16px status-value role; do not enlarge them into dashboard KPIs.
- The inherited eyebrow and page heading are not new activity-specific type roles; their existing selector values remain the source of truth.
- Labels use sentence case. Lane and lifecycle enum names must be translated into readable copy, never rendered as raw uppercase identifiers.
- Do not use oversized display type.

---

## Color

| Role | Value | Usage |
|------|-------|-------|
| Dominant (60%) | `--tm-bg: #f4f6fb`, `--tm-surface: #ffffff` | Page background, rail, status and row surfaces |
| Secondary (30%) | `--tm-surface-subtle: #f8faff`, `--tm-border: #d9e0ee`, `--tm-text-muted: #5b6478` | Flat row separation, secondary facts, inactive navigation |
| Accent (10%) | `--tm-primary: #3f5cff`, `--tm-primary-soft: #eef2ff` | Current rail item, links, primary action, help/focus affordance only |
| Informational | `--tm-info: #2459bd`, `--tm-info-soft: #ebf2ff` | Active bootstrap/freshness/backfill state |
| Success | `--tm-success: #16794f`, `--tm-success-soft: #e8fbf3` | Completed initial batch or completed backfill |
| Warning | `--tm-warning: #8a5a16`, `--tm-warning-soft: #fff6df` | Retry wait, stale freshness, rescan required/running, maintainer ceiling |
| Destructive/error | `--tm-danger: #b83232`, `--tm-danger-soft: #fff0f0` | Failed sync and reauthorization-required states only |
| Text | `--tm-text: #111827`, `--tm-text-strong: #0f172a` | Body and headings |

Accent is reserved for current navigation, keyboard focus, links, the help icon, and the primary recover/retry action. It is not a generic status color.

- Status must never rely on color alone; pair every tint/border with explicit text.
- Do not introduce gradients. Existing gradients elsewhere in `workspace.css` are not a precedent for this surface.
- Do not use colored pills or badges for lifecycle, lane, unread, attachment, or rescan labels.

---

## Component Contract

### Live Status Region

The status region has `id="activity-status"` and contains:

- A visible lifecycle headline.
- Current lane and phase in plain language.
- One concise guidance sentence.
- Zero or one primary action.
- The progress facts `dl`.
- Lane rows.
- An optional recent-activity section.

The region is the htmx replacement boundary. It must remain present in every state, including loading and errors, so polling never targets a missing element.

### Progress Facts

| Label | Visibility and value rule |
|-------|---------------------------|
| `Indexed messages` | Always visible. Unique stored provider-message rows for the selected account scope. |
| `Provider total` | Visible only when the provider supplies a reliable account total. Include observation time in accessible text. Never use `resultSizeEstimate` as an exact total. |
| `Remaining` | Visible only when provider total is reliable and `max(total - indexed, 0)` describes the same mailbox scope. Otherwise omit the entire term/value pair. |
| `Initial batch` | Always visible during first run: `Preparing`, `Ready`, or `Unavailable`. |
| `Freshness` | Visible when a successful provider check exists: `Checked {relative age}` with a machine-readable `<time datetime>`. Otherwise `Not checked yet`; never `Up to date`. |
| `Rescan` | Always visible: `Not needed`, `Required`, `In progress`, or `Completed {relative age}`. |
| `Estimated time` | Visible only under the ETA rules below. Value begins with `Estimated:`. Omit the entire term/value pair otherwise. |

Counts use unique messages. Never add counts across Gmail labels.

### ETA Rules

Show ETA only when all are true:

1. Work is active.
2. The denominator is reliable and covers the same mailbox scope as the numerator.
3. Enough completed samples produce a stable positive processing rate.
4. The worker is not stalled, retrying, or waiting for quota.
5. A rescan has not invalidated the denominator.

Copy format is `Estimated: about {rounded duration}` or `Estimated: {rounded range}`. Do not show seconds-level precision or a bare clock time. Suppress ETA rather than showing `Unknown`, `Calculating`, `--`, or a misleading progress bar.

### Progress Visualization

- A horizontal progress bar is allowed only when provider total is reliable.
- The bar has a visible text equivalent: `{indexed} of {provider total} messages indexed`.
- Without a reliable total, show the indexed count and active phase with no determinate bar, percentage, or animated indeterminate stripe.
- Progress updates must not animate count changes. Restrained color transitions may reuse the existing 120-150ms timing and must honor `prefers-reduced-motion`.

### Actions

| State | Action |
|-------|--------|
| Initial batch pending | No forced action. The page polls automatically. |
| Initial batch ready | Primary link `Open cleanup` to `/senders?account={selectedAccountQuery}` while backfill continues. |
| Recoverable failure | Button `Retry sync`. POSTs through the existing CSRF-protected sync action and returns to `/activity`. |
| Reauthorization required | Primary link/button `Reconnect Gmail`. Preserve indexed data; do not imply data deletion. |
| Active work | Disabled control is optional; if shown, label `Sync running`, not a spinner-only control. |
| Complete/healthy | No CTA required. Manual `Check now` may be a secondary action only if the backend supports a distinct freshness request. |

No action on this page is destructive. No destructive confirmation belongs to this phase.

---

## State Table

| State / condition | Headline | Required facts and behavior | Action |
|-------------------|----------|-----------------------------|--------|
| Model loading | `Loading sync activity` | Render the full compact shell immediately with `Indexed messages: 0` or the known count, `Initial batch: Preparing`, and `Freshness: Not checked yet`. Never render a blank main area. | None |
| New account, bootstrap queued/running | `Preparing your cleanup workspace` | Show provider/account, lane `Initial setup`, current phase, indexed count, initial batch `Preparing`, and background guidance. Provider total/remaining only if reliable. | None |
| First batch ready, backfill active | `Cleanup is ready while history continues` | Show indexed count, initial batch `Ready`, lane `Historical mail`, freshness fact, and conditional total/remaining/ETA. The cleanup workspace is available immediately. | `Open cleanup` |
| Freshness check active | `Checking for new mail` | Lane `New mail`, phase, indexed count, last successful check age, rescan state. Backfill may independently remain active. | None |
| Backfill active for returning account | `Indexing older mail in the background` | Show indexed count and conditional reliable total/remaining/ETA. Existing cleanup data remains usable. | Optional `Open cleanup` link |
| Healthy, backfill complete | `Mailbox activity is current` | Show indexed count, latest successful check age, initial batch `Ready`, rescan `Not needed`, and completed lane timestamps. This headline is allowed only from an explicit successful provider check, never inferred from row write time. | None |
| No reliable provider total | State-specific headline | Omit provider total, remaining, percentage, progress bar, and ETA. Keep indexed count and phase. | State-specific |
| Retry waiting / rate limited | `Sync will retry automatically` | Show safe reason, affected lane, and next retry time when known. Suppress ETA. Existing indexed data remains available. | Optional `Retry sync` only if manual retry is safe |
| Recoverable sync failure | `Sync paused after a recoverable error` | Show safe error copy and affected lane; never expose provider payloads, tokens, stack traces, or raw exception text. | `Retry sync` |
| Reauthorization required | `Reconnect Gmail to continue syncing` | State that indexed cleanup data remains available. Freshness age remains factual and may be stale. Stop polling after this state arrives. | `Reconnect Gmail` |
| Rescan required | `A mailbox rescan is required` | Rescan `Required`; explain via help tooltip that recent changes are reconciled before full history is checked. Do not claim missing data or completion prematurely. | None if automatically queued; otherwise `Start rescan` only if backend requires it |
| Rescan running | `Rechecking mailbox history` | Lane `Mailbox rescan`, current phase, indexed count, freshness age, rescan `In progress`. Suppress remaining and ETA unless a new reliable denominator applies to this rescan. | None |
| Rescan complete | State appropriate to backfill/freshness | Rescan `Completed {age}` and latest successful freshness age. Resume normal lane display. | State-specific |
| Maintainer ceiling reached | `Historical indexing stopped at the configured limit` | Warning treatment. State `A maintainer safety limit stopped additional indexing.` Never describe GB, storage consumed, mailbox capacity, or ask the user to archive mail. | None |
| Provider sync unsupported | `Sync activity is unavailable for this provider` | Show provider name and indexed count if present. Do not display Gmail lifecycle terms for non-Gmail providers. | None |
| Completed empty mailbox | `No messages were found` | Initial batch `Ready`, indexed `0`, successful freshness age, reliable provider total `0` only if known. This is a valid non-blank state. | None |
| Activity history empty | No additional headline | With zero events, omit the recent-activity section. With exactly one event, render the section with that one event; the empty state does not apply. Do not show an empty card or placeholder row. | None |

Every active or queued server-side state includes:

> Sync continues in the background; you can close this page.

Never show "keep this window open," "do not close," or equivalent browser-owned-job copy.

---

## Copywriting Contract

| Element | Exact copy |
|---------|------------|
| Primary first-run CTA | `Open cleanup` |
| Retry CTA | `Retry sync` |
| Reauthorization CTA | `Reconnect Gmail` |
| Optional freshness CTA | `Check now` |
| Empty mailbox heading | `No messages were found` |
| Empty mailbox body | `Gmail reported no messages for this account. Sync will continue checking for new mail.` |
| Initial pending heading | `Preparing your cleanup workspace` |
| Initial pending body | `Recent messages are being indexed first. Sync continues in the background; you can close this page.` |
| Ready-during-backfill heading | `Cleanup is ready while history continues` |
| Ready-during-backfill body | `Your recent messages are ready to review. Older mail will continue indexing in the background.` |
| Healthy heading | `Mailbox activity is current` |
| Recoverable error | `Sync paused after a recoverable error. Your indexed data is still available. Retry now or wait for the next automatic attempt.` |
| Reauthorization error | `Gmail authorization needs attention. Reconnect Gmail to continue syncing; your indexed data will remain available.` |
| Background guidance | `Sync continues in the background; you can close this page.` |
| Unknown freshness | `Not checked yet` |
| Unknown attachment | `Checking` |
| Destructive confirmation | `None. This surface does not delete data or mutate provider messages.` |

Copy rules:

- Use `messages indexed`, not `messages synced`, when the fact is a local indexed-row count.
- Use `Checked {age}` for freshness. Do not use `Live`, `Up to date`, `Fully synced`, or `Complete` without the matching provider-backed state.
- Use `Provider total` only for a reliable total. Do not call it `Mailbox size`.
- Use `Estimated` in the visible ETA label every time ETA is shown.
- Name lanes for users: `Initial setup`, `New mail`, `Historical mail`, and `Mailbox rescan`.
- Error copy describes the safe next step and preservation of indexed data; it never includes internal codes in the visible body. A safe support code may appear as secondary text.

---

## Unread And Metadata Label Contract

### Unread

| Surface | Required visible label | Counting rule |
|---------|------------------------|---------------|
| `/unread` page total | `Inbox unread` | Unique messages where unread is true and the message currently has the `INBOX` label |
| `/unread` category metrics and row column | `Inbox unread` | Same source of truth as the page total |
| Workspace rail accessible label for Unread Only | `{count} Inbox unread messages` | Same Inbox-unread count |
| `/senders` aggregates and row column | `All Mail unread` | Unique indexed unread messages, regardless of number of Gmail labels |
| Analytics unread size metric | `Inbox unread size` | Full message-size estimates for unique Inbox-unread messages |
| Any surface showing both | `Inbox unread` and `All Mail unread` | Render as separate facts; never display an unlabeled `Unread` total |

- Never sum Gmail label counts.
- Never use `Unread` alone where the scope could be ambiguous.
- Tooltips may explain scope, but the visible label itself must remain explicit.

### Attachment State

The one required visible production surface is the existing sender-stats email metadata table:

| Property | Contract |
|----------|----------|
| User entry and route | `View stats` on `/unsubscribe`, loading `GET /unsubscribe/senders/{senderRowKey}/stats?account={account}&from={from}&to={to}` |
| Template location | `src/main/resources/templates/fragments/email-stats-modal-frag.html`, existing `Email metadata` table; add one `Attachment` column, not a card or separate panel |
| Model field | Extend each existing `EmailStatsModal.EmailRow` with `attachmentState` using the persisted tri-state (`PRESENT`, `ABSENT`, `UNKNOWN`) |
| Visible mapping | `PRESENT` -> `Present`; `ABSENT` -> `None`; `UNKNOWN` -> `Checking` |
| Partial coverage | If any rendered row is `UNKNOWN`, append `Attachment coverage is still being checked; Present and None apply only to reconciled messages.` to the existing table caption. Keep every row visible and show its individual state. If no row is unknown, omit this sentence. Derive this condition from the rendered rows; do not add a decorative coverage card. |

On this surface, render plain text:

| Stored state | Visible label |
|--------------|---------------|
| Confirmed present | `Present` |
| Confirmed absent | `None` |
| Unknown / pending reconciliation | `Checking` |

- Do not render these values as pills, badges, colored chips, icons alone, checkmarks, or boolean yes/no.
- `Checking` is a data state, not a loading animation.
- Missing METADATA MIME parts must never map to `None`.
- The existing `/unread` `Has attachments` category may include only confirmed `PRESENT` rows. It must not count `UNKNOWN` as absent or claim complete coverage while relevant rows remain `Checking`; retain its existing layout and add no new card.

### Size

- Continue to label derived size values `Estimated`.
- Use `Unavailable` or `Pending sync` when the source fact is absent; never coerce absent size to zero.
- Do not imply that a message-count ceiling is a Gmail storage or GB limit.

---

## htmx Polling Contract

The implementation must make a bounded extension to `src/main/resources/static/js/htmx-lite.js`; importing full htmx or adding another client library is out of scope. The extension contract is:

1. The full `GET /activity` response renders a complete non-blank `#activity-status` region.
2. Add a polling initializer that scans the document on `DOMContentLoaded` and scans each newly inserted swap root after `afterSwap`. It recognizes only `hx-trigger="every {integer}s"` for polling, bounded to `1-300s`; existing click and submit behavior remains unchanged.
3. Track one timer and one in-flight flag per polling element. Use a one-shot timeout scheduled after the prior request settles, not `setInterval`; if a request is still active, do not start or queue an overlapping request.
4. Before an `outerHTML` replacement, clear the old element's timer and pending focus handler. After replacement, initialize only the returned root/subtree. Also dispose timers for disconnected elements and for replacements that no longer carry a recognized polling trigger.
5. Parse `hx-swap` as a swap mode plus optional settle modifier. `outerHTML settle:120ms` must select `outerHTML` replacement and delay `afterSwap`, local-date formatting, and polling reinitialization until the 120ms settle period completes. Keep exact `outerHTML` and existing `innerHTML` behavior compatible; reject unsupported modifiers rather than silently treating the whole string as `innerHTML`.
6. While bootstrap, freshness, backfill, rescan, retry-wait, or queued work is active, the region polls:
   - `hx-get="/activity/status?account={selectedAccountQuery}"`
   - `hx-trigger="every 5s"`
   - `hx-target="#activity-status"`
   - `hx-swap="outerHTML settle:120ms"`
7. The replacement fragment includes polling attributes only while work remains active. A healthy, completed, unsupported, reauthorization-required, or terminal-failure fragment omits `hx-trigger`; disposal of the old timer plus no reinitialization is the explicit polling termination mechanism.
8. A recoverable failure uses `hx-trigger="every 30s"` only when an automatic retry is scheduled. A terminal failure omits polling attributes.
9. Polling requests return only the activity status fragment, never the rail or full page. The selected account query is server-resolved and encoded; do not derive account identity from client state.
10. Make replacement focus-safe. If `document.activeElement` is inside `#activity-status`, hold at most the latest successful response and replace after focus leaves the region. If the focused control has a stable ID that exists in the new fragment, replacement may proceed and restore focus to that ID. Never remove a focused retry/reconnect control, change scroll position, or reset rail/account state.
11. A failed poll leaves the last factual state visible. The first consecutive failure retries on the normal interval. After the second consecutive failure, show `Activity updates are temporarily unavailable. This does not stop background sync.` in a dedicated non-alert warning node inside `#activity-status` and back off to `30s`. Keep the 30s interval until a success resets the failure count and removes the warning. Request timeout, network error, and non-2xx response all count as failures.
12. Count and state changes are announced through the live-region rules below; do not add a spinner or continuously animated indicator.

When the first batch becomes ready, the fragment adds the `Open cleanup` action. It does not force an automatic navigation or steal focus.

---

## Responsive Behavior

### Desktop, above 980px

- Preserve the `248px` sticky rail and existing `1180px` content maximum.
- The progress `dl` may use up to four equal columns.
- Lane rows use four columns: Lane, Status, Last activity, Work.
- Long account labels and safe error copy wrap; numeric values do not collide.

### Compact desktop/tablet, 721-980px

- Use the existing top rail behavior.
- Progress facts use two columns.
- Lane rows may use two columns, with Lane/Status first and Last activity/Work second.
- Navigation must accommodate six links; do not rely on `:last-child` spanning assumptions from the current five-link rail.

### Mobile, 376-720px

- Main padding remains `24px 16px 32px`.
- Progress facts become one column.
- Each lane row becomes a stacked definition row with visible labels; no horizontal scroll.
- Page header aligns to the top. Tooltip width remains within viewport.
- Actions are full-width or naturally wrapping with a minimum 44px height.
- The six navigation links use a two-column grid in document order; `Sync / Activity` and `Analytics` each occupy normal cells.

### Narrow mobile, 320-375px

- Use 16px activity-section padding and 8px activity page-side padding. Existing inherited component internals may retain their current breakpoint spacing without activity-specific redeclaration.
- Rail navigation becomes one column.
- Long provider/account/error values wrap with `overflow-wrap:anywhere`.
- Do not abbreviate `Inbox unread`, `All Mail unread`, or `Estimated`.
- No horizontal scrolling is permitted for status facts or lane rows.

The page must remain usable at 200% browser zoom and at 320 CSS px width.

---

## Accessibility

- Keep the existing skip link targeting `#workspace-main`.
- The page header help trigger is keyboard focusable, has an accessible name, and references `role="tooltip"` content with `aria-describedby`.
- `#activity-status` uses `role="status"` and `aria-live="polite"` for normal transitions.
- A nested, dedicated error message uses `role="alert"` only when a new recoverable failure or reauthorization requirement first appears. Do not mark the entire frequently-polled region assertive.
- Use `aria-atomic="false"` so only changed status text is announced. Keep stable labels and IDs across fragment swaps.
- Announce lifecycle changes and first-batch readiness. Do not announce every numeric increment. Server-render a visually hidden announcement only when the semantic state changes.
- Progress bars, when allowed, use native `<progress max value>` or equivalent `role="progressbar"` with `aria-valuemin`, `aria-valuemax`, `aria-valuenow`, and a textual count.
- Omit determinate progress semantics entirely when total is unknown.
- Relative freshness text is paired with `<time datetime="{ISO-8601 instant}">`.
- Lane names are row headers or definition terms, not color-coded labels.
- Buttons and links have visible focus using the existing 2px primary outline and minimum 44px touch target on mobile.
- Reconnect and retry actions have specific accessible names including the selected provider where ambiguity exists.
- Polling and fragment replacement must not steal focus or reset the user's scroll position.
- Motion is limited to existing short transitions and disabled by `prefers-reduced-motion: reduce`.
- Color contrast must meet WCAG 2.2 AA for text, controls, borders needed to identify state, and focus indicators.

---

## Prohibited Patterns

- No floating page-section cards, nested cards, card grids, or dashboard KPI tiles.
- No pills, chips, or rounded badges for status, lane, unread scope, attachment state, ETA, or labels.
- No oversized headings, hero treatment, illustrations, images, gradients, or decorative animation.
- No explanatory feature copy, onboarding carousel, tour, wizard, or modal.
- No click-only `details`/`summary` help. Logic explanations use superscript hover/focus help tooltips.
- No SPA framework, client-side progress calculation, client-side account truth, or optimistic lifecycle state.
- No fabricated total, remaining count, percentage, ETA, freshness, completion, or "up to date" claim.
- No ETA when denominator or rate is unreliable.
- No `resultSizeEstimate` presented as an exact provider total.
- No blank first-run main area.
- No browser warning that says the page/window/tab must remain open.
- No automatic redirect when initial batch becomes ready.
- No summing Gmail label counts.
- No ambiguous `Unread` label where Inbox and All Mail can differ.
- No attachment boolean that maps unknown to absent.
- No message ceiling described as GB, storage, or mailbox capacity.
- No raw error messages, provider payloads, token details, stack traces, or provider message IDs.
- No unrelated visual changes to Senders, Unread, Review, Unsubscribe, or Analytics.

---

## UI Considerations

Applicable state considerations resolved: 49 covered, 28 backstop, 0 unresolved.

| Category | Element(s) | Status | Resolution / Reason |
|----------|------------|--------|---------------------|
| loading | Workspace `Sync / Activity` navigation item | covered | The server-rendered rail remains present and usable while `#activity-status` loads or polls; only the status fragment is replaced. |
| error | Workspace `Sync / Activity` navigation item | covered | Activity load or poll failures stay inside `#activity-status`; the rail and its `aria-current="page"` state remain intact. |
| overflow | Workspace `Sync / Activity` navigation item | backstop | { statement: "Verify six rail links reflow as specified at 721-980px, into two columns at 376-720px, and into one column at 320-375px without horizontal page scrolling.", verification: backstop } |
| long-text | Workspace `Sync / Activity` navigation item | backstop | { statement: "Verify the exact Sync / Activity label remains readable and does not collide with adjacent rail links at 320 CSS px and 200% zoom.", verification: backstop } |
| overflow | Account/provider context line | backstop | { statement: "Verify selected account and provider values wrap within the activity content width without overlapping status facts or controls.", verification: backstop } |
| long-text | Account/provider context line | backstop | { statement: "Verify unusually long account and provider values use safe wrapping, including overflow-wrap:anywhere at 320-375px, while preserving their full text.", verification: backstop } |
| loading | Live status summary | covered | Model loading renders the complete compact `#activity-status` shell with `Loading sync activity`, indexed count, `Initial batch: Preparing`, and `Freshness: Not checked yet`; the main area is never blank. |
| error | Live status summary | covered | Recoverable failure and reauthorization states render the safe Copywriting Contract text, preserve indexed-data facts, identify the affected lane, and expose only the applicable retry or reconnect action. |
| overflow | Live status summary | backstop | { statement: "Verify the full-width status block, guidance, conditional action, and safe error copy reflow without clipping or horizontal scrolling at every specified breakpoint and 200% zoom.", verification: backstop } |
| long-text | Live status summary | backstop | { statement: "Verify long safe error, phase, account, and provider text wraps without colliding with non-wrapping numeric facts or actions.", verification: backstop } |
| empty | Progress metrics | covered | Indexed messages, first-run initial batch, freshness fallback, and rescan remain factual at zero; provider total, remaining, ETA, and progress visualization are omitted when their required source facts are absent. |
| loading | Progress metrics | covered | Loading shows known values or the specified zero/preparing/not-checked fallbacks immediately and never substitutes a skeleton, spinner-only state, fabricated percentage, or indeterminate stripe. |
| error | Progress metrics | covered | Retry, recoverable failure, reauthorization, and rescan states retain factual indexed and freshness values while omitting invalid remaining work and ETA. |
| populated | Progress metrics | covered | A normal reliable state renders a compact definition list of indexed messages, conditional provider total and remaining, initial batch, freshness, rescan, and conditional estimated time. |
| partial | Progress metrics | covered | Each conditional fact is independently omitted unless its source is reliable; available indexed, initial-batch, freshness, and rescan facts remain visible. |
| overflow | Progress metrics | backstop | { statement: "Verify the progress definition list reflows from up to four columns to two and then one without label/value collisions or horizontal scrolling.", verification: backstop } |
| zero-one-many | Progress metrics | covered | The definition list remains structurally valid from the always-required core facts through all eligible conditional facts, and zero counts render as factual numeric values rather than empty placeholders. |
| long-text | Progress metrics | backstop | { statement: "Verify long relative times, estimated ranges, and localized numeric values wrap or reflow without truncating required labels such as Estimated.", verification: backstop } |
| empty | Optional progress bar | covered | No progress bar or determinate progress semantics render when provider total is absent or unreliable. |
| loading | Optional progress bar | covered | Active work without a reliable total shows indexed count and phase only; no animated indeterminate stripe or fabricated percentage appears. |
| error | Optional progress bar | covered | Retry, stalled, quota-wait, invalidated-rescan, and unreliable-denominator states suppress the progress bar and ETA. |
| populated | Optional progress bar | covered | When provider total is reliable, the determinate bar has the matching visible text `{indexed} of {provider total} messages indexed` and native or equivalent accessible progress semantics. |
| overflow | Optional progress bar | backstop | { statement: "Verify the determinate bar and its text equivalent fit the activity region at 320 CSS px and 200% zoom without page-level horizontal scrolling.", verification: backstop } |
| long-text | Optional progress bar | backstop | { statement: "Verify large formatted counts in the indexed-of-total text reflow without clipping, overlap, or loss of the textual equivalent.", verification: backstop } |
| empty | Lifecycle lane rows | covered | Only lanes applicable to the selected provider and current state render; unsupported providers do not receive empty Gmail lane placeholders. |
| loading | Lifecycle lane rows | covered | Queued or active work renders the applicable user-facing lane name, current phase, known last activity, and work fact inside the non-blank shell. |
| error | Lifecycle lane rows | covered | Retry and failure states identify the affected lane with safe status text while preserving other usable lane facts. |
| populated | Lifecycle lane rows | covered | Applicable Freshness, Backfill, and Rescan rows use the stable Lane, Status, Last activity, and Work structure with readable lane names. |
| partial | Lifecycle lane rows | covered | Missing timestamps, totals, remaining work, or ETA do not remove the lane; only unsupported facts are omitted or replaced by the specified factual state. |
| overflow | Lifecycle lane rows | backstop | { statement: "Verify four-column desktop lanes become two-column tablet rows and stacked labeled mobile rows with no horizontal scrolling.", verification: backstop } |
| zero-one-many | Lifecycle lane rows | covered | The region renders zero unsupported lanes, one applicable lane, or all applicable lanes without empty filler rows or duplicated lane names. |
| long-text | Lifecycle lane rows | backstop | { statement: "Verify long phase, safe error, and work text wraps within its lane while labels and timestamps remain distinguishable.", verification: backstop } |
| empty | Recent activity list | covered | Zero events omit the entire recent-activity section; no empty card, placeholder row, or fabricated history renders. |
| loading | Recent activity list | covered | Until real events are available, the status shell carries loading state and the recent-activity section remains omitted rather than showing synthetic rows. |
| error | Recent activity list | covered | Sync or polling errors are presented by the status/error treatment and never create an activity event or erase already returned factual events. |
| populated | Recent activity list | covered | One or more real events render as a bounded compact list or table in newest-first order. |
| partial | Recent activity list | covered | A bounded returned history renders only the events supplied; it does not imply exhaustive history or fabricate missing entries. |
| overflow | Recent activity list | backstop | { statement: "Verify the bounded event list or table keeps timestamps, event text, and any secondary facts readable on mobile and at 200% zoom without page-level horizontal scrolling.", verification: backstop } |
| zero-one-many | Recent activity list | covered | Zero events omit the section, exactly one event renders exactly one row, and many events retain the same compact newest-first structure. |
| loading | Reconnect/retry and state actions | covered | Initial pending work has no forced action; an optional active disabled control says `Sync running`, and first-batch readiness adds `Open cleanup` without automatic navigation. |
| error | Reconnect/retry and state actions | covered | Recoverable failure exposes CSRF-protected `Retry sync`; authorization failure exposes `Reconnect Gmail`; indexed data remains available and terminal states stop polling as specified. |
| long-text | Reconnect/retry and state actions | backstop | { statement: "Verify provider-specific accessible names and visible action labels wrap naturally, retain a 44px minimum mobile target, and never overlap adjacent content.", verification: backstop } |
| empty | Background polling fragment | covered | `#activity-status` is present in every full-page and fragment state; a swap can never remove the target or replace it with an empty main region. |
| loading | Background polling fragment | covered | Active work uses one non-overlapping one-shot timer per element, polls every 5 seconds after the prior request settles, and reinitializes only the settled returned root or subtree. |
| error | Background polling fragment | covered | A failed poll preserves the last factual state; after two consecutive failures it adds the specified non-alert warning and backs off to 30 seconds until success resets the warning and interval. |
| populated | Background polling fragment | covered | Successful swaps replace only `#activity-status`, apply `outerHTML settle:120ms`, retain server-owned account truth, and stop when the returned fragment omits a recognized polling trigger. |
| partial | Background polling fragment | covered | While focus is inside the region, at most the latest successful response is held; replacement resumes after focus leaves or restores the same stable control ID without resetting scroll or rail state. |
| overflow | Background polling fragment | backstop | { statement: "Verify repeated fragment states, including the temporary-update warning and newly inserted actions, do not introduce horizontal overflow or layout jumps at mobile widths and 200% zoom.", verification: backstop } |
| zero-one-many | Background polling fragment | covered | The implementation maintains one timer and one in-flight flag per polling element, never queues overlapping requests, and disposes timers for disconnected or terminal replacement roots. |
| long-text | Background polling fragment | backstop | { statement: "Verify swapped long guidance, warning, and safe error text wraps without changing scroll position or obscuring focused controls.", verification: backstop } |
| empty | First-run state | covered | A completed empty mailbox is a valid non-blank state with `No messages were found`, initial batch `Ready`, indexed `0`, factual freshness, and provider total `0` only when reliable. |
| loading | First-run state | covered | A newly connected account redirects to `/activity` and immediately renders `Preparing your cleanup workspace` with recent-first indexing guidance and automatic background polling. |
| error | First-run state | covered | Recoverable and authorization failures preserve indexed cleanup data, show the specified safe next step, and never expose provider payloads, tokens, stack traces, or raw exceptions. |
| populated | First-run state | covered | First-batch readiness renders `Cleanup is ready while history continues`, marks initial batch `Ready`, shows historical-mail state, and adds `Open cleanup` while backfill continues. |
| partial | First-run state | covered | Recent indexed messages become usable before historical backfill completes; unreliable total, remaining, progress, and ETA facts remain omitted. |
| overflow | First-run state | backstop | { statement: "Verify preparing, ready-during-backfill, empty-mailbox, retry, and reconnect first-run states remain usable at 320 CSS px and 200% zoom with no horizontal scrolling.", verification: backstop } |
| zero-one-many | First-run state | covered | Indexed count truthfully supports zero, one, and many messages while the shell and lifecycle copy remain structurally consistent. |
| long-text | First-run state | backstop | { statement: "Verify first-run account, provider, lifecycle, and preservation guidance wraps without hiding `Open cleanup`, `Retry sync`, or `Reconnect Gmail`.", verification: backstop } |
| overflow | Unread and size labels | backstop | { statement: "Verify `Inbox unread`, `All Mail unread`, `Inbox unread size`, and `Estimated` remain visibly scoped and do not collide with their values at 320 CSS px or 200% zoom.", verification: backstop } |
| long-text | Unread and size labels | backstop | { statement: "Verify required unread and size labels are not abbreviated or ellipsized into ambiguity; absent values remain `Unavailable` or `Pending sync` rather than zero.", verification: backstop } |
| empty | Sender-stats attachment table | covered | With zero email rows, no attachment pseudo-row or decorative coverage card is created; the phase only adds the `Attachment` column to the existing email metadata table. |
| loading | Sender-stats attachment table | covered | Pending attachment reconciliation keeps each email row visible and renders plain text `Checking`; it is a data state, not an animation or spinner. |
| error | Sender-stats attachment table | covered | Missing or unreconciled MIME metadata maps to `Checking`, never `None`, so incomplete evidence cannot be presented as confirmed absence. |
| populated | Sender-stats attachment table | covered | Every rendered email row maps persisted `PRESENT`, `ABSENT`, or `UNKNOWN` to plain text `Present`, `None`, or `Checking` in one Attachment column. |
| partial | Sender-stats attachment table | covered | If any rendered row is `UNKNOWN`, every row remains visible and the exact attachment-coverage sentence is appended to the existing caption; the sentence is omitted otherwise. |
| overflow | Sender-stats attachment table | backstop | { statement: "Verify the added Attachment column and conditional caption fit the existing stats modal/table treatment at narrow widths and 200% zoom without causing page-level horizontal overflow.", verification: backstop } |
| zero-one-many | Sender-stats attachment table | covered | Zero rows add no attachment content, one row shows one tri-state value, and many rows independently show their persisted state without aggregate decoration. |
| long-text | Sender-stats attachment table | backstop | { statement: "Verify the attachment-coverage caption and adjacent long email metadata reflow within the existing modal while each Attachment value remains readable.", verification: backstop } |
| loading | Header help trigger and tooltip | covered | The server-rendered keyboard-focusable help trigger and its referenced tooltip remain available while activity model data loads or polls. |
| error | Header help trigger and tooltip | covered | Status or polling errors do not replace the page header, remove the trigger, or alter its reliable-source explanation. |
| overflow | Header help trigger and tooltip | backstop | { statement: "Verify the tooltip remains within the viewport at all specified breakpoints and at 200% zoom without covering an unreachable trigger.", verification: backstop } |
| long-text | Header help trigger and tooltip | backstop | { statement: "Verify the full tooltip copy wraps legibly on narrow screens and remains available on both hover and keyboard focus.", verification: backstop } |
| live updates | htmx activity updates | covered | The fragment uses bounded 5-second polling for active work, 30-second retry/backoff where specified, last-factual-state preservation, settle-aware swaps, and server-controlled termination without optimistic client truth. |
| reduced motion | Activity status and progress updates | backstop | { statement: "Verify `prefers-reduced-motion: reduce` disables activity-specific transitions and that count changes, polling, and progress never introduce continuous or indeterminate animation.", verification: backstop } |
| keyboard/focus | Polling, help, and recovery controls | backstop | { statement: "Verify keyboard-only use keeps visible 2px focus, preserves or restores focus across fragment swaps, never removes a focused retry/reconnect control, and does not change scroll position.", verification: backstop } |
| screen-reader live regions | Lifecycle, readiness, and errors | backstop | { statement: "Verify `#activity-status` announces semantic lifecycle changes and first-batch readiness politely without announcing every count, while only a newly appearing recoverable or authorization error uses the nested alert.", verification: backstop } |
| mobile overflow | Activity, rail, lanes, actions, tooltip, and attachment table | backstop | { statement: "Verify the complete Phase 19 UI at 320, 375, 720, 980, and desktop widths plus 200% zoom: no page-level horizontal scrolling, stacked labels remain visible, and all actions remain reachable with 44px touch targets.", verification: backstop } |

---

## Registry Safety

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| shadcn official | None | Not applicable; project is not a React/shadcn application |
| Third-party registries | None | No registry code permitted for this phase |

No external UI package, icon package, font package, or registry block is required.

---

## Checker Sign-Off

- [x] Dimension 1 Copywriting: PASS
- [x] Dimension 2 Visuals: PASS
- [x] Dimension 3 Color: PASS
- [x] Dimension 4 Typography: PASS
- [x] Dimension 5 Spacing: PASS
- [x] Dimension 6 Registry Safety: PASS

**Approval:** approved 2026-08-14 after independent UI checker verification.
