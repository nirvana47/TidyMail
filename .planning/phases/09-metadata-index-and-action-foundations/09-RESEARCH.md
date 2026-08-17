---
phase: 09-metadata-index-and-action-foundations
status: complete
researched: 2026-08-09
confidence: high
---

# Phase 9: Metadata Index And Action Foundations - Research

## Research Scope

This research reconciles the Phase 9 context with the current Spring Boot application, the five HTML mocks under `tidymail-ux-rehaul/pages`, the project-level architecture/stack/pitfall research, and the completed metadata, action-scope, and workspace-shell explorer findings.

The phase is a foundation phase, not a completion pass for the five product surfaces. It must make indexed metadata, immutable action identity, and a truthful shared shell available without implementing Phase 10 filters/actions, Phase 11 unsubscribe execution, Phase 12 execution policy/outcomes, or Phase 13 analytics/export.

## Locked Constraints

- Persist only the approved metadata projection. Message bodies, MIME parts, attachment content, snippets, OAuth material, and arbitrary raw headers are forbidden.
- Use `(account_id, provider_message_id)` as the message identity and preserve `first_seen_at` across every upsert.
- Keep labels and approved multi-value headers structured in both PostgreSQL and H2 test migrations.
- Sync through provider pages with explicit checkpoints and bounded batches; page requests read only the database.
- Reuse the existing per-account `Semaphore(1)` non-overlap behavior and extend `SyncProgress` instead of creating a second progress model.
- Expose one authenticated, CSRF-protected queue endpoint for manual sync. The existing `/api/senders/_sync` endpoint is synchronous and is not a valid DATA-02 implementation.
- Put page persistence in a separate proxied transaction collaborator and submit background work through a real proxy/executor boundary; self-invoked `@Transactional` and `@Async` methods do not satisfy the commit/queue contract.
- Persist an immutable action scope before queueing or execution. Preview, review, execution, history, and undo use that same row and never resolve the target query again.
- Hash canonical target kind, action type, account, and sorted exact message IDs with SHA-256.
- Render Senders, Unread, Unsubscribe, Review, and Analytics in one Thymeleaf/htmx shell now. Controls owned by later phases must be visibly disabled and explain when they become available.
- Account scope and queued-action count are server-owned model values.
- Gmail is the only implemented v1.1 mail provider. `account=all` therefore includes only owned Google account rows; Microsoft remains deferred with OUTL-01.
- Keep the current Java 21, Spring Boot 3.5, Spring MVC, Thymeleaf, htmx, Spring Data JPA, Flyway, PostgreSQL 16, H2, JUnit 5, and MockMvc stack. Add no SPA, frontend build, external queue, AI, or second datastore.

## Verified Codebase Findings

### Persistence and migration baseline

- Production migrations are in `src/main/resources/db/migration`; H2 mirrors are in `src/test/resources/db/test-migration`. Both currently end at V5 and must receive equivalent V6 objects.
- `spring.jpa.hibernate.ddl-auto=validate` is enabled. Entity and migration types must agree in both dialects at application-context startup.
- Existing portable structured mappings use `@JdbcTypeCode(SqlTypes.ARRAY)` for PostgreSQL `TEXT[]` and H2 `VARCHAR ARRAY`, and `@JdbcTypeCode(SqlTypes.JSON)` for PostgreSQL `JSONB` and H2 `JSON`.
- Existing account-owned tables use foreign keys to `user_accounts(id) ON DELETE CASCADE`.
- Some older tests assert Flyway migration counts. Adding V6 requires updating only assertions that intentionally pin the latest count.

### Provider and sync baseline

- `MailMessage` is already a metadata-only record and `body()` throws `UnsupportedOperationException`.
- `GmailMailService.listMessages` performs one Gmail list request, then metadata gets in batches of ten with `format=METADATA` and a static header whitelist. This satisfies the no-serial-per-message network requirement and should be reused.
- The current whitelist has 22 headers but omits `List-Unsubscribe-Post`, which is required for later RFC 8058 capability detection. Phase 9 should add that approved metadata header and update the whitelist guard/test to 23.
- `IncrementalSyncJob` currently fetches one 100-message page, aggregates sender statistics, and stores only a RUNNING/SUCCESS/FAILED `SyncProgress` row. It does not persist message-level metadata or a provider checkpoint.
- The existing per-account semaphore is in-memory and correct for the documented single-instance deployment. Cluster locking remains out of scope.
- `SenderAggregationController`, `FragmentController`, and `BulkActionApiController` perform provider reads inside user requests. New workspace routes must not call these paths; later phases can migrate their legacy endpoints.

