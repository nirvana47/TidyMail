> Current optional classification: V21 adds Jev via OpenRouter, per-message category
> filters and corrections, and service-aware automatic grouping. See
> [classification architecture and privacy](JEV-CLASSIFICATION.md). It supersedes
> earlier non-AI and domain-only grouping statements for users who start a run.

# Phase 3 — Indexed browsing and exact selections

**Subsequent scope decision — 2026-10-28:** The user confirmed desktop-first MVP
scope. Mobile work/checks already recorded below are historical evidence, not
ongoing requirements. No further mobile implementation or testing is planned
unless the user explicitly brings it back into scope.

Implementation date: 2026-10-28. Builds on Phase 2 (`806a930`) and the
[approved dashboard](wireframes/sender-dashboard-approved.html). The application
landing route `/dashboard` now serves the indexed dashboard. Mailbox execution is
not enabled here; Phase 4 owns confirmation-to-job dispatch and cancellation.

## Task tracking

| Task | Status | Deliverable and acceptance evidence |
|---|---|---|
| Shared scope/filter contract | Complete | `DashboardFilter` and `DashboardRepository.FILTERED` drive summary, rows, details, materialized selection and preview eligibility. |
| Derived groups and corrections | Complete | Additive V16; stable address/list/message units; conservative domains; account-owned merge/separate/reset with preview and revision checks. |
| Locked browsing interface | Complete | `/dashboard`, 10 sender groups and 10 messages per independent page, split detail pane, inline addresses, Messages / Sender addresses tabs. |
| Shared exact selection | Complete | Opaque message-ID map; group/address selections include unloaded matching children; header affects current sender page; parent mixed states; paging preserves choices; filters/corrections discard them. |
| Exact preview | Complete | Persisted scopes and paginated target snapshots, effective/skip/protected counts, original filters/coverage and expiration; stale revision/foreign IDs rejected. |
| Fresh and retained upgrade fixtures | Complete | `DashboardMigrationTest`: empty install and V15 with 10,000 retained rows, two owners, dummy credential bytes, cursor and historical statuses. |
| Performance | Complete | Implemented paths meet Phase 2 budgets at 10k/50k messages and 500 units, idle and during metadata writes; 501/5,000-ID previews measured. |
| Browser checks | Complete (bounded) | Authenticated synthetic MockMvc bridge; scope, pagination, mixed selection, filter clearing, grouping correction, preview paging and desktop/narrow visual inspection. |
| Final regression verification | Complete (known exceptions recorded) | Fresh Java 21 full run: 463 tests, 460 passed, three failures. Final 36-test check passes after fixing the fixture/index failure; two pre-existing assertions remain open. |

## What changed

`DashboardController` resolves the authenticated owner's active account for every
request and retains CSRF on all writes. Responses use `no-store`. All public
selection/group/preview identifiers are account-scoped UUIDs; provider message IDs
remain server-side in the existing exact-scope records. The legacy pages remain
available until Phase 6's navigation/access cleanup.

`DashboardService` uses repeatable-read transactions so summary, rows and details
within each response share a database snapshot. Group rows sort by matching count
and stable group key; message rows sort by received time and provider ID. A shared
predicate excludes Spam/Trash/Draft before applying Inbox or All received scope.
Archived non-Sent mail qualifies with known label evidence; Sent mail requires
Inbox or independent Delivered-To evidence to the account or a verified alias.
To/Cc alone is insufficient. Missing evidence is visible in coverage diagnostics.
Dates use inclusive start/exclusive next-day boundaries in the displayed timezone;
default is all indexed dates. Subject/name/address/group-label search is literal,
case-insensitive substring matching, including punctuation such as `%_!`.

`DashboardIdentity` preserves local-part case and normalizes domains with IDNA.
It deliberately rejects ambiguous/unsupported From or List-ID syntax into isolated
message units. Grouping corrections require stable ADDRESS/LIST evidence;
unverified message fallback groups remain separate until fresh metadata arrives.
Valid lists form separate atomic units. V20 supersedes the original exact-domain
allowlist: addresses now share an organization group across subdomains using a
bundled Public Suffix List (including private hosting boundaries). For example,
`chase.com`, `e.chase.com`, and `notify.chase.com` form `chase.com`; independent
`alice.github.io` and `bob.github.io` tenants stay apart. Known personal mailbox,
tenant, newsletter-hosting and relay services remain exact-address groups via a
shared-domain registry. Unknown suffixes stay separate; reviewed dedicated-domain
entries still support internal/test domains. Display-name similarity and lookalike
domains never merge senders. This is a browsing heuristic, not sender authentication.

