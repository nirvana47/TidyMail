---
phase: 15-implement-pending-cleanup-and-navigation
plan: "01"
subsystem: ui
tags: [spring-mvc, thymeleaf, htmx, jdbc, postgres, pagination]

requires:
  - phase: 14-ui-fidelity-and-route-recovery
    provides: Authenticated workspace shell and authoritative design alignment
provides:
  - Exact sender category summaries without row-body materialization
  - Stable five-row category paging with a bounded 1,000-row Load All path
  - Authorized HTMX/native sender category fragments and targeted 422 guidance
affects: [phase-15-sender-staging, phase-15-unread-only, workspace-navigation]

actuals:
  tokens: 28277
  tasks: 2
  commits: 5

tech-stack:
  added: []
  patterns:
    - Exact aggregate headers separated from bounded row-body queries
    - HTMX category chunks replace only paging controls while retaining loaded rows

key-files:
  created:
    - src/main/java/com/tidymail/dto/CategoryPage.java
    - src/test/java/com/tidymail/Phase15CategoryPagingTest.java
  modified:
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/service/SenderDecisionQueryService.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/fragments/senders-results-frag.html

key-decisions:
  - "Category state uses server-validated five-row offsets and canonical query reconstruction."
  - "Load All is available only after ten loaded rows and rejects exact totals above 1,000 before a row query."
  - "Initial sender pages combine exact summaries with at most five server-fetched rows per category for SSR/native compatibility."

patterns-established:
  - "Summary/page split: category headers use exact aggregate SQL while row endpoints apply category predicates before LIMIT/OFFSET."
  - "Bounded HTMX append: each increment replaces the prior paging controls with exactly five new rows and new controls."

requirements-completed: [QUAL-04, UX-03]

coverage:
  - id: D1
    description: "Exact sender category summaries and stable five-row query-level paging with 1,000/1,001 Load All boundaries"
    requirement: QUAL-04
    verification:
      - kind: integration
        ref: "mvn -Dtest='Phase15CategoryPagingTest,Phase10DecisionQueryTest' test"
        status: pass
      - kind: other
        ref: "PostgreSQL 16 EXPLAIN ANALYZE on 12,000-row bounded category fixture"
        status: pass
    human_judgment: false
  - id: D2
    description: "Authorized lazy sender fragments render only requested rows and return targeted HTTP 422 guidance"
    requirement: UX-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15CategoryPagingTest.java#senderPageRendersBoundedInitialRowsAndCategoryRouteReturnsFiveRowChunks"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15CategoryPagingTest.java#oversizedLoadAllReturnsTargetedGuidanceForHtmxAndNativeCallers"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 01: Bounded Sender Category Tracer Summary

**Exact sender summaries now include an authorized five-row initial page with stable increments and a separately bounded Load All contract**

## Performance

- **Duration:** 15 min
- **Started:** 2026-08-11T22:29:00Z
- **Completed:** 2026-08-11T22:44:10Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments

- Replaced sender-wide header materialization with exact category aggregate queries and deterministic volume/account/sender paging.
- Added server-owned category state for five-row opens, five-row increments, and explicit Load All at totals up to 1,000.
- Added an authorized category-body route and shared row fragment whose full-page render includes only each category's first five rows.

## Task Commits

1. **Task 15-01-01: Build exact category summaries and bounded page queries** - `eac1767` (feat)
2. **Task 15-01-02: Wire the bounded sender category fragment end to end** - `78bac6f` (feat)
3. **Declared dependency capture: SenderPageModel from the existing dirty worktree** - `ae8110b` (chore)
4. **Post-wave regression fix: Restore bounded initial sender rows for SSR/native compatibility** - `701ab62` (fix)

**Plan metadata:** committed separately after this summary was written.

## Files Created/Modified

- `src/main/java/com/tidymail/dto/CategoryPage.java` - Immutable category paging state and invariants.
- `src/main/java/com/tidymail/dto/SenderCategoryGroup.java` - Exact summary facts plus the bounded initial category page.
- `src/main/java/com/tidymail/dto/SenderPageModel.java` - Declared page-model dependency captured from the existing worktree.
- `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java` - Allowlisted shared sender category predicates.
- `src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java` - Exact summaries and bounded ordered row queries.
- `src/main/java/com/tidymail/service/SenderDecisionQueryService.java` - Category summary/page orchestration and Load All ceiling.
- `src/main/java/com/tidymail/controller/WorkspaceController.java` - Authorized category route, canonical URLs, and 422 HTMX targeting.
- `src/main/resources/templates/fragments/senders-results-frag.html` - Bounded initial bodies, reusable row chunks, and subtle load controls.
- `src/test/java/com/tidymail/Phase15CategoryPagingTest.java` - Query, boundary, authorization, response-body, and route coverage.

## Decisions Made

- Offset paging is restricted to non-negative multiples of five and rebuilt from the authorized canonical query.
- Totals above 1,000 fail before `senderCategoryRows` is invoked; the separate exact-message action cap is unchanged.
- The initial `/senders` request retains the empty legacy result DTO, but category groups carry at most five bounded rows so SSR/native HTML remains useful without a global row query.

## Deviations from Plan

- The post-wave integration gate clarified that compatible SSR/native full-page HTML must contain each category's first bounded rows. This supersedes the plan's literal empty-initial-body criterion without changing the five-row query bound, +5 paging, or all-row streaming prohibition.

## Issues Encountered

- The supplementary Testcontainers PostgreSQL suite could not access `/var/run/docker.sock`. The required Maven suite remained green, and PostgreSQL 16 plan inspection was completed directly through the running Compose database using a rollback-only 12,000-row fixture.
- Autosquash of the declared `SenderPageModel` dependency was blocked by unrelated tracked worktree changes. To preserve them, the file remains in a separate scoped chore commit.

## User Setup Required

None - no external service configuration required.

## Verification

- `mvn -Dtest='Phase15CategoryPagingTest' test` - 5 tests passed.
- `mvn -Dtest='Phase15CategoryPagingTest,Phase10DecisionQueryTest' test` - 17 tests passed.
- Java 21 `mvn -Dtest='Phase15CategoryPagingTest,Phase10SenderUnreadWebTest,Phase13IntegratedProductTest' test` - 29 tests passed, 0 failures, 0 errors.
- PostgreSQL 16 `EXPLAIN (ANALYZE, BUFFERS)` - account/type index scan, 25 kB top-N heapsort, 5 rows returned, 0.586 ms execution.
- Query-call assertions prove the initial page requests only five category rows, never invokes the sender-wide query, and a 1,001-row Load All rejection does not call `senderCategoryRows`.

## Post-Wave Regression Fix

- **Cause:** The initial full-page sender render replaced the legacy result page with an empty DTO and deferred every category row to a later HTMX toggle, leaving SSR/native HTML without its first bounded rows.
- **Resolution:** Each category now carries only its initial server-side page of at most five rows. The shared row fragment renders those rows in the full page, and subsequent `Load more` requests begin at offset five without restoring sender-wide streaming.
- **Commit:** `701ab62` (`fix(15-01): restore bounded rows in initial sender render`).

## Next Phase Readiness

The bounded sender tracer is ready for Plan 15-02 exact Review staging and later Unread Only reuse. No Plan 15-01 blockers remain.

## Self-Check: PASSED

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
