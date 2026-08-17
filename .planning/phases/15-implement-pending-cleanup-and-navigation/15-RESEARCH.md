# Phase 15 Research: Pending Cleanup And Navigation

**Researched:** 2026-08-11  
**Scope:** Research only. This artifact does not create a plan or implement code.

## Research Summary

Phase 15 is not primarily a visual cleanup. It replaces three incorrect runtime
patterns:

1. Sender and unread category pages currently stream and classify every matching row
   before rendering. They need exact category aggregates plus independently bounded,
   server-side five-row category pages.
2. Sender actions currently create a Selection Preview and ask the user to choose
   staged or immediate execution. Phase 15 must materialize exact provider message IDs
   and create staged Review actions directly, without leaving the cleanup page.
3. Bulk Unsubscribe currently scans all candidate sources, cleanup history, and sender
   batches on every page request, then filters and sorts in Java. This explains the
   reported navigation latency and must be replaced with a bounded query path.

Two correctness gaps are confirmed:

- `DecisionSurfaceSql.unread(...)` filters on `m.unread = true` but does not require
  the provider `INBOX` label. Archived unread Gmail messages are therefore included.
- Existing `sender_category_assignments` persist account-owned custom category
  placement, but there is no durable account-owned sender-type override. The current
  deterministic classifier will restore the inferred type on every query.

The existing exact-scope and action-command infrastructure is reusable. The safest
implementation is a cleanup staging facade that invokes the existing repeatable-read
materialization path, receives immutable action scopes and confirmation bindings, and
submits them through `ActionCommandService` with `ExecutionMode.STAGED` only.

## Sources Read

### Locked Product Sources

- `.planning/phases/15-implement-pending-cleanup-and-navigation/15-CONTEXT.md`
- `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md`
- `.planning/REQUIREMENTS.md`
- `.planning/STATE.md`
- `.planning/ROADMAP.md`
- `tidymail-ux-rehaul-draft/tidymail-ux-rehaul-draft.design`
- `tidymail-ux-rehaul-draft/pages/build-notes.html`
- The design-backed sender, unread, and unsubscribe page sources referenced by the
  `.design` project, used only to inspect the active Design Canvas pages.
- `.planning/phases/14-ui-fidelity-and-route-recovery/14-CONTEXT.md`
- `docs/DECISIONS.md`

### Main Implementation Areas

- Workspace shell: `WorkspaceModelService`, `WorkspacePageModel`, `WorkspaceRoute`,
  `workspace-rail.html`, `workspace.css`
- Cleanup query path: `DecisionSurfaceSql`, `DecisionSurfaceQueryRepository`,
  `SenderDecisionQueryService`, `UnreadDecisionQueryService`
- Classification persistence: `DecisionClassifier`, `DecisionRuleSet`,
  `SenderCategoryService`, `SenderCategory`, `SenderCategoryAssignment`
- Exact staging: `SelectionMaterializationService`,
  `SelectionPreviewTransactionService`, `ActionCommandService`,
  `ActionCommandPersistenceService`
- Cleanup UI: `senders.html`, `unread.html`, `unsubscribe.html`,
  their result fragments, and `cleanup-categories.js`
- Unsubscribe: `UnsubscribeCandidateService`, `UnsubscribeCandidateRepository`
- Existing Phase 10-14 query, web, migration, safety, performance, and fidelity tests

### Provider Confirmation

Gmail supports exact message-level Inbox-unread selection. `users.messages.list`
accepts `labelIds[]`, and all supplied labels must match; it also supports Gmail search
syntax through `q`. The local metadata index already stores Gmail message label IDs,
so Phase 15 should enforce both local facts: `unread = true` and label membership
`INBOX`. No provider request belongs in the page path.

- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list
- https://developers.google.com/workspace/gmail/api/guides/filtering

The API/UI search differences documented by Google concern aliases and thread-wide
searches, not message-level `INBOX` membership. The relevant limitation is freshness:
the local count is exact for the indexed snapshot, not necessarily for provider changes
that have not synced yet.

