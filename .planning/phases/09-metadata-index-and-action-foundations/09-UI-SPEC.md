---
phase: 09-metadata-index-and-action-foundations
slug: metadata-index-and-action-foundations
status: approved
shadcn_initialized: false
preset: none
created: 2026-08-09
---

# Phase 9 - UI Design Contract

## Purpose and Boundary

Phase 9 delivers a truthful shared shell for:

- `/senders`
- `/unread`
- `/unsubscribe`
- `/review`
- `/analytics`

The shell is production UI, but the route-specific product controls belong to later phases. Phase 9 renders navigation, account scope, queued-action count, sync/freshness state, responsive layout, and honest capability placeholders. It must not render fabricated metrics, fake rows, working-looking exports, working-looking actions, or `href="#"`.

## Design System

| Property | Contract |
|----------|----------|
| Rendering | Server-rendered Thymeleaf with htmx fragments |
| CSS | One local file at `/static/css/workspace.css`; no build pipeline |
| Component library | None |
| Icon library | None required in Phase 9; labels may not rely on icons alone |
| Font | `Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` |
| Monospace | `"SFMono-Regular", ui-monospace, Menlo, monospace` |
| JS | Existing htmx only where a real fragment endpoint exists |

The mock suite is the visual source. Tailwind browser runtime and Lucide CDN references in the mocks are not copied into production.

## Shared Route Anatomy

Every route uses the same landmark order:

1. Skip link targeting `#workspace-main`.
2. Sticky `<header>` with brand, primary navigation, queued-action count, and account-scope summary.
3. Shell body with contextual `<aside>` and `<main id="workspace-main">`.
4. Route header containing eyebrow, `h1`, and one-sentence purpose.
5. Shared sync/freshness state region.
6. Route capability panel.
7. Next-step footer linking to a real route.

### Top Rail

- Brand text: `TidyMail`.
- Navigation order is fixed: Senders, Unread, Unsubscribe, Review, Analytics.
- Each link has a real application URL.
- Current route uses `aria-current="page"` and the active visual token.
- Queue status copy is `Queued actions: {count}`. Count is server-owned and reflects selected account scope.
- Account summary copy is `{label}` where label is either the selected account address/provider or `All linked accounts ({count})`.
- No account or queue value is hardcoded in a template.

### Left Rail

Phase 9 left rails explain route purpose and show the shared account selector. Route-specific filters are displayed only as disabled capability previews:

| Route | Rail title | Capability owner |
|-------|------------|------------------|
| Senders | Sender view | Phase 10 |
| Unread | Unread view | Phase 10 |
| Unsubscribe | Unsubscribe queue | Phase 11 |
| Review | Review actions | Phase 12 |
| Analytics | Analytics view | Phase 13 |

### Main Capability Panels

| Route | Heading | Phase 9 body copy |
|-------|---------|-------------------|
| Senders | Sender decisions | `Indexed sender filters and exact bulk selection arrive in Phase 10. Metadata sync and account scope are ready now.` |
| Unread | Unread emails | `Indexed unread rows and message-level decisions arrive in Phase 10. No mailbox scan runs while this page renders.` |
| Unsubscribe | Unsubscribe queue | `Unsubscribe detection and guarded execution arrive in Phase 11. No unsubscribe request can run from this placeholder.` |
| Review | Review actions | `Exact scopes can be stored now. Approval, conflict checks, execution outcomes, and history arrive in Phase 12.` |
| Analytics | Cleanup analytics | `Historical metrics, drilldowns, and exports arrive in Phase 13 after enough sync and action facts exist.` |

Each panel names the unavailable capability and its delivery phase. It does not show sample counts or sample mailbox data.

## Tokens

### Color

| Token | Value | Usage |
|-------|-------|-------|
| `--tm-bg` | `#f4f6fb` | Page background |
| `--tm-surface` | `#ffffff` | Cards, rail, controls |
| `--tm-surface-subtle` | `#f8faff` | Secondary surfaces |
| `--tm-text` | `#111827` | Body text |
| `--tm-text-strong` | `#0f172a` | Headings, values |
| `--tm-text-muted` | `#5b6478` | Secondary copy; contrast must remain at least 4.5:1 |
| `--tm-border` | `#d9e0ee` | Borders and separators |
| `--tm-primary` | `#3f5cff` | Active navigation, focus, real primary actions |
| `--tm-primary-soft` | `#eef2ff` | Active/selected background |
| `--tm-success` | `#16794f` | COMPLETE/READY |
| `--tm-success-soft` | `#e8fbf3` | COMPLETE/READY background |
| `--tm-warning` | `#8a5a16` | PARTIAL/STALE |
| `--tm-warning-soft` | `#fff6df` | PARTIAL/STALE background |
| `--tm-danger` | `#b83232` | ERROR only |
| `--tm-danger-soft` | `#fff0f0` | ERROR background |
| `--tm-info` | `#2459bd` | QUEUED/RUNNING |
| `--tm-info-soft` | `#ebf2ff` | QUEUED/RUNNING background |

