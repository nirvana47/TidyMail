---
phase: 12-review-dual-execution-history-undo
plan: "03"
subsystem: grouped-review-approval
tags: [review, exact-scope, conflicts, batch-reservation, csrf, accessibility]
requires:
  - phase: 12-review-dual-execution-history-undo
    provides: reviewed V9 execution ledger and exhaustive exact-scope handlers
provides:
  - strict account-owned grouped Review queue with canonical filters and stable pages
  - exact provider-ID-free conflict detection and explicit non-mutating resolution
  - intent-bound cancellation, selected approval, eligible-all, and unsubscribe consent
  - atomic multi-account batch reservation through the shared ExactScopeExecutor
  - accessible responsive server-rendered Review controls and truthful outcomes
affects: [12-04, 12-05, phase-13]
actuals:
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - one authorized Review predicate feeds metrics, groups, rows, and approval sets
    - accounts lock ascending before actions lock ascending in one batch transaction
    - signed expiring operation intents bind owner, operation, and exact action set
key-files:
  created:
    - src/main/java/com/tidymail/repository/ReviewActionQueryRepository.java
    - src/main/java/com/tidymail/service/ActionConflictService.java
    - src/main/java/com/tidymail/service/ActionApprovalService.java
    - src/main/java/com/tidymail/service/ReviewOperationIntentService.java
    - src/main/resources/templates/fragments/review-queue-frag.html
    - src/test/java/com/tidymail/Phase12ReviewWebTest.java
  modified:
    - src/main/java/com/tidymail/service/ExactScopeExecutor.java
    - src/main/java/com/tidymail/service/ExecutionReservationService.java
    - src/main/java/com/tidymail/controller/ReviewActionsController.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/review.html
    - src/main/resources/static/css/workspace.css
key-decisions:
  - "Generic eligible-all excludes unsubscribe and reports FRESH_CONSENT_REQUIRED; each unsubscribe row has a dedicated fresh method-bound confirmation."
  - "Review operation intents are signed and stateless until mutation; durable execution idempotency remains in the existing V9 execution ledger."
  - "Conflict resolution cancels a complete action or keeps a matrix-compatible pair; immutable scopes and scope targets are never edited."
requirements-completed: [ACTN-04, ACTN-05, ACTN-06, ACTN-07]
coverage:
  - id: D1
    description: "Seven stable Review groups, account subgroups, metrics, filters, and pages use one authorized server predicate."
    requirement: ACTN-04
    verification:
      - kind: automated_ui
        ref: "Phase12ReviewWebTest#ACTN_04_groupedQueueUsesRealOwnedFactsAndPrivateAccessibleMarkup"
        status: pass
    human_judgment: false
  - id: D2
    description: "Cancellation, selected approval, eligible-all, and multi-account reservation are atomic and intent-bound."
    requirement: ACTN-05
    verification:
      - kind: integration
        ref: "Phase12ReviewWebTest#ACTN_05_selectedIsAtomicCancellationIsIntentBoundAndAllIncludesPageTwo"
        status: pass
      - kind: integration
        ref: "Phase12MultiAccountTest#ACTN_05_reviewSelectedReservesAcrossAccountsThroughOneBatchPath"
        status: pass
    human_judgment: false
  - id: D3
    description: "Account-local exact target intersections create provider-ID-free conflicts with explicit scope-preserving resolution."
    requirement: ACTN-06
    verification:
      - kind: integration
        ref: "Phase12ReviewWebTest#ACTN_06_exactConflictsAreAccountLocalAndResolutionNeverChangesScopes"
        status: pass
      - kind: unit
        ref: "Phase12ActionStateMachineTest#ACTN_01_compatibilityMatrixIsCompleteAndSymmetric"
        status: pass
    human_judgment: false
  - id: D4
    description: "Batch dispatch retains independent requested, succeeded, failed, and verification-pending outcomes through the shared executor."
    requirement: ACTN-07
    verification:
      - kind: integration
        ref: "Phase12ExecutionServiceTest"
        status: pass
      - kind: integration
        ref: "Full Java 21 Maven suite: 203 tests, zero failures/errors/skips"
        status: pass
    human_judgment: false
