---
phase: 15-implement-pending-cleanup-and-navigation
plan: "05"
subsystem: ui
tags: [thymeleaf, unread, pagination, classification, inbox]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Exact staged cleanup foundations, account-local classification, Inbox-only unread semantics, and Plan 09 correction routing
provides:
  - Freshness-first summary-only Unread category shells with overlapping membership
  - Stable 5/+5/Load All paging with the shared 1,000/1,001 guard
  - Account-owned semantic and custom correction controls on every loaded unread row
affects: [phase-15-unread-staging, workspace-responsive, unread-corrections]

actuals:
  tokens: 16266
  tasks: 1
  commits: 2

tech-stack:
  added: []
  patterns:
    - Exact category totals remain available while initial browser row bodies stay empty
    - Unread category chunks reuse the shared five-row and bounded Load All contract
    - Unread corrections submit the same semantic/custom targets as sender corrections

key-files:
  created: []
  modified:
    - src/main/java/com/tidymail/dto/UnreadCategoryGroup.java
    - src/main/java/com/tidymail/dto/UnreadPageModel.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/java/com/tidymail/service/SenderCategoryService.java
    - src/main/resources/templates/unread.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java

key-decisions:
  - "Keep exact unread summary totals in the page model while withholding sender rows until a category is opened."
  - "Route unread category chunks through the existing category loader namespace with an unread-prefixed key, preserving Plan 09 collapse/reset behavior without changing undeclared JavaScript."
  - "Post semantic and custom corrections directly to Plan 09's strict owned correction route; do not create an unread-specific mutation path."

requirements-completed: [ACTN-02, ACTN-03, QUAL-03, QUAL-04, UX-03, UX-07]

coverage:
  - id: D1
    description: "Unread Only renders independent overlapping summaries and stable five-row chunks with bounded Load All behavior"
    requirement: QUAL-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#unreadShellIsFreshnessFirstEmptyAndLoadsStableFiveRowOverlappingChunks"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#unreadPagingEnforcesTheSharedOneThousandLoadAllBoundary"
        status: pass
    human_judgment: false
  - id: D2
    description: "Exact indexed-snapshot freshness copy precedes the overlap note and all category disclosures"
    requirement: QUAL-04
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#unreadShellIsFreshnessFirstEmptyAndLoadsStableFiveRowOverlappingChunks"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every loaded unread group exposes account-owned semantic/custom correction through the established Plan 09 route"
    requirement: UX-07
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#unreadShellIsFreshnessFirstEmptyAndLoadsStableFiveRowOverlappingChunks"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15ClassificationTest.java"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java"
        status: pass
    human_judgment: false

duration: 7 min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 05: Bounded Overlapping Unread Categories Summary

**Unread Only now opens freshness-honest overlapping Inbox categories in stable bounded chunks and reuses the account-owned correction contract**

## Performance

- **Duration:** 7 min
- **Started:** 2026-08-11T23:52:41Z
- **Completed:** 2026-08-11T23:59:46Z
- **Tasks:** 1
- **Files modified:** 8

## Accomplishments

- Replaced eager unread sender bodies with exact summary-only category shells and stable five-row category chunks.
- Preserved overlapping list, age, direct, and attachment membership with 5/+5/Load All behavior and a 1,000/1,001 guard.
- Placed the exact indexed-snapshot freshness statement and overlap note before every category.
- Reused Plan 09's strict account-owned semantic/custom correction endpoint without adding a second mutation route.

## Task Commits

1. **Task 15-05-01: Wire overlapping bounded Unread categories and correction** - `6bd5d08`

**Plan metadata:** committed separately after this summary was written.

## Files Created/Modified

- `UnreadCategoryGroup` and `UnreadPageModel` retain exact totals while initial row collections stay empty.
- `WorkspaceController` serves authorized unread category chunks and preserves canonical query state.
- `SenderCategoryService` applies shared five-row paging and bounded Load All validation.
- `unread.html` and `unread-results-frag.html` provide the quiet shell, freshness order, correction dialog, and chunk markup.
- Phase 10 and Phase 15 web tests now assert the authoritative Inbox-only, summary-first Unread contract.

## Decisions Made

- Empty initial bodies are a browser-delivery contract; category totals remain exact and server-owned.
- The existing category loader path recognizes an `unread-` key prefix so collapse/reopen behavior remains shared without modifying Plan 10's JavaScript file early.
- Direct action staging remains deferred to Plan 15-10; this plan only exposes bounded rows and correction.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The initial focused run exposed stale Phase 10 expectations for Selection Preview, export, eager unread rows, and label-less unread fixtures. Those assertions and fixtures were updated only where the Phase 15 authoritative contract supersedes them.
- A new web fixture initially used an account email different from the OAuth principal and correctly received HTTP 401. The fixture was corrected to exercise the owned-account path.

## User Setup Required

None - no external service configuration required.

## Verification

- Java 21.0.12 `mvn -Dtest='Phase15ClassificationTest,Phase15UnreadSemanticsTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest' test` - 29 passed, 0 failures, 0 errors.
- Java 21.0.12 `mvn -Dtest='*Unread*Test' test` - 15 passed, 0 failures, 0 errors.
- Java 21.0.12 `mvn -DskipTests compile` - passed.
- `git diff --cached --check` before task commit - passed.
- Task commit allowlist and post-commit deletion scan - passed; no deleted files.

## Next Phase Readiness

Ready for Plan 15-10 to add direct exact Unread staging and responsive reconciliation over these bounded category chunks. No Plan 15-05 blocker remains.

## Self-Check: PASSED

- Task commit `6bd5d08` exists and contains exactly the eight Plan 15-05 declared files.
- All required files exist and both plan and legacy Unread verification commands pass.
- Pre-existing dirty `STATE.md`, `ROADMAP.md`, `REQUIREMENTS.md`, and `config.json` were not modified or staged by this plan.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
