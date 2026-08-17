---
phase: 11-safe-unsubscribe-and-protection
plan: "03"
subsystem: unsubscribe-consent-and-outcomes
tags: [spring-mvc, csrf, hmac, jpa, h2, postgres, append-only-events]
requires:
  - phase: 11-safe-unsubscribe-and-protection
    plan: "01"
    provides: frozen V8 attempt/event/nonce schema and bounded candidate fingerprints
  - phase: 11-safe-unsubscribe-and-protection
    plan: "02"
    provides: owned candidate inspection and explicit method-specific alternatives
  - phase: 11-safe-unsubscribe-and-protection
    plan: "04"
    provides: early provider-neutral validated transport foundation
provides:
  - signed expiring one-time candidate/method/destination intents
  - account-owned privacy-bounded attempt, event, and nonce persistence mappings
  - hard protection and stale-fact revalidation before effects
  - committed PENDING before one transport call and append-only honest outcomes
  - explicit manual completion and user-confirmed attestation
  - CSRF-protected privacy-safe execution controllers and outcome timeline
affects: [11-04-integration, 11-05, phase-12]
actuals:
  tokens: 14000
  tasks: 4
  commits: 0
tech-stack:
  added: []
  patterns:
    - HMAC-signed canonical binary intent with persisted nonce hash and pessimistic consume lock
    - REQUIRES_NEW attempt/event transactions around non-transactional outbound I/O
    - ordered immutable outcome events plus bounded mutable latest-attempt summary
key-files:
  created:
    - src/main/java/com/tidymail/entity/UnsubscribeAttempt.java
    - src/main/java/com/tidymail/entity/UnsubscribeOutcomeEvent.java
    - src/main/java/com/tidymail/entity/UnsubscribeIntentNonce.java
    - src/main/java/com/tidymail/unsubscribe/UnsubscribeIntentService.java
    - src/main/java/com/tidymail/unsubscribe/UnsubscribeIntentNonceService.java
    - src/main/java/com/tidymail/service/UnsubscribeExecutionService.java
    - src/main/java/com/tidymail/service/UnsubscribeOutcomeService.java
    - src/main/java/com/tidymail/controller/UnsubscribeExecutionController.java
    - src/main/resources/templates/fragments/unsubscribe-outcome-frag.html
    - src/test/java/com/tidymail/Phase11UnsubscribeExecutionTest.java
  modified:
    - src/main/java/com/tidymail/service/UnsubscribeCandidateService.java
    - src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java
    - src/main/java/com/tidymail/controller/WorkspaceController.java
    - src/main/resources/templates/fragments/unsubscribe-inspection-frag.html
    - src/test/java/com/tidymail/Phase11UnsubscribeWebTest.java
key-decisions:
  - "Intent payloads use canonical length-prefixed binary encoding and HMAC-SHA256 domain separation; only SHA-256 nonce fingerprints are persisted."
  - "Intent consumption commits before attempt creation, and PENDING attempt/event rows commit before DNS resolution or transport invocation."
  - "Manual completion creates MANUAL_REQUIRED then VERIFICATION_PENDING; CONFIRMED is appended only for explicit USER_CONFIRMED attestation."
  - "Phase 10 protected-override audits are never consulted and grant no unsubscribe permission."
requirements-completed: [UNSB-03, UNSB-06, UNSB-08]
coverage:
  - id: D1
    description: "Frozen V8 attempt, event, and nonce tables are mapped with exact bounded allowlists and append-only event APIs."
    requirement: UNSB-08
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest#frozenSchemaAndEntitiesContainOnlyBoundedAllowlistedFacts"
        status: pass
      - kind: integration
        ref: "Phase11PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D2
    description: "Signed method-specific intents enforce ownership, expiry, stale facts, protection, tamper detection, and one-time concurrent consumption."
    requirement: UNSB-03
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest"
        status: pass
      - kind: unit
        ref: "Phase11UnsubscribeIntentTest#expiredAndTamperedIntentsFailBeforeFreshLookupOrNonceConsumption"
        status: pass
    human_judgment: false
  - id: D3
    description: "Automated execution commits PENDING before one injected transport call and preserves accepted, failed, and verification-pending truth."
    requirement: UNSB-08
    verification:
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest#pendingIsCommittedBeforeOneTransportCallAndAcceptedRemainsVerificationPending"
        status: pass
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest#concurrentDoubleSubmitConsumesOnceAndTimeoutIsNotRetried"
        status: pass
    human_judgment: false
  - id: D4
    description: "CSRF-protected automated and manual controllers render privacy-safe ordered outcomes with explicit confirmation sources."
    requirement: UNSB-06
    verification:
      - kind: automated_ui
        ref: "Phase11UnsubscribeWebTest#csrfExecutionRendersTruthfulTimelineAndProtectionRevalidationBlocks"
        status: pass
      - kind: integration
        ref: "Phase11UnsubscribeExecutionTest#manualCompletionIsExplicitAppendOnlyAndConfirmationNamesItsSource"
        status: pass
    human_judgment: false
