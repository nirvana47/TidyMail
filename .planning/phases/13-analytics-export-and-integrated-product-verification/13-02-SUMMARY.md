---
phase: 13-analytics-export-and-integrated-product-verification
plan: "02"
subsystem: analytics-ledger
tags: [java-21, postgresql-16, h2, flyway, jdbc, analytics, privacy]

requires:
  - phase: 13-analytics-export-and-integrated-product-verification
    provides: strict AnalyticsQuery, received predicate, availability, source, and CSV tracer
  - phase: 12-review-dual-execution-history-undo
    provides: immutable scopes, target outcomes, events, undo, and V8 unsubscribe linkage
provides:
  - Complete ledger-derived dashboard metrics with exact denominators and prior comparisons
  - Target-grain cleaned source predicates and honest per-source availability
  - Interval-first cleaned dedupe, real undo subtraction, and bounded grouped trends
  - Deterministic H2/PostgreSQL analytics SQL parity coverage
  - Additive paired V10 analytics indexes with V9 upgrade preservation
  - Fixed delay distribution, top senders, coverage, and outcome partition contracts
affects: [13-03, 13-04, 13-05, analytics, exports, snapshot]

actuals:
  tokens: 26824
  tasks: 3
  commits: 0

tech-stack:
  added: []
  patterns:
    - repository-owned CTEs aggregate each fact family at its intended grain
    - provider message IDs resolve only inside account-authorized SQL
    - denominator-zero ratios are unavailable rather than zero percent
    - PostgreSQL percentile_cont and H2 arithmetic share one eligible delay sample
    - cleaned aggregate, source, and trend require timestamped source-capable metadata

key-files:
  created:
    - src/main/resources/db/migration/V10__analytics_fact_indexes.sql
    - src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java
    - src/main/java/com/tidymail/service/AnalyticsMetricService.java
    - src/main/java/com/tidymail/dto/AnalyticsDashboard.java
    - src/test/java/com/tidymail/Phase13MetricDefinitionTest.java
    - src/test/java/com/tidymail/Phase13PostgresMigrationTest.java
  modified:
    - src/main/java/com/tidymail/service/AnalyticsService.java
    - src/main/java/com/tidymail/dto/AnalyticsMetric.java
    - src/main/java/com/tidymail/dto/AnalyticsTrendBucket.java
    - src/test/java/com/tidymail/Phase13AnalyticsRepositoryTest.java

key-decisions:
  - "V9 per-target terminal completed_at is the target-grain success fact; action_events remains action/execution-grain and is never misused as a per-target event."
  - "ACTION_QUEUED establishes first durable decisions; EXECUTION_RESERVED establishes requested target cohorts."
  - "Accepted remains an overlapping as-of fact; succeeded, failed, verification-pending, and not-dispatched remain the exact partition."
  - "V10 adds only three missing indexes because the existing target and metadata indexes already satisfy their planned shapes."

patterns-established:
  - "Fact isolation: received, decisions, target outcomes, unsubscribe events, and coverage use separate bounded pipelines."
  - "Source equality: cleaned aggregate, page, and stream reuse one cleaned_facts CTE."

requirements-completed:
  - ANLY-01
  - ANLY-02
  - ANLY-03
  - ANLY-04
  - ANLY-05
  - ANLY-06
  - ANLY-07
  - ANLY-08

coverage:
  - id: D1
    description: "Exact ledger headline metrics, as-of target outcomes, undo, and confirmed unsubscribe semantics"
    requirement: ANLY-01
    verification:
      - kind: integration
        ref: "Phase13AnalyticsRepositoryTest#ledgerFactsPreserveFirstDecisionGrossSuccessUndoAndConfirmedSemantics"
        status: pass
    human_judgment: false
  - id: D2
    description: "Exact ratios, denominator-zero availability, and fixed delay sample arithmetic"
    requirement: ANLY-07
    verification:
      - kind: unit
        ref: "Phase13MetricDefinitionTest (7 tests)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Additive V10 with frozen V9, clean/upgrade preservation, and PostgreSQL index shapes"
    requirement: ANLY-08
    verification:
      - kind: integration
        ref: "Phase13AnalyticsRepositoryTest and Phase13PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "Account-local top senders and exact cleaned aggregate/page/stream equality"
    requirement: ANLY-05
    verification:
      - kind: integration
        ref: "Phase13AnalyticsRepositoryTest#cleanedAggregateEqualsPageAndStreamAndTopSendersStayAccountLocal"
        status: pass
    human_judgment: false

duration: 23min
completed: 2026-08-10
status: complete
---

# Phase 13 Plan 02: Complete Ledger Analytics Summary

**Immutable V8/V9 facts now produce all required metrics, exact ratios, cleaned drilldowns, availability, sender ranking, and additive V10 indexes.**

## Performance

- **Duration:** 23 min
- **Started:** 2026-08-10T07:47:00Z
- **Completed:** 2026-08-10T08:10:00Z
- **Tasks:** 3
- **Files modified:** 22

## Accomplishments

- Added all six headline metrics, exact prior comparisons, as-of outcome partitions,
  three coverage ratios, reduction, cleanup rate, confidence retention, delay
  distribution, and account-local top senders.
