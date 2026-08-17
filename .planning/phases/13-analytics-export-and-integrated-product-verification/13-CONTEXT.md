---
phase: 13-analytics-export-and-integrated-product-verification
status: ready-for-planning
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
---

# Phase 13: Analytics, Export, And Integrated Product Verification - Context

**Gathered:** 2026-08-09
**Status:** Ready for planning
**Mode:** Autonomous conservative resolution; no user questions

<domain>
## Phase Boundary

Phase 13 turns indexed metadata and the Phase 12 durable action ledger into
trustworthy analytics, completes the real controls represented by the analytics
mock, adds server-filtered CSV exports to every required workspace surface, adds a
sanitized analytics snapshot, and closes the milestone with blocking localhost UAT.

Phase 13 owns:

- `/analytics`, its filters, metrics, comparisons, trend buckets, source drilldowns,
  top-sender links, outcomes, coverage, efficiency, and unavailable states.
- CSV export for Senders, Unread, Unsubscribe, Review history, and Analytics.
- A sanitized analytics snapshot suitable for sharing.
- Remaining decorative controls on all five workspace pages.
- Responsive and accessible behavior at 375px.
- Automated analytics/export/privacy/performance evidence and a real localhost Gmail
  cycle before any tunnel or deployment.

It does not add provider capabilities, ingest bodies or attachments, infer historical
facts from current mailbox totals, confirm unsubscribe remotely without evidence,
or deploy publicly.
</domain>

<decisions>
## Locked Decisions

### Canonical Analytics State

The canonical query is:

```text
/analytics?from=YYYY-MM-DD&to=YYYY-MM-DD&account={owned-id|all}
  &action={all|archive|trash|mark_read|unsubscribe|keep|protect|review}
  &group={day|week|month}&compare={prior|none}
```

- Dates are inclusive UTC calendar dates. The server converts them once to the
  half-open interval `[from 00:00:00Z, to + 1 day 00:00:00Z)`. The UI says `Dates
  use UTC`. Browser locale never changes membership.
- Default range is the latest 30 UTC dates including today; default `group=day`,
  `action=all`, and `compare=prior`.
- `account` uses the Phase 9 owned account resolver. One account canonicalizes to its
  positive numeric ID; `all` means all currently owned supported Gmail accounts.
  Missing/default aliases redirect or render links with canonical state. Foreign,
  disabled, unsupported, malformed, or duplicate recognized parameters fail closed.
- Maximum selected range is 366 dates. `from > to`, impossible dates, duplicate
  recognized keys, unknown enum values, and overlarge ranges return a bounded 400
  state without running a broad query.
- Weeks start Monday at 00:00Z. Months are UTC calendar months. Buckets are clipped to
  the selected half-open interval, are contiguous, and include explicit zero buckets
  only when the source is known complete for that bucket.
- Prior comparison is the immediately preceding interval with exactly the same
  duration: `[from - duration, from)`. It uses the same account and action predicates.
  Grouping affects presentation only, not comparison membership.
- Filter changes reset drilldown/source pagination. Every link, export, snapshot, and
  drilldown carries the normalized complete state.

`action` limits action-derived numerators, outcome sections, and action source rows.
Received volume remains the selected account/date baseline because received facts
have no action type. This exception is visibly labeled `Received baseline: all
indexed mail in this account and date range`; it is never presented as action-filtered.

### Fact Time And Source Authority

- Received facts use `message_metadata.received_at`; null timestamps are excluded and
  counted in an unavailable-detail reason.
- Decision facts use the first durable Phase 12 decision event for a target. Execution
  facts use the append-only event/target timestamp that establishes that outcome.
- Unsubscribe confirmation uses linked V8 `unsubscribe_outcome_events.occurred_at`.
- Undo uses its own confirmed inverse target event. It never rewrites the forward fact.
- Event timestamps, not mutable current status or `updated_at`, place action facts in
  ranges and buckets.
- Distinct-message metrics may resolve `(account_id, scope_ordinal)` against the
  immutable `ActionScope.messageIds` inside a server-side query. Provider IDs never
  leave that query, enter analytics DTOs, logs, HTML, URLs, CSV, or snapshots.
