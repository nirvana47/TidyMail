---
phase: 15
slug: implement-pending-cleanup-and-navigation
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-08-11
replanned: 2026-08-11
---

# Phase 15 - Validation Strategy

## Test Infrastructure

| Property | Value |
|----------|-------|
| Framework | JUnit 5, Spring Boot Test, MockMvc, Testcontainers PostgreSQL 16 |
| Config | `pom.xml` |
| Quick command | `mvn -Dtest='Phase15*Test' test` |
| Full command | `mvn test` |
| Focused latency | Under 120 seconds |

## Sampling Rate

- After every implementation task: run its mapped command.
- After each wave: `mvn -Dtest='Phase15*Test,Phase10*Test,Phase11*Test,Phase12*Test,Phase14*Test' test`.
- Before verification sign-off: focused inherited suite, then `mvn test`.
- PostgreSQL/Testcontainers evidence may not be replaced by H2-only evidence.

## Per-Task Verification Map

| Task ID | Plan | Wave | Decisions / Todos | Threats | Proof | Command | Status |
|---------|------|------|-------------------|---------|-------|---------|--------|
| 15-01-01 | 01 | 1 | D-01,D-12..14; Todo 18..21 | T-15-01..03 | exact summaries, 5/+5, stable ties, 1,000/1,001 Load All | `mvn -Dtest='Phase15CategoryPagingTest' test` | pending |
| 15-01-02 | 01 | 1 | D-12..14; Todo 18..21 | T-15-01..03 | controller-service-repository-fragment tracer and 422 | `mvn -Dtest='Phase15CategoryPagingTest' test` | pending |
| 15-02-01 | 02 | 2 | D-20..23; Todo 14..15,25..26 | T-15-04..07 | exact per-sender scope/hash/count, Review, cap, idempotency, conflicts, no provider | `mvn -Dtest='Phase15StagingTest' test` | pending |
| 15-09-01 | 09 | 3 | D-07..10,D-17,D-19,D-20..23; Todo 09..10,13..17,22..26 | T-15-04,T-15-06..09 | sender correction routing plus HTMX/native inline staging, warning, disabled state, responsive contract | `mvn -Dtest='Phase15WorkspaceWebTest,Phase15StagingTest,Phase15CategoryPagingTest' test` | pending |
| 15-03-01 | 03 | 2 | D-15..17,D-19; Todo 22..24 | T-15-08..11 | PostgreSQL/H2 owned override schema and isolation | `mvn -Dtest='Phase15PostgresMigrationTest,Phase15ClassificationTest' test` | pending |
| 15-03-02 | 03 | 2 | D-15..16; Todo 23..24 | T-15-09..11 | four deterministic false-positive fixtures and explainability | `mvn -Dtest='Phase15ClassificationTest' test` | pending |
| 15-04-01 | 04 | 3 | D-15..16,D-18 | T-15-10,T-15-14 | effective SQL/Java classification parity | `mvn -Dtest='Phase15ClassificationTest,Phase15PostgresMigrationTest,Phase10DecisionQueryTest' test` | pending |
| 15-04-02 | 04 | 3 | D-18,D-20,D-24; Todo 26..28 | T-15-12..14 | Inbox-negative fixtures, overlap dedupe, exact materialization | `mvn -Dtest='Phase15UnreadSemanticsTest,Phase15PostgresMigrationTest,Phase10DecisionQueryTest,Phase10SelectionMaterializationTest' test` | pending |
| 15-05-01 | 05 | 4 | D-07..19,D-24; Todo 18..24,27..30 | T-15-15..17 | overlapping bounded UI reusing Plan 09 correction routing, correction isolation, freshness/order | `mvn -Dtest='Phase15WorkspaceWebTest,Phase10SenderUnreadWebTest,Phase15ClassificationTest,Phase15UnreadSemanticsTest' test` | pending |
| 15-10-01 | 10 | 5 | D-20..24; Todo 25..30 | T-15-16..17 | Unread inline exact staging and responsive/native behavior | `mvn -Dtest='Phase15WorkspaceWebTest,Phase15StagingTest,Phase10SenderUnreadWebTest' test` | pending |
| 15-06-01 | 06 | 4 | D-01..05; Todo 01..08 | T-15-18..19 | authorized labels/badges, account route retention/state reset | `mvn -Dtest='Phase14WorkspaceFidelityTest' test` | pending |
| 15-06-02 | 06 | 4 | D-06; Todo 11 | T-15-20 | all-workspace no-export scan with backend regression | `mvn -Dtest='Phase13AnalyticsWebTest,Phase13ExportTest,Phase14InteractionFidelityTest' test` | pending |
| 15-07-01 | 07 | 2 | D-27; Todo 12 | T-15-22 | production-shaped PostgreSQL baseline and plan evidence | `mvn -Dtest='Phase15UnsubscribePerformanceTest' test` | pending |
| 15-07-02 | 07 | 2 | D-25..27; Todo 12,31 | T-15-21..23 | page-bounded relational work and Phase 11 safety | `mvn -Dtest='Phase15UnsubscribePerformanceTest,Phase11CandidateQueryTest,Phase11UnsubscribeSecurityTest,Phase11UnsubscribeExecutionTest' test` | pending |
| 15-11-01 | 11 | 5 | D-06,D-25..26; Todo 11,31 | T-15-20..21,T-15-23 | individual-sender authoritative UI, truthful outcomes, and Review/Unsubscribe no-export | `mvn -Dtest='Phase11UnsubscribeWebTest,Phase11UnsubscribeSecurityTest,Phase15UnsubscribePerformanceTest,Phase14InteractionFidelityTest,Phase13ExportTest' test` | pending |
| 15-08-01 | 08 | 6 | D-01..27; Todo 01..31 | T-15-24 | structural + focused + full + PostgreSQL/H2 + deterministic fixtures + Java 21 localhost:8080 readiness | `mvn -Dtest='Phase15*Test,Phase10*Test,Phase11*Test,Phase12*Test,Phase14*Test' test && mvn test`, then Compose/startup/health preparation | pending |
| 15-08-02 | 08 | 6 | D-01..27; Todo 01..31 | T-15-24 | final authenticated five-page browser matrix and process/database cleanup | End-of-phase manual checkpoint | pending |

