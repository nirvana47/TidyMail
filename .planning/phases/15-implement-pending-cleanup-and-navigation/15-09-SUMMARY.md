---
phase: 15-implement-pending-cleanup-and-navigation
plan: "09"
subsystem: ui
tags: [thymeleaf, htmx, accessibility, staging, classification]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Bounded sender categories, exact staged cleanup commands, and account-local semantic corrections from Plans 15-01 through 15-03
provides:
  - Quiet Bulk Archive/Delete shell with direct exact staging and cross-category selection
  - Strict account-owned semantic/custom correction routing
  - Inline staged reconciliation, Delete acknowledgement, and responsive sender rows
affects: [phase-15-unread-ui, review, workspace-responsive]

actuals:
  tokens: 21338
  tasks: 1
  commits: 2

tech-stack:
  added: []
  patterns:
    - Canonical correction target prefixes keep semantic type and custom placement routing separate
    - Plan 02 JSON staging results reconcile visible HTMX rows without weakening server idempotency
    - Row account identity follows each correction control across all-account views

key-files:
  created:
    - src/main/resources/templates/fragments/cleanup-action-result-frag.html
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java
  modified:
    - src/main/java/com/tidymail/controller/SenderCategoryController.java
    - src/main/resources/templates/senders.html
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/static/js/cleanup-categories.js
    - src/main/resources/static/css/workspace.css

key-decisions:
  - "Correction forms submit canonical semantic:* or custom:* targets; display labels never select the persistence route."
  - "The existing Plan 02 cleanup endpoint remains the staging authority, while HTMX consumes its exact result for inline row and Review-count reconciliation."
  - "Delete uses a centered required acknowledgement whose copied form fields still pass the Plan 02 strict allowlist."

requirements-completed: [ACTN-02, ACTN-03, ACTN-07, QUAL-03, UX-03, UX-05, UX-07]

coverage:
  - id: D1
    description: "Bulk Archive/Delete removes preview/export chrome and directly stages exact single or cross-category sender selections"
    requirement: ACTN-02
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#senderMarkupUsesDirectExactStagingQuietControlsAndResponsiveReconciliation"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "Owned semantic type corrections and custom category placement route as separate account-local facts"
    requirement: UX-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#strictCorrectionRoutingKeepsSemanticAndCustomFactsOwnedAndSeparate"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#correctionRejectsForeignOwnershipSmugglingAndDisplayLabels"
        status: pass
    human_judgment: false
  - id: D3
    description: "Inline result, disabled-row reconciliation, Delete recovery copy, collapse reset, and narrow responsive controls are present"
    requirement: UX-05
    verification:
      - kind: automated_ui
        ref: "src/test/java/com/tidymail/Phase15WorkspaceWebTest.java#senderMarkupUsesDirectExactStagingQuietControlsAndResponsiveReconciliation"
        status: pass
    human_judgment: true
    rationale: "Source and MockMvc contracts pass, but final visual overflow and focus quality still require the Phase 15 authenticated browser matrix."

duration: 17 min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 09: Bulk Archive/Delete Interaction UI Summary

**Bulk Archive/Delete now routes owned corrections separately and stages exact sender actions inline with responsive disabled-state reconciliation**

## Performance

- **Duration:** 17 min
- **Started:** 2026-08-11T23:32:00Z
- **Completed:** 2026-08-11T23:49:00Z
- **Tasks:** 1
- **Files modified:** 7

## Accomplishments

- Replaced Selection Preview and export affordances with direct Plan 02 exact staging for row and cross-category selections.
- Added strict same-owner account/sender routing for semantic type overrides, custom category placement, and custom-placement removal.
- Added inline exact counts, Review-count reconciliation, success/reuse row disabling, centered Delete acknowledgement, collapse reset, and narrow stacked rows.

## Task Commits

1. **Task 15-09-01: Complete sender UI and inline staged reconciliation** - `c3beaf5` (feat)

**Plan metadata:** committed separately after this summary.

## Files Created/Modified

- `src/main/java/com/tidymail/controller/SenderCategoryController.java` - Strict canonical correction endpoint with owned semantic/custom routing.
- `src/main/resources/templates/senders.html` - Quiet controls, bulk staging, correction dialog, and Delete acknowledgement.
- `src/main/resources/templates/fragments/senders-results-frag.html` - Standard columns, every-row selection/correction, and direct exact forms.
- `src/main/resources/templates/fragments/cleanup-action-result-frag.html` - Inline result and Review-count OOB contract.
- `src/main/resources/static/js/cleanup-categories.js` - Dialog delegation, collapse reset, HTMX result reconciliation, and row disabling.
- `src/main/resources/static/css/workspace.css` - Six-column sender layout, staged state, 44px controls, and narrow card conversion.
- `src/test/java/com/tidymail/Phase15WorkspaceWebTest.java` - Static, MockMvc, tenant-isolation, smuggling, and fact-separation coverage.

## Decisions Made

- Account identity is copied from the selected sender row into the correction dialog so all-account views cannot accidentally route through the first account.
- Browser disabling is reconciliation only; active-equivalent reuse and transactional idempotency remain exclusively server-authoritative.
- The direct staging controller remains unchanged because Plan 02 already owns strict parsing, exact materialization, canonical fallback, and provider-zero-interaction behavior.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The complete prescribed legacy command ran but remains red in undeclared superseded tests. `Phase10SenderUnreadWebTest` still expects Selection Preview/current-action toolbar markup and seeds unread fixtures without Inbox labels; `Phase10PostgresMigrationTest` likewise expects 5,002 unread rows from label-less fixtures. These conflict with Plans 15-04 and 15-09 and were not edited outside the declared file list.
- Phase 14 legacy fidelity tests still require sender export and Selection Preview controls and old dialog JavaScript. Their failures are the expected proof that the superseded UI contract was removed.
- The first PostgreSQL run used Testcontainers' default socket. With the Colima socket configured, all four `Phase12PostgresMigrationTest` cases passed; the remaining Phase 10 PostgreSQL assertion is the stale Inbox-label fixture described above.

## User Setup Required

None - no external service configuration required.

## Verification

- Java 21 `mvn -Dtest='Phase15WorkspaceWebTest,Phase15StagingTest,Phase15CategoryPagingTest' test` - 14 passed.
- Java 21 compatible Phase 10/12 regression set - 76 passed.
- Java 21 with Colima PostgreSQL `Phase12PostgresMigrationTest` - 4 passed.
- Full requested legacy command - executed; 103 tests ran with four stale `Phase10SenderUnreadWebTest` failures and two initial Docker errors. Docker was corrected; the out-of-scope stale Phase 10/14 assertions remain documented above.
- `git diff --check` and forbidden sender-markup scans - passed.

## Next Phase Readiness

Plan 15-05 can reuse the strict correction route, and Plan 15-10 can reuse the inline staging/reconciliation behavior. No Plan 15-09 implementation blocker remains.

## Self-Check: PASSED

- Task commit `c3beaf5` exists and contains exactly the seven plan-declared implementation/test files.
- All created artifacts exist and the focused plan gate is green.
- Shared `STATE.md`, `ROADMAP.md`, and `REQUIREMENTS.md` were not modified or staged by this plan.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
