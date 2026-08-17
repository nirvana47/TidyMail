---
phase: 15-implement-pending-cleanup-and-navigation
plan: "03"
subsystem: persistence
tags: [postgresql, h2, flyway, classification, tenant-isolation]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Bounded account-local sender identities from Plan 15-01
provides:
  - Durable account-owned semantic sender type overrides with PostgreSQL/H2 parity
  - Normalized ownership-checked override lookup, upsert, and removal
  - Corrected deterministic sender classification with explainable manual precedence
affects: [phase-15-classification-sql, phase-15-sender-correction-ui, unread-only]

actuals:
  tasks: 2
  commits: 5

tech-stack:
  added: []
  patterns:
    - Account-leading normalized sender override access
    - Separate semantic type override and custom category assignment facts
    - Manual classification reason replaces inferred evidence when an override applies

key-files:
  created:
    - src/main/resources/db/migration/V14__sender_type_overrides_and_cleanup_indexes.sql
    - src/test/resources/db/test-migration/V14__sender_type_overrides_and_cleanup_indexes.sql
    - src/main/java/com/tidymail/entity/SenderTypeOverride.java
    - src/main/java/com/tidymail/repository/SenderTypeOverrideRepository.java
    - src/main/java/com/tidymail/service/SenderTypeOverrideService.java
    - src/test/java/com/tidymail/Phase15ClassificationTest.java
    - src/test/java/com/tidymail/Phase15PostgresMigrationTest.java
  modified:
    - src/main/java/com/tidymail/service/DecisionRuleSet.java
    - src/main/java/com/tidymail/service/DecisionClassifier.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java

key-decisions:
  - "Semantic sender type corrections are unique per account and normalized sender; they never modify custom category placement."
  - "Direct thread evidence wins over automated and return-path signals; return-path transactions outrank list-like markers only when bulk-automation evidence is absent."
  - "Manual overrides replace inferred type evidence with MANUAL_SENDER_TYPE_OVERRIDE while no-override calls retain the SQL parity guard."

requirements-completed: [QUAL-03, UX-03]

coverage:
  - id: D1
    description: "PostgreSQL 16 and H2 V14 schemas persist constrained account-local sender type overrides with cascade and Hibernate validation"
    requirement: QUAL-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15PostgresMigrationTest.java#postgresV13ToV14PreservesCategoriesAndEnforcesOwnedOverrides"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15ClassificationTest.java#v14H2SchemaValidatesAndSameSenderRemainsAccountLocal"
        status: pass
    human_judgment: false
  - id: D2
    description: "Ownership-checked normalized upsert and removal remain separate from custom category assignment"
    requirement: UX-03
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15ClassificationTest.java#semanticCorrectionDoesNotMutateCustomCategoryPlacement"
        status: pass
    human_judgment: false
  - id: D3
    description: "Four false-positive classification directions are corrected and manual precedence is distinguishable and reversible"
    requirement: QUAL-03
    verification:
      - kind: integration
        ref: "mvn -Dtest='Phase15ClassificationTest,Phase15PostgresMigrationTest' test"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15ClassificationTest.java#sqlAndJavaStayInParityForCorrectedFalsePositiveDirections"
        status: pass
    human_judgment: false

duration: 18 min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 03: Account-Local Classification Corrections Summary

**Account-owned sender type corrections now persist across PostgreSQL/H2 with deterministic, explainable inference beneath them**

## Performance

- **Duration:** 18 min
- **Started:** 2026-08-11T22:52:00Z
- **Completed:** 2026-08-11T23:09:47Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments

- Added paired V14 migrations and a JPA persistence contract for constrained, cascading, account-local sender type overrides.
- Added ownership-checked normalize, lookup, upsert, and removal operations without coupling semantic type to custom category placement.
- Corrected four deterministic false-positive directions and added an explicit `MANUAL_SENDER_TYPE_OVERRIDE` explanation whose removal reveals inference again.
- Restored SQL/Java classification parity for all four corrected directions and added repository-backed H2 coverage through the production parity guard.

## Task Commits

