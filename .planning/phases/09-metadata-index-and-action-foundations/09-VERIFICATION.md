---
phase: 09-metadata-index-and-action-foundations
status: passed
verified: 2026-08-09
verifier: independent
requirements_verified: 14
requirements_total: 14
automated_tests:
  tests: 97
  failures: 0
  errors: 0
  skipped: 0
manual_items_blocking: false
---

# Phase 9 Verification

## Verdict

Phase 9 passes. All 14 mapped requirements and all four roadmap success criteria
are covered by current code plus automated evidence. No blocking implementation or
verification gaps remain.

The verifier fixed two in-scope evidence defects before issuing this result:

1. `Phase9PostgresMigrationTest` now forces the Spring validation context onto the
   same Testcontainers JDBC URL migrated by Flyway and asserts that URL. Previously,
   lower-precedence builder properties allowed local `.env` datasource settings to win.
2. `action-scope-preview-frag.html` is now a true single-root HTMX fragment without
   `DOCTYPE`, `html`, or `body` wrappers, with a regression assertion.

## Requirement Evidence

| Requirement | Result | Actual code and automated evidence |
|---|---|---|
| DATA-01 | PASS | `MessageMetadataQueryService` depends only on `MessageMetadataRepository`; `WorkspaceModelService` and all five workspace GETs use persisted queries. `Phase9MetadataIndexTest.queryServiceHasNoProviderDependencyAndMetadataHasNoForbiddenFields` and `Phase9WorkspaceShellTest.allFiveRoutesRenderTruthfulSharedShellWithoutProviderCalls` enforce the boundary. |
| DATA-02 | PASS | `IncrementalSyncJob.requestSync` persists QUEUED before executor submission; `MessageMetadataSyncService` transitions through RUNNING, COMPLETE, PARTIAL, and FAILED. `POST /api/metadata-sync` is authenticated and CSRF protected. Metadata integration tests cover all states, immediate return, resume, rejection, and coalescing. |
| DATA-03 | PASS | V6 and `SyncProgress` persist scanned/upserted/page counters, last success, bounded safe errors, and opaque checkpoints. `workspace-state.html` renders progress and safe failure copy. Metadata and workspace state tests cover each branch. |
| DATA-04 | PASS | V6 and `MessageMetadata` expose only explicit metadata columns: sender, subject header, domain, received time, labels, unread, thread references, and approved unsubscribe/address headers. No body, snippet, payload, MIME, attachment, token, or generic raw-header field exists. Entity reflection and H2 schema privacy assertions pass. Gmail requests exactly the 23 approved METADATA headers, including `List-Unsubscribe-Post`. |
| DATA-05 | PASS | V6 enforces unique `(account_id, provider_message_id)`. Page upsert loads existing rows once, updates mutable fields, and preserves `first_seen_at`. The repeated five-page/500-message test finishes with exactly 500 stable rows after two runs. |
| DATA-06 | PASS | Each metadata page commits through `MessageMetadataPageService.upsertPage` with `REQUIRES_NEW`; checkpoint advancement occurs only after return. PARTIAL rows remain queryable while later pages resume in background. Rollback, checkpoint, bounded-run, and resume tests pass. |
| DATA-07 | PASS | `OwnedAccountScopeService` resolves default, numeric, and `all` selections only from normalized-email owned Gmail rows. Malformed, foreign, and Microsoft selections fail closed. Workspace integration and aggregate tests cover selected/all behavior. |
| WORK-01 | PASS | `WorkspaceController` is the sole GET owner for `/senders`, `/unread`, `/unsubscribe`, `/review`, and `/analytics`; all templates use shared navigation, rail, state, and CSS. Route rendering, active navigation, account preservation, and `/dashboard` redirect tests pass. |
| WORK-03 | PASS | `WorkspaceState` and `WorkspaceModelService.aggregate` implement LOADING, EMPTY, PARTIAL, STALE, ERROR, and READY, including never-started, completed-empty, mixed-account, safe-error, freshness, and polling rules. Every state has positive rendered evidence. |
| WORK-04 | PASS | `WorkspaceModelService` counts only QUEUED actions across the selected owned IDs and supplies the selected account label. Shared navigation renders both on every route, including zero and unavailable handling. |
| ACTN-02 | PASS | `ActionScopeService.createOrGet` persists exact canonical IDs, count, typed target/risk summaries, and a stable hash before queue creation. The preview renders count/hash/summaries but not provider IDs; no `UserAction` exists until explicit confirmation. |
| ACTN-03 | PASS | `ActionScope` is `@Immutable`; `ActionScopeHasher` uses `scope-v1`, normalized identity fields, sorted/deduplicated IDs, length-prefixed UTF-8, and SHA-256. Queue, apply, and undo consume the persisted scope and fail closed on snapshot, inverse, hash, or ownership mismatch. End-to-end exact-ID and tampering tests pass. |
| QUAL-04 | PASS | Deterministic 10,000-row tests warm and enforce indexed query under two seconds and each workspace shell GET under one second. Five-route provider-isolation tests pass. |
| QUAL-05 | PASS | Sync is bounded to five pages of 100 and Gmail metadata retrieval batches message gets rather than issuing serial page-level requests. The repeated 500-message test verifies five provider page calls per run, stable identity, updates, and no duplicates. |

