---
phase: 10-sender-and-unread-decision-surfaces
status: planned-context
source: autonomous
created: 2026-08-09
requirements:
  - WORK-02
  - SEND-01
  - SEND-02
  - SEND-03
  - SEND-04
  - SEND-05
  - SEND-07
  - UNRD-01
  - UNRD-02
  - UNRD-03
  - UNRD-04
  - UNRD-05
  - UNRD-06
  - QUAL-03
---

# Phase 10 Context: Sender And Unread Decision Surfaces

<domain>
## Phase Boundary

Phase 10 turns the Phase 9 metadata index and shared workspace shell into two real,
provider-free decision surfaces:

1. `/senders` groups indexed metadata into deterministic sender rows.
2. `/unread` presents indexed unread messages with deterministic thread/category
   explanations and provider deep links.
3. Both surfaces reproduce browse state from the query string and materialize exact
   immutable `ActionScope` previews for individual, visible-page, and all-matching
   selections.

The phase covers exactly `WORK-02`, `SEND-01..05`, `SEND-07`, `UNRD-01..06`, and
`QUAL-03`.
</domain>

<decisions>
## Decisions

- **D-01: Typed URL State.**

All browse state is represented by immutable `SenderQuery` and `UnreadQuery` records.
Controllers bind explicit query parameters, normalize defaults, reject unknown enum
values and out-of-range pages with HTTP 400, and generate canonical query maps for
forms, pagination, fragments, and provider-return links. Search text is trimmed,
bounded to 200 characters, and never interpreted as SQL syntax.

Changing any search, filter, account, or sort control resets `page=0`. Pagination
preserves every other normalized parameter. Selection is not encoded in GET URLs.

- **D-02: Deterministic Classification Only.**

Sender type, unread category, thread state, risk, recommendation, and explanation
codes are derived only from indexed labels and approved header fields. No AI,
message-body, snippet, attachment, contact lookup, or provider request is allowed.
Each classifier returns a typed enum plus stable reason codes; presentation maps
those codes to plain-English copy.

The rules are a versioned contract, not implementation hints. `DecisionRuleSet.V1`
uses the following ordered precedence, with boundary instants supplied by one
request-scoped UTC `Clock`:

- Sender type: `LIST` when List-Id or List-Unsubscribe is present; otherwise
  `AUTOMATED` when the first Auto-Submitted value is present and not `no`,
  Feedback-ID is present, or the first normalized Precedence value is
  `bulk|list|junk`; otherwise `TRANSACTIONAL` when Return-Path is present and there
  is no reply/thread signal; otherwise `PERSONAL`
  when Message-ID is present and a reply/thread signal exists; otherwise `UNKNOWN`.
- Sender risk: `HIGH` for protected or personal/threaded senders; `MEDIUM` for
  transactional, unknown, or activity within seven days; otherwise `LOW`.
- Sender recommendation: `KEEP` for protected senders; `REVIEW` for high risk,
  personal, transactional, or unknown senders; `TRASH` for unprotected list/
  automated senders whose newest message is older than 90 days; `MARK_READ` when
  every matching message is unread; otherwise `ARCHIVE`.
- Unread category: `LIST` for list-header facts, `AUTOMATED` for automated-header
  facts, and `ONE_TO_ONE` otherwise. Thread state is `THREADED` when the stored
  thread ID occurs more than once in the authorized indexed account or references/
  in-reply-to are present; otherwise `SINGLE`. `WAITING_ON_ME` requires
  `ONE_TO_ONE`, `THREADED`, and an inbound references/in-reply-to signal.
- Unread recommendation: `REPLY_REQUIRED` for waiting-on-me; `REVIEW` for other
  threaded one-to-one mail; `KEEP` for other one-to-one mail; `TRASH` for
  unprotected list/automated mail older than 90 days; `ARCHIVE` for unprotected
  list/automated mail older than 30 days; otherwise `MARK_READ`.

Rules are conservative advice, not semantic claims. Subject text never influences
classification. Every branch has a stable reason code, and SQL classification plus
the Java presenter must pass parity fixtures for every branch and exact threshold.

- **D-03: Normalized Sender Protection.**