## Decision Coverage

| Decisions | Research conclusion |
|---|---|
| D-01, D-02 | Use only the named `.design` project and Build Notes. Update `WorkspaceRoute`, rail labels, page titles, and the brand link together. |
| D-03 | Move the existing authorized queued count into a Review badge. Add an authorized, selected-account count of messages satisfying Inbox + unread for the Unread Only badge. Remove the standalone queue row. |
| D-04, D-05 | Render email-only option labels, constrain/truncate the select, auto-submit existing account choices, and branch `add:google` to connection. A GET to the current route containing only the new `account` resets incompatible query, selection, modal, and category-page state by construction. |
| D-06 | Remove CSV/snapshot/download/export markup from every workspace template and analytics source. Backend export routes may remain, but no workspace HTML or navigation may link to them. |
| D-07 through D-11 | Sender and unread pages need one shared structural contract: quiet heading, conditional sync banner, no page account/filter/summary/export controls, controls directly above categories, corrected settings copy, standardized columns, and the unread overlap note before categories. |
| D-12 through D-14 | Replace category-wide streams with exact aggregate queries and category-specific row queries. Initial/open limit is 5, load-more increment is 5, load-all is explicit, order is volume descending plus deterministic identity ties, and collapse discards the category body/cursor. |
| D-15, D-16 | Add an account-scoped normalized sender-type override. Join it into SQL classification and apply the same override in Java parity checks. Keep deterministic reasons that distinguish manual override from inferred evidence. Add fixtures for all four observed false-positive directions. |
| D-17 through D-19 | All sender rows need selection and category/type correction controls. Preserve overlapping unread groups. Keep custom category assignment as a separate account-owned fact and make it affect category presentation on future requests/syncs. |
| D-20 through D-23 | Reuse exact materialization and immutable scopes, but add a direct staged-only facade. Never derive action scope from category counts or sizes. Return an in-place staged state. Delete requires visible Gmail Trash recovery wording and server-validated acknowledgement. |
| D-24 | Add Inbox label membership to the unread base query, all unread aggregates, materialization, and rail count. Test archived unread exclusion. |
| D-25, D-26 | Preserve unsubscribe category, exact date range, search, Archive/Delete default, individual rows, eligibility, destination safety, and outcome truthfulness while matching the design shell and hierarchy. |
| D-27 | Profile the current default route with production-shaped PostgreSQL data. Replace unbounded candidate/sender/history scans with bounded repository work and verify query plans and response budgets. |

## Folded Todo Coverage

The folded 31-item todo must remain visible in downstream planning. The implementation
implication of every item is:

| Todo | Required planning input |
|---:|---|
| 1 | Brand is a link to `/senders`. |
| 2 | Rail label and page title are `Bulk Archive/Delete`. |
| 3 | Rail label and page title are `Unread Only`. |
| 4 | Rail label and page title are `Bulk Unsubscribe`. |
| 5 | Remove standalone queued-actions row; badge Review. |
| 6 | Add authorized Inbox-unread message count beside Unread Only. |
| 7 | Restyle account select and remove provider suffixes. |
| 8 | Remove Switch account; auto-submit; preserve Add new account. |
| 9 | Put Settings and Expand/Collapse together above categories. |
| 10 | Remove the toolbar/card wrapper. |
| 11 | Remove all user-facing export and snapshot affordances. |
| 12 | Profile and repair Bulk Unsubscribe backend latency. |
| 13 | Use `Choose the default action`, `Archive`, `Delete`, `Mark as Read`. |
| 14 | Sender action directly stages exact IDs for Review. |
| 15 | Return a disabled/muted staged row action; no inline undo. |
| 16 | Use sender, domain, message count, size, action column order. |
| 17 | Use `Size (Estimated)` heading and value-only size cells. |
| 18 | Open category with five rows only. |
| 19 | Center a subtle, non-primary Load more control. |
| 20 | Load +5; then show Load more and Load all; collapse resets to five. |
| 21 | Enforce query-level/server-side bounds; never preload hidden rows. |
| 22 | Put checkboxes and correction controls in all sender categories. |
| 23 | Persist correction as account-owned feedback with inference precedence. |
| 24 | Correct and fixture personal/automated/transactional/list rules. |
| 25 | Support cross-category multi-sender staged Archive/Delete. |
| 26 | Materialize exact IDs for every selected sender before Review creation. |
| 27 | Trace and correct the current All Mail unread count. |
| 28 | Require Inbox + unread and document indexed-snapshot freshness. |
| 29 | Apply the complete shared cleanup contract to Unread Only. |
| 30 | Move the non-mutual-exclusivity note above unread categories. |
| 31 | Align Bulk Unsubscribe without weakening its distinct safety model. |

