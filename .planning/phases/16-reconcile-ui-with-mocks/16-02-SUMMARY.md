---
phase: 16-reconcile-ui-with-mocks
plan: "02"
subsystem: ui
tags: [thymeleaf, htmx, workspace, cleanup, categories, java21]

requires:
  - phase: 16-01
    provides: "Workspace production tracer cleanup and row-level staging feedback foundations."
provides:
  - "Mock-aligned cleanup category headers with split title and metric areas."
  - "Compact table-like sender and unread rows with accessible rounded cleanup actions."
  - "Category-management affordances and first-class account-owned user-generated categories."
affects: [workspace-ui, bulk-archive-delete, unread-only, sender-categories, review-staging]

actuals:
  tokens: 5200
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Custom sender categories render through SenderCategoryGroup.userGenerated=true rather than global learning rules."
    - "Cleanup categories keep exact row keys as staging scope while counts and metrics stay presentation-only."

key-files:
  created:
    - .planning/phases/16-reconcile-ui-with-mocks/16-02-SUMMARY.md
  modified:
    - src/main/java/com/tidymail/dto/SenderCategoryGroup.java
    - src/main/java/com/tidymail/service/SenderDecisionQueryService.java
    - src/main/resources/static/css/workspace.css
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/unread.html
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java

key-decisions:
  - "Display user-created sender categories as account-owned category rows with a visible User generated tag."
  - "Use Size pending for category totals when reliable category-level indexed size totals are unavailable."
  - "Rename correction affordances to Change type while preserving existing account-owned category service boundaries."

patterns-established:
  - "Category headers expose .category-title plus .category-meta so desktop metrics right-align and responsive wrapping avoids page overflow."
  - "Sender and unread fragments share the compact Select/Sender/Count/Size/Domains/Action row hierarchy."
  - "Category movement affordances open local dialogs and do not create a staging redirect or new page."

requirements-completed: [UX-09, UX-10, UX-11, UX-12, UX-13, UX-14, UX-15]

coverage:
  - id: D1
    description: "Bulk Archive/Delete and Unread Only category headers now use compact title/meta hierarchy with split sender, unread, and size metrics."
    requirement: UX-09
    verification:
      - kind: unit
        ref: "src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java#cleanupCategoryHeadersExposeSplitMetricsAndStayLazy"
        status: pass
      - kind: integration
        ref: "mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15CategoryPagingTest,Phase15ClassificationTest,Phase15StagingTest' test"
        status: pass
    human_judgment: false
  - id: D2
    description: "Sender and unread rows use compact table-like column order with rounded accessible action buttons."
    requirement: UX-10
    verification:
      - kind: unit
        ref: "src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java#cleanupRowsUseCompactMockColumnOrderAndRoundedActions"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "Category-management copy exposes Move selected, Create category, Change type, and User generated while removing visible Correct classification copy."
    requirement: UX-14
    verification:
      - kind: unit
        ref: "src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java#categoryManagementUsesMockCopyAndUserGeneratedTags"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#userGeneratedSenderCategoryRendersAsOwnedFirstClassCategory"
        status: pass
    human_judgment: false
  - id: D4
    description: "Exact row-key staging and account-owned category boundaries remain covered by existing Wave 1 regression suites."
    requirement: UX-11
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15ClassificationTest.java"
        status: pass
    human_judgment: false
  - id: D5
    description: "Visual fidelity against the authoritative design files still needs authenticated browser walkthrough at realistic data scale."
    requirement: UX-15
    verification:
      - kind: manual_procedural
        ref: "Manual localhost walkthrough against tidymail-ux-rehaul-draft/pages/build-notes.html and compact-suite mocks"
        status: unknown
    human_judgment: true
    rationale: "Automation verifies source structure and rendered behavior, but final mock fidelity requires human visual comparison."

duration: 45min
completed: 2026-08-12
status: complete
---

# Phase 16: Reconcile UI With Mocks Plan 02 Summary

**Cleanup category hierarchy now matches the compact mock structure while preserving exact-ID staging and account-owned category boundaries.**

