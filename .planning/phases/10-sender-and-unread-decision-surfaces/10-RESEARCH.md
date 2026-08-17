---
phase: 10-sender-and-unread-decision-surfaces
status: complete
created: 2026-08-09
---

# Phase 10 Research: Sender And Unread Decision Surfaces

## Research Question

How should TidyMail implement reproducible sender/unread decision surfaces and exact
selection previews on the completed Phase 9 metadata/action-scope foundation without
provider reads, privacy expansion, or premature Phase 12 execution?

## Inputs Read

- Phase 10 roadmap and all 14 mapped requirements.
- Completed Phase 9 context, research, plans, summaries, verification, migrations,
  entities, repositories, services, controllers, templates, fragments, CSS, and tests.
- The inherited Phase 10 exploration findings for data/query design, exact selection
  materialization, and MVC/UI integration.
- Sender and Unread compact-suite mocks.

## Existing Foundation

### Database and privacy

`message_metadata` already stores the exact approved projection needed here:
account/provider IDs, message/thread IDs, sender display/address/domain, subject
header, received/sync times, unread, labels, recipient arrays, unsubscribe/list
headers, precedence, auto-submitted, message/reference/in-reply-to, return-path,
reply-to, delivered-to, and feedback IDs. It has no body, snippet, MIME, attachment,
token, or generic raw-header column.

V6 indexes account plus received time, sender, domain, unread, thread, and last sync.
Phase 10 needs V7 indexes for case-normalized search/grouping and common unread
predicates. PostgreSQL expression indexes are not portable to H2, so persist the
already-normalized sender address/domain consistently and use explicit lower-case
query inputs; add composite B-tree indexes that both dialects support.

### Query and account boundaries

`OwnedAccountScopeService` is the established fail-closed resolver for default,
numeric, and `all` Gmail account selection. `MessageMetadataQueryService` is
provider-free but only exposes primitive pages. Phase 10 should add dedicated
sender/unread query services rather than overload this foundation with UI-specific
projection and classification concerns.

`WorkspaceModelService` owns account options, queue count, sync state, freshness, and
shared route data. Route-specific models should carry a `WorkspacePageModel` plus
typed query/result data.

### Action scopes

`ActionScopeService` already guarantees account ownership, sorted/deduplicated exact
IDs, versioned length-prefixed SHA-256 identity, typed bounded summaries, immutable
persistence, race-safe create-or-get, and downstream integrity checks.

Its current validation only models a single `SENDER_ADDRESS`; Phase 10 needs typed
target kinds for sender selection and unread selection, including multiple targets.
The existing `ActionScopeTargetSummary.selectedTargetCount` and
`ActionScopeRiskSummary` bounds are 1,000, which supplies the exact materialization
cap. Preview UI must hide raw provider IDs even though the server record retains them.

`BulkActionApiController.bulkBySenderFragment` is a legacy bridge: it caps at 1,000
after loading pages, infers protected messages from labels, supports only one current
account/sender, and returns a preview that can queue immediately. Phase 10 should add
a separate selection-preview controller/service and leave execution migration to
Phase 12.

### MVC and UI

`WorkspaceController` is intentionally the sole GET owner for all five routes.
`senders.html` and `unread.html` are truthful Phase 9 placeholders using shared nav,
rail, state, account scope, sync forms, and local `workspace.css`.

The mocks define the desired density and information hierarchy but include fabricated
metrics, decorative links, CDN icons, and action buttons. Preserve their labels,
table structure, metric strip, sticky selection bar, and filter concepts; replace
sample content with database facts and keep Phase 12 actions non-executable.

## Recommended Architecture

### 1. V7 normalized state and audit tables

Create equivalent PostgreSQL/H2 migrations:

`protected_senders`

| Column | Contract |
|---|---|
| `id` | Dialect identity primary key |
| `account_id` | Owned account FK, cascade delete |
| `sender_address` | Lower-case normalized address, max 320 |
| `source` | `MIGRATION`, `USER`, or `ACTION` |
| `created_at` | Immutable timestamp |

Unique `(account_id, sender_address)`. Index `(account_id, sender_address)` and
`(account_id, created_at)`.

`protected_override_audits`

| Column | Contract |
|---|---|
| `id` | Identity primary key |
| `account_id` | Owned account FK, cascade delete |
| `selection_surface` | `SENDERS` or `UNREAD` |
| `selection_mode` | `INDIVIDUAL`, `VISIBLE_PAGE`, `ALL_MATCHING` |
| `action_type` | Typed preview action |
| `query_fingerprint` | Lower-case SHA-256 of canonical normalized query |
| `protected_sender_count` | Non-negative |
| `protected_message_count` | Non-negative |
| `reason_code` | Bounded enum-like value |
| `created_at` | Immutable timestamp |

