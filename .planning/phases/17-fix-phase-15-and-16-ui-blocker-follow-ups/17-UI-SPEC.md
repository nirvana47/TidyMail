---
phase: 17
slug: fix-phase-15-and-16-ui-blocker-follow-ups
status: approved
shadcn_initialized: false
preset: none
created: 2026-08-13
---

# Phase 17 - UI Design Contract

> Visual and interaction contract for closing the authenticated Phase 15/16 UI blocker follow-ups. This is a remediation contract, not a broad redesign.

---

## Design System

| Property | Value |
|----------|-------|
| Tool | none |
| Preset | not applicable |
| Component library | none |
| Icon library | existing inline SVG / local icons only |
| Font | existing workspace system stack |

Phase 17 preserves the Phase 16 compact Inbox Zero-aligned workspace system: clean rail, quiet page headers, table-like cleanup rows, restrained buttons, and minimal persistent instructional copy. Do not introduce a SPA framework, external UI registry, export affordances, or new decorative surfaces.

---

## Spacing Scale

Declared values remain the Phase 16 compact workspace scale:

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4px | Icon gaps, inline metadata gaps |
| sm | 8px | Compact element spacing, row subtext gaps |
| md | 16px | Default control groups and row padding |
| lg | 24px | Header/control separation and modal padding |
| xl | 32px | Major page section breaks |
| 2xl | 48px | Large modal/top-level separation only |

Exceptions:

- Stats modal internals may use `10px` gaps where the mock does (`modal-action-row`, chart/table spacing).
- Utility rail menu rows must use a single left edge; destructive details cannot have extra indentation compared with Feedback and Logout.
- Header-line controls must sit on the same visual row as the title/help affordance at desktop widths and wrap beneath the title only when narrow width or 200% zoom requires it.

---

## Typography

| Role | Size | Weight | Line Height |
|------|------|--------|-------------|
| Body | 14px | 400 | 1.45 |
| Label / table head | 12px | 600 | 1.3 |
| Category / card title | 15-16px | 600 | 1.3 |
| Page heading | 30px desktop, existing responsive reduction on narrow viewports | 600 | 1.15 |
| Modal title | 20px | 600 | 1.25 |

Typography contract:

- Keep cleanup and Review pages information-dense. Do not enlarge rows into broad cards.
- Long sender names, addresses, domains, subjects, outcome reasons, and provider follow-up links must truncate or wrap inside their allocated cell/card without creating page-level horizontal scrolling.
- Review History timestamps should be human-readable compact text in the primary row; verbose ISO/timeline details belong in a secondary details area, tooltip, disclosure, or stacked metadata block only when needed.

---

## Color

| Role | Value | Usage |
|------|-------|-------|
| Dominant (60%) | `#f4f6fb` / `#ffffff` | Page background and primary surfaces |
| Secondary (30%) | `#f8faff`, `#eef2ff`, subtle borders | Rail active state, compact panels, category rows |
| Accent (10%) | existing workspace blue `#3f5cff` | Active route, focus ring, non-destructive primary actions |
| Cleanup action | existing cleanup attention orange/brown `#c95020` / `#ea580c` | Archive/Delete/Mark-read staging buttons where Phase 16 uses high-attention cleanup actions |
| Destructive | `#b83232` family with white readable text | Delete selected, Delete my data and logout, destructive warnings |

Destructive hover/focus contract:

- `Delete selected` must maintain WCAG AA contrast in normal, hover, focus-visible, active, disabled, and staged states.
- White label text must not shift to low-contrast blue on hover/focus.
- Focus-visible must be obvious through outline/ring or border treatment without relying on color alone.

Accent reserved for:

- Active rail route and focus rings.
- Dialog primary actions.
- Non-destructive action feedback.

Do not use accent badges on rail labels, and do not reintroduce visible numeric rail clutter.

---

## Copywriting Contract

