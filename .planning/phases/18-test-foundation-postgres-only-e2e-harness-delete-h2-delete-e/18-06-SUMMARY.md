---
phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e
plan: 06
subsystem: export-removal
tags: [security, routes, deletion, journeys]
requires:
  - phase: 18-03
    provides: PostgreSQL journey harness and green integration suite
provides:
  - Six former export routes return 404 for authenticated users
  - Export controller, services, CSV package, properties, and DTO links removed
  - Export streaming transaction path removed
affects: [analytics, senders, unsubscribe, review]
actuals:
  tasks: 3
  commits: 1
key-files:
  created:
    - src/test/java/com/tidymail/journey/ExportRemovedJourneyTest.java
  deleted:
    - src/main/java/com/tidymail/controller/ExportController.java
    - src/main/java/com/tidymail/service/ExportService.java
    - src/main/java/com/tidymail/service/SanitizedSnapshotService.java
    - src/main/java/com/tidymail/export/
key-decisions:
  - "Remove snapshotUrl together with csvUrl because both belonged exclusively to the deleted export surface."
  - "Preserve UnsubscribeCandidateService.exportOrder because the live in-page candidate ordering still uses it."
requirements-completed: [TEST-03]
completed: 2026-08-14
status: complete
---

# Phase 18 Plan 06: Export Subsystem Removal Summary

**CSV and analytics snapshot exports are gone on the wire and in code; all six former routes are locked to authenticated 404 responses**

## Accomplishments

- Deleted the export controller, streaming service, configuration, snapshot service, and eight-file CSV package.
- Removed export properties and URL fields from sender and analytics page models.
- Removed export-only scan logic while preserving live unsubscribe page ordering.
- Pruned export-specific tests from mixed analytics and performance suites without reducing their live-product coverage.
- Added `exportEndpointsAreGoneForAuthenticatedUsers` covering all six former routes.

## Commit

- **Delete export subsystem and add negative journey** - `0a584c5`

## Decision Checkpoint

Proceed was recorded from the user's explicit request to remove export as part of Phase 18.

## Verification

- Clean test compilation passes.
- Focused export-removal and retained analytics tests pass.
- Full Maven suite: 405 tests, 0 failures, 0 errors.
- Production scan finds no export controller, service, package, properties, URLs, or snapshot route.

## Self-Check: PASSED

The export data-egress surface and transaction-held-across-streaming-I/O path no longer exist.

---
*Phase: 18-test-foundation-postgres-only-e2e-harness-delete-h2-delete-e*
*Completed: 2026-08-14*