Primary is reserved for active route state, keyboard focus, account-scope submit, and future enabled primary actions. Disabled future controls use neutral tokens, never primary.

### Spacing and Shape

| Token | Value | Usage |
|-------|-------|-------|
| `--space-1` | `4px` | Tight inline gaps |
| `--space-2` | `8px` | Control and card gaps |
| `--space-3` | `12px` | Compact card padding |
| `--space-4` | `16px` | Standard content padding |
| `--space-6` | `24px` | Major narrow-screen separation |
| `--radius-sm` | `6px` | Small controls |
| `--radius-md` | `10px` | Cards and navigation |
| `--radius-lg` | `16px` | Reserved feature panels |
| `--shadow-1` | `0 1px 2px rgba(15,23,42,.04), 0 8px 24px rgba(15,23,42,.04)` | Cards |
| `--shell-top` | `58px` | Desktop top rail |
| `--shell-rail` | `226px` | Desktop left rail |
| `--shell-max` | `1480px` | Main content maximum |

All new spacing values use the 4px scale except the mock-derived 58px top rail and 226px rail.

### Typography

| Role | Size | Weight | Line height |
|------|------|--------|-------------|
| Body | `12px` desktop, `14px` at <=720px | 400 | 1.5 |
| Secondary | `11px` desktop, `13px` at <=720px | 400 | 1.45 |
| Label/eyebrow | `10px` desktop, `12px` at <=720px | 700 | 1.3 |
| Navigation/control | `12px` desktop, `14px` at <=720px | 700 | 1.3 |
| Section heading | `14px` | 700 | 1.3 |
| Page heading | `22px` desktop, `20px` narrow | 700 | 1.15 |

Labels may use uppercase with `0.07em` tracking. Sentence copy does not use all caps.

## Shared Server-Owned State

### Account Scope

- Query parameter: `account=all` or `account={numericAccountId}`.
- Default: current authenticated account.
- `all` includes only implemented-provider accounts returned by the ownership query for the authenticated normalized email. In v1.1 this is Gmail/`google`; a stored Microsoft row is unavailable until OUTL-01.
- Unknown, unsupported, or foreign IDs are rejected; never silently broaden to `all`.
- The selector is a GET form so the URL is shareable within the authenticated session.
- Route navigation preserves the selected account parameter.
- Templates receive a typed selected scope and option list, not raw request strings.

### Queue Count

- Count only `UserAction.status = QUEUED`.
- For one account, count that account.
- For all linked accounts, sum only the owned account IDs in scope.
- Count appears in the top rail on all five routes, including zero.
- Queue count failure does not hide the page. Render `Queued actions unavailable` and set the shared state to partial/error as applicable.

### Sync/Freshness Summary

The model provides:

- state;
- messages indexed;
- messages scanned in the current/latest run;
- pages completed;
- last successful completion time;
- safe failure explanation;
- selected account-scope label.
- `syncActive`, true when any selected account's latest run is QUEUED or RUNNING.

Failure copy is sanitized server-side and must not include provider payloads, access tokens, URLs with credentials, stack traces, or raw message headers.

For `all`, indexed/scanned/page counts are summed across selected accounts. State is PARTIAL when usable rows coexist with any active, partial, failed, or never-completed selected account; it is READY only when every selected account has a fresh completion. A mixed-account failure must never be hidden by another account's success.

## State Contracts

The state component is a shared Thymeleaf fragment with `role="status"` for non-error states and `role="alert"` for errors.

| State | Trigger | Heading | Required detail | Available action |
|-------|---------|---------|-----------------|------------------|
| LOADING | QUEUED/RUNNING and zero indexed rows | `Building your metadata index` | scanned/upserted count when known; account scope | Status polling |
| EMPTY | No run yet, or COMPLETE and zero indexed rows | `No indexed messages yet` | Never-started: `Metadata sync has not started for the selected account scope.` Completed: `The selected account scope completed sync with no metadata rows.` | Real `Sync metadata` control |
| PARTIAL | Indexed rows exist and any scoped account is QUEUED/RUNNING/PARTIAL/FAILED or never completed | `Showing partial indexed data` | indexed count, pages complete, and active/failure/incomplete detail | Continue browsing |
| STALE | Indexed rows exist and no successful completion within 15 minutes | `Indexed data may be stale` | last successful time and selected scope | Real `Sync metadata` control |
| ERROR | Latest failed and no usable rows | `Metadata sync failed` | safe failure explanation and retry guidance | Real retry control |
| READY | Indexed rows exist and latest completion is fresh | `Metadata index is ready` | indexed count and last successful time | None required |

