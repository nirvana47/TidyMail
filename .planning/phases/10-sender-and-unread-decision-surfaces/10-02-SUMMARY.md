---
phase: 10-sender-and-unread-decision-surfaces
plan: "02"
subsystem: exact-selection-previews
tags: [spring-mvc, jdbc, repeatable-read, action-scopes, htmx, postgres, h2]
requires:
  - phase: 10-01
    provides: canonical sender/unread queries and shared classified CTE predicates
  - phase: 09-02
    provides: immutable account-owned ActionScopes and canonical hashes
provides:
  - six server-authoritative sender/unread selection materialization modes
  - exact per-account cap enforcement with no truncation
  - protected exclusion and privacy-bounded audited override
  - atomic per-account preview scope creation with whole-transaction retry
  - preview-only full-page and HTMX responses with ID-free digests
  - fail-closed Phase 10 scope rejection at the legacy queue endpoint
affects: [10-03, phase-12]
actuals:
  tokens: 39431
  tasks: 5
  commits: 0
tech-stack:
  added: []
  patterns:
    - shared classified CTE plus mode-specific selected-target CTE
    - repeatable-read locked account transaction with REQUIRED scope inserts
    - ID-free aggregate preview boundary
key-files:
  created:
    - src/main/java/com/tidymail/service/SelectionMaterializationService.java
    - src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java
    - src/main/java/com/tidymail/controller/SelectionPreviewController.java
    - src/main/resources/templates/fragments/selection-preview-frag.html
    - src/test/java/com/tidymail/Phase10SelectionMaterializationTest.java
  modified:
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/service/ActionScopeService.java
    - src/main/java/com/tidymail/service/UndoService.java
    - src/test/java/com/tidymail/Phase10PostgresMigrationTest.java
key-decisions:
  - "Visible-page selection is derived once from global authorized ordering before IDs are partitioned into account-owned scopes."
  - "The 1,001-ID guard is partitioned per account and must agree with a separate exact distinct count before persistence."
  - "A unique scope race aborts the complete transaction and retries once through a nontransactional facade."
requirements-completed: [SEND-05, SEND-07, UNRD-05, QUAL-03]
coverage:
  - id: D1
    description: "All six sender/unread selection modes materialize exact server-authorized IDs."
    requirement: SEND-05
    verification:
      - kind: integration
        ref: "Phase10SelectionMaterializationTest#allSixModesMaterializeExactServerIdsAndRejectUntrustedKeys"
        status: pass
      - kind: integration
        ref: "Phase10PostgresMigrationTest#productionV7UpgradeQueriesAndHibernateValidationMatchH2Contract"
        status: pass
    human_judgment: false
  - id: D2
    description: "Per-account 1,000 cap, protected exclusion/override, and atomic account partitioning are enforced without truncation."
    requirement: SEND-07
    verification:
      - kind: integration
        ref: "Phase10SelectionMaterializationTest#cap, protection, rollback, and concurrency tests"
        status: pass
    human_judgment: false
  - id: D3
    description: "Preview-only full-page and HTMX responses expose hashes/counts but no provider IDs or execution controls."
    requirement: UNRD-05
    verification:
      - kind: integration
        ref: "Phase10SelectionMaterializationTest#MVC preview tests"
        status: pass
    human_judgment: false
  - id: D4
    description: "Phase 10 scope kinds cannot be submitted to the legacy queue endpoint."
    requirement: QUAL-03
    verification:
      - kind: integration
        ref: "Phase10SelectionMaterializationTest#storedScopesDoNotDriftAndNewKindsCannotReachLegacyQueue"
        status: pass
    human_judgment: false
duration: 26 min
completed: 2026-08-10
status: complete
---

# Phase 10 Plan 02: Exact Selection Preview Summary

**Server-authoritative sender/unread selections now create atomic, protected, per-account immutable previews without queueing or provider interaction.**

## Performance

- **Duration:** 26 min
- **Started:** 2026-08-09T23:48:00Z
- **Completed:** 2026-08-10T00:14:00Z
- **Tasks:** 5
- **Files changed:** 25 including this summary

## Accomplishments