Protection is sender-level persisted state keyed by `(account_id,
normalized_sender_address)` in a V7 `protected_senders` table. It is not inferred
from `STARRED` or `IMPORTANT` message labels. The table stores only account ID,
normalized sender address, creation time, and a bounded source enum. Phase 10 reads
this state; creation/removal through user-facing protect actions remains Phase 12.

- **D-04: Stable Ordering And Pagination.**

Every sort has a complete deterministic tie-break chain ending in normalized sender
address for sender rows and `(received_at DESC, id DESC)` or `(received_at ASC,
id ASC)` for unread rows as appropriate. Null timestamps sort last. Page size is
fixed at 25 for both surfaces. Counts and rows use the same normalized predicate.

Risk rank is `HIGH`, `MEDIUM`, `LOW`. Sender recommendation rank is `TRASH`,
`ARCHIVE`, `MARK_READ`, `REVIEW`, `KEEP`; unread recommendation rank is
`REPLY_REQUIRED`, `REVIEW`, `KEEP`, `MARK_READ`, `ARCHIVE`, `TRASH`. All enum-rank
CASE expressions then use the documented account/key tie-breakers.

Sender sorts: `volume_desc`, `unread_desc`, `oldest_first`, `risk_desc`,
`recommendation`, and `recent_desc`.

Unread sorts: `oldest_first`, `newest_first`, `sender_asc`, and
`recommendation`.

- **D-05: Sender Query Contract.**

Sender search covers normalized display name, full address, and domain. Filters are:

- `window=7d|30d|90d|all`
- `type=all|personal|list|automated|transactional|unknown`
- `risk=all|low|medium|high`
- `unread=all|none|some|majority|all_unread`
- `queue=all|open|queued`
- `protected=exclude|only|include`
- existing authorized `account`

The default excludes protected senders and uses `window=30d`,
`sort=volume_desc`, and `page=0`.

Unread-pressure buckets are disjoint: `none` is zero, `some` is greater than zero
and at most 50 percent, `majority` is greater than 50 percent but less than 100
percent, and `all_unread` is 100 percent. `open` means no matching active
`user_actions.status='QUEUED'` row keyed by account and normalized
`sender_group_key`; `queued` means such a row exists. Phase 10 creates no such row.

- **D-06: Unread Query Contract.**

Unread search covers sender display/address/domain and subject header. Filters are:

- `type=all|one_to_one|list`
- `age=all|today|7d|30d|older_30d`
- `waiting=all|waiting_on_me|not_waiting`
- `thread=all|single|threaded`
- `recommendation=all|reply_required|review|archive|trash|mark_read|keep`
- `sender` as an exact normalized sender-address drilldown
- `provider=all|google`
- existing authorized `account`

The default uses `sort=oldest_first`, `page=0`, and only rows whose indexed
`unread` value is true.

The unread `list` filter includes both `LIST` and `AUTOMATED` deterministic
categories; `one_to_one` includes only `ONE_TO_ONE`.

`today` begins at 00:00 UTC from the injected clock; `7d` and `30d` are inclusive
instant boundaries; `older_30d` is strictly earlier than the 30-day boundary.
Non-`all` sender windows and unread age filters exclude null `received_at`; `all`
retains nulls and sorts them last.

- **D-06A: Portable Dynamic Grouped Queries.**

Filtering or sorting a Java-classified page would make counts and pagination wrong.
Each surface therefore uses one fixed-fragment, bound-parameter SQL CTE pipeline:
authorized/base rows, aggregate facts, classified facts, filtered facts, then
count/metrics/page/materialization. Sender facts group one row per
`(account_id, sender_key)`; equal addresses in different accounts never consolidate.
Missing addresses use an internal per-account `__missing__` sender key and display
`Unknown sender`, but are not protectable by address.

The SQL rule expressions implement `DecisionRuleSet.V1` before filtering, ordering,
or pagination. Only allowlisted enum branches choose fixed SQL fragments; user values
are always bound. Search uses `LIKE ... ESCAPE '!'` after escaping `!`, `%`, and `_`.
Portable `CARDINALITY(array_column)` checks are exercised against both H2 PostgreSQL
mode and PostgreSQL 16. Any first-element access is inside
`CASE WHEN CARDINALITY(column) > 0` so empty arrays are never subscripted. Count,
metric, page-key, exact-count, and materialization
queries are generated from the same CTE/predicate object, not independently copied
WHERE clauses. Every order ends with account ID and stable sender key or metadata ID.

