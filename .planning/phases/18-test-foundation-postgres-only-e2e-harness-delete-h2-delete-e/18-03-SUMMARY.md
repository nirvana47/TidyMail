---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
plan: 03
subsystem: testing
tags: [postgres, testcontainers, journeys, migration, performance]
requires:
  - phase: 18-01
    provides: Shared PostgreSQL journey harness
  - phase: 18-02
    provides: Deterministic 10,000-message synthetic mailbox
provides:
  - Four readable HTTP and PostgreSQL user journeys
  - PostgreSQL-only Spring integration test wiring
  - Zero H2 or test-migration consumers in src/test
  - Production-compatible timestamp binding and sender-query performance
affects: [phase-18-h2-collapse, phase-18-export-removal, all-integration-tests]
actuals:
  tasks: 7
  commits: 4
tech-stack:
  added: []
  patterns:
    - Global test-only ContextCustomizerFactory for shared PostgreSQL
    - Two-connection test pools across cached Spring contexts
key-files:
  created:
    - src/test/java/com/tidymail/journey/BulkActionJourneyTest.java
    - src/test/java/com/tidymail/journey/UnreadReviewJourneyTest.java
    - src/test/java/com/tidymail/journey/UnsubscribeJourneyTest.java
    - src/test/java/com/tidymail/journey/AnalyticsJourneyTest.java
    - src/test/java/com/tidymail/support/PostgresTestContextCustomizerFactory.java
  modified:
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/repository/ActionHistoryRepository.java
key-decisions:
  - "All Spring integration contexts use the shared PostgreSQL 16 container and production Flyway migrations."
  - "Cached test contexts use two-connection Hikari pools with no minimum idle to stay below PostgreSQL connection limits."
  - "The 10,000-row sender query uses an account-scoped thread-count CTE instead of correlated per-message probes."
requirements-completed: [TEST-02, TEST-04]
duration: 75 min
completed: 2026-08-14
status: complete
---

# Phase 18 Plan 03: PostgreSQL Journey Migration Summary

**Four readable user journeys and the complete integration suite now run against PostgreSQL 16 with production migrations and zero H2 consumers**

## Accomplishments

- Added bulk archive/undo, unread review, unsubscribe, and analytics journeys over real HTTP routes and PostgreSQL rows.
- Migrated all Spring integration tests from local H2 datasource blocks to one shared PostgreSQL harness.
- Removed H2 parity execution from migration tests while preserving production Flyway validation on PostgreSQL.
- Fixed PostgreSQL-specific arrays, timestamps, catalogs, intervals, foreign-key cleanup, and fixture constraints.
- Replaced a correlated sender thread probe that took about 12.6 seconds at 10,000 rows with an account-scoped aggregate CTE.
- Passed the complete 426-test suite on PostgreSQL with zero H2 or test-migration references in test code.

## Task Commits

1. **Bulk archive and exact undo journey** - `d69abff`
2. **Unread review and unsubscribe journeys** - `ab1400d`
3. **Honest analytics journey** - `25f7df0`
4. **Full integration-suite PostgreSQL migration** - `756b9f9`

## Deviations from Plan

### Auto-fixed Issues

- PostgreSQL JDBC required explicit `Timestamp` bindings for direct `Instant` parameters.
- PostgreSQL enforced typed empty arrays, `BYTEA` values, required metadata columns, and foreign-key-safe cleanup.
- Cached Spring contexts exhausted PostgreSQL's connection cap with default Hikari pools; test-only pools were bounded at two connections.
- The realistic-volume sender journey exposed a correlated query regression; the production query was changed to aggregate thread counts once per account.

## Verification

- Four journey classes pass on PostgreSQL.
- Analytics and production migration cluster passes.
- The H2 consumer scan across test sources, resources, and `pom.xml` returns no matches.
- Full Maven suite: 426 tests, 0 failures, 0 errors.

## Self-Check: PASSED

Plan 18-04 can safely collapse production H2 branches, and Plan 18-05 can delete the now-orphaned H2 artifacts.

---
*Phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e*
*Completed: 2026-08-14*