### Action baseline

- `UserAction` already stores account ID, action type, target information, exact `message_ids`, inverse JSON, count, status, and timestamps.
- V5 supports QUEUED/APPLIED/CANCELLED actions. Existing review execution uses `UserAction.messageIds` directly, which is compatible with exact scopes but currently has no stable scope identity.
- Existing action creation is sender-specific and resolves IDs from a live provider scan. Phase 9 must attach a persisted scope while retaining legacy `message_ids` as a compatibility snapshot for current queue/apply/undo code.
- Existing action cleanup assumes queued rows can have `expires_at = null`; schema/entity changes must not reintroduce the obsolete five-second expiry behavior.

### MVC and UI baseline

- `/dashboard` is owned by `LoginController`; `/review` is owned by `ReviewActionsController`. Adding duplicate mappings would fail application startup.
- Existing templates duplicate CSS and expose only Dashboard, Review, and Reply Zero. The new route shell should supersede page GET ownership while leaving mutation endpoints intact.
- Current MockMvc tests fabricate `OAuth2AuthenticationToken` directly and mock providers by bean name. This is the established offline page-test pattern.
- Never pass `Optional<?>` into a Thymeleaf model. Controllers must unwrap to a value or `null`.
- htmx fragments must be partial markup only, with no `DOCTYPE`, `html`, or `body` wrappers.

### Mock-suite findings

All five mocks share the same system:

- 58px sticky top rail, approximately 226px left filter rail, and 1480px maximum workspace width.
- Light neutral background, white cards, indigo primary color, compact 10-12px labels/body copy, 22px page heading, 6/10/16px radii, and low-elevation shadows.
- Five top-level routes with one active tab, account/queue status in the top rail, route-specific left-rail context, page heading, four-metric strip, primary content panel, and next-step footer.
- Desktop tables/panels collapse at approximately 980px and 720px. At narrow widths the document scrolls normally and the rail stacks above content.
- The mocks contain fabricated metrics and decorative `href="#"` controls. Phase 9 must not copy those behaviors. It should preserve layout and labels while replacing unavailable features with native disabled controls and explicit capability copy.

## Design Choices

### V6 schema

Use one V6 migration pair to establish both foundations atomically:

1. `message_metadata`
   - Surrogate `id`.
   - `account_id`, `provider`, `provider_message_id`, and `provider_thread_id`.
   - Parsed sender display/address/domain and subject header.
   - `received_at`, immutable `first_seen_at`, and mutable `last_synced_at`.
   - Structured arrays for labels and approved multi-value address/thread/unsubscribe headers.
   - Derived `unread` boolean for indexed unread queries; source labels remain authoritative.
   - Unique `(account_id, provider_message_id)`.
   - Account-scoped indexes for received time, sender/domain, unread plus received time, thread ID, and last sync time.

2. `action_scopes`
   - Surrogate `id`, `account_id`, normalized `action_type`, normalized `target_kind`.
   - Exact sorted/deduplicated `message_ids`.
   - Lowercase 64-character `scope_hash`.
   - Persisted `message_count`, target summary JSON, risk summary JSON, and `created_at`.
    - Unique `(account_id, scope_hash)` and unique `(id, account_id)` so the UserAction link can enforce account ownership.
   - No update timestamp: scope rows are immutable.

3. Compatibility/progress changes
    - Nullable `user_actions.action_scope_id` for legacy-row compatibility, with composite `(action_scope_id, account_id) -> action_scopes(id, account_id)` ownership and an account/scope index.
   - Extend `sync_progress` with checkpoint token, pages completed, messages upserted, and last successful time.
    - Replace SUCCESS with COMPLETE for the new state vocabulary while preserving existing rows through a migration update. PostgreSQL can drop V4's deterministic `sync_progress_status_check`; the H2 V6 migration must rebuild/copy `sync_progress` because V4 left its check constraint unnamed.
   - Permit exactly QUEUED, RUNNING, COMPLETE, PARTIAL, and FAILED.

### Metadata mapping

Persist a fixed projection, not the entire `MailMessage.headers()` map:

- `From`, `To`, `Cc`, `Bcc`, `Subject`, `Date`
- `List-Unsubscribe`, `List-Unsubscribe-Post`, `List-Id`
- `Precedence`, `Auto-Submitted`, `Return-Path`, `Reply-To`
- `Message-ID`, `References`, `In-Reply-To`, `Delivered-To`
- `Feedback-ID`
- Provider message/thread IDs, received time, labels, and unread derivation

The provider may continue downloading the full approved 23-header whitelist, but the database entity exposes only explicit approved fields. This makes privacy review schema-driven and prevents accidental arbitrary-header persistence.

### Idempotent bounded sync

- A sync request first persists QUEUED progress, then asynchronous work transitions it to RUNNING.
- One run handles at most five provider pages of 100 messages, so the acceptance batch is 500 messages.
- Each page is fetched through `MailProvider.listMessages`, mapped, and upserted transactionally.
- For each page, load existing rows with one account-scoped `IN` query, update mutable metadata in memory, create missing rows, and `saveAll`. Never issue one existence query per message.
- Existing rows retain `first_seen_at`; `received_at` comes from the provider; `last_synced_at` records the current run.
- `MessageMetadataPageService` owns one `REQUIRES_NEW` page upsert. Persist the next provider page token only after that proxied transaction returns successfully.
- A run with another token after page five becomes PARTIAL; no token becomes COMPLETE. Manual requests and the scheduled dispatcher resume the latest PARTIAL checkpoint, so users do not have to keep a page open for older history to continue.
- A new progress row copies the prior committed checkpoint and latest successful time. After COMPLETE, the next incremental run starts from the newest page with a null checkpoint.
- Repeated pages and retried runs converge on one row per `(account_id, provider_message_id)`.
- Do not refresh legacy `sender_stats` from one bounded page/run: doing so would overwrite totals with an incomplete slice. Phase 10 derives sender views from `message_metadata`; existing sender snapshots may remain untouched during the transition.

### Sync request and coalescing

- Add `POST /api/metadata-sync` with CSRF protection and `account=all|{ownedId}` semantics shared with the workspace resolver.
- The endpoint persists QUEUED before submitting work and returns immediately to the current workspace route.
- Maintain at most one active worker and one visible queued continuation per account in this single-instance deployment. A request arriving while the semaphore is held reuses the existing QUEUED row instead of creating unbounded duplicates.
- Background dispatch must use an injected executor or a separate proxied bean. After releasing the semaphore, drain the one coalesced queued continuation.
- Checkpoint values are opaque provider cursors: store them only in `sync_progress`, never render/log them, and never include them in safe failure text.
- Persist/display only allowlisted failure categories and fixed recovery copy. Do not sanitize by truncating and reusing a raw provider exception, which may contain URLs, IDs, or payload fragments; detailed exceptions remain in protected server logs.

### Indexed query service

Introduce a database-only `MessageMetadataQueryService` with typed account-scope and page arguments. Phase 9 needs only foundation queries:

- account-scoped page ordered by `received_at DESC, id DESC`;
- count and freshness summary for one or multiple owned account IDs;
- exact ID lookup for later preview/scope creation;
- sender/domain and unread query primitives backed by V6 indexes.

Do not expose a `MailProvider` dependency from this service. A constructor-level architecture test and MockMvc provider-interaction tests protect this boundary.

### Canonical scope hashing

Canonicalize before persistence:

1. Reject blank IDs.
2. Deduplicate and sort message IDs by Java natural string order.
3. Normalize action type and target kind to uppercase enum names.
4. Serialize a versioned, length-prefixed UTF-8 sequence in fixed order:
   `version`, `accountId`, `actionType`, `targetKind`, then each `messageId`.
5. Hash the exact bytes with JDK `MessageDigest` SHA-256 and encode lowercase hexadecimal.

Length prefixes prevent delimiter ambiguity. The version field allows a later canonicalization change without silently changing old identities. On unique-key reuse, compare all canonical fields before returning the existing row so a theoretical hash collision fails closed.

### UserAction compatibility

- Add nullable `actionScopeId` to `UserAction`; old rows remain valid.
- New queue creation uses `ActionScopeService.createOrGet` before saving `UserAction`.
- Copy the scope's exact IDs and count into existing `UserAction.messageIds` and `affectedCount` fields for current apply/undo compatibility.
- Once attached, action code must reject a mismatch between scope IDs/hash and the compatibility snapshot.
- Do not implement Phase 12 execution modes or outcome expansion here.