## Current Implementation Gaps

### Rail And Account Scope

`workspace-rail.html` currently:

- renders an unlinked brand;
- labels routes `Senders`, `Unread`, and `Unsubscribe`;
- renders queue status as a footer paragraph;
- has no unread count;
- displays account labels produced as `email (Gmail|Outlook)`;
- requires a `Switch account` button;
- only handles Add new account in the select's change listener.

`WorkspaceModelService` already resolves one owned selected account and computes the
queued count from authorized IDs. It is the correct place to add Inbox-unread count
and email-only labels. Keep failure handling explicit: count failures must not expose a
foreign count or break ownership, and the UI should omit an unavailable badge rather
than display a fabricated zero.

`WorkspaceRoute` still contains obsolete titles and export-oriented Analytics copy.
Updating it prevents old terminology from leaking into shared state/error fragments.

### Cleanup Page Shell

The sender and unread templates already have closed `<details>` categories and centered
dialogs, but they violate the new contract:

- visible export links;
- toolbar card with current-action explanatory copy;
- old setting labels (`Bulk archive`, `Bulk delete`);
- no semantic column header row;
- row order is sender, count, size, domain, action;
- `Estimated` is embedded in values rather than only in the heading;
- the unread overlap note is after the category list;
- Selection Preview slots remain in both pages.

The existing CSS tokens, 248px rail, category accents, dialogs, focus styles, responsive
breakpoints, and 44px narrow-screen targets are reusable. The cleanup toolbar rules
should be replaced by a minimal right-aligned action row, not restyled into another
container.

### Category Query Shape

`SenderDecisionQueryService.categories(...)` calls
`DecisionSurfaceQueryRepository.streamSenders(...)`, classifies every sender in Java,
stores all rows in lists, sorts them, and derives counts from those lists.

`UnreadDecisionQueryService.categories(...)` is more expensive: it streams every
matching unread message, accumulates every sender and every message row key in memory,
then builds five overlapping category lists. This also means category header counts are
coupled to full row materialization.

The existing `senders(...)` and `unread(...)` methods have SQL `LIMIT/OFFSET`, but those
are for legacy global pages and are not used to bound category rendering. Reusing them
with client-side filtering would not satisfy D-12 through D-14.

Recommended repository contract:

```text
categorySummaries(accountIds, canonicalQuery, now)
categoryPage(accountIds, canonicalQuery, categoryKey, offset, limit=5, loadAll=false)
```

Each summary must return exact sender count and optional exact/qualified size without
returning row bodies. Each page must execute a category predicate in SQL before
`ORDER BY volume DESC, account_id ASC, normalized_sender ASC LIMIT/OFFSET`.

Prefer offset for this phase because category pages are short-lived, collapse resets
them, and the account is single-selected. Include complete deterministic tie-breakers.
A cursor is optional, but it must bind account, category, filters, sort, and last row;
never accept a raw client offset/cursor without rebuilding the authorized query.

Category response state should include:

- category key and authorized account;
- returned rows;
- loaded count and exact total;
- `hasMore`;
- whether the first increment has occurred, controlling Load all visibility;
- a canonical next request.

