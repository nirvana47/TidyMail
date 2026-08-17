---
phase: 15-implement-pending-cleanup-and-navigation
plan: "07"
subsystem: database
tags: [postgresql, jdbc, pagination, performance, unsubscribe, security]

requires:
  - phase: 11-safe-unsubscribe-and-protection
    provides: Hardened unsubscribe classification, consent, protection, and truthful outcomes
  - phase: 15-implement-pending-cleanup-and-navigation
    plan: "01"
    provides: Bounded workspace paging patterns and PostgreSQL evidence conventions
provides:
  - Production-shaped PostgreSQL unsubscribe query and latency regression evidence
  - Relational count/page filtering with page-only URI-bearing source fetches
  - Exact candidate-fingerprint outcome joins and relational cleanup lifecycle facts
affects: [phase-15-plan-11, bulk-unsubscribe, unsubscribe-navigation]

actuals:
  tokens: 33245
  tasks: 2
  commits: 3

tech-stack:
  added: []
  patterns:
    - Body-free candidate identity projection before page limits
    - Page identity allowlist before URI-bearing unsubscribe metadata reads
    - Dialect-specific SHA-256 expressions preserving PostgreSQL and H2 parity

key-files:
  created:
    - src/test/java/com/tidymail/Phase15UnsubscribePerformanceTest.java
  modified:
    - src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java
    - src/main/java/com/tidymail/service/UnsubscribeCandidateService.java
    - src/test/java/com/tidymail/Phase11CandidateQueryTest.java

key-decisions:
  - "Candidate lifecycle joins reproduce the exact Phase 11 candidate fingerprint from a body-free list-identity projection."
  - "Volume, read, sender, date, and state sorts use the relational page path; classification-dependent confidence, method, and review sorts retain the measured compatibility fallback."
  - "URI parsing, RFC 8058 eligibility, protection, and execution remain in the existing Phase 11 Java safety path."

patterns-established:
  - "Three-query navigation: exact count, ordered sender page, then source facts for at most 25 owned sender identities."
  - "Lifecycle filtering uses persisted candidate outcomes and cleanup execution facts before LIMIT/OFFSET."

requirements-completed: [QUAL-01, QUAL-04]

coverage:
  - id: D1
    description: "Production-shaped PostgreSQL evidence proves bounded statement count, retained plans, and sub-two-second cold/warm navigation."
    requirement: QUAL-04
    verification:
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase15UnsubscribePerformanceTest.java#navigationIsPageBoundedTruthfulAndWithinTheIndexedProductBudget"
        status: pass
      - kind: other
        ref: "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) for count, page, and source query families"
        status: pass
    human_judgment: false
  - id: D2
    description: "Bulk Unsubscribe preserves exact filters, ordering, candidate outcomes, protection, and Phase 11 safety while fetching URI sources only for page identities."
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: "mvn -Dtest='Phase15UnsubscribePerformanceTest,Phase11*Test' test"
        status: pass
      - kind: integration
        ref: "src/test/java/com/tidymail/Phase11CandidateQueryTest.java#pageSourceFetchRejectsOversizedOrForeignIdentitiesAndExcludesOffPageSenders"
        status: pass
    human_judgment: false

duration: 11min
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 07: Page-Bounded Bulk Unsubscribe Queries Summary

**Bulk Unsubscribe navigation now uses exact relational count/page queries and fetches safety-sensitive source metadata only for the returned 25 sender identities**

## Performance

- **Duration:** 11 min
- **Started:** 2026-08-11T23:11:43Z
- **Completed:** 2026-08-11T23:22:39Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- Added a PostgreSQL 16 fixture with 600 senders, 24,000 messages, 600 outcomes, and 300 cleanup targets, retaining statement, heap, timing, and JSON plan evidence.
- Replaced repeated broad source/sender batches with exactly three query families: exact count, filtered ordered page, and page-identity source facts.
- Preserved candidate-specific outcomes, cleanup lifecycle facts, source ordering, protection, and every Phase 11 parser/security/execution contract.

## Task Commits

1. **Task 15-07-01: Capture production-shaped unsubscribe baseline** - `e31f3a0` (test)
2. **Task 15-07-02: Bound candidate, source, outcome, and history work** - `e8b6bfc` (perf)

**Plan metadata:** committed separately after this summary was written.

## Files Created/Modified

- `src/test/java/com/tidymail/Phase15UnsubscribePerformanceTest.java` - PostgreSQL statement, plan, cardinality, lifecycle, heap, and latency regression.
- `src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java` - Exact lifecycle count/page pipeline and bounded source identity query.
- `src/main/java/com/tidymail/service/UnsubscribeCandidateService.java` - Page-first orchestration with retained classification-sort fallback.
- `src/test/java/com/tidymail/Phase11CandidateQueryTest.java` - Off-page, oversized, and foreign source identity regression coverage.

## Decisions Made

- The page projection derives only selected list identity and metadata identity before paging. Full unsubscribe headers remain outside the projection.
- PostgreSQL uses built-in `sha256`; H2 uses `HASH('SHA-256', ...)`, both reproducing the Java candidate fingerprint exactly.
- Classification-dependent sorts retain the prior broad compatibility path because exact URI parsing remains authoritative in Java; default and relational navigation paths are bounded.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Testcontainers did not discover Colima's non-default socket automatically. Verification passed after setting `DOCKER_HOST` to the active Docker context and preserving `/var/run/docker.sock` as the container-side override.
- An initial sender-fingerprint outcome join failed the existing candidate-identity-neutral regression. It was replaced before commit with the exact candidate-fingerprint projection required by Phase 11 truthfulness.

## User Setup Required

None - no external service configuration required.

## Verification

- `mvn -Dtest='Phase15UnsubscribePerformanceTest' test` - PostgreSQL 16 performance regression passed.
- `mvn -Dtest='Phase15UnsubscribePerformanceTest,Phase11CandidateQueryTest,Phase11UnsubscribeSecurityTest,Phase11UnsubscribeExecutionTest' test` - 32 tests passed.
- `mvn -Dtest='Phase15UnsubscribePerformanceTest,Phase11*Test' test` - 50 tests passed.
- Final PostgreSQL evidence: 3 statement families, 802 ms cold, 783-787 ms warm, 25 page identities, and retained count/page/source `EXPLAIN ANALYZE` buffer plans.

## Next Phase Readiness

The bounded backend is ready for Plan 15-11's authoritative individual-sender Bulk Unsubscribe UI completion. No Plan 15-07 blockers remain.

## Self-Check: PASSED

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