| Element | Copy |
|---------|------|
| Bulk Archive/Delete title | `Bulk Archive/Delete` |
| Bulk Archive/Delete help affordance | compact help icon/summary beside the H1, using existing helper copy only inside the help disclosure |
| Expand all control | `Expand all` / `Collapse all` |
| Bulk Unsubscribe stats trigger | `View stats` |
| Bulk Unsubscribe primary action | `Unsubscribe` when unsubscribe is available; otherwise the honest fallback action label such as `Archive` or `Delete` |
| Bulk Unsubscribe action loading | `Preparing...` or existing short loading label; avoid silent no-op |
| Bulk Unsubscribe unavailable state | `Unsubscribe is not available for this sender.` plus the honest fallback action path |
| Stats modal kicker | `Sender stats` |
| Stats modal close | accessible label `Close` |
| Category empty state | `No senders are currently in this category.` or blank/`0 KB` for size metric when category count is zero |
| Unread Only provider total label | `Inbox unread` |
| Has attachments empty/audit state | `No unread senders with attachments found.` only after metadata audit confirms this is true |
| Row staging success | `Staged` or `Already staged` |
| Selected action staging success | concise live-region text such as `Staged 3 senders for Review.` |
| Selected action failure | concise problem plus recovery path, e.g. `Action not staged. Try again or refresh this category.` |
| Review History empty | `No completed actions yet.` |
| Review History compact details | `Details` / `Hide details` if an expansion is needed |
| Delete selected warning | Preserve Gmail Trash recovery acknowledgement copy before staging |

Must remove or keep removed:

- Category helper block text `Select senders first, then choose where they belong.`, `Move selected`, and `Create category` when those controls are stale relative to the contextual selection bar.
- Any generic persistent `Ready to stage for Review` panel on cleanup pages.
- User-facing `Export`, `CSV`, `snapshot`, `download`, or share actions.

---

## Surface Contracts

### Bulk Unsubscribe Stats Modal

`View stats` opens an in-page modal/dialog. It must not navigate to a full page, replace the main route, or strand the user outside the Bulk Unsubscribe context.

Reference: the local mock currently has the cited modal at `tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html:369-425` (the older todo line range is stale in this checkout). Required structure:

- Overlay container equivalent to `.stats-modal-overlay`.
- Dialog container with `role="dialog"`, `aria-modal="true"`, and an accessible title.
- Header with `Sender stats`, sender name, sender address/domain context, and selected time range.
- Close button with accessible label and keyboard dismissal.
- Action row with the same available row actions (`Unsubscribe`, `Archive`, `Delete`) and the same safety/availability truth as the originating sender row.
- Incoming email distribution chart with accessible label and no mandatory always-visible bar labels.
- Compact email table showing sender name, subject, snippet, and date received.

Modal behavior:

- Opening the modal moves focus into the dialog; closing returns focus to the triggering `View stats` control.
- Escape and close button dismiss the modal.
- Background content is inert or otherwise not keyboard reachable while the modal is open.
- Loading state uses an in-dialog skeleton/spinner or short text, not a full route transition.
- Error state stays in the modal shell and names the recovery path.
- Stats rendering must have bounded performance evidence; do not mask multi-second query/render latency with only presentation changes.

### Bulk Unsubscribe Primary Action Feedback

The primary sender-row `Unsubscribe` button must be visibly wired.

- Click starts a visible loading/pending state on the button or row.
- Success opens the expected preview/consent/staging UI or updates the row with explicit feedback.
- If unsubscribe is unavailable, the UI must show an honest unavailable/fallback state.
- Failure must render an accessible row/modal error; silent no-op is forbidden.
- The button must not claim unsubscribe success until execution outcomes are known.
- Destination safety, consent, protected-sender handling, honest accepted/pending/confirmed/failed/manual states, and bounded query performance remain intact.

### Bulk Archive/Delete Header And Controls

Desktop header line:

- H1 text `Bulk Archive/Delete`.
- Compact help affordance immediately beside the H1.
- `Expand all` / `Collapse all` on the same header line, right-aligned within the page header row.

Responsive behavior:

- At narrow width and 200% zoom, the right-side control may wrap beneath the H1 but must remain visually associated with the header.
- Help and expand controls must remain keyboard reachable and maintain 44px touch targets.

Do not reintroduce a broad toolbar card, search box, or page-level account selector on Bulk Archive/Delete.

### Cleanup Grid And Contextual Actions

Bulk Archive/Delete and Unread Only sender grids must not render a stale `Action` column heading when row actions are hidden and actions are only available through the contextual selection bar.

Required grid behavior:

- Header columns must match visible row content.
- If no row-level action cells exist, remove `Action` from table/grid header.
- Contextual selected-action bar appears only after one or more senders are selected.
- The selected-action bar must include selected count, clear selection, and valid actions for the page.
- Disabled/staged rows stay visibly muted and cannot accidentally enqueue duplicates.

### Load More Continuation

`Load more` appends additional sender rows as a continuation of the existing category body.