## Performance

- **Duration:** 45 min
- **Started:** 2026-08-12T00:00:00-07:00
- **Completed:** 2026-08-12T00:12:00-07:00
- **Tasks:** 3
- **Files modified:** 10

## Accomplishments

- Restored compact cleanup category headers with split sender, unread, and size metrics for Bulk Archive/Delete and Unread Only.
- Reworked sender and unread rows into the mock-aligned table-like hierarchy with quiet controls and rounded action buttons.
- Added `Move selected`, `Create category`, `Change type`, and visible `User generated` category tags without adding export, redirect, or global-learning behavior.

## Task Commits

Each implementation task was committed atomically:

1. **Task 1: Split category metrics** - `1166e13` (feat)
2. **Task 2: Compact cleanup sender rows** - `f655a47` (feat)
3. **Task 3: User-generated cleanup categories** - `770aa18` (feat)

## Files Created/Modified

- `src/main/java/com/tidymail/dto/SenderCategoryGroup.java` - Added category size label formatting for header metrics.
- `src/main/java/com/tidymail/service/SenderDecisionQueryService.java` - Emits account-owned custom categories as first-class user-generated groups and supports custom category paging.
- `src/main/resources/static/css/workspace.css` - Tightened category containers, right-aligned split metrics, compact row cells, and rounded cleanup actions.
- `src/main/resources/templates/fragments/senders-results-frag.html` - Added compact sender category headers, table-like rows, category tools, and user-generated tags.
- `src/main/resources/templates/fragments/unread-results-frag.html` - Added compact unread group headers, rows, and category tools.
- `src/main/resources/templates/senders.html` - Updated category dialog and correction dialog copy to the mock-aligned wording.
- `src/main/resources/templates/unread.html` - Added unread category-management dialog and updated correction copy.
- `src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java` - Added source-level fidelity checks for headers, rows, controls, tags, and forbidden copy.
- `src/test/java/com/tidymail/Phase15WorkspaceWebTest.java` - Updated rendered web expectations and added account-bound user-generated category coverage.
- `src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java` - Updated existing workspace regression assertions for the new hierarchy and labels.

## Decisions Made

User-generated category groups are derived only from `SenderCategoryRepository` and `SenderCategoryAssignmentRepository` for authorized account IDs. No cross-user/global learning rule was introduced.

Category-level size remains `Size pending` for custom categories because a reliable category aggregate size is not available for every assigned sender. Row-level size remains unchanged.

## Deviations from Plan

### Auto-fixed Issues

**1. Missing size formatter assumption**
- **Found during:** Task 1
- **Issue:** The template initially referenced a nonexistent utility for category size labels.
- **Fix:** Added `SenderCategoryGroup.estimatedSizeLabel()` using the existing `EstimatedSizeLabel` DTO formatter.
- **Verification:** Task 1 and plan-level Maven suites passed.
- **Committed in:** `1166e13`

**2. Test fixture ownership mismatch**
- **Found during:** Task 3
- **Issue:** The new rendered category test created the owned account with an email that did not match the authenticated principal, causing a 401.
- **Fix:** Changed the fixture to use `OWNER_EMAIL` so the connected-account guard remained realistic.
- **Verification:** Task 3 and plan-level Maven suites passed.
- **Committed in:** `770aa18`

---

**Total deviations:** 2 auto-fixed issues
**Impact on plan:** Both fixes were needed to execute the planned behavior correctly; no scope was added.

## Issues Encountered

The repository does not include `./mvnw`, so all Maven commands used the requested Java 21 direct Maven invocation.

## Verification

Passed:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15CategoryPagingTest,Phase15ClassificationTest,Phase15StagingTest' test
```

Result: 45 tests run, 0 failures, 0 errors, 0 skipped.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 16-02 is ready for orchestrator review and the next Phase 16 plan. Remaining visual fidelity should be validated manually on localhost against the authoritative `.design` and Build Notes files.

---
*Phase: 16-reconcile-ui-with-mocks*
*Completed: 2026-08-12*