- Phase 12 legacy rows without target/event detail remain visible in History but are
  excluded from metrics that require those details. They contribute an explicit
  unavailable count; migration never fabricates events or target outcomes.
- Every displayed value carries `available`, `partial`, or `unavailable` plus a safe
  reason and coverage boundary. Zero is rendered only when the fact is available and
  counted zero.

### Exact Metric Definitions

All target counts are account-local ledger targets. Distinct-message metrics
deduplicate by `(account_id, provider_message_id)` only inside the database query.

| Metric | Numerator | Denominator / display contract |
|---|---|---|
| Received | Distinct metadata rows with `received_at` in range | Count; baseline is all action types |
| Triaged | Distinct received messages whose first durable KEEP, REVIEW, PROTECT, ARCHIVE, TRASH, MARK_READ, or UNSUBSCRIBE decision occurs in range | Count; duplicate later decisions do not increase it |
| Archived | Forward ARCHIVE targets first proven `SUCCEEDED` in range | Gross count; undo shown separately |
| Trashed | Forward TRASH targets first proven `SUCCEEDED` in range | Gross count |
| Marked read | Forward MARK_READ targets first proven `SUCCEEDED` in range | Gross count |
| Unsubscribed | Targets with a linked `CONFIRMED` V8 event in range | Accepted/pending is not confirmed and is shown separately |
| Prior change | `(current - prior) / prior * 100` | If prior is zero, show absolute delta and `Percent unavailable`; no infinity |
| Action outcomes | Requested, accepted, succeeded, failed, verification-pending, not-dispatched target counts from Phase 12 executions in range | `requested = succeeded + failed + verification_pending + not_dispatched`; accepted overlaps |
| Review coverage | Targets executed from STAGED actions | All executed targets in selected action/date/account predicate; show `N / D` |
| Cleanup coverage | Distinct received messages with a first durable decision by range end | Distinct received messages in the selected range; cohort metric, may mature after receipt |
| Unsubscribe completion | Confirmed unsubscribe targets | Initiated targets with ACCEPTED, CONFIRMED, FAILED, or VERIFICATION_PENDING facts; manual-required-only candidates excluded and separately counted |
| Inbox reduction | Successful ARCHIVE + TRASH targets minus confirmed inverse undo targets, floored at zero | Received baseline; show `N / D`; may be unavailable when target/event linkage is incomplete |
| Cleanup rate | Distinct triaged messages with successful ARCHIVE, TRASH, MARK_READ, or confirmed UNSUBSCRIBE outcome | Distinct triaged messages; KEEP, REVIEW, and PROTECT remain triaged but not cleaned |
| Median triage delay | Median of first decision time minus `received_at`, excluding negative/corrupt pairs | Number of eligible paired messages; show sample `n` |
| Triage-time distribution | Eligible first-decision delays in `<15m`, `15m-1h`, `1-24h`, `1-7d`, `>7d` | Same eligible delay sample; buckets partition denominator |
| Confidence retention | Requested targets not left verification-pending | All requested targets; numerator is succeeded + failed + not-dispatched, shown as `N / D` |

Rates use unrounded integer numerator/denominator and round only for display to one
decimal place. Counts never derive from a displayed percentage. A denominator of
zero renders `Unavailable - no eligible rows`, not `0%`.

`Inbox reduction` is an event-window throughput ratio and may exceed 100% when the
user cleans older mail; the UI explains this rather than clamping. `Cleanup coverage`
is the received cohort ratio and cannot exceed 100%.

### Trend, Bucket, And Source-Row Consistency

- Received trend values count received source rows in each bucket.
- Cleaned trend values count the same successful cleanup outcome definition used by
  Cleanup rate, placed by outcome event time. The legend says these series use
  different fact times.
- Each chart point/bar is a real link to `/analytics/source` with canonical filters,
  `series=received|cleaned`, and exact bucket start/end. The source query uses the
  same repository predicate as the aggregate, not a reconstructed approximation.