- The loaded fragment must not repeat table headers such as `Sender`, `Domain`, `Emails`, `Size (Estimated)`, or `Action`.
- Newly appended rows preserve the category's column alignment and stable ordering.
- `Load more` and `Load all` remain subtle controls, not blue primary CTAs.
- Collapse resets the category to the initial bounded view as established in Phase 16.
- Loading state is local to the category, not a page-level spinner.
- Error state is local to the category and keeps already loaded rows visible.

### Empty Category Metrics

When the last sender leaves a category:

- Sender count becomes `0 senders`.
- Size metric is blank, omitted, or `0 KB`.
- The UI must not show `Size pending sync` for a genuinely empty category.
- Empty body copy uses the Copywriting Contract row.

### Expand All Coverage

`Expand all` must expand every category on Bulk Archive/Delete and Unread Only, including:

- `Automated notifications`
- `Receipts and transactions`
- `Personal senders`
- User-created categories
- Initially unloaded categories
- Zero-loaded or empty categories

Coverage requirements:

- The global toggle must iterate over canonical category keys from server-owned data, not a hand-picked visible subset.
- Empty and unloaded categories still enter an expanded state and show their local loading/empty result.
- `Collapse all` returns all categories to collapsed state without leaving partial open state behind.

### Unread Only Total And Attachment Grouping

Unread Only must present a server-owned provider Inbox unread total.

Placement:

- Preferred placement is the Unread Only page header/control area below the title and above category groups, labeled `Inbox unread`.
- If rail placement is also used, it must not visually clutter the rail label; no visible rail badges are required by this phase.

Semantic contract:

- Total counts messages that are both provider Inbox and unread.
- Archived unread All Mail messages must not be included.
- The total should be reconcilable with Gmail's Inbox unread figure; the user reported `7,930` during feedback, but the UI must display the live authorized server fact, not a hard-coded number.

Suspicious attachment grouping:

- `Has attachments` showing `0 senders` and `0 unread` is not acceptable without evidence.
- If metadata supports attachment detection, the group must use the same Inbox-unread predicate and correct indexed attachment flag.
- If metadata is unavailable or partial, the group must show honest unavailable/partial copy rather than a misleading zero.
- Group presentation follows the same compact category row contract: group name, short description, sender count, unread total.

### Rail Chrome

Brand:

- The TidyMail brand remains clickable as the workspace/home navigation affordance.
- It must be styled as brand chrome, not a normal underlined text hyperlink.
- Hover/focus may show subtle brand/navigation affordance without changing it into inline body-link styling.

Utility menu:

- `Delete my data and logout` aligns to the same left edge as `Feedback` and `Logout`.
- Preserve destructive warning copy, confirmation controls, CSRF, and POST semantics.
- Destructive copy may have stronger color but not different indentation.

Collapse affordance:

- The rail collapse control uses a left-pointing arrow/disclosure for the expanded rail state because the action is horizontal collapse.
- The collapsed state may use the mirrored direction if needed to communicate expansion.
- The control has an accessible name and focus-visible state.

### Review History Compact Treatment

Review History must fit the same compact workspace visual system without losing audit information.

Desktop table contract:

- Primary columns should prioritize: action/type, target summary, status/outcome, requested/applied counts, queued/completed time, undo/follow-up availability.
- Use compact chips or short labels for status; avoid nested walls of raw outcome counts in the primary row.
- Long outcome reasons, timelines, provider follow-up links, and immutable scope details move into a row details disclosure or secondary line.
- Timestamps use concise human-readable formatting in the primary row; raw ISO strings belong only in details if needed.

Narrow viewport contract:

- History can become compact cards below the existing responsive breakpoint.
- Card order mirrors table priority and keeps actions/follow-up links reachable.
- No page-level horizontal scrolling at 375px.

Empty/loading/error:

- Empty state uses `No completed actions yet.`
- Loading state preserves the Review tab/header skeleton without jumping layout.
- Error state explains that history could not load and offers retry/navigation without hiding Queue.

### Destructive Selected Action Feedback

`Delete selected` on Unread Only must be visually and behaviorally reliable.

Visual states:

- Normal, hover, focus-visible, active, disabled, loading, and staged states must all keep readable text contrast.
- Disabled uses muted opacity/background plus disabled cursor/semantics; it cannot look like an enabled destructive action.
- Loading/staged feedback must be visible in the contextual bar and/or affected selected rows.

Behavior:

- Clicking `Delete selected` never directly deletes provider mail from the cleanup page.
- It must materialize exact provider message IDs and stage them for Review, preserving CSRF, account authorization, protected-sender handling, immutable scope hash/count, and Gmail Trash recovery acknowledgement.
- Success announces and shows a concise staged result.
- Failure announces and shows a concise accessible error.
- Silent no-op is forbidden.

---

## UI Considerations

Applicable state considerations resolved: 40 covered, 10 backstop, 0 unresolved.

| Category | Element(s) | Status | Resolution / Reason |
|----------|------------|--------|---------------------|
| empty | Bulk Unsubscribe stats modal email table | covered | Empty table keeps the modal open and states that no matching emails were found for the selected range. |
| loading | Bulk Unsubscribe stats modal | covered | Modal opens with an in-dialog loading state; it never navigates away from Bulk Unsubscribe. |
| error | Bulk Unsubscribe stats modal | covered | Modal shell remains visible and shows a concise retry/error message. |
| populated | Bulk Unsubscribe stats modal | covered | Dialog includes sender context, action row, distribution chart, and compact email table. |
| long-text | Stats modal subject/snippet/sender | covered | Table cells truncate or wrap inside the modal without horizontal page scroll. |
| keyboard | Stats modal | covered | Focus enters dialog on open, Escape/close dismisses, and focus returns to `View stats`. |
| narrow viewport | Stats modal | backstop | Modal becomes full-width/stacked as needed at 375px while preserving close/action reachability. |
| 200% zoom | Stats modal | backstop | Modal content scrolls internally or stacks without trapping inaccessible controls. |
| loading | Bulk Unsubscribe primary action | covered | Button/row shows pending state during request. |
| error | Bulk Unsubscribe primary action | covered | Failure or unavailable state renders accessible row/modal feedback; no silent no-op. |
| disabled/staged | Bulk Unsubscribe primary action | covered | Protected, unavailable, loading, and staged/accepted states are visually distinct and non-duplicating. |
| populated | Bulk Archive/Delete header | covered | H1, help affordance, and Expand all share the header line on desktop. |
| narrow viewport | Bulk Archive/Delete header | backstop | Header controls may wrap but remain associated with the title and stay 44px touch targets. |
| keyboard | Help and Expand all controls | covered | Both controls are focusable, named, and operable by keyboard. |
| populated | Cleanup grid header | covered | Column headers match visible row cells; stale `Action` heading is removed when actions are contextual. |
| empty | Cleanup grid category body | covered | Empty categories show concise empty copy and no stale action column. |
| loading | Load more | covered | Category-local loading indicator appends rows below existing rows. |
| error | Load more | covered | Category-local error preserves already loaded rows and offers retry/continued bounded paging. |
| populated | Load more continuation | covered | Loaded fragments contain data rows only, no repeated table header. |
| zero-one-many | Empty category metrics | covered | Zero sender category uses `0 senders` plus blank/omitted/`0 KB` size, never `Size pending sync`. |
| populated | Expand all | covered | All canonical categories expand, including Automated notifications, Receipts and transactions, Personal senders, user-created, unloaded, and empty categories. |
| loading | Expand all unloaded categories | covered | Initially unloaded categories enter expanded state and show local loading before rows/empty state. |
| error | Expand all category load | covered | One failed category does not prevent other categories from expanding. |
| keyboard | Expand all / Collapse all | covered | Toggle is keyboard operable and announces expanded/collapsed state. |
| populated | Unread Only Inbox unread total | covered | Page shows server-owned provider Inbox unread total labeled `Inbox unread`. |
| partial | Unread total reliability | covered | If provider denominator is unavailable, UI uses honest loaded/partial wording instead of fabricated total. |
| error | Unread total load | covered | Failure to load total shows an accessible error or unavailable label without blocking category rows. |
| populated | Has attachments group | covered | Group uses Inbox-unread predicate and displays sender count plus unread total when metadata is reliable. |
| partial | Has attachments metadata | covered | If attachment metadata is unavailable/partial, copy says so instead of showing misleading zero. |
| empty | Has attachments true zero | backstop | A true zero must be backed by metadata/repository evidence and verified in UAT. |
| populated | Rail brand | covered | Brand is clickable home chrome without normal hyperlink underline styling. |
| keyboard | Rail brand and collapse control | covered | Both expose accessible names and focus-visible states. |
| populated | Utility rail menu | covered | Destructive details row aligns left with Feedback and Logout while preserving destructive warning flow. |
| narrow viewport | Rail utility menu | backstop | Menu padding/alignment must hold in collapsed or narrow shell states. |
| populated | Rail collapse arrow | covered | Expanded rail uses left-pointing disclosure; collapsed state may mirror for expand. |
| empty | Review History | covered | Empty history renders `No completed actions yet.` under the History tab. |
| loading | Review History | covered | Loading skeleton/row placeholder preserves compact tab/table layout. |
| error | Review History | covered | Error state is local to History and does not hide Queue. |
| populated | Review History desktop | covered | Compact table prioritizes action, target, status, counts, time, and undo/follow-up availability. |
| long-text | Review History reasons/links | covered | Long details move to disclosure/secondary line and wrap safely. |
| narrow viewport | Review History | backstop | History converts to compact cards without page-level horizontal scrolling at 375px. |
| 200% zoom | Review History | backstop | Table/card treatment remains readable and controls reachable at 200% zoom. |
| populated | Delete selected button | covered | Normal/hover/focus states keep high-contrast destructive styling. |
| disabled/staged | Delete selected button | covered | Disabled/loading/staged states are visibly different and prevent duplicate action submission. |
| keyboard | Delete selected | covered | Focus-visible contrast and keyboard activation are preserved. |
| error | Delete selected staging | covered | Failure is announced in live region/contextual bar and does not silently no-op. |
| populated | Delete selected staging | covered | Success visibly stages exact IDs for Review and updates selected rows/bar. |
| 200% zoom | Selected action bar | backstop | Contextual bar wraps without hiding selected count, clear action, or destructive action. |
| long-text | Sender/category/history labels | covered | Names, addresses, categories, and outcomes wrap/truncate without breaking row alignment. |
| narrow viewport | Cleanup sender rows | backstop | Rows stack into labeled fields with 44px controls and no page-level horizontal scroll. |
| disabled/staged | Staged sender rows | covered | Affected rows/buttons are muted and cannot enqueue duplicate Review actions. |

