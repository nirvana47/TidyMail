---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
verified: 2026-08-14T22:12:41Z
status: human_needed
score: 9/10 must-haves verified
behavior_unverified: 0
decision_coverage:
  honored: 0
  total: 0
  not_honored: []
gaps:
  - "The explicit live-Gmail freshness acceptance criterion is not evidenced; 19-LIVE-UAT.md is missing."
---

# Phase 19: Mailbox Freshness & First-Run Onboarding Verification Report

**Phase Goal:** Make freshly arrived mail appear within one sync cycle through Gmail History API synchronization without backfill starvation; provide a useful first run and factual activity UI; distinguish unread scopes; prove metadata truth; and manage persisted OAuth refresh.

**Verified:** 2026-08-14T22:12:41Z
**Status:** human_needed

## Goal Achievement

### Observable Truths

The ROADMAP success criteria are the authoritative goal truths. Plan-level must-haves were also audited for artifacts, wiring, prohibitions, and evidence.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A live Gmail message appears within one sync cycle during backfill | ✗ FAILED | Deterministic `newGmailMessageAppearsWhileHistoricalBackfillIsStillRunning` passes, but the ROADMAP explicitly requires live-mailbox UAT. Plan 19-07 requires `19-LIVE-UAT.md`; that artifact is absent. |
| 2 | Historical backfill cannot starve fresh-mail ingestion | ✓ VERIFIED | Separate executors in `MailboxSyncExecutorConfig`; lane dispatch in `IncrementalSyncJob`; reserved Gmail capacity; `MailboxFreshnessConcurrencyTest` passes. |
| 3 | Gmail History advances idempotently with bounded expired-history recovery | ✓ VERIFIED | `GmailMailService.listChanges`, atomic cursor/message commits, H1 repair and generation rescan; contract, persistence, and freshness journeys pass. |
| 4 | Freshness state reports successful checks, lag, and rescan honestly | ✓ VERIFIED | `ActivityQueryService` derives freshness from `incremental_last_success_at`; Activity journey proves unknown/current/rescan rendering and omission of unsupported facts. |
| 5 | Managed OAuth refresh persists safely and preserves null timestamps/data | ✓ VERIFIED | `ManagedGoogleCredentialService`, `JpaOAuth2AuthorizedClientService`, and `GmailClientFactory`; six OAuth refresh tests pass, including one-refresh concurrency and nondestructive failure. |
| 6 | First run renders a useful 100-250 message batch before full history | ✓ VERIFIED | Default 200 and range validation; H0 bootstrap precedes independent freshness/backfill; first-run journey passes against PostgreSQL and HTTP rendering. |
| 7 | Dedicated Activity UI shows factual durable background progress | ✓ VERIFIED | Owned `/activity` routes, server-owned status model, CSRF retry, bounded polling, responsive/fidelity contracts, and Activity journeys pass. |
| 8 | Full mailbox backfill is progressive and unlimited by default | ✓ VERIFIED | `GmailSyncConfigProperties` defaults unlimited; unique-message ceiling logic is exact; backfill uses independent pagination and `includeSpamTrash=true`. |
| 9 | Inbox unread and All Mail unread are distinct, unique, and explicit | ✓ VERIFIED | Shared `UnreadScopeCounts` query/model; sender, unread, and analytics journeys prove multi-label uniqueness, pending versus zero, and explicit labels. |
| 10 | METADATA unread/size and attachment provenance reach storage and UI | ✓ VERIFIED | Provider-model to commit/PostgreSQL/MockMvc/Thymeleaf journey proves unread, size, and PRESENT/ABSENT/UNKNOWN without bodies, snippets, or attachment content. |

**Score:** 9/10 truths verified

### Required Artifacts