`Load all` is explicit user intent but is not unbounded. The Phase 15 product ceiling
is 1,000 cumulative sender rows per category response lifecycle, including rows already
loaded. An exact category total of 1,001 or more returns HTTP 422 with guidance to keep
using five-row `Load more` requests; it must not issue an all-row query or replace rows
already rendered. This row-return ceiling is independent of the existing 1,000 exact
provider-message-ID action cap.

### Account-Scoped Classification Feedback

Current classification exists twice:

- SQL in `DecisionSurfaceSql`;
- Java in `DecisionRuleSet`, checked by `DecisionClassifier`.

Any rule correction must update both or the parity guard throws. Current precedence is
list signal, automated signal, transactional return path, personal thread signal, then
unknown. This explains common false positives: a single list/automation header always
wins over direct/thread evidence.

Recommended migration (next version after V13, with an H2 twin):

```text
sender_type_overrides
  id
  account_id              FK user_accounts ON DELETE CASCADE
  sender_address          normalized lowercase addr-spec
  sender_type             LIST|AUTOMATED|TRANSACTIONAL|PERSONAL
  created_at
  updated_at
  UNIQUE(account_id, sender_address)
  CHECK normalized address and allowed type
  INDEX(account_id, sender_type, sender_address)
```

Do not store the override on `message_metadata`: sync upserts could overwrite it and
would duplicate one user fact across every message. Join the override once at the
sender identity level. The override must replace inferred type before risk,
recommendation, sender-category membership, and unread list/direct membership are
computed.

Add a reason such as `MANUAL_SENDER_TYPE_OVERRIDE` so classification remains
explainable. `DecisionClassifier` should compare effective SQL type with effective Java
type, while dedicated tests still exercise inference with no override.

Treat custom category assignment and semantic type override as separate facts:

- choosing a built-in category upserts the semantic type override;
- choosing a user-created category persists the existing category assignment;
- custom assignment wins sender-page placement;
- the semantic type still controls unread list/direct classification;
- moving between custom categories updates the existing unique assignment;
- removing a custom assignment reveals the effective manual/inferred built-in type.

The service transaction must validate account ownership for account, custom category,
and sender identities. Never infer ownership from submitted hidden fields.

### Exact-ID Direct Staging

The exact foundation is already sound:

- repeatable-read account locking;
- authorized row-key decoding;
- server query re-materialization;
- distinct provider message IDs;
- a 1,000-ID per-account guard;
- immutable action scopes and hashes;
- protected-sender exclusion/audit;
- signed confirmation intents;
- staged persistence through `ActionCommandService`.

The missing layer is a cleanup-specific staged command endpoint/facade. It should:

1. Parse a strict request: surface, action, canonical filter state, and one or more
   account-bound sender row keys.
2. Resolve the current owned account server-side.
3. Materialize exact qualifying IDs using the same base predicates as the displayed
   category, including Inbox-only unread.
4. Deduplicate IDs across overlapping unread categories and repeated sender presence.
5. Reject stale/foreign senders, protected-only selections, empty scopes, and cap
   overflow without creating `UserAction` rows.
6. Create/reuse immutable scopes and issue confirmation bindings.
7. Submit through `ActionCommandService` with `STAGED`; never accept an execution mode
   from the cleanup form.
8. Return an HTMX row/bulk result that marks every affected visible sender action
   disabled and muted, updates the Review badge, and preserves the open category.
9. Provide a normal POST fallback that redirects back to the same cleanup route, not
   Selection Preview or Review.

Unread category rows are sender-level, while current unread individual
materialization accepts metadata-row keys. Add a sender-level unread materialization
path rather than trusting the browser's accumulated metadata IDs. The SQL should select
all Inbox-unread messages matching the authorized canonical query and selected sender
identities, then deduplicate provider IDs.

