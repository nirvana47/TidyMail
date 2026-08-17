---
phase: 13-analytics-export-and-integrated-product-verification
status: planned
requirements:
  - WORK-05
  - ANLY-01
  - ANLY-02
  - ANLY-03
  - ANLY-04
  - ANLY-05
  - ANLY-06
  - ANLY-07
  - ANLY-08
  - EXPT-01
  - EXPT-02
  - EXPT-03
  - QUAL-02
  - QUAL-06
automated_architecture:
  - pure-unit
  - h2-integration
  - mockmvc
  - postgres-16-testcontainers
  - privacy-canary
  - performance-plan
manual_localhost_blocking: true
public_deployment_blocked_until_uat: true
---

# Phase 13: Validation Strategy

## Validation Goal

Prove that every analytics value comes from the documented owned source facts and
exact UTC predicate; chart buckets equal their drilldown rows; unavailable history is
never converted to zero or inferred; all required exports reuse current server
filters and contain only allowlisted data; all five pages are accessible at 375px;
and a real localhost Gmail cycle succeeds before any public deployment.

Full-suite green is necessary but insufficient. QUAL-06 requires recorded human
localhost evidence.

## Core Invariants

1. Every query resolves principal-owned accounts before reading metadata or ledger
   facts; foreign IDs never broaden to current/all.
2. Date membership is one strict half-open UTC interval; prior period has equal
   duration and no overlap.
3. Aggregate value equals source-row count for the identical metric/bucket predicate.
4. Outcome partition is exact; accepted remains overlapping and is never success.
5. Unsubscribe increments `Unsubscribed` only from a linked `CONFIRMED` V8 event.
6. Legacy/missing history produces partial/unavailable state, never fabricated facts.
7. HTML, CSV, JSON, URLs, filenames, and logs contain no forbidden private/credential
   data or hidden provider IDs.
8. HTML and export routes use the same typed query, account scope, predicate, and
   deterministic ordering.
9. Analytics/export GETs perform zero provider or unsubscribe transport calls.
10. No public deployment/tunnel proceeds until localhost UAT is fully passed.

## Test Layers

### Layer 1: Pure Deterministic Tests

No Spring, database, provider, network, or wall clock.

Table/property tests cover:

- strict date parsing, leap dates, 366-date cap, reversed dates;
- UTC start/end conversion and equal prior intervals;
- Monday week and calendar-month bucket clipping;
- duplicate/unknown enum rejection and canonical query serialization;
- metric numerator/denominator arithmetic and one-decimal display rounding;
- denominator zero, prior zero, negative/corrupt delay exclusion;
- availability combination (`unavailable > partial > available`);
- outcome partition and accepted overlap;
- delay distribution partition and median fixture expectations;
- CSV RFC 4180 quoting, CR/LF, Unicode, nulls, deterministic headers;
- formula injection after leading whitespace for `= + - @ tab CR`;
- safe filenames and bounded reason-to-copy mapping;
- snapshot DTO schema/field allowlist.

Property tests should generate arbitrary nonnegative outcome partitions and arbitrary
date ranges across leap/year/month boundaries.

### Layer 2: H2 Repository, Service, And MVC

Cover fast behavioral semantics:

- owned one/all account resolution and foreign/unsupported rejection;
- canonical `/analytics` round-trip and complete links;
- received, sender, action, outcome, coverage, efficiency, and availability DTOs;
- empty, zero, partial, stale, unavailable, error, and prior-zero templates;
- action filter exception for visible received baseline;
- exact source count/page equality for every metric family;
- stable source pagination and no internal IDs in links;
- opaque sender drilldown ownership/expiry/tamper rejection;
- five CSV endpoints with current non-default filters;
- row-cap 422 with no partial attachment;
- sanitized snapshot aliases and aggregate-only content;
- security headers, authentication, and no provider interaction on GET;
- static HTML accessibility and no decorative controls.

H2 is not evidence for PostgreSQL array ordinal resolution, percentile behavior,
timestamp plans, expression/index use, or production performance.

### Layer 3: PostgreSQL 16 Testcontainers

Run production Flyway migrations against the exact container JDBC URL and assert it.
No skip when Docker/PostgreSQL is unavailable.

Cover:

