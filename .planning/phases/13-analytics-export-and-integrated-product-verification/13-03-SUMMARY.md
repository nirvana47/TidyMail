---
phase: 13-analytics-export-and-integrated-product-verification
plan: "03"
subsystem: export-privacy
tags: [java-21, spring-mvc, jdbc, csv, streaming, privacy, postgres-16]

requires:
  - phase: 13-analytics-export-and-integrated-product-verification
    provides: canonical analytics predicates, availability, source equality, and ledger metrics
  - phase: 12-review-dual-execution-history-undo
    provides: canonical History predicate, safe outcome facts, and privacy contracts
provides:
  - Generic allowlisted CSV source/schema and bounded repeatable-read export orchestration
  - Five authenticated filter-faithful CSV routes with atomic 50000-row caps
  - Versioned aggregate-only sanitized analytics snapshot
  - Recursive CSV/snapshot privacy, formula-defense, closure, and route-ownership evidence
affects: [13-04, 13-05, 13-06, exports, analytics, privacy, performance]

actuals:
  tokens: 42872
  tasks: 3
  commits: 0

tech-stack:
  added: []
  patterns:
    - count and stream execute in one read-only REPEATABLE_READ servlet request transaction
    - prepared snapshots own count, row stream, schema, and cleanup as one export unit
    - unsubscribe classification uses fixed-size external sort runs from one captured instant
    - fixed CsvSchema projections prevent entity or hidden-field serialization
    - over-cap rejection occurs before content type, attachment headers, or body bytes
    - sanitized JSON is projected field by field from aggregate analytics records

key-files:
  created:
    - src/main/java/com/tidymail/service/ExportService.java
    - src/main/java/com/tidymail/controller/ExportController.java
    - src/main/java/com/tidymail/export/FilteredCsvSource.java
    - src/main/java/com/tidymail/export/CsvExportSnapshot.java
    - src/main/java/com/tidymail/export/CsvSchema.java
    - src/main/java/com/tidymail/dto/SanitizedAnalyticsSnapshot.java
    - src/main/java/com/tidymail/service/SanitizedSnapshotService.java
    - src/test/java/com/tidymail/Phase13ExportTest.java
    - src/test/java/com/tidymail/Phase13SnapshotPrivacyTest.java
    - src/test/java/com/tidymail/service/UnsubscribeExportSnapshotTest.java
  modified:
    - src/main/java/com/tidymail/controller/AnalyticsController.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/repository/ActionHistoryRepository.java
    - src/main/java/com/tidymail/service/CsvResponseWriter.java
    - src/main/resources/application.properties
    - src/test/java/com/tidymail/Phase13AnalyticsWebTest.java

key-decisions:
  - "Synchronous servlet output keeps count, cap decision, headers, and cursor consumption inside one repeatable-read transaction."
  - "AnalyticsController no longer owns /analytics.csv; ExportController is its only mapping owner."
  - "Unsubscribe export externally sorts one canonical classified scan into a disk-backed prepared snapshot."
  - "Analytics ownership is resolved once and passed unchanged through workspace, cap count, and stream."
  - "Unavailable analytics zero values export as empty cells plus explicit availability and bounded reason."

patterns-established:
  - "Export adapters accept typed queries and owned account IDs; raw parameter maps and entities never enter schemas."
  - "CSV cells are formula-hardened after all leading Unicode whitespace/control/format/separator prefixes without altering ordinary cells."
  - "Snapshot identity is reduced to ONE_ACCOUNT or account count, and sender identity to Sender N aliases."

requirements-completed:
  - WORK-05
  - EXPT-01
  - EXPT-02
  - EXPT-03

