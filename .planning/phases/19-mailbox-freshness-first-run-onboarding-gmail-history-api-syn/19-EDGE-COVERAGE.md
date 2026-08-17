# Phase 19 Edge Coverage

Spec-less fallback coverage for FRESH-01 through FRESH-10. This artifact contains probe coverage only; it is not an implementation plan.

## Raw Coverage Summary

The deterministic invocation used the exact ten requirement texts from `.planning/REQUIREMENTS.md`.

| Stage | Applicable | Covered | Backstop | Unresolved | Dismissed |
|---|---:|---:|---:|---:|---:|
| Raw `edge-probe.cjs` output | 21 | 0 | 0 | 21 | 0 |
| Autonomous fallback resolution | 21 | 21 | 0 | 0 | 0 |

Engine-native resolved summary after resolution: `resolved=21`, `unresolved=0`, `byVerification.explicit=21`, `byVerification.backstop=0`.

No classified edge required a backstop. The available roadmap, research, UI contract, and locked planning decisions support exact acceptance criteria for all 21 classified rows. None was dismissed or left unresolved.

## Surfaced Edge Items

| Requirement | Category | Status | Verification | Exact statement |
|---|---|---|---|---|
| FRESH-01 | empty | covered | explicit | A freshness cycle with no provider changes commits a successful provider-check timestamp, advances to the provider-returned history point when valid, inserts no rows, and never infers mailbox completeness from the no-op. |
| FRESH-01 | encoding | covered | explicit | Provider message IDs and Gmail history IDs remain opaque exact provider values; history IDs use a representation that preserves the full unsigned numeric domain and compares revisions without signed overflow or text-order corruption. |
| FRESH-02 | unclassified | covered | explicit | Freshness starts independently of active or saturated backfill and completes within one configured freshness cycle under deterministic fixtures, using separate durable cursors, leases, executors, and reserved Gmail request capacity. |
| FRESH-03 | concurrency | covered | explicit | History rows, deletes, and the corresponding cursor advance commit atomically; replay after interruption is idempotent, and concurrent backfill/history writes keep the metadata carrying the greatest provider revision. |
| FRESH-04 | unclassified | covered | explicit | Current lag is `now - incremental_last_success_at`; before the first successful provider check the UI shows `Not checked yet`, and rescan states never claim that the mailbox is up to date. |
| FRESH-05 | adjacency | covered | explicit | At the configured expiry-skew boundary, a token whose expiry is later than the boundary is reused, while a token at or before the boundary is refreshed once through the managed client manager. |
| FRESH-05 | empty | covered | explicit | Null issued-at or expiry timestamps remain unknown rather than being replaced with now or coerced to expired; a missing usable refresh token yields `REAUTH_REQUIRED` without deleting the account or indexed data. |
| FRESH-05 | ordering | covered | explicit | Concurrent requests for one account share a single refresh outcome, persist that outcome before dependent Gmail calls proceed, and preserve the existing refresh token when Google omits a replacement. |
| FRESH-05 | concurrency | covered | explicit | Parallel freshness and backfill client builds for the same expired credential cause at most one token endpoint refresh and both observe the persisted refreshed access token; terminal refresh failure moves the account to `REAUTH_REQUIRED` without a refresh loop. |
| FRESH-06 | unclassified | covered | explicit | The initial batch defaults to 200 messages, configuration validates a 100-250 inclusive range, and the deterministic localhost journey renders populated sender/category data within 10 seconds of connection. |
| FRESH-07 | empty | covered | explicit | With zero indexed messages or no recent activity, `/activity` still renders a non-blank factual status shell; it shows zero only for known counts, omits unknown total/remaining/ETA facts, and omits the empty activity-history section. |
| FRESH-07 | encoding | covered | explicit | Activity labels, account/provider values, timestamps, and numeric facts are server-rendered with semantic text and machine-readable times; raw lifecycle enums, provider payloads, tokens, stack traces, and provider message IDs are never rendered. |
| FRESH-08 | boundary | covered | explicit | An absent or zero maintainer message ceiling means unlimited indexing; a positive ceiling stops only after exactly that many unique provider messages are indexed, never one message early or late, and freshness ingestion remains enabled. |
| FRESH-08 | empty | covered | explicit | A reliably empty mailbox completes bootstrap and backfill with indexed count 0 and remains eligible for future freshness checks; it is not treated as an error or a reason to invent historical work. |
| FRESH-08 | encoding | covered | explicit | The safety ceiling is configured and displayed only as a unique-message count; no code or copy converts it to GB, storage consumed, mailbox capacity, or a request that the user archive Gmail data. |
| FRESH-08 | precision | covered | explicit | Indexed, provider-total, remaining, and ceiling values use non-overflowing integer storage; remaining is `max(reliable provider total - indexed unique messages, 0)` only when both values cover the same mailbox scope. |
| FRESH-09 | adjacency | covered | explicit | A message carrying both `INBOX` and other Gmail labels contributes exactly once to All Mail unread and exactly once to Inbox unread; label membership never creates duplicate message counts. |
| FRESH-09 | empty | covered | explicit | When no unread messages exist, Inbox unread and All Mail unread each render a factual zero from the shared unique-message query; missing or not-yet-synced data is labeled unavailable or pending rather than zero. |
| FRESH-09 | encoding | covered | explicit | Every visible unread total uses the exact scope label `Inbox unread` or `All Mail unread`, and persisted Gmail label matching uses exact provider label IDs rather than localized or display text. |
| FRESH-09 | ordering | covered | explicit | When both unread scopes are shown, Inbox unread precedes All Mail unread consistently, and all routes obtain both values from one shared read model so render order cannot change their definitions. |
| FRESH-10 | unclassified | covered | explicit | Attachment evidence uses metadata-only `has:attachment` message-ID membership with `PRESENT`, `ABSENT`, and `UNKNOWN` provenance; missing METADATA MIME children remain `UNKNOWN`, and the sync path never widens to `FULL` or `RAW`. |