- Added exact cleaned aggregate/page/stream predicates with no provider-ID projection
  and independent received, ledger, unsubscribe, delay, and legacy-gap availability.
- Added paired index-only V10 migrations and clean/upgrade evidence on H2 and
  PostgreSQL 16.14 while preserving V1-V9 contents and facts.
- Closed independent review CR-01 and WR-01 through WR-06: real `REVERTED`
  ARCHIVE/TRASH undo subtraction, independent trend availability, interval-first
  cleaned dedupe, explicit metadata exclusions, conservative legacy gaps, and one
  grouped cleaned trend query.
- Added a deterministic PostgreSQL 16 fixture that executes the production analytics
  SQL and proves aggregate/source parity, array resolution, as-of outcomes,
  unsubscribe confirmation, undo, median, grouped trends, and account isolation.

## Task Commits

No commits were created, as requested.

## Files Created/Modified

- `src/main/java/com/tidymail/repository/AnalyticsLedgerSql.java` - Shared authorized
  first-decision, resolved-target, and cleaned fact CTEs.
- `src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java` - Ledger,
  outcome, cohort, delay, trend, and exact cleaned source queries.
- `src/main/java/com/tidymail/repository/AnalyticsCoverageRepository.java` - Ledger
  boundaries, exact legacy/linkage gaps, and account-local sender ranking.
- `src/main/java/com/tidymail/service/AnalyticsMetricService.java` - Locked integer
  ratio, partition, reduction, confidence, median, and bucket arithmetic.
- `src/main/java/com/tidymail/service/AnalyticsService.java` - Complete dashboard and
  independent source availability composition.
- `src/main/resources/db/migration/V10__analytics_fact_indexes.sql` - Three missing
  account/fact-time indexes; no table or column changes.
- `src/test/java/com/tidymail/Phase13MetricDefinitionTest.java` - Exact denominator,
  partition, unavailable, over-100%, and boundary-bucket proof.
- `src/test/java/com/tidymail/Phase13AnalyticsRepositoryTest.java` - H2 facts,
  source equality, sender isolation, hash, and V9-to-V10 upgrade proof.
- `src/test/java/com/tidymail/Phase13PostgresMigrationTest.java` - PostgreSQL 16
  production migration and index-shape proof.

## Decisions Made

- Target `completed_at` is the only implemented V9 per-target terminal fact time.
  Action events provide durable decision/request times but do not identify target
  ordinals, so analytics does not fabricate a target event stream.
- Existing `idx_action_execution_targets_execution_status` and
  `idx_message_metadata_account_received` were retained instead of duplicated.
- The reviewed six-column Received source CSV remains unchanged. Cleaned source rows
  add fact time, action, and outcome only in cleaned mode.

## Deviations from Plan

### Auto-fixed Issues

**1. Missing PostgreSQL production migration evidence**
- Added `Phase13PostgresMigrationTest` beyond the originally listed test files.
- Verified V9-to-V10 fact preservation and exact index definitions on PostgreSQL 16.14.

**2. Existing migration tests assumed V9 was permanently latest**
- Updated only latest-version/count assertions in Phase 3, 11, and 12 tests.
- Frozen migration files and checksum assertions were not changed.

**3. Received CSV compatibility**
- An initial shared source schema added cleaned columns to Received CSV.
- Restored the reviewed six-column Received schema and made extra facts cleaned-only.

**Total deviations:** 3 auto-fixed correctness/verification issues.
**Impact on plan:** No feature or privacy scope expansion; changes close migration and
compatibility evidence required by V10.

## Issues Encountered

- H2 required an explicit `VARCHAR` cast for parameterized array fixtures.
- H2 composite scope ownership FKs required explicit action/scope cleanup order.
- Parallel Maven runs share `target/`; final evidence was rerun sequentially.

## Verification

- Focused query/metric/repository/web/undo/PostgreSQL suites: 52 tests passed.
- H2 clean V1-V10, V9-to-V10 upgrade, row preservation, and Hibernate validation:
  passed.
- PostgreSQL 16.14 V9-to-V10 migration/index and deterministic analytics SQL parity
  tests through Colima: passed.
- Full OpenJDK 21.0.12 suite: 271 tests, 0 failures, 0 errors, 0 skipped.
- Testcontainers used
  `DOCKER_HOST=unix:///Users/bytedance/.colima/default/docker.sock` with Ryuk disabled
  because Colima cannot mount that host socket path into the sidecar.
- `git diff --check`: passed.
- No Gmail/provider or unsubscribe transport is called by analytics GETs.

## User Setup Required

None.

## Next Phase Readiness

- Plan 13-03 can export the complete dashboard records and transfer `/analytics.csv`
  ownership without changing query or metric semantics.
- Plan 13-04 still owns complete visual presentation and opaque sender queue links.
- PostgreSQL representative-plan/performance proof and real localhost Gmail UAT
  remain assigned to Plans 13-05 and 13-06.

---
*Phase: 13-analytics-export-and-integrated-product-verification*
*Completed: 2026-08-10*