- clean V1 through final migration and real prior-version upgrade;
- unchanged checksums for frozen V1-V9 migrations;
- Hibernate validation and paired production/test migration parity;
- target ordinal -> immutable scope array -> metadata joins without projected IDs;
- `percentile_cont` median and distribution equality;
- UTC day/week/month edges, leap day, and boundary exclusion;
- identical sender addresses in two accounts remain separate;
- action event and unsubscribe event fact-time membership;
- undo subtraction without erasing forward outcomes;
- legacy rows excluded with exact unavailable count;
- H2/PostgreSQL aggregate, all outcome, availability, action-filter, pagination,
  source-count, and exact boundary parity;
- required indexes and actual-production-SQL
  `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plans with natural planner settings;
- multiple warmed workspace/service/product observations on deterministic data;
- first-data-row streaming, stable order, fixed buffering, and retained-heap behavior
  across result sizes.

### Layer 4: Privacy And Output Canaries

Seed unique canaries in:

- provider message/thread IDs;
- message subject and every stored header array;
- sender/account email and domain;
- action/scope/internal/public IDs and fingerprints;
- unsubscribe URI/destination/fingerprints/nonces;
- token/cookie/authorization/CSRF/idempotency/lease-like values;
- sync/provider/raw exception text;
- body and attachment-shaped fixture fields where available.

Assert canaries are absent from:

- analytics and source HTML;
- all link/form URLs and DOM attributes;
- every CSV body/header/filename;
- sanitized snapshot keys and values;
- captured application/access logs;
- error responses and stack-safe user copy.

Positive assertions prove approved safe sender fields remain in the relevant CSV/HTML,
while snapshot sender/account identities are replaced/omitted.

### Layer 5: Full Regression

Run all Phase 9-12 suites to retain:

- metadata-only idempotent sync and account isolation;
- sender/unread canonical filters and exact materialization;
- unsubscribe SSRF/DNS/redirect/auth safety and honest outcomes;
- staged/immediate shared preflight and durable target ledger;
- conflict, history, partial/pending, undo, and compensation behavior;
- shared shell, CSRF, responsive tokens, and provider-free GET routes.

Update only assertions explicitly superseded by real Phase 13 analytics/export
controls; do not weaken privacy or exactness assertions.

## Suggested Test Suites

| Suite | Purpose |
|---|---|
| `Phase13AnalyticsQueryTest` | UTC parser, canonical state, prior range, buckets |
| `Phase13MetricDefinitionTest` | Numerators, denominators, delay, availability |
| `Phase13AnalyticsRepositoryTest` | Aggregate/source equality and stable details |
| `Phase13AnalyticsWebTest` | Filters, states, links, accessibility, no inert controls |
| `Phase13ExportTest` | Five filter-identical CSVs, cap, escaping, headers |
| `Phase13SnapshotPrivacyTest` | Dedicated schema and recursive privacy canaries |
| `Phase13PostgresMigrationTest` | Upgrade, indexes, array join, percentile, plans |
| `Phase13PerformanceTest` | Representative warm query and streaming budgets |
| `Phase13WorkspaceResponsiveTest` | Five-page static 375px/44px contracts |

## Deterministic Fixture

At minimum:

- two owned Gmail accounts and one foreign account;
- 10,000 metadata rows over 400 UTC dates, including null receipt times;
- UTC midnight, Monday boundary, month/year boundary, and leap-day rows;
- repeated senders within/across accounts plus unknown/long/formula-like labels;
- all action types, staged/immediate modes, and every outcome partition;
- duplicate decisions for the same message;
- succeeded archive/trash/read, partial execution, pending, not-dispatched, undo;
- V8 accepted, confirmed, failed, manual-required, and pending unsubscribe events;
- legacy actions lacking target details;
- complete, partial, running, stale, failed, and never-started sync states;
- forbidden privacy canaries in every non-allowlisted field.

Expected counts and source-row identities must be fixed before performance timing.

## Detailed Requirement Matrix

### WORK-05: No Decorative Controls

Positive:

- Every five-page selector submits a typed GET predicate.
- Every action/export/drilldown has a real route and server fallback.
- Analytics Export and Snapshot download valid files.
- Source, sender queue, history, clear-filter, pagination, and footer links preserve
  canonical state.

Negative:

- Static/template scan finds no `href="#"`, Phase 13 placeholder, unsupported sample
  metric, click-only non-button, or enabled control without route/form behavior.
- JavaScript disabled still supports filters, drilldowns, and downloads.

### ANLY-01: Selected-Range Metrics

Positive:

- Boundary fixtures prove received uses `received_at`; action outcomes use establishing
  event time; confirmed unsubscribe uses V8 event time.
- Triaged deduplicates repeated later decisions.
- Archive/trash/read are gross succeeded target counts; undo remains separate.

Negative:

- Null receipt, accepted-only unsubscribe, failed/pending target, current labels, and
  mutable sender totals do not inflate metrics.
- Foreign-account rows are absent.

### ANLY-02: Grouping And Filters

Positive:

- Day/week/month buckets cover the range exactly once with Monday week starts.
- Account and action filters combine and round-trip in all links.
- Received baseline remains visibly all-mail when action is non-all.

Negative:

- Invalid/duplicate dates/enums/account fail before broad query.
- Grouping never changes overall totals.

### ANLY-03: Prior Comparison

Positive:

- Prior interval ends exactly at current start and has equal duration.
- Same account/action predicate is used.
- Absolute and percent deltas use unrounded source values.

Negative:

- Prior zero emits no infinity/NaN/percent.
- Partial/unavailable prior does not emit a confident comparison.

### ANLY-04: Trend And Source Rows

For every bucket and both series:

```text
aggregate value == source count == complete source-page union count
```

Test first/last clipped buckets, known zero, partial, page boundaries, stable ties,
filter intersections, and CSV source count. Received and cleaned fact-time labels
must be visible.

### ANLY-05: Top Senders

Positive:

- Ranking uses selected received rows, count descending, stable sender tie-break.
- Same address in two accounts remains separate.
- Opaque link opens exactly the corresponding owned filtered sender queue.

Negative:

- Unknown sender has no fake link.
- Token tamper/expiry/foreign owner fails closed.
- Raw sender address is absent from analytics URL and logs.

### ANLY-06: Outcomes And Coverage

Assert exact integer `N / D` for:

- requested partition and separate accepted overlap;
- staged executed targets / all executed targets;
- cohort decisions / received cohort;
- confirmed unsubscribe / eligible initiated unsubscribe.

Denominator zero is unavailable. Manual-required-only unsubscribe is excluded and
counted. Action-row weighting cannot replace target weighting.

### ANLY-07: Efficiency

- Inbox reduction subtracts only confirmed inverse target successes and retains
  forward history.
- Cleanup rate deduplicates successfully cleaned messages among triaged messages.
- Median and five delay buckets use the same eligible sample.
- Negative/null delay pairs are excluded and disclosed.
- Confidence numerator excludes verification-pending and partitions requested.
- Ratios may exceed 100% only where contract permits (event-window reduction), with
  explanatory copy.

### ANLY-08: Honest Unavailable History

Fixtures cover:

- range before metadata indexing;
- metadata complete but ledger absent;
- mixed all-account coverage;
- running/partial/failed/stale sync;
- legacy actions without targets/events;
- accepted/pending but unconfirmed unsubscribe;
- no eligible denominator.

Assert distinct `0`, `partial`, and `unavailable` DTO/template/CSV/snapshot values.
No current total is used as a historical fallback.

### EXPT-01 And EXPT-02: Five Current-Filter CSVs

For Senders, Unread, Unsubscribe, Review History, and Analytics:

1. build a non-default combined HTML query;
2. capture visible full-result count and boundary identities;
3. request CSV with the same normalized state;
4. assert exact row count/content predicate and deterministic order;
5. assert foreign and near-match fixtures absent;
6. assert pagination does not limit export;
7. assert over-cap emits 422 and zero CSV bytes.

Spy/architecture tests prove HTML and CSV call the same parser/resolver/predicate
factory. Formula-like sender/target values remain text when opened in a spreadsheet.

### EXPT-03: Sanitized Snapshot

Positive:

- Version, UTC generated time, normalized non-identifying filter state, metric
  definitions, integer counts, denominators, availability, trend, outcomes, coverage,
  distribution, and anonymous sender ranks are present.

Negative:

- Recursive key/value canary scan finds no account/sender identity, source row,
  provider/internal/public ID, fingerprint, subject/header/body/attachment, URI,
  token/cookie/credential, raw error, or arbitrary entity property.
- Snapshot is not a serialization of the page model.

### QUAL-02: 375px And Touch

Automated:

- viewport meta, one main/h1, skip link, labels, fieldsets, captions/headers;
- CSS media contracts for all five pages;
- 44px minimum interactive dimensions;
- wrapping/contained overflow and no fixed min-width causing page scroll;
- focus-visible, reduced-motion, non-color statuses;
- semantic chart data and accessible download names.

Manual:

- real 375x812 viewport on all five pages;
- no page-level horizontal scrolling;
- every action/filter/download reachable and not overlapped;
- keyboard traversal after HTMX/full-page updates;
- 200% zoom and long-label fixtures;
- screen-reader spot check of metric definitions and status changes.

### QUAL-06: Real Localhost Cycle

See the blocking UAT below. Automated provider mocks cannot satisfy this requirement.

## Performance And Query-Plan Gates

On the deterministic PostgreSQL fixture after warmup:

- workspace shell response under 1 second;
- each default analytics aggregate request under 2 seconds;
- each filtered source count/page under 2 seconds;
- top-sender and median/distribution query under 2 seconds;
- export starts streaming promptly and does not materialize all rows in a list;
- account/time predicates appear in plans before bucket/group operations;
- no foreign-account scan/result and no unbounded sequential scan caused by a missing
  leading account/time index.

Use generous CI variance handling around the product budgets, but retain deterministic
plan/index assertions. Performance failures cannot be waived by reducing fixture
correctness.

## Response And Security Acceptance

All export/snapshot responses:

```text
Content-Disposition: attachment; filename="safe-name"
Content-Type: text/csv; charset=UTF-8   (CSV)
Content-Type: application/json          (snapshot)
Cache-Control: no-store
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
```

Require authentication. GET downloads do not require CSRF but perform no mutation.
Foreign/tampered scope returns 404/400 without existence detail. Exceptions map to
safe bounded copy and never echo raw query/private values.

## Integrated Localhost UAT

### Preconditions

- Java 21, Postgres 16, Docker/Colima, and the intended local build are running.
- OAuth origin is exactly `http://localhost:8080`; redirect URI is exactly
  `http://localhost:8080/login/oauth2/code/google`.