## Locked Decision Resolutions

1. **FRESH-02 starvation contract:** Separate lane capacity and deterministic concurrency evidence enforce freshness completion within one configured cycle while backfill remains active.
2. **FRESH-04 lag semantics:** Lag derives only from `incremental_last_success_at`; unknown and rescan states use explicit non-current copy.
3. **FRESH-06 first-render budget:** The default is 200 messages, the valid range is 100-250, and deterministic localhost readiness is bounded at 10 seconds.
4. **FRESH-10 attachment evidence:** ID-only `has:attachment` membership is the accepted metadata-only evidence source; missing MIME children never prove absence.

## Prohibition Recall Input

### Adversarial Raw Must-NOT Candidates

| # | Raw candidate | Precision disposition |
|---:|---|---|
| 1 | MUST NOT claim the mailbox is current, complete, fresh, or predictably finishing from local row timestamps or unreliable provider estimates. | KEEP: bespoke product transparency. |
| 2 | MUST NOT retrieve or persist message bodies, attachment contents, Gmail snippets, raw provider payloads, or plaintext OAuth tokens while adding freshness and attachment evidence. | KEEP: project-specific metadata-only privacy boundary. |
| 3 | MUST NOT erase the account or indexed cleanup data when refresh credentials expire, are revoked, or fail. | KEEP: product safety and data preservation. |
| 4 | MUST NOT mutate, archive, label, delete, or otherwise change Gmail messages as a side effect of read-only synchronization or rescan. | KEEP: product safety and user agency. |
| 5 | MUST NOT expose one connected household account's tokens, sync state, activity, counts, or indexed messages in another account's view or worker context. | KEEP: project-specific account privacy. |
| 6 | MUST NOT make synchronization browser-owned after OAuth completion or pressure the user to keep a page, tab, or window open. | KEEP: product trust and autonomy; resolves the stale FRESH-07 wording in favor of durable background work. |
| 7 | MUST NOT accept SQL injection through account selectors, cursors, or activity filters. | DROP: OWASP canon; breadcrumb to `/gsd:secure-phase` plus parameterized-query/static-analysis checks. |
| 8 | MUST NOT render provider error payloads as executable script or unsafe HTML. | DROP: OWASP XSS canon; breadcrumb to `/gsd:secure-phase` plus template escaping/security tests. |
| 9 | MUST NOT allow cross-site requests to trigger retry or reconnect actions. | DROP: OWASP CSRF canon; breadcrumb to `/gsd:secure-phase` and the existing CSRF gate. |
| 10 | MUST NOT violate generic GDPR retention or consent obligations. | DROP: compliance canon; breadcrumb to `/gsd:secure-phase`, not minted as a Phase 19 bespoke prohibition. |
| 11 | MUST NOT apply the same history event twice or duplicate a provider message row. | DROP: routine correctness; covered by the idempotency/concurrency edge criteria and journey tests. |
| 12 | MUST NOT advance a cursor after a failed database commit or transient provider failure. | DROP: routine correctness; covered by the FRESH-03 concurrency criterion and code review. |
| 13 | MUST NOT overflow large counts, history IDs, or remaining-work arithmetic. | DROP: routine correctness; covered by FRESH-01 encoding and FRESH-08 precision. |
| 14 | MUST NOT render a blank activity area for zero messages or zero events. | DROP: routine state handling; covered by FRESH-07 empty and the UI contract. |
| 15 | MUST NOT let repeated polling overlap, leak timers, reorder events, or steal focus. | DROP: routine correctness/accessibility; owned by the UI contract, edge coverage, and visual accessibility verification. |