| Artifact group | Status | Details |
|----------------|--------|---------|
| V15 mailbox state, revision/provenance schema, atomic repositories | ✓ EXISTS + SUBSTANTIVE + WIRED | Production Flyway migration, mapped entities, account-scoped SQL, revision guards, short `REQUIRES_NEW` commits, and PostgreSQL tests. |
| Managed OAuth refresh and persistence | ✓ EXISTS + SUBSTANTIVE + WIRED | Manager-backed refresh, same-account single flight, encrypted persistence, and nondestructive `REAUTH_REQUIRED`. |
| Provider-neutral sync SPI and Gmail History implementation | ✓ EXISTS + SUBSTANTIVE + WIRED | Profile, bootstrap, complete backfill, unfiltered History, current METADATA, and ID-only attachment membership. |
| Progressive orchestration | ✓ EXISTS + SUBSTANTIVE + WIRED | Independent durable freshness/backfill/rescan lanes, first batch, exact ceiling, and guarded repair/sweep. |
| Activity UI and polling | ✓ EXISTS + SUBSTANTIVE + WIRED | Full page, fragment, account ownership, factual model, bounded htmx-lite polling, CSS breakpoints, and accessibility semantics. |
| Unread/attachment read models and realistic performance evidence | ✓ EXISTS + SUBSTANTIVE + WIRED | Shared unread scopes, visible attachment tri-state, 10,000-row performance test, and required indexes. |
| `19-LIVE-UAT.md` | ✗ MISSING | Explicit Plan 19-07 artifact and the only evidence path for the ROADMAP live-mailbox criterion. |

**Artifacts:** 6/7 grouped artifact sets verified

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| Gmail History API contract | PostgreSQL state/messages | `MailboxFreshnessService` plus `MailboxSyncCommitService` | ✓ WIRED | Provider work precedes a short atomic commit; replay/revision tests pass. |
| Bootstrap | Freshness and complete backfill | H0 commit and separate lane queues | ✓ WIRED | Initial batch marks ready, then queues freshness from H0 and backfill from null. |
| Expired History | Bounded repair and rescan | `RESCAN_REQUIRED` to H1 repair/generation sweep | ✓ WIRED | Journey proves fresh-during-rescan rows survive. |
| OAuth manager | Gmail client | Persisted authorized client | ✓ WIRED | Parallel lanes share one refresh and both consume the persisted access token. |
| Activity routes | Account-scoped server state | `OwnedAccountScopeService` and `ActivityQueryService` | ✓ WIRED | Full page and fragment use the same server-owned status model. |
| Polling fragment | Server continuation state | Stable `#activity-status` and returned `hx-trigger` | ✓ WIRED | One-shot polling, backoff, settle, focus handling, and termination are tested. |
| Gmail metadata/search | Visible unread/size/attachment facts | Commit, PostgreSQL, controllers, Thymeleaf | ✓ WIRED | Behavioral journey checks persisted and rendered values. |
| Live Gmail observation | Phase acceptance evidence | `19-LIVE-UAT.md` | ✗ NOT WIRED | No controlled-provider evidence artifact exists. |

**Wiring:** 12/13 plan key links verified; the only missing link is Plan 19-07 live evidence.

## Requirements Coverage

### Formal Repository Requirements

| Requirement | Status | Evidence / Blocking Issue |
|-------------|--------|---------------------------|
| FRESH-01 | ✗ BLOCKED | Automated concurrency/journey behavior passes, but the ROADMAP requires a live-mailbox observation and none is recorded. |
| FRESH-02 | ✓ SATISFIED | Independent executors, leases, cursors, and request capacity; concurrency test passes. |
| FRESH-03 | ✓ SATISFIED | Unfiltered paginated History, atomic cursor commits, idempotency, expired-history repair/rescan. |
| FRESH-04 | ✓ SATISFIED | Successful-check timestamp, factual lag, rescan state, and no fabricated up-to-date claim. |
| FRESH-05 | ✓ SATISFIED | Managed refresh, persistence, nullable timestamps, one-refresh concurrency, nondestructive failure. |
| FRESH-06 | ✓ SATISFIED | Explicit 200-message bootstrap, 100-250 validation, useful render before backfill completion. |
| FRESH-07 | ✓ SATISFIED | Dedicated Activity page, durable server-side work, factual conditional progress, close-page copy. |
| FRESH-08 | ✓ SATISFIED | Unlimited default, optional exact unique-message ceiling, no GB/storage claim. |
| FRESH-09 | ✓ SATISFIED | Shared unique-message query and explicit Inbox/All Mail labels across affected routes. |
| FRESH-10 | ✓ SATISFIED | METADATA and attachment-membership evidence reaches PostgreSQL and visible routes. |

