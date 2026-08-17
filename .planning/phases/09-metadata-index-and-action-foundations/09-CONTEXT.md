# Phase 9: Metadata Index And Action Foundations - Context

**Gathered:** 2026-08-09
**Status:** Ready for planning
**Mode:** Auto-generated (discuss skipped via workflow.skip_discuss)

<domain>
## Phase Boundary

Make every workspace query and action operate from durable, indexed, exact metadata instead of synchronous provider rescans. Deliver idempotent metadata persistence, observable incremental sync, immutable action scopes, response-time baselines, and a shared shell for Senders, Unread, Unsubscribe, Review, and Analytics.

</domain>

<decisions>
## Implementation Decisions

### Data model
- Persist only the approved metadata projection; never body, MIME parts, attachment content, or snippets.
- Key messages by account plus provider message ID and make sync upserts idempotent.
- Store labels and allowed multi-value headers in structured database columns that work in Postgres and the H2 test migration.
- Preserve first-seen time separately from provider received time so later analytics can use honest historical boundaries.

### Sync
- Page through provider metadata with explicit checkpoints and bounded batches.
- Keep page requests database-only; manual and scheduled sync update the index asynchronously.
- Provide one authenticated, CSRF-protected sync request endpoint so the shared shell can queue the selected owned account scope without using the legacy synchronous sender-sync endpoint.
- Reuse the existing per-account non-overlap gate and `SyncProgress` model, extending status and counters where needed.
- Commit each metadata page in a separate proxied transaction before persisting its checkpoint; do not rely on self-invoked `@Transactional` or `@Async` methods.

### Actions
- Create immutable scope rows before queueing or executing an action.
- Compute a stable SHA-256 scope hash from canonical target kind, action type, account, and sorted message IDs.
- Preview, review, execution, history, and undo must reference the same stored scope.
- Preview summaries use fixed, typed metadata-only fields rather than caller-supplied arbitrary maps.

### Workspace shell
- Use one Thymeleaf layout pattern and htmx fragments without adding a frontend build pipeline.
- All five routes must render now; later-phase controls may be visibly disabled with capability text until their backend phase lands.
- Account scope and queue count are server-owned shared model attributes.
- For v1.1, Gmail is the only implemented mail provider. `all` includes only owned Gmail accounts until the deferred Outlook provider exists.
- Multi-account progress has deterministic aggregate precedence, including truthful never-started and mixed-success states.

### Claude's Discretion
Repository projections, JSON versus SQL arrays for approved metadata fields, exact controller/service boundaries, and CSS organization may follow the simplest established codebase pattern that satisfies the requirements and tests.

</decisions>

<code_context>
## Existing Code Insights

- `GmailMailService.listMessages` already enforces metadata-only retrieval and batches metadata gets in groups of ten.
- `IncrementalSyncJob` has a per-account semaphore and records basic progress, but currently aggregates sender stats directly.
- `SenderAggregationController` and `BulkActionApiController` perform live provider scans inside requests and must move to indexed queries.
- `UserAction` already stores message IDs and target JSON but needs a stable scope identity and richer outcome lifecycle in later phases.
- Production and test Flyway migrations are maintained in parallel and Hibernate schema validation is strict.

</code_context>

<specifics>
## Specific Ideas

- Add `message_metadata` and `action_scopes` tables with account-scoped indexes.
- Preserve the mock suite's five-route navigation immediately so later phases fill real fragments rather than creating disconnected pages.
- Add tests proving no provider call occurs in indexed page-query services.
- Establish query timing assertions with deterministic seeded data rather than flaky wall-clock browser tests.

</specifics>

<deferred>
## Deferred Ideas

- Full sender and unread filters and actions belong to Phase 10.
- Network unsubscribe execution belongs to Phase 11.
- Dual execution policy, conflict resolution, outcomes, history, and undo completion belong to Phase 12.
- Analytics queries, exports, and integrated browser UAT belong to Phase 13.

</deferred>
