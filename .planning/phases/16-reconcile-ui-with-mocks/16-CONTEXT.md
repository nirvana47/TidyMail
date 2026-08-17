# Phase 16: Reconcile UI with mocks - Context

**Gathered:** 2026-08-12T06:00:11Z
**Status:** Ready for planning

<domain>
## Phase Boundary

Reconcile the running authenticated workspace with
`tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and its Build Notes.
This phase is an audit-first UI remediation pass over the already-implemented
workspace surfaces: shared left rail, Bulk Archive/Delete, Unread Only, Bulk
Unsubscribe, Review, and Analytics.

Phase 16 owns removal of cleanup-page status clutter, mock-aligned category and
sender-row hierarchy, user-facing Unread copy, category-management affordance
alignment, visible user-created category labeling, and the authenticated
page-by-page walkthrough evidence needed to close UX-15.

It does not add new cleanup surfaces, export/snapshot/download affordances,
immediate mailbox execution from cleanup pages, new provider capabilities, AI,
public deployment, or any redirect/new-page staging flow.
</domain>

<decisions>
## Implementation Decisions

### Authoritative Audit First
- **D-01:** Phase 16 begins with a page-by-page authenticated walkthrough comparing
  the running app to `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`
  and its Build Notes before remediation is considered complete.
- **D-02:** The walkthrough should cover all authenticated workspace pages, not only
  the pages named in the status-clutter todo. Captured deltas should drive the
  remediation depth for visual density and spacing.
- **D-03:** Visual fidelity is audit-first rather than broad-CSS-first: fix captured
  mismatches against the design contract, then apply deeper CSS/template
  restructuring only where the walkthrough shows it is needed to match the mocks.

### Folded UAT Scope
- **D-04:** Fold both matching pending UI todos into Phase 16:
  `.planning/todos/completed/2026-08-11-remove-cleanup-page-status-clutter.md` and
  `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`.
  The older navigation/classification todo remains relevant as overlap and
  regression coverage even though most of its behavior was implemented in Phase 15.
- **D-05:** Phase 16 should preserve Phase 15 behavior that is already correct:
  rail-only account selection, exact-ID Review staging, bounded category loading,
  collapse reset, server-side category pagination, Inbox-only unread semantics, no
  export controls, and no Selection Preview redirect for cleanup staging.
- **D-06:** Any remaining older-todo items that conflict with the newer
  status-clutter UAT are resolved in favor of the newer Phase 16 UAT and the
  authoritative design file.

### Navigation And Status Clutter
- **D-07:** Remove the visible numeric badges from the `Unread Only` and `Review`
  left-rail links. Navigation labels must render as text-only and exactly match the
  page titles.
- **D-08:** Removing rail badges must not remove the server-side facts used for
  authorization, Review counts, Unread counts, analytics, or tests. If counts remain
  available to assistive labels or internal state, they must not visually clutter the
  rail labels.
- **D-09:** Remove the generic persistent `Ready to stage for Review` panel from
  Bulk Archive/Delete and Unread Only, and audit the rest of the cleanup workspace
  for the same pattern.
- **D-10:** After staging succeeds or fails, feedback should be row/button-level only:
  affected rows/buttons become visually muted and disabled with `Staged`,
  `Already staged`, or an equivalent concise state. Do not render a persistent
  result panel before or after the action. Errors should use compact row/action-level
  feedback where feasible and must remain accessible.
- **D-11:** Review remains the inspection and approval destination for staged exact
  scopes. Removing the panel must not weaken Review discovery, LIFO ordering,
  immutable exact-scope counts/hashes, duplicate prevention, destructive-action
  warnings, CSRF, or account authorization.

### Unread Only Copy
- **D-12:** Replace the Unread Only header copy with the exact user-approved line:
  `Tidy up your unread emails`.
- **D-13:** Remove both stale explanatory notes above the Unread category list:
  `Counts and rows reflect the last completed metadata sync; provider changes appear
  after the next sync.` and `Unread groups are not mutually exclusive. A sender can
  appear in more than one group.`
- **D-14:** Unread Only still means provider Inbox plus unread state only. The copy
  can become simpler, but implementation and tests must preserve the Inbox-only
  predicate and must not count archived unread All Mail messages.

### Mock-Aligned Cleanup Structure
- **D-15:** Cleanup page controls remain the quiet Phase 15 contract: `Settings` and
  `Expand all` / `Collapse all` adjacent, right-aligned, directly above categories,
  without a toolbar pill/card wrapper.
- **D-16:** Make cleanup buttons more mock-aligned and slightly rounder while
  preserving accessible target sizes and existing local button patterns.
- **D-17:** Remove broad category pill/card enclosure treatment where the design shows
  each category toggle as its own compact row. The category hierarchy should feel
  closer to the mock structure than to a heavy card stack.
- **D-18:** Category headers must show the mock-required metrics on the right:
  sender count plus size for Bulk Archive/Delete, and sender count plus unread total
  for Unread Only when data is available.
- **D-19:** Sender rows should be more compact, cleaner, and professionally weighted,
  with mock-aligned spacing, density, hierarchy, column order, and action placement.
  Preserve semantic headings, responsive behavior, keyboard access, and 44px target
  requirements.

### Category Management
- **D-20:** Replace the visible per-row `Correct classification` clutter with the
  mock-aligned category-management affordances for changing category and creating a
  new category. The implementation may reuse the existing `SenderCategoryService`,
  dialogs, and server-side sanitization, but the visible interaction should match
  the design direction rather than expose internal terminology.
- **D-21:** User-created categories must show a visible `User generated` tag.
- **D-22:** Category corrections remain account-owned and user-scoped. They must not
  create cross-user or global learning rules, and they must retain precedence over
  inferred deterministic categories for that account.

### Safety And Product Constraints
- **D-23:** No user-facing export, CSV, snapshot, or download affordance may appear
  on any workspace page.
- **D-24:** Cleanup staging must continue to materialize exact qualifying provider
  message IDs before creating Review items. Category totals, checkbox counts, sender
  counts, and estimated sizes never become action scopes.
- **D-25:** Delete staging and Review approval must continue to warn about provider
  Trash recovery limits, including Gmail's typical 30-day window.
- **D-26:** Bulk Unsubscribe alignment remains visual/interaction fidelity only. Do
  not weaken eligibility, destination safety, honest accepted/pending/confirmed
  states, or performance expectations.

### Claude's Discretion
- Exact CSS selectors, template factoring, fragment boundaries, and test placement may
  follow established local patterns as long as the authenticated audit confirms the
  pages match the design contract and the locked safety constraints remain intact.

### Folded Todos
- **Remove cleanup page status clutter** — Direct Phase 16 source UAT. Folded for
  rail badge removal, Unread copy cleanup, persistent status panel removal,
  mock-aligned category/header/row density, user-generated category labels, and the
  second page-by-page walkthrough.
- **Refine workspace cleanup navigation and classification** — Folded as overlap and
  regression input. Phase 16 should verify the Phase 15 implementations still match
  the authoritative design and should complete any remaining mismatches without
  reopening behavior that is already correct.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Product And Design Contract
- `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` — sole UI source of
  truth for Phase 16.
- `tidymail-ux-rehaul-draft/pages/build-notes.html` — Build Notes implementation
  contract for the design project.
- `tidymail-ux-rehaul-draft/pages/by-sender-compact-suite.html` — Bulk
  Archive/Delete mock reference cited by the Phase 16 UAT.
- `tidymail-ux-rehaul-draft/pages/unread-compact-suite.html` — Unread Only mock
  reference cited by the Phase 16 UAT.
- `tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html` — Bulk
  Unsubscribe mock reference cited by the Phase 16 UAT.

### Planning Inputs
- `.planning/ROADMAP.md` — Phase 16 roadmap scope and requirement mapping.
- `.planning/REQUIREMENTS.md` — UX-09 through UX-15 plus the global no-export,
  exact-ID, Review, deletion-warning, and Inbox-only constraints.
- `.planning/STATE.md` — current milestone state and promoted UAT history.
- `.planning/todos/completed/2026-08-11-remove-cleanup-page-status-clutter.md` —
  direct Phase 16 source UAT.
- `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`
  — folded overlapping navigation, category, classification, and cleanup-page
  contract todo.

### Prior Phase Context
- `.planning/phases/15-implement-pending-cleanup-and-navigation/15-CONTEXT.md` —
  Phase 15 decisions for exact-ID staging, bounded category loading, Inbox-only
  unread semantics, no export UI, and cleanup-page contract.
- `.planning/phases/14-ui-fidelity-and-route-recovery/14-CONTEXT.md` — shared
  shell, route availability, design-source, safety, and privacy decisions.
- `.planning/phases/13-analytics-export-and-integrated-product-verification/13-CONTEXT.md`
  — prior analytics/export context; note that later project requirements now forbid
  user-facing export affordances.

### Existing Implementation
- `src/main/resources/templates/fragments/workspace-rail.html` — left rail labels,
  account selector, visible badges to remove, Review/Unread link state.
- `src/main/resources/templates/senders.html` — Bulk Archive/Delete shell, controls,
  bulk staging form, dialogs, and current cleanup status fragment inclusion.
- `src/main/resources/templates/unread.html` — Unread Only shell, approved header
  copy target, bulk staging form, dialogs, and current cleanup status fragment inclusion.
- `src/main/resources/templates/fragments/senders-results-frag.html` — sender
  categories, category header metrics, row density, row actions, correction control,
  category creation controls, load-more UI.
- `src/main/resources/templates/fragments/unread-results-frag.html` — Unread notes
  to remove, unread category headers, lazy category content, row actions, correction
  control, load-more UI.
- `src/main/resources/templates/fragments/cleanup-action-result-frag.html` —
  persistent staging status panel to remove or replace with row/action-level feedback.
- `src/main/resources/static/js/cleanup-categories.js` — category open/collapse,
  dialog wiring, delete dialog preparation, staging response reconciliation, row
  disable/mute behavior.
- `src/main/resources/static/css/workspace.css` — rail, controls, cleanup category,
  row, status panel, responsive, and accessible target styling.
- `src/main/java/com/tidymail/service/WorkspaceModelService.java` — authorized
  rail facts and account options.
- `src/main/java/com/tidymail/service/CleanupActionService.java` — exact-scope
  staging result and Review count response.
- `src/main/java/com/tidymail/service/SenderDecisionQueryService.java` — sender
  category summaries and estimated size data.
- `src/main/java/com/tidymail/service/UnreadDecisionQueryService.java` — unread
  category summaries and Inbox-unread row data.
- `src/main/java/com/tidymail/service/SenderCategoryService.java` — account-owned
  category and correction persistence.
- `src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java` —
  sender/unread predicates, bounded category queries, and Inbox-only integration.
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `WorkspaceModelService` already centralizes authorized account options and rail
  facts; Phase 16 should remove visual clutter without rebuilding account state.
- `CleanupActionService` returns exact staged sender/message counts and Review counts;
  Phase 16 can preserve this backend contract while changing where feedback appears.
- `cleanup-categories.js` already disables and mutes staged rows in
  `reconcileCleanupResult`; this is the likely base for row/button-only feedback.
- `SenderCategoryService`, category dialogs, and server sanitization already support
  account-owned categories and corrections; Phase 16 mainly changes visible affordance
  and labeling.
- `SenderCategoryGroup` includes `estimatedSize` and `userGenerated`; `UnreadCategoryGroup`
  includes `senderCount` and `unreadCount`, which support the mock header metrics.

### Established Patterns
- Thymeleaf SSR with HTMX enhancement and full-page fallback.
- Shared `workspace.css` tokens and responsive rules, including 44px touch target
  requirements.
- Server-owned account/query state, CSRF, and exact materialized action scopes.
- Category content is lazily loaded/bounded and reset on collapse.
- Tests use focused MockMvc assertions for rendered templates and service tests for
  exact staging and query semantics.

### Integration Points
- Remove `.rail-nav-badge` visual output from `workspace-rail.html` and adjust tests
  that currently expect badge content.
- Remove `cleanup-action-result-frag :: status` from cleanup pages and update
  `cleanup-categories.js` so success/error handling remains accessible without a
  persistent status panel.
- Update sender/unread result fragments for category header metrics, compact row
  layout, user-generated category tags, and category-management affordances.
- Update `workspace.css` around `.cleanup-category`, `.cleanup-row`,
  `.cleanup-page-actions`, `.cleanup-action-result`, `.category-tools`, and related
  responsive selectors.
- Reconcile tests in `Phase15WorkspaceWebTest`, `Phase10SenderUnreadWebTest`, and
  staging tests with the new row-feedback/no-panel contract.
</code_context>

<specifics>
## Specific Ideas

- Use the exact Unread Only header copy: `Tidy up your unread emails`.
- Treat row/button disabled states as the primary post-stage feedback. Review remains
  the place users inspect exact staged scopes.
- Make the audit report concrete enough to drive planning: page, expected mock/build
  note behavior, current authenticated behavior, severity, and remediation target.
- Keep the remediation narrow where the audit shows the implementation is already
  aligned; do not churn unrelated backend behavior to satisfy visual polish.
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within Phase 16 scope.
</deferred>

---

*Phase: 16-reconcile-ui-with-mocks*
*Context gathered: 2026-08-12T06:00:11Z*