The audit deliberately excludes query JSON, subject, sender display name, provider
IDs, headers, and tokens. Index `(account_id, created_at DESC)`.

Add composite metadata indexes for `(account_id, unread, from_address, received_at,
id)` and `(account_id, from_address, received_at, id)`. Keep both migrations in
lockstep and extend PostgreSQL Testcontainers from V6 rather than replacing it.

### 2. Typed query records

Implement `SenderQuery` and `UnreadQuery` as immutable records with nested enums or
shared enum classes. A dedicated parser/factory:

- validates all values;
- supplies documented defaults;
- trims and bounds search;
- normalizes sender filters;
- clamps no values silently except omitted defaults;
- exposes canonical ordered query parameters;
- computes offset from fixed page size 25;
- renders every normalized parameter, including defaults, exactly once in canonical
  URLs while parsing omitted and explicit defaults identically.

Use one predicate builder per surface so count, page rows, visible-page
materialization, and all-matching materialization cannot drift.

### 3. Versioned rules plus database classification

Use the exact `DecisionRuleSet.V1` precedence in CONTEXT. Repository projections
carry facts and already-classified enum values; Java maps those values and stable
reason codes to display copy and independently evaluates parity fixtures. Do not
classify only the 25 rows after database pagination: type/risk/recommendation filters,
counts, and ordering would then be incorrect.

`SenderFacts` should include normalized sender address, representative display name,
domain, total count, unread count, oldest/newest received time, list/bulk/automated
signal counts, thread/reply signal counts, queued state, and protected state.

`UnreadFacts` should include metadata row/account/provider/message/thread identity,
sender fields, subject header, received time, list/bulk/automated signals, and
thread-reference counts. Never expose recipient/header arrays directly to templates.

Presenters return records, for example:

- `SenderDecision(type, risk, recommendation, reasonCodes)`
- `UnreadDecision(category, threadState, recommendation, reasonCodes)`

Suggested stable reason codes include `LIST_HEADERS_PRESENT`,
`AUTOMATED_HEADER_PRESENT`, `HIGH_UNREAD_RATIO`, `REPEAT_VOLUME`,
`RECENT_ACTIVITY`, `PROTECTED_SENDER`, `DIRECT_MESSAGE`, `HAS_THREAD_REFERENCES`,
`WAITING_ON_ME`, `OLD_UNREAD`, and `NO_REPLY_SIGNAL`. Copy belongs in a presenter or
enum method so tests can verify every code has accessible text.

The rule set must stay conservative. Ambiguous human-thread cases recommend
`REVIEW` or `KEEP`, never `TRASH`. `REPLY_REQUIRED` is only emitted when direct-mail
and thread-reference facts support it. No subject-keyword classifier is needed;
subjects are display/search metadata, not a semantic oracle.

### 4. Portable dynamic grouped SQL

Build each surface from one fixed-fragment CTE factory: authorized/base rows,
aggregate facts, V1 classified facts, filtered facts, then count/metrics/page or
materialization. Sender aggregation returns one row per `(account_id, sender_key)`;
equal addresses in different accounts never consolidate. A missing address uses an
internal `__missing__` key and visible `Unknown sender`, but cannot match protection.

Every order includes tie-breakers. Avoid pageable native queries with a fragile
auto-derived count. A custom repository may use `NamedParameterJdbcTemplate` or
`EntityManager`, but dynamic choices append only allowlisted fixed SQL fragments and
all data values are bound. Search uses case-insensitive `LIKE ... ESCAPE '!'` after
escaping `!`, `%`, and `_`.

Queue filtering can use an `EXISTS` predicate over QUEUED `user_actions` keyed by
account and `sender_group_key`; unread queue state is not a Phase 10 requirement.
Protected state uses `EXISTS` against `protected_senders`.

The same runtime SQL executes in H2 PostgreSQL mode and PostgreSQL 16. Array-backed
signals use only `CARDINALITY`, one-based first-element subscripting for the
single-valued Auto-Submitted/Precedence headers, and portable `LOWER(TRIM(...))`.
Every subscript is guarded by `CASE WHEN CARDINALITY(column) > 0`; direct access to
an empty H2 array raises an error.
Parity fixtures include `Auto-Submitted: no` and non-bulk Precedence so mere header
presence cannot become an automated signal. PostgreSQL-only casts, JSON/array
operators, `FILTER`, `DISTINCT ON`, and null-order syntax are forbidden from shared
queries. Portable CASE expressions implement null-last ordering. Count, metrics,
page keys, page rows, exact counts, and materialization IDs all come from the same
CTE/predicate object.

