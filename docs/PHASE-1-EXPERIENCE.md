# Phase 1 — Sender dashboard experience

2026-10-28 · Approved and locked after review revisions. All examples are synthetic.
Review [the dashboard wireframe](wireframes/sender-dashboard.html) alongside this
contract. This phase changes documentation only, not application behavior.

## Reference-led layout and complexity

The supplied dashboard image sets the visual direction: compact header and filter
summary, sender table with inline addresses, right-hand detail tabs, and a bottom
selection/action bar with progress directly underneath. The v1 wireframe is retained
as [historical design](wireframes/sender-dashboard-v1.html); v2 supersedes its layout.

Clicking a sender name focuses its detail pane; a chevron expands addresses; only
checkboxes select. The right pane has Messages and Sender addresses tabs.
All checkboxes share one set of exact IDs. A partial selection produces mixed-state
parent boxes and a recalculated count. The reference's inconsistent checked rows
and 438 total are corrected in the mockup. The initial 438 selection is complete;
deselecting a message or address reduces it consistently across panes.

The bottom bar chooses one action, then Review and confirm opens a modal. Selection
and job state remain independent: the synthetic 438-target job represents an earlier,
non-overlapping selection. Activity holds detailed outcomes; the job row persists
while browsing. The state picker is a design review aid, not planned product UI.

MVP complexity is primarily front-end state and responsive layout. Reuse local
metadata, paging, and one selection contract; do not add a service. The mockup uses
initial badges, illustrative familiar names and reserved example domains, not
verified company identities. Actual default names still follow the conservative
grouping rules below. Body snippets, remote logo lookup and company enrichment are
deferred; message secondary lines show sender metadata. The screenshot account
caret does not imply multi-account management. Mobile stacks detail below the list
and reflows the dock. Production paging and correction flows remain later-phase work.

## Outcome language (defined before the progress panel)

Each confirmed target belongs to exactly one bucket:

| Label | Meaning |
|---|---|
| Changed | Gmail acknowledged the requested change, or verification established it after an uncertain dispatch. Does not prove TidyMail was the sole actor. |
| Failed | Definitive failure; no automatic mutation retry remains. |
| Skipped / no-op | Already in the requested state, no longer eligible, missing, or excluded at dispatch. Show the reason. |
| Awaiting verification | Dispatched, but its outcome is unknown. Never describe this as unchanged. |
| Not processed — cancelled | Cancellation prevented dispatch; no mutation request for this target was sent. |
| Remaining | Queued, waiting for retry/reconnection, or in flight with no settled outcome yet. |

Changed + failed + skipped + awaiting verification + not processed + remaining
always equals the fixed confirmed target count. Progress is acknowledged outcomes,
not elapsed time. Show all buckets in Activity; the compact panel emphasizes
changed, remaining, and any exceptions. Do not label settled outcomes “successful.”

Cancel is available immediately in queued, running, retry-wait, reconnect-wait,
and verification states while mutation work remains. One click records the request;
no second modal delays it. Copy: **“Cancellation requested. Stopping new requests;
requests already sent may finish. Completed changes stay.”** Disable the button
once requested. If recording fails, keep Cancel available and show
“Could not request cancellation. Work may still be running. Try again.”

At cancellation settlement, remaining = 0. Example: **“Cancelled · 120 changed ·
375 not processed · 5 awaiting verification.”** Total = 500. Read-only verification
may update unresolved counts later; it must not restart mutations. Closing a tab,
signing out, or reloading does not request cancellation. Jobs restore on return.
No pause, resume, Undo, permanent delete, or automatic replay of uncertain requests.

## Received-mail and filter contract

Apply rules per message, never by thread membership. Require the account's local
index and ownership boundary. Spam, Trash, and Draft labels exclude a message even
if it also has Inbox or received evidence. Inbox membership is positive received
evidence. Outside Inbox, non-Sent messages qualify as received; Sent messages need
independent delivery evidence to this account (including a verified account alias).
A To/Cc match alone is insufficient for a Sent message; retain ambiguous Sent mail
outside both scopes pending evidence. Missing labels or insufficient classification
are excluded with a visible coverage caveat, not silently treated as received.
Phase 2 must map these evidence requirements to retained metadata and identify gaps.

Inbox = eligible received messages currently labeled Inbox. All received mail =
all eligible received messages, including archived. Read status does not affect
browsing. Date bounds use provider received time, inclusive start and exclusive
next-day end in the displayed account timezone; default is all indexed dates.
Missing received times appear in all-dates results but not bounded dates and are
reported as undated. Search is a case-insensitive literal substring over displayed
group label, From address/name, or indexed subject; no body search. A group-label
match includes its eligible children; address/subject matches include only matching
messages. Summary, row counts, expansion, selection, and preview use that same
predicate. Sort groups by matching count descending, then stable group key; messages
by received time descending, then stable message ID. Dates and search never trigger
a full mailbox download.

