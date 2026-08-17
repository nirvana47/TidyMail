---
phase: 13-analytics-export-and-integrated-product-verification
status: complete
researched: 2026-08-09
---

# Phase 13: Analytics, Export, And Integrated Product Verification - Research

## Research Question

How should TidyMail derive reproducible analytics and private exports from the current
metadata/scope schemas and the Phase 12 ledger while preserving the existing
server-rendered workspace, account ownership, localhost performance, and truthfulness
constraints?

## Executive Finding

No analytics warehouse or frontend chart framework is needed. PostgreSQL can compute
the required bounded aggregates from:

1. `message_metadata` for received cohorts and sender rankings;
2. Phase 12 action executions, target outcomes, and append-only events for decisions,
   outcomes, cleanup, review mode, and undo;
3. V8 unsubscribe events for confirmed versus accepted/pending semantics;
4. `sync_progress` plus ledger coverage for availability boundaries.

The implementation should introduce a canonical immutable `AnalyticsQuery`, a
repository-owned fact vocabulary, a service that returns values with provenance and
availability, and thin MVC/export adapters. The same query/predicate methods must
serve HTML, source drilldowns, CSV, and snapshots. This is safer than maintaining
separate dashboard and export SQL and avoids stale denormalized analytics.

## Inherited Explorer Findings

- The analytics mock has the intended compact composition: range/group/account/action/
  compare controls, four headline metrics, received-versus-cleaned trend, sender
  ranking, action outcomes, efficiency, delay distribution, coverage, export, and
  snapshot.
- Mock numbers, `href="#"`, button-only selectors, `Delete` terminology, remote
  Tailwind/Lucide assets, and implied confirmed unsubscribe counts are placeholders.
- `analytics.html` is currently a deliberate Phase 13 capability placeholder inside
  the Phase 9 shared shell.
- Phase 9 deliberately stores `received_at` separately from `first_seen_at`, enabling
  honest receipt time while revealing that history before initial indexing may be
  incomplete.
- `message_metadata` already has useful account/time, sender/time, domain/time, unread/
  time, and sync-time indexes. It contains metadata-only fields but also private
  headers and provider IDs that must never be serialized generically.
- `ActionScope.messageIds` is the exact immutable provider-ID authority. Resolving a
  Phase 12 target ordinal to that array inside SQL/service code is acceptable;
  copying IDs into analytics or exports is not.
- V8 unsubscribe facts explicitly distinguish `ACCEPTED`, `CONFIRMED`, `FAILED`,
  `MANUAL_REQUIRED`, and `VERIFICATION_PENDING`. Only `CONFIRMED` supports an
  `Unsubscribed` count.
- Phase 12 planning locks normalized target outcomes, append-only events, requested
  partition counts, mode, safe reasons/sources, history filtering, and legacy
  unavailable labels. Phase 13 should consume this contract, not current legacy
  `UserAction.affectedCount` or blanket `APPLIED`.
- The shared workspace model already resolves owned numeric account IDs/`all`, labels
  stale/partial/error states, and keeps GET routes provider-free.
- The user preference and prior deployment decision require full manual localhost
  validation before public tunneling or deployment.

## Recommended Architecture

### Canonical Query Objects

Add immutable server types along these lines:

```java
record AnalyticsQuery(
    LocalDate from,
    LocalDate to,
    AccountSelection account,
    AnalyticsAction action,
    BucketGrouping grouping,
    Comparison comparison) {
    Instant startInclusive();
    Instant endExclusive();
    DateRange priorRange();
}

record AnalyticsSourceQuery(
    AnalyticsQuery analytics,
    SourceSeries series,
    Instant bucketStart,
    Instant bucketEnd,
    int page) {}
```

Parsing must reject duplicate recognized parameters. Use `LocalDate` with strict ISO
resolution and `Instant` UTC boundaries. Do not accept browser-generated timestamps.
Normalize enums once and generate every URL from the typed object.

Each existing surface should expose its typed query to an export service rather than
passing a raw parameter map:

```java
interface FilteredCsvSource<Q, R> {
    Stream<R> streamOwned(Q query, List<Long> ownedAccountIds);
    CsvSchema<R> schema();
}
```

### Fact Vocabulary

Use explicit records rather than maps:

```java
record MetricValue(
    MetricId id,
    Long value,
    Long numerator,
    Long denominator,
    Availability availability,
    String reason) {}

record TrendBucket(
    Instant start,
    Instant end,
    long received,
    long cleaned,
    Availability availability) {}

record AnalyticsCoverage(
    Instant receivedReliableFrom,
    Instant actionReliableFrom,
    Instant reliableThrough,
    Availability received,
    Availability ledger) {}
```

`Availability` should be a bounded enum: `AVAILABLE`, `PARTIAL`, `UNAVAILABLE`.
Reasons should be bounded safe codes mapped to copy, not database/provider messages.

### Repository Separation

Recommended repository methods:

- received count, bucket counts, received source count/page;
- top sender aggregate and opaque drilldown resolution;
- first-decision distinct count and delay sample/distribution;
- cleanup/outcome aggregates by action event time;
- target source count/page;
- staged/executed and confidence aggregates;
- unsubscribe confirmed/initiated aggregates;
- legacy/detail-gap counts;
- earliest/latest trustworthy boundaries.

Keep SQL organized by source authority. Avoid one Cartesian dashboard query joining
metadata, scopes, events, and unsubscribe events before aggregation; it risks target
fan-out and inflated counts. Aggregate each fact family to its intended grain first,
then combine bounded result records in the service.

### Distinct Target Identity

Phase 12 target rows intentionally avoid provider-ID duplication. For metrics that
must deduplicate messages or join to receipt time:

1. authorize account IDs before query;
2. join execution target to action and immutable scope;
3. resolve `scope_ordinal` to the scope array in PostgreSQL (one-based array index is
   `scope_ordinal + 1`);
4. join metadata on account plus provider message ID;
5. aggregate/deduplicate in the database;
6. project no provider ID.

H2 lacks exact PostgreSQL array/percentile behavior. Keep a behaviorally equivalent
H2 query or service fallback for fast tests, but make PostgreSQL 16 the production
truth and parity-test boundary values.

### Median And Distribution

PostgreSQL `percentile_cont(0.5) within group (order by delay)` is appropriate for
median. Compute delay from first durable decision event minus metadata receipt time.
Exclude null and negative pairs, return the excluded count, and show the sample size.
Distribution should use the same eligible CTE so bucket totals equal the sample.

### Comparison

Run the same aggregate functions with a different `DateRange`, not separate prior
SQL. Comparison should be a pure result transformation:

```text
prior == 0 -> no percentage; retain absolute delta
either side partial -> comparison partial
either side unavailable -> comparison unavailable
otherwise -> BigDecimal percent, one display decimal
```

Do not infer a prior value from current sender statistics.

### Drilldown Consistency

Aggregate and detail paths should share a predicate builder or named base CTE.
Every bucket response should include a server-generated source URL carrying exact
bucket boundaries. Tests should assert:

```text
bucket.value == sourceCount(same query, series, bucket)
source CSV count == sourceCount(same typed predicate)
```

For action-derived details, a Review History link is valid only if History can express
the exact predicate. Otherwise use the analytics source table. This prevents a
nominal drilldown from showing a broader dataset.

### Opaque Sender Drilldown

The existing sender filters can express an exact sender, but placing a private address
in an analytics URL is avoidable. Generate a short-lived signed token bound to owner,
account, sender key, range, and expiry. Store no raw sender in logs; verify signature
and ownership, then construct the Sender query server-side. This token is a locator,
not authorization.

## Metric Feasibility

| Metric family | Required source | Feasibility / caveat |
|---|---|---|
| Received/top senders | `message_metadata.received_at` | Available only for indexed timestamped metadata |
| Triaged/delay | first Phase 12 decision target event + scope ordinal + metadata | Unavailable for legacy actions or missing metadata join |
| Archive/trash/read | Phase 12 succeeded forward target event | Do not use mutable labels |
| Unsubscribed | linked V8 `CONFIRMED` event | Accepted/pending must remain separate |
| Outcomes/confidence | Phase 12 execution partition counts/targets | `accepted` overlaps and is not partition member |
| Review coverage | Phase 12 mode + executed targets | Target weighted, not action-row weighted |
| Cleanup coverage | received cohort + first decisions through range end | Clearly cohort-based |
| Inbox reduction | succeeded removal outcomes minus succeeded inverse outcomes | Event-window throughput; can exceed received denominator |
| Cleanup rate | distinct successful cleanup among distinct triaged | Requires server-side target identity resolution |

## Export Design

Use `StreamingResponseBody` or direct servlet output with a small buffered writer.
Apache Commons CSV is not currently required; a small dedicated RFC 4180 writer is
reasonable if it is table-tested for quotes, CR/LF, Unicode, nulls, and formula
injection. Do not concatenate rows manually in controllers.

Recommended flow:

```text
authenticate
-> parse typed query
-> resolve owned account IDs
-> count with same predicate
-> reject over cap
-> set no-store attachment headers
-> stream allowlisted projection in deterministic order
```

Never reflect over entities or dump JSON columns/arrays. Each surface owns a fixed
schema and safe projection. Export tests need secret/private canaries in every
forbidden field and a positive assertion that expected visible values survive.

CSV cap should be configurable, defaulting conservatively to 50,000 rows. Analytics
aggregate CSV is naturally bounded. Over-cap behavior must be 422 before attachment
headers and must not emit a partial file.

## Snapshot Design

Use a versioned allowlist:

```java
record SanitizedAnalyticsSnapshot(
    int schemaVersion,
    Instant generatedAt,
    SnapshotFilter filter,
    SnapshotAccountScope accountScope,
    List<SnapshotMetric> metrics,
    List<SnapshotBucket> trend,
    List<SnapshotOutcome> outcomes,
    List<SnapshotSenderRank> senderRanks) {}
```

