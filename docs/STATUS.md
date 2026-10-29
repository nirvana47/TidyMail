# Project status

## Dashboard Phase 4 — 2026-10-28

Durable Gmail Mark as Read is implemented at `/dashboard`: exact preview and
idempotent confirmation, fixed PostgreSQL targets, 50-target background batches,
progress/Cancel, reload restoration and Activity results. Cancellation stops new
claims and settles in-flight work. Ambiguous or lost dispatches use read-only
label verification without automatic mutation replay. V17 fresh/10k-row retained
upgrade fixtures passed, including retained V16 preview handling.

The Java 21 full suite ran 481 tests: 479 passed, the two known sender-label and
deployment-command assertions failed, and there were no errors/skips. The final
focused run passed all 30 checks after bounded scope/deletion refinements; see the
[Phase 4 report](PHASE-4-BACKGROUND-MARK-READ.md) for its result and exact evidence.
Desktop synthetic browser checks passed confirmation, progress across reload,
cancellation, completion, count reconciliation, history and keyboard focus.
This is simulated-provider evidence; live Gmail and release acceptance remain open.

Phase 4 is complete within that scope. Phase 5 Archive/Trash and resilience are
next; those dashboard actions remain preview-only. The retained application and
database were not upgraded/restarted, and no real mailbox action, commit, push or
deployment was performed.

## Dashboard Phase 3 — 2026-10-28

Indexed browsing and exact-selection previews are implemented at `/dashboard`.
The shared received/date/search predicate, ten-row sender/message paging,
account-owned grouping corrections, selection state and persisted target previews
passed functional and synthetic browser checks. Fresh and 10k-row retained upgrade
fixtures passed. New browsing paths met the Phase 2 performance budgets at 10k/50k
messages with concurrent writes; the slowest measured new browsing response/query
in the fresh Java 21 run was 347.15 ms. This is local synthetic evidence.

The full fresh run had 463 tests: 460 passed, three failed, no errors/skips.
A fixture/index assertion was fixed and passed in the final 36-test targeted run,
along with the final provider-label and grouping guards. The two pre-existing
sender-label/deployment-command assertions remain open; the full suite was not
repeated after those bounded fixes. See the
[Phase 3 report](PHASE-3-INDEXED-DASHBOARD.md) for exact evidence and screenshots.
At Phase 3 completion, durable background actions were next and execution was
disabled. Phase 4 supersedes that state as recorded above.
The retained database/application and live mailbox were not changed or restarted.
No commit, push or deployment was performed.

## Dashboard Phase 2 — 2026-10-28

Baseline verification and persistence design are complete. The existing suite ran
451 tests: 448 passed, three baseline failures, no errors/skips. The new isolated
10k/50k-message benchmark passed; the slowest sampled repository operation was
972.46 ms during concurrent synthetic metadata writes. This does not establish
dashboard rendering performance: the existing warm sender-route check failed at
27.688 s. See the [Phase 2 report](PHASE-2-BASELINE-AND-PERSISTENCE.md) for component
reuse, evidence gaps, additive schema/state design, backup/upgrade fixtures and
Phase 3 targets. No application source, retained database or live mailbox changed.
Phase 3 indexed browsing is next; the three baseline failures remain open.

Source mapped on 2026-10-14. Implementation findings below are distinct from historical test evidence and live acceptance. No application code changed during this refresh.

## Current capabilities

| Area | Finding |
|---|---|
| Gmail cleanup | Provider, indexed sender/unread surfaces, exact staging, Review execution and outcome history implemented |
| Synchronization | Recent bootstrap, progressive backfill, independent History freshness, rescan, managed refresh and Activity UI implemented |
| Unsubscribe | Candidate inspection, method-specific consent, bounded transport and outcome ledger implemented |
| Analytics | Metadata/ledger queries, UTC trends, comparisons and source drilldown implemented |
| Corrections | Account-owned sender type overrides and organization categories implemented |
| Undo | Eligible successful Archive/Trash/Mark Read targets; ten-minute engine window |
| Outlook | OAuth and abstractions exist; production mailbox provider absent |
| Privacy | Metadata stored; bodies/snippets/content excluded. Logout retains data; confirmed owner deletion removes it |
| Export | Live export subsystem removed; removal journey test retained |