For every selected sender, populate `ActionScopeTargetSummary.senderAddress` and stage
one account-local Review action with that sender's immutable exact-ID scope. This
per-sender shape matches Review row identity, conflict handling, LIFO ordering, and
disabled-row reconciliation. Normalize repeated row keys to one sender, enforce the
1,000-ID cap over the union before any write, and persist all new actions atomically in
canonical sender order. Active-equivalent actions are reused idempotently; incompatible
actions retain Review conflicts; mixed duplicate/new batches create only missing
actions. Tests must cover duplicate sender keys, repeated/concurrent requests,
conflicts, deterministic LIFO order, rollback, and disabled-row reconciliation.

Use a render-scoped idempotency token or active-equivalent-action check in addition to
button disabling. UI disabling alone cannot prevent a double-click race or repeated
normal POST.

Delete requires a centered confirmation containing the Gmail typical 30-day Trash
recovery limit. The server must require a delete-warning acknowledgement for cleanup
TRASH staging; a client-only `confirm()` is insufficient.

### Inbox-Only Unread

The defect is at `DecisionSurfaceSql` unread base: only `m.unread = true` is present.
The labels array is already persisted by `MessageMetadataMapper`.

Add a dialect-aware label membership helper, following
`AnalyticsMailboxRepository`:

```text
PostgreSQL: 'INBOX' = ANY(labels)
H2:         ARRAY_CONTAINS(labels, 'INBOX')
```

Apply the predicate once in the unread base CTE so it automatically governs:

- unread message browsing;
- sender/category aggregates;
- exact action materialization;
- rail unread count;
- deep-link result rows;
- any retained unread export backend.

Add an index based on measured PostgreSQL plans. Existing
`(account_id, unread, from_address, received_at, id)` helps unread sender grouping but
does not index array membership. A partial PostgreSQL index such as
`(account_id, from_address, received_at DESC, id DESC) WHERE unread = true AND
'INBOX'=ANY(labels)` may be useful; verify actual planner use before locking it in.
The H2 migration only needs behaviorally equivalent schema, not an identical index
implementation.

### Rail Counts

Add `Long inboxUnreadCount` and an availability helper to `WorkspacePageModel`.
Compute it from the same reusable Inbox predicate and selected authorized account IDs.
Do not use `WorkspacePageModel.indexedCount`, sender counts, Gmail
`resultSizeEstimate`, or category totals.

The queue count is already selected-account scoped. Move it into the Review link and
keep its unavailable state non-fabricated. Both badges need accessible labels, not
color-only meaning.

### No-Export UI

User-facing export remains in:

- `senders.html`
- `unread.html`
- `unsubscribe.html`
- `analytics.html`
- `analytics-source.html`
- `fragments/review-history-frag.html`

Remove those anchors and any surrounding empty action containers. Update tests that
currently assert their presence. Backend controllers/services may remain under D-06,
but workspace templates, navigation, buttons, help text, and route descriptions must
not advertise CSV, snapshot, download, or export.

A negative template scan should fail if workspace HTML contains:

```text
.csv
snapshot
download
workspace-download
analytics-download
Export
```

Exclude unrelated privacy prose such as the login page's statement about not
downloading email bodies.

### Bulk Unsubscribe Performance

The current request does all of the following before returning one 25-row page:

- scans every unsubscribe source candidate in batches of 200;
- parses and classifies every candidate in Java;
- fetches latest outcomes for all fingerprints;
- scans all successful cleanup execution targets and builds a dynamic OR predicate;
- scans every sender aggregate in batches of 200;
- filters, counts, and retains a top-N priority queue in Java.

This is unbounded relative to requested page size and is the primary performance risk.

Profile the real default navigation and each visible filter with:

- production-shaped sender/message/candidate/outcome/action cardinalities;
- SQL statement count and timings;
- `EXPLAIN (ANALYZE, BUFFERS)` on sender aggregation, candidate source, latest outcome,
  and cleanup-history queries;
- warm and cold request timing;
- heap/allocation evidence if Java classification remains broad.

