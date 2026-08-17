---
phase: 15-implement-pending-cleanup-and-navigation
plan: "06"
subsystem: ui
tags: [workspace-rail, account-switching, analytics, no-export, thymeleaf]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Account-local classification and Inbox-only unread semantics from Plans 02 and 04
provides:
  - Canonical five-route workspace rail with authorized badges and route-safe account switching
  - Email-only account options, `/senders` brand home, and reset-on-switch query behavior
  - Analytics and Analytics source pages with no authenticated export discoverability
affects: [phase-15-unread-integration, phase-15-unsubscribe-integration, workspace-regression]

actuals:
  tasks: 2
  commits: 3

key-files:
  modified:
    - src/main/java/com/tidymail/dto/WorkspacePageModel.java
    - src/main/java/com/tidymail/dto/WorkspaceRoute.java
    - src/main/java/com/tidymail/service/WorkspaceModelService.java
    - src/main/resources/templates/fragments/workspace-rail.html
    - src/main/resources/templates/analytics.html
    - src/main/resources/templates/analytics-source.html
    - src/test/java/com/tidymail/Phase14WorkspaceFidelityTest.java
    - src/test/java/com/tidymail/Phase13AnalyticsWebTest.java

key-decisions:
  - "Use the allowlisted WorkspaceRoute path as the account-switch form action and submit only the account value."
  - "Render queue and Inbox-unread badges only from authorized available facts; unavailable counts are omitted rather than fabricated."
  - "Remove authenticated Analytics export affordances while retaining undiscoverable backend export/privacy behavior and historical evidence."

requirements-completed: [EXPT-01, EXPT-02, EXPT-03, QUAL-03, UX-01, UX-05]
status: complete-with-deferred-integration
completed: 2026-08-11
---

# Phase 15 Plan 06: Workspace Rail and Analytics No-Export Summary

**The canonical workspace rail now owns navigation and account switching, while Analytics exposes no CSV, snapshot, download, or export affordance.**

## Accomplishments

- Standardized the five route labels and titles, linked the brand home to `/senders`, and removed provider suffixes and standalone account-switch copy.
- Added authorized Review and selected-account Inbox-unread badge semantics without foreign or fabricated counts.
- Preserved the current allowlisted route while resetting old account query, selection, modal, category, and pagination state.
- Removed Analytics and Analytics source export controls, copy, and wrappers while retaining backend export controllers, services, and privacy tests.
- Added negative Analytics template/render assertions for `.csv`, snapshot, download, and export discoverability.

## Task Commits

1. **Task 15-06-01: Finalize canonical rail, badges, and account switching** - `0950b8b`
2. **Task 15-06-02: Remove remaining authenticated export discoverability** - `19e9e70`

**Plan summary:** committed separately after all implementation and verification work.

## Declared Scope Audit

- `git diff --name-only 9f3cce6..19e9e70` matches all eight and only the files declared by Plan 15-06.
- The prior executor's post-task-1 changes were confined to the three declared task-2 files and were retained.
- No safely attributable prior-executor hunk existed in an undeclared file, so no undeclared or user change was reverted.
- Dirty shared tracking files `.planning/STATE.md`, `.planning/ROADMAP.md`, `.planning/REQUIREMENTS.md`, and `.planning/config.json` were not modified or staged.

## Verification

- Java 21.0.12 `Phase14WorkspaceFidelityTest`: **5 passed**, 0 failures, 0 errors, 0 skipped.
- Java 21.0.12 `Phase13AnalyticsWebTest`: **13 passed**, 0 failures, 0 errors, 0 skipped.
- Combined Plan command: **28 tests**, 1 deferred failure in `Phase13ExportTest`, 0 errors, 0 skipped.
- Full Java 21.0.12 suite with explicit Colima Docker: **385 tests**, 9 deferred failures, 0 errors, 0 skipped.
- Analytics forbidden-term scan: no `.csv`, export, download, or snapshot match in either declared Analytics template.
- Plan-owned post-commit status and `git diff --check`: passed.

## Deferred Integration

These are documented dependencies, not abandoned Plan 15-06 work:

- Plan 15-10 owns final Unread templates and assertions. Current stale failures cover old eager row/count, old sender label, old download, and old Inbox-unread fixture expectations in:
  - `src/test/java/com/tidymail/Phase13ExportTest.java`
  - `src/test/java/com/tidymail/Phase13IntegratedProductTest.java`
  - `src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java`
  - `src/test/java/com/tidymail/Phase14InteractionFidelityTest.java`
  - `src/test/java/com/tidymail/Phase10PostgresMigrationTest.java`
- Plan 15-11 owns remaining authenticated export controls in:
  - `src/main/resources/templates/unsubscribe.html`
  - `src/main/resources/templates/fragments/review-history-frag.html`
  - `src/test/java/com/tidymail/Phase14InteractionFidelityTest.java`
- Legacy shared-shell assertions in `src/test/java/com/tidymail/Phase9WorkspaceShellTest.java` still expect provider suffixes, standalone queue text, and old labels. They are undeclared by 15-06 and were left untouched.

## Deviations from Plan

- The implementation scope is complete. The full suite is not green until the explicitly downstream-owned stale templates/assertions are integrated.
- Backend export behavior remains intentionally retained and tested; only authenticated discoverability was removed in this plan.

## Self-Check: PASSED WITH DEFERRED INTEGRATION

- Commits `0950b8b` and `19e9e70` exist and contain exactly the eight declared implementation/test files.
- Both Plan 15-06-owned Java 21 suites pass.
- No Plan 15-10/15-11-owned file or shared tracking file was modified.
- All remaining failures and user-facing export controls are identified above with downstream ownership.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