- Source pages show safe account label, received/event time, safe sender label,
  bounded action/outcome label, and a provider deep link only where the existing
  authorized safe builder can produce one. They never show provider IDs, raw headers,
  bodies, attachment data, scope IDs, fingerprints, or tokens.
- Aggregate count must equal the count endpoint for the same source predicate.
  Paginated source rows use stable time then internal-ID ordering; internal IDs do
  not enter rendered URLs.
- Partial buckets are labeled `Partial` when sync coverage or ledger history cannot
  establish completeness. No missing interval is backfilled from sender totals.

### Top Senders And History Drilldowns

- Top senders rank distinct received metadata rows by `(account_id, normalized sender
  key)` for the selected range, descending count then normalized sender key. Equal
  addresses in different accounts do not merge. Default limit is five; full report
  limit is bounded at 100.
- Display uses approved sender name/address/domain already allowed on sender surfaces.
  Missing addresses display `Unknown sender` and have no sender-queue link.
- `Open sender queue` uses a short-lived opaque account-owned drilldown key that the
  server rejoins to the normalized sender and canonical `/senders` predicate. Raw
  addresses are not placed in analytics URLs or logs. The resulting Senders page
  visibly states the originating analytics range.
- Action metric, outcome, coverage, and efficiency details link to Review History
  with the intersection expressible by its canonical date/account/action/status
  filters. When the metric predicate is more specific than History supports, link to
  `/analytics/source` instead of broadening silently.
- No `href="#"`, inert selector, sample number, or JavaScript-only control remains.
  A represented control either has complete server behavior and fallback or is absent.

### CSV Export Contract

Required GET endpoints:

```text
/senders.csv
/unread.csv
/unsubscribe.csv
/review/history.csv
/analytics.csv
```

- Each endpoint invokes the same query parser, owned-account resolver, normalized
  predicate object, and repository source as its HTML surface. It exports the full
  filtered set, not only the current page, with a configured hard row cap and no
  silent truncation. Over-cap returns 422 with guidance to narrow filters.
- Pagination keys and UI-only view keys are ignored only after validation; all
  filtering, sorting, account, date, action, status, target, and comparison state
  remains identical.
- CSV is UTF-8, RFC 4180, CRLF, deterministic column order, explicit header row, and
  streamed. Every textual cell beginning after whitespace with `=`, `+`, `-`, `@`,
  tab, or carriage return is prefixed with `'` after normal CSV escaping to prevent
  spreadsheet formula injection.
- Senders exports safe aggregate sender fields; Unread exports approved visible
  metadata; Unsubscribe exports candidate/method/eligibility safe summaries; Review
  exports safe history facts and counts; Analytics exports long-form metric, bucket,
  comparison, numerator, denominator, availability, and safe reason rows.
- Exports contain no database IDs, provider message/thread IDs, action/scope IDs,
  target fingerprints, exact scope arrays, unsubscribe URI/query/header values,
  bodies, attachment data, OAuth/access/refresh tokens, cookies, CSRF/idempotency/
  lease values, DNS details, raw exception text, or hidden model fields.
- Filenames include surface and UTC generation date only. Responses set attachment,
  `text/csv; charset=UTF-8`, `X-Content-Type-Options: nosniff`,
  `Cache-Control: no-store`, and `Referrer-Policy: no-referrer`.

### Sanitized Analytics Snapshot

`GET /analytics/snapshot.json` uses the current canonical analytics state. It contains:

- schema version, UTC generated time, normalized date/group/action/compare state;
- account scope as `one account` or count only, never email or internal/public ID;
- metric definitions, values, integer numerators/denominators, availability/reasons;
- trend buckets and outcome/coverage/distribution aggregates;
- top senders replaced with stable-in-document labels `Sender 1`, `Sender 2`, etc.,
  with counts only.

It excludes source rows, sender/address/domain/list labels, account labels, provider
links, all IDs/fingerprints, headers, subjects, bodies, attachments, URLs, tokens,
cookies, credentials, raw errors, and arbitrary serialized entities/models. Build it
from a dedicated allowlisted DTO, not generic object serialization. Apply `no-store`,
`nosniff`, no-referrer, and attachment headers.