---

## Accessibility And Responsive Requirements

- All interactive controls introduced or touched by Phase 17 must have visible focus states.
- Modal, rail menu, category toggles, contextual selected-action bar, and Review History details must be keyboard reachable.
- Touch targets remain at least 44px on 375px-wide layouts.
- 200% zoom must not hide modal close buttons, selected destructive actions, category toggles, or Review History details.
- Live regions or equivalent accessible status targets must announce action loading, success, failure, and staged states.
- Disabled controls must use semantic disabled/aria-disabled behavior consistent with local patterns, not visual muting only.

---

## Registry Safety

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| shadcn official | none | not required |
| third-party | none | not applicable |

---

## Verification Hooks

Planner should carry these probes into implementation and UAT:

- Bulk Unsubscribe `View stats` opens an in-page accessible modal and does not navigate the main route.
- Stats modal covers empty, loading, error, long table content, keyboard close/focus return, 375px, and 200% zoom.
- Bulk Unsubscribe primary row action shows loading and either preview/staging/unavailable/error feedback.
- Bulk Archive/Delete header line contains H1, help affordance, and Expand all together at desktop width.
- Bulk Archive/Delete and Unread Only do not render stale `Action` headers when actions are contextual only.
- `Load more` appends rows without repeated table headers.
- Empty categories never show `Size pending sync`.
- `Expand all` expands every canonical, user-created, unloaded, and empty category.
- Unread Only shows provider Inbox unread total and excludes archived unread All Mail from that total.
- `Has attachments` group is audited and either corrected or honestly marked partial/unavailable.
- Rail brand is clickable chrome without underline-link styling.
- Utility menu left padding aligns destructive and non-destructive rows.
- Rail collapse uses a horizontal left-pointing disclosure in expanded state.
- Review History desktop table and narrow cards stay compact while preserving audit details.
- `Delete selected` hover/focus remains readable, and click visibly stages exact IDs for Review or shows accessible failure.
- No authenticated workspace page gains export, CSV, snapshot, download, share, or direct destructive provider execution from cleanup pages.

---

## Checker Sign-Off

- [x] Source alignment: PASS
- [x] Design system fit: PASS
- [x] Surface coverage: PASS
- [x] Interaction states: PASS
- [x] Accessibility/responsive coverage: PASS
- [x] Safety/non-goals: PASS

**Approval:** Trae fallback UI checker verified and approved 2026-08-13.

## UI-SPEC VERIFIED
