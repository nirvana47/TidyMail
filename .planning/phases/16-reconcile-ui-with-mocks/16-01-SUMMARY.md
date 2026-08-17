---
phase: 16-reconcile-ui-with-mocks
plan: "01"
subsystem: ui
tags: [thymeleaf, htmx, workspace, staging, junit]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: exact-ID cleanup staging, rail-only account scope, Inbox-only unread semantics
provides:
  - Text-only workspace rail labels without visible Unread Only or Review numeric badges
  - Exact Unread Only helper copy and removal of stale explanatory notes
  - Cleanup pages without the persistent Review-ready status panel
  - Row/button staging feedback with compact form-level live regions
  - Phase 16 structural tests for the tracer slice
affects: [workspace-ui, cleanup-staging, unread-only, review-discovery]

actuals:
  tokens: 5365
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Panel-independent cleanup staging feedback via row state plus form-local aria-live targets"
    - "Source/rendered-template fidelity tests for Phase 16 UI guardrails"

key-files:
  created:
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
  modified:
    - src/main/resources/templates/fragments/workspace-rail.html
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/unread.html
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/main/resources/static/js/cleanup-categories.js
    - src/main/resources/static/css/workspace.css
    - src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java

key-decisions:
  - "Kept server-side Unread and Review count facts, including accessible count context, while removing visible rail badges."
  - "Kept the legacy cleanup-action-result fragment on disk but removed it from cleanup page rendering; row/form feedback now owns cleanup staging UX."
  - "Used Maven directly with Java 21 because this checkout has no ./mvnw wrapper."

patterns-established:
  - "Cleanup staging success should mutate affected rows/buttons in place and announce through data-cleanup-feedback, not through a persistent page-level panel."
  - "Unread copy cleanup must be paired with existing service tests that prove Inbox label plus unread state remains unchanged."

requirements-completed: [UX-09, UX-10, UX-11, UX-12, UX-13, UX-14, UX-15]

coverage:
  - id: D1
    description: "Text-only rail labels for Unread Only and Review, with backend count facts retained."
    requirement: UX-09
    verification:
      - kind: unit
        ref: "mvn -Dtest='Phase16WorkspaceFidelityTest,Phase14WorkspaceFidelityTest' test"
        status: pass
    human_judgment: false
  - id: D2
    description: "Unread Only uses exact helper copy and no longer renders stale explanatory notes."
    requirement: UX-10
    verification:
      - kind: integration
        ref: "mvn -Dtest='Phase16WorkspaceFidelityTest,Phase10SenderUnreadWebTest,Phase15UnreadSemanticsTest' test"
        status: pass
    human_judgment: false
  - id: D3
    description: "Bulk Archive/Delete and Unread Only no longer render the persistent cleanup status panel; staging feedback is row/button-level with compact live feedback."
    requirement: UX-11
    verification:
      - kind: integration
        ref: "mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15StagingTest' test"
        status: pass
    human_judgment: false
  - id: D4
    description: "Exact-ID staging, ownership, duplicate reuse, delete acknowledgement, protected-sender handling, immutable hashes, and no provider mutation remain covered."
    requirement: UX-11
    verification:
      - kind: integration
        ref: "mvn -Dtest='Phase15StagingTest' test via plan combined command"
        status: pass
    human_judgment: false
  - id: D5
    description: "Visual density, category hierarchy, category-management copy, user-generated category tagging, and authenticated walkthrough closure remain for later Phase 16 plans."
    requirement: UX-12
    verification: []
    human_judgment: true
    rationale: "Plan 16-01 is the tracer slice; later Phase 16 plans own broader visual hierarchy and walkthrough closure."

duration: 55min
completed: 2026-08-12
status: complete
---

# Phase 16 Plan 01: Production Tracer Slice Summary

**Workspace cleanup pages now remove visible rail/status/Unread-copy clutter while preserving exact Review staging and safety guards.**

## Performance

