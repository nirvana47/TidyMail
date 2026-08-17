---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
plan: 01
subsystem: mailbox-sync-persistence
tags: [postgresql, flyway, gmail, metadata, transactions, testcontainers]
requires:
  - phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
    provides: PostgreSQL 16 journey harness, Flyway validation, and deterministic synthetic mailbox fixtures
provides:
  - Provider-neutral durable mailbox lifecycle, cursor, backfill, rescan, lease, and OAuth state
  - Unsigned provider revision, scan generation, and tri-state attachment provenance
  - Account-scoped revision-aware metadata upserts, idempotent deletes, and freshness-safe generation sweep
  - Atomic REQUIRES_NEW metadata, delete, activity, and lane-cursor commits
affects: [phase-19-history-api, first-run-backfill, mailbox-activity, oauth-recovery]
actuals:
  tokens: 24479
  tasks: 2
  commits: 2
tech-stack:
  added: []
  patterns:
    - PostgreSQL INSERT ON CONFLICT guarded by unsigned provider revision
    - Short REQUIRES_NEW sync-unit commits with locked lease/version validation
    - UUID generation sweep guarded by rescan start freshness
key-files:
  created:
    - src/main/resources/db/migration/V15__mailbox_sync_state.sql
    - src/main/java/com/tidymail/entity/MailboxSyncState.java
    - src/main/java/com/tidymail/repository/MailboxSyncStateRepository.java
    - src/main/java/com/tidymail/repository/MessageMetadataSyncRepository.java
    - src/main/java/com/tidymail/service/MailboxSyncCommitService.java
    - src/test/java/com/tidymail/MailboxSyncStateTest.java
    - src/test/java/com/tidymail/MailboxSyncPersistenceTest.java
  modified:
    - src/main/java/com/tidymail/entity/MessageMetadata.java
    - src/main/java/com/tidymail/mail/MailMessage.java
    - src/main/java/com/tidymail/mail/gmail/GmailMailService.java
    - src/main/java/com/tidymail/service/MessageMetadataMapper.java
    - src/test/java/com/tidymail/support/SyntheticMailboxGenerator.java
key-decisions:
  - "Keep mailbox sync state provider-neutral; Gmail history IDs are cursor values rather than account identity."
  - "Store provider revisions as BigInteger/NUMERIC(20,0) and reject stale writes in PostgreSQL."
  - "Use attachment UNKNOWN as the compatibility default and persist provenance only for observed PRESENT or ABSENT facts."
  - "Keep provider network work outside MailboxSyncCommitService; only bounded persistence units enter REQUIRES_NEW transactions."
patterns-established:
  - "Atomic sync unit: lock account state, validate lane lease/version, write metadata/deletes/activity, then advance one lane cursor."
  - "Freshness-safe rescan: delete only outside-generation rows last synchronized before rescan start."
requirements-completed: [FRESH-01, FRESH-03, FRESH-04, FRESH-08, FRESH-10]
coverage:
  - id: D1
    description: "Provider-neutral V15 mailbox state and revision/provenance schema validate on PostgreSQL 16."
    requirement: FRESH-03
    verification:
      - kind: integration
        ref: "MailboxSyncStateTest and *PostgresMigrationTest under Java 21/Testcontainers"
        status: pass
    human_judgment: false
  - id: D2
    description: "Metadata, deletes, activity, and lane cursor commit atomically with replay and stale-write protection."
    requirement: FRESH-04
    verification:
      - kind: integration
        ref: "MailboxSyncPersistenceTest under Java 21/Testcontainers"
        status: pass
    human_judgment: false
  - id: D3
    description: "Active production and test consumers no longer retrieve or persist Gmail snippets."
    requirement: FRESH-10
    verification:
      - kind: other
        ref: "! rg -n 'metadataSnippet|metadata_snippet|getSnippet' src/main/java src/test/java src/main/resources/db/migration/V15__mailbox_sync_state.sql"
        status: pass
    human_judgment: false
duration: 16 min
completed: 2026-08-14
status: complete
---

# Phase 19 Plan 01: Mailbox Sync Persistence Summary

**Provider-neutral mailbox state and revision-aware PostgreSQL writes now commit metadata, deletes, activity, and lane cursors as one replay-safe unit**

## Performance

