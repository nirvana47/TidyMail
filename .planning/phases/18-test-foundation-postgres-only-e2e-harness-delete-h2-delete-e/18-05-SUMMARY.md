---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
plan: 05
subsystem: build
tags: [postgres, h2-removal, flyway, testcontainers]
requires:
  - phase: 18-03
    provides: Zero H2 consumers in test code
  - phase: 18-04
    provides: PostgreSQL-only production repository SQL
provides:
  - No H2 dependency
  - No parallel H2 migration set
  - PostgreSQL-only integration and migration suite
affects: [all-tests, ci]
actuals:
  tasks: 3
  commits: 1
key-files:
  modified:
    - pom.xml
  deleted:
    - src/test/resources/db/test-migration/
key-decisions:
  - "Docker/Testcontainers is a hard requirement for integration-test trust; there is no H2 fallback."
requirements-completed: [TEST-01, TEST-04]
completed: 2026-08-14
status: complete
---

# Phase 18 Plan 05: H2 Artifact Removal Summary

**The H2 dependency and all 14 parallel test migrations are deleted; the suite runs exclusively on PostgreSQL**

## Accomplishments

- Removed `com.h2database:h2` from Maven test dependencies.
- Deleted the complete `src/test/resources/db/test-migration/` tree.
- Retained Spring Boot Testcontainers, JUnit Testcontainers, and PostgreSQL dependencies.
- Verified production Flyway migration tests and the complete suite without H2 on the classpath.

## Commit

- **Delete orphaned H2 dependency and migrations** - `4d79385`

## Checkpoints

- Decision: proceed, based on the user's explicit PostgreSQL-only execution request.
- Human verification: approved after the complete suite passed with H2 absent.

## Verification

- Clean test compilation passes.
- H2 source, dependency, and migration scans return zero matches.
- H2 migration directory is absent.
- Full Maven suite: 405 tests, 0 failures, 0 errors.

## Self-Check: PASSED

TidyMail now has one production and test SQL dialect: PostgreSQL.

---
*Phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e*
*Completed: 2026-08-14*
