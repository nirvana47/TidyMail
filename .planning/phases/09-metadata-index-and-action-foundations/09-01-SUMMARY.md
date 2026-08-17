---
phase: 09-metadata-index-and-action-foundations
plan: 01
subsystem: metadata-index
tags: [flyway, postgres, h2, jpa, gmail, async-sync, testcontainers]
requires:
  - phase: 08-deployment-and-mobile
    provides: scheduled incremental sync and progress baseline
provides:
  - dual-dialect V6 metadata/action-scope/progress schema
  - explicit metadata-only persistence and indexed query boundary
  - bounded resumable asynchronous metadata synchronization
  - authorized CSRF-protected manual sync endpoint
affects: [09-02, 09-03, phase-10, phase-11, phase-12, phase-13]
tech-stack:
  added: [testcontainers-postgresql]
  patterns: [requires-new-page-upsert, checkpoint-after-commit, account-scoped-query-service]
key-files:
  created:
    - src/main/resources/db/migration/V6__metadata_index_and_action_scopes.sql
    - src/test/resources/db/test-migration/V6__metadata_index_and_action_scopes.sql
    - src/main/java/com/tidymail/entity/MessageMetadata.java
    - src/main/java/com/tidymail/service/MessageMetadataSyncService.java
    - src/main/java/com/tidymail/controller/MetadataSyncController.java
    - src/test/java/com/tidymail/Phase9MetadataIndexTest.java
    - src/test/java/com/tidymail/Phase9PostgresMigrationTest.java
  modified:
    - src/main/java/com/tidymail/job/IncrementalSyncJob.java
    - src/main/java/com/tidymail/entity/SyncProgress.java
    - src/main/java/com/tidymail/mail/gmail/GmailMailService.java
key-decisions:
  - Page metadata commits in a separate REQUIRES_NEW transaction before progress checkpoints advance.
  - One in-memory semaphore and one visible queued continuation coalesce work per account.
  - Workspace metadata queries accept authorized account IDs and have no provider dependency.
requirements-completed: [DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, DATA-06, QUAL-05]
duration: 19 min
completed: 2026-08-09
coverage:
  - deliverable: Dual-dialect V6 upgrade and Hibernate validation
    verification:
      - kind: command
        ref: "mvn -q -Dtest=Phase3PersistenceTest,Phase9MetadataIndexTest test"
        status: pass
    human_judgment: false
  - deliverable: Idempotent bounded resumable metadata sync
    verification:
      - kind: test
        ref: "src/test/java/com/tidymail/Phase9MetadataIndexTest.java"
        status: pass
    human_judgment: false
  - deliverable: Authorized asynchronous metadata-sync endpoint
    verification:
      - kind: test
        ref: "src/test/java/com/tidymail/Phase9MetadataIndexTest.java#endpointRequiresCsrfScopesOwnershipAndReturnsBeforeWorkRuns"
        status: pass
    human_judgment: false
---

# Phase 9 Plan 1: Metadata Index and Sync Foundations Summary

V6 now provides an explicit metadata-only index, action-scope schema foundation, extended progress states, and a bounded asynchronous Gmail synchronization path.

## Accomplishments

- Added behaviorally equivalent PostgreSQL/H2 V6 migrations with metadata indexes, action-scope ownership, SUCCESS-to-COMPLETE conversion, and H2 identity preservation.
- Added the approved metadata projection, batch page upsert, account-scoped provider-free queries, and the 23-header Gmail whitelist.
- Reworked incremental sync into queued, coalesced, five-page slices with committed checkpoints, PARTIAL resume, safe errors, and no sender-stat refresh.
- Added the owned-Gmail account boundary and CSRF-protected `POST /api/metadata-sync` with allowlisted workspace redirects.
- Added H2 upgrade, privacy, 500-message idempotency, concurrency, endpoint, and 10,000-row query tests plus PostgreSQL 16 Testcontainers coverage.

## Verification

- Focused Phase 3/4/8/9 suite: passed.
- Full `mvn test`: 79 tests, 0 failures, 0 errors, 1 skipped.
- PostgreSQL 16 Testcontainers smoke: skipped because Docker/Colima was unavailable (`/var/run/docker.sock` missing).
- The requested `JAVA_HOME=$(/usr/libexec/java_home -v 21)` prefix was used; this host resolves that selector to its installed OpenJDK 26.0.1 runtime.

## Deviations from Plan

None - plan executed as specified. The PostgreSQL test is environment-gated and remains ready to run when Docker is available.

## Next Phase Readiness

Plan 09-02 can build immutable action-scope entities and services on the V6 schema.