**Coverage:** 9/10 formal Phase 19 requirements fully accepted.

## Prohibition Verification

| Prohibition area | Status | Evidence |
|------------------|--------|----------|
| Sync must not mutate provider messages | ✓ NO VIOLATION | Sync SPI is separate from cleanup mutation APIs; all 13 scoped Gmail opt-out cases pass. |
| No body, snippet, attachment content, raw payload, or plaintext token storage | ✓ NO VIOLATION | METADATA whitelist; snippet removed; attachment ID membership only; encrypted OAuth columns and persistence; source guards pass. |
| OAuth failure must not delete account/indexed data | ✓ NO VIOLATION | PostgreSQL journey retains account, sync state, and metadata in `REAUTH_REQUIRED`. |
| No cross-account state exposure | ✓ NO VIOLATION | Account-scoped SQL/routes and cross-account journey assertions pass. |
| Browser must not own background sync | ✓ NO VIOLATION | Durable executors/leases and exact close-page guidance; forbidden keep-open copy absent. |
| UI must not fabricate totals, ETA, freshness, unread scope, or attachment absence | ✓ NO VIOLATION | Conditional server model and behavioral journeys; UNKNOWN renders Checking and unreliable facts are omitted. |

## Behavioral Verification

| Check | Result | Detail |
|-------|--------|--------|
| Targeted Phase 19 Java 21/PostgreSQL matrix | ✓ 53 passed, 0 failed, 0 errors, 0 skipped | Eleven test classes under OpenJDK 21.0.12, Colima, Testcontainers PostgreSQL 16. |
| Realistic-volume profile | ✓ PASS | 10,000 rows; seed 713 ms; first 200 in 1 ms; sender/unread/activity 273/176/38 ms; bounded 500-row upsert 266 ms; required indexes used. |
| Gmail opt-out guarantees | ✓ PASS | 13 parameterized cases cover mutation, FULL/RAW, snippet/MIME/content, attachment fetch, watch/settings/filter/labels/contacts/drafts/send/threads. |
| OAuth/no-token guarantees | ✓ PASS | Managed refresh tests, encrypted-at-rest source inspection, safe-code logging scan, and destructive-removal guard. |
| JavaScript syntax | ✓ PASS | `node --check src/main/resources/static/js/htmx-lite.js`. |
| Full suite | ✓ PRE-EXISTING EVIDENCE | `19-VALIDATION.md` records 447/447 under Java 21; not rerun because no specific gap required it. |

## Test Quality Audit

| Test group | Linked requirements | Active | Skipped | Circular | Assertion level | Verdict |
|------------|---------------------|--------|---------|----------|-----------------|---------|
| State/persistence/concurrency | FRESH-01..04,08 | 13 | 0 | No | Behavioral, transactional, value | Strong |
| OAuth managed refresh | FRESH-05 | 6 | 0 | No | Behavioral, concurrency, persisted value | Strong |
| Gmail synchronization contract | FRESH-03,06,08,10 | 19 | 0 | No | Request/value behavior plus negative guards | Strong, provider stubbed |
| Freshness/Activity journeys | FRESH-01..04,06..08,10 | 8 | 0 | No | Real HTTP/PostgreSQL multi-step behavior | Strong, external Gmail not invoked |
| Sender/unread/analytics journeys | FRESH-09,10 | 6 | 0 | No | Real HTTP/PostgreSQL rendered values | Strong |
| Performance | FRESH-06,08 | 1 | 0 | No | Measured behavior and query plans | Strong |

