---
phase: 12-review-dual-execution-history-undo
plan: "02"
subsystem: exact-scope-action-handlers
tags: [exact-scope, staged, immediate, unsubscribe, protection, multi-account, htmx]
requires:
  - phase: 12-review-dual-execution-history-undo
    provides: reviewed V9 execution ledger and exact-scope archive tracer
  - phase: 10-sender-and-unread-decision-surfaces
    provides: server-authoritative selection materialization and protection rules
  - phase: 11-safe-unsubscribe-and-protection
    provides: V8 intent, SSRF, transport, attempt, and outcome contracts
provides:
  - exhaustive shared handlers for all sender and unread decisions
  - provider-free KEEP, REVIEW, and PROTECT execution through the V9 ledger
  - fresh Phase 11 unsubscribe intent execution with V8-to-V9 target linkage
  - separate per-account scopes, actions, executions, targets, and receipts
  - staged-default and acknowledged-immediate source confirmation UI
affects: [12-03, 12-04, 12-05, phase-13]
actuals:
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - exhaustive typed handler registry behind one exact-scope executor
    - provider-free local outcomes use the same reservation and completion ledger
    - unsubscribe issues and consumes a fresh V8 intent only at execution time
    - presentation batch identifiers never become cross-account execution authority
key-files:
  created:
    - src/main/java/com/tidymail/service/LocalDecisionHandler.java
    - src/main/java/com/tidymail/service/ProtectionHandler.java
    - src/main/java/com/tidymail/service/UnsubscribeActionHandler.java
    - src/test/java/com/tidymail/Phase12MultiAccountTest.java
  modified:
    - src/main/java/com/tidymail/service/ExactScopeExecutor.java
    - src/main/java/com/tidymail/service/ActionCommandService.java
    - src/main/java/com/tidymail/service/ActionPreflightService.java
    - src/main/java/com/tidymail/service/MailboxMutationHandler.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/fragments/selection-preview-frag.html
    - src/main/resources/templates/fragments/unsubscribe-inspection-frag.html
    - src/test/java/com/tidymail/Phase12ExecutionServiceTest.java
key-decisions:
  - "All action kinds dispatch through the reviewed ExactScopeExecutor public API."
  - "REVIEW records local target facts immediately and remains BLOCKED in both policies."
  - "Unsubscribe HTTP acceptance is verification-pending and is never reported as success."
  - "PROTECT reloads one unambiguous sender and treats an existing identical protection as idempotent."
requirements-completed: [SEND-06, UNRD-07, UNSB-07, ACTN-01, ACTN-07]
coverage:
  - id: D1
    description: "Every sender and unread action uses one staged/immediate command and executor path."
    requirement: ACTN-01
    verification:
      - kind: integration
        ref: "Phase12ExecutionServiceTest#SEND_06_and_UNRD_07_allMailboxAndLocalActionsUseTheSharedLedger"
        status: pass
    human_judgment: false
  - id: D2
    description: "Unsubscribe preserves Phase 11 intent and truthful pending outcome semantics."
    requirement: UNSB-07
    verification:
      - kind: integration
        ref: "Phase12ExecutionServiceTest#UNSB_07_immediateUsesFreshPhase11SafetyAndLinksAcceptedPendingAttempt"
        status: pass
    human_judgment: false
  - id: D3
    description: "Multi-account commands create independent account-owned execution ledgers."
    requirement: ACTN-07
    verification:
      - kind: integration
        ref: "Phase12MultiAccountTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "Source forms expose no provider identifiers, destinations, counts, or result authority."
    requirement: ACTN-01
    verification:
      - kind: automated_ui
        ref: "Phase12ExecutionServiceTest#sourceControllersAndFormsExposeNoProviderOrTransportAuthority"
        status: pass
    human_judgment: false
duration: 2h
completed: 2026-08-09
status: complete
---

# Phase 12 Plan 02: Complete Action Handler Summary

**Every sender, unread, local, protection, and unsubscribe decision now uses the
reviewed exact-scope execution ledger in staged and immediate modes**

## Accomplishments

- Expanded the fail-closed handler registry to archive, trash, mark read, keep,
  protect, review, and unsubscribe without adding another forward executor.
- Added typed intent and final preflight validation for message, sender, local-review,
  and unsubscribe-candidate scopes. Protection requires one current normalized sender.
- Kept KEEP and REVIEW provider-free while recording durable per-target facts. REVIEW
  executes its local decision immediately and remains `BLOCKED`; PROTECT performs an
  account-owned idempotent local write.
- Adapted Phase 11 unsubscribe without duplicating parsing, SSRF, DNS, transport, or
  outcome logic. A fresh method-bound V8 intent is issued and consumed only when the
  V9 action executes, and its attempt is linked to the V9 target.
- Split all-account submissions into deterministic, independent per-account actions,
  scopes, executions, targets, and receipts. A shared batch UUID is presentation only.
- Added staged-default/immediate-acknowledgement controls, safe summaries, shortened
  comparison hashes, CSRF fields, narrow 44px targets, and responsive mode layout.

## Verification

- Required focused Phase 10/11/12 matrix: passed.
- PostgreSQL 16.14 Phase 11/12 migration and Hibernate parity suites: passed.
- Full Maven suite on OpenJDK 21.0.12 with Colima/Testcontainers: **198 tests,
  0 failures, 0 errors, 0 skipped**.
- Surefire XML nonzero failure/error/skip scan: passed.
- `git diff --check`, controller/provider dependency canary, and template authority
  canaries: passed.
- Frozen migration SHA-256 values remained exact:
  - V8 production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - V8 H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`
  - V9 production: `c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379`
  - V9 H2: `8f5d6ad52bf4105af15bd5e3ca0e4d8d5d45352dd9d5d70f93e29621c2a1909f`

## Deviations From Plan

### Auto-Fixed Issues

1. Repeated PROTECT approval initially failed stale-protection preflight after the
   first successful local write. Preflight now accepts the exact idempotent
   unprotected-to-fully-protected transition only for PROTECT.
2. Automated unsubscribe inspection previously issued a reusable queue-time V8
   intent. It now creates a typed V9 candidate action and issues the fresh V8 intent
   inside final execution, so staged submission performs no transport or nonce use.
3. Frozen V9 does not allow `NEEDS_DECISION` as a reason code. REVIEW is represented
   without changing V9 by `ActionStatus.BLOCKED` plus typed subject
   `LOCAL_REVIEW`; its local target fact remains successful.
4. Inherited Phase 11 web assertions expected the superseded direct automated form.
   Only those boundary assertions changed; direct Phase 11 execution, CSRF, privacy,
   SSRF, transport, and truthful-outcome coverage remains active.

No V8/V9 migration, provider API, unsubscribe transport, or network safety rule was
changed.

## User Setup Required

None.

## Next Phase Readiness

Plan 12-03 can build Review grouping and approval controls on complete per-action,
per-account ledgers. Unsubscribe rows remain individually confirmable, and REVIEW
rows are durable non-executable decisions.

---
*Phase: 12-review-dual-execution-history-undo*
*Plan: 02 complete*
