---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
plan: 04
subsystem: mailbox-sync-orchestration
tags: [gmail-history, bootstrap, backfill, rescan, concurrency, testcontainers]
requires:
  - phase: 19-01
    provides: Durable mailbox state and atomic revision-aware persistence
  - phase: 19-02
    provides: Managed nondestructive OAuth refresh
  - phase: 19-03
    provides: Provider-neutral Gmail synchronization contract
provides:
  - Durable independent freshness and backfill/rescan execution lanes
  - Atomic H0 bootstrap with a 200-message first batch
  - Complete unique-message-aware backfill with an optional exact ceiling
  - Bounded H1 repair followed by guarded progressive generation rescan
  - New-account Activity routing and returning-account freshness routing
affects: [mailbox-freshness-ui, activity, first-run-onboarding]
tech-stack:
  added: []
  patterns:
    - Durable PostgreSQL CAS leases replace process-local account synchronization
    - Provider work executes outside transactions and commits through short REQUIRES_NEW units
    - Bootstrap and rescan anchors install atomically with their metadata repair units
key-files:
  created:
    - src/main/java/com/tidymail/service/MailboxBootstrapService.java
    - src/main/java/com/tidymail/service/MailboxRescanService.java
    - src/test/java/com/tidymail/journey/MailboxFreshnessJourneyTest.java
  modified:
    - src/main/java/com/tidymail/job/IncrementalSyncJob.java
    - src/main/java/com/tidymail/service/MessageMetadataSyncService.java
    - src/main/java/com/tidymail/service/MailboxFreshnessService.java
    - src/main/java/com/tidymail/service/MailboxBackfillService.java
    - src/main/java/com/tidymail/service/MailboxSyncCommitService.java
    - src/main/java/com/tidymail/security/oauth2/OAuthLoginSuccessListener.java
    - src/main/java/com/tidymail/security/AccountLoginSuccessHandler.java
key-decisions:
  - "Use the backfill lease for bootstrap and rescan while retaining a separately reserved freshness lane."
  - "Preserve incremental_last_success_at through expired-history repair so rescan never claims a fresh provider check."
  - "Apply the maintainer ceiling to unique provider IDs, allowing bootstrap-overlap rows without stopping early."
requirements-completed: [FRESH-01, FRESH-02, FRESH-03, FRESH-04, FRESH-06, FRESH-08]
coverage:
  - id: D1
    description: "Freshness owns separate executor, lease, and Gmail capacity and completes while backfill is blocked."
    requirement: FRESH-02
    verification:
      - kind: integration
        ref: "MailboxFreshnessConcurrencyTest and MailboxSyncPersistenceTest on PostgreSQL 16/Testcontainers"
        status: pass
    human_judgment: false
  - id: D2
    description: "H0 precedes an atomic 200-message first batch, then independent freshness and complete backfill continue."
    requirement: FRESH-06
    verification:
      - kind: journey
        ref: "MailboxFreshnessJourneyTest#newUserSeesFirstBatchBeforeFullMailboxBackfillCompletes"
        status: pass
    human_judgment: false
  - id: D3
    description: "Expired History installs H1 only with bounded repair and sweeps only after full generation traversal."
    requirement: FRESH-03
    verification:
      - kind: journey
        ref: "MailboxFreshnessJourneyTest#expiredGmailHistoryTriggersBoundedRepairAndProgressiveRescan"
        status: pass
    human_judgment: false
completed: 2026-08-14
status: complete
---

# Phase 19 Plan 04: Progressive Mailbox Sync Summary

**Mailbox synchronization now delivers a fast first batch, starvation-proof freshness, complete bounded backfill units, and guarded expired-History recovery**

## Accomplishments

- Added independent named executors, durable per-lane leases, stale-owner takeover, rejected-submission recovery, and reserved Gmail freshness request capacity.
- Added atomic H0 bootstrap, a default 200-message first batch, immediate freshness from H0, complete backfill from a null cursor, exact unique-message ceilings, and successful empty-mailbox handling.
- Added expired-History transition to `RESCAN_REQUIRED`, atomic bounded H1 repair, progressive generation stamping, and a final guarded sweep that retains post-start freshness writes.
- Routed newly created Google accounts to background bootstrap and `/activity`; returning accounts retain `/dashboard` behavior while freshness is queued.
- Replaced obsolete Phase 9 five-page, 500-row, and single-executor assertions with PostgreSQL journey and concurrency coverage.

## Task Commits

1. **Task 1: Add durable lane leases, separate executors, and bounded dispatch** - `2f6987d`
2. **Task 2: Implement bootstrap, complete backfill, and expired-history rescan lifecycle** - `09c9759`

## Verification

- `MailboxFreshnessConcurrencyTest,MailboxSyncPersistenceTest` passed with Colima and PostgreSQL 16 Testcontainers.
- `MailboxFreshnessJourneyTest,MailboxFreshnessConcurrencyTest,SenderTriageJourneyTest,Phase9MetadataIndexTest,OAuthLoginSuccessListenerTest` passed with the same environment.
- The stale-contract scan found no `MAX_PAGES`, process-local synchronization maps, or retired 50,000-message default.
- Freshness/backfill executor capacities and Gmail freshness reservation are independently configurable and startup-validated.

## Deviations from Plan

### Auto-fixed Issues

**1. Counted the optional ceiling by unique provider identity**
- Bootstrap overlap can appear again during full backfill, so page row counts could stop indexing early.
- Backfill now distinguishes existing IDs from newly indexed IDs and stops only at exactly the configured unique-message count.

**2. Preserved freshness age during expired-History repair**
- H1 installation initially risked updating the last-success timestamp even though no successful change-feed check had completed.
- Rescan now preserves `incremental_last_success_at` while updating only the attempt timestamp and H1 cursor.

## Issues Encountered

The first targeted run exposed only obsolete Phase 9 orchestration assertions and one PostgreSQL fixture timestamp binding. The stale assertions were removed as planned, the fixture used an explicit SQL timestamp, and all final verification passed.

## User Setup Required

None.

## Self-Check: PASSED

Both task commits exist, both focused Plan 04 suites pass, all four required journey methods exist, stale contracts are absent, and `STATE.md`, `ROADMAP.md`, and Plan 05 files were not modified.

---
*Phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn*
*Completed: 2026-08-14*
