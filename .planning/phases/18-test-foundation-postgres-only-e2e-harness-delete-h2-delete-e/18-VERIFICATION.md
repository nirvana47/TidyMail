---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
verified_at: "2026-08-14T14:57:49Z"
status: passed
score: "19/19 must-haves; 5/5 requirements"
head: 378ff54
scope: "HEAD production code including the committed Phase 18 fixture realism and analytics assertion closure, plus status-only planning edits marking Phase 18 complete and Phase 19 ready to plan"
requirements:
  TEST-01: passed
  TEST-02: passed
  TEST-03: passed
  TEST-04: passed
  TEST-05: passed
automated_checks:
  - name: full_maven_suite
    result: passed
    evidence: "404 tests, 0 failures, 0 errors, 0 skipped"
  - name: current_phase18_focused_suite
    result: passed
    evidence: "8 tests, 0 failures, 0 errors, 0 skipped"
  - name: h2_and_export_source_scans
    result: passed
    evidence: "No H2 consumers/dependency/migrations/dialect branches and no residual export implementation or routes"
---

# Phase 18 Verification

## Verdict

Phase 18 achieves its goal. All six plans' must-haves and TEST-01 through TEST-05 are satisfied by the production code at HEAD, including the committed synthetic fixture realism and analytics assertion closure. The subsequent ROADMAP, 18-03-SUMMARY, and 18-VALIDATION metadata cleanup changes no implementation evidence or verification outcome.

## Requirement Status

| Requirement | Status | Independent evidence |
|---|---|---|
| TEST-01 | Passed | PostgreSQL 16 Testcontainers is the only integration database. Flyway validates and applies all 14 files from `src/main/resources/db/migration`; the 404-test full suite passed. The H2 dependency, test migrations, datasource references, and runtime repository branches are absent. |
| TEST-02 | Passed | Six plain-English journeys exercise authenticated MockMvc HTTP routes backed by real PostgreSQL rows: sender triage, exact bulk archive/undo, unread review, unsubscribe, analytics, and removed exports. Assertions use visible behavior/model facts rather than raw template source. |
| TEST-03 | Passed | Export controller/service/configuration/package, snapshot service, DTO URLs, property, scan path, and response writer are absent. The authenticated negative journey confirms all six former routes return 404; `exportOrder` remains for live in-page ordering. |
| TEST-04 | Passed | `PostgresTestContextCustomizerFactory` globally binds Spring integration contexts to the shared PostgreSQL container and production Flyway path. `PostgresDatabaseCleanupTestExecutionListener` truncates account-rooted state before each test, schedulers are disabled, and pools are bounded. No test can fall back to H2. |
| TEST-05 | Passed | The seeded generator enforces at least 10,000 rows, fixed seed/time, skewed sender volume, bulk and non-bulk populations, all production metadata columns, and 1,000-row JDBC batches. Current guards scan canonical whole rows for disallowed PII and checksum canonical whole rows for determinism. |

## Must-Have Evidence

- The shared harness starts PostgreSQL 16, uses `@ServiceConnection`, validates JPA against production migrations, exposes reusable account and synthetic-mailbox fixtures, and isolates tests through schema reset.
- PostgreSQL-only SQL is retained for `=ANY(...)`, one-based arrays, `percentile_cont`, and SHA-256 encoding. Repository-wide H2 scans are clean.
- Exact-ID safety is asserted by persisting only the eligible provider ID, preserving the immutable scope hash, executing that ID, and replaying only that ID during undo.
- Protected senders are excluded from bulk scope and rendered unavailable for unsubscribe. Existing PostgreSQL integration tests also cover explicit protected overrides and audit records.
- Mutating journeys provide CSRF tokens; inherited security tests assert missing or invalid CSRF produces 403 responses.
- The current synthetic fixture keeps external sender identity coherent within each three-message thread. `SENT` rows are owner-originated and cannot also carry `INBOX` or `UNREAD`.
- Analytics now asserts exact `RECEIVED=113`, `SENT=7`, top-sender identity/count `113`, and exact ratio numerator/denominator pairs. The PostgreSQL median control remains `4096`.

## Automated Checks

| Check | Result |
|---|---|
| Recorded final full suite: `mvn test` with Colima/Testcontainers | Passed: 404 tests, 0 failures, 0 errors, 0 skipped |
| Fresh current-tree Phase 18 journeys plus PII/determinism guard | Passed: 8 tests, 0 failures, 0 errors, 0 skipped |
| Fresh container migration evidence | PostgreSQL 16.14; 14 production migrations validated and applied through V14 |
| H2 scan across `pom.xml`, `src/main`, and `src/test` | No consumers, dependency, migration location, or dialect branch; one test assertion mentions the deleted token only |
| Export implementation and route scan | No residual implementation or route mapping; all six authenticated requests returned 404 |
| Working-tree syntax check | `git diff --check` passed |

## Advisory Residual Risks

- The synthetic mailbox is deterministic and production-shaped, but it remains a simplified statistical model rather than a full behavioral replica of a 119,811-message mailbox. This does not invalidate the stated volume, privacy, distribution, or journey must-haves.
- The journeys use Spring MockMvc, so they exercise the real security/controller/view/service/database route stack without opening a network socket. Browser and deployed-server behavior remain outside this phase's automated scope.