Preferred optimization sequence:

1. Build an SQL sender-summary/count query that applies account, exact date range,
   category lifecycle, search, protection, and stable default volume ordering before
   `LIMIT/OFFSET`.
2. Fetch unsubscribe source metadata only for the page's sender identities.
3. Batch latest outcomes only for page candidate fingerprints.
4. Replace the dynamic cleanup-target OR list with relational joins/`EXISTS`.
5. Keep URI parsing and eligibility classification in Java for the bounded page only.
6. If non-default confidence/method sorts still require a global Java scan, either
   implement equivalent persisted classification facts or retain a separately measured
   bounded fallback. Do not silently change safety classification.

If page-bounded joins cannot meet the budget because lifecycle/outcome identity is
available only as hashes, introduce an account-owned derived unsubscribe projection
maintained during metadata sync/outcome updates. That is a migration-backed fallback,
not the first change. It must never store message bodies, destination secrets, or
unredacted URLs.

Acceptance should use the existing localhost requirement: warm indexed results under
2 seconds, with an additional tighter regression threshold chosen from stable CI
measurements. A tiny fixture is not evidence for D-27.

## Reusable Patterns

- `AnalyticsMailboxRepository.hasLabel(...)` provides the established PostgreSQL/H2
  array-membership pattern.
- `SelectionPreviewTransactionService` provides repeatable-read locking, exact scope
  creation, cap handling, protected audit, and confirmation bindings.
- `ActionCommandService.submit(..., "STAGED", false)` creates Review items without a
  provider call.
- `ActionCommandController` demonstrates strict parameter allowlists and HTMX/native
  response branching.
- `SenderCategoryService` provides account ownership and sender normalization.
- `ReviewActionQueryRepository` demonstrates stable server-side page ordering.
- Existing category dialogs, CSS accents, focus behavior, responsive breakpoints, and
  HTMX fragments can be retained.
- Phase 10 PostgreSQL tests demonstrate Flyway upgrade, Hibernate validation,
  `EXPLAIN`, 10k-row fixtures, exact-ID assertions, and H2/PostgreSQL parity.

## Tests That Must Change

Several existing tests encode contracts Phase 15 intentionally supersedes:

- `Phase14WorkspaceFidelityTest` expects old rail labels, standalone queue copy,
  `Switch account`, and Selection Preview forms.
- `Phase14InteractionFidelityTest` explicitly expects sender, unread, analytics,
  snapshot, and history download controls.
- `Phase10SenderUnreadWebTest` expects `Current action: Bulk archive/delete`, full
  category row rendering, Selection Preview actions, and the unread note after rows.
- `Phase10DecisionQueryTest` and `Phase10PostgresMigrationTest` seed unread rows without
  Inbox labels and currently expect all unread mail.
- `Phase10SelectionMaterializationTest` unread fixtures omit Inbox labels and exercise
  metadata-row selection rather than sender-level cleanup staging.
- Unsubscribe pagination tests validate stable pages but do not prove bounded backend
  work.

Update these tests in the same tasks as their production contract changes. Do not leave
contradictory Phase 14 assertions to be fixed in a final cleanup plan.

## Validation Architecture

### Test Layers

| Layer | Purpose | Suggested coverage |
|---|---|---|
| Pure classification tests | Keep deterministic inference and override precedence explainable | Four observed false-positive directions; no-override inference; override reason; account isolation |
| Repository/H2 tests | Fast query behavior and service composition | Category summaries, five-row pages, +5, load-all, stable ties, custom categories, Inbox-only unread, selected-sender deduplication |
| Migration tests | Schema, constraints, cascade, indexes, Hibernate parity | New override table; normalized address/type checks; unique account+sender; H2 twin; production V13-to-next migration |
| PostgreSQL integration | Production SQL correctness and plans | Array membership, category aggregate/page CTEs, partial/index usage, 10k+ senders, unsubscribe `EXPLAIN ANALYZE` |
| Service/action tests | Exactness and transaction behavior | Single and multi-sender staged-only commands, immutable IDs, cap, stale/foreign keys, protected sender, idempotency, no provider call |
| MockMvc/Thymeleaf | Route, authorization, fragments, copy, and fallback | Rail badges/labels, account auto-submit, category open/load/collapse, in-place disabled actions, delete warning, no export UI |
| Static contract tests | Prevent visual/markup regressions cheaply | No forbidden export terms; no Selection Preview forms; exactly two cleanup controls; note placement; column headings/order |
| Browser/UAT | Real interaction and responsive behavior | Authenticated desktop and 375x812, 200% zoom when harness permits, keyboard dialogs/disclosures, cross-category selection, account reset |

