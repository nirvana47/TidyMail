---
status: constrained
phase: 13-analytics-export-and-integrated-product-verification
plan: "06"
source:
  - 13-06-PLAN.md
  - 13-VALIDATION.md
started: 2026-08-10T11:28:13Z
updated: 2026-08-10T16:33:00Z
automated_preflight: passed
manual_gates: 4_passed_3_constrained
---

# Phase 13 Localhost UAT

Deployment status: BLOCKED - constrained localhost gates remain

## Privacy Rules

Record only account count, redacted canonical state, aggregate counts and N/D values,
outcome partitions, filename/bytes/SHA-256, privacy result, browser/viewport,
accessibility observations, bounded defect references, and PASS/FAIL.

Do not record email addresses, provider IDs, raw headers, subjects, bodies,
attachments, unsubscribe destinations, tokens, cookies, credentials, private
download content, or unredacted screenshots. Do not commit downloaded CSV/JSON files.

## Automated Preflight

| Check | Result | Evidence |
|---|---|---|
| UTC execution | PASS | 2026-08-10T11:28:13Z |
| Worktree identity | PASS | HEAD `937adbfc4baf`; dirty worktree preserved |
| Java | PASS | OpenJDK 21.0.12 |
| PostgreSQL | PASS | PostgreSQL 16.14; Flyway V10 |
| Application | PASS | TidyMail 0.1.0; Spring Boot 3.5.16 |
| Focused Phase 13 | PASS | 80 tests; 0 failures, 0 errors, 0 skipped |
| Full regression | PASS | 322 tests; 0 failures, 0 errors, 0 skipped |
| Clean package | PASS | `target/tidymail-0.1.0.jar`; 154978935 bytes |
| JAR SHA-256 | PASS | `77b057a8a4ee69e9a8a0e5ac0982b3db4962a7a40dd5c003a354e709cce29373` |
| JAR contents | PASS | Boot launcher, build info, application class, five templates, static CSS |
| Loopback smoke | PASS | `/login` 200; unauthenticated `/analytics` 302 to local login |
| Listener | PASS | PID 27344; `127.0.0.1:8080` only |
| Public exposure | PASS | No cloudflared process or public application listener |
| Diff hygiene | PASS | `git diff --check` |
| Privacy/security automation | PASS | GET provider/transport isolation, output canaries, auth, CSRF, SSRF regression |

Automated evidence: [13-06-AUTOMATED-EVIDENCE.md](./13-06-AUTOMATED-EVIDENCE.md)

## Localhost Runtime

- URL: `http://localhost:8080`
- Process PID: `27344`
- Command ID: `job-44f2922f22f94448b043d02f511099f5`
- OAuth origin required: `http://localhost:8080`
- OAuth redirect required: `http://localhost:8080/login/oauth2/code/google`
- Scheduling: disabled
- Public tunnel/deployment: not started

## Gate A: Authentication, Sync, And Shell

Expected: Authenticate one known low-risk Gmail test account; sync at least 500
metadata rows twice; prove idempotence; verify consistent shell/account/queue state,
provider-free page GETs, and all five routes at desktop and 375x812.

Actual: OAuth completed without redirect loops. Two bounded Gmail syncs added 500 rows
each, all five workspace routes rendered at desktop, and provider-free GET behavior
remained intact. The browser harness could not force an authenticated 375x812 viewport.

Evidence: account count 1; indexed counts first/second 22,900/23,400; duplicate
provider-message keys 0; later indexed count 28,015; desktop browser route pass;
static 375px responsive contract pass.

Result: CONSTRAINED - authentication, sync, idempotence, and desktop shell passed;
the exact authenticated 375x812 observation remains unavailable.

## Gate B: Both Execution Policies

Expected: Stage and approve one low-risk reversible archive/read action, then run a
separate explicitly acknowledged immediate action. Exact scope and truthful outcome
partitions must match the shared safety contract.

Actual: One staged MARK_READ action was approved and a separate immediate MARK_READ
action was explicitly acknowledged. Both used one-message exact scopes.

Evidence: staged partition 1/1/1/0/0 with 0 not dispatched; immediate partition
1/1/1/0/0 with 0 not dispatched; distinct one-message scopes.

Result: PASS

## Gate C: Unsubscribe Alternatives

Expected: Inspect and explicitly execute one eligible safe unsubscribe, exercise one
mailto/account-page/manual alternative, keep unsafe/protected options blocked, and
treat accepted as pending rather than confirmed.

Actual: A real candidate required fresh consent. The RFC 8058 path remained blocked
because trusted DKIM coverage was unavailable. The available legacy method was
executed and truthfully failed; no manual completion was fabricated.

Evidence: legacy partition requested 1, accepted 0, succeeded 0, failed 1; unsafe
RFC 8058 remained blocked; failed History copy does not claim endpoint acceptance.

Result: CONSTRAINED - no genuinely eligible safe or successful manual alternative
existed in the real indexed data.

## Gate D: Analytics Consistency