### Scope persistence and preview privacy

- Do not recover from a unique-key exception inside the transaction that performed the failed insert; JPA marks that transaction rollback-only. Use a separate `REQUIRES_NEW` insert collaborator, then reload and compare after the failed insert transaction has ended.
- Target and risk summaries are typed, fixed-schema records serialized to JSON. Reject unknown fields, nested arbitrary objects, overlong strings, and values outside the declared scalar/list types. A denylist of suspicious key names is not a privacy boundary.
- Deep-copy JSON-compatible lists on construction and readback. Top-level `Map.copyOf` alone is insufficient for immutable persisted previews.

### Shared workspace state

Resolve the authenticated account first, then derive owned account options from the same normalized email and the explicit implemented-provider set (`google` in v1.1). `ALL` means those owned supported account IDs, never every account in the database. V1's unique `(email, provider)` constraint means this is currently at most one Gmail row; do not fabricate multiple owned Gmail rows in integration tests. Keep aggregation code list-based and unit-test synthetic multiple-ID summaries for future provider parity. A Microsoft row is not selectable until OUTL-01 supplies an Outlook `MailProvider`.

The shared MVC model owns:

- active route;
- allowed account options and selected account scope;
- queued action count across selected owned accounts;
- latest sync/freshness summary;
- workspace state and state copy;
- capability availability for later-phase controls.

Workspace state precedence:

For one account, and for `all` after summing rows/counters and loading each account's latest progress:

1. `LOADING`: zero indexed rows and any scoped account is QUEUED/RUNNING.
2. `ERROR`: zero indexed rows, no active sync, and any scoped account's latest run FAILED.
3. `EMPTY`: zero indexed rows and either no scoped account has started or every started account is COMPLETE. Copy distinguishes `Not synced yet` from a completed empty mailbox.
4. `PARTIAL`: usable rows exist and any scoped account is QUEUED/RUNNING/PARTIAL/FAILED or has never completed.
5. `STALE`: usable rows exist, no account is incomplete/failed, and any scoped account lacks a success time or its success is older than 15 minutes.
6. `READY`: usable rows exist and every scoped account is complete with a success time no older than 15 minutes.

Polling is a separate `syncActive` model flag and is true when any scoped latest run is QUEUED/RUNNING. It is not inferred from `WorkspaceState`, because PARTIAL can also be actively syncing.

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| PostgreSQL/H2 type drift | Maintain V6 in both migration locations; boot a full H2 context with Hibernate validation; run a real PostgreSQL Flyway/boot smoke when Docker is available. |
| Message privacy expands through a generic header map | Map only explicit entity fields; add reflection/schema assertions forbidding body, snippet, payload, MIME, attachment, token, and raw-header columns. |
| Duplicate rows under retry | Database unique constraint plus page-level existing-row load and update; repeat the same 500-message fixture twice and assert stable row count/IDs/first-seen timestamps. |
| Gmail page token checkpoint advances before data commit | Put page upsert in a separate `REQUIRES_NEW` collaborator and update checkpoint only after that proxied call returns successfully. |
| Self-invoked `@Async` does not run asynchronously | Keep the async entry point in a separate Spring bean or invoke it through a proxied collaborator; test QUEUED response before completion. |
| Scope drift after preview | Scope entity has no public mutators; all downstream IDs come from the stored row; tests mutate source lists and repository state to prove the scope remains unchanged. |
| Hash ambiguity/collision | Versioned length-prefixed canonical bytes and full-field comparison on hash reuse. |
| Unsafe all-account scope | Resolve only implemented-provider accounts owned by the authenticated principal's normalized email; reject unknown/unsupported account IDs with 404/400 rather than falling back to ALL. |
| Action-scope insert race poisons caller transaction | Perform the insert in `REQUIRES_NEW`; catch outside that transaction, reload by account/hash, and compare all canonical fields. |
| Arbitrary preview JSON stores private content under innocent keys | Accept typed fixed-schema target/risk records only; reject unknown/nested/unbounded values and deep-copy collections. |
| Duplicate MVC mappings | Move GET ownership deliberately: `/dashboard` redirects to `/senders`; `/review` GET moves to the workspace controller while review POST endpoints remain in `ReviewActionsController`. |
| Decorative future controls | Use native `disabled`, `aria-disabled="true"`, visible availability text, and no `href="#"`. Navigation and account scope remain functional. |
| Flaky timing tests | Seed deterministic H2 data once, warm the query, assert indexed query shape/count and use the 1s/2s limits as coarse integration ceilings, not microbenchmarks. |

