# Phase 17 Research: Fix Phase 15 and 16 UI Blocker Follow-ups

**Research date:** 2026-08-13
**Scope:** Planning research only. No product code or tests changed.
**Source of truth:** `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`, especially the two 2026-08-13 continuation sections, plus `17-CONTEXT.md`.

## Executive Summary

Phase 17 should be planned as targeted blocker remediation, not a broad UI redesign. The work clusters into five areas:

1. Bulk Unsubscribe stats/action wiring and stats performance.
2. Shared cleanup category controls, pagination fragments, empty-category metrics, and Expand all behavior.
3. Unread Only count/attachment semantics and destructive selected-action staging.
4. Shared rail utility/collapse/brand polish.
5. Review History table density.

The highest-risk files are shared across clusters:

- `src/main/resources/static/css/workspace.css`
- `src/main/resources/static/js/cleanup-categories.js`
- `src/main/resources/templates/fragments/senders-results-frag.html`
- `src/main/resources/templates/fragments/unread-results-frag.html`
- `src/main/resources/templates/fragments/workspace-rail.html`
- `src/main/resources/templates/fragments/review-history-frag.html`

Plan waves should avoid parallel edits to `workspace.css` and avoid concurrent modifications to the same fidelity test file, especially `Phase16WorkspaceFidelityTest`.

## Current Implementation Map By Issue Cluster

### 1. Bulk Unsubscribe Stats Modal And Primary Action Wiring

Current files:

- `src/main/resources/templates/unsubscribe.html`
  - Contains no HTMX script include.
  - Has `#unsubscribe-inspection-slot`, `#unsubscribe-action-preview-slot`, and `#sender-stats-modal-slot`.
- `src/main/resources/templates/fragments/unsubscribe-results-frag.html`
  - Primary `Unsubscribe` row action is an `<a class="action-button">` with `href` and `hx-get` to `/unsubscribe/candidates/{key}`, targeting `#unsubscribe-inspection-slot`.
  - `View stats` is an `<a>` with `href` and `hx-get` to `/unsubscribe/senders/{rowKey}/stats`, targeting `#sender-stats-modal-slot`.
- `src/main/java/com/tidymail/controller/UnsubscribeStatsController.java`
  - Returns `fragments/email-stats-modal-frag :: modal(emailStats)` only when `HX-Request: true`; otherwise returns full `unsubscribe-stats`.
- `src/main/resources/templates/unsubscribe-stats.html`
  - Full page route with `workspace-state`, route heading, inline modal wrapper, and Back link.
- `src/main/resources/templates/fragments/email-stats-modal-frag.html`
  - Existing dialog-shaped fragment with `role="dialog"` but no overlay wrapper matching the referenced `.stats-modal-overlay` mock.
  - Contains inline `<style>`, action row, distribution list, and email metadata table.
- `src/main/java/com/tidymail/service/UnsubscribeStatsService.java`
  - Loads all matching sender emails for the selected date range using `UnsubscribeCandidateRepository.findSenderEmails(...)`.
  - Builds buckets in memory by streaming over all facts for every bucket.
  - Also calls `UnsubscribeCandidateService.candidateForSender(...)`.
- `src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java`
  - `findSenderEmails(...)` has no explicit row limit and returns all rows in the exact range, ordered by `received_at DESC, id DESC`.

Likely root causes:

- `unsubscribe.html` lacks HTMX, so `hx-get` attributes are inert in normal browser interaction. Clicking `View stats` or primary `Unsubscribe` follows the fallback `href`, explaining full-page stats navigation and possibly why primary action appears to do nothing relative to the expected in-page behavior.
- The stats path is potentially unbounded: `findSenderEmails` returns all sender rows in the range and bucket construction is in-memory. Large production-shaped sender ranges can take seconds.
- The existing modal fragment is not an overlay/modal shell matching the mock and lacks obvious close behavior beyond a `Close` link to the full page URL.

Files likely to modify:

- `unsubscribe.html` to load HTMX and any minimal modal close/focus JS if local patterns require it.
- `unsubscribe-results-frag.html` if the primary action needs better visible fallback/error affordance or target placement.
- `email-stats-modal-frag.html` and `workspace.css` for overlay/dialog structure and visual treatment.
- `UnsubscribeStatsService` and `UnsubscribeCandidateRepository` for bounded stats query/aggregation.
- Possibly `UnsubscribeStatsController` if non-HTMX fallback should preserve route but HTMX modal should get a stricter fragment.