V20 regroups already-indexed ADDRESS units without refetching mail, preserving
atomic IDs and saved manual membership. It advances grouping revisions so stale
selections/previews must reload; confirmed job targets remain fixed. LIST and
uncertain MESSAGE units retain their existing boundaries. Group evidence explains
the organization-domain rule. Public suffix data is pinned in the migration with
source/version/license attribution; updates need a new migration. Shared providers
missing from the registry can still need manual separation and a registry addition.
No external logo, enrichment, classifier, message-body download or new full-mailbox
fetch was introduced.

V20 validation (2026-10-29): 55 distinct focused tests passed across identity,
dashboard journeys, retained/fresh migrations, cleanup jobs and resilience. The
Java 21 package build, JavaScript syntax and diff checks passed. The Chase fixture
asserts 117 messages, two unread, five addresses and one organization group, with
exact address/group selections and manual separation surviving new mail. Boundary
fixtures cover multi-label suffixes, private tenants, wildcard/exception rules,
IDNA and shared providers. The retained application/database has not been upgraded;
these checks use isolated synthetic databases. The full suite and fresh browser
acceptance were not run for this change.

The Gmail projection preserves missing labels as unknown, separately from an
observed empty set; a direct provider-to-mapper regression test covers this.
Metadata mapping and bulk sync persistence retain raw From values and whether
label evidence was observed. V16 treats retained identities as lossy and derives
message-level fallback units rather than reconstruct original local-part case.
Historical nonempty labels count as observed label evidence; empty arrays remain
unknown until a real metadata observation distinguishes an empty label set from
missing data. Sync writes maintain group membership through a database trigger,
including inserts outside the bulk repository. Existing credentials, metadata,
sync cursors and action history are preserved.

Corrections serialize against account writes and increment a grouping revision.
Active correction history can be replayed; reset disables only the selected
correction. Separation pairs reject transitive merge conflicts. Preview lists all
affected atomic units before applying a correction. Group-label changes never
rewrite a persisted message selection or exact scope.

`DashboardPreviewService` reuses `ActionScopeHasher`, `action_scopes` and
`action_scope_targets`. It materializes all selected owned IDs, deduplicates them,
and stores an immutable display snapshot for every target. Known no-ops,
protected senders and no-longer-eligible messages remain counted with reasons;
there is no substitution or silent truncation at 1,000 messages. The generic
summary DTO now accepts larger nonnegative counts; legacy sender-selection limits
remain in their legacy validation paths. Previews expire after 30 minutes and
can be explicitly invalidated when the draft changes. A future Phase 4 dispatcher
must still recheck ownership, preview validity, overlap and eligibility at dispatch.

The final confirmation button is disabled and explains that background actions
are not available yet. There is no simulated successful job or optimistic Gmail
mutation behind it. Phase 4 will connect this exact preview to durable execution.

## Automated checks

Commands use isolated Testcontainers PostgreSQL 16.15; the retained Compose
database and live provider are not test fixtures.

```sh
mvn -o -Dtest='DashboardJourneyTest,DashboardIdentityTest' test
mvn -o -Dtest='DashboardMigrationTest,DashboardPerformanceTest' test
mvn -o test
```

The initial 34 scope/sync compatibility checks passed. New functional checks cover
message-local received eligibility, read/unread browsing, archived and Sent/self
exceptions, alias evidence, exclusion precedence, mixed threads, unknown labels,
shared/subdomain/list/case distinctions, literal subject search, null dates,
Pacific midnight and a 25-hour DST date. They also cover correction preview/reset,
transitive separation conflicts, exact deduplication, no-ops and protection,
new-mail/filter/revision changes, immutable target details, cross-owner rejection,
CSRF, account timezone settings and independent ten-row paging boundaries.

Fresh install and V15→V16 fixtures passed. Retained fixture checks compare credential
bytes, all 10,000 messages' original selected fields, sync-state JSON and historical
action JSON before/after migration; confirm 8,000 observed nonempty label sets and
10,000 conservative fallback units; and reject cross-owner membership insertion.
These are synthetic upgrades, not a backup/restore or migration of the user's index.

The first full run executed 463 tests with 9 failures, zero errors/skips. Four
failures were old redirect expectations; one inspected now-external CSS in the
HTML; one deterministic synthetic checksum now included a random UUID. Those
tests/fixtures were updated to the new contract. A separate random-ciphertext
assertion incorrectly rejected a chance `41` substring; the test now checks for
actual readable source values rather than two random digits. The pre-existing
sender-label and deployment-command assertions also failed. The previously failing
legacy sender timing check passed in that run; this does not establish a fix for
its variable performance.