**Disabled requirement tests:** 0  
**Circular patterns detected:** 0  
**Insufficient assertions:** 0  
**Residual limitation:** Gmail request construction uses official model objects and stubs, so it cannot establish real provider latency/retention.

## Anti-Patterns Found

No blocking `TBD`, `FIXME`, `XXX`, `TODO`, `HACK`, placeholder content, log-only function, or hardcoded fake progress was found in Phase 19 production changes. `return null` matches were reviewed as legitimate optional lookups/parsing or polling-target absence, not stubs.

## Human Verification

### Blocking External-Provider Acceptance

**Test:** With controlled Gmail and active historical backfill, send a synthetic message and record receipt-to-render latency within the configured cycle; also observe real managed refresh and metadata-only attachment membership without recording PII/secrets.

**Expected:** Fresh mail appears within one cycle without backfill starvation; refresh persists once; unread/size/attachment UI facts match Gmail; evidence is recorded in `19-LIVE-UAT.md`.

**Why human/external:** Provider retention, latency, token endpoint behavior, and Gmail search membership are not established by local deterministic fixtures.

### Nonblocking Visual Advisory

Authenticated desktop/390px Activity inspection remains useful for overflow, focus, scroll, live-region, tooltip, zoom, and reduced-motion judgment. It does **not** independently force `human_needed`: responsive/fidelity tests, semantic template checks, polling contracts, CSS breakpoint checks, and the approved UI specification provide sufficient automated goal evidence. Record this as residual visual risk, not an acceptance blocker.

## Decision Coverage

Skipped: Phase 19 has no `19-CONTEXT.md` and therefore no trackable `<decisions>` block. Plan and summary decisions were nevertheless checked against implementation during artifact and wiring review.

## Gaps Summary

### Critical Gaps

1. **Live Gmail acceptance evidence is missing**
   - Missing: `19-LIVE-UAT.md` and a controlled observation for the ROADMAP live-mailbox criterion.
   - Impact: Local tests prove deterministic behavior but cannot prove real Gmail History retention, end-to-end latency, token endpoint behavior, or attachment search semantics.
   - Fix: Run the privacy-safe controlled Gmail procedure from Plan 19-07 and record the result.

No gap is clearly deferred to Phases 20-22; the remaining item concerns Phase 19 live-provider acceptance.

## Recommended Fix Plans

### 19-08: Controlled Gmail Acceptance

**Objective:** Close the real-provider evidence gap without changing production code.

1. Run the redacted controlled Gmail freshness/backfill, managed-refresh, and metadata-membership checks.
2. Create `19-LIVE-UAT.md` with only synthetic aliases, rounded timestamps, counts, lifecycle facts, and pass/fail.
3. Re-run only the affected Phase 19 contract/journey tests if an observation disagrees with automation, then re-verify.

## Verification Metadata

**Approach:** Goal-backward verification using ROADMAP success criteria, all 19-01..07 plans/summaries, REQUIREMENTS, VALIDATION, UI-SPEC, production source, tests, and git history.  
**Must-haves source:** ROADMAP success criteria override for the 10-truth score; all 76 plan truths, 44 declared artifact entries, 13 key links, and 6 prohibitions were cross-checked.  
**Automated checks rerun:** 53 passed, 0 failed.  
**Full-suite evidence reused:** 447 passed, 0 failed from `19-VALIDATION.md`.  
**Human checks required to close gaps:** 1 controlled external-provider acceptance procedure.  
**Manual advisory:** Authenticated visual inspection only; nonblocking.

---
*Verified: 2026-08-14T22:12:41Z*
*Verifier: independent code assistant*
