---
phase: 12-review-dual-execution-history-undo
plan: "01"
subsystem: action-execution-ledger
tags: [postgresql, h2, flyway, exact-scope, idempotency, leases, hmac, csrf]
requires:
  - phase: 09-metadata-index-and-action-foundations
    provides: immutable exact ActionScope persistence and canonical hashes
  - phase: 10-sender-and-unread-decision-surfaces
    provides: server-authoritative per-account selection scopes
  - phase: 11-safe-unsubscribe-and-protection
    provides: frozen V8 privacy and account-ownership contracts
provides:
  - paired additive V9 action, intent, execution, target, event, conflict, and command-token ledgers
  - pure compatibility, lifecycle, count, fingerprint, lease, and provider-result contracts
  - one durable write-before-I/O exact-scope archive executor
  - signed opaque staged/default and acknowledged immediate confirmation path
  - disabled numeric scope queue, blanket apply, and numeric undo mutation paths
affects: [12-02, 12-03, 12-04, 12-05, phase-13]
actuals:
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - reservation and dispatch marker commit before external provider I/O
    - completion uses a new lease-guarded transaction
    - scope ordinals carry SHA-256 fingerprints without duplicating provider IDs
key-files:
  created:
    - src/main/resources/db/migration/V9__action_execution_history_and_undo.sql
    - src/test/resources/db/test-migration/V9__action_execution_history_and_undo.sql
    - src/main/java/com/tidymail/service/ExactScopeExecutor.java
    - src/main/java/com/tidymail/service/ExecutionReservationService.java
    - src/main/java/com/tidymail/service/ExecutionCompletionService.java
    - src/main/java/com/tidymail/service/ActionCommandService.java
    - src/test/java/com/tidymail/Phase12ExecutionServiceTest.java
    - src/test/java/com/tidymail/Phase12PostgresMigrationTest.java
  modified:
    - src/main/java/com/tidymail/entity/UserAction.java
    - src/main/java/com/tidymail/controller/BulkActionApiController.java
    - src/main/java/com/tidymail/controller/ReviewActionsController.java
    - src/main/resources/templates/fragments/selection-preview-frag.html
key-decisions:
  - "ActionScope remains immutable; creation races serialize on owned account rows."
  - "Malformed provider results preserve valid known facts and classify contradictions or missing dispatched targets as verification-pending."
  - "Legacy direct mutation URLs return gone; active preview forms use only signed opaque confirmation."
requirements-completed: [SEND-06, UNRD-07, ACTN-01, ACTN-07]
coverage:
  - id: D1
    description: "Paired additive V9 preserves frozen V8 and exposes typed analytics-ready facts."
    requirement: ACTN-07
    verification:
      - kind: integration
        ref: "Phase12PostgresMigrationTest#ACTN_07_productionV1ToV9AndV8UpgradePreserveTruthAndEnforceLedger"
        status: pass
    human_judgment: false
  - id: D2
    description: "Staged and immediate archive share one exact-scope executor and ledger."
    requirement: ACTN-01
    verification:
      - kind: integration
        ref: "Phase12ExecutionServiceTest#ACTN_01_stagedAndImmediateArchiveUseOneExactExecutorAndLedger"
        status: pass
      - kind: integration
        ref: "Phase12ExecutionServiceTest#ACTN_01_concurrentImmediateReplayHasOneExecutionAndOneDispatch"
        status: pass
    human_judgment: false
  - id: D3
    description: "Provider partial, malformed, missing, and exception outcomes remain truthful."
    requirement: ACTN-07
    verification:
      - kind: unit
        ref: "Phase12ActionStateMachineTest#ACTN_07_providerResultsPreserveKnownFactsAndNeverBroadenSuccess"
        status: pass
      - kind: integration
        ref: "Phase12ExecutionServiceTest#ACTN_07_partialMissingAndMalformedProviderFactsRemainTruthful"
        status: pass
    human_judgment: false
  - id: D4
    description: "Confirmation defaults staged and immediate requires current explicit acknowledgement."
    requirement: ACTN-01
    verification:
      - kind: automated_ui
        ref: "Phase12ExecutionServiceTest#ACTN_01_confirmationPostDefaultsStagedAndBlocksModeBypassBeforeIo"
        status: pass
    human_judgment: false
duration: 1h 20m
completed: 2026-08-09
status: complete
---

# Phase 12 Plan 01: Action Execution Foundation Summary

**Paired V9 execution ledger, truthful exact-scope archive executor, and one signed staged/immediate confirmation path**

## Accomplishments

- Added behaviorally equivalent PostgreSQL/H2 V9 migrations and mappings for typed
  intents, scope ordinals, command tokens, executions, per-target facts, append-only
  events, and conflicts.
- Added deterministic compatibility/state/count/fingerprint/result contracts that
  never broaden provider success and never persist raw provider reasons.
- Added durable reservation, dispatch marker, transaction-free provider call, and
  lease-guarded completion transactions through one `ExactScopeExecutor`.
- Added HMAC-signed, expiring, one-time confirmation with staged default and explicit
  immediate acknowledgement. Active forms contain no scope ID, provider ID, trusted
  count, fingerprint, lease, or plaintext idempotency token.
- Removed active numeric scope queue, blanket Review apply, and numeric undo mutation
  semantics. Review cancellation remains CSRF-protected and provider-free.

## Verification

- Focused state/execution H2 suites: **8 tests, 0 failures, 0 errors, 0 skipped**.
- PostgreSQL 16.14 clean V1-V9 and actual V8-to-V9 upgrade: **1 test, 0 failures,
  0 errors, 0 skipped**, including Hibernate validation.
- Full Maven suite with Colima/Testcontainers: **188 tests, 0 failures, 0 errors,
  0 skipped**.
- Surefire XML nonzero failure/error/skip scan: passed.
- `git diff --check` and untracked-file trailing-whitespace scan: passed.
- Frozen V8 SHA-256 remained exact:
  - production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`

## Deviations From Plan

### Auto-Fixed Issues

1. H2 unnamed action checks required a table rebuild rather than direct constraint
   replacement; IDs, arrays, JSON, scope links, and legacy status were preserved.
2. Immutable `ActionScope` cannot accept a pessimistic entity lock. Creation now
   locks owned account rows in ascending order and then active actions, preserving
   scope immutability and deterministic serialization.
3. New scopes initially lacked normalized ordinal rows after migration backfill.
   Scope persistence now writes fingerprint-only target rows atomically.
4. Command-token result FKs initially blocked account cleanup. They now cascade with
   the account-owned result rows.
5. Inherited Phase 7/9/10 tests expected numeric queue/apply/undo bypasses. Only those
   superseded expectations were updated; exact hashing, ownership, privacy, cap, and
   provider-free preview assertions remain.

No V1-V8 migration, provider API, unsubscribe transport, real mailbox, or network
mutation was changed.

## User Setup Required

None.

## Next Phase Readiness

Plan 12-02 can add remaining sender/unread action handlers and Phase 11 unsubscribe
integration using the complete V9 schema and shared executor. Review grouping,
selected/all approval, history, and applied-ID-only undo remain in later Phase 12
plans.

---
*Phase: 12-review-dual-execution-history-undo*
*Plan: 01 complete*
