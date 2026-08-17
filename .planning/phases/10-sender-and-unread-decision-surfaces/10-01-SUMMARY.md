---
phase: 10-sender-and-unread-decision-surfaces
plan: "01"
subsystem: decision-query-foundation
tags: [flyway, postgres, h2, jdbc, deterministic-classification, pagination]
requires:
  - phase: 09-metadata-index-and-action-foundations
    provides: metadata-only message index, account ownership, and action queue facts
provides:
  - behaviorally equivalent PostgreSQL/H2 V7 normalized protection and audit schema
  - bounded canonical sender and unread URL query records
  - shared classified CTE repository with deterministic filters, metrics, sorts, and pages
  - pure V1 Java parity rules and provider-free query services
affects: [10-02, 10-03, phase-11, phase-12]
actuals:
  tokens: 31098
  tasks: 5
  commits: 0
tech-stack:
  added: []
  patterns:
    - fixed-fragment bound-parameter CTE classification
    - pure-Java validation of SQL classifications
    - canonical immutable URL query records
key-files:
  created:
    - src/main/resources/db/migration/V7__sender_unread_decision_surfaces.sql
    - src/test/resources/db/test-migration/V7__sender_unread_decision_surfaces.sql
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/service/DecisionRuleSet.java
    - src/test/java/com/tidymail/Phase10DecisionQueryTest.java
    - src/test/java/com/tidymail/Phase10PostgresMigrationTest.java
  modified:
    - src/test/java/com/tidymail/Phase3PersistenceTest.java
key-decisions:
  - "V1 classifications are computed before filtering and paging in shared-dialect SQL, then independently validated by pure Java."
  - "Protection comes only from normalized protected_senders rows; message labels are not consulted."
  - "Every query captures one UTC clock instant and uses fixed 25-row pages with complete database tie-breakers."
requirements-completed:
  - WORK-02
  - SEND-01
  - SEND-02
  - SEND-03
  - SEND-04
  - UNRD-01
  - UNRD-02
  - UNRD-03
  - UNRD-04
  - QUAL-03
coverage:
  - id: D1
    description: "V7 provides normalized protected sender state, privacy-bounded override audits, and portable query indexes."
    requirement: SEND-02
    verification:
      - kind: integration
        ref: "Phase10DecisionQueryTest#upgradePreservesRowsEnforcesPrivacyChecksCascadeAndIndexes"
        status: pass
      - kind: integration
        ref: "Phase10PostgresMigrationTest#productionV7UpgradeQueriesAndHibernateValidationMatchH2Contract"
        status: pass
    human_judgment: false
  - id: D2
    description: "Sender and unread query records reject malformed state and round-trip normalized canonical parameters."
    requirement: WORK-02
    verification:
      - kind: unit
        ref: "Phase10DecisionQueryTest#Canonical query and V1 rule contracts"
        status: pass
    human_judgment: false
  - id: D3
    description: "Shared sender and unread CTEs apply all filters and deterministic sorts before fixed pagination."
    requirement: SEND-03
    verification:
      - kind: integration
        ref: "Phase10DecisionQueryTest#senderAndUnreadQueriesClassifyFilterSortPageAndIsolateAccounts"
        status: pass
      - kind: integration
        ref: "Phase10PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "V1 classifications are conservative, explainable, provider-free, and stay below the warm two-second budget."
    requirement: QUAL-03
    verification:
      - kind: integration
        ref: "Phase10DecisionQueryTest#warmedTenThousandRowQueriesStayWithinBudget"
        status: pass
      - kind: integration
        ref: "JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test"
        status: pass
    human_judgment: false
duration: 8 min
completed: 2026-08-09
status: complete
---

# Phase 10 Plan 01: Sender And Unread Query Foundation Summary

**V7 normalized protection plus portable, deterministic sender/unread classification, filtering, sorting, metrics, and pagination**

## Performance

- **Duration:** 8 min
- **Started:** 2026-08-09T23:43:00Z
- **Completed:** 2026-08-09T23:51:00Z
- **Tasks:** 5
- **Files modified:** 18 implementation/test files

## Accomplishments

- Added equivalent PostgreSQL/H2 V7 protection and audit constraints, privacy allowlists, cascades, and composite metadata indexes.
- Added typed fail-closed URL state with stable canonical maps, fixed 25-row pages, and pagination reset/preservation helpers.
- Added one shared-dialect CTE pipeline for sender/unread facts, V1 classification, filters, metrics, and deterministic ordering.
- Added pure Java parity validation, visible reason copy, opaque row keys, and provider-free query services.
- Added H2 and PostgreSQL 16 upgrade, parity, privacy, provider-isolation, index-plan, and warm 10,000-row budget coverage.

## Task Commits

None. The user explicitly requested no commit.

## Verification

- Focused Phase 10 H2/PostgreSQL suite: passed with PostgreSQL 16.14 running through Colima.
- Phase 9 metadata/migration plus Phase 10 regression matrix: passed.
- Full `mvn -q test`: 107 tests, 0 failures, 0 errors, 0 skipped.
- Both H2 and PostgreSQL Hibernate `ddl-auto=validate`: passed.
- Warm 10,000-row sender and unread repository requests: each below two seconds.
- Provider mocks: zero interactions.
- `git diff --check`: passed.

All commands used `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. On this host that
selector resolves to installed Java 26.0.1.

## Deviations from Plan

### Auto-fixed Issues

**1. Baseline Flyway assertion still expected the previous migration version**
- **Found during:** Full Maven regression suite
- **Issue:** `Phase3PersistenceTest` asserted the latest migration was V6, so the required V7 migration made the otherwise healthy suite fail.
- **Fix:** Updated only its migration count/version/description expectation to V7.
- **Files modified:** `src/test/java/com/tidymail/Phase3PersistenceTest.java`
- **Verification:** Full 107-test suite passes.
- **Committed in:** Not committed by request.

**Total deviations:** 1 auto-fixed blocking test expectation.
**Impact:** No Phase 9 runtime behavior changed; the assertion now reflects the current schema.

## Issues Encountered

- PostgreSQL JDBC does not infer a type for raw `Instant` bound through named JDBC parameters. Shared query boundaries now bind portable `Timestamp` values while retaining one request-scoped `Instant` for Java parity.
- Testcontainers required the active Colima socket to be exported explicitly.

## Residual Risks

- Plan 10-01 exposes query facts only. Exact selection materialization, override writes, controllers, templates, and provider deep links remain intentionally assigned to Plans 10-02 and 10-03.
- Performance evidence covers the supported deterministic 10,000-row localhost dataset; larger or materially different production distributions may need additional plan analysis.
- The requested Java 21 selector resolves to Java 26.0.1 on this machine, so an actual Java 21 runtime was not exercised.

## User Setup Required

None.

## Next Phase Readiness

Plan 10-02 can build exact selection materialization and audited protected overrides on the shared query predicates and normalized V7 state.

## Self-Check: PASSED

---
*Phase: 10-sender-and-unread-decision-surfaces*
*Completed: 2026-08-09*