When a sync fails but usable rows exist, render the data as PARTIAL with an inline error note rather than replacing the page with ERROR. For `all`, apply the aggregate rules above across every selected account.

### Sync Request

- The real control submits `POST /api/metadata-sync` with CSRF and the selected `account` value.
- It preserves a validated same-origin `returnTo` workspace route and redirects back after queueing; arbitrary return URLs are rejected.
- It is available for EMPTY, STALE, ERROR, and non-active PARTIAL states. While `syncActive` is true, render a disabled `Sync queued` or `Sync running` button rather than submitting duplicates.
- `account=all` queues only the owned Gmail account IDs already present in the typed scope model.
- The legacy synchronous `/api/senders/_sync` endpoint is never linked from the workspace shell.

### htmx Fragment Behavior

- Initial full-page response includes the current state; no blank shell waits for JavaScript.
- Poll `GET /workspace/state?route={route}&account={scope}`. Both parameters are parsed as typed/authorized values and the response is the same single state-fragment root.
- The state fragment may poll every 15 seconds only while `syncActive` is true, including PARTIAL pages with usable rows.
- Automatic polling stops whenever `syncActive` becomes false, regardless of display state.
- Fragment root is one partial element; never include document wrappers.
- Use `aria-live="polite"` for progress count changes. Error transitions use assertive announcement through `role="alert"`.
- Preserve focus after swaps; the fragment must not contain the page heading or navigation.

## Later-Phase Controls

### Required Disabled Pattern

Unavailable controls use:

```html
<button type="button"
        disabled
        aria-disabled="true"
        aria-describedby="capability-phase-10">
  Archive selected
</button>
<p id="capability-phase-10">Available in Phase 10 after indexed selection is implemented.</p>
```

Rules:

- Native `disabled` is mandatory for buttons/selects/inputs.
- Do not attach `hx-*`, form action, click handler, or link destination.
- Do not use `href="#"`, `javascript:`, or a link styled as disabled.
- Disabled text remains readable; opacity must be at least `0.65`.
- A visible explanation is required. Tooltip-only explanations are insufficient.
- Route navigation and account selection are available and must never use this pattern.

### Route Capability Matrix

| Surface | Available in Phase 9 | Disabled until |
|---------|----------------------|----------------|
| Senders | Route, account scope, queue count, sync state/action | Search/filter/sort/select/actions Phase 10; export Phase 13 |
| Unread | Route, account scope, queue count, sync state/action | Rows/filter/select/open/action Phase 10; export Phase 13 |
| Unsubscribe | Route, account scope, queue count, sync state/action | Detection/filter/select Phase 11; queue/immediate policy Phase 12; export Phase 13 |
| Review | Route, account scope, queue count, sync state/action, exact-scope readiness copy | Remove/approve/conflicts/outcomes/history Phase 12 |
| Analytics | Route, account scope, queue count, sync state/action, freshness copy | Date/group/filter/compare/charts/drilldowns/export Phase 13 |

## Responsive Contract

### Desktop: >980px

- Top rail stays 58px high.
- Shell body is `226px minmax(0, 1fr)`.
- Left rail and main workspace scroll independently only when viewport height requires it.
- Workspace content is capped at 1480px.
- State details may render in a four-cell summary strip.

### Compact: 721-980px

- Body returns to normal document scrolling.
- Top rail wraps; navigation becomes a horizontal scroll row with visible focus.
- Left rail stacks above main and loses its right border.
- Account form and queue status remain visible in the top region.
- Summary strip becomes two columns.

### Narrow: <=720px

- All summary/content grids become one column.
- Top navigation remains horizontally scrollable; no clipped tabs.
- Left-rail capability previews collapse into a `<details>` block labeled `Filters and account scope`.
- Body text increases to 14px.
- Every enabled interactive target is at least 44px by 44px.
- Disabled controls also retain 44px height so layout does not jump when enabled in later phases.
- No page-level horizontal scrolling. Only explicitly labeled data regions may scroll internally in later phases.

### 375px Acceptance

- `document.documentElement.scrollWidth <= document.documentElement.clientWidth`.
- Brand, active route, queue count, and account label remain readable without overlap.
- Long email addresses use ellipsis visually but retain the complete value in accessible text/title.
- State/error copy wraps without fixed widths.

