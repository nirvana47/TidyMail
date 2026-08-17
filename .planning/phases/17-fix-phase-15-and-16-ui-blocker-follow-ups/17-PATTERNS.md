# Phase 17 Pattern Map

**Generated:** 2026-08-13
**Scope:** Read-only implementation map for Phase 17 planning.

## Source Priority

- `.planning/phases/17-fix-phase-15-and-16-ui-blocker-follow-ups/17-CONTEXT.md`
- `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`, especially the two 2026-08-13 continuation sections.
- Phase 16 summaries, review, verification, and UI-SPEC are prior work, not the final authority when they conflict with the latest authenticated feedback.

## Bulk Unsubscribe

- `src/main/resources/templates/unsubscribe.html` defines `#unsubscribe-inspection-slot`, `#sender-stats-modal-slot`, and `#unsubscribe-action-preview-slot`.
- `src/main/resources/templates/fragments/unsubscribe-results-frag.html` contains the primary `Unsubscribe` action and `View stats` HTMX links.
- `src/main/resources/templates/fragments/email-stats-modal-frag.html` already has a dialog-like stats fragment, but it needs modal-first overlay behavior and no full-page primary path.
- `src/main/resources/templates/unsubscribe-stats.html` and `src/main/java/com/tidymail/controller/UnsubscribeStatsController.java` provide the full-page fallback that conflicts with the requested modal-first UX.
- `src/main/resources/templates/fragments/unsubscribe-inspection-frag.html` is the existing inspection/preview contract; reuse it instead of inventing a second staging surface.
- `src/main/java/com/tidymail/service/UnsubscribeStatsService.java` loads sender emails and buckets in memory; profile or bound this path for the reported stats latency.
- `src/test/java/com/tidymail/BulkUnsubscribeContractAlignmentTest.java` and `src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java` should lock modal slots, HTMX script availability, and non-silent action feedback.

## Cleanup Categories

- `src/main/resources/static/js/cleanup-categories.js` owns expand/collapse, lazy loading, selection bars, delete confirmation, and staged feedback.
- `data-category-toggle-all` currently snapshots category nodes on load; fixing Expand all should account for dynamically loaded or initially zero-loaded categories.
- `src/main/resources/templates/fragments/senders-results-frag.html` and `src/main/resources/templates/fragments/unread-results-frag.html` render category chunks. Split initial chunks with headers from appended chunks without repeated headers.
- The category helper block with `Select senders first...`, `Move selected`, and `Create category` still appears in cleanup category fragments and should be removed when the contextual bar is the action path.
- `src/test/java/com/tidymail/Phase15CategoryPagingTest.java`, `Phase15WorkspaceWebTest.java`, and `Phase16WorkspaceFidelityTest.java` are the focused tests to extend.

## Unread Semantics

- `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java` already encodes unread base predicates with `m.unread = true` plus provider `INBOX` label checks. Preserve the dialect-aware H2/Postgres `hasLabel` behavior.
- `src/main/java/com/tidymail/service/WorkspaceModelService.java` sources rail unread counts through `DecisionSurfaceQueryRepository.countUnread`; this is the server-owned count source to reuse for the visible Inbox unread total.
- `src/main/resources/templates/unread.html` currently does not show the numeric Inbox unread total visibly in the page body.
- Attachment grouping depends on `MessageMetadata.hasAttachments`, `MessageMetadataMapper`, and `UnreadDecisionQueryService` group accumulation. Audit ingestion before assuming the `0 senders` result is a UI-only issue.
- Reuse and extend `Phase15UnreadSemanticsTest`, `Phase15WorkspaceWebTest`, and `Phase15StagingTest`.

## Review History

- `src/main/resources/templates/review.html` already uses the desired top-level hierarchy: header, Queue/History tabs, contextual feedback, table.
- `src/main/resources/templates/fragments/review-history-frag.html` currently places long timestamps, nested outcome details, timelines, follow-up blocks, provider links, and compensation links in dense table cells.
- `src/main/resources/templates/fragments/action-outcome-frag.html` contains reusable outcome counts, reason counts, and append-only timeline data. Compact or progressively disclose this data rather than duplicating outcome arithmetic.
- `src/test/java/com/tidymail/Phase12ReviewWebTest.java` and `Phase16WorkspaceFidelityTest.java` should preserve LIFO/source/count safety, strict parameter rejection, no provider calls, and compact hierarchy.

## Rail And Destructive Controls

- `src/main/resources/templates/fragments/workspace-rail.html` contains the brand link, rail collapse summary, utility menu, logout, and destructive delete-data form.
- `src/main/resources/static/css/workspace.css` controls rail brand styling, collapse affordance, menu item padding, destructive details styling, and global button hover behavior.
- The brand link should explicitly suppress normal link underlining while remaining clickable.
- The rail collapse summary likely exposes the default disclosure marker; suppress it and provide horizontal left/right affordance.
- `.delete-data-control summary` needs padding/left alignment parity with `.rail-menu-link` and `.rail-logout`, while preserving destructive warning, confirmation, CSRF, and POST semantics.
- Global `button:hover` can turn destructive/cleanup button text blue. Prefer scoped hover/focus overrides for `.cleanup-action` destructive states instead of weakening every button hover.

## Staging And Safety

- `src/main/java/com/tidymail/controller/CleanupActionController.java` handles `/api/cleanup-actions`, rejects smuggled parameters, validates single values, requires row keys/request tokens, and returns JSON for HTMX.
- `src/main/java/com/tidymail/service/CleanupActionService.java` requires `deleteAcknowledged=true` for `TRASH` and materializes exact IDs before Review staging.
- `cleanup-categories.js` marks successful staging with disabled row controls and live-region announcements. Add visible error behavior for failed selected delete/HTMX paths.
- Do not broaden this phase into concurrency refactors unless required by a blocker; in-JVM locks in `CleanupActionService` are known but not the Phase 17 target.

## Sequencing Risks

- `workspace.css` and `Phase16WorkspaceFidelityTest.java` are shared by most issues. Avoid same-wave parallel plans that modify them concurrently.
- Bulk Unsubscribe HTMX wiring and cleanup selected action staging are functional blockers and should lead the plan before cosmetic polish.
- Unread count/grouping has backend predicate and authenticated data implications; it needs focused repository/service tests plus honest UAT evidence.
- Review History compaction must preserve durable audit semantics while improving density.

## PATTERN MAP COMPLETE