duration: 35m
completed: 2026-08-09
status: complete
---

# Phase 12 Plan 03: Grouped Review Approval Summary

**Server-authoritative grouped Review queue with exact conflicts, atomic selected/all approval, fresh unsubscribe consent, and one shared batch executor**

## Accomplishments

- Replaced the Review placeholder with seven stable action groups, owned account
  subgroups, four real metrics, strict canonical filters, fixed 25-row pages, and
  provider-free GET rendering.
- Added exact account-local conflict recomputation over scope target fingerprints.
  Conflict facts contain only canonical action pairs, bounded reason, overlap count,
  status, resolution, and timestamps.
- Added signed owner/operation/set-bound intents for cancellation, selected approval,
  eligible-all confirmation, and conflict resolution. Invalid, duplicate, foreign,
  stale, conflicted, or ineligible selected members reserve nothing.
- Replaced the former batch loop with one `reserveBatch` transaction. It locks
  accounts and actions ascending, validates the complete set, writes every execution,
  target, lease, and event, commits, then dispatches each reservation through the same
  handler and completion path used by immediate execution.
- Added server-counted eligible-all beyond the visible page, a configured hard action
  limit with no truncation, exact excluded reason counts, and stale-set rejection.
- Kept generic batch approval from treating an action ID as unsubscribe consent.
  Review now issues a fresh method/destination-bound confirmation for one staged
  unsubscribe action before the existing Phase 11 safety handler may run.
- Added semantic tables/cards, captions and headers, checkbox names with action,
  target, account and count, live/alert regions, focus targets, 980/720/375 layouts,
  44px narrow controls, reduced motion, and full non-JavaScript form behavior.

## Verification

- Focused Phase 12 state/review/multi-account/execution suites: passed.
- Inherited Phase 9 workspace and Phase 10/11 selection, web, unsubscribe, security,
  and execution suites: passed.
- PostgreSQL 16.14 clean and upgrade/parity suites for Phases 9-12: passed under
  OpenJDK 21.0.12 with Testcontainers and no skips.
- Full Maven suite: **203 tests, 0 failures, 0 errors, 0 skipped**.
- Surefire XML aggregate skip/failure/error gate: passed.
- `git diff --check`, new-file trailing-whitespace scan, controller dependency canary,
  and Review DOM authority/privacy canaries: passed.
- Frozen migration SHA-256 values remained exact:
  - V8 production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - V8 H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`
  - V9 production: `c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379`
  - V9 H2: `8f5d6ad52bf4105af15bd5e3ca0e4d8d5d45352dd9d5d70f93e29621c2a1909f`

## Deviations From Plan

### Auto-Fixed Issues

1. The inherited `ExactScopeExecutor.executeBatch` loop reserved one action at a time.
   It was replaced with one complete-set reservation transaction before dispatch.
2. Testcontainers initially targeted `/var/run/docker.sock` although Colima was
   healthy. Verification now pins Colima's socket and Homebrew OpenJDK 21 explicitly.
3. The first Review template condition evaluated absent fragment arguments during
   parsing. Conditional blocks now wrap fragment replacement and retain the shared
   workspace-state contract.
4. The initial queue correctly excluded unsubscribe from generic approval but lacked
   its dedicated fresh-consent UI. A separate CSRF confirmation now reloads ownership,
   scope, method, and destination fingerprints before issuing the one-time approval.
5. Legacy Phase 9 cancellation coverage submitted no operation intent. The inherited
   test now exercises the superseding CSRF plus server-intent contract.

No V8/V9 migration, immutable scope, provider API, unsubscribe transport, or direct
network behavior was changed.

## User Setup Required

None.

## Next Phase Readiness

Plan 12-04 can build History, undo, and compensation over complete Review control,
conflict, execution, target, and event facts. Plan 12-05 can add PostgreSQL concurrency
and privacy closure without introducing another forward reservation path.

---
*Phase: 12-review-dual-execution-history-undo*
*Plan: 03 complete*