### Nyquist Requirement Matrix

| Behavior | Automated proof | Manual proof |
|---|---|---|
| Rail naming, home link, badges | Rendered template + MockMvc ownership tests | Navigate all five routes |
| Account switch stays on route and resets state | MockMvc query-state tests | Switch account from each cleanup route |
| No export UI | Global template negative scan | Inspect all five pages and analytics source |
| Five-row category loading | Repository spy/query count + fragment tests | Open, +5, load all, collapse/reopen |
| Browser never receives hidden rows | Response body assertions for each increment | DevTools response inspection |
| Manual correction persists and is isolated | Migration/service/query tests with two accounts | Correct sender, sync/reload, switch account |
| Overlapping unread groups | Repository category membership fixtures | Observe one sender in multiple groups |
| Inbox-only unread | Inbox unread vs archived unread fixture in H2 and PostgreSQL | Compare indexed count with Gmail Inbox snapshot |
| Direct Review staging | Scope/action DB assertions and provider mock zero-interaction | Stage row and cross-category selection |
| Duplicate prevention | Concurrent/repeated POST test | Double-click/reload attempt |
| Trash warning | Server acknowledgement test + copy assertion | Stage Delete and inspect confirmation |
| Unsubscribe safety unchanged | Existing Phase 11 security/outcome suites | Inspect eligible and fallback candidates |
| Unsubscribe latency fixed | Production-shaped benchmark and SQL plan assertions | Authenticated warm/cold navigation timing |
| Responsive/accessibility | CSS/static checks + rendered landmarks | 375x812, keyboard, focus, 200% zoom |

### Required Fixtures

- Inbox unread, archived unread, Inbox read, Spam unread, Trash unread.
- More than 12 senders in one category with volume ties to prove 5/5/rest and stable
  ordering.
- The same sender in multiple unread groups, proving ID deduplication.
- Two owned accounts with the same normalized sender but different overrides.
- A foreign account/category/row key submitted to each new endpoint.
- A protected sender mixed with ordinary senders.
- 1,000 and 1,001 exact IDs for cap boundaries.
- Personal with misleading automated header, personal with transactional signal,
  transactional with list-like signal, and list with transactional signal.
- User-created category retained across metadata resync/update.
- Production-shaped unsubscribe data with hundreds of candidates and tens of thousands
  of messages, outcomes, and cleanup targets.

### Verification Commands

Downstream plans should use focused tests during implementation and finish with:

```bash
mvn -Dtest='Phase15*Test,Phase10*Test,Phase11*Test,Phase12*Test,Phase14*Test' test
mvn test
```

PostgreSQL/Testcontainers tests must not be replaced by H2-only evidence. The repository
has no Maven wrapper, so use the installed Java 21/Maven toolchain.

### Evidence Rules

- Category boundedness needs SQL/query-count evidence, not only a five-row DOM.
- Exact staging needs persisted `action_scopes.message_ids` and `user_actions` evidence,
  plus zero provider interactions before Review approval.
- Inbox semantics need a negative archived-unread fixture.
- Account isolation needs same-sender cross-account fixtures.
- Performance needs production-shaped cardinality and query plans.
- UI fidelity needs authenticated browser evidence; source assertions alone are not
  sufficient for layout, overflow, focus, and collapse-reset behavior.