- **Duration:** 55 min
- **Started:** 2026-08-12T06:04:00Z
- **Completed:** 2026-08-12T06:59:14Z
- **Tasks:** 3
- **Files modified:** 11

## Accomplishments

- Removed visible Unread Only and Review rail numeric badges while keeping `WorkspaceModelService` count facts and accessible count labels.
- Replaced Unread helper copy with exactly `Tidy up your unread emails` and removed both stale Unread explanatory notes.
- Removed the persistent cleanup status panel from Bulk Archive/Delete and Unread Only; staging now reconciles through row/button state and form-local live feedback.
- Added `Phase16WorkspaceFidelityTest` to lock the tracer UI contracts and kept staging/unread semantics regression suites green.

## Task Commits

1. **Task 1: Remove visible rail badges and lock exact navigation labels** - `b294e79` (fix)
2. **Task 2: Replace Unread helper copy and remove stale notes** - `353f992` (fix)
3. **Task 3: Remove persistent cleanup status panel and keep row/button staging feedback** - `dada7d1` (fix)

## Files Created/Modified

- `src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java` - New structural contract tests for Phase 16 tracer slice.
- `src/main/resources/templates/fragments/workspace-rail.html` - Removed visible rail badge spans.
- `src/main/resources/templates/unread.html` - Updated helper copy and removed status-panel include.
- `src/main/resources/templates/senders.html` - Removed status-panel include and added compact live feedback hooks.
- `src/main/resources/templates/fragments/unread-results-frag.html` - Removed stale notes and added row-form feedback hooks.
- `src/main/resources/templates/fragments/senders-results-frag.html` - Added row-form feedback hooks.
- `src/main/resources/static/js/cleanup-categories.js` - Made staging reconciliation independent of `#cleanup-action-status`.
- `src/main/resources/static/css/workspace.css` - Removed visible status-panel styling and added compact staging feedback styling.
- `src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java` - Inverted rail badge expectation.
- `src/test/java/com/tidymail/Phase15WorkspaceWebTest.java` - Updated cleanup and Unread page contracts.
- `src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java` - Updated old Unread/status copy assertions.

## Decisions Made

- Kept `cleanup-action-result-frag.html` available but no longer included it on cleanup pages; this avoids a user-facing panel while leaving any future fragment split explicit.
- Used direct Maven invocation under Java 21 because the repository contains `pom.xml` but no `./mvnw`.
- Left `.planning/STATE.md` and `.planning/config.json` untouched because they had pre-existing uncommitted changes and the orchestrator owns shared tracking.

## Deviations from Plan

None - implementation scope executed as planned.

## Issues Encountered

- The plan-specified `./mvnw` command could not run because `./mvnw` is absent from this checkout. Verification used `mvn` with the required Java 21 `JAVA_HOME` and `PATH` instead.
- Pre-existing uncommitted `.planning/STATE.md` and `.planning/config.json` changes were present before summary close-out; they were not modified or committed.

## User Setup Required

None - no external service configuration required.

## Verification

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase14WorkspaceFidelityTest' test` - pass, 6 tests.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase10SenderUnreadWebTest,Phase15UnreadSemanticsTest' test` - pass, 17 tests.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15StagingTest' test` - pass, 16 tests.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase16WorkspaceFidelityTest,Phase14WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest' test` - pass, 36 tests.

## Next Phase Readiness

Plan 16-01 is complete. Later Phase 16 work can build on the panel-independent row feedback path for broader category hierarchy, density, user-generated category tags, and authenticated walkthrough closure.

## Self-Check: PASSED

- Production task commits exist for all three tasks.
- Summary file exists at `.planning/phases/16-reconcile-ui-with-mocks/16-01-SUMMARY.md`.
- Plan-level verification passed.
- `STATE.md` and `ROADMAP.md` were not edited by this executor.

---
*Phase: 16-reconcile-ui-with-mocks*
*Completed: 2026-08-12*