## Test Strategy

### Plan 01: metadata and sync

- H2 Flyway V6 plus `ddl-auto=validate` context startup.
- Schema/index inspection for unique and account-scoped indexes.
- Metadata projection test proving approved fields round-trip and forbidden fields do not exist.
- Upsert test: same IDs twice, changed labels/subject on retry, stable row count and `firstSeenAt`, updated mutable fields.
- 500-message test: five 100-message pages, Gmail/provider method called five times, one row per ID, no serial repository lookup per ID.
- Checkpoint tests for QUEUED -> RUNNING -> PARTIAL/COMPLETE and failure retention.
- Resume test: persisted token is passed to the next provider call only after a committed page.
- MockMvc test for the CSRF-protected manual queue endpoint, ownership/all-scope handling, immediate QUEUED response, and request coalescing.
- Regression updates for the legacy sync fragment/tests from SUCCESS to COMPLETE.
- Indexed query service test with deterministic seeded rows and no provider dependency.

### Plan 02: exact action scopes

- Canonicalization permutation, duplicate-ID, delimiter, Unicode, account, action, and target-kind tests.
- Known SHA-256 fixture to prevent accidental algorithm drift.
- Persistence immutability test after entity-manager clear/reload.
- Preview persistence test for exact count, target summary, risk, and hash.
- `createOrGet` idempotency and collision fail-closed test.
- Concurrent create recovery test proving reload happens outside the failed insert transaction.
- Typed summary allowlist, unknown-field, nested-value, length-bound, and deep-copy tests.
- UserAction compatibility test proving `action_scope_id`, `message_ids`, and count are exact and unchanged through preview/queue/reload.

### Plan 03: workspace shell

- MockMvc render tests for all five routes and active navigation.
- Account ownership/scope integration tests for the current account, `all` resolving the same one supported row, and rejected foreign/unsupported Microsoft accounts; pure aggregation tests may use synthetic multiple-ID summaries.
- Queue-count tests across selected scope.
- State-contract tests for LOADING, EMPTY, PARTIAL, STALE, ERROR, and READY.
- Mixed-account aggregate tests and never-started EMPTY copy; polling assertions use `syncActive`, including active PARTIAL.
- `verifyNoInteractions(gmailProvider, outlookProvider)` after every workspace GET.
- HTML assertions for disabled unavailable controls, capability copy, no `href="#"`, semantic landmarks, active-tab state, and accessible status regions.
- Static CSS assertions for shared tokens, 980/720/375 breakpoints, no page-level fixed minimum width, visible focus, reduced motion, and 44px narrow-screen controls.
- Coarse shell render budget under one second and indexed result budget under two seconds on deterministic localhost test data.

## Requirement Coverage Recommendation

| Plan | Requirements |
|------|--------------|
| 09-01 | DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, DATA-06, QUAL-05 |
| 09-02 | ACTN-02, ACTN-03 |
| 09-03 | DATA-07, WORK-01, WORK-03, WORK-04, QUAL-04 |

All 14 Phase 9 requirements are covered once, and no later-phase requirement is claimed.

## Sources

- `.planning/PROJECT.md`
- `.planning/ROADMAP.md`
- `.planning/REQUIREMENTS.md`
- `.planning/STATE.md`
- `.planning/phases/09-metadata-index-and-action-foundations/09-CONTEXT.md`
- `.planning/research/{ARCHITECTURE,STACK,PITFALLS,FEATURES,SUMMARY}.md`
- `tidymail-ux-rehaul/pages/*.html`
- `src/main/java/com/tidymail/**`
- `src/main/resources/{db/migration,templates}/**`
- `src/test/java/com/tidymail/**`
- `src/test/resources/db/test-migration/**`

## Research Conclusion

Phase 9 should use one V6 database foundation, a checkpointed 500-message metadata sync service, an immutable canonical action-scope service, and a server-owned shared MVC shell. The application already has the required provider batching, persistence stack, authentication test pattern, and htmx conventions; the work is primarily to establish durable boundaries and remove provider access from page rendering without prematurely implementing Phases 10-13.