- **D-07: Exact Selection Modes.**

Selection materialization accepts exactly one of:

- `INDIVIDUAL`: explicit opaque row keys from the current authorized result type.
- `VISIBLE_PAGE`: the server reruns the canonical query at the submitted page.
- `ALL_MATCHING`: the server reruns the canonical query without pagination.

The browser never submits provider message IDs as the source of truth. Sender row
keys identify `(account_id, normalized_sender_address)` and unread row keys identify
an owned metadata row ID. The server re-authorizes every key and reruns the current
query before creating one immutable `ActionScope`.

Row keys are versioned opaque transport locators, not authorization credentials.
Decoding a key only produces candidate identity. The repository must rejoin it to
the authorized classified result CTE, including every current filter. Unknown,
duplicate, foreign, stale, cross-surface, or filter-mismatched keys fail closed and
cannot broaden the result. Client counts, account IDs, sender/protected/risk facts,
page membership, action compatibility, and provider IDs are never accepted as truth.

- **D-08: Exactness Cap.**

An `ActionScope` stores exact provider message IDs, so materialization has a hard
limit of 1,000 messages per account-owned scope, matching the existing typed summary
bounds. In a repeatable-read transaction, the service first computes an exact
distinct count per account, then retrieves at most 1,001 IDs as a drift guard. A
count above 1,000, or a guard mismatch, returns HTTP 422 with the server count,
creates no audit or scope row, and gives visible guidance to narrow filters. An
`account=all` preview may exceed 1,000 in aggregate only when every per-account scope
is independently at or below 1,000. It never silently truncates.

- **D-09: Protected Exclusion And Audited Override.**

Without override, all selection modes exclude protected senders in the server
predicate regardless of rendered checkboxes. The response reports server-derived
excluded sender/message counts. If exclusion leaves no targets, the response is
`PROTECTED_BLOCKED`; otherwise it may return a READY unprotected preview plus an
explicit override option. A checked, reasoned override reruns the same selection with
protected rows included. This applies to individual, visible-page, and all-matching
selection and is the only path that can include protected rows.

An override requires a CSRF-protected POST, the normalized authorized query, an
action type, and a bounded reason code. Before scope creation, append an immutable
`protected_override_audits` row containing account, selection surface, selection
mode, action type, query fingerprint, protected sender count, protected message
count, reason code, and timestamp. No subject, provider message ID, raw query JSON,
token, or header value is stored in the audit row.

Override reasons are exactly `FALSE_POSITIVE`, `BUSINESS_REQUIRED`,
`USER_CONFIRMED`, and `OTHER_REVIEWED`; UI labels explain them while storage uses
only the code.

- **D-10: Preview Is Not Execution.**

Phase 10 may create and render persisted `ActionScope` previews for the Phase 10
preview vocabulary (`ARCHIVE`, `TRASH`, `MARK_READ`, `KEEP`, `REVIEW`) but must not
queue, immediately execute, approve, or mutate mailbox state from Senders or Unread.
The Phase 9 queue button is not rendered on these Phase 10 preview fragments.
`SEND-06` and `UNRD-07` remain Phase 12.

`KEEP` and `REVIEW` are preview intents only; current `UndoService.BulkAction` does
not support them and is not extended here. Existing `ActionScope` hash bytes and the
four-field `ActionScopeTargetSummary` JSON shape remain unchanged. New target kinds
use `SENDER_SELECTION` and `UNREAD_SELECTION`; legacy `SENDER_ADDRESS` validation and
hash fixtures remain exact. Current-selection counts and warnings come from the
selection result, not a reused scope's persisted summary.

The selection transaction coordinator acquires owned account rows in ascending ID
order and performs preflight, override-audit inserts, and all new scope inserts in
one repeatable-read REQUIRED transaction. It must not call the existing
`ActionScopePersistenceService.insert` `REQUIRES_NEW` path. Existing legacy callers
retain that path. A multi-account failure rolls back every new audit/scope row.
The template model contains only ID-free per-account scope digests
(`account label`, hash, message count), never `ActionScopePreview` or its
`messageIds`.