coverage:
  - id: D1
    description: "Atomic capped RFC 4180 streaming infrastructure with injection defense"
    requirement: EXPT-01
    verification:
      - kind: integration
        ref: "Phase13ExportTest#capMinusOneCapAndCapPlusOneAreAtomicAndStreamOnlyWhenAllowed"
        status: pass
      - kind: unit
        ref: "Phase13ExportTest#writerUsesExactCrLfQuotingUnicodeNullAndAllDangerousPrefixes"
        status: pass
    human_judgment: false
  - id: D2
    description: "Five authenticated current-filter CSV routes with single analytics route ownership"
    requirement: EXPT-02
    verification:
      - kind: integration
        ref: "Phase13ExportTest (8 tests)"
        status: pass
      - kind: integration
        ref: "Phase13AnalyticsWebTest (21 tests)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Versioned aggregate-only snapshot with anonymous account and sender treatment"
    requirement: EXPT-03
    verification:
      - kind: integration
        ref: "Phase13SnapshotPrivacyTest (3 tests)"
        status: pass
    human_judgment: false

duration: 45min
completed: 2026-08-10
status: complete
---

# Phase 13 Plan 03: Bounded Exports And Sanitized Snapshot Summary

**Five filter-faithful CSV downloads now share an atomic 50,000-row streaming boundary, and analytics has a dedicated aggregate-only privacy snapshot.**

## Performance

- **Duration:** 45 min
- **Started:** 2026-08-10T08:18:00Z
- **Completed:** 2026-08-10T09:03:00Z
- **Tasks:** 3
- **Files modified:** 21

## Accomplishments

- Added generic `FilteredCsvSource<Q,R>`, fixed `CsvSchema<R>`, and `ExportService`
  orchestration. Count and cursor consumption share one read-only repeatable-read
  transaction; cap+1 returns 422 before attachment metadata or CSV bytes.
- Added prepared export snapshots. Unsubscribe classification now captures one
  instant, scans repository batches once, and uses fixed-size disk-backed external
  sorting so cap and stream share exact rows with bounded heap use.
- Added `/senders.csv`, `/unread.csv`, `/unsubscribe.csv`,
  `/review/history.csv`, and `/analytics.csv`, preserving typed filters, owned scope,
  deterministic order, validated-but-nonlimiting page state, and provider-free GETs.
- Transferred sole `/analytics.csv` ownership from `AnalyticsController` to
  `ExportController` while retaining exact Received/Cleaned source mode and adding
  complete long-form dashboard metric, bucket, outcome, ratio, and distribution rows.
- Added `/analytics/snapshot.json` with schema version, UTC generation/filter state,
  aggregate definitions and N/D values, availability, trend, outcomes, coverage,
  delay distribution, and anonymous `Sender N` ranks only.

## Exact Exports

- **Senders:** account; approved sender identity/domain; message/unread counts;
  oldest/newest receipt; type, recommendation, risk, protection, queue, safe reasons.
- **Unread:** account; approved sender identity/domain and subject; receipt time;
  category, thread/waiting state, recommendation, protection, safe reasons.
- **Unsubscribe:** safe sender/list identity; volume/activity; methods,
  recommendation, confidence/eligibility, protection/review, bounded latest outcome.
- **Review History:** safe account/target; action, mode, source, status/time; requested,
  accepted, succeeded, failed, pending, not-dispatched; bounded outcome/reason.
- **Analytics:** fixed long-form dashboard schema; exact source mode retains the
  reviewed six-column Received or nine-column Cleaned source schema.
- **Snapshot:** dedicated version-1 JSON DTO; no page model, entity, source row, raw
  parameter map, provider/scope/action IDs, fingerprints, credentials, or hidden data.

## Cap And Output Contract

- Default and tested production cap: **50,000 rows**; nonpositive startup values fail.
- Cap-1 and cap stream; cap+1 returns 422, invokes no stream, sets no content type or
  attachment header, and emits zero CSV bytes.
- Output is UTF-8 RFC 4180 with CRLF, deterministic headers, null/quote/comma/CR/LF
  handling, Unicode, and apostrophe defense for `= + - @` after leading Unicode
  whitespace, control, format, and separator prefixes. Ordinary prefixed data is
  unchanged.
