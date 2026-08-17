# Phase 16 Research: Reconcile UI With Mocks

## Purpose

Phase 16 is an audit-first UI reconciliation pass for the authenticated workspace. It should align the running app with `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design` and the exported Build Notes/mocks, while preserving the Phase 15 safety model: exact message-ID staging to Review, no provider mutation before Review approval, Inbox-only unread semantics, no user-facing export affordances, and no redirect/new page staging flow.

No implementation code changes are part of this research artifact.

## Required Inputs Read

- `.planning/STATE.md`
- `.planning/ROADMAP.md`
- `.planning/REQUIREMENTS.md`
- `.planning/phases/16-reconcile-ui-with-mocks/16-CONTEXT.md`
- `.planning/todos/completed/2026-08-11-remove-cleanup-page-status-clutter.md`
- `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`
- `tidymail-ux-rehaul-draft/pages/build-notes.html`
- `tidymail-ux-rehaul-draft/pages/by-sender-compact-suite.html`
- `tidymail-ux-rehaul-draft/pages/unread-compact-suite.html`
- `tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html`
- Phase 16 named implementation files across templates, JS, CSS, services, repository predicates, and tests.

## Current Implementation Surfaces

### Shared Workspace Shell

- `src/main/resources/templates/fragments/workspace-rail.html`
  - Current rail already has correct brand link to `/senders`, route names, account selector, add-account option, logout/delete controls.
  - Current mismatch: visible `.rail-nav-badge` spans still render for `Unread Only` and `Review`, even though the new UX-09 contract requires text-only nav labels. The server-side count facts should remain available via `WorkspaceModelService`; only the visible rail clutter should be removed.
- `src/main/java/com/tidymail/service/WorkspaceModelService.java`
  - Builds authorized account options, selected account label, queued action count, and Inbox unread count.
  - Should stay as the server-owned source of facts. Phase 16 should not remove count calculation or authorization logic.
- `src/main/resources/static/css/workspace.css`
  - Rail layout and selector density are mostly Phase 15 aligned.
  - Badge styling is implicit through flex nav layout; removal may require minor rail spacing cleanup.

### Bulk Archive/Delete

- `src/main/resources/templates/senders.html`
  - Current shell uses rail-only account scope, right-aligned `Settings` and `Expand all`, direct exact staging form, delete warning dialog, correction dialog, category organization dialog, and `cleanup-action-result-frag :: status`.
  - Current mismatch: header helper says "stage exact actions for Review" rather than the mock's task-oriented cleanup copy; persistent status panel renders below bulk staging; correction dialog exposes internal "Correct classification" terminology.
- `src/main/resources/templates/fragments/senders-results-frag.html`
  - Current categories are `<details class="cleanup-category">`, closed by default, with 5-row chunks, `Load more`, `Load all`, per-row exact staging forms, cross-category checkboxes, protected sender disabling, and category tools for Other.
  - Current mismatches:
    - Category header only renders a single `category-count`; mock shows name/description left and sender count plus size/unread metric right.
    - User-generated groups have DTO support but are not currently populated by `SenderDecisionQueryService.categories`; visible `User generated` tags are therefore missing.
    - Per-row `Correct classification` button is visible under every sender; mocks prefer category-management affordances such as `Change type`, `Move selected`, and `Create category`, with centered modal workflows.
    - Row density and hierarchy are heavier than the compact mock rows.
- `src/main/java/com/tidymail/service/SenderDecisionQueryService.java`
  - Provides category summaries with `senderCount` and `estimatedSize`, and bounded 5-row category pages.
  - Currently returns predefined groups only; research suggests planner should decide whether user-created categories need to be added to the group list here to satisfy UX-14 visible tagging.
- `src/main/java/com/tidymail/dto/SenderCategoryGroup.java`
  - Already includes `estimatedSize` and `userGenerated`.

### Unread Only

- `src/main/resources/templates/unread.html`
  - Current shell uses correct title `Unread Only`, rail-only account scope, settings/expand controls, direct exact staging form, and delete warning dialog.
  - Current mismatch: helper copy is not the locked text. It must be exactly `Tidy up your unread emails`.
  - Current mismatch: persistent `cleanup-action-result-frag :: status` is rendered.
- `src/main/resources/templates/fragments/unread-results-frag.html`
  - Current mismatch: still renders both stale notes above categories:
    - `Counts and rows reflect the last completed metadata sync; provider changes appear after the next sync.`
    - `Unread groups are not mutually exclusive. A sender can appear in more than one group.`
  - Current category count already combines senders and unread total when available, which is close to the mock, but the right-side metric presentation needs mock-aligned styling.
  - Current rows mirror the sender cleanup row contract and include visible `Correct classification` clutter.