- Real secrets remain only in local environment; no screenshots/logs expose them.
- Start from a known test Gmail account with reversible low-risk messages.
- Public tunnel/deployment is disabled.

### Gate A: Authentication, Sync, And Shell

1. Authenticate at localhost and verify the intended account scope.
2. Queue metadata sync and reach at least 500 idempotent indexed records.
3. Verify loading -> ready/partial transitions and no provider calls on page GET.
4. Open Senders, Unread, Unsubscribe, Review, and Analytics at desktop and 375px.
5. Record indexed count and UTC coverage boundary without recording account email.

Pass: all five routes work, shell/account/queue state agrees, privacy copy is present,
and 375px has no page scroll or sub-44px control.

### Gate B: Both Execution Policies

1. Create a low-risk staged archive/read action from an exact scope.
2. Confirm it appears in the correct Review action/account group.
3. Approve it and record requested/accepted/succeeded/failed/pending counts.
4. Create a separate low-risk immediate action with explicit opt-in.
5. Verify both modes show the same preflight/scope safety and durable history shape.

Pass: no shortcut behavior, provider request equals exact scope, and results are
truthful.

### Gate C: Unsubscribe Alternatives

1. Inspect one eligible safe automatic candidate without exposing its raw destination.
2. Execute only with explicit consent; treat 2xx as accepted/pending unless confirmed.
3. Exercise a mailto/account-page/manual alternative without server-side fake success.
4. Verify protected/unsafe option remains blocked.