| Fixture | Inbox | All received | Expected explanation |
|---|---|---|---|
| Read or unread received Inbox message | Yes | Yes | Read status is irrelevant to browsing |
| Archived received message, no Sent label | No | Yes | Archive preserves All received membership |
| Inbox + Trash, Spam, or Draft | No | No | Exclusions take precedence |
| Sent-only outgoing message | No | No | No independent received evidence |
| Sent + verified inbound delivery to self, archived | No | Yes | Received evidence overrides sent-only classification |
| Thread has one sent and one received message | Received child only | Received child only | No thread-wide inclusion |
| Unknown labels or ambiguous Sent delivery | No | No | Coverage caveat; do not infer eligibility |
| Oct 28 date filter in displayed timezone | Only Oct 28 eligible mail | Same date predicate | Midnight Oct 29 excluded |

## Deterministic grouping proposal

Grouping is an organizational aid, not a verified identity or trust signal. Display
addresses and an evidence explanation on expansion. Use a versioned rule set:

1. Parse one unambiguous From mailbox; normalize domain to lowercase IDNA, preserve
   local-part identity (do not strip dots, plus tags, or lowercase distinct local
   parts). Missing/multiple invalid From values remain separate message-level rows.
2. A valid single List-ID makes an atomic mailing-list unit, keyed by its normalized
   identifier. Distinct List-IDs stay separate even under the same domain. One
   address may contribute to multiple list units; each message belongs to one unit.
3. Without a List-ID, use exact From domain only for a domain classified as dedicated.
   No automatic parent-domain, cross-domain, reply-to, return-path, display-name,
   category, logo, unsubscribe-URL, or fuzzy-name merging. Subdomains stay separate.
4. Shared mailbox providers (gmail.com, outlook.com, yahoo.com), tenant/relay domains,
   and unknown domain classifications default to exact-address units. A versioned
   shared-domain registry takes precedence over dedicated classification. Unknown
   means separate until reviewed; the UI says “Kept separate — insufficient evidence.”
5. Corrections are account-owned and apply to stable list/address units. A manual
   separate constraint overrides automatic grouping and prevents a transitive merge
   from reconnecting those units. Explicit merges can combine units across domains;
   reject conflicts until the user explicitly removes the separation. Resetting a
   correction restores automatic rules. No correction bypasses eligibility filters.

Automatic domain groups use the literal domain as their label; list groups show
List-ID; manual groups may have a user label. “Merge groups” previews all affected
units; “Keep separate” previews an address/list unit leaving its current group.
Corrections apply to future matching units but never rewrite a confirmed job.
A merge is organization only: it does not itself select or mutate mail.

| Fixture | Expected result |
|---|---|
| receipts@shop.example and offers@shop.example; dedicated domain, no List-ID | One shop.example group; categories may differ |
| receipts@shop.example and news@mail.shop.example | Two groups; subdomains are not inferred aliases |
| alice@gmail.com and bob@gmail.com | Two groups regardless of matching display names |
| a@relay.example and b@relay.example; unknown/shared domain | Two exact-address groups |
| Same valid List-ID on two From addresses | One list group, both addresses visible |
| Two List-IDs at one domain | Two list groups; non-list messages remain in domain/address unit |
| “Shop” at shop.example and unrelated.example | Separate; display name is not evidence |
| Manual merge A+B, then separate B | A and B separate; automatic rules cannot rejoin them |
| A separated from B, then merge A+C and C+B | Second merge rejected with conflict explanation |
| Group correction after confirming message IDs 1–10 | Job remains exactly IDs 1–10 |

## Selection and confirmation

Checkboxes exist at group, address, and message levels; parent boxes use unchecked,
checked, and mixed states. A group/address checkbox selects all currently matching
indexed children, including unloaded children, with its exact count stated. The
header checkbox is labeled “Select groups on this page”; it selects matching
messages in those displayed groups, not off-page groups. In a message expansion,
“Select visible messages” selects only that loaded message page. There is no whole-result selection control. Select additional sender pages explicitly;
existing selections persist while paging.
Deduplicate IDs across all selection paths.

Paging preserves a draft selection; show total selected even off-page. Applying
scope, search, or date changes clears the unconfirmed selection with a visible
notice. Group corrections also clear draft selection. Opening preview freezes a
snapshot; changing filters closes/invalidates that preview and requires a new one.
A confirmed job remains fixed regardless of filters, new mail, regrouping, or sync.
The job panel retains its original scope/date/search summary.