## Wave 0 Requirements

- [ ] `Phase15CategoryPagingTest` with 12+ tied senders and 1,000/1,001 category totals.
- [ ] `Phase15StagingTest` with per-sender duplicate/conflict/LIFO/idempotency, protected, 1,000/1,001 action IDs, exact hash/count, and provider zero-interaction fixtures.
- [ ] `Phase15ClassificationTest` with two-account same-sender isolation and four false-positive directions.
- [ ] `Phase15UnreadSemanticsTest` with Inbox unread, Inbox read, archived unread, Spam unread, Trash unread, and overlapping groups.
- [ ] `Phase15PostgresMigrationTest` with V14 constraints, H2 parity, array predicates, and measured index/query plans.
- [ ] `Phase15UnsubscribePerformanceTest` with production-shaped candidates/messages/outcomes/cleanup targets.
- [ ] Existing Phase 10/13/14 tests are updated in the owning production plans, not deferred to final verification.

## Manual-Only Verifications

| Behavior | Plan/Task | Instructions |
|----------|-----------|--------------|
| Session/readiness prerequisite | 15-08-01 -> 15-08-02 | Java and Maven report 21; deterministic fixture suites pass; PostgreSQL `pg_isready` succeeds; `localhost:8080/actuator/health` is `UP`; browser OAuth session reaches an owned-account workspace without `/login` redirect |
| Five-page responsive/accessibility | 15-08-02 | After the prerequisite passes: desktop, 375x812, 200% zoom, keyboard/focus, 44px targets, page/local overflow on Bulk Archive/Delete, Unread Only, Bulk Unsubscribe, Review, Analytics |
| Category interaction | 15-08-02 | Open 5, load +5, Load All, 1,000/1,001, collapse/reopen, inspect response bodies |
| Account-local correction/freshness | 15-08-02 | Correct same sender differently in two accounts, reload/resync, verify exact freshness copy before categories |
| Exact direct staging/Review | 15-08-02 | Stage one and multiple senders, inspect inline count and identical Review hash/count, duplicate/conflict/LIFO/disabled reconciliation, Delete warning |
| Unsubscribe latency/safety | 15-08-02 | Record warm/cold timings and inspect eligible, legacy, manual, protected, and truthful outcome paths |
| Cleanup/stop | 15-08-02 | Stop the recorded `/tmp/tidymail-phase15.pid`, confirm port 8080 and health are closed, remove the PID file, and run `docker compose stop db` before approval or failure routing |

## Coverage Sign-Off

- [x] 17/17 actual task IDs map exactly to the eleven plans.
- [x] Every implementation plan declares at most 9 `files_modified`; Plan 08 is read-only with 0.
- [x] Every task `<files>` union agrees exactly with its plan `files_modified` frontmatter.
- [x] D-01 through D-27 are cited explicitly in plan must-haves and validation.
- [x] Todo 01 through Todo 31 are cited explicitly in plan must-haves and validation.
- [x] T-15-01 through T-15-24 map to tasks and verification.
- [x] ACTN-02 cleanup-surface supersession and retained other-surface previews are tested.
- [x] 1,000-row Load All/422 and separate 1,000-action-ID contracts are covered.
- [x] One Review action per selected sender, immutable exact hash/count, freshness copy, no-export, PostgreSQL/H2 parity, and no-new-external-API constraints are covered.
- [x] Every automated task has a command; the only manual task is an explicit human checkpoint.
- [x] The only human checkpoint is the final task of the final wave and follows concrete Java 21, deterministic fixture, PostgreSQL, server startup, health/readiness, authenticated-session, and cleanup gates.

`nyquist_compliant: true` is valid because all 17 tasks have automated or explicit manual evidence, all missing fixtures are declared in Wave 0, and every decision, todo, threat, and phase requirement has a mapped proof.