Do not reuse the Thymeleaf page model or configure a broad Jackson ignore strategy.
Snapshot sender ranks use ordinal aliases only. A recursive property-name/value
privacy test should reject keys or canaries matching token, cookie, authorization,
header, body, attachment, URI, provider ID, email/address/domain, fingerprint, CSRF,
lease, nonce, or raw error concepts.

## UI Implementation Direction

Keep the current server-rendered stack:

- GET filter form and real links as baseline;
- CSS grid/flex for metric cards and bars;
- semantic table/list immediately adjacent to chart visuals;
- optional HTMX replacement of the analytics results region;
- no Tailwind browser runtime, Lucide CDN, canvas-only chart, or SPA state.

CSS bars can use a custom property calculated from bounded server values. The
accessible text must contain exact values independently of width/color. At 375px,
filters become a `<details>` panel, metric cards stack to one/two columns, trend
visuals become horizontally contained or list-first, and all tables become labeled
cards or local overflow regions.

## Availability Model

Availability is per source family, not one blanket page flag:

- metadata sync state controls received, sender, cohort, and delay completeness;
- Phase 12 ledger start/detail controls action and target metrics;
- V8 linkage controls unsubscribe confirmation;
- prior comparison takes the weaker current/prior state.

The service should return coverage boundaries before metrics. A query can therefore
show available received data, partial action history, and unavailable delay in one
honest response. `0`, `partial`, and `unavailable` must remain distinct in DTOs and
templates.

## Database And Performance Research

Existing V6 indexes already support account/time receipt and sender ranking. Verify
plans before adding duplicates. The likely Phase 13 additions depend on final V9
names but should cover:

- action events by account, occurred time, event/action type, stable ID;
- executions by account, completion/start time, action type/status, stable ID;
- execution targets by execution, status, ordinal;
- unsubscribe events by account, occurred time, status, stable ID.

Use half-open predicates without wrapping indexed timestamp columns in `date()` or
timezone functions. Bucket expressions may appear only after the account/time base
filter. Parameterize authorized account ID arrays.

Representative PostgreSQL fixture:

- at least 10,000 metadata rows across two owned and one foreign account;
- one year of dates including UTC day/week/month edges and leap day;
- repeated sender addresses across accounts;
- Phase 12 staged/immediate, all action types/outcomes, partial/pending/undo/legacy;
- V8 accepted/confirmed/manual/failed attempts;
- null timestamps and incomplete sync boundaries.

Measure warm query execution separately from app startup. Assert correct counts and
boundary rows before timing. A response-time test without correctness assertions is
not useful.

## Security And Privacy Risks

| Risk | Required mitigation |
|---|---|
| Account ID tampering | Resolve principal-owned IDs before every query/export |
| Raw parameter broadening | Strict typed parser; reject duplicates/invalid values |
| Join fan-out | Aggregate each fact family at target/message grain first |
| Provider ID leakage | Resolve only inside DB/server; projection allowlists |
| CSV injection | Prefix dangerous leading cells after whitespace |
| Snapshot accidental serialization | Dedicated versioned DTO, recursive deny-canary test |
| Sender URL leakage | Opaque signed account-owned drilldown token |
| Cache/referrer leakage | no-store, nosniff, no-referrer attachment responses |
| Fabricated history | Availability boundary and legacy exclusion counts |
| Unsubscribe overclaim | Count only V8 CONFIRMED as unsubscribed |
| Formula/rounding mismatch | Persist integer counts in DTO; round display only |

## Testing Strategy Implications

- Pure tests: query parsing, UTC boundaries, prior ranges, bucket generation, metric
  arithmetic, availability lattice, percent/zero behavior, CSV escaping.
- H2/MVC: ownership, canonical links, templates/states, source count equality,
  exports, snapshot allowlist, accessibility/static responsive contracts.
- PostgreSQL 16: production migrations, array ordinal join, percentile, bucket edges,
  query parity, indexes/plans, representative performance, stream ordering.
- Full regression: Phase 9 metadata privacy and shell; Phase 10 filters; Phase 11
  unsubscribe safety; Phase 12 outcome/history/undo.
- Manual localhost: real OAuth/provider cycle, visual/keyboard/zoom judgment, real
  downloads, and deployment block.

## Planning Recommendations

1. Lead with a vertical analytics tracer: canonical query -> one received metric ->
   one trend bucket -> exact source rows -> analytics CSV.
2. Add Phase 12 ledger metrics and availability only after the final V9 schema exists;
   do not plan against legacy `affectedCount`.
3. Build shared export infrastructure and migrate each required surface with
   per-surface allowlisted schemas.
4. Implement sanitized snapshot separately from CSV and page DTOs.
5. Finish with all-page responsive/accessibility cleanup, full automated regression,
   then the blocking localhost UAT artifact. Do not deploy in this phase.

## Research Conclusion

The phase is feasible with the current stack. The main engineering risk is semantic,
not visual: joins and mutable mailbox fields can produce plausible but false numbers.
Typed predicates, ledger/event authority, per-metric availability, aggregate/source
equality tests, and allowlisted output models are the required controls.
