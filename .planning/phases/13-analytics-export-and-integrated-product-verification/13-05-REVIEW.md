---
phase: 13-analytics-export-and-integrated-product-verification
plan: "05"
reviewed: 2026-08-10
resolved: 2026-08-10
closure_reviewed: 2026-08-10
status: passed
verdict: approved
controls_passed: 15
findings:
  blocker: 0
  warning: 0
  resolved: 9
  total: 0
---

# Plan 13-05 Independent Review

## Verdict

**APPROVED.** B1-B6 and W1-W3 are resolved with stronger executable evidence.
Production and test V1-V10 migrations remain unchanged. An independent closure
run of the seven Plan 13-05 suites plus the inherited CSRF suite passed on
OpenJDK 21.0.12 and PostgreSQL 16.14 with 61 tests and zero failures, errors,
or skips. No false-positive closure evidence or new Plan 13-05 finding remains.

## Resolved Findings

| ID | Resolution | Executable evidence |
|---|---|---|
| B1 | Real repository SQL and bindings are captured from the spied production `NamedParameterJdbcTemplate`, explained with natural planner settings, and parsed as JSON. Received paths assert relation-local account/time/index conditions; ledger paths assert bounded fact relations plus account and fact-time predicates in the executed plan tree. | `Phase13PerformanceTest#representativeAnalyticsAreCorrectIndexedAndWithinProductBudgets` covers received/cleaned trend and source, outcomes, unsubscribe, top sender, median, and all five delay buckets. |
| B2 | The 10,503-row/401-date fixture now includes duplicate decisions, partial/failed/pending/not-dispatched targets, undo, legacy no-detail, all V8 outcomes, complete/running/partial/failed/stale/never-started sync states, and foreign isolation. | Fixed counts are asserted before timing and plan collection in `Phase13PerformanceTest`. |
| B3 | The gate times the real `WorkspaceModelService.build` path and production repository operations with two warmups and five observations each, including outcomes and the five-bucket delay distribution. | Reported maximums: workspace 19 ms; aggregate 19 ms; outcomes 12 ms; received count/page 10/16 ms; cleaned count/page 15/16 ms; top sender 28 ms; median/distribution 87 ms. |
| B4 | Streaming proof detects the first complete data row, verifies it follows one source consumption, checks all row identities/order, closes the stream, and compares fixed-buffer/retained-heap behavior at 2,000, 20,000, and 50,000 rows. | `Phase13PerformanceTest#firstCsvDataRowPrecedesFullConsumptionAndOrderBufferAndHeapStayBounded`. |
| B5 | Shared H2/PostgreSQL parity now covers received first/end boundaries, every outcome counter, undo, unsubscribe confirmed/eligible/manual/linkage facts, legacy/null coverage facts, two-page stable order, action filters, source counts, delays, median, and buckets. | `Phase13PostgresMigrationTest#h2AndPostgresMetricsSourcesOutcomesAndOrderingAreEqual` asserts fixed values before dialect equality. |
| B6 | Owned private canaries and one foreign-authority canary fixture are scanned across five pages, source HTML, DOM/URLs, five CSVs, snapshot, headers/filenames, invalid-query errors, and captured logs. Forced repository errors return fixed private-free 503 responses. The complete POST route inventory rejects missing and invalid CSRF before controller writes/provider I/O. | `Phase13SnapshotPrivacyTest`, `Phase13AnalyticsWebTest#repositoryFailureHasBoundedPrivateFreeResponseHeadersAndLogs`, and `Phase12ReviewWebTest#ACTN_05_allMutationRoutesRequireAuthenticationAndCsrfBeforeIo`. |
| W1 | Commands resolve Homebrew Java 21 and export both required Colima variables. | `13-VALIDATION.md` and `13-05-SUMMARY.md`. |
| W2 | Counts were re-read from Surefire XML. | Focused: 7 reports, 52 tests, 0/0/0; full: 59 reports, 301 tests, 0/0/0. |
| W3 | Every Phase 9-13 PostgreSQL suite now uses `disabledWithoutDocker = false`. | Static annotation scan plus full zero-skip run. |

## Query-Plan Evidence

Thirteen actual production statements are explained with
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`. `enable_seqscan` remains `on`.
Large `message_metadata` received/source/top-sender paths use
`idx_message_metadata_account_received` with account and half-open receipt-time
conditions. Ledger plans prove account restriction and fact-time placement over
targets/events/executions before aggregate operations. The small five-row
unsubscribe outcome relation may naturally use a bounded sequential scan; its
time filter and account-bound attempt index join are both asserted in the parsed
tree.

## Privacy And Security Evidence

Forbidden provider IDs, subjects/header arrays, internal authority UUIDs,
scope/target/token/idempotency/lease/nonce fingerprints, destinations, raw errors,
and body/attachment-shaped values are absent from owned HTML/attributes/URLs,
all downloads, errors, and captured logs. Approved sender/account display fields
remain only on their intended authenticated HTML/CSV surfaces; snapshot identities
remain aliased/omitted. Access logging is disabled by configuration.

## Verification

```bash
export JAVA_HOME="${JAVA_HOME_21:-$(brew --prefix openjdk@21)}"
export PATH="$JAVA_HOME/bin:$PATH"
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"

mvn -q \
  -Dtest=Phase13PostgresMigrationTest,Phase13PerformanceTest,Phase13AnalyticsRepositoryTest,Phase13MetricDefinitionTest,Phase13AnalyticsWebTest,Phase13ExportTest,Phase13SnapshotPrivacyTest,Phase12ReviewWebTest \
  test
git diff --check
```

- Independent closure run: **8 reports, 61 tests**, zero failures/errors/skips.
- Plan 13-05 subset within that run: **7 reports, 52 tests**, zero
  failures/errors/skips.
- Prior full regression recorded in the execution summary: **59 reports,
  301 tests**, zero failures/errors/skips.
- Runtime: OpenJDK **21.0.12**, PostgreSQL **16.14**, Colima.
- Closure-run maximum timings: workspace **18 ms**, aggregate **15 ms**,
  outcomes **12 ms**, received count/page **11/16 ms**, cleaned count/page
  **15/16 ms**, top sender **29 ms**, median plus five delay buckets **108 ms**.
- Frozen V1-V10 production/test migrations: unchanged.
- `git diff --check`: passed.
- Commits: none.

## Remaining Gate

No blocker or warning remains for Plan 13-05. Plan 13-06 real localhost
Gmail/browser UAT remains a separate gate for QUAL-06 and public deployment.
No provider mutation, tunnel, deployment, or commit was performed.