## Verification evidence

The archived Phase 19 validation, dated 2026-08-14, records **447 tests across 92 reports, zero failures/errors/skips**. Independent verification records a **53-test targeted pass** and a **10,000-row synthetic performance profile**. These are historical recorded results, not a fresh run of this checkout. Original evidence is recoverable from Git; see [History](HISTORY.md).

That same verification explicitly leaves controlled live Gmail acceptance open: freshness while backfill runs, token refresh persistence and real unread/size/attachment facts. No `19-LIVE-UAT.md` was found in the archived tree. Testcontainers/MockMvc evidence does not establish provider latency or real-browser rendering.

The repository contains 83 Java test-source files, including support classes; that is not a test-case count. No current Surefire reports were found in `target/`. This refresh checked documentation/source consistency and links; it did not rerun the suite or mutate a mailbox.

At mapping time, `src/main/resources/templates/login.html` contained a pre-existing uncommitted redesign. It is outside this documentation change and excluded from its commits.

## Remaining work

The [2026-10-28 MVP plan](MVP-PLAN.md) supersedes the priorities below where they
conflict. Durable background cleanup and live action progress are now MVP
requirements; unsubscribe, multi-account UI, and future automatic rules are out
of scope. Preserve the existing connected account/index for testing where
practical. This update records requirements only; no implementation or new live
acceptance is claimed.

| Priority | Work | Evidence / completion criterion |
|---|---|---|
| First | Controlled live Gmail acceptance | Prove fresh mail during backfill, managed refresh and indexed metadata against a test mailbox; record redacted observations |
| First | Current release verification | Run affected/full PostgreSQL tests and authenticated browser checks after application changes |
| Next | Classification quality | Rules and corrections exist; old plans proposed a labeled accuracy benchmark and optional local ML, neither delivered in source |
| Next | Architectural maintenance | Large candidate/analytics services, duplicate validation/account helpers and in-JVM staging locks remain; preserve journeys while refactoring |
| MVP | Bulk execution feedback | Durable background cleanup with live progress is required by the new plan; current cleanup is request-dispatched, unlike background mailbox sync |
| Later | Gmail rules | Automatic rules for future messages remain outside the dashboard MVP |
| Scope decision | Outlook completion | Implement and verify a provider before claiming Outlook cleanup support |
| Scope decision | Automatic data expiry | Current jobs perform partial housekeeping, not full timed deletion of metadata/tokens |

These priorities consolidate unfinished historical work and mapping findings; they are not authorization to implement all of it. No production deployment, full-mailbox capacity guarantee or comprehensive security certification is established by this map.

## Sync recovery update

Metadata HTTP batches now share a paced budget (default: ten messages per
second), with up to five retries for rate-limit and temporary server errors.
Retries retain successful metadata and request only failed IDs. Read retries
respect `Retry-After`; they do not retry mailbox mutations. Active worker leases
renew, and scheduled sync resumes unfinished backfill alongside freshness,
including shortly after application startup.

New coverage uses the actual Gmail SDK, simulated HTTP responses and a real
PostgreSQL test database. It verifies a 200-message bootstrap, exhausted page
retries without cursor advancement, automatic recovery to 214 messages, partial
batch retries, pacing, lease renewal and permanent permission failures.

The full suite ran 450 tests: 447 passed, three failed, zero errors or skips.
The sender-display assertion also fails on the committed baseline; the
legacy deployment-document assertion requires a command missing from the
committed baseline as well. The 10,000-row sender performance check exceeded
its two-second threshold in the full run, but passed on both the baseline and updated code in
isolation. These remain separate from the passing sync recovery checks.

After rebuilding and restarting locally, the live Gmail index advanced beyond
200 to at least 11,500 messages, with completed 500-message backfill pages and
continued work. This establishes recovery beyond the original stall, not full
mailbox completion or acceptance of cleanup/undo and other product journeys.
