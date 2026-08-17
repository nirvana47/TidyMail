---
status: passed
phase: 13-analytics-export-and-integrated-product-verification
plan: "03"
depth: deep
files_reviewed: 35
finding_counts:
  critical: 0
  warning: 0
  info: 0
  total: 0
original_finding_counts:
  critical: 0
  warning: 3
  info: 0
  total: 3
disposition_counts:
  resolved: 3
  open: 0
---

# Plan 13-03 Independent Deep Review

## Findings

### WR-01: Unsubscribe export is neither bounded-memory nor one canonical predicate snapshot

`UnsubscribeCsvSource` obtains the count with one call to
`candidates.query(..., query.resetPage())`, then implements streaming by calling the
same paged service again for every output page
(`src/main/java/com/tidymail/export/UnsubscribeCsvSource.java:47-75`). Each service
call takes a fresh `clock.instant()`, scans and classifies the entire candidate set,
and retains `offset + 25` rows in a priority queue
(`src/main/java/com/tidymail/service/UnsubscribeCandidateService.java:67-110`).

At page 1,999 of a 50,000-row export, that queue retains all 50,000 rows. The export
also repeats the complete classification scan about 2,000 times. It therefore
materializes the full capped result and has quadratic work in the number of output
pages. Because every page uses a new instant, age filters can change membership
between count and later pages even inside the database repeatable-read transaction.
An extra row fails only after headers/body have started, while a missing row is never
detected.

Provide a cursor/iterator that captures one classification instant and keeps memory
independent of result size, or move the exact classification/order into one
stream-capable repository predicate. Add a near-cap memory/query-count test and an age
boundary test that advances the clock between count and consumption.

**Disposition: Resolved.** `UnsubscribeCsvSource` now prepares one disk-backed
snapshot from `UnsubscribeCandidateService.scanForExport`. The scan captures one
`Instant`, visits each repository batch once, applies the canonical classifier and
filters once, and writes fixed 200-row sorted runs. Fixed fan-in external merges
produce the deterministic canonical order with heap use independent of result size.
The prepared snapshot owns count, row stream, and cleanup, including cap rejection
and disconnect paths. `UnsubscribeExportSnapshotTest` covers a 499-row near-cap shape
in three linear repository batches, backing-data mutation after preparation, and a
seven-day boundary with an advancing clock.

### WR-02: Analytics count and stream can use different ownership scopes

The analytics CSV handler resolves ownership through `AnalyticsQueryService`, builds
the workspace through a second `OwnedAccountScopeService.resolve`, then calls
`ExportService`, which resolves it a third time
(`src/main/java/com/tidymail/controller/ExportController.java:129-155`;
`src/main/java/com/tidymail/service/AnalyticsQueryService.java:60-75`;
`src/main/java/com/tidymail/service/WorkspaceModelService.java:91-99`;
`src/main/java/com/tidymail/service/ExportService.java:56-71`).

For source mode, the cap count reads `request.workspace().selectedAccountIds()`, but
the stream reads the separately resolved `ownedAccountIds`
(`src/main/java/com/tidymail/export/AnalyticsCsvSource.java:73-89`). Account
linkage/ownership changing between those pre-transaction resolutions can therefore
make the count and stream scopes differ. More rows trigger an invariant exception
after response commitment; fewer rows silently produce an incomplete CSV. This
violates the locked resolve-once and count/stream snapshot contracts.

Resolve one immutable owned scope and pass it through workspace construction, count,
and stream. The source should reject any request/workspace/scope mismatch. Add a test
whose ownership resolver changes its result between calls and assert only one
resolution occurs.

**Disposition: Resolved.** Analytics export now resolves ownership once through
`AnalyticsQueryService`, passes the immutable IDs to
`WorkspaceModelService.buildResolved`, and calls `ExportService.exportResolved` with
the same IDs. `AnalyticsCsvSource` rejects both query/workspace and
workspace/export-scope mismatches. The regression configures changing resolver
results, verifies one resolution, and verifies mismatched source scope fails before
analytics access.

### WR-03: Formula defense skips only whitespace, not all leading control prefixes

`formulaSafe` walks `Character.isWhitespace` and explicitly handles only an initial
tab or carriage return
(`src/main/java/com/tidymail/service/CsvResponseWriter.java:37-50`). Other ISO control
prefixes, such as NUL, backspace, or U+000E, and format prefixes such as a BOM are
left before `=`, `+`, `-`, or `@`. Consumers that discard those characters can expose
the formula marker. The test covers spaces, tab, and carriage return only
(`src/test/java/com/tidymail/Phase13ExportTest.java:209-221`).

Scan or reject the complete allowlisted leading whitespace/control/format set before
checking the formula marker, while preserving the original cell after adding the
apostrophe. Add table-driven coverage for C0 controls, Unicode separators, and BOM,
plus combinations of multiple prefixes.

**Disposition: Resolved.** `CsvResponseWriter` scans Unicode code points across
whitespace plus control, format, space-separator, line-separator, and
paragraph-separator categories before checking `=`, `+`, `-`, or `@`. It prepends an
apostrophe only when a formula marker follows and otherwise leaves the original cell
unchanged. Table-driven coverage includes C0 controls, DEL/NEL, Unicode separators,
BOM and other format characters, and combined prefixes.

## Verified

- Five route paths are unique; `ExportController` is the sole `/analytics.csv`
  owner. Security requires authentication and adds no download-specific CSRF bypass.
- Sender, Unread, and Review count/stream methods share their canonical typed SQL and
  deterministic order. Account predicates are present in all reviewed repositories.
- Cap+1 is rejected before CSV content type, attachment headers, or stream creation.
  Allowed exports close JDBC streams and use buffered incremental writes.
- CSV output uses UTF-8, CRLF, RFC 4180 quoting, fixed schemas, safe fixed filenames,
  and the required no-store/nosniff/no-referrer headers.
- Snapshot construction is field-by-field into a dedicated record graph. It exposes
  aggregate facts, account count/scope, and `Sender N` aliases without identities,
  source rows, IDs, fingerprints, secrets, provider links, or raw errors.
- Export/snapshot GET tests verify no `MailProvider` or unsubscribe transport calls.
- Production V1-V5 are unchanged from the tracked baseline. Frozen V8/V9 hashes match
  prior evidence. V10 production/test hashes match and the PostgreSQL V9-to-V10
  upgrade preserves schema/data while adding indexes only.

## Verification

- OpenJDK 21.0.12 focused export/analytics regression suites: 21 tests passed,
  0 failures, 0 errors, 0 skipped.
- Complete Phase 13 plus unsubscribe snapshot coverage: 54 tests passed,
  0 failures, 0 errors, 0 skipped.
- PostgreSQL 16.14 Testcontainers migration/ledger suite: 2 tests passed, 0 failures,
  0 errors, 0 skipped.
- Full OpenJDK 21.0.12 suite with PostgreSQL 16 Testcontainers: 285 tests passed,
  0 failures, 0 errors, 0 skipped.
- `git diff --check`: passed.
- V1-V10 production and test migrations were not edited. No commit was created.