### Availability And Historical Coverage

- The analytics page displays the earliest reliable received date and earliest
  reliable action-ledger date per selected account, then the conservative latest
  common boundary for `account=all`.
- A running/partial/failed/stale metadata sync marks received/cohort metrics partial.
  Action metrics can remain available independently when their ledger source is
  complete.
- Dates before first indexed history are `Unavailable - mail was not indexed then`.
  Legacy Phase 12 gaps are `Partial - per-target action history is unavailable for
  {count} legacy actions`.
- Current `unread`, labels, `sender_stats.total_count`, and mailbox totals cannot be
  used to invent past received, cleaned, or inbox-reduction values.
- Unsubscribe ACCEPTED or VERIFICATION_PENDING is labeled exactly; it never increments
  Unsubscribed/confirmed.
- Comparison adopts the weaker availability of current and prior ranges. If either is
  unavailable, no percentage is emitted.

### UI, Accessibility, And Responsive Contract

- Reuse the Phase 9 workspace shell, local `workspace.css`, Thymeleaf, canonical GET
  forms, semantic HTML, and optional HTMX enhancement. No remote chart/UI dependency.
- Charts require an adjacent semantic data table or list containing the same values.
  SVG/canvas alone is insufficient. Every bucket is keyboard reachable and named.
- Exactly one `main` and `h1`; headings are ordered; filters use labels and fieldsets;
  statuses have text; live updates use polite status regions; errors use alerts.
- At 375 CSS pixels and 200% zoom there is no page-level horizontal scrolling.
  Tables become labeled cards or use contained local overflow with an accessible
  label. All controls and links provide at least a 44 by 44 CSS pixel target.
- Focus remains visible. Reduced-motion disables nonessential transitions. Charts do
  not rely on color alone and meet contrast requirements.
- Empty, loading, partial, stale, unavailable, zero, error, and over-cap export states
  are distinct and actionable.

### Performance And Index Requirements

- Analytics and exports are database-only GETs and perform zero Gmail/provider/
  unsubscribe transport calls.
- Shell response remains under one second and indexed analytics/filter/drilldown
  queries under two seconds on the supported localhost representative dataset after
  warmup. CSV streams without loading the complete result into memory.
- Phase 13 may add an additive paired V10 migration after Phase 12 V9. V1-V9 files and
  checksums remain frozen.
- Required PostgreSQL indexes must lead with account and fact time, then filter/tie
  columns: metadata `(account_id, received_at, id)`, action event
  `(account_id, occurred_at, action_type/status, id)` as supported by the final V9
  shape, execution `(account_id, completed_at/started_at, action_type, id)`, target
  `(execution_id, status, scope_ordinal)`, and unsubscribe event
  `(account_id, occurred_at, status, id)`.
- Use `EXPLAIN (ANALYZE, BUFFERS)` assertions/recorded plans on deterministic
  PostgreSQL fixtures to reject full-table scans caused by missing leading account/
  time predicates. Do not add duplicated provider IDs or generic analytics JSON.
- Prefer query-time CTEs/window functions and bounded aggregate DTOs. Add a summary
  table only if measured plans cannot meet the budget, and then require transactional
  provenance, rebuild parity, and no private duplicated facts.

### Integrated Localhost UAT Gate

Automated green is necessary but not sufficient. Before any cloud deployment or
public tunnel, a human performs a real authenticated Gmail cycle on
`http://localhost:8080`:

1. verify OAuth/account scope and sync at least 500 metadata records;
2. inspect all five workspace pages at desktop and 375px;
3. create one staged and one explicit immediate action through the same scope safety;
4. exercise unsubscribe safe automatic and manual/account-page alternatives without
   claiming unverified completion;
5. verify Review result counts/history, partial/pending wording, and no hidden IDs;
6. verify Analytics filters, comparison, bucket/source equality, top-sender queue,
   history drilldowns, and unavailable history;
