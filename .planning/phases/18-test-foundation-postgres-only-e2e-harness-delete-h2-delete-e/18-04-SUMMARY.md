---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
plan: 04
subsystem: persistence
tags: [postgres, sql, h2-removal]
requires:
  - phase: 18-03
    provides: Full integration suite running on PostgreSQL
provides:
  - PostgreSQL-only production repository SQL
  - No runtime database-product detection or H2 SQL branches
affects: [phase-18-h2-artifact-deletion]
actuals:
  tasks: 4
  commits: 1
key-files:
  modified:
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/repository/AnalyticsMailboxRepository.java
    - src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java
    - src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java
key-decisions:
  - "Retain only PostgreSQL =ANY, one-based array, percentile_cont, and SHA-256 encoding expressions."
requirements-completed: [TEST-01]
completed: 2026-08-14
status: complete
---

# Phase 18 Plan 04: Production H2 Branch Collapse Summary

**Five production repositories now use one PostgreSQL SQL path with no runtime dialect detection**

## Accomplishments

- Removed all `boolean h2`, database-product inspection, `ARRAY_CONTAINS`, `ARRAY_GET`, and H2 hashing branches.
- Retained PostgreSQL-native array membership, one-based array indexing, `percentile_cont`, and SHA-256 encoding.
- Updated the source contract to require the PostgreSQL-only unread-label helper.
- Kept the complete 426-test PostgreSQL suite green.

## Commit

- **Collapse all five production H2 branches** - `3504a86`

## Decision Checkpoint

Proceed was recorded from the user's explicit PostgreSQL-only, remove-H2 execution request after Plan 18-03 passed the full suite.

## Verification

- Clean compile and test compile pass.
- Production H2 branch scan returns zero matches.
- Full Maven suite: 426 tests, 0 failures, 0 errors.

## Self-Check: PASSED

Plan 18-05 can remove the orphaned H2 dependency and migration resources.

---
*Phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e*
*Completed: 2026-08-14*