- `src/main/java/com/tidymail/service/UnreadDecisionQueryService.java`
  - Builds overlapping unread groups and computes `senderCount`/`unreadCount`.
  - Uses in-memory grouping from `DecisionSurfaceQueryRepository.streamUnread`; this is already Phase 15 behavior. Do not alter unless audit identifies performance or correctness drift.
- `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java`
  - Unread base predicate requires `m.unread = true` and the `INBOX` label via `hasLabel("m.labels", "INBOX", h2)`.
  - This is the locked Inbox-only unread predicate; Phase 16 must preserve it.

### Shared Staging Feedback

- `src/main/resources/templates/fragments/cleanup-action-result-frag.html`
  - Current persistent panel says `Ready to stage for Review`, explains exact IDs, links to Review, and includes OOB review count fragment.
  - Phase 16 should remove this panel from cleanup pages. If the review-count OOB fragment remains useful for internal HTMX responses, decouple it from any visible persistent panel.
- `src/main/resources/static/js/cleanup-categories.js`
  - `reconcileCleanupResult` currently requires `#cleanup-action-status`, updates the panel, disables/mutes staged rows, changes action text to `Staged` or `Already staged`, updates an old `.rail-queue-status` selector, then focuses the status panel.
  - Good reusable base: row/button disabling and staged label logic.
  - Required change direction: success/failure feedback becomes row/button-level and accessible without a persistent result panel. Errors still need a compact accessible target, likely near the triggering row/form or transient live region.

### Bulk Unsubscribe

- `src/main/resources/templates/unsubscribe.html`
- `src/main/resources/templates/fragments/unsubscribe-results-frag.html`
  - Current page mostly follows the individual sender workflow: category/date/search/settings controls, compact table, Unsubscribe or Archive/Delete primary action, ellipsis menu with stats/archive/delete.
  - Phase 16 scope is visual/interaction audit only. Do not convert Bulk Unsubscribe into grouped categories. Do not weaken destination safety, consent, honest states, or bounded query behavior.

### Tests Likely Affected

- `src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java`
  - Currently expects `class="rail-nav-badge"`; must invert for UX-09.
- `src/test/java/com/tidymail/Phase15WorkspaceWebTest.java`
  - Currently expects `Correct classification`, `cleanup-action-status`, stale Unread notes, and `cleanup-action-result-frag` inclusion. These assertions need to move to the new Phase 16 contract.
- `src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java`
  - Currently locks old Unread notes and cleanup status fragment. Update structural UI assertions while keeping exact staging and no preview redirect protections.
- Preserve or extend:
  - `Phase15UnreadSemanticsTest` for Inbox plus unread only, archived unread exclusion, overlapping categories, exact ID dedupe.
  - `Phase15StagingTest` for exact-ID staging, ownership, delete acknowledgement, duplicate/reuse behavior, and no provider mutation.
  - no-export template tests from Phase 13/14/15.

## Target Design Contract

### Build Notes

- Bulk Archive/Delete:
  - No search box unless explicitly added later.
  - Account selector belongs only in the left rail.
  - Categories closed by default, showing sender count and optional total size.
  - Exactly two primary page controls: `Settings` and `Expand all` / `Collapse all`.
  - Expanded categories show sender, email count, optional size, domain, and one action button matching Settings.
  - Action button label changes with Settings, but high-attention color remains consistent.
  - Other is special: selectable senders before moving them into an existing category or creating a new category.
  - Move/create category workflows open centered modals; do not place the full workflow inline.
  - User-created categories appear below the closest predefined category and carry a clear `User generated` label.
  - Category names must be validated server-side and client-side, escaped, and stored/displayed as plain text.
  - Category size is optional; hide or mark estimated if reliable totals are unavailable.

- Unread Only:
  - Same quiet structure as Bulk Archive/Delete: rail-only account selector, simple header, optional loading banner, Settings, Expand all/Collapse all, category dropdowns.
  - No page-level account selector, search box, filter-pill strip, summary-stat strip, or bottom next-step mock.
  - Human-readable buckets: lists/automated mail, older than 30 days, direct mail, attachments, Other.
  - Lists/automated depends on backend classification; UI should not imply it is manually chosen unless overridden.
  - Groups are not mutually exclusive, but Phase 16 UAT says do not render that stale explanatory note above the list.
  - Direct mail rows sort by sender volume.
  - Other follows Bulk Archive/Delete Other: selectable senders plus centered modal actions to move or create category.
  - Per-sender action button label follows Settings; high-attention color stays consistent.