The already-mapped Phase 9 `/api/actions/scopes/{scopeId}/queue` endpoint must reject
`SENDER_SELECTION` and `UNREAD_SELECTION` during Phase 10. `UndoService` continues
to queue only legacy `SENDER_ADDRESS` scopes until Phase 12 explicitly adopts the new
target kinds. A crafted POST with a discovered Phase 10 scope ID therefore creates no
`UserAction` and invokes no provider.

- **D-11: Provider Deep Links.**

Unread rows expose an HTTPS Gmail link generated from stored provider/thread IDs:
prefer `https://mail.google.com/mail/u/{accountEmail}/#all/{threadId}` and fall
back to the message ID when no thread ID is present. Values are encoded, provider
type is allowlisted, links use `target="_blank"` and `rel="noopener noreferrer"`,
and unsupported providers render no link. Opening a provider link does not call a
provider API.

The builder percent-encodes the account path segment and the fragment item segment
independently with UTF-8 RFC 3986 rules while preserving the literal `#all/`
delimiter. It rejects control characters, slash/backslash traversal, malformed
Unicode, non-HTTPS output, user-info, ports, query strings, and any host other than
`mail.google.com`. Tests parse the final URI and assert the exact decoded account and
thread/message target; templates receive the final URI and never concatenate it.

- **D-12: Shared Shell Integration.**

`WorkspaceController` remains the sole GET owner for `/senders` and `/unread`.
Route-specific model services compose the existing `WorkspacePageModel`; they do
not duplicate authentication or account ownership logic. Search/filter/pagination
GETs and any htmx result fragments are provider-free. Existing workspace state,
account scope, queue count, privacy copy, sync controls, and responsive tokens
remain intact.

- **D-13: Accessible Explanations.**

Every type, risk, recommendation, protected marker, score-like value, and metric has
a visible text label or a programmatically associated explanation. Tables use real
headers and captions; result counts use polite status text; selection errors and cap
errors use alert semantics. Checkbox labels name the sender/message. Reason text is
visible without hover. Color and icons never carry meaning alone.

- **D-14: Migration And Performance Parity.**

V7 has behaviorally equivalent PostgreSQL 16 and H2 test migrations, Hibernate
validation in both dialects, and matching uniqueness/index/privacy assertions.
Supported deterministic datasets must keep filtered result requests below the
existing two-second indexed-result budget after warm-up. All Senders/Unread GETs
and preview materialization use repositories only and verify zero `MailProvider`
interactions.
</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md`
- `.planning/REQUIREMENTS.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-CONTEXT.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-RESEARCH.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-01-SUMMARY.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-02-SUMMARY.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-03-SUMMARY.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-VERIFICATION.md`
- `tidymail-ux-rehaul/pages/by-sender-compact-suite.html`
- `tidymail-ux-rehaul/pages/unread-compact-suite.html`
</canonical_refs>

<specifics>
## Specifics

- Use V7 for normalized sender protection, protected-override audit, and portable
  sender/unread query indexes.
- Reuse Phase 9 `ActionScope` and create one scope per account for `account=all`.
- Use server-rendered Thymeleaf pages plus single-root htmx result/preview fragments.
- Keep a hard per-account exactness cap of 1,000 with exact-count preflight and a
  cap-plus-one consistency guard.
</specifics>

<deferred>
## Deferred

- `SEND-06`: sender queue/immediate archive, trash, mark-read, keep, protect, and
  review execution remains Phase 12.
- `UNRD-07`: unread queue/immediate archive, trash, mark-read, keep, and review
  execution remains Phase 12.
- Protect/unprotect mutation controls remain Phase 12; Phase 10 only reads normalized
  protection and audits explicit selection overrides.
- Action mode selection, queue confirmation, conflict resolution, outcome tracking,
  history, and undo remain Phase 12.
- CSV export remains Phase 13.
- Outlook browse/deep-link parity remains `OUTL-01`.
</deferred>

<scope_fence>
## Scope Fence

Do not edit provider APIs, fetch message bodies, add AI classification, add a SPA or
frontend build, execute mailbox mutations, queue action scopes, implement
unsubscribe, analytics, exports, or Phase 12 action policy.
</scope_fence>
