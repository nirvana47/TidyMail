---
phase: 13-analytics-export-and-integrated-product-verification
plan: "05"
subsystem: production-database-verification
tags: [java-21, postgres-16, testcontainers, explain, performance, privacy]

requires:
  - phase: 13-analytics-export-and-integrated-product-verification
    provides: analytics facts, exact source predicates, exports, snapshot, and UI routes
  - phase: 12-review-dual-execution-history-undo
    provides: immutable target/event ledger, History, undo, and PostgreSQL harnesses
provides:
  - Frozen production and H2 V1-V10 hashes with clean and V9-to-V10 PostgreSQL proof
  - Direct H2/PostgreSQL aggregate, source, outcome, ordering, delay, and bucket parity
  - Deterministic 10,503-row, 401-date PostgreSQL performance fixture
  - JSON EXPLAIN ANALYZE BUFFERS account/time index proof and product timing gates
  - Incremental 2k/20k/50k CSV streaming/heap proof and exhaustive output/log/CSRF matrices
affects: [13-06, postgres, analytics, exports, privacy, regression]

actuals:
  tokens: 12200
  tasks: 3
  commits: 0

tech-stack:
  added: []
  patterns:
    - production dialect tests assert the exact Testcontainers JDBC URL and never skip Docker absence
    - correctness and boundary identities are asserted before timing and EXPLAIN gates
    - output privacy combines exact structural allowlists with recursive canary denial
    - actual repository SQL and representative bindings are captured before natural-planner JSON EXPLAIN

key-files:
  created:
    - src/test/java/com/tidymail/Phase13PerformanceTest.java
    - src/main/java/com/tidymail/controller/SafeDataAccessErrorHandler.java
  modified:
    - src/test/java/com/tidymail/Phase13PostgresMigrationTest.java
    - src/test/java/com/tidymail/Phase13ExportTest.java
    - src/test/java/com/tidymail/Phase13SnapshotPrivacyTest.java
    - src/test/java/com/tidymail/Phase13AnalyticsWebTest.java
    - src/test/java/com/tidymail/Phase12ReviewWebTest.java
    - src/test/java/com/tidymail/Phase9PostgresMigrationTest.java
    - src/test/java/com/tidymail/Phase10PostgresMigrationTest.java

key-decisions:
  - "Capture exact repository SQL/bindings and inspect natural PostgreSQL JSON plans; permit a tiny bounded sequential scan only when its predicates are asserted."
  - "Treat visible sender and subject fields according to each surface allowlist while denying provider IDs, credentials, fingerprints, raw destinations, and internal authority facts."
  - "Install and pin Homebrew OpenJDK 21 because macOS java_home -v 21 incorrectly resolved the host's Java 26 installation."

patterns-established:
  - "Representative fixture: 10,503 metadata rows, 401 UTC dates, foreign isolation, every target partition, duplicate decision, undo, legacy, stale, and never-started states."
  - "Parity record: compare received/ledger/unsubscribe/availability/filter/pagination boundary facts from one shared fixture in H2 and PostgreSQL."
  - "Streaming gate: first complete data row follows one source consumption; order, fixed buffering, retained heap, and close behavior hold across sizes."

requirements-completed:
  - WORK-05
  - ANLY-01
  - ANLY-02
  - ANLY-03
  - ANLY-04
  - ANLY-05
  - ANLY-06
  - ANLY-07
  - ANLY-08
  - EXPT-01
  - EXPT-02
  - EXPT-03
  - QUAL-02

coverage:
  - id: D1
    description: "PostgreSQL 16 clean/upgrade/hash/schema/constraint/index production proof"
    requirement: ANLY-01
    verification:
      - kind: integration
        ref: "Phase13PostgresMigrationTest"
        status: pass
    human_judgment: false
  - id: D2
    description: "Direct H2/PostgreSQL metric, outcome, source, ordering, median, and bucket equality"
    requirement: ANLY-04
    verification:
      - kind: integration
        ref: "Phase13PostgresMigrationTest#h2AndPostgresMetricsSourcesOutcomesAndOrderingAreEqual"
        status: pass
    human_judgment: false
  - id: D3
    description: "Representative correctness-first response budgets, plans, and incremental CSV streaming"
    requirement: ANLY-07
    verification:
      - kind: integration
        ref: "Phase13PerformanceTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "Five-route CSV and snapshot structural privacy, ownership, and security matrices"
    requirement: EXPT-03
    verification:
      - kind: integration
        ref: "Phase13ExportTest and Phase13SnapshotPrivacyTest"
        status: pass
    human_judgment: false

duration: 12min
completed: 2026-08-10
status: complete
---

# Phase 13 Plan 05: PostgreSQL Production Proof Summary

**PostgreSQL 16 migration, parity, query-plan, performance, streaming, and cross-output privacy evidence**

## Performance

