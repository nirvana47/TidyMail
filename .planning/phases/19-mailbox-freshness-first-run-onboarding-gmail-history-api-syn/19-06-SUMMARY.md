---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
plan: 06
subsystem: metadata-truth-and-validation
tags: [unread, attachments, postgres, performance, test-consolidation]
requires:
  - phase: 19-05
    provides: Account-scoped sync activity and progressive mailbox lifecycle
provides:
  - Shared account-scoped Inbox unread and All Mail unread read model
  - Attachment PRESENT, ABSENT, and UNKNOWN provenance in sender statistics
  - Realistic-volume mailbox sync performance and index-plan evidence
  - Legacy assertion and fixture compatibility with metadata truth contracts
affects: [workspace, senders, unread, analytics, mailbox-sync-validation]
tech-stack:
  added: []
  patterns:
    - One account-scoped read model supplies both unread scopes
    - Attachment UNKNOWN remains Checking until reconciliation proves presence or absence
key-files:
  created:
    - src/main/java/com/tidymail/dto/UnreadScopeCounts.java
    - src/test/java/com/tidymail/MailboxSyncPerformanceTest.java
  modified:
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/repository/ReviewActionQueryRepository.java
    - src/main/resources/templates/fragments/email-stats-modal-frag.html
    - src/main/resources/templates/fragments/senders-results-frag.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/test/java/com/tidymail/journey/MailboxFreshnessJourneyTest.java
key-decisions:
  - "Inbox unread requires the INBOX label; All Mail unread includes every unread indexed message."
  - "Attachment absence is rendered only after reconciliation records ABSENT; UNKNOWN renders Checking."
  - "Performance validation uses a 10,000-row default and a bounded 500-message commit work unit."
requirements-completed: [FRESH-02, FRESH-03, FRESH-08]
duration: resumed after crash
completed: 2026-08-14
status: complete
---

# Phase 19 Plan 06: Metadata Truth and Validation Summary

**TidyMail now renders explicit unread scopes and attachment provenance from shared account-scoped facts, backed by realistic Postgres performance and full-suite evidence.**

## Accomplishments

- Added one immutable unread-scope model and account-scoped query for distinct Inbox unread and All Mail unread counts, then propagated explicit labels through workspace, sender, unread, and analytics surfaces.
- Added attachment-state propagation through sender statistics and the existing metadata table, rendering PRESENT as Present, ABSENT as None, and UNKNOWN as Checking with the conditional coverage caption.
- Added provider-to-Postgres-to-visible-route journey evidence, a 10,000-row mailbox performance test, index-plan checks, and removal of stale Phase 4 and Phase 9 duplicate assertions.
- Preserved and committed the ten crash-recovered test-file edits, changing only one additional assertion after the focused suite proved its old `Emails` expectation incompatible with the approved All Mail unread contract.

## Plan 06 Commits

1. **Unify unread scope counts and labels** - `e1f739c`
2. **Render attachment provenance in sender stats** - `b19325f`
3. **Consolidate sync performance and legacy coverage** - `90b9cb9`
4. **Align legacy metadata truth fixtures** - `0a7f132`

## Verification

- Focused affected suite: 75 tests, 0 failures, 0 errors, 0 skipped across the ten requested Phase 10-17 classes.
- Full Maven suite: 447 tests, 0 failures, 0 errors, 0 skipped across 92 Surefire report files.
- Performance journey: 10,000 seeded rows in 801 ms; first 200 rows in 1 ms; sender, unread, and activity routes in 40 ms, 111 ms, and 21 ms; 500-row bounded upsert in 479 ms; retained bounded-work heap 1,072,408 bytes.
- PostgreSQL plans used `idx_message_metadata_account_unread_received` and `idx_message_metadata_account_last_synced`; measured execution times were 1.382 ms and 2.795 ms.
- Forbidden FULL/RAW, attachment-content fetch, snippet access, and `metadata_snippet` executable production paths: zero in Java, templates, and static resources. The historical V12 migration declaration remains schema history only.
- `git diff --check` passed. `STATE.md`, `ROADMAP.md`, Plan 07, and unrelated production files were not changed during crash recovery.

## Deviations from Plan

### [Assertion Compatibility] Updated one stale paged-sender header expectation

- **Found during:** Focused affected-suite rerun after crash.
- **Issue:** `Phase15CategoryPagingTest` still expected `Emails` while the approved sender contract renders `All Mail unread`.
- **Fix:** Changed only that assertion to the rendered contract.
- **Verification:** Focused 75-test suite and full 447-test suite passed.
- **Commit:** `0a7f132`

**Total deviations:** 1 assertion-only compatibility correction. No production change was required.

## Manual Visual Verification Remaining

At 320 CSS px and 200% zoom, verify explicit unread labels do not truncate into ambiguity and the attachment column/caption reflows without page-level overflow. Automated template, journey, responsive, and fidelity contracts are green.

## User Setup Required

None.

## Next Phase Readiness

Plan 06 is complete. Plan 07, `STATE.md`, and `ROADMAP.md` were intentionally left unchanged.

## Self-Check: PASSED

All four Plan 06 implementation/test commits exist, focused and full suites pass, measured performance and static privacy guards are recorded, and this summary is the only uncommitted file.
