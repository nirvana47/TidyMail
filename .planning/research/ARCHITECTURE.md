# Architecture Research: v1.1 Complete Cleanup Workspace

## Data Flow

1. Scheduled or manual sync lists provider message IDs in pages and fetches metadata in bounded batches.
2. A metadata upsert stores the latest allowed headers and labels without bodies.
3. Deterministic classifiers derive sender type, unread type, actionability, unsubscribe method, confidence, risk, and protected state.
4. Query services apply account, time, search, queue, status, and sort filters against PostgreSQL.
5. Selection creates an immutable action scope containing exact message IDs and a visible impact summary.
6. Staged mode queues the scope for review. Immediate mode sends the same scope directly to the executor.
7. The executor records per-action outcomes and inverse state before exposing success.
8. Analytics reads sync facts and action outcomes, never reinterpreting current state as historical state.

## New Components

- `MessageMetadata` entity and repository
- `MessageMetadataSyncService` with checkpointed, idempotent upserts
- `RecommendationService` for deterministic classifications and explanations
- `WorkspaceQueryService` for Senders, Unread, and Unsubscribe views
- `ActionBatch`/expanded `UserAction` model and `ActionExecutionService`
- `UnsubscribeService` with one-click, legacy, mailto, and manual methods
- `AnalyticsService` with typed filter and bucket DTOs
- Shared Thymeleaf workspace shell and htmx fragments
- CSV export endpoints using the same server-side filters as the visible tables

## Modified Components

- `MailProvider` gains query/sync primitives needed for exact metadata indexing and provider web links.
- `GmailMailService` exposes paged metadata sync without rescanning a fixed 500-message window per action.
- `IncrementalSyncJob` advances provider checkpoints and records sync facts.
- `UndoService` becomes outcome-aware and uses succeeded IDs only.
- Existing sender aggregation reads the metadata index rather than invoking Gmail in page requests.

## Build Order

1. Persistence, sync checkpoints, exact action contracts, and shared workspace shell
2. Sender and Unread query surfaces
3. Unsubscribe methods and protected decisions
4. Review, execution policies, partial outcomes, history, and undo
5. Analytics, exports, drilldowns, responsive integration, and localhost UAT

## Compatibility

Provider-neutral interfaces remain intact. Gmail is the only provider required for live verification in this milestone; Outlook implementations may return capability-not-available until its skipped provider phase is completed, but no Gmail-specific identifier may leak into shared entities or routes.
