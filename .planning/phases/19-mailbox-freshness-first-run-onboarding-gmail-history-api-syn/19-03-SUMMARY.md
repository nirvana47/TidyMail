---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
plan: 03
subsystem: mail-sync
tags: [gmail-api, history-api, metadata-only, java-21, testcontainers]
requires:
  - phase: 19-01
    provides: Revision-safe mailbox sync state and atomic persistence boundaries
  - phase: 19-02
    provides: Nondestructive managed OAuth refresh and persisted Gmail credentials
provides:
  - Provider-neutral mailbox synchronization SPI with HISTORY, DELTA, and NONE capabilities
  - Gmail profile, recent bootstrap, complete backfill, History, metadata, and attachment-membership requests
  - Fair Gmail request capacity with a freshness reservation and validated Phase 19 sync settings
affects: [19-04, mailbox-freshness, mailbox-backfill, attachment-reconciliation]
actuals:
  tokens: 15231
  tasks: 2
  commits: 2
tech-stack:
  added: []
  patterns:
    - Opaque provider cursors with explicit change-feed capability kinds
    - Gmail METADATA batches of ten with bounded safe error classifications
    - Fair total API semaphore plus a separate backfill gate reserving freshness capacity
key-files:
  created:
    - src/main/java/com/tidymail/mail/sync/MailboxSyncProvider.java
    - src/main/java/com/tidymail/mail/sync/ChangePage.java
    - src/main/java/com/tidymail/mail/sync/AttachmentMembershipPage.java
    - src/test/java/com/tidymail/GmailSynchronizationContractTest.java
  modified:
    - src/main/java/com/tidymail/mail/ProviderCapabilities.java
    - src/main/java/com/tidymail/mail/gmail/GmailMailService.java
    - src/main/java/com/tidymail/mail/gmail/GmailRateLimiter.java
    - src/main/java/com/tidymail/mail/gmail/GmailSyncConfigProperties.java
key-decisions:
  - "Keep provider cursors opaque in the SPI and validate the declared HISTORY/DELTA/NONE kind before provider use."
  - "Treat a requested metadata ID omitted after Gmail returns 404 as the idempotent deletion signal; expired History returns only HISTORY_EXPIRED."
  - "Derive attachment presence from full-mailbox has:attachment ID membership; METADATA MIME shape always remains UNKNOWN."
patterns-established:
  - "Sync providers expose profile, bootstrap, backfill, changes, current metadata, and attachment membership independently from mutation methods."
  - "Gmail History consumes only messagesAdded, messagesDeleted, labelsAdded, and labelsRemoved without history filters or generic messages."
requirements-completed: [FRESH-03, FRESH-06, FRESH-08, FRESH-10]
coverage:
  - id: D1
    description: "Provider-neutral immutable sync contracts separate changed/deleted IDs, cursor kinds, complete revisions, pagination, and ID-only attachment membership."
    requirement: FRESH-03
    verification:
      - kind: other
        ref: "Java 21 mvn -q -DskipTests compile plus Task 1 acceptance scans"
        status: pass
    human_judgment: false
  - id: D2
    description: "Gmail implements profile, 200-message bootstrap, includeSpamTrash backfill, unfiltered paginated History, metadata 404 deletion, expired-history classification, and attachment ID membership."
    requirement: FRESH-08
    verification:
      - kind: integration
        ref: "GmailSynchronizationContractTest, Phase4GmailProviderTest, and Phase4ConfigValidationTest on PostgreSQL 16/Testcontainers"
        status: pass
    human_judgment: false
  - id: D3
    description: "METADATA conversion preserves the exact 24 headers, labels, unread state, size, and BigInteger revision while snippets/content remain absent and attachment state remains UNKNOWN."
    requirement: FRESH-10
    verification:
      - kind: integration
        ref: "GmailSynchronizationContractTest#gmailMetadataMapsLabelsSizeAndUnsignedRevisionWithoutSnippetOrMimeInference and 13 scoped opt-out cases"
        status: pass
    human_judgment: false
