---
phase: 15-implement-pending-cleanup-and-navigation
plan: "04"
subsystem: persistence
tags: [postgresql, h2, classification, inbox, exact-materialization]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Account-local sender type overrides and corrected deterministic inference from Plan 15-03
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Bounded canonical sender query foundations from Plan 15-01
provides:
  - Effective account-local sender classification before risk, recommendation, and category membership
  - One dialect-aware Inbox-plus-unread base for rows, counts, groups, and materialization
  - Deduplicated exact unread materialization with 1,000/1,001 boundary evidence
affects: [phase-15-unread-ui, phase-15-staging, workspace-rail]

actuals:
  tokens: 28593
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - Inferred sender type is computed separately from account-local effective type
    - PostgreSQL ANY and H2 ARRAY_CONTAINS share one generated Inbox predicate
    - Duplicate unread locators normalize before exact provider-ID materialization

key-files:
  created:
    - src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java
  modified:
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/test/java/com/tidymail/Phase15ClassificationTest.java
    - src/test/java/com/tidymail/Phase15PostgresMigrationTest.java
    - src/test/java/com/tidymail/Phase10DecisionQueryTest.java
    - src/test/java/com/tidymail/Phase10SelectionMaterializationTest.java

key-decisions:
  - "Join semantic overrides after inference but before risk and recommendation, retaining the manual type as explicit query evidence."
  - "Detect the database dialect once in the repository and generate exactly one Inbox-membership predicate inside unread_base."
  - "Do not add an Inbox index without production-shaped PostgreSQL EXPLAIN evidence."

patterns-established:
  - "Effective classification: COALESCE(account override, corrected inference) is the only downstream sender type."
  - "Unread authority: every result and exact scope starts from unread=true plus Inbox label membership."

requirements-completed: [ACTN-03, QUAL-04, UX-07]

coverage:
  - id: D1
    description: "Account-local manual sender type overrides precede risk, recommendation, sender categories, and unread list/direct membership"
    requirement: UX-07
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15ClassificationTest.java#effectiveSqlOverrideWinsAfterMetadataUpdatesAndStaysAccountLocal"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java#manualTypeControlsOverlappingGroupsWithoutChangingInboxBase"
        status: pass
    human_judgment: false
  - id: D2
    description: "All unread rows, counts, categories, and PostgreSQL/H2 reads require both Inbox membership and unread state"
    requirement: QUAL-04
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java#everyUnreadReadUsesInboxAndUnreadTogether"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15PostgresMigrationTest.java#postgresV13ToV14PreservesCategoriesAndEnforcesOwnedOverrides"
        status: pass
    human_judgment: false
  - id: D3
    description: "Overlapping unread selections deduplicate exact provider IDs and preserve 1,000/1,001 scope boundaries"
    requirement: ACTN-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java#overlappingSenderSelectionsDeduplicateExactProviderIds"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java#inboxUnreadMaterializationPreservesThe1000And1001Boundary"
        status: pass
    human_judgment: false

duration: 12 min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 04: Effective Classification and Inbox-Unread Base Summary

**Account-local effective classification and one PostgreSQL/H2 Inbox-plus-unread base now govern browsing, grouping, counts, and exact materialization**

## Performance

- **Duration:** 12 min
- **Started:** 2026-08-11T23:27:00Z
- **Completed:** 2026-08-11T23:39:00Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments

- Applied account-owned sender type overrides after corrected inference and before risk, recommendation, sender category, and unread list/direct membership.
- Centralized Inbox-plus-unread semantics with PostgreSQL `ANY` and H2 `ARRAY_CONTAINS`, excluding read Inbox, archived unread, Spam unread, and Trash unread rows.
- Deduplicated overlapping unread locators before exact materialization and proved exact 1,000/1,001 message boundaries.

## Task Commits

1. **Task 15-04-01: Apply effective classification in shared SQL** - `094b1d5`
2. **Task 15-04-02: Centralize Inbox-only unread reads and exact materialization** - `389cf5c`

**Plan metadata:** committed separately after this summary was written.

## Files Created/Modified

- `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java` - Effective type CTE and shared dialect-aware unread base.
- `src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java` - Dialect routing, manual evidence mapping, and duplicate-locator normalization.
- `src/test/java/com/tidymail/Phase15ClassificationTest.java` - Override freshness, account isolation, and effective SQL parity.
- `src/test/java/com/tidymail/Phase15UnreadSemanticsTest.java` - Inbox negatives, overlap, correction, dedupe, and cap boundaries.
- `src/test/java/com/tidymail/Phase15PostgresMigrationTest.java` - PostgreSQL 16 effective unread execution.
- `src/test/java/com/tidymail/Phase10DecisionQueryTest.java` - Inbox-aware inherited query fixtures.
- `src/test/java/com/tidymail/Phase10SelectionMaterializationTest.java` - Inbox-aware inherited scope fixtures.

## Decisions Made

- Manual semantic type remains separate from custom category placement and is carried as explicit repository evidence.
- No Inbox index was added because this plan did not produce production-shaped PostgreSQL evidence proving index use.
- Existing service boundaries remain unchanged; query, count, grouping, and materialization converge in the repository CTE.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Testcontainers initially checked the unavailable default Docker socket. The required PostgreSQL suite passed after using the active Colima socket through `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`.
- One inherited 10,000-row fixture omitted Inbox labels and correctly returned zero under the new contract. The declared fixture was updated to represent Inbox mail.

## User Setup Required

None - no external service configuration required.

## Verification

- Java 21.0.12 task 15-04-01 suite: 22 tests passed on H2 and PostgreSQL 16.
- Java 21.0.12 task 15-04-02 suite: 29 tests passed on H2 and PostgreSQL 16.
- Java 21.0.12 final combined suite: 39 tests passed, 0 failures, 0 errors.
- `git diff --check 094b1d5^..389cf5c` passed.

## Next Phase Readiness

Effective classification and Inbox-only unread authority are ready for the bounded Unread UI and direct staging plans. No Plan 15-04 blockers remain.

## Self-Check: PASSED

- Both task commits contain only Plan 15-04 declared files.
- The required unread semantics artifact exists and all plan verification commands pass.
- Shared `STATE.md`, `ROADMAP.md`, and `REQUIREMENTS.md` were not modified or staged by this plan.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