### 5. Exact materialization

Introduce:

- `SelectionMode`
- `SelectionRequest`
- `SelectionMaterializationService`
- `ProtectedOverrideAuditService`
- `SelectionPreviewController`

The service accepts authorized account IDs, surface type, canonical query, selection
mode, opaque row keys, action type, and override request. It uses the same predicate
builder as browsing.

For sender selections, materialize all matching metadata rows for selected sender
keys. For unread selections, materialize unread metadata rows only. For visible page,
rerun page keys and then materialize their messages. For all matching, rerun the full
predicate. In one repeatable-read transaction, compute exact distinct counts per
account first and use `LIMIT 1001` as a consistency guard. Reject empty, mismatched,
or over-cap results before persistence. The 1,000 limit is per account-owned scope.

Protected behavior:

- Every mode excludes protected senders unless explicit override is present.
- The non-override result reports excluded protected counts. It returns READY for
  remaining unprotected targets or PROTECTED_BLOCKED when none remain.
- Override reruns the same selection with protected rows and requires a reason.
- Audit and every newly created scope commit or roll back together.
- Never trust a submitted protected count.

For `account=all`, an `ActionScope` is account-owned and cannot span multiple account
IDs. Therefore a single preview request must return one preview per account, each
with its own exact scope/hash, plus aggregate display counts. This preserves Phase 9
ownership invariants. The UI must state `2 exact scopes across 2 accounts` rather
than pretending one cross-account hash exists.

The existing `ActionScopePersistenceService.insert` uses `REQUIRES_NEW`, so it cannot
provide this atomicity. Add a Phase 10 coordinator that locks owned account rows in
ascending ID order and uses a REQUIRED scope-insert method. Preserve the legacy path,
ActionScope hash bytes, and the four-field `ActionScopeTargetSummary` JSON shape.
`SENDER_SELECTION` and `UNREAD_SELECTION` use the existing nullable summary fields.
Current selection counts/risks live in the aggregate result because a reused exact
scope may carry an older summary.

### 6. Preview fragments

Create a Phase 10-specific preview fragment that renders:

- exact message count;
- selected sender/message target count;
- account count/scope count;
- protected/high-risk counts;
- cap or validation errors;
- each scope hash;
- explicit `Preview only - no mailbox changes have been queued or applied`.

It must not render `/scopes/{id}/queue`, immediate execution, or approval controls.
Those belong to Phase 12.

Never place `ActionScopePreview` in the template model because it exposes
`messageIds`. Map it to an ID-free digest containing only account label, hash, and
message count.

Hiding the Phase 9 queue button is insufficient because its POST endpoint already
exists. During Phase 10, `UndoService.createScopedActionRow` must reject
`SENDER_SELECTION` and `UNREAD_SELECTION`; only legacy `SENDER_ADDRESS` remains
queue-compatible. Phase 12 will deliberately adopt the new target kinds.

### 7. Provider links

Build Gmail links locally from owned account email plus stored thread/message ID.
Strictly encode the account path segment and fragment target independently while
preserving the literal `#all/` delimiter. Parse and validate the final URI: HTTPS,
`mail.google.com`, no user-info/port/query, and no controls or slash/backslash
traversal in decoded segments. The link opens the exact provider item but does not
verify it through the API. No link is rendered if identity is missing or provider
unsupported.

## Query And UI Contract

### Senders

Default URL semantics:

`/senders?account={owned}&window=30d&type=all&risk=all&unread=all&queue=open&protected=exclude&sort=volume_desc&page=0`

Rows display sender, domain/address, type, volume, unread count/ratio, last activity,
recommendation, risk, protected state, and visible explanation. Metrics are computed
from the same filtered result, not from mock constants.

### Unread

Default URL semantics:

`/unread?account={owned}&type=all&age=all&waiting=all&thread=all&recommendation=all&provider=all&sort=oldest_first&page=0`

Rows display age, sender, subject header, thread state, deterministic category,
recommendation, visible reasons, and provider link. Do not copy mock context
sentences because they imply body-derived summaries.

## Test Strategy

### Foundation

- H2 V7 upgrade and clean migration with Hibernate validation.
- PostgreSQL 16 V1-V7 upgrade preserving V6 rows, constraints, and identity.
- Dialect parity assertions for both new tables, constraints, indexes, and privacy
  column allowlists.