7. download and inspect all five CSV surfaces under non-default filters;
8. inspect the sanitized snapshot with privacy canaries;
9. undo one eligible archive/trash/mark-read result and verify analytics/history
   retain both forward and inverse facts;
10. keyboard-test live updates, 200% zoom, 375px no page scroll, and 44px targets.

Evidence records UTC time, build commit/worktree identity, browser, viewport, account
count (not email), canonical URLs with private values redacted, expected/actual
counts, downloaded filenames/checksums, screenshots without private content, and
pass/fail notes. No token, cookie, email, provider ID, body, raw header, or private
download is committed. Any failure blocks deployment.
</decisions>

<code_context>
## Existing Foundations To Reuse

- Phase 9 `MessageMetadata`, `ActionScope`, owned account resolution, shared workspace
  shell/state, account query, metadata-only sync, and indexed received/sender facts.
- Phase 10 canonical Sender/Unread query parsers, deterministic SQL, safe row keys,
  provider deep-link builder, and server-side materialization.
- Phase 11 unsubscribe candidate/attempt/event privacy and honest accepted/pending/
  confirmed semantics.
- Phase 12 planned V9 actions, executions, per-target outcomes, append-only events,
  history filters, undo, and analytics-ready counts.
- `tidymail-ux-rehaul/pages/analytics-compact-suite.html` for composition and density,
  not data, URLs, controls, or terminology.
- Existing `analytics.html` is a Phase 13 placeholder and must become a real route.

## Canonical References

- `.planning/ROADMAP.md`
- `.planning/REQUIREMENTS.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-CONTEXT.md`
- `.planning/phases/11-safe-unsubscribe-and-protection/11-VALIDATION.md`
- `.planning/phases/12-review-dual-execution-history-undo/12-CONTEXT.md`
- `.planning/phases/12-review-dual-execution-history-undo/12-RESEARCH.md`
- `.planning/phases/12-review-dual-execution-history-undo/12-UI-SPEC.md`
- `.planning/phases/12-review-dual-execution-history-undo/12-VALIDATION.md`
- `tidymail-ux-rehaul/pages/analytics-compact-suite.html`
- `src/main/resources/templates/analytics.html`
- `src/main/resources/static/css/workspace.css`
- `src/main/resources/db/migration/V6__metadata_index_and_action_scopes.sql`
- `src/main/resources/db/migration/V8__safe_unsubscribe.sql`
- Phase 12 V9 migration and ledger code once implemented

## Deferred

- Public deployment/tunneling, provider parity, body/content analytics, attachment
  analytics, predictive recommendations, remote unsubscribe verification jobs,
  scheduled emailed reports, and cross-user benchmarking.
</code_context>

## Requirement Coverage

| Requirement | Phase 13 contract |
|---|---|
| WORK-05 | Every rendered selector, action, drilldown, and export is real or removed |
| ANLY-01 | Exact received, triaged, action, and confirmed unsubscribe metrics |
| ANLY-02 | Canonical UTC range, grouping, account, and action filters |
| ANLY-03 | Equivalent immediately preceding half-open interval |
| ANLY-04 | Received/cleaned trend with exact source-row drilldowns |
| ANLY-05 | Account-local top senders with opaque queue drilldown |
| ANLY-06 | Outcomes and three coverage ratios with explicit integer denominators |
| ANLY-07 | Reduction, cleanup, delay distribution, and confidence definitions |
| ANLY-08 | Per-source availability, partial history, and no inferred past |
| EXPT-01 | Five required CSV surfaces |
| EXPT-02 | Shared parser/resolver/predicate and full filtered set |
| EXPT-03 | Dedicated allowlisted sanitized JSON snapshot |
| QUAL-02 | Accessible 375px/44px/200% contract across all five pages |
| QUAL-06 | Blocking real localhost Gmail cycle before deployment |

## Autonomous Resolution Note

Ambiguity is resolved toward reproducibility, privacy, and honest absence. UTC
calendar semantics avoid browser-dependent membership; target-ledger facts outrank
mutable mailbox state; a missing denominator is unavailable rather than zero; and
deployment remains blocked until the preferred localhost manual validation passes.