The fresh Java 21 build explicitly compiled all application/test sources. Its
full run finished in 9 min 21 s: **463 tests, 460 passed, 3 failures, 0 errors,
0 skipped**. All dashboard functional, migration and performance checks passed.
The failures were the two known display/documentation assertions plus an index-plan
assertion affected by the fixture's extra UUID update. The fixture now inserts its
deterministic opaque IDs directly, avoiding unnecessary update/dead-row side effects.
The plan assertion then passed. Final verification of this change, the provider
label fix and the stable-unit correction guard ran **36 tests, all passed**, on
Java 21 in 43.617 s. This includes eight dashboard journeys and two identity/evidence
checks. The whole suite was not rerun after those final bounded fixes.

| Remaining existing failure | Evidence / disposition |
|---|---|
| `MailboxFreshnessJourneyTest.newUserSeesFirstBatchBeforeFullMailboxBackfillCompletes` | Old `/senders` output lacks `Batch Sender`; same Phase 2 assertion remains open. |
| `Phase8DeploymentTest.T8d_lintAndDocTests.T8d_01_deployMdChecks` | DEPLOY.md lacks the historical literal tunnel command; same Phase 2 assertion remains open. |

An intermediate run also exceeded the old `/senders` coarse timing budget. The
exact test passed on unchanged Phase 2 source in a separate temporary copy and in
the fresh Java 21 full run. Treat that legacy timing as variable, not a demonstrated
performance fix. The new dashboard has its own passing measured budgets below.
The temporary baseline copy and all database fixtures were independent of the
retained checkout/database. No release-ready claim is made while the two known
assertions and later-phase gates remain open.

## Measured performance

Local synthetic data, one warmup and ten samples per operation; p95 is the maximum
with this sample size. The authenticated response measurement includes both real
MockMvc HTML rendering and the JSON data endpoint; it excludes browser/network
latency. Concurrent workers repeatedly commit 200-row metadata updates, not real
Gmail sync traffic. Values below are milliseconds from the fresh Java 21 full run; raw measurements
are saved in [phase-3-performance.csv](phase-3-performance.csv).

| Messages / writer | Summary + page p95 | Date/search p95 | Sender page 10 p95 | Message/address expansion p95 | Authenticated HTML + data p95 |
|---|---|---|---|---|---|
| 10,000 / idle | 44.53 | 55.82 | 43.13 | 17.99 | 63.82 |
| 10,000 / active | 46.83 | 63.92 | 44.75 | 21.83 | 57.59 |
| 50,000 / idle | 215.12 | 274.75 | 214.21 | 19.54 | 246.04 |
| 50,000 / active | 347.15 | 281.63 | 323.14 | 13.08 | 344.72 |

All paths passed their Phase 2 budgets (10k: 500/250/2,000 ms; 50k:
2,000/500/3,000 ms for browsing/expansion/authenticated response). The active
workers committed 183 and 808 batches. A 501-ID preview took 42.68–86.19 ms;
5,000 IDs took 214.29–303.01 ms across sizes and writer conditions, with the exact
selected counts asserted. No throughput guarantee is inferred for a real mailbox.

## Browser evidence and boundaries

The browser harness serves the actual Thymeleaf output and production MockMvc
routes through a loopback-only HTTP bridge, with a synthetic authenticated account,
shared session and normal CSRF tokens. Providers are mocked. It is explicitly not
an OAuth login against Gmail or acceptance of the deployed application.

| Browser check | Observed result |
|---|---|
| Inbox vs All received | 288 vs 360 synthetic messages; archived messages appear only in All received |
| Group/message synchronization | Select 24; deselect one → 23 and mixed parent; second message page retained selection |
| Independent sender paging | Second sender page retained 23; its page checkbox added only 48 → 71 |
| Exact preview | 71 fixed targets, 71 effective, zero skips; target pages 1–10 and 11–20 inspectable; dispatch disabled |
| Filter clearing | Any time → last 7 days → Any time left zero selections and hidden action bar |
| Group correction | Explicit preview/apply merged two atomic units; groups 12→11; matching mail remained 360; no mail selected |
| Expansion | Merged group showed 60 messages and two addresses, 30 each; message pages remained independent |
| Desktop/narrow view | Split pane inspected on desktop; 390px stack inspected; selection bar reflows, bottom padding reserves its height |

Screenshots: [desktop](screenshots/phase-3-desktop.jpg),
[390px mobile](screenshots/phase-3-mobile.jpg). These show synthetic data.

Full responsive/accessibility acceptance, real OAuth/browser persistence across
restart, live provider behavior and durable job controls remain in later phases.
No retained database migration, reset, backup, app restart, Git publication or live
mailbox action was performed during this phase. Apply V16 to the retained index
only with Phase 2's verified backup procedure at the planned upgrade gate.

Phase 3 implementation and its bounded gate are complete. Phase 4 is next:
Mark as Read from exact preview into a durable background job, including
idempotency, cancellation, acknowledged accounting and recovery.