- Added strict immutable request/preview contracts, shared opaque row-key decoding, and ID-free account scope digests.
- Reused the classified browse CTE for sender/unread individual, visible-page, and all-matching materialization.
- Enforced exact per-account counts and a partitioned 1,001-ID rejection guard; 1,000 succeeds and 1,001 returns HTTP 422 without writes.
- Added SQL-level protected exclusion, deterministic privacy-bounded fingerprints, immutable override audits, ascending account locks, repeatable-read atomic scope creation, rollback, and one whole-transaction race retry.
- Added authenticated CSRF-protected full-page/HTMX previews and rejected `SENDER_SELECTION`/`UNREAD_SELECTION` at the legacy queue endpoint.

## Task Commits

None. The user explicitly requested no commit.

## Verification

- Focused H2 action/materialization matrix:
  `Phase7BulkActionsTest,Phase9ActionScopeTest,Phase10SelectionMaterializationTest`
  passed: 41 tests, 0 failures, 0 errors, 0 skipped.
- PostgreSQL 16.14 production V1-V7 migration, Hibernate validation, browse queries,
  and all six materialization modes passed: 1 test.
- Full Maven suite with PostgreSQL Testcontainers passed:
  121 tests, 0 failures, 0 errors, 0 skipped.
- Provider mocks received zero interactions throughout preview tests.
- `git diff --check`: passed.

All commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. On this host the
selector resolves to installed Java 26.0.1.

## Files Created/Modified

- `SelectionPreviewController.java` - strict authenticated preview POST and response negotiation.
- `SelectionMode.java`, `SelectionSurface.java`, `SelectionRequest.java` - strict selection vocabulary.
- `SelectionScopePreview.java`, `SelectionScopeDigest.java` - immutable ID-free presentation boundary.
- `ProtectedOverrideAudit.java`, `ProtectedOverrideAuditRepository.java` - immutable V7 audit mapping.
- `DecisionSurfaceQueryRepository.java`, `DecisionSurfaceSql.java` - shared-CTE exact materialization and per-account guards.
- `UserAccountRepository.java` - deterministic ascending pessimistic account locking.
- `ActionScopeService.java`, `ActionScopePersistenceService.java` - multi-target validation and REQUIRED insert path.
- `DecisionRowKey.java`, `SenderDecisionQueryService.java`, `UnreadDecisionQueryService.java` - shared versioned row-key codec.
- `ProtectedOverrideAuditService.java` - versioned length-prefixed query fingerprints and server audit facts.
- `SelectionMaterializationService.java`, `SelectionPreviewTransactionService.java` - validation, retry, preflight, audit, and atomic scopes.
- `UndoService.java` - Phase 10 target-kind queue rejection.
- `selection-preview.html`, `selection-preview-frag.html` - full-page and single-root accessible previews.
- `Phase10SelectionMaterializationTest.java`, `Phase10PostgresMigrationTest.java` - exhaustive H2/MVC and PostgreSQL evidence.

## Deviations from Plan

### Auto-fixed Issues

1. Spring constructor selection required explicit `@Autowired` on services with test-only clock constructors.
2. Repository-thrown `IllegalArgumentException` was translated by JPA exception handling; stale individual selections now use a bounded HTTP 400 exception.
3. PostgreSQL parity coverage was expanded from migration/browse checks to all six materialization query modes.

No Phase 9 hash fixture, four-field target summary shape, provider API, mailbox mutation,
or planning-state file was changed.

## Residual Risks

- Manual browser judgment for 375px layout, focus movement after HTMX replacement,
  keyboard flow, and 200% zoom remains Phase 13 UAT.
- The Java 21 selector resolves to Java 26.0.1 on this machine; an actual Java 21
  runtime was not exercised.
- Atomic multi-account behavior is integration-tested directly. The existing account
  ownership schema currently permits only one row per email/provider pair, so a real
  multi-Gmail linked-account flow still depends on later ownership-model evolution.

## User Setup Required

None.

## Next Phase Readiness

Plan 10-03 can render real sender/unread pages that submit these strict preview
contracts. Phase 12 can deliberately adopt the new scope kinds for execution.

## Self-Check: PASSED

---
*Phase: 10-sender-and-unread-decision-surfaces*
*Completed: 2026-08-10*
