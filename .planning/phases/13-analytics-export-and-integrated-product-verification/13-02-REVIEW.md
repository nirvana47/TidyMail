---
status: clean
phase: 13-analytics-export-and-integrated-product-verification
plan: "02"
depth: deep
files_reviewed: 38
finding_counts:
  critical: 0
  warning: 0
  info: 0
  total: 0
closure_counts:
  prior_critical_closed: 1
  prior_warning_closed: 6
  remaining: 0
  new: 0
---

# Plan 13-02 Independent Deep Review

## Findings

### CR-01: Real undo successes never reduce Inbox reduction

The analytics query counts inverse success only when an undo target has
`target_status='SUCCEEDED'`
(`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:74-80`).
The V9 undo implementation deliberately persists a successful inverse target as
`REVERTED`, not `SUCCEEDED`
(`src/main/java/com/tidymail/service/UndoService.java:240-247`). Therefore
`inverseSucceeded` is zero for every real successful undo and Inbox reduction remains
gross after undo, violating the locked gross-forward/net-reduction contract.

The Phase 13 fixture masks the defect by inserting an impossible `SUCCEEDED` undo
target directly (`src/test/java/com/tidymail/Phase13AnalyticsRepositoryTest.java:196-198`).
In addition, the query accepts every undo action type under `action=all`; after fixing
the status, successful MARK_READ inverses would incorrectly subtract from an
ARCHIVE+TRASH numerator. Count confirmed `REVERTED` undo targets only for ARCHIVE and
TRASH, and cover the real `UndoService` persistence shape plus a MARK_READ undo.

### WR-01: Cleaned trend uses received availability

Each trend bucket computes a ledger-derived cleaned count but assigns only the
received metadata availability to the combined bucket
(`src/main/java/com/tidymail/service/AnalyticsService.java:82-94`). A range with
complete metadata but no V9 ledger history consequently renders cleaned zero as
available, while opening its cleaned source recomputes ledger availability and says
the same bucket is unavailable
(`src/main/java/com/tidymail/service/AnalyticsService.java:172-180`).

Represent received and cleaned availability independently. A cleaned bucket must use
ledger and unsubscribe-linkage coverage and must not expose a known-zero state before
that source is complete.

### WR-02: Cleaned membership is deduplicated before the selected interval

`cleaned_ranked` selects the first cleanup over all history and only afterward do
`cleanedCount` and source queries apply the requested range
(`src/main/java/com/tidymail/repository/AnalyticsLedgerSql.java:82-115`;
`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:342-354`).
If a message was archived in an earlier interval and trashed in the selected
interval, the current cleaned trend is zero. The error is also action-sensitive:
`action=trash` drops the in-range trash because the lifetime first row remains
ARCHIVE.

Apply the half-open fact-time and action predicate to candidate outcomes before
deduplicating account/message for the selected aggregate. Add repeat-cleanup fixtures
across adjacent ranges and action filters.

### WR-03: Cleaned aggregate and source rows diverge when metadata is incomplete

The aggregate counts `cleaned_facts` without requiring a metadata row
(`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:342-355`), but
the page and stream inner-join `message_metadata`
(`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:384-395`).
There is no V9 foreign key from an immutable target to metadata, so a missing metadata
row yields `aggregate > complete source union`. A retained metadata row with null
`received_at` is worse: it is counted, then the row mapper dereferences a null
timestamp (`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:424-434`).

Use one source-capable base predicate for count/page/stream, and classify missing or
untimestamped metadata as an explicit exclusion/availability reason. Test both cases.

### WR-04: Legacy gap detection uses action creation time, not metric fact time

`legacyGapCount` considers only legacy/detail-less actions whose `created_at` falls
inside the selected interval
(`src/main/java/com/tidymail/repository/AnalyticsCoverageRepository.java:42-70`).
Action metrics, however, are placed by decision, completion, and unsubscribe outcome
times. A migrated legacy action created before the range but applied during it has no
V9 target/event fact, is omitted from the metric, and does not mark the range partial.

Derive the conservative legacy boundary from all pre-V9/detail-less history, using
the best retained applicable fact time such as `applied_at`, or mark subsequent
history partial when exact placement cannot be established. Cover a pre-range legacy
creation with an in-range retained outcome time.

