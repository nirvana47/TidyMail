---
phase: 15-implement-pending-cleanup-and-navigation
plan: "10"
subsystem: ui-api
tags: [unread, exact-scopes, htmx, inbox, csrf, ownership]

requires:
  - phase: 15-implement-pending-cleanup-and-navigation
    provides: Exact sender staging, Inbox-unread semantics, and bounded overlapping unread categories
provides:
  - Strict UNREAD cleanup staging with account-owned sender/category keys
  - Immutable per-sender UNREAD_SELECTION scopes from exact Inbox-unread IDs
  - Inline HTMX reconciliation and canonical native /unread fallback
affects: [review, unread-only, cleanup-staging]

actuals:
  tasks: 1
  commits: 2

key-files:
  modified:
    - src/main/java/com/tidymail/controller/CleanupActionController.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java
    - src/main/java/com/tidymail/repository/DecisionSurfaceSql.java
    - src/main/java/com/tidymail/service/CleanupActionService.java
    - src/main/java/com/tidymail/service/DecisionRowKey.java
    - src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java
    - src/main/resources/templates/unread.html
    - src/main/resources/templates/fragments/unread-results-frag.html
    - src/main/resources/static/js/cleanup-categories.js
    - src/main/resources/static/css/workspace.css
    - src/test/java/com/tidymail/Phase10SenderUnreadWebTest.java
    - src/test/java/com/tidymail/Phase15StagingTest.java
    - src/test/java/com/tidymail/Phase15WorkspaceWebTest.java

requirements-completed: [ACTN-02, ACTN-03, ACTN-07, QUAL-03, QUAL-04, UX-03, UX-07]
completed: 2026-08-11
status: complete
---

# Phase 15 Plan 10: Exact Unread Staging Summary

**Unread Only now stages immutable account-owned Inbox-unread sender/category scopes inline without trusting browser message IDs**

## Accomplishments

- Added row and cross-category Unread staging for Archive, Delete, and Mark as Read, with the shared Delete recovery acknowledgement.
- Added canonical unread sender/category keys and repeatable-read materialization through the shared unread CTE, preserving Inbox plus unread, canonical filters, selected categories, ownership, protection, and the 1,000-ID cap.
- Deduplicated repeated and overlapping category selections into one exact per-sender Review action and reconciled every visible occurrence after HTMX success.
- Preserved the existing sender staging path, strict parameter allowlist, CSRF enforcement, active-equivalent reuse, immutable scope hash/count, and zero provider interaction.
- Added native 303 fallback to the canonical `/unread` route and retained open category state for HTMX requests.

## Task Commits

1. **Task 15-10-01: Complete Unread direct staging and responsive interaction** - `140608b`
2. **Plan summary** - committed separately after this file was written.

## Rule 3 Deviations

Plan 15-10 declared only frontend and inherited web-test files. Exact Unread staging was blocked because Plan 15-02 accepted only `SENDERS` and its materializer could not enforce Inbox-unread category/filter scope. The following minimal backend/test files were therefore added under the requested blocking deviation:

- `CleanupActionController.java` - accepts only the explicit `UNREAD` canonical parameter set and returns `/unread` for native submissions; the `SENDERS` allowlist remains separate.
- `CleanupActionService.java` - dispatches `UNREAD` requests to a dedicated path while retaining the existing sender constructor and sender staging flow.
- `SelectionPreviewTransactionService.java` - locks owned accounts, enforces the union cap before writes, creates `UNREAD_SELECTION` scopes, and signs `DecisionSource.UNREAD` confirmations.
- `DecisionSurfaceQueryRepository.java` - materializes sender-level unread selections from server-side Inbox-unread rows instead of submitted metadata IDs.
- `DecisionSurfaceSql.java` - exposes allowlisted predicates matching the five displayed overlapping unread groups.
- `DecisionRowKey.java` - adds canonical `us1` account/category/sender keys with strict decode and category validation; existing sender and unread-message key behavior is preserved.
- `Phase15StagingTest.java` - proves exact Inbox/read/archive/category/filter behavior, overlap dedupe, immutable scope identity, native/HTMX behavior, CSRF, smuggling rejection, ownership isolation, and provider non-interaction.

No other undeclared files were modified. Shared `STATE.md`, `ROADMAP.md`, `REQUIREMENTS.md`, and config/tracking files were not modified or staged.

## Decisions Made

- Category identity is encoded with account and sender identity so cross-category bulk selection can preserve each clicked group without accepting browser message IDs.
- Multiple selected categories for the same sender union exact qualifying IDs and create one sender Review action.
- Browser disabling remains reconciliation only; server-side scope identity and active-equivalent action reuse remain the duplicate defense.

## Verification

- Java 21.0.12 focused implementation gate: `Phase15StagingTest`, `Phase15WorkspaceWebTest`, and `Phase10SenderUnreadWebTest` - 24 tests passed after overlap coverage was added.
- Java 21.0.12 requested regression/security gate: mapped unread web coverage plus `Phase15StagingTest`, `Phase10SenderUnreadWebTest`, `Phase15ClassificationTest`, `Phase15UnreadSemanticsTest`, `Phase11UnsubscribeSecurityTest`, `Phase12MultiAccountTest`, `SecurityConfigTest`, and `AccountSessionServiceTest` - 55 tests passed.
- `Phase15UnreadWebTest` does not exist in this checkout; Plan 15-10 declares `Phase15WorkspaceWebTest` as its Unread HTMX/native web proof, so that mapped suite was run.
- `git diff --check` and staged-path allowlist passed.

## Self-Check: PASSED

- Implementation commit `140608b` contains exactly the 13 declared/deviation implementation and test files.
- Exact scopes contain only account-owned IDs satisfying unread, Inbox, canonical filters, sender, and selected category predicates.
- No Selection Preview navigation, provider mutation, sender authorization weakening, or CSRF weakening was introduced.

---
*Phase: 15-implement-pending-cleanup-and-navigation*
*Completed: 2026-08-11*