## Success Criteria

1. **Repeated 500-message metadata-only sync:** PASS. Idempotency, progress,
   privacy, five-page bounds, and stable first-seen identity are automated.
2. **PostgreSQL indexed provider-free queries within budgets:** PASS. PostgreSQL
   16 V1-V6 migration and Hibernate validation pass; page/query services and all
   workspace GETs are provider-free; deterministic budgets pass.
3. **Immutable action scope through approval, execution, and undo:** PASS.
   Canonical hash fixtures, persisted preview, explicit queue, exact provider
   requests, compatibility checks, collision handling, and tamper rejection pass.
4. **Five-route shared shell with account, queue, and state contracts:** PASS.
   All routes render from one model with authorized account scope, queue count,
   six shared states, truthful disabled capabilities, and no provider interaction.

## Automated Results

All commands used the requested selector:

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

- Focused Phase 3/4/7/8/9 regression matrix: PASS.
- `Phase7BulkActionsTest,Phase9ActionScopeTest` after fragment fix: PASS.
- `Phase9PostgresMigrationTest` with Colima `DOCKER_HOST`: PASS, 1 test,
  PostgreSQL 16.14, no skip.
- Full `mvn -q test` with Colima `DOCKER_HOST`: PASS, 97 tests, 0 failures,
  0 errors, 0 skipped.
- `git diff --check`: PASS.

The macOS Java 21 selector resolves to the installed OpenJDK 26.0.1 runtime on
this host. Tests therefore ran through the requested selector but not an actual
Java 21 installation.

## Migration And Privacy

- Production PostgreSQL and H2 test V6 migrations define equivalent metadata,
  action-scope, ownership, progress, and index contracts using dialect-specific
  array/JSON/timestamp types.
- PostgreSQL 16 upgrade evidence covers V5 row preservation, SUCCESS-to-COMPLETE,
  identity continuity, arrays, timestamps, indexes, composite account ownership,
  and Hibernate `ddl-auto=validate` against the migrated container database.
- H2 upgrade evidence independently covers V5 preservation, status conversion,
  identity restart, and strict Hibernate validation.
- Checkpoint tokens are confined to progress persistence and are not rendered.
  Provider exception payloads are replaced with fixed safe failure text.

## Exact Scope Invariants

- Canonical identity includes version, account, action type, target kind, and
  sorted/deduplicated exact message IDs.
- Scope persistence is immutable and account-owned; cross-account links are
  rejected by the composite foreign key in H2 and PostgreSQL.
- Typed summaries reject unknown JSON fields, arbitrary nested values, invalid
  counts, overlong text, and mutable list aliasing.
- Queue confirmation accepts only the owned scope ID. Apply and undo validate
  account, action, IDs, count, hash integrity, and scoped inverse data before any
  provider mutation.

## Environment Skips

None. Colima was available and PostgreSQL Testcontainers coverage ran successfully
after exporting its user-level Docker socket.

## Non-Blocking Manual Browser Items

The following UI-SPEC checks require visual/human browser judgment and were not
run in this verifier pass:

- Desktop, 980px, 720px, and 375px visual layout.
- Keyboard-only navigation and visible focus flow.
- 200% browser zoom and real rendered horizontal-overflow inspection.

These are non-blocking for Phase 9 because build-failing template/CSS contracts
cover shared fragments, landmarks, active navigation, 44px narrow targets,
breakpoints, focus styling, reduced motion, disabled capability semantics, and
forbidden decorative links. Integrated browser UAT remains explicitly assigned
to Phase 13.

## Repository State

No commit was created. The final worktree remains intentionally dirty with the
Phase 9 implementation/planning files and this verification artifact. Final
whitespace validation passed.