Existing tests to reuse/extend:

- `BulkUnsubscribeContractAlignmentTest#templatesExposeIndividualWorkflowAndReuseSharedStatsModal`
- `Phase14InteractionFidelityTest` around stats modal fragment return and progressive fallback assertions.
- `Phase11UnsubscribeWebTest` for Bulk Unsubscribe rendered rows.
- `Phase15UnsubscribePerformanceTest` for production-shaped unsubscribe performance, if Docker/Testcontainers is available.

New/focused coverage suggested:

- Source/rendered assertion that `unsubscribe.html` includes HTMX before relying on `hx-get`.
- MockMvc assertion that stats HTMX request returns only modal fragment, and native request remains a full fallback.
- Contract assertion that modal fragment has overlay/dialog semantics, close control, focus target, and no `workspace-state`.
- Repository/service test that stats query is bounded or split into bounded email table + aggregated counts, with performance evidence.

### 2. Cleanup Category Header, Load More, Empty Metrics, Helper Copy, Expand All

Current files:

- `senders.html`
  - H1 and help icon are in `.page-header`.
  - `Expand all` sits below header in `.cleanup-page-actions`.
- `unread.html`
  - Same pattern as senders.
- `fragments/senders-results-frag.html`
  - Category summary includes size metric:
    - `category.estimatedSize == null ? 'Size pending sync' : category.estimatedSizeLabel`
  - Renders helper block for `category.other`: `Select senders first...`, `Move selected`, `Create category`.
  - `categoryChunk` contains the header row and sender rows together.
  - Load more target is `closest .category-load-actions` with `hx-swap="outerHTML"`.
- `fragments/unread-results-frag.html`
  - Similar helper block for `category.key == 'other'`.
  - Category rows are lazy initially: `.category-page-content` starts empty and JS loads first page.
  - `categoryChunk` includes its own header row and rows.
- `cleanup-categories.js`
  - Captures `categories` once on DOMContentLoaded.
  - `Expand all` only sets `category.open = expand`; it relies on toggle events to load content.
  - Collapsing clears loaded content.
  - Initial category loading uses `data-category-url` if present, otherwise `/senders/categories/{key}`.

Likely root causes:

- Repeated table header on Load more is structural: `categoryChunk` always includes `.cleanup-row-head`; HTMX swaps only `.category-load-actions`, so the returned chunk inserts a second header before the next rows.
- Empty categories show `Size pending sync` because category summaries do not distinguish zero senders from missing size data.
- The stale helper copy remains in both category fragments even though the contextual selection bar is now the action path.
- `Expand all` should expand all categories, but failures on specific categories likely come from lazy/unloaded categories and/or category keys/URLs. The JS captures the initial details list and opens them all, but it does not explicitly fetch every category or surface failed fetches; the senders page also lacks `data-category-url`, relying on constructed `/senders/categories/{key}`.
- The requested help and Expand all placement requires template restructuring in both `senders.html` and `unread.html`.

Files likely to modify:

- `senders.html`, `unread.html` for header-line help/Expand all placement.
- `senders-results-frag.html`, `unread-results-frag.html` for row-only continuation fragments or conditional header rendering.
- `WorkspaceController.senderCategory(...)` / `unreadCategory(...)` if the controller needs to select a row-only fragment for offset > 0.
- `SenderDecisionQueryService`, `UnreadDecisionQueryService`, or DTO mapping if zero-count categories need explicit metric labels.
- `cleanup-categories.js` for robust Expand all load behavior and error visibility.
- `workspace.css` for shared header action layout and row-only fragment visual continuity.

Existing tests to reuse/extend:

- `Phase15CategoryPagingTest`
  - Already validates initial 5 rows and second page does not repeat earlier data, but not repeated header absence.
- `Phase16WorkspaceFidelityTest`
  - Already checks lazy category structure and header text; update expectations for no `Action` heading and no helper block.