### Precision-Filtered Prohibitions

These six items must survive into plan `must_haves.prohibitions` exactly as descriptor-less, flagged-unverified entries. They intentionally contain no `check_kind`, `check_target`, `check_rule`, `check_violation_fixture`, or `check_clean_fixture`.

| Requirement(s) | Category | Status | Flagged | Statement |
|---|---|---|---|---|
| FRESH-04, FRESH-07, FRESH-09, FRESH-10 | transparency | unverified | true | MUST NOT fabricate or overstate freshness, completion, totals, remaining work, ETA, unread scope, attachment absence, or mailbox-capacity meaning; every visible claim must be backed by a source reliable for that exact scope. |
| FRESH-05 | safety | unverified | true | MUST NOT delete a connected account or its indexed cleanup data because token refresh fails, credentials are revoked, or reauthorization is required. |
| FRESH-01, FRESH-02, FRESH-03, FRESH-08 | safety | unverified | true | MUST NOT mutate, archive, label, delete, or otherwise change provider messages as a side effect of synchronization, backfill, history recovery, or rescan. |
| FRESH-05, FRESH-10 | privacy | unverified | true | MUST NOT retrieve or persist message bodies, attachment contents, body-derived Gmail snippets, raw provider payloads, or plaintext OAuth tokens; Phase 19 remains metadata-only. |
| FRESH-06, FRESH-07 | privacy | unverified | true | MUST NOT expose one connected account's credentials, sync lifecycle, activity, counts, or indexed messages in another account's UI selection or worker execution context. |
| FRESH-06, FRESH-07 | values | unverified | true | MUST NOT make post-OAuth synchronization depend on an open browser page or tell the user to keep a page, tab, or window open; durable background work must continue after navigation or closure. |

## Planner Lift Contract

- Every `covered` truth above must appear in plan `must_haves.truths` as a plain string.
- Every `backstop` truth, if later introduced, must appear in plan `must_haves.truths` as a flat-scalar marker with `statement: <exact check>` and `verification: backstop`; it must never be flattened into prose or a nested object.
- Any future `unresolved` row must appear as an explicit flagged assumption. It must not be promoted, dismissed, or omitted silently.
- Every precision-filtered prohibition above must appear in the sibling plan `must_haves.prohibitions` block as descriptor-less with `status: unverified` and `flagged: true`.
- No-silent-drop equality is mandatory: 21 surfaced edge items must equal 21 lifted covered truths plus 0 lifted backstops plus 0 flagged assumptions; 6 kept prohibitions must equal 6 descriptor-less flagged-unverified prohibition entries.
