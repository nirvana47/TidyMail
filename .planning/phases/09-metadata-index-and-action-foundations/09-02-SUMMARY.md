---
phase: 09-metadata-index-and-action-foundations
plan: "02"
subsystem: action-scopes
tags: [jpa, sha-256, transactions, htmx, compatibility]
requires:
  - phase: 09-01
    provides: V6 action-scope schema and metadata-only query index
provides:
  - immutable account-owned ActionScope persistence
  - versioned length-prefixed canonical SHA-256 scope identity
  - typed bounded metadata-only persisted previews
  - two-step preview and explicit queue workflow
  - scoped apply and undo compatibility assertions
affects: [09-03, phase-10, phase-12]
actuals:
  tokens: 20000
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns: [requires-new-create-or-get, persisted-preview-source-of-truth, legacy-null-scope-compatibility]
key-files:
  created:
    - src/main/java/com/tidymail/entity/ActionScope.java
    - src/main/java/com/tidymail/service/ActionScopeHasher.java
    - src/main/java/com/tidymail/service/ActionScopeService.java
    - src/main/java/com/tidymail/service/ActionScopePersistenceService.java
    - src/main/resources/templates/fragments/action-scope-preview-frag.html
    - src/test/java/com/tidymail/Phase9ActionScopeTest.java
  modified:
    - src/main/java/com/tidymail/entity/UserAction.java
    - src/main/java/com/tidymail/service/UndoService.java
    - src/main/java/com/tidymail/controller/BulkActionApiController.java
    - src/main/java/com/tidymail/controller/ReviewActionsController.java
    - src/test/java/com/tidymail/Phase7BulkActionsTest.java
key-decisions:
  - Scope identity includes canonical version, account, action, target kind, and sorted deduplicated exact message IDs.
  - Unique-key recovery is attempted only for SQLSTATE 23505 or the named account/hash constraint after the REQUIRES_NEW insert returns.
  - New scoped queued actions have no undo expiry until the later execution lifecycle assigns one; legacy null-scope rows retain existing behavior.
patterns-established:
  - Persisted scope rows are the sole source for queue, apply, and undo IDs.
  - Scoped UserAction snapshots fail closed before provider mutation when account, action, IDs, or count differ.
requirements-completed: [ACTN-02, ACTN-03]
coverage:
  - id: D1
    description: Immutable canonical action-scope persistence and race-safe create-or-get
    requirement: ACTN-02
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase9ActionScopeTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: Persisted preview, explicit queue, and exact scoped apply/undo compatibility
    requirement: ACTN-03
    verification:
      - kind: integration
        ref: "mvn -q -Dtest=Phase7BulkActionsTest,Phase9ActionScopeTest test"
        status: pass
      - kind: integration
        ref: "mvn -q test"
        status: pass
    human_judgment: false
duration: 14min
completed: 2026-08-09
status: complete
---

# Phase 9 Plan 2: Action Scope Foundations Summary

**Immutable canonical action scopes now preserve one exact persisted ID set from preview through queue, apply, and undo.**

## Performance

- **Duration:** 14 min
- **Started:** 2026-08-09T15:38:00
- **Completed:** 2026-08-09T15:52:00
- **Tasks:** 4
- **Files modified:** 16

## Accomplishments

- Added immutable account-scoped persistence, typed bounded summaries, defensive copies, and deterministic `scope-v1` SHA-256 hashing.
- Added `REQUIRES_NEW` insert and post-transaction unique-race recovery with exact collision checks.
- Replaced direct sender queueing with metadata-index preview followed by scope-ID-only queue confirmation.
- Added scoped snapshot assertions before apply and undo while preserving legacy null-scope actions.
- Added hash, persistence, ownership, race, tampering, no-requery, fragment, and end-to-end exact-ID tests.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -Dtest=Phase7BulkActionsTest,Phase9ActionScopeTest test`: passed.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test`: 88 tests, 0 failures, 0 errors, 1 skipped.
- `git diff --check`: passed.
- No commits were created, as requested.

## Deviations from Plan

- `UserActionRepository` and `queued-action-frag.html` required no changes; their existing account-scoped lookup and queued confirmation contract already fit the implementation.

## Issues Encountered

- The host's Java 21 selector resolves to installed Java 26.0.1, matching the existing Plan 09-01 environment note.

## Residual Risks

- PostgreSQL Testcontainers coverage remains skipped because Docker/Colima is unavailable. H2 validates the composite foreign key and persistence behavior; SQLSTATE `23505` race recovery is covered with a transaction-boundary mock.
- Detailed execution outcomes and undo-window lifecycle remain intentionally owned by Phase 12.

## Next Phase Readiness

Plan 09-03 can build on stable metadata index and immutable action-scope foundations.
