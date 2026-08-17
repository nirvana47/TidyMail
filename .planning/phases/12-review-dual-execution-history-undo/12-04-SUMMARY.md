---
phase: 12-review-dual-execution-history-undo
plan: "04"
subsystem: action-history-undo
tags: [java21, spring-mvc, h2, history, recovery, undo, compensation]
requires:
  - phase: 12-01
    provides: V9 execution ledger and exact-scope executor
  - phase: 12-02
    provides: complete handler registry and source actions
  - phase: 12-03
    provides: grouped Review queue and conflict-safe approval
provides:
  - Truth-preserving expired-lease recovery and outcome projection
  - Strict provider-free History filters, metrics, rows, and timelines
  - Opaque applied-SUCCEEDED-ordinal-only undo execution
  - Honest unsubscribe guidance and linked remove-protection compensation
affects: [12-05, phase-13-analytics]
actuals:
  tokens: 7000
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - One bound History predicate feeds metrics, count, and page rows
    - Undo reserves its own operation before provider I/O
    - Compensation creates a linked action without mutating the original
key-files:
  created:
    - src/main/java/com/tidymail/repository/ActionHistoryRepository.java
    - src/main/java/com/tidymail/service/ActionHistoryService.java
    - src/main/java/com/tidymail/service/ExecutionRecoveryService.java
    - src/main/java/com/tidymail/service/CompensationService.java
    - src/test/java/com/tidymail/Phase12UndoCompensationTest.java
    - src/test/java/com/tidymail/Phase12PrivacyTest.java
  modified:
    - src/main/java/com/tidymail/service/UndoService.java
    - src/main/java/com/tidymail/controller/ReviewActionsController.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/review.html
key-decisions:
  - "Kept V8 and V9 migrations immutable; existing V9 already supports UNDO, COMPENSATION, and REMOVE_PROTECTION."
  - "Removed the numeric undo route because UUID and numeric path variants are ambiguous and numeric scope-wide undo is forbidden."
  - "Recovery reuses existing allowlisted event types while appending new recovery facts."
requirements-completed: [ACTN-07, ACTN-08, ACTN-09, ACTN-10]
coverage:
  - id: D1
    description: Truthful execution outcome and recovery behavior
    requirement: ACTN-07
    verification:
      - kind: integration
        ref: "mvn -Dtest=Phase12ExecutionServiceTest test"
        status: pass
    human_judgment: false
  - id: D2
    description: Strict provider-free History surface
    requirement: ACTN-10
    verification:
      - kind: integration
        ref: "mvn -Dtest=Phase12ReviewWebTest,Phase12PrivacyTest test"
        status: pass
    human_judgment: false
  - id: D3
    description: Applied-ordinal-only undo
    requirement: ACTN-08
    verification:
      - kind: integration
        ref: "mvn -Dtest=Phase12UndoCompensationTest test"
        status: pass
    human_judgment: false
  - id: D4
    description: Honest unsubscribe and protection compensation
    requirement: ACTN-09
    verification:
      - kind: integration
        ref: "mvn -Dtest=Phase12UndoCompensationTest,Phase12ReviewWebTest test"
        status: pass
    human_judgment: false
duration: 35 min
completed: 2026-08-09
status: complete
---

# Phase 12 Plan 04: History, Recovery, Undo, And Compensation Summary

**Durable provider-free History, truthful lease recovery, applied-target-only undo, and linked remove-protection compensation**

## Accomplishments

- Added strict History parsing and one authorized SQL pipeline for metrics, count,
  fixed 25-row pages, stable time/action ordering, safe search, legacy truth, bounded
  reasons, and append-only timelines.
- Extracted expired-lease recovery so stale lease owners cannot complete, reserved
  not-dispatched work receives a new lease, and dispatched unknown targets become
  verification-pending without retrying unsubscribe.
- Replaced whole-scope undo with an opaque-intent operation that selects only forward
  `SUCCEEDED` ordinals, verifies immutable scope fingerprints, reserves an independent
  undo ledger, and preserves partial/unknown inverse outcomes.
- Added safe external unsubscribe guidance and a new `REMOVE_PROTECTION` compensation
  action linked through `compensates_action_id`; the original action remains unchanged.
- Closed review findings W-01 through W-06, W-08, W-09, and I-01 with
  operation-aware undo recovery, undo-ledger History projection, pre-write
  compensation validation, bounded one-sided dates, summed safe reasons that exclude
  successful `NONE` sentinels, account-preserving forms, complete filter
  controls/linkage, and provider-derived account labels. W-07 remains Plan 12-05.

## Files Created/Modified

- Added History DTOs, repository, service, templates, outcome fragment, and CSS.
- Added `ExecutionRecoveryService` and delegated reservation recovery to it.
- Reworked `UndoService`, removed numeric undo routing, and added confirmation/result UI.
- Added `CompensationService`, explicit remove behavior in `ProtectionHandler`, MVC
  endpoints, and compensation UI.
- Added `Phase12UndoCompensationTest` and `Phase12PrivacyTest`; updated superseded
  Phase 7/9 numeric undo assertions.
- Broadened Plan 12-04 coverage for all inverse types, partial/unknown and expired
  undo outcomes, compensation no-write conflicts, CSRF/account propagation, History
  undo projection/reason aggregation, date bounds, filters, links, and provider labels.
- Excluded successful-target `NONE` sentinels from History reason aggregation and
  added a narrow mixed success/failure regression in `Phase12ReviewWebTest`.

## Verification

- PASS: OpenJDK `21.0.12` compile and test compilation.
- PASS: `Phase12ExecutionServiceTest,Phase12ReviewWebTest,Phase12UndoCompensationTest,Phase12PrivacyTest`.
- PASS: 31 focused Phase 12 tests after the W-09 regression.
- PASS: affected `Phase9ActionScopeTest`.
- PASS: `git diff --check`.
- PASS: frozen V8 SHA-256 values remain
  `190c0d9c...456461` and `fd9fd85c...39724d`.
- Full `mvn test` was attempted. The Docker-backed
  `Phase11PostgresMigrationTest` and `Phase12PostgresMigrationTest` could not start
  because no Docker environment was available.

## Residual Issues

- PostgreSQL/Testcontainers verification must be rerun where Docker is available.
- Plan 12-05 still owns PostgreSQL concurrent duplicate/race closure and exhaustive
  two-page equal-time/filter-permutation validation.

## Deviations from Plan

- No migration changes were made. Existing V9 proved sufficient, avoiding an
  unjustified additive correction and preserving migration immutability.
- No commits were created, per user instruction.

## Next Phase Readiness

Plan 12-05 can close PostgreSQL concurrency/parity and broaden the remaining undo and
History matrices without changing the core Plan 12-04 contracts.

---
*Phase: 12-review-dual-execution-history-undo*
*Completed: 2026-08-09*
