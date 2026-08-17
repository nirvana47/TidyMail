---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
plan: 01
subsystem: testing
tags: [testcontainers, postgres, spring-boot-test, mockmvc, flyway]

requires:
  - phase: 17
    provides: Accepted authenticated workspace behavior and existing integration suite
provides:
  - Shared Testcontainers-Postgres journey harness using production Flyway migrations
  - Reusable user account fixture aligned with the current production schema
  - First plain-English HTTP-to-database sender journey
affects: [phase-18-test-foundation, phase-19-freshness, phase-20-classification]

actuals:
  tokens: 2200
  tasks: 2
  commits: 2

tech-stack:
  added: [spring-boot-testcontainers]
  patterns:
    - Static postgres:16-alpine service connection for Spring journey tests
    - Truncate user_accounts with cascade between tests for shared-container isolation

key-files:
  created:
    - src/test/java/com/tidymail/support/PostgresJourneyTest.java
    - src/test/java/com/tidymail/support/UserAccountFixtures.java
    - src/test/java/com/tidymail/journey/SenderTriageJourneyTest.java
  modified:
    - pom.xml

key-decisions:
  - "Use one static Testcontainers Postgres instance with TRUNCATE user_accounts RESTART IDENTITY CASCADE between journey tests."
  - "Keep Docker absence as a hard failure and document the local Colima socket environment."
  - "Treat the new sender journey as regression coverage for existing behavior rather than manufacture a misleading TDD RED failure."

patterns-established:
  - "Journey test base: extend PostgresJourneyTest for production migrations, authenticated MockMvc, provider mocks, and account fixture access."
  - "Fixture schema parity: seed every current NOT NULL user_accounts column, including V11 owner_key."

requirements-completed: [TEST-04]

coverage:
  - id: D1
    description: "A shared Spring journey harness boots against PostgreSQL 16 and validates all 14 production Flyway migrations."
    requirement: TEST-04
    verification:
      - kind: integration
        ref: "mvn test -Dtest=SenderTriageJourneyTest"
        status: pass
      - kind: integration
        ref: "mvn test (424 tests)"
        status: pass
    human_judgment: false
  - id: D2
    description: "An authenticated user sees noisiest senders ranked by count using real HTTP and database rows without provider calls."
    requirement: TEST-04
    verification:
      - kind: e2e
        ref: "src/test/java/com/tidymail/journey/SenderTriageJourneyTest.java#userConnectsGmailAndSeesTheirNoisiestSenders"
        status: pass
    human_judgment: false

duration: 6 min
completed: 2026-08-14
status: complete
---

# Phase 18 Plan 01: Shared Postgres Journey Harness Summary

**Reusable PostgreSQL 16 journey-test foundation with production Flyway migrations, centralized account fixtures, and an authenticated sender-ranking E2E test**

## Performance

- **Duration:** 6 min
- **Started:** 2026-08-14T05:46:00-07:00
- **Completed:** 2026-08-14T05:52:00-07:00
- **Tasks:** 2 implementation tasks + 1 auto-approved verification checkpoint
- **Files modified:** 4

## Accomplishments

- Added a shared `PostgresJourneyTest` base using `@ServiceConnection` and `postgres:16-alpine`, with production migrations and Hibernate schema validation.
- Centralized synthetic `user_accounts` creation in `UserAccountFixtures`, including the V11 `owner_key` contract.
- Added `userConnectsGmailAndSeesTheirNoisiestSenders`, proving visible count-descending sender results through real MockMvc routes and Postgres rows with zero mail-provider calls.
- Kept the complete legacy suite green while the new Postgres harness coexists with H2: 424 tests passed.

## Task Commits

1. **Task 1: Shared Postgres harness base class + account fixture** - `b821c04`
2. **Task 2: First sender-triage journey tracer** - `17c2fae`

## Files Created/Modified

- `pom.xml` - Adds the BOM-managed `spring-boot-testcontainers` test dependency.
- `src/test/java/com/tidymail/support/PostgresJourneyTest.java` - Shared real-Postgres Spring/MockMvc base.
- `src/test/java/com/tidymail/support/UserAccountFixtures.java` - Production-schema-aligned account seeding.
- `src/test/java/com/tidymail/journey/SenderTriageJourneyTest.java` - First named journey test.

## Decisions Made

- A static container plus account-root cascade truncation gives reuse speed while preserving per-test isolation.
- Provider implementations are mocked centrally in the base because journey routes must prove metadata-only behavior.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Account fixture omitted the V11 mandatory owner key**
- **Found during:** Task 2 journey execution
- **Issue:** The plan referenced the V1/V6 account shape, but production V11 makes `owner_key` non-null.
- **Fix:** Added deterministic `lower(email) || ':' || provider` fixture seeding.
- **Files modified:** `src/test/java/com/tidymail/support/UserAccountFixtures.java`
- **Verification:** `SenderTriageJourneyTest` and the full 424-test suite pass.
- **Committed in:** `17c2fae`

**2. [Rule 3 - Plan mismatch] TDD marker targeted already-existing sender behavior**
- **Found during:** Task 2 execution
- **Issue:** Manufacturing a RED failure would test a deliberately wrong assertion because count-descending sender behavior already exists.
- **Fix:** Added the correct permanent regression journey directly; no production behavior was changed.
- **Files modified:** `src/test/java/com/tidymail/journey/SenderTriageJourneyTest.java`
- **Verification:** Journey passes against PostgreSQL 16 and production migrations.
- **Committed in:** `17c2fae`

---

**Total deviations:** 2 auto-fixed (1 fixture bug, 1 execution-plan mismatch).
**Impact on plan:** Both changes strengthen the intended schema-parity and readable-journey guarantees without widening product scope.

## Issues Encountered

- JDK 26 emits future-compatibility warnings for Mockito dynamic agent loading and reflective final-field mutation. Tests pass; the project still compiles to Java 21.

## User Setup Required

None - local Colima variables are documented in the shared harness Javadoc.

## Next Phase Readiness

- The Postgres harness and fixture API are ready for the ≥10,000-message generator in Plan 18-02.
- H2 remains untouched and the full suite is green, preserving the planned migration sequence.

## Self-Check: PASSED

- All created files exist.
- Both task commits are present.
- Task acceptance criteria and plan verification pass.
- Full suite: 424 tests, 0 failures, 0 errors.

---
*Phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e*
*Completed: 2026-08-14*