1. **Task 15-03-01: Add account-owned sender type override persistence** - `60b3940` (feat)
2. **Task 15-03-02: Correct deterministic rules and preserve explainability** - `c6f5bfe` (fix)
3. **Regression follow-up: Restore SQL/Java classification parity** - `8a8e246` (fix)

**Plan metadata:** initial and regression summaries committed separately.

## Files Created/Modified

- `src/main/resources/db/migration/V14__sender_type_overrides_and_cleanup_indexes.sql` - PostgreSQL V14 table, checks, cascade, uniqueness, and account-leading index.
- `src/test/resources/db/test-migration/V14__sender_type_overrides_and_cleanup_indexes.sql` - Behaviorally equivalent H2 V14 schema.
- `src/main/java/com/tidymail/entity/SenderTypeOverride.java` - Constrained semantic override entity and timestamps.
- `src/main/java/com/tidymail/repository/SenderTypeOverrideRepository.java` - Account-leading sender queries and removal.
- `src/main/java/com/tidymail/service/SenderTypeOverrideService.java` - Ownership, normalization, upsert, lookup, and removal boundary.
- `src/main/java/com/tidymail/service/DecisionRuleSet.java` - Corrected inference precedence and manual explanation.
- `src/main/java/com/tidymail/service/DecisionClassifier.java` - Effective manual type output while preserving no-override parity checks.
- `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java` - SQL sender classification precedence aligned branch-for-branch with Java inference.
- `src/test/java/com/tidymail/Phase15ClassificationTest.java` - H2 repository parity, isolation, category independence, false-positive, and explainability coverage.
- `src/test/java/com/tidymail/Phase15PostgresMigrationTest.java` - PostgreSQL 16 V13-to-V14, constraints, cascade, index, and Hibernate validation.

## Decisions Made

- Override storage accepts only `LIST`, `AUTOMATED`, `TRANSACTIONAL`, and `PERSONAL`; `UNKNOWN` remains an inference result, not user feedback.
- Every service operation validates `ownerKey + accountId` before using the normalized sender address.
- Custom category assignments survive semantic override changes and removal unchanged.

## Deviations from Plan

The original focused fixtures covered Java inference but not the existing SQL classifier. Full-suite regression exposed the stale SQL precedence, so repository-backed parity coverage was added within the declared Phase 15 classification test scope.

## Issues Encountered

`Phase13SnapshotPrivacyTest.privateCanariesStayOutOfEveryOutputAttributeHeaderErrorAndCapturedLog` initially errored because SQL retained the old `LIST -> AUTOMATED -> TRANSACTIONAL -> PERSONAL` precedence. Aligning the SQL CASE with `DecisionRuleSet` resolved the production guard failure without changing account-local override behavior.

## User Setup Required

None - no external service configuration required.

## Verification

- Java 21.0.12 `mvn -Dtest='Phase15ClassificationTest,Phase15PostgresMigrationTest' test` - 8 tests passed, 0 failures, 0 errors.
- PostgreSQL 16.14 Testcontainers - V13-to-V14 upgrade, constraints, cascade, index, and Hibernate validation passed.
- H2 2.3.232 - clean V1-to-V14 migration and Hibernate validation passed.
- `git diff --check 60b3940^..c6f5bfe` - passed.
- Java 21.0.12 `mvn -Dtest='Phase15ClassificationTest,Phase13SnapshotPrivacyTest,Phase10DecisionQueryTest' test` - 24 tests passed, 0 failures, 0 errors, 0 skipped.
- `git diff --check` before regression commit `8a8e246` - passed.

## Next Phase Readiness

Ready for Plan 15-04 to join effective overrides into SQL classification and Inbox-only unread semantics. Inferred SQL and Java classification now agree on the four corrected false-positive directions; no Plan 15-03 blockers remain.

## Self-Check: PASSED

- Both task commits and regression commit exist and contain only Plan 15-03 classification implementation/test files.
- All ten implementation/test files exist and the final plan verification is green.
- Shared `STATE.md`, `ROADMAP.md`, and `REQUIREMENTS.md` were not modified or staged by this plan.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