Expected: Use non-default date/account/action/group/prior filters; verify definitions
and N/D values; prove one received and one cleaned bucket equal all source pages;
verify top sender and History intersections, unavailable pre-coverage state, and
accepted/pending unsubscribe exclusion.

Actual: Real PostgreSQL Analytics rendered after aligning median precision with the
bucket sample. Non-default MARK_READ/week/no-comparison filters round-tripped, explicit
unavailable states rendered, and received/cleaned source predicates matched aggregates.

Evidence: canonical state retained action/group/comparison; exact outcome partition
2 requested = 2 succeeded; cleaned bucket aggregate/source-page-union/CSV = 2/2/2;
PostgreSQL/H2 per-bucket source-union parity passed; source/top-sender/History links
were owned and filtered; incomplete comparisons rendered unavailable rather than zero.

Result: PASS

## Gate E: Exports And Snapshot

Expected: Download filtered Senders, Unread, Unsubscribe, Review History, and Analytics
CSV plus sanitized snapshot; verify filters, counts, UTF-8, quoting, formula safety,
privacy, filenames, byte sizes, and SHA-256 without committing downloads.

Actual: All six authenticated downloads returned HTTP 200 with filtered attachment
filenames. Content scans found no body, MIME, attachment, authorization, or token
fields; the snapshot contained no account or sampled sender identity.

Evidence:

| Download | Filename | Bytes | SHA-256 | Count/filter/privacy | Result |
|---|---|---:|---|---|---|
| Senders CSV | `tidymail-senders-2026-08-10.csv` | 52,332 | `a866dcd881a21e60c8367a7f40c990bad23a17bdadfc40d1de60dcbaea167f00` | 287 rows; clean | PASS |
| Unread CSV | `tidymail-unread-2026-08-10.csv` | 1,664,733 | `ffce0f0337df348635bd96201ebf1b9792b8eb08855666602474e81844109c8d` | 8,239 rows; clean | PASS |
| Unsubscribe CSV | `tidymail-unsubscribe-2026-08-10.csv` | 218,576 | `a8d88c9466a41812e897d142d05e22481d3fbf3769e76022ca7e538cc5aa8498` | 717 rows; clean | PASS |
| Review History CSV | `tidymail-review-history-2026-08-10.csv` | 574 | `d862751de948aff597d66a5e1d0448e2e6b6c9e40801a20168e653828cd1ced1` | 3 rows; clean | PASS |
| Analytics CSV | `tidymail-analytics-2026-08-10.csv` | 7,240 | `101ac68623005a1e75829068cddc9efd3b309316b09b568f22ca75efc325bee6` | 36 rows; filtered; clean | PASS |
| Sanitized snapshot | `tidymail-analytics-snapshot-2026-08-10.json` | 10,647 | `257402e658536299c0075d6b14c5ccdf4ecb8ea3b67518c8cc59d5dd84e808ee` | 6 buckets; identities omitted | PASS |

Result: PASS

## Gate F: Undo And Retained History

Expected: Undo one successful archive/trash/mark-read action; invert only succeeded
targets; retain forward and inverse History; retain gross analytics and reduce net
results only from the confirmed inverse fact time.

Actual: The staged MARK_READ action was undone. History retained both the forward
execution and linked inverse, and only the recorded succeeded target was reverted.

Evidence: forward partition 1 requested/1 succeeded; undo partition 1 requested/1
reverted; History retained Applied and Undone events; automated analytics retained
gross facts and counted the confirmed inverse separately.

Result: PASS

## Gate G: Accessibility And Deployment Decision

Expected: Keyboard traverse all five pages; verify live focus/status, 375x812, 200%
zoom, long labels, 44px targets, no page-level horizontal scroll, screen-reader
names, and no inert control.

Actual: Keyboard navigation, focus/status semantics, names, long-label wrapping,
local overflow containment, and absence of inert represented controls passed across
all five routes. Static 375px/44px contracts passed. The browser harness could not
enforce an authenticated 375x812 viewport at 200% zoom.

Evidence: browser route snapshots and focus traversal; five-page accessible-name and
status audit; 375px/44px/focus/reduced-motion static suite; no page-level overflow
at the available viewport; combined mobile-plus-zoom observation unavailable.

Result: CONSTRAINED - deployment remains blocked pending the exact combined viewport
and zoom observation.

## Summary

| Gate | Status |
|---|---|
| Gate A | CONSTRAINED |
| Gate B | PASS |
| Gate C | CONSTRAINED |
| Gate D | PASS |
| Gate E | PASS |
| Gate F | PASS |
| Gate G | CONSTRAINED |

Passed: 4. Failed: 0. Constrained: 3.

Deployment status: BLOCKED - no public deployment attempted

## Gaps

- QUAL-02 exact authenticated 375x812 plus 200% zoom observation remains.
- Gate C needs a real high-confidence safe candidate and successful provider/manual
  alternative; current data supports only truthful blocked/failed evidence.
- Existing local Compose PostgreSQL publishes host port 5432 on all interfaces; this
  does not expose the loopback-only application but should be hardened separately.