- Query parser defaults, canonical round-trip, invalid enum/page/search, and page
  reset tests.
- Classifier table tests for every enum/reason and conservative ambiguous cases.
- SQL/Java rule parity for every V1 branch and exact 7/30/90-day and unread-ratio boundary.
- Deterministic sorting/tie/null behavior and stable pagination under seeded data.
- Search/filter/account isolation and SQL wildcard escaping.
- The same grouped-query matrix on H2 and PostgreSQL 16 with count/metric/page parity.

### Materialization

- Individual, visible-page, and all-matching exact IDs for both surfaces.
- Browser-submitted IDs cannot broaden server materialization.
- Same predicate drives display count and exact scope IDs.
- `account=all` produces separate account-owned scopes.
- Protected exclusion plus excluded counts in server predicates, explicit override, immutable audit
  content, CSRF, ownership, and no client-count trust.
- Exactly 1,000 per account succeeds; 1,001 returns 422 and persists no new scope/audit.
- Multi-account failure rolls back every new scope/audit despite the legacy REQUIRES_NEW path.
- Preview fragment contains hashes/counts but no raw IDs and no queue/apply endpoint.
- Provider mocks receive zero interactions.

### MVC/UI

- Full-page and htmx fragment requests reproduce canonical URLs and preserve filters
  through pagination/account changes.
- Real database rows replace placeholders; zero-result empty state keeps filters.
- Every badge/recommendation/risk/metric has visible or associated explanation.
- Semantic tables, captions, scoped checkbox labels, fieldsets, status/alert regions,
  focus styles, 44px narrow targets, and no page-level 375px overflow contract.
- Gmail deep links are exact, encoded, HTTPS, and `noopener`; unsupported providers
  have no link.
- Warm deterministic 10,000-row filtered requests stay below two seconds in H2 MVC
  and PostgreSQL 16 repository coverage, with correctness assertions outside timing.
- `verifyNoInteractions(gmailProvider, outlookProvider)` after all GET and preview
  requests.

## Risks

| Risk | Mitigation |
|---|---|
| Sender aggregate, classifier, and selection predicates drift | One CTE factory feeds count, metrics, page, exact count, and materialization; SQL/Java parity fixtures lock V1 rules. |
| A cross-account scope violates Phase 9 ownership | Create one immutable scope per account and aggregate only at the preview presentation layer. |
| Cap silently changes semantics | Query cap-plus-one and return 422 without scope persistence. |
| Protected rows leak into bulk scopes | Enforce exclusion in repository predicates; audit any explicit override before creating scopes. |
| Deterministic labels become misleading | Conservative rules, stable reason codes, visible explanations, and no body/AI inference. |
| Native SQL differs between PostgreSQL and H2 | Keep SQL portable, maintain paired V7 migrations, validate both dialects and upgrade paths. |
| Legacy REQUIRES_NEW creates partial multi-account previews | Use a locked REQUIRED Phase 10 transaction coordinator and rollback tests; leave the legacy path unchanged. |
| Template can traverse raw provider IDs | Expose only ID-free scope digests, never ActionScopePreview. |
| Crafted POST reaches the existing queue endpoint | Reject Phase 10 target kinds in UndoService until Phase 12. |
| Query-string controls lose state | Canonical query map is the only link/form source and round-trip tests cover every parameter. |
| Legacy provider-backed controller is reused | New surface services depend only on repositories; architecture and interaction tests enforce provider-free behavior. |

## Plan Recommendation

| Plan | Purpose | Requirements |
|---|---|---|
| 10-01 | V7, typed query records, deterministic classifiers, sender/unread repository queries | WORK-02, SEND-01..04, UNRD-01..04, QUAL-03 |
| 10-02 | Exact materialization, protected override audit, ActionScope preview, cap semantics | SEND-05, SEND-07, UNRD-05 |
| 10-03 | Real controllers/templates/fragments, provider links, accessibility and performance verification | WORK-02, SEND-01..05/07, UNRD-01..06, QUAL-03 |

## Conclusion

Phase 10 should remain a database and decision-surface phase. V7 normalizes protection
and override audit facts; typed records make URLs and predicates reproducible; pure
classifiers make every label explainable; and exact server materialization extends
Phase 9 `ActionScope` without queuing or executing it. This satisfies the mocks and
all 14 requirements while preserving provider isolation, metadata-only privacy, and
Phase 12 ownership of action execution.