duration: 30 min
completed: 2026-08-10
status: complete
---

# Phase 11 Plan 03: Consent, Execution, And Honest Outcomes Summary

**Signed one-time method consent, committed write-before-I/O attempts, hard protection
revalidation, and append-only automated/manual outcome timelines**

## Performance

- **Duration:** 30 min
- **Completed:** 2026-08-10T02:24:30Z
- **Tasks:** 4
- **Commits:** 0, as requested

## Accomplishments

- Mapped the frozen V8 attempt, outcome-event, and nonce tables without editing either
  migration, including exact enums, account ownership, bounded fingerprints, and
  immutable chronological event reads.
- Added five-minute canonical HMAC intents binding account, candidate, method,
  destination, issue/expiry times, and a random one-time nonce with atomic replay
  prevention.
- Added fresh ownership, metadata, destination, method, and protected-sender checks,
  then committed PENDING before DNS/transport and invoked the injected transport once.
- Added separate CSRF automated/manual POSTs and truthful PENDING, ACCEPTED, FAILED,
  MANUAL_REQUIRED, VERIFICATION_PENDING, and source-qualified CONFIRMED rendering.

## Verification

- Focused Phase 11 parser, candidate, security, intent, execution, and MVC suites:
  **38 tests, 0 failures, 0 errors, 0 skipped**.
- PostgreSQL 16.14 Testcontainers: **1 test, 0 failures, 0 errors, 0 skipped**,
  covering clean V1-V8, actual V7-to-V8, Hibernate validation, and query parity.
- Full Maven regression with the active Colima socket:
  **170 tests, 0 failures, 0 errors, 0 skipped**.
- `git diff --check`: passed.
- Frozen V8 SHA-256 remained exact:
  - production: `190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461`
  - H2: `fd9fd85cdb5c3abae103ec8b82f30e96f67ff332934b9d26666a51ea4839724d`

All Maven commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The host selector
still resolves to installed Java 26.0.1. PostgreSQL and the full suite used
`unix://$HOME/.colima/default/docker.sock`.

## Deviations From Plan

### Auto-fixed Issues

1. Thymeleaf automatically adds CSRF fields to `th:action` forms. An explicit field
   initially produced duplicate `_csrf` parameters that strict parsing would reject;
   the duplicate declaration was removed while retaining Spring Security CSRF.
2. Intent timestamps initially retained nanoseconds in persistence while the canonical
   signed form used epoch seconds. Issuance now truncates once to whole seconds before
   signing and storage, preventing false stale-intent failures.
3. Candidate latest-outcome timestamps initially reported attempt creation rather than
   completion. The existing query now returns `completed_at` when present.
4. The first PostgreSQL invocation found no Docker socket and therefore did not execute
   Testcontainers. It was rerun with the active Colima socket and completed with zero
   skips.
5. Burning an intent inside the same transaction that performed fresh candidate
   validation caused rollback-only behavior on a protected rejection. Nonce
   consumption now has one narrow pessimistic-lock transaction with explicit result
   states; fresh validation happens outside it, and typed rejection is raised only
   after the nonce commit. This also guarantees that removing protection requires a
   newly issued intent.

**Total deviations:** 5 auto-fixed correctness and verification issues.
**Impact:** All fixes strengthen the planned safety and evidence; no Phase 12 behavior
or schema change was introduced.

## Phase Boundaries

- No `ActionScope`, `UserAction`, Review routing, selection persistence, queue,
  staged/immediate mode, shared approval, or multi-candidate execution was added.
- Phase 10 override audits are intentionally ignored by unsubscribe execution.
- Browse, inspection, and opening manual alternatives still create no attempt/event.
- No full URI, query value, raw header, response body, provider ID, credential,
  exception text, or mailbox data is persisted or rendered in outcomes.

## User Setup Required

None.

## Next Phase Readiness

Plan 11-04 can use the completed lifecycle directly with its existing validated,
redirect-free transport. Plan 11-05 can verify integrated Phase 11 behavior without
introducing Phase 12 execution policy.

---
*Phase: 11-safe-unsubscribe-and-protection*
*Plan: 03*