Pass: method and outcome wording match Phase 11/12 safety and analytics confirmation
does not overclaim.

### Gate D: Analytics Consistency

1. Use non-default date, account, action, group, and prior comparison.
2. Check every headline definition and denominator.
3. Select at least one received and one cleaned bucket; count all source pages.
4. Confirm each bucket equals its source total.
5. Open a top sender queue and verify sender/account/range context.
6. Open action/history drilldowns and verify exact filter intersection.
7. Select a pre-coverage range and verify unavailable, not zero.
8. Verify accepted/pending unsubscribe is not counted as confirmed.

Pass: values, drilldowns, availability, and comparison are internally consistent.

### Gate E: Exports And Snapshot

1. Download filtered Senders, Unread, Unsubscribe, Review History, and Analytics CSV.
2. Verify headers, row counts, non-default filters, UTF-8, quoting, and formula-safe
   cells.
3. Search files for local privacy canaries and forbidden fields.
4. Download snapshot and verify anonymous account/sender treatment and aggregate-only
   schema.
5. Record filenames, byte sizes, and SHA-256 checksums; do not commit private files.

Pass: five CSVs match visible server filters and all six downloads pass privacy checks.

### Gate F: Undo And Retained History

1. Undo one eligible successful archive/trash/mark-read action.
2. Verify only succeeded forward targets are inverted.
3. Verify History retains forward and inverse facts.
4. Refresh Analytics and verify gross forward counts remain while net reduction
   reflects confirmed inverse success according to range.