- **Duration:** 12 min
- **Started:** 2026-08-10T10:25:00Z
- **Completed:** 2026-08-10T10:37:00Z
- **Tasks:** 3
- **Files modified:** 4

## Accomplishments

- Froze all production and H2 V1-V10 SHA-256 values, validated clean V1-V10 and
  real V9-to-V10 PostgreSQL paths, asserted the container JDBC URL, PostgreSQL
  16.14, Hibernate schema validation, constraints, exact V10 indexes, unchanged
  schema inventory, preserved rows, and zero fabricated facts.
- Added one shared H2/PostgreSQL fixture proving received boundaries, all outcome
  partitions, unsubscribe/legacy/linkage availability, stable two-page order,
  action filters, account isolation, array ordinal resolution, delay sample,
  `percentile_cont`-equivalent median, and UTC bucket equality.
- Added a deterministic 10,503-row fixture with 10,000 owned timestamped rows,
  500 foreign rows, three null-receipt rows, 401 UTC dates, 1,001 ledger
  targets, duplicate/partial/pending/not-dispatched/failure/undo/legacy states,
  all V8 outcomes, complete/running/partial/failed/stale/never-started sync states,
  and privacy/formula canaries.
- Proved 13 actual production statements with natural-planner parsed JSON
  `EXPLAIN (ANALYZE, BUFFERS)`, repeated warm product budgets, first-data-row and
  bounded 2k/20k/50k CSV behavior, five exact CSV schemas, cross-output/log/error
  privacy, and missing/invalid-CSRF rejection.

## Task Commits

No commits were created, as requested.

## Verification

```bash
export JAVA_HOME="${JAVA_HOME_21:-$(brew --prefix openjdk@21)}"
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"

mvn -q \
  -Dtest=Phase13PostgresMigrationTest,Phase13PerformanceTest,Phase13AnalyticsRepositoryTest,Phase13MetricDefinitionTest,Phase13AnalyticsWebTest,Phase13ExportTest,Phase13SnapshotPrivacyTest \
  test
mvn -q test
! rg -n '(failures|errors|skipped)="[1-9][0-9]*"' \
  target/surefire-reports/TEST-*.xml
git diff --check
```

- Focused Plan 13-05 suites: **7 reports, 52 tests**, 0 failures, 0 errors,
  0 skipped; **25.39 s** wall time.
- Full regression: **59 reports, 301 tests**, 0 failures, 0 errors, 0 skipped;
  **60.95 s** wall time.
- Runtime: OpenJDK **21.0.12**, Colima Docker socket
  `unix://$HOME/.colima/default/docker.sock`, PostgreSQL **16.14**.
- Five-observation maximum timings after two warmups: workspace **19 ms**,
  aggregate **19 ms**, outcomes **12 ms**, received count/page **10/16 ms**,
  cleaned count/page **15/16 ms**, top sender **28 ms**, median plus five delay
  buckets **87 ms**.
- Plans: 13 captured production statements, `enable_seqscan=on`, parsed relation/
  predicate/index checks for every fact family. Large receipt paths use
  `idx_message_metadata_account_received`; the tiny unsubscribe relation remains
  bounded by asserted time/account predicates.
- Phase 9-13 PostgreSQL suites all use `disabledWithoutDocker=false`.
- `git diff --check`: passed.

## Decisions Made

- Natural planner choices are asserted by predicate placement and bounded relation
  access rather than forced with `enable_seqscan=off`.
- V10 remains frozen and catalog-proven as additive index-only migration support.
- Snapshot privacy uses exact nested key sets plus cross-output, header, filename,
  bounded-error, and captured-log canary scans.

## Deviations from Plan

### Auto-fixed Issues

**1. Actual Java 21 was not discoverable through `/usr/libexec/java_home`**
- **Found during:** environment baseline
- **Issue:** `java_home -v 21` returned OpenJDK 26.0.1.
- **Fix:** Used the installed Homebrew OpenJDK 21.0.12 path explicitly for every
  Maven command.
- **Verification:** Surefire startup logs report Java 21.0.12.

**2. Existing V9 index outperformed broader V10 event index**
- **Found during:** EXPLAIN gate
- **Issue:** PostgreSQL selected `idx_action_events_account_status_time`.
- **Fix:** Asserted the actual selective leading index and retained separate exact
  V10 catalog assertions.
- **Verification:** JSON EXPLAIN includes account/status/time `Index Cond`.

## Issues Encountered

None remaining in automated verification.

## User Setup Required

None.

## Next Phase Readiness

- Plan 13-06 remains for real localhost browser/provider UAT and integrated
  milestone closure.
- No real provider mutation, live unsubscribe request, browser UAT, public tunnel,
  deployment, or commit was performed.

---
*Phase: 13-analytics-export-and-integrated-product-verification*
*Completed: 2026-08-10*