- Bulk Unsubscribe:
  - Individual sender workflow, not grouping workflow.
  - Same quiet shell: rail-only account selector, simple header, optional loading banner, no bottom next-step mocks.
  - Top controls: category and time duration, compact search, right-aligned Settings for Archive/Delete only.
  - UI must not claim unsubscribe success until execution results are known.
  - Rows show sender/domain, email count, Read %, primary action, ellipsis menu with View stats, Archive all, Delete all.
  - Stats modal has action buttons, chart, and compact email table.

### Mock Export Details

- Rail labels in mock exports are plain text: `Bulk Archive/Delete`, `Unread Only`, `Bulk Unsubscribe`, `Review`, `Analytics`; no visible numeric nav badges.
- Category headers use a left `category-title` block and right `category-meta` block:
  - sender count plus size for Bulk Archive/Delete, for example `84 senders` and `About 2.4 GB`.
  - sender count plus unread total for Unread Only, for example `74 senders` and `2,780 unread`.
- Rows are compact grid/table-like structures:
  - Bulk Archive/Delete table head: Sender, Emails, Size, Domains, Action.
  - Other adds Select.
  - Unread table head: Sender, Unread, Size, Domains, Action; Direct mail may include Type.
- User-created categories show `User generated` inline with the category description and in the category modal example.
- Other category tools read like user-facing workflow controls: select senders first, then `Move selected` or `Create category`.

## Mismatch Map And Likely Strategy

1. Rail badge clutter
   - Remove visible `.rail-nav-badge` spans for Unread Only and Review.
   - Keep server counts in `WorkspacePageModel` and optionally accessible labels if UX accepts non-visible count context. Do not reintroduce visible count pills.

2. Unread copy and notes
   - Replace `unread.html` helper with exact text `Tidy up your unread emails`.
   - Delete the two `<p>` notes in `unread-results-frag.html`.
   - Preserve the underlying overlapping-category and freshness facts in tests/docs, not as page clutter.

3. Persistent cleanup status panel
   - Remove `cleanup-action-result-frag :: status` from `senders.html` and `unread.html`.
   - Rework `cleanup-categories.js` so success no longer depends on `#cleanup-action-status`.
   - Keep row/button-only success states: disabled controls, muted row, action text `Staged` or `Already staged`.
   - Provide compact accessible error feedback for failed staging. Avoid redirect/new page flow.
   - If OOB review-count updates are still needed, keep them separate from visible UI or drop visible count updates entirely under UX-09.

4. Category header metrics
   - Sender categories already carry `estimatedSize`; render a separate right-side metric structure rather than one combined count.
   - Unread categories already carry `senderCount()` and `unreadCount()`; render as separate right-side metrics.
   - Use size labels only when available; otherwise use mock-compatible `Size pending` or omit/estimated wording based on planner decision.

5. Category and row density
   - Rework CSS around `.cleanup-category`, `.cleanup-category summary`, `.cleanup-category-body`, `.cleanup-row`, `.cleanup-row-head`, `.cleanup-action`, `.category-tools`, and mobile rules.
   - Target the mock's compact row hierarchy and rounded button treatment while preserving 44px touch targets at responsive breakpoints.
   - Avoid broad toolbar/card/pill wrappers around cleanup controls.

6. Category management affordances
   - Replace visible `Correct classification` copy with mock-aligned controls:
     - likely `Change type` for direct/manual classification cases.
     - `Move selected` and `Create category` for Other/category management.
   - Reuse `SenderCategoryService`, existing dialogs, server sanitization, and ownership checks.
   - Planner should resolve whether this is a copy/placement change only or whether user-created category groups must be rendered as first-class groups from `SenderDecisionQueryService`.

7. User-generated category label
   - DTO supports `SenderCategoryGroup.userGenerated`.
   - Current service does not appear to emit user-created groups in `SenderDecisionQueryService.categories`; this is the likely backend-adjacent implementation seam for visible `User generated` tags.
   - Preserve account-owned scope: user-created categories and assignments must not become global rules.

8. Bulk Unsubscribe audit
   - Validate visual fidelity against `unsubscribe-compact-suite.html`.
   - Keep individual-sender workflow, exact date range, search/category/date/default-action controls, and safe unsubscribe outcome model.
   - Do not add grouped categories or mark-read default action here.

## Hard Constraints To Preserve