## Accessibility Contract

- Exactly one `h1` and one `<main>` per route.
- Landmarks have labels where more than one of the same type exists.
- Skip link is first focusable element and becomes visible on focus.
- Active route uses both visual styling and `aria-current="page"`.
- Focus indicator: 2px solid `--tm-primary` with 2px offset; never removed.
- Color is never the only state signal. Every state has a text label.
- Minimum contrast: 4.5:1 for normal text, 3:1 for large text and meaningful UI boundaries.
- Account selector has a visible `<label>`.
- Disabled capability explanations are associated with `aria-describedby`.
- Status polling uses `aria-live` without repeatedly announcing unchanged text.
- Respect `prefers-reduced-motion: reduce`; disable nonessential transitions and smooth scrolling.
- CSS layout must remain usable at 200% browser zoom.

## Copywriting Contract

| Element | Copy rule |
|---------|-----------|
| Sync CTA | `Sync metadata` or `Retry metadata sync`; never `Refresh inbox` |
| Queue count | `Queued actions: {count}` |
| Privacy note | `Only approved message metadata is indexed. Bodies and attachments are never stored.` |
| Loading | State what is happening and show known counts |
| Empty | Distinguish not-yet-synced from completed-with-zero without implying a run occurred |
| Partial | State that visible results are usable but incomplete |
| Stale | Show last successful time and invite metadata sync |
| Error | Safe problem summary plus one recovery path |
| Disabled control | `Available in Phase {N} after {capability} is implemented.` |

Avoid `Done`, `Success`, or `Applied` for queued/partial states. Do not imply that disabled controls can mutate a mailbox.

## Route Acceptance Matrix

| Contract | Senders | Unread | Unsubscribe | Review | Analytics |
|----------|---------|--------|-------------|--------|-----------|
| Real route and active nav | Required | Required | Required | Required | Required |
| Account scope preserved | Required | Required | Required | Required | Required |
| Queue count visible | Required | Required | Required | Required | Required |
| Shared state fragment | Required | Required | Required | Required | Required |
| No provider call on GET | Required | Required | Required | Required | Required |
| Later controls disabled | Phase 10/13 | Phase 10/13 | Phase 11/12/13 | Phase 12 | Phase 13 |
| No fabricated metrics | Required | Required | Required | Required | Required |

## UI Considerations

Applicable state considerations resolved: 8 covered, 0 backstop, 0 unresolved.

| Category | Element(s) | Status | Resolution |
|----------|------------|--------|------------|
| Empty | Shared data state | Covered | Never-started or COMPLETE plus zero rows renders truthful variant copy and no fake table. |
| Loading | Shared data state | Covered | QUEUED/RUNNING plus zero rows renders server content immediately and polls only the state fragment. |
| Partial | Shared data state | Covered | Indexed rows stay usable with explicit incomplete-data copy and progress counts. |
| Stale | Shared data state | Covered | Data older than 15 minutes is labeled with last successful completion time. |
| Error | Shared data state | Covered | No-data failures use alert semantics; failures with usable rows degrade to PARTIAL. |
| Zero/one/many | Account and queue state | Covered | Zero queue count is shown; one current Gmail account uses a specific label; list-based `all` remains ready for future supported providers without fabricating duplicate Gmail ownership. |
| Long text | Email and safe failure copy | Covered | Values wrap or ellipsize without changing accessible text; errors are bounded and sanitized. |
| Overflow | Navigation and shell | Covered | Tabs scroll internally below 980px and the page has no horizontal overflow at 375px. |

## Verification

- MockMvc renders each route, active navigation, account scope, queue count, and every shared state, including never-started and mixed-account aggregates.
- MockMvc proves the sync form is CSRF-protected, queues one/all owned Gmail accounts, rejects foreign/unsupported accounts and unsafe return targets, and never uses `/api/senders/_sync`.
- Provider mocks receive zero interactions for all five GET routes.
- Rendered HTML contains no `href="#"` and unavailable controls are native disabled with visible phase copy.
- Static CSS includes the declared tokens, 980px/720px/375px behavior, focus styles, reduced-motion rule, and 44px narrow-screen targets.
- Manual browser verification at desktop, 980px, 720px, 375px, keyboard-only navigation, and 200% zoom is required during Phase 9 verification.

## Checker Sign-Off

- [x] Copywriting: PASS
- [x] Visual system: PASS
- [x] Color and contrast contract: PASS
- [x] Typography contract: PASS
- [x] Spacing and responsive contract: PASS
- [x] Registry/dependency safety: PASS

**Approval:** approved 2026-08-09