duration: 15 min
completed: 2026-08-14
status: complete
---

# Phase 19 Plan 03: Gmail Synchronization Contract Summary

**Provider-neutral sync contracts now drive Gmail profile, bootstrap, backfill, History, current METADATA, and ID-only attachment membership with reserved freshness capacity**

## Performance

- **Duration:** 15 min
- **Started:** 2026-08-14T18:16:00Z
- **Completed:** 2026-08-14T18:30:53Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments

- Added immutable provider-neutral contracts for capabilities, profile anchors, backfill, change pages, current metadata, and attachment membership.
- Implemented exact Gmail profile/bootstrap/backfill/History/404/attachment-search semantics without widening beyond METADATA.
- Added Java 21 contract evidence for all positive Gmail coverage rows, 13 scoped opt-outs, validated progressive defaults, and independently reserved freshness request capacity.

## Task Commits

1. **Task 1: Define provider-neutral mailbox synchronization contracts** - `361f58d`
2. **Task 2: Implement exact Gmail request semantics and metadata conversion** - `954aef5`

## Files Created/Modified

- `mail/sync/*.java` - Immutable provider-neutral capability, request, cursor, change, and attachment membership contracts.
- `GmailMailService.java` - Gmail HISTORY implementation with bounded metadata batches and safe 404 classifications.
- `GmailRateLimiter.java` / `GmailSyncConfigProperties.java` - Fair lane capacity and validated Phase 19 settings.
- `ProviderCapabilities.java` - Source-compatible value class enabling covariant sync capabilities.
- `GmailSynchronizationContractTest.java` - Real Google model fixtures, request stubs, and 13 scoped opt-out guards.

## Decisions Made

- Current metadata 404s are represented by omission from the requested ID result, allowing the caller to apply an idempotent deletion without exposing provider error content.
- The top-level History cursor and the last completely consumed page revision remain separate values.
- Existing cleanup mutation methods remain behavior-constant and outside the Phase 19 sync source guards.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Resolved the dual capabilities return-type collision**
- **Found during:** Task 2
- **Issue:** `MailProvider.capabilities()` returned `ProviderCapabilities` while the planned `MailboxSyncProvider.capabilities()` returned `SyncCapabilities`, so one Gmail class could not implement both interfaces.
- **Fix:** Preserved all existing `ProviderCapabilities` constructor/accessor/value behavior as a class and made `SyncCapabilities` its covariant subtype.
- **Files modified:** `ProviderCapabilities.java`, `SyncCapabilities.java`
- **Verification:** Java 21 compile and all focused provider/config tests pass.
- **Committed in:** `954aef5`

---

**Total deviations:** 1 auto-fixed (1 blocking issue).
**Impact on plan:** The compatibility change preserves existing mutation-provider behavior and exposes the exact planned sync capability method without renaming either API.

## Issues Encountered

The developer machine's ignored `.env` still sets the retired `SYNC_INITIAL_PAGE_SIZE=500`. Startup validation correctly rejected it, so deterministic verification supplied `SYNC_INITIAL_PAGE_SIZE=200` to the test process without modifying the user's local environment.

## User Setup Required

None - checked-in configuration defaults are valid and no external service setup is required.

## Next Phase Readiness

Ready for Plan 19-04 orchestration. Gmail sync remains provider-only: no worker concurrency, browser behavior, cursor persistence orchestration, or Plan 04+ functionality was introduced.

## Self-Check: PASSED

Both task commits and all created artifacts exist. Java 21 compilation passes; 30 focused tests pass on PostgreSQL 16/Testcontainers; all acceptance scans and 13 opt-out guards pass; no History filter is configured; and `.planning/STATE.md` plus `.planning/ROADMAP.md` remain unchanged.

---
*Phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn*
*Completed: 2026-08-14*