- **Duration:** 16 min
- **Started:** 2026-08-14T17:50:00Z
- **Completed:** 2026-08-14T18:06:07Z
- **Tasks:** 2
- **Files modified:** 21

## Accomplishments

- Added V15 durable mailbox lifecycle, independent freshness/backfill/rescan state, leases, OAuth status, and factual activity counters.
- Replaced snippet persistence with unsigned provider revisions, UUID generations, and attachment tri-state provenance while retaining positive boolean compatibility for existing read models.
- Added account-scoped PostgreSQL upserts, deletes, and guarded sweeps behind one short atomic commit service.
- Proved rollback, replay identity, lower-revision rejection, idempotent deletion, no-change success, account isolation, and freshness-safe sweeping on PostgreSQL 16.

## Task Commits

1. **Task 1: Add provider-neutral durable state and revision/provenance schema** - `6f78e75`
2. **Task 2: Commit provider changes, deletes, activity, and cursor atomically** - `ca30993`

## Files Created/Modified

- `V15__mailbox_sync_state.sql` - Durable state, revision, generation, provenance, OAuth, and activity schema.
- `MailboxSyncState.java` / `MailboxSyncStateRepository.java` - Provider-neutral state mapping and account/lane CAS operations.
- `MessageMetadataSyncRepository.java` - Revision-guarded native metadata upsert, account-scoped delete, and guarded sweep.
- `MailboxSyncCommitService.java` - Bounded REQUIRES_NEW sync-unit commit boundary.
- `MailboxSyncStateTest.java` / `MailboxSyncPersistenceTest.java` - Real PostgreSQL schema and transactional correctness coverage.
- Existing metadata, Gmail conversion, unsubscribe, legacy tests, and synthetic fixtures - Snippet-free revision/provenance shape.

## Decisions Made

- PostgreSQL remains the correctness authority for revision ordering and atomic cursor coupling.
- Rescan and backfill share the durable backfill lease in this single-lane plan; concurrent lane enablement remains deferred.
- Existing `has_attachments` reads remain compatible, but only tri-state attachment evidence is authoritative.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated prior migration count expectation**
- **Found during:** Task 1 verification
- **Issue:** The V13 migration test expected one pending migration, but V14 and new V15 are now pending.
- **Fix:** Updated the expectation to two without changing migration behavior.
- **Files modified:** `src/test/java/com/tidymail/Phase15PostgresMigrationTest.java`
- **Verification:** Full plan migration wildcard passed.
- **Committed in:** `6f78e75`

**2. [Rule 1 - Bug] Corrected synthetic provenance bind count**
- **Found during:** Task 1 tracer run
- **Issue:** The expanded synthetic insert initially had one fewer placeholder than bound revision/provenance values.
- **Fix:** Aligned all 38 parameters and reran deterministic 10,000-row tests.
- **Files modified:** `src/test/java/com/tidymail/support/SyntheticMailboxGenerator.java`
- **Verification:** `SyntheticMailboxPiiGuardTest` passed.
- **Committed in:** `6f78e75`

**3. [Rule 1 - Bug] Typed nullable lane completion expressions**
- **Found during:** Task 2 persistence run
- **Issue:** PostgreSQL could not infer nullable cursor/completion expression types in rescan state updates.
- **Fix:** Added explicit completion booleans and typed timestamp null branches.
- **Files modified:** `src/main/java/com/tidymail/repository/MailboxSyncStateRepository.java`
- **Verification:** Focused persistence suite and full plan suite passed.
- **Committed in:** `ca30993`

**Total deviations:** 3 auto-fixed (2 bugs, 1 blocking compatibility update).
**Impact on plan:** All fixes were required for the planned PostgreSQL schema transition and introduced no later-plan orchestration.

## Issues Encountered

Initial fixture parameter and nullable PostgreSQL expression failures were resolved within their task commits; no open issues remain.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The persistence tracer is ready for Plan 19-02 OAuth lifecycle work and later Gmail History/backfill orchestration. Concurrent freshness/backfill workers remain intentionally disabled.

## Self-Check: PASSED

All declared artifacts and task commits exist, coverage classification reports all three deliverables auto-covered, the plan verification suite passed, and shared tracking files were not modified.

---
*Phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn*
*Completed: 2026-08-14*