## Risks And Mitigations

| Risk | Mitigation |
|---|---|
| SQL and Java classifiers diverge after overrides/rule fixes | Keep parity guard and add effective/inferred classification fixtures in both paths. |
| Header counts disagree with paged rows | Derive summaries and page predicates from one shared CTE/category definition. |
| Offset paging repeats rows under concurrent sync | Use complete deterministic ordering; optionally bind a snapshot timestamp/version in requests. |
| Overlapping unread selections duplicate message IDs | Deduplicate by account + provider message ID before scope creation. |
| Direct staging bypasses protected/cap safeguards | Wrap existing materialization transaction; do not build a second scope engine. |
| UI-only disabled state permits duplicate Review items | Add request idempotency or active-equivalent-action rejection. |
| Custom category and type override conflict | Model separate facts and define presentation/type precedence explicitly. |
| Inbox predicate performs poorly on label arrays | Measure PostgreSQL plan and add a partial/expression index only with evidence. |
| Load all creates an oversized response | Cap one category at 1,000 cumulative returned sender rows; return HTTP 422 with bounded-paging guidance for totals above 1,000 and test that no all-row query runs. |
| No-export rule conflicts with completed EXPT requirements | Treat D-06 as the latest phase contract: backend may remain, workspace discoverability must be removed. Update obsolete tests. |
| Unsubscribe optimization weakens URI safety | Keep parsing/SSRF/destination eligibility code unchanged; bound the data supplied to it. |
| Broad phase causes late integration failures | Deliver a sender end-to-end tracer first, then extend shared contracts to unread and unsubscribe. |

## Recommended Plan Decomposition

Use tracer-first ordering and keep migration/query work ahead of broad template polish.

1. **Data and query foundation:** characterize current SQL; add account-scoped type
   overrides and H2/PostgreSQL migrations; centralize Inbox membership; implement
   shared category summary/page primitives and production query tests.
2. **Bulk Archive/Delete tracer:** one category summary, five-row open/load/collapse,
   correction controls, one exact staged action, in-place disabled result, and delete
   warning through native and HTMX paths.
3. **Shared selection and Unread Only:** all categories selectable, custom categories,
   cross-category staging, overlapping unread groups, sender-level exact
   materialization, Inbox-only semantics, and shared quiet page contract.
4. **Rail and global contract:** canonical names/home link, auto-submit account switch
   with state reset, Review/Unread badges, email truncation, route-copy cleanup, and
   removal of every export/snapshot affordance.
5. **Bulk Unsubscribe:** capture baseline query plans, implement bounded data access,
   preserve all eligibility/outcome safety, align the authoritative page hierarchy,
   and add production-shaped performance regression tests.
6. **Integrated verification:** update superseded Phase 10/14 tests, run focused and
   full Java 21 suites with PostgreSQL/H2 parity, then authenticated desktop/narrow
   browser UAT. Keep public deployment blocked.

Do not defer contradictory old tests, no-export scans, Inbox-negative fixtures, or
production-shaped unsubscribe profiling to the final verification task; each belongs
with the production change it guards.

## Planning Guidance

- Phase 15 now inherits the registered action, quality, UX, and superseded-export
  requirement IDs in `ROADMAP.md`; plans also cite every D-01 through D-27 decision
  directly in `must_haves` and include the folded todo trace so neither requirement
  nor decision coverage can silently drop an item.
- Existing completed requirements are inherited safety contracts, not permission to
  retain superseded UI. In particular, D-06 supersedes user-facing EXPT controls while
  ACTN-02/03/07, QUAL-01/02/03/04, UX-01/03/04/05/07 remain regression obligations.
- A new UI-SPEC would be useful before planning because Phase 15 changes interaction
  states not fully represented in the static design: five-row loading, Load all,
  cross-category selection, staged disabled state, badge availability, and account
  reset behavior.
- No public deployment belongs in this phase. Verification remains localhost-first.