- `Phase15WorkspaceWebTest` and `Phase10SenderUnreadWebTest`
  - Existing source/rendered coverage for cleanup controls, categories, dialogs.

New/focused coverage suggested:

- MockMvc HTMX category continuation at offset 5 must not include `.cleanup-row-head` or column labels.
- Render/source assertions that `Action` header is absent on Bulk Archive/Delete and Unread Only when no row action column exists.
- Source/rendered assertions that helper text `Select senders first...`, `Move selected`, and `Create category` are absent from category bodies.
- Unit/source test that zero-sender category size label is blank or `0 KB`, not `Size pending sync`.
- JS/source test or browser UAT checklist for Expand all opening and loading all category keys, including initially empty/unloaded categories.

### 3. Unread Only Inbox Counts, Attachments, Delete Selected Hover And Staging

Current files:

- `DecisionSurfaceSql.unread(...)`
  - Base predicate already requires `m.unread = true` and `hasLabel("m.labels", "INBOX", h2)`.
  - Attachment category predicate is `has_attachments = true`.
- `WorkspaceModelService.buildResolved(...)`
  - Computes `inboxUnreadCount` from `decisionQueryRepository.countUnread(...)` with default `UnreadQuery`.
  - Rail exposes the count only in `aria-label`, not visible text.
- `workspace-rail.html`
  - `Unread Only` label includes Inbox unread count only in `aria-label`, preserving Phase 16 text-only rail labels.
- `unread.html`
  - No visible top-of-page authoritative Inbox unread total.
  - `Delete selected` is a type button opening the delete dialog via `data-delete-source="unread-bulk-stage"`.
- `cleanup-categories.js`
  - `prepareDeleteDialog(...)` copies selected form fields into the delete confirmation form and appends hidden action when absent.
  - On successful HTMX stage, parses JSON and announces a visually hidden success message.
  - If setup fails because dialog/source/target is missing, it silently returns.
- `CleanupActionController`
  - Requires POST body parameters only, CSRF, single values except `rowKey`, delete acknowledgement, and `HX-Request` for JSON result.
- `Phase15UnreadSemanticsTest`
  - Confirms Inbox+unread predicate excludes archived, spam, trash, and read rows.
  - Confirms attachment category can contain rows and materialization preserves Inbox-unread.

Likely root causes:

- The backend SQL predicate appears correct for indexed metadata. The user-reported mismatch may be due to:
  - Gmail provider label ingestion not matching the user's live Inbox state.
  - Rail count being aria-only, so the authoritative count is not visible.
  - Stale/incomplete sync state not communicated near Unread Only categories.
  - User comparing current Gmail UI to an older indexed snapshot.
- `Has attachments` showing `0 senders` / `0 unread` is suspicious but not explained by query code alone. It depends on `GmailMailService.hasAttachmentMetadata(...)`, `MessageMetadataMapper.setHasAttachments(...)`, migration default `false`, and whether the sync is using Gmail METADATA payload parts that include attachment metadata.
- `Delete selected` no visible staging could be:
  - No checked `rowKey` inputs loaded/selected because category content is lazy or selection got reset.
  - Dialog lookup/source mismatch in `prepareDeleteDialog`.
  - HTMX missing or failed request hidden behind visually hidden feedback.
  - `CleanupActionController` rejecting parameters as bad request, with current JS only surfacing errors for successful HTMX JSON parsing path.
- Hover contrast is CSS: `.cleanup-action` uses white text, `.cleanup-action:hover` changes background only. The reported blue text could come from a broader button/link hover rule or inherited `.rail-menu-panel button:hover`-style rule in a specific context; verify computed styles in browser.

Files likely to modify:

- `unread.html` and/or `workspace-rail.html` to make the Inbox unread count visible in the allowed location.
- `WorkspacePageModel` only if the current `inboxUnreadCount` needs richer labeling/freshness, but it already has a count.
- `GmailMailService`, `MessageMetadataMapper`, `MessageMetadata`, and `DecisionSurfaceSql` only if attachment ingestion/query audit finds a real defect.
- `cleanup-categories.js` for visible error handling on HTMX failures and non-HTMX failures, and for making delete dialog setup failures explicit.
- `workspace.css` for high-contrast destructive hover/focus states.
- `CleanupActionController` / `CleanupActionService` only if request payload from dialog omits needed parameters or action mapping rejects valid destructive staging.

