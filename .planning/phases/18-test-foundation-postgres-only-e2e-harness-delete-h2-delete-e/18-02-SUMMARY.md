---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
plan: 02
subsystem: testing
tags: [postgres, synthetic-data, jdbc-batch, pii-guard, testcontainers]
requires:
  - phase: 18-01
    provides: Shared Postgres journey harness and account fixtures
provides:
  - Deterministic 10,000-row synthetic mailbox generator
  - Opt-in large-mailbox fixture hook for all Postgres journeys
  - Automated PII and repeatability guard
affects: [phase-18-journeys, performance-testing, freshness, classification]
actuals:
  tokens: 4200
  tasks: 3
  commits: 4
tech-stack:
  added: []
  patterns:
    - Deterministic seeded JDBC batch fixtures
    - Suite-lifetime static Testcontainers database
key-files:
  created:
    - src/test/java/com/tidymail/support/SyntheticMailboxGenerator.java
    - src/test/java/com/tidymail/support/SyntheticMailboxPiiGuardTest.java
  modified:
    - src/test/java/com/tidymail/support/PostgresJourneyTest.java
key-decisions:
  - "Generate every identity and metadata value from fixed .test templates and a seeded Random."
  - "Start the base container once per Maven JVM so Spring context caching cannot retain a stopped JDBC endpoint."
patterns-established:
  - "Large fixture API: seedSyntheticMailbox(seed, rowCount) returns a stable account/result handle."
  - "PII guard: positive synthetic regexes plus negative public-domain/token disallow-list."
requirements-completed: [TEST-05]
coverage:
  - id: D1
    description: "A deterministic JDBC batch generator seeds at least 10,000 production-shaped metadata rows."
    requirement: TEST-05
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/support/SyntheticMailboxPiiGuardTest.java#fixedSeedProducesIdenticalMailboxRows"
        status: pass
    human_judgment: false
  - id: D2
    description: "Generated sender identities, subjects, domains, and provider IDs contain only synthetic derived values."
    requirement: TEST-05
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/support/SyntheticMailboxPiiGuardTest.java#syntheticMailboxContainsRealisticVolumeWithoutRealPii"
        status: pass
    human_judgment: false
duration: 8 min
completed: 2026-08-14
status: complete
---

# Phase 18 Plan 02: Synthetic Mailbox Dataset Summary

**Deterministic 10,000-row PostgreSQL mailbox fixture with skewed senders, unsubscribe signals, complete array metadata, and build-enforced PII protection**

## Performance

- **Duration:** 8 min
- **Started:** 2026-08-14T05:52:00-07:00
- **Completed:** 2026-08-14T06:00:00-07:00
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments

- Added `SyntheticMailboxGenerator` with fixed-seed, fixed-time, Zipf-like sender distribution and 1,000-row JDBC batches.
- Populated all current metadata columns, including array headers, attachment/size facts, and trusted unsubscribe signals.
- Added opt-in harness methods and a guard that verifies 10,000 rows, deterministic reseeding, synthetic patterns, and bulk/non-bulk coverage.
- Kept the cumulative suite green: 426 tests passed.

## Task Commits

1. **Task 1: Deterministic synthetic mailbox generator** - `372e9a4`
2. **Task 2: Shared harness seed hook** - `84bfe99`
3. **Task 3: PII and determinism guard** - `580b087`
4. **Deviation: Suite-lifetime container lifecycle** - `284c102`

## Files Created/Modified

- `src/test/java/com/tidymail/support/SyntheticMailboxGenerator.java` - Batched production-shaped dataset generation.
- `src/test/java/com/tidymail/support/SyntheticMailboxPiiGuardTest.java` - PII, volume, signal, and repeatability enforcement.
- `src/test/java/com/tidymail/support/PostgresJourneyTest.java` - Opt-in generator hooks and suite-lifetime container.

## Decisions Made

- Minimum row count is enforced at 10,000; callers can request more but cannot silently use toy volume.
- Time is anchored to a fixed instant so repeat runs produce identical facts.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Generator used a non-canonical unsubscribe auth reason**
- **Found during:** Task 3
- **Issue:** V8 only permits `TRUSTED_PASS`, `NO_TRUSTED_RESULT`, `AMBIGUOUS_RESULT`, or `COVERAGE_MISSING`.
- **Fix:** Bulk synthetic rows use `TRUSTED_PASS`.
- **Verification:** PII guard passes over 10,000 rows.
- **Committed in:** `580b087`

**2. [Rule 1 - Bug] Extension-managed inherited container conflicted with Spring context caching**
- **Found during:** Full-suite gate
- **Issue:** JUnit restarted the inherited container per subclass while Spring reused the first context and stale JDBC URL.
- **Fix:** Start the static container once for the Maven JVM and let Ryuk stop it at process exit.
- **Verification:** Sender and PII journey subclasses pass together; full 426-test suite passes.
- **Committed in:** `284c102`

---

**Total deviations:** 2 auto-fixed correctness defects.
**Impact on plan:** The fixes enforce the production schema and the intended single-container suite contract.

## Issues Encountered

- Initial cumulative run failed on stale cached JDBC connections; corrected by suite-lifetime container ownership.

## User Setup Required

None.

## Next Phase Readiness

- Plan 18-03 can seed realistic volume through `seedSyntheticMailbox(...)`.
- The generator is reproducible and safe to reuse in performance journeys.

## Self-Check: PASSED

- Required files and commits exist.
- PII guard: 2 tests pass.
- Full suite: 426 tests, 0 failures, 0 errors.

---
*Phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e*
*Completed: 2026-08-14*
