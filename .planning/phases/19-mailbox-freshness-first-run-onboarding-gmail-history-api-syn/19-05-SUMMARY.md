---
phase: 19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn
plan: 05
subsystem: mailbox-activity-ui
tags: [activity, thymeleaf, htmx-lite, accessibility, responsive, postgres]
requires:
  - phase: 19-04
    provides: Durable progressive mailbox sync lanes and first-batch lifecycle
provides:
  - Owned-account Sync / Activity page and fragment routes
  - Truthful mailbox lifecycle, freshness, progress, lane, and bounded event models
  - Bounded one-shot htmx-lite polling with settle, focus retention, and failure backoff
  - Six-link responsive workspace navigation and flat Activity layout
affects: [mailbox-freshness-ui, first-run-onboarding, workspace-navigation]
tech-stack:
  added: []
  patterns:
    - Server-rendered ActivityStatusModel remains the sole source of lifecycle truth
    - Polling uses one-shot timers and server omission as its termination signal
key-files:
  created:
    - src/main/java/com/tidymail/controller/ActivityController.java
    - src/main/java/com/tidymail/dto/ActivityPageModel.java
    - src/main/java/com/tidymail/dto/ActivityStatusModel.java
    - src/main/java/com/tidymail/service/ActivityQueryService.java
    - src/main/resources/templates/activity.html
    - src/main/resources/templates/fragments/activity-status.html
    - src/test/java/com/tidymail/journey/MailboxActivityJourneyTest.java
  modified:
    - src/main/resources/static/js/htmx-lite.js
    - src/main/resources/static/css/workspace.css
    - src/main/resources/templates/fragments/workspace-rail.html
    - src/test/java/com/tidymail/Phase13WorkspaceResponsiveTest.java
    - src/test/java/com/tidymail/Phase16WorkspaceFidelityTest.java
key-decisions:
  - "Omit ETA entirely because persisted activity does not yet prove a stable same-scope processing rate."
  - "Treat provider_total as reliable only when its matching observation timestamp is present."
  - "Stop polling through server omission of hx-trigger rather than client-derived lifecycle decisions."
requirements-completed: [FRESH-04, FRESH-07]
coverage:
  - id: D1
    description: "Owned accounts render a nonblank six-link Activity page and fragment with factual lifecycle fields and safe actions."
    requirement: FRESH-07
    verification:
      - kind: journey
        ref: "MailboxActivityJourneyTest#activityRouteRendersTruthfulAccountScopedStatus"
        status: pass
      - kind: journey
        ref: "MailboxActivityJourneyTest#retrySyncRequiresCsrfAndReturnsToActivity"
        status: pass
    human_judgment: false
  - id: D2
    description: "Active fragments poll at five seconds and terminal server states omit polling while preserving server-owned facts."
    requirement: FRESH-04
    verification:
      - kind: journey
        ref: "MailboxActivityJourneyTest#pollingPreservesFactsFocusAndServerAuthority"
        status: pass
      - kind: command
        ref: "node --check src/main/resources/static/js/htmx-lite.js"
        status: pass
    human_judgment: true
    rationale: "Automated source and server-fragment contracts pass; live focus/scroll behavior still needs an authenticated browser session."
  - id: D3
    description: "Activity reflows across six-link desktop, tablet, and narrow-mobile layouts using existing workspace tokens."
    requirement: FRESH-07
    verification:
      - kind: integration
        ref: "Phase13WorkspaceResponsiveTest#activityRailAndFactsReflowAtApprovedBreakpoints"
        status: pass
      - kind: integration
        ref: "Phase16WorkspaceFidelityTest#activityUsesApprovedTokensFocusAndReducedMotion"
        status: pass
    human_judgment: true
    rationale: "Automated CSS and template fidelity checks pass; authenticated desktop and 390px visual inspection remains."
duration: 13 min
completed: 2026-08-14
status: complete
---

# Phase 19 Plan 05: Sync / Activity Workspace Summary

**TidyMail now exposes server-owned mailbox activity with truthful first-run/freshness states and bounded, failure-tolerant polling.**

## Accomplishments

- Added owned-account `/activity` and `/activity/status` routes, a six-link rail, factual progress/lane/event models, CSRF-safe retry, and Gmail reauthorization handling.
- Added one-shot 1-300 second polling, 120ms settled outerHTML swaps, two-failure 30-second backoff, last-state retention, focus/scroll preservation, and server-controlled termination.
- Added flat token-based Activity styling with four/two/one-column facts, desktop/tablet/mobile six-link navigation, 44px mobile actions, semantic live regions, and reduced-motion handling.

## Task Commits

1. **Task 1: Build account-scoped Activity routes, model, and six-link workspace page** - `4736322`
2. **Task 2: Extend htmx-lite polling and responsive/accessibility behavior** - `bdaa44d`
3. **Post-task truthfulness correction** - `af6cf0b`

## Verification

- Java 21/PostgreSQL: `MailboxActivityJourneyTest`, `SecurityConfigIntegrationTest`, `Phase13WorkspaceResponsiveTest`, and `Phase16WorkspaceFidelityTest` passed, 29 tests total.
- Existing shared shell regression: `Phase9WorkspaceShellTest` passed.
- `node --check src/main/resources/static/js/htmx-lite.js` passed.
- Browser-owned-job copy, provider-secret/raw-lifecycle leakage, fabricated ETA, and destructive Activity-action guards passed.
- `git diff --check` passed.

## Manual Visual Verification Remaining

The local `/activity` route redirects to `/login`, and this executor has no authenticated OAuth browser session. Verify desktop and 390px layouts, 200% zoom, tooltip containment, keyboard focus through live swaps, unchanged scroll position, and reduced motion in an authenticated session.

## Deviations from Plan

### [Rule 1 - Bug] Tightened first-run and retry-state truthfulness

- **Found during:** Post-task contract review
- **Issue:** Pending bootstrap could inherit the generic Historical mail phase, and any older retry code could keep a terminal error polling.
- **Fix:** Keep pending bootstrap labeled Initial setup and allow 30-second error polling only for the latest explicitly queued retry.
- **Files modified:** `ActivityQueryService.java`
- **Verification:** Full 29-test Plan 05 suite and source guards passed.
- **Commit:** `af6cf0b`

**Total deviations:** 1 auto-fixed bug. **Impact:** Narrower, more truthful server state; no scope expansion.

## Issues Encountered

Spring initially selected neither of the two ActivityQueryService constructors. The production constructor was explicitly annotated for injection, after which all tests passed.

## User Setup Required

None.

## Next Phase Readiness

Ready for Plan 06. `STATE.md`, `ROADMAP.md`, and Plan 06 files were not modified.

## Self-Check: PASSED

All production commits exist, all Plan 05 tests and source guards pass, the summary is present, and prohibited planning files remain untouched.