Existing tests to reuse/extend:

- `Phase15UnreadSemanticsTest`
- `Phase15StagingTest#unreadPathStagesOnlyOwnedInboxUnreadIdsForSenderCategoryAndFilters`
- `Phase15StagingTest#unreadOverlappingCategorySelectionsUnionIdsIntoOneSenderAction`
- `Phase10SenderUnreadWebTest` for Unread route categories and rendered controls.
- `Phase9MetadataIndexTest` for Gmail metadata privacy/attachment ingestion.

New/focused coverage suggested:

- Rendered `/unread` page includes visible text for authoritative Inbox unread total, with accessible label and no rail badge clutter regression.
- Attachment grouping fixture with Gmail-like payload metadata proves `has_attachments=true` is persisted and appears in `attachments` group.
- MockMvc POST using the exact dialog-emitted payload for `Delete selected` on Unread returns JSON and creates queued Review action with `TRASH`, without provider mutation.
- JS/source assertion that HTMX failure paths show accessible visible error, not silent no-op.
- CSS/source assertion that destructive cleanup button hover/focus keeps `color: #fff` or equivalent high-contrast token.

### 4. Rail Brand, Utility Menu Padding, Collapse Direction

Current files:

- `workspace-rail.html`
  - Brand is an `<a class="rail-brand" href="/senders">`.
  - Collapse summary text is `Collapse rail` / `Expand rail`, no arrow glyph or horizontal disclosure affordance.
  - Utility menu has `Feedback`, POST Logout, and nested `<details class="delete-data-control">`.
- `workspace.css`
  - `.rail-brand` has layout/color rules for children but no explicit `text-decoration: none`.
  - `.rail-menu-panel button, .rail-menu-link` get shared padding.
  - `.delete-data-control summary` has its own styling and lacks the shared width/padding/border treatment of menu links/buttons.
  - Collapsed rail uses `writing-mode: vertical-rl`, which can visually reinforce vertical disclosure rather than horizontal collapse.

Likely root causes:

- Brand looks underlined because anchor default text decoration is not explicitly reset on `.rail-brand`.
- Delete data details summary is outside the `.rail-menu-panel button, .rail-menu-link` shared selector and therefore has different padding/width/alignment.
- Collapse control is text-only; any marker shown is the default details marker, likely down/right oriented and not tailored to horizontal collapse.

Files likely to modify:

- `workspace-rail.html` for explicit arrow/disclosure markup or accessible labels.
- `workspace.css` for brand link chrome, shared utility summary alignment, collapse marker direction, focus states.

Existing tests to reuse/extend:

- `Phase16WorkspaceFidelityTest#rail...`
- `Phase14WorkspaceFidelityTest`
- `Phase13WorkspaceResponsiveTest`

New/focused coverage suggested:

- Source assertions for `.rail-brand { text-decoration: none; }` or equivalent.
- Source assertions that delete-data summary shares rail menu padding/alignment.
- Source/rendered assertion that collapse affordance uses left-pointing visible arrow text/icon while retaining accessible expand/collapse labels.

### 5. Review History Table Density

Current files:

- `review.html`
  - Clean header and Queue/History tabs.
- `fragments/review-history-frag.html`
  - Seven columns: Time, Target, Account, Action, Mode, Outcome, Follow-up.
  - Renders raw `row.occurredAt`, long target, nested `action-outcome-frag`, verbose undo text, unsubscribe/protect explanatory blocks, compensation text.
- `workspace.css`
  - `.review-data-table` uses `table-layout: fixed`, min-width 900px.
  - `.history-table` min-width 920px.
  - `.outcome-counts` is a three-column nested grid; `.event-timeline` remains visible.

Likely root causes:

- The table is technically compact relative to older status panels, but content density is high because audit details are expanded inline per row.
- Long ISO timestamps and nested outcome/follow-up blocks consume cell space.
- Current tests only assert absence of metrics panels and presence of durable copy; they do not constrain visual density or summarized row content.

Files likely to modify:

- `review-history-frag.html` to move verbose details into `<details>` or a compact follow-up/actions menu while preserving durable audit data.
- `action-outcome-frag.html` if a compact variant is useful.
- `ReviewActionQueryRepository` / DTOs only if formatted timestamps or summarized outcome labels should be prepared server-side.
- `workspace.css` for compact history table widths, row details, badges, and responsive behavior.

Existing tests to reuse/extend:

- `Phase16WorkspaceFidelityTest#reviewQueueAndHistoryShareCompactTableHierarchyWithoutMetrics`
- `Phase12ReviewWebTest`
- `Phase12UndoCompensationTest`

New/focused coverage suggested:

- Render/source assertions that raw nested outcome/timeline/follow-up blocks are collapsed behind details/menu by default.
- Assertions that undo eligibility, provider options links, linked unsubscribe outcome, and protection compensation remain available.
- Accessibility checks for details/menu labels and keyboard focus.

## Cross-Cutting Safety Constraints

- Do not reintroduce export, CSV, snapshot, download, or share affordances in authenticated workspace templates.
- Cleanup pages must stage exact immutable message-ID scopes to Review; they must not execute provider mutations directly.
- Preserve CSRF, account authorization, owner scoping, protected-sender handling, delete acknowledgement, Gmail Trash warning, and 1,000-ID guard behavior.
- Counts, category totals, size estimates, and checkbox counts are not execution scopes.
- Keep metadata-only privacy: no body or attachment content ingestion.
- Do not reset or revert existing worktree changes. Current `git status --short` showed modified `.planning/ROADMAP.md`, modified `.planning/STATE.md`, and an untracked Phase 17 planning directory before this research write.

## Suggested Plan Decomposition And Sequencing

Recommended sequencing:

1. **Plan 17-01: Bulk Unsubscribe interaction and bounded stats**
   - Add HTMX/runtime support or equivalent local JS to `unsubscribe.html`.
   - Convert stats to an actual in-page modal overlay and make close/focus behavior explicit.
   - Make primary `Unsubscribe` action visibly load inspection/preview or accessible error.
   - Bound/profile stats query path.
   - Tests: BulkUnsubscribeContractAlignmentTest, Phase14InteractionFidelityTest, Phase11UnsubscribeWebTest, stats service/repository focused test, optional performance test.

2. **Plan 17-02: Cleanup category controls and pagination fragments**
   - Move help/Expand all to header line on Bulk Archive/Delete and Unread Only.
   - Remove `Action` header where no row actions exist.
   - Split initial category chunk from continuation row-only chunk, or conditionally suppress header when offset > 0.
   - Remove stale category helper blocks.
   - Fix empty category size label.
   - Harden Expand all to load every category and surface failures.
   - Tests: Phase15CategoryPagingTest, Phase16WorkspaceFidelityTest, Phase15WorkspaceWebTest, Phase10SenderUnreadWebTest.

3. **Plan 17-03: Unread Only semantics visibility, attachment audit, destructive staging**
   - Display server-owned Inbox unread count visibly while preserving text-only rail contract unless the plan explicitly chooses rail visible count.
   - Audit Gmail attachment metadata ingestion through mapper and query; fix only if evidence shows a defect.
   - Ensure `Delete selected` dialog payload stages exact IDs and visibly reports success/error.
   - Fix destructive hover/focus contrast.
   - Tests: Phase15UnreadSemanticsTest, Phase15StagingTest, Phase9MetadataIndexTest, rendered `/unread` tests.

4. **Plan 17-04: Rail and Review History polish**
   - Brand link chrome, utility menu padding, collapse arrow direction.
   - Compact Review History row presentation while preserving audit details behind disclosure/menus.
   - Tests: Phase16WorkspaceFidelityTest, Phase14WorkspaceFidelityTest, Phase12ReviewWebTest, responsive/source checks.

5. **Plan 17-05: Integrated verification and authenticated UAT closure**
   - Run focused non-Docker suite.
   - Run full suite or explicitly record Testcontainers blocker if still present.
   - Authenticated desktop, 375x812, and 200% zoom walkthrough for Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics, and rail.

Conflict-risk notes:

- `workspace.css` will be touched by every plan if not coordinated. Prefer one CSS-owner wave or reserve CSS edits for the end of each plan with serialized merges.
- `Phase16WorkspaceFidelityTest` is the likely shared assertion file for all visual/source contracts. Split new tests by cluster where possible:
  - Bulk unsubscribe assertions in `BulkUnsubscribeContractAlignmentTest` / `Phase14InteractionFidelityTest`.
  - Cleanup pagination behavior in `Phase15CategoryPagingTest`.
  - Unread destructive behavior in `Phase15StagingTest`.
  - Rail/review source checks in `Phase16WorkspaceFidelityTest`.
- `senders-results-frag.html` and `unread-results-frag.html` share structure. Change both together or extract/align test expectations in the same task to avoid drift.

## Validation Architecture

Use a layered validation approach:

- **Source contract tests** for static UI constraints:
  - No export/snapshot/download affordances.
  - HTMX loaded wherever `hx-*` drives required behavior.
  - No repeated category header in continuation fragments.
  - No stale helper copy.
  - Brand/rail/collapse markup and CSS tokens.

- **MockMvc rendered tests** for authenticated route behavior:
  - `/unsubscribe` row actions target in-page slots.
  - `/unsubscribe/senders/{rowKey}/stats` returns modal fragment with `HX-Request`.
  - `/senders/categories/{key}?offset=5` and `/senders/categories/unread-{key}?offset=5` return row continuation without header.
  - `/unread` shows visible Inbox unread count.
  - `/api/cleanup-actions` accepts exact delete-selected payload and returns JSON for HTMX.

- **Repository/service tests** for data semantics:
  - Inbox unread excludes archived/read/spam/trash.
  - Attachment metadata persists and groups correctly.
  - Stats queries are bounded and do not load unbounded sender history for modal display.
  - Exact staging deduplicates IDs and preserves 1,000/1,001 guard behavior.

- **CSS/responsive checks**:
  - 375px no page-level horizontal scroll for primary pages.
  - 44px touch targets retained.
  - Destructive hover/focus contrast remains readable.
  - Review History remains usable at desktop and narrow widths.

- **Manual authenticated UAT**:
  - Required because the blockers were found in live authenticated localhost.
  - Must cover desktop, 375x812, and 200% zoom.
  - Must include actual clicks for stats modal, primary Unsubscribe, Expand all, Load more, category move-empty state, Unread Delete selected, rail utility menu, rail collapse, and Review History.

## Verification Commands

Focused commands likely useful during Phase 17:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,BulkUnsubscribeContractAlignmentTest,Phase14InteractionFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15CategoryPagingTest,Phase15UnreadSemanticsTest,Phase15StagingTest,Phase11UnsubscribeWebTest,Phase12ReviewWebTest' test
```

For non-Docker broad regression, matching Phase 16 verification precedent:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='!*PostgresMigrationTest,!Phase13PerformanceTest,!Phase15UnsubscribePerformanceTest' test
```

Full suite if Testcontainers/Docker is available:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn test
```

Docker/Testcontainers note:

- Phase 16 verification had `docker info` reachable through Colima, but full Maven still failed in Testcontainers-dependent classes with `Could not find a valid Docker environment`.
- Phase 17 should record the exact state if this persists; do not treat it as an app assertion failure without evidence.

## Open Questions For Planner

- Should the visible Inbox unread total live at the top of Unread Only, or should Phase 17 intentionally revise the Phase 16 text-only rail rule to allow a visible rail count? `17-CONTEXT.md` allows either top-of-page or sidebar, but requirements still list UX-09 text-only rail labels without numeric badge clutter.
- Should Bulk Unsubscribe stats show all matching emails, or a bounded sample plus aggregate totals? The current code loads all rows. A bounded modal is safer for latency, but the UX copy should be honest if the table is sampled.
- Should Review History compactness be solved with row disclosures, a detail drawer/modal, or tighter inline summaries? The safest minimal change is collapsed per-row details preserving existing audit text.
- The referenced mock range `tidymail-ux-rehaul-draft/pages/unsubscribe-compact-suite.html:2449-2505` did not produce content in this checkout with `nl -ba ... | sed -n '2440,2515p'`, although the file exists. Recheck exact line numbers or use the `.design` Build Notes before implementing modal visuals.

## RESEARCH COMPLETE
