---
phase: 15-implement-pending-cleanup-and-navigation
plan: "02"
subsystem: api
tags: [spring-mvc, transactions, exact-scopes, idempotency, review]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Bounded sender categories and canonical account-local sender row keys from Plan 15-01
provides:
  - Strict CSRF-protected direct cleanup staging endpoint
  - Atomic exact per-sender Review actions with immutable scope hashes and counts
  - Distinct-union cap, active-equivalent reuse, conflict, LIFO, and Delete acknowledgement enforcement
affects: [phase-15-sender-staging-ui, phase-15-unread-staging, review]

actuals:
  tokens: 14109
  tasks: 1
  commits: 2

tech-stack:
  added: []
  patterns:
    - One combined exact materialization is split into server-owned per-sender scopes only after the union cap passes
    - Account-keyed request serialization encloses one repeatable-read staging transaction

key-files:
  created:
    - src/main/java/com/tidymail/controller/CleanupActionController.java
    - src/main/java/com/tidymail/service/CleanupActionService.java
    - src/test/java/com/tidymail/Phase15StagingTest.java
  modified:
    - src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java

key-decisions:
  - "Materialize all canonical sender locators once, enforce the distinct provider-ID union cap, then partition the authorized IDs by normalized server metadata before any scope write."
  - "Serialize same-account staging requests around a repeatable-read transaction while retaining database account locks and active-equivalent action reuse."
  - "Require Delete acknowledgement at the service boundary and return Gmail's typical 30-day Trash recovery notice in the inline result."

patterns-established:
  - "Preflight-before-write: stale, foreign, protected-only, empty, and over-cap batches fail before immutable scopes or Review actions are persisted."
  - "Per-sender reconciliation: each result carries sender, action public ID, exact scope hash/count, and whether an active equivalent was reused."

requirements-completed: [ACTN-02, ACTN-03, ACTN-07, QUAL-04]

coverage:
  - id: D1
    description: "Direct single- and multi-sender requests atomically create or reuse one immutable exact staged Review action per canonical sender"
    requirement: ACTN-02
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java#directPostsPersistExactPerSenderScopesReuseActiveActionsAndReturnCanonicalRoute"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java#concurrentEquivalentPostsReuseOneAction"
        status: pass
    human_judgment: false
  - id: D2
    description: "Strict staging rejects smuggled authority, requires Delete acknowledgement, preserves canonical native fallback, and performs zero provider calls"
    requirement: ACTN-07
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java#strictBoundaryRejectsCsrfSmugglingDuplicatesAndDeleteWithoutAcknowledgement"
        status: pass
      - kind: integration
        ref: "mvn -Dtest='Phase15StagingTest,Phase10SelectionMaterializationTest,Phase12*Test' test"
        status: pass
    human_judgment: false
  - id: D3
    description: "Distinct-union 1,000/1,001 limits, mixed rollback, deterministic LIFO sequence, and incompatible action conflicts are persisted transactionally"
    requirement: ACTN-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java#unionCapAccepts1000AndRejects1001BeforeAnyScopeOrActionWrite"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java#canonicalCreationOrderIsLifoDeterministicAndIncompatibleActionsConflict"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15StagingTest.java#protectedStaleAndForeignSelectionsRollBackWithoutProviderInteraction"
        status: pass
    human_judgment: false

duration: 16min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 02: Exact Cleanup Action Staging Summary

**Strict direct cleanup posts now stage immutable per-sender Review actions with union-cap preflight, transactional reuse, and no provider mutation**

## Performance

- **Duration:** 16 min
- **Started:** 2026-08-11T22:46:00Z
- **Completed:** 2026-08-11T23:02:25Z
- **Tasks:** 1
- **Files modified:** 4

## Accomplishments

- Added a strict `/api/cleanup-actions` boundary that accepts only canonical sender query state, owned row keys, request token, action, Delete acknowledgement, and CSRF.
- Added one-transaction exact materialization, distinct-union cap enforcement, per-sender immutable scope creation, active-equivalent reuse, conflict recomputation, and deterministic Review sequencing.
- Added integration coverage for exact hashes/counts, duplicate and concurrent posts, mixed rollback, 1,000/1,001 boundaries, protected/stale/foreign rows, native fallback, Delete recovery copy, and zero provider interactions.

## Task Commits

1. **Task 15-02-01: Add strict per-sender exact staged commands** - `b204be3` (feat)

**Plan metadata:** committed separately after this summary was written.

## Files Created/Modified

- `src/main/java/com/tidymail/controller/CleanupActionController.java` - Strict parameter allowlist, owned account resolution, HTMX result, and canonical 303 fallback.
- `src/main/java/com/tidymail/service/CleanupActionService.java` - Serialized repeatable-read orchestration, staged command reconciliation, and authorized Review count.
- `src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java` - Combined exact preflight, union cap, server-side sender partitioning, immutable scopes, and signed confirmations.
- `src/test/java/com/tidymail/Phase15StagingTest.java` - Security, persistence, idempotency, conflict, cap, rollback, LIFO, fallback, and provider-isolation proof.

## Decisions Made

- A batch is materialized once through the existing exact query, then provider IDs are partitioned by normalized persisted sender metadata. This preserves one authoritative filter evaluation and permits a distinct-union cap before writes.
- Same-account posts are serialized in-process around the complete repeatable-read transaction, while database account locks remain the cross-process concurrency authority.
- Repeated request tokens do not create a second action because immutable scope identity plus active-equivalent action locking is the durable duplicate defense.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The first inherited run could not discover Colima through Testcontainers' default socket lookup. Re-running with `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` enabled the required PostgreSQL suite; all 74 tests passed.
- Focused development exposed that independent per-sender repository calls could return stale prepared-query results under H2. The final implementation uses one combined exact materialization and server-owned partitioning, which also better enforces the plan's union-before-write contract.

## User Setup Required

None - no external service configuration required.

## Verification

- Java 21 `mvn -Dtest='Phase15StagingTest' test` - 6 tests passed.
- Java 21 with Colima Testcontainers `mvn -Dtest='Phase15StagingTest,Phase10SelectionMaterializationTest,Phase12*Test' test` - 74 tests passed, 0 failures, 0 errors.
- `git diff --check` on all four declared task files - passed.
- Acceptance scan confirmed the strict endpoint, query-string rejection, Delete acknowledgement/recovery notice, repeatable-read transaction, active-equivalent reuse, exact counts, native 303 fallback, and provider-zero-interaction assertions.

## Next Phase Readiness

The exact sender staging boundary is ready for Plan 15-09's inline sender UI and later Plan 15-10 Unread Only integration. No Plan 15-02 blockers remain.

## Self-Check: PASSED

- Task commit `b204be3` exists and contains only the four plan-declared implementation/test files.
- `15-02-SUMMARY.md` exists and is the only file staged for the final documentation commit.
- Shared `STATE.md`, `ROADMAP.md`, and `REQUIREMENTS.md` were not modified or staged by this plan.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