- Filenames contain only fixed surface and UTC date. All six downloads set attachment,
  no-store, nosniff, no-referrer, and exact content type.

## Task Commits

No commits were created, as requested.

## Decisions Made

- Used direct servlet streaming instead of asynchronous `StreamingResponseBody`.
  This is required to finish count/cap validation before response commitment while
  retaining the transaction through cursor closure and client disconnect.
- Kept unsubscribe classification in its canonical Java authority. A fixed-memory,
  disk-backed external sort preserves exact filters/order without collecting the
  capped result in heap or rescanning per output page.
- Resolved analytics ownership once and passed the immutable IDs through workspace
  construction, cap count, and source stream; mismatches fail before output.
- Kept dashboard unavailable values empty in CSV while retaining explicit
  availability/reason; unavailable history is not presented as established zero.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Preserved temporary analytics source compatibility**
- **Found during:** Task 2
- **Issue:** A single expanded analytics schema would have broken the reviewed
  six-column Received and nine-column Cleaned source CSV contract from Plan 13-02.
- **Fix:** Kept fixed per-mode source schemas while dashboard mode uses the expanded
  long-form schema; transferred the one route mapping in the same change.
- **Verification:** `Phase13AnalyticsWebTest` and route-ownership assertions pass.

**2. [Rule 3 - Blocking] Updated superseded async tracer assertions**
- **Found during:** Task 2
- **Issue:** Cap-before-headers requires synchronous servlet streaming, while the
  tracer tests asserted that MockMvc async processing started.
- **Fix:** Assertions now inspect the synchronous response without weakening source
  equality, privacy, headers, formula defense, or provider-free checks.
- **Verification:** All 21 inherited Analytics web tests pass.

**3. [Rule 2 - Missing Critical] Added account labels to the shared Sender predicate**
- **Found during:** Task 2
- **Issue:** The planned Sender schema requires its approved account label, but the
  shared sender fact CTE did not project it.
- **Fix:** Joined the already-authorized account inside `DecisionSurfaceSql` and
  carried its label through the same grouped predicate/order.
- **Verification:** Sender HTML regressions and filtered CSV tests pass.

**Total deviations:** 3 auto-fixed (2 missing critical, 1 blocking).
**Impact on plan:** No feature expansion or privacy weakening; fixes preserve reviewed
contracts and satisfy atomic cap behavior.

## Issues Encountered

- Homebrew `/usr/libexec/java_home -v 21` is unreliable on this host, so every
  verification used explicit `/opt/homebrew/opt/openjdk@21` (OpenJDK 21.0.12).
- Colima Testcontainers required its explicit Docker socket and Ryuk disabled because
  the sidecar cannot mount that host socket path.

## Verification

- Focused export/analytics warning regressions: **21 tests passed**, 0 failures,
  0 errors, 0 skipped.
- Complete Phase 13 plus unsubscribe snapshot coverage: **54 tests passed**,
  0 failures, 0 errors, 0 skipped.
- PostgreSQL 16.14: **2 tests passed** for clean V1-V10 and V9-to-V10 upgrade/index/
  analytics parity.
- Full OpenJDK 21.0.12 suite with Colima: **285 tests passed**, 0 failures,
  0 errors, 0 skipped.
- `git diff --check`: passed. V1-V10 production/test migrations were not edited.

## Residual Issues

- Real private-file download inspection remains part of Plan 13-06 localhost Gmail
  UAT; no provider login or public tunnel was used here.

## User Setup Required

None.

## Next Phase Readiness

- Plan 13-04 can expose these real download/snapshot controls in the final responsive
  Analytics composition without changing route or privacy contracts.
- Plan 13-05 can measure the shared streaming paths and unsubscribe external-sort
  throughput on PostgreSQL.

---
*Phase: 13-analytics-export-and-integrated-product-verification*
*Completed: 2026-08-10*