- No export, CSV, snapshot, or download affordances on authenticated workspace pages.
- Exact-ID Review staging remains mandatory for cleanup-page actions.
- Cleanup pages must not redirect to Selection Preview or a new staging page.
- Row/button-only staging feedback replaces the persistent status panel.
- Review remains the inspection and approval destination, ordered and exact as already implemented.
- Unread page copy must be exactly `Tidy up your unread emails`.
- Unread Only predicate remains provider Inbox label plus unread state. Archived unread All Mail messages must stay excluded.
- Delete staging and Review execution must keep the Gmail Trash recovery warning.
- CSRF, ownership checks, protected sender handling, duplicate prevention, immutable scope hashes/counts, and no provider mutation before Review approval remain non-negotiable.

## Test And Verification Strategy

### Audit First

Create a Phase 16 walkthrough artifact before or during implementation planning:

| Page | Audit focus |
| --- | --- |
| Bulk Archive/Delete | rail labels, header/helper, controls, category metrics, row density, staging feedback, category management, no exports |
| Unread Only | exact helper copy, stale notes removed, category metrics, Inbox-only count sanity, row density, staging feedback, no exports |
| Bulk Unsubscribe | quiet shell, compact controls, individual sender rows, stats/preview placement, no grouped category drift, no exports |
| Review | still discoverable through rail, LIFO/exact counts/warnings intact, no export controls |
| Analytics | one-account analytics contract, no export/snapshot/download affordances |

The walkthrough should record: page, expected mock/build-note behavior, current behavior, severity, remediation target, and closure evidence.

### Automated Tests

- Add or update template tests for UX-09 through UX-14:
  - rail contains route labels but no visible `.rail-nav-badge`.
  - Unread helper contains exact copy and does not contain the two stale notes.
  - Senders/Unread templates do not include `cleanup-action-result-frag :: status` or `Ready to stage for Review`.
  - Result fragments render separate category meta for sender count plus size/unread total.
  - visible row controls use new mock-aligned wording, not `Correct classification`.
  - user-created groups render `User generated`.
- Update JS structural tests:
  - `reconcileCleanupResult` still disables staged rows/buttons and sets `Staged`/`Already staged`.
  - success handling does not require `#cleanup-action-status`.
  - error handling remains accessible through a compact live region or row/form target.
- Preserve service/repository tests:
  - `Phase15UnreadSemanticsTest.everyUnreadReadUsesInboxAndUnreadTogether`.
  - unread materialization/dedupe and 1000/1001 boundary tests.
  - `Phase15StagingTest` exact ownership, delete acknowledgement, duplicate/reuse, no provider mutation.
  - category paging tests for 5/+5/load-all/422 behavior.
  - no-export template tests across all five workspace pages.
- Consider adding a focused Phase 16 test class instead of overloading old phase tests. Old tests that assert now-invalid clutter should be inverted or retired.

### Manual Verification

- Run authenticated localhost at desktop, 375x812, and 200% zoom.
- Verify keyboard reachability, focus states, dialogs, row staging, delete warning, and no page-level horizontal scrolling.
- Stage sender and unread actions and confirm:
  - no persistent panel appears.
  - row/button changes in place.
  - Review contains the exact staged action with immutable count/hash.
  - mailbox provider is not mutated before Review approval.
- Check representative Unread data against a fixture or live account where archived unread messages exist; ensure they do not appear.
- Inspect page source/DOM for export/snapshot/download strings and links.

## Validation Architecture

Phase 16 is a Nyquist-style validation problem because visual reconciliation can easily pass unit tests while missing the actual user-facing mismatch. Use two layers:

1. Structural contract tests cover stable invariants that should not regress: no visible rail badges, no stale Unread notes, no persistent cleanup status panel, no export affordances, exact copy, exact staging forms, and no Selection Preview redirects.
2. Authenticated walkthrough evidence covers perceptual and interaction fidelity: page-by-page screenshots/notes against Build Notes and mock exports, responsive/mobile/zoom behavior, row density, category hierarchy, dialog placement, and staging feedback.

The planner should require both layers before closing UX-15. Automated tests are necessary but insufficient for this phase; the audit artifact is a first-class deliverable.

## Planning Notes

- Treat this as primarily template/CSS/JS work, with limited service work only if user-created categories are not currently emitted into cleanup category groups.
- Do not refactor export backend code; the current product constraint is no discoverable UI affordance.
- Do not reopen Phase 15 behavior that is already correct: rail-only account selection, bounded category loading, collapse reset, direct exact staging, Review LIFO safety, and Inbox-only unread.
- Keep implementation scoped to authenticated workspace pages and associated focused tests.