Preview lists selected count, eligible/effective count, no-op count, action, scope,
date/search, index coverage/as-of time, and paginated exact target details. It must
be possible to inspect every target before confirming. For 12 selected messages
with 4 already read: “Mark 8 messages as read; 4 already read will be skipped.”
The job's fixed total is all 12 selected IDs, including the 4 known no-ops. Disable
confirmation if effective count is zero. Label the button “Mark 8 as read”,
“Archive 12 messages”, or “Move 12 to Trash”, never just “OK”.

Confirmation copy: **“This applies only to these selected messages, not future
mail. Cancel stops remaining work; completed changes are not reversed.”** Archive
also explains removal from Inbox; Trash explains that this is not permanent deletion.
Recheck eligibility immediately before dispatch: changed state becomes a counted
skip, never substitution with another message. Failed previews cannot be confirmed.
Overlapping targets in an active job cannot start a second mutation job; explain
which selection is already pending and let the user revise it.

| Selection fixture | Expected result |
|---|---|
| Page has 2 groups / 12 matching messages; result has 100 messages | Header selects 12; remaining pages require explicit selection |
| Expand a group with 8 children; show 3 | Group selects 8; visible-message control selects 3 |
| Select group and one of its child messages | Child counted once |
| Select 12, move to page 2 | Still 12 selected; off-page selection disclosed |
| Select 12, change Inbox to All received or change date/search | Draft clears, notice shown; no expansion to archived messages |
| Confirm 12; 5 new matching messages arrive | Fixed total stays 12 |
| Confirm Archive; one target loses Inbox before dispatch | One skip; no replacement target |
| Confirm; one target moves to Spam/Trash | Skip as no longer eligible |
| Preview cannot obtain exact IDs | No actionable confirmation |

## Screen states and access

The wireframe provides a dashboard with collapsed and expanded groups, scope and
filters, selection toolbar, exact confirmation, compact progress, and an Activity
drawer. Its state picker demonstrates the following copy.
These are local design interactions, not functioning mailbox controls.

| State | Visible behavior |
|---|---|
| Empty index | “No messages indexed yet”; indexing status, disabled actions |
| No matches | “No messages match these filters”; clear filters; do not claim mailbox empty |
| Partial index | Coverage interval and “Results include indexed messages only”; ongoing backfill |
| Reconnect | Indexed browsing remains; reconnect entry point; mutation dispatch waits; Cancel available |
| Queued / running / retry wait | Named job, fixed count, acknowledged buckets; immediate Cancel |
| Cancellation requested | Settling in-flight requests; no new mutation dispatch |
| Cancelled | Final buckets, zero remaining; verification may continue |
| Partial failure | Changed and failed counts separated; inspect failure reasons in Activity |
| Uncertain outcome | Awaiting verification distinct from failures and unchanged messages |
| Completed | All targets accounted for; no-op counts remain visible |

Activity shows job ID, action, original scope, creation/completion times, six outcome
buckets and per-target reasons/details. Opening a job restores its panel without
altering current filters. Activity survives reload via server persistence in later
phases. Mark pending rows in place; update counts only from acknowledged/reconciled
changes. Keep account identity, Sign out, and confirmed local-data deletion accessible.
Sign out retains index data; local-data deletion requires a separate explicit
confirmation and must handle active jobs safely in the later implementation design.

Keyboard: native controls, labeled checkboxes, visible focus, Enter/Space activation;
confirmation and drawer manage focus and restore it to their opener, Escape closes
without cancelling work. Announce outcome updates politely. Mobile stacks filters,
row metrics, and progress; controls remain reachable without hover. These are
acceptance requirements, not claims of completed accessibility testing.

## Phase tracking and review gate

| Task | Status | Dependency | Deliverable / affected components | Check and evidence |
|---|---|---|---|---|
| Define cancellation and counts | Complete (design) | MVP agreement | Outcome contract above | Six disjoint buckets; 120+375+5=500 |
| Specify eligibility/grouping | Complete (proposal) | MVP scope | Rules and fixtures above | Explicit expected results including shared-domain and Sent exceptions |
| Specify selection | Complete (proposal) | Scope/grouping rules | Selection and preview contract | Paging, filter, duplicate, stale and new-mail examples |
| Deliver wireframe | Complete (approved design) | Reference image and outcome language | docs/wireframes/sender-dashboard.html | Shared local selection, detail tabs, confirmation and state scenarios; synthetic only |
| User review | Complete | All deliverables | This contract and wireframe | User approved the current design on 2026-10-28: “ok i think this looks good. Let's lock it for now.” |