### WR-05: A maximum-range dashboard issues 366 cleaned count queries

The received series was changed to one grouped query, but the dashboard still calls
`ledgerRepository.cleanedCount` once inside every bucket iteration
(`src/main/java/com/tidymail/service/AnalyticsService.java:78-95`). The accepted
366-day shape therefore performs 366 repeated full CTE pipelines for cleaned data.
The query-count test spies only `AnalyticsReceivedRepository` and cannot detect this
regression (`src/test/java/com/tidymail/Phase13AnalyticsWebTest.java:274-285`).

Add a bounded grouped cleaned query using the same fact predicate and assert one
ledger aggregate call for day/week/month maximum-range dashboards.

### WR-06: PostgreSQL tests do not execute the analytics SQL dialect

The PostgreSQL test migrates V9 to V10 and checks index definitions only
(`src/test/java/com/tidymail/Phase13PostgresMigrationTest.java:31-102`). It never
executes target array ordinal resolution, ledger aggregates, source parity, or
`percentile_cont`; all behavioral assertions run only on H2. This leaves the required
H2/PostgreSQL V10 equivalence unproved despite separate SQL branches in
`AnalyticsLedgerRepository`.

Run the same deterministic ledger fixture and expected projections against
PostgreSQL 16, including array lookup, as-of outcomes, confirmed unsubscribe, undo,
median/distribution, foreign-account exclusion, and aggregate/source equality.

## Disposition

- **CR-01 resolved:** inverse reduction now counts only real `REVERTED` undo targets
  for ARCHIVE/TRASH. MARK_READ undo is excluded. Both a direct ledger fixture and the
  real `UndoService` persistence path assert the contract.
- **WR-01 resolved:** trend buckets carry independent received and cleaned
  availability. Cleaned availability composes ledger, legacy, unsubscribe-linkage,
  and timestamped-metadata coverage and is used by HTML, source, and CSV output.
- **WR-02 resolved:** half-open interval and action predicates are applied to cleanup
  candidates before account/message dedupe. Adjacent-range ARCHIVE/TRASH and
  action-filter fixtures cover repeat cleanup.
- **WR-03 resolved:** cleaned count, page, and stream use one metadata-capable base.
  Missing and null-time metadata are excluded consistently and reported as
  `CLEANED_METADATA_GAP`.
- **WR-04 resolved:** legacy detection uses retained `applied_at`, `completed_at`, or
  `undone_at`; actions with no retained fact time conservatively mark all subsequent
  history partial.
- **WR-05 resolved:** day/week/month trends use one bounded grouped ledger aggregate
  call per dashboard. Maximum-range query-count coverage asserts 366/54/13 buckets
  and zero per-bucket `cleanedCount` calls.
- **WR-06 resolved:** the deterministic analytics fixture now runs against
  PostgreSQL 16.14 and covers array ordinal lookup, as-of outcomes, confirmed
  unsubscribe, REVERTED undo, MARK_READ exclusion, grouped cleaned trends,
  `percentile_cont`, foreign-account isolation, and aggregate/page/stream parity.

## Verification

- Independent focused repository/web/real-undo/PostgreSQL closure matrix: 35 tests,
  0 failures, 0 errors, 0 skipped.
- The PostgreSQL subset ran 2 tests against Testcontainers PostgreSQL 16.14,
  including production array lookup, `percentile_cont`, grouped trends, real
  `REVERTED` undo semantics, foreign-account exclusion, and aggregate/page/stream
  parity.
- Runtime: explicit OpenJDK 21.0.12 from the versioned Homebrew JDK path.
- PostgreSQL: Testcontainers `postgres:16-alpine` reported PostgreSQL 16.14 through
  `DOCKER_HOST=unix:///Users/bytedance/.colima/default/docker.sock`; Ryuk was disabled
  because Colima cannot mount its host socket path into the sidecar.
- Production and test V10 migrations remain byte-equivalent and index-only.
- V1-V9 were not edited. V8/V9 production and test hashes retain their pinned values;
  V6/V7 remain untracked in the pre-existing worktree.
- Provider-free analytics GETs, account authorization, safe projections, and
  foreign-account exclusion pass.
- `git diff --check`: passed.
- Final closure status: 1 prior critical and 6 prior warnings closed; 0 remaining,
  0 new.
- No commit was created.