Pass: no history rewrite and analytics follows documented event semantics.

### Gate G: Accessibility And Deployment Decision

1. Keyboard traverse all five pages and dynamic result/focus transitions.
2. Test 375x812, long labels, and 200% zoom.
3. Spot-check screen-reader names for filters, metrics, charts, statuses, source links,
   and downloads.
4. Confirm no inert mock control remains.
5. Mark every UAT gate pass/fail and list unresolved defects.

Pass: all gates pass. Any failure keeps deployment status `BLOCKED`.

## UAT Evidence Artifact

Implementation should produce a local/redacted verification record containing:

- UTC execution time;
- git commit or dirty-worktree identity;
- Java/Postgres/app version;
- browser and viewport;
- account count only;
- redacted canonical query state;
- expected/actual metric and source counts;
- action outcome counts;
- export/snapshot filename, bytes, SHA-256, and privacy result;
- accessibility observations;
- pass/fail per gate and defect references.

Never commit downloaded CSV/JSON, screenshots containing private mail/account data,
tokens, cookies, provider IDs, raw headers, or bodies.

## Suggested Verification Commands

Resolve Homebrew Java 21 and the Colima socket explicitly:

```bash
export JAVA_HOME="${JAVA_HOME_21:-$(brew --prefix openjdk@21)}"
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"

"$JAVA_HOME/bin/java" -version

mvn -q \
  -Dtest=Phase13AnalyticsQueryTest,Phase13MetricDefinitionTest,Phase13AnalyticsRepositoryTest \
  test
mvn -q \
  -Dtest=Phase13AnalyticsWebTest,Phase13ExportTest,Phase13SnapshotPrivacyTest,Phase12ReviewWebTest \
  test
mvn -q \
  -Dtest=Phase13PostgresMigrationTest,Phase13PerformanceTest,Phase13WorkspaceResponsiveTest \
  test
mvn -q test
! rg -n '(failures|errors|skipped)="[1-9][0-9]*"' \
  target/surefire-reports/TEST-*.xml
git diff --check
```

Then execute the localhost UAT gates. No command or mock test substitutes for them.

## Requirement Traceability

| Requirement | Primary evidence |
|---|---|
| WORK-05 | Web/static control audit plus JS-disabled manual flow |
| ANLY-01 | Metric definition/repository/PostgreSQL boundary tests |
| ANLY-02 | Query parser, bucket, account/action filter parity |
| ANLY-03 | Prior-range and unavailable/zero comparison matrix |
| ANLY-04 | Aggregate/source/page/CSV equality |
| ANLY-05 | Sender ranking and opaque owned queue drilldown |
| ANLY-06 | Outcome partition and explicit coverage `N / D` |
| ANLY-07 | Net reduction, cleanup, median/distribution, confidence |
| ANLY-08 | Coverage/legacy/sync/unsubscribe unavailable matrix |
| EXPT-01 | Five CSV endpoint suites and manual downloads |
| EXPT-02 | Shared parser/predicate architecture and row parity |
| EXPT-03 | Dedicated DTO plus recursive canary and manual inspection |
| QUAL-02 | Static responsive suite plus 375px/zoom/keyboard UAT |
| QUAL-06 | Gates A-G on real localhost Gmail |

## Completion Gate

Phase 13 and milestone v1.1 are complete only when:

- all 14 mapped requirements have named positive and negative evidence;
- metric arithmetic and availability match the locked definitions;
- every bucket/metric drilldown equals its source predicate;
- PostgreSQL migrations, indexes, plans, and budgets pass with zero skips;
- all five CSVs reuse current server filters and privacy allowlists;
- sanitized snapshot contains no identity, source-row, content, header, or credential
  data;
- all five pages pass 375px, 44px, keyboard, and 200% zoom checks;
- full Phase 9-12 regression is green;
- real localhost Gmail UAT Gates A-G pass with redacted evidence;
- public deployment/tunneling has not occurred before that pass.

## Planned Validation Status

| Area | Status |
|---|---|
| Requirement mapping | Complete |
| Metric/source invariants | Executed; green |
| H2/PostgreSQL architecture | Executed on PostgreSQL 16.14; green |
| Export/snapshot privacy | Executed cross-output/log/error/CSRF matrix; green |
| Performance/index plans | Executed with natural planner and repeated observations; green |
| Accessible responsive UI | Specified |
| Localhost UAT | Specified; blocking |
| Execution evidence | 52 focused and 301 full tests; zero failures/errors/skips |