Checks performed: inspected the MVP and strategy for required state coverage;
checked example accounting and wireframe scenario presence; verified that no
application source is modified by this phase. V2 local browser checks confirmed that deselecting one message changes 438 to 437,
sets parent checkboxes to mixed, and opens an Archive confirmation for exactly
437 targets. Desktop layout was visually inspected; a preview is saved at
[dashboard v2](wireframes/sender-dashboard-v2.png). JavaScript syntax and all
438-target scenario totals passed. No application tests, live Gmail calls, full
responsive/accessibility acceptance, throughput, or persistence claims are made. The proposed
rules still require feasibility mapping in Phase 2. Existing unrelated working-tree
changes are preserved. User review is recorded below. Phase 2 baseline/persistence work is next and has
not started.

## Review refinements — 2026-10-28

Remove the About tab from the MVP. Retain manual merge/separate requirements, with
correction controls to live in Sender addresses rather than a dedicated About section.
Keep the detail pane open; selecting another sender changes its contents. Remove
the close button and the redundant About ellipsis. Keep only “Clean up your inbox.”
in the subtitle. Align Activity and Sign out to the same right edge and width; inset
the date dropdown arrow.

Header sync status must reflect actual indexing state: “Fetching mail…” for initial
fetch, “Fetching older mail…” during backfill, and connection/error status when
blocked. Show “Synced…” only with an actual successful sync timestamp. Do not invent
percentages or an estimated completion time. Empty-index coverage stays unavailable
until metadata arrives.

Single-address senders use ordinary rows labeled “1 sender address.” Sender pagination
has explicit previous/next buttons and a visible range. The design sample has 11
senders over two pages (10 per page). The MVP sender page size is 10. Page selection affects that page only; existing
selections persist on page changes. These refinements are included in the design approved on 2026-10-28. Application
implementation has not begun.

## Selection visibility and pagination refinement

Show the selection/action row only when at least one message is selected. Keep
job progress and Cancel independently visible when work exists; hide the entire
dock when neither selection nor job exists. Place the sender page range and
previous/next controls immediately below the sender list, not in the summary area.

Date changes discard the draft selection and invalidate any open preview. Never
cache selections per date range: all dates → last 7 days → all dates leaves zero
selected throughout both transitions. The same clearing policy applies to scope
and search changes. Confirmed jobs retain their fixed targets. Acceptance check:
select two senders, change dates, switch back, verify all checkboxes remain clear
and the action row stays hidden while existing job controls remain accessible.

## Message paging and all-matching selection clarification

The Messages tab needs independent previous/next controls and a visible message
range so every matching message can be inspected. Show 10 messages per page.
Message paging preserves selected IDs; changing the focused sender resets message
paging but preserves selection. Filter changes reset both pagers and clear selection.

The sender header checkbox selects only messages from sender groups on the current
page. A group checkbox includes all matching children, including message pages not
loaded. There is no whole-result selection button. Explicit selections on additional
sender pages accumulate and still require exact preview and confirmation.

Acceptance: move to message page two without losing selections; verify that page selection stays limited to the current sender page.

## Review refinement — remove whole-result selection

Remove the “Select all matching” button and its explanatory section from the MVP.
Keep the sender-table header checkbox limited to the current page, plus individual
group/address/message checkboxes. Additional pages require explicit selection;
paging preserves prior choices and filter changes still discard them.

Known mockup limitation: Inbox and All received mail currently use identical
synthetic mail counts. Implementation must include archived received messages only
in All received mail, and apply that scope consistently to counts, previews and
selection. Verify with an archived-message fixture in the implementation phases.

Sender pagination: show 10 sender rows per page. Expanded address rows do not count
toward this limit. Message pagination remains independent.

Message pagination: show 10 messages per page in the right-hand detail pane,
independently of the sender list pager.

## Approved design baseline — 2026-10-28

The user approved the current wireframe: “ok i think this looks good. Let's lock it
for now.” Phase 1 is complete. Preserve the
[approved snapshot](wireframes/sender-dashboard-approved.html) as the implementation
reference. Later design changes require an explicit scope update.

The locked baseline includes 10 senders per page and 10 messages per detail page,
Messages and Sender addresses tabs, no About tab or detail close button, current-page
and individual selection without a whole-result selection button, permanent draft
selection clearing on filter changes, a selection row visible only when selected,
and independent job progress/Cancel controls. Sender pagination sits below the list.

Approval covers the design baseline. Mockup behavior remains synthetic; identical
Inbox/All received counts remain a documented limitation to resolve in implementation.
At design lock, Phase 2 had not started. Baseline verification and persistence
design is now complete; see the [Phase 2 report](PHASE-2-BASELINE-AND-PERSISTENCE.md).
No application implementation, mailbox action, or publication occurred as part of
locking this design.
