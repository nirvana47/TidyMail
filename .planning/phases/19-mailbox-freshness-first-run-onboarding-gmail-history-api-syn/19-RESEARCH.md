# Phase 19 Research: Mailbox Freshness and First-Run Onboarding

**Phase:** 19 - Mailbox Freshness & First-Run Onboarding - Gmail History API sync
**Researched:** 2026-08-14
**Requirements:** FRESH-01 through FRESH-10
**Scope:** Research only. This document recommends planning boundaries; it does not implement them.

## Executive Summary

Phase 19 should replace the current single pagination job with a durable, two-lane synchronization system:

1. A high-priority **freshness lane** consumes Gmail History API changes from a durable `historyId`.
2. A low-priority **backfill lane** independently walks `messages.list` pages until the full mailbox is indexed.

The lanes must not share a cursor, worker queue, or account-wide lock. They may touch the same message, so correctness cannot depend on Java-side read-then-save. Metadata persistence should use PostgreSQL atomic upserts ordered by Gmail's per-message `historyId` (stored as `NUMERIC(20,0)` or an equivalently safe representation), with `(account_id, provider_message_id)` remaining the idempotency key.

For a new Gmail account, capture a mailbox profile/history anchor first, ingest roughly 200 recent non-spam/non-trash messages for a useful first screen, then run the history lane from that anchor and start a separate full-mailbox backfill with `includeSpamTrash=true`. The browser request must not own either job. The activity page should therefore never show "do not close" after the OAuth callback has completed; it should explicitly say background work can continue if useful.

An expired History API cursor is not safely repaired by merely replacing it with the newest ID. Recovery should:

1. mark a full rescan as required/in progress;
2. capture a new profile `historyId`;
3. perform a bounded recent reconciliation promptly;
4. activate the new history cursor only after that bounded work commits; and
5. continue a generation-marked full rescan in the backfill lane, including a guarded sweep for messages no longer present.

OAuth refresh must move from `UserCredentials.refreshIfExpired()` in every `GmailClientFactory.buildFor` call to Spring Security's background-capable `AuthorizedClientServiceOAuth2AuthorizedClientManager` with a `refreshToken()` provider. Its success path persists through `JpaOAuth2AuthorizedClientService`. Before enabling its failure handler, change `JpaOAuth2AuthorizedClientService.removeAuthorizedClient`: it currently deletes the entire `UserAccount` and cascades all user data, which is unacceptable for an expired or revoked refresh token.

## Current Implementation Map with Exact Source Paths/Symbols

### Provider and Gmail client

- `src/main/java/com/tidymail/mail/MailProvider.java`
  - `listMessages(UserAccount, String, int)` is the only synchronization read contract.
  - The interface has no change feed, profile snapshot, deletion event, current-state batch fetch, or provider cursor capability.
- `src/main/java/com/tidymail/mail/PageResult.java`
  - Carries items and one `nextPageToken`; it does not carry a provider total or snapshot revision.
- `src/main/java/com/tidymail/mail/MailMessage.java`
  - Already carries labels, `sizeEstimate`, `hasAttachments`, and `metadataSnippet`.
  - `isUnread()` derives unread from the `UNREAD` label.
  - It does not carry Gmail message `historyId`, so concurrent backfill/history writes cannot be ordered by provider revision.
- `src/main/java/com/tidymail/mail/ProviderCapabilities.java`
  - Models denominator reliability only. It is a useful pattern for capability declaration, but it does not declare incremental-sync support.
- `src/main/java/com/tidymail/mail/gmail/GmailMailService.java`
  - `listMessages` calls `users.messages.list`, then fetches each message in batches of 10 with `format=METADATA` and the 24-header `METADATA_HEADERS` whitelist.
  - It currently leaves `includeSpamTrash` unset, so the full mailbox contract is not explicit.
  - `toMailMessage` correctly reads `labelIds`, `sizeEstimate`, MIME part filename/attachment ID, `internalDate`, headers, and snippet while rejecting raw/body data.
  - There are two critical caveats in that statement:
    - Gmail's official `Format.METADATA` contract returns only message ID, labels, and email headers. It does not promise MIME child-part filenames or attachment IDs, so `hasAttachmentMetadata(payload)` is not a reliable attachment source and will normally see no part tree.
    - Gmail documents `snippet` as a short part of message text. Persisting `gmailMessage.getSnippet()` as `metadataSnippet` is inconsistent with the locked no-body-content privacy rule even if Gmail happens to include it.
  - `toMailMessage` ignores `Message.historyId`.
  - No `users.history.list` or `users.getProfile` call exists.
- `src/main/java/com/tidymail/mail/gmail/GmailClientFactory.java`
  - `buildFor` decrypts only the refresh token, constructs Google `UserCredentials`, and calls `refreshIfExpired()`.
  - Because the Google credential is rebuilt without the persisted access token, client construction can perform a refresh network request repeatedly.
  - The refreshed token is not written back to `user_accounts`.
- `src/main/java/com/tidymail/mail/gmail/GmailRateLimiter.java`
  - One process-wide non-fair semaphore wraps whole provider operations. It does not distinguish freshness from backfill, so backfill can consume all permits.
- `src/main/java/com/tidymail/mail/gmail/GmailSyncConfigProperties.java`
  - Configures only API concurrency and a batch delay.

### Current synchronization and transaction boundaries

- `src/main/java/com/tidymail/service/MessageMetadataSyncService.java`
  - `JOB_TYPE = "INCREMENTAL_SYNC"` is misleading: the implementation is a bounded `messages.list` backfill.
  - `MAX_PAGES = 5` and `PAGE_SIZE = 100` create the accidental 500-message behavior.
  - `synchronize` advances one `checkpointToken` after each committed page and marks the run `PARTIAL` after five pages.
  - It has no History API semantics, deletion handling, rescan state, provider total, first-batch lifecycle, or sender/category readiness milestone.
- `src/main/java/com/tidymail/service/MessageMetadataPageService.java`
  - `upsertPage` runs in `REQUIRES_NEW`, deduplicates a page by message ID, loads existing rows, maps, then calls `saveAll`.
  - This gives page-level retry safety in one process, but it is a read-then-write race across concurrent freshness/backfill lanes. Concurrent first inserts can still collide on the unique constraint; stale fetches can overwrite newer labels.
- `src/main/java/com/tidymail/service/MessageMetadataMapper.java`
  - Maps unread, labels, attachment presence, and size correctly into `MessageMetadata`.
  - Gmail-specific DKIM trust is already explicitly gated by `message.provider()`.
- `src/main/java/com/tidymail/job/IncrementalSyncJob.java`
  - Runs every five minutes and queues all accounts.
  - Uses process-local `ConcurrentHashMap<Long, Semaphore>` and request monitors.
  - One semaphore excludes all work for an account, so a historical page walk and fresh-mail ingestion cannot coexist.
  - Maps Microsoft to an `outlookMailService` bean even though no production implementation exists.
  - Process-local maps are not crash durable, cross-instance safe, or cleaned up.
- `src/main/java/com/tidymail/entity/SyncProgress.java` and
  `src/main/java/com/tidymail/repository/SyncProgressRepository.java`
  - `sync_progress` is an append-only-ish run record carrying one checkpoint, counts, status, and last successful time.
  - It cannot represent independent history, backfill, and rescan state.
  - `messagesUpserted` is actually attempted rows, not necessarily newly inserted rows.

### Persistence

- `src/main/java/com/tidymail/entity/MessageMetadata.java`
  - The unique key is `(account_id, provider_message_id)`, the correct message idempotency key.
  - Labels, unread, size, attachment presence, headers, and timestamps are already stored.
  - There is no provider revision, full-scan generation, or tombstone/seen marker.
  - `hasAttachments` defaults to false, which collapses "METADATA did not provide MIME structure" into a false claim of "no attachment".
  - `metadataSnippet` is body-derived text and should not remain populated under the locked privacy contract.
- `src/main/java/com/tidymail/repository/MessageMetadataRepository.java`
  - Supports batch lookup by provider ID and basic freshness summary.
  - `FreshnessSummary.lastSyncedAt` is the maximum row write time, not proof that Gmail was successfully checked.
  - There is no conditional delete or generation sweep query.
- `src/main/resources/db/migration/V6__metadata_index_and_action_scopes.sql`
  - Creates the metadata unique constraint and indexes.
  - Extends `sync_progress` with the current single checkpoint.
- `src/main/resources/application.properties` and `.env.example`
  - `SYNC_INITIAL_PAGE_SIZE=500` conflicts with the locked first-batch target of approximately 100-250.
  - `SYNC_MAX_MESSAGES_PER_ACCOUNT=50000` conflicts with FRESH-08 and DECISIONS.md section 11. It must default to unlimited and must not tell users to archive Gmail data.

### OAuth token persistence

- `src/main/java/com/tidymail/entity/UserAccount.java`
  - Stores encrypted access/refresh tokens, issued/expires timestamps, scopes, and account identity.
- `src/main/java/com/tidymail/security/oauth2/JpaOAuth2AuthorizedClientService.java`
  - `save` correctly persists refreshed access tokens and preserves an existing refresh token if the provider omits a replacement.
  - `loadAuthorizedClient` currently substitutes `Instant.now()` for missing `issuedAt` and then substitutes `issuedAt` for missing `expiresAt`. This fabricates an immediately expired token.
  - `removeAuthorizedClient` deletes the `UserAccount`, cascading metadata, actions, settings, and tokens. Do not connect this method to automatic refresh-failure handling.
- `src/main/java/com/tidymail/security/SecurityConfig.java`
  - Registers the existing four Google scopes and requests offline access through Google-specific authorization parameters.
  - Phase 19 does not need the Phase 22 scope expansion.
- `src/main/java/com/tidymail/security/oauth2/OAuthLoginSuccessListener.java`
  - Saves the callback client, updates `lastLoginAt`, then calls `IncrementalSyncJob.requestSync`.
  - It does not distinguish a brand-new account from a returning account or direct a first-run lifecycle.
- `src/main/java/com/tidymail/security/AccountLoginSuccessHandler.java`
  - Always redirects to `/dashboard`, which redirects to `/senders`.

### Read models and UI

- `src/main/java/com/tidymail/service/WorkspaceModelService.java`
  - Builds indexed/scanned/page counts and a coarse `WorkspaceState`.
  - It treats a recent `SyncProgress.lastSuccessfulAt` as ready, even though the current job may only have exhausted one pagination walk.
  - It computes Inbox unread through `DecisionSurfaceQueryRepository.countUnread`.
- `src/main/java/com/tidymail/repository/DecisionSurfaceSql.java`
  - `unread` is the current source of truth for Inbox unread: `m.unread = true` plus `'INBOX'=ANY(m.labels)`.
  - Sender aggregates use `m.unread` without the Inbox predicate, so those values are All-Mail unread. The UI must label that distinction rather than treating both as one concept.
- `src/main/resources/templates/unread.html`
  - Already labels the total as "Inbox unread".
- `src/main/resources/templates/fragments/unread-results-frag.html`
  - Displays unread counts and estimated full-message size.
- `src/main/java/com/tidymail/service/UnreadDecisionQueryService.java`
  - Aggregates `sizeEstimate` and attachment-derived category membership.
- `src/main/java/com/tidymail/repository/AnalyticsMailboxRepository.java` and
  `src/main/resources/templates/fragments/analytics-results-frag.html`
  - Display size-based metrics, but attachment presence itself is not visibly proven on an individual metadata row.
- `src/main/resources/templates/fragments/workspace-state.html`
  - Shows indexed/scanned/page data but no provider total, remaining count, lane/phase, truthful ETA, history freshness age, or rescan state.
- `src/main/resources/templates/fragments/sync-progress-frag.html`
  - Legacy badge reports per-run scanned/upserted counts; it is not a mailbox activity model.
- `src/main/resources/templates/fragments/workspace-rail.html` and
  `src/main/java/com/tidymail/dto/WorkspaceRoute.java`
  - Have no dedicated Sync/Activity route.
- `src/main/java/com/tidymail/controller/MetadataSyncController.java`
  - POSTs one generic sync request and allows only the existing five workspace return routes.

### Phase 18 test foundation

- `src/test/java/com/tidymail/support/PostgresJourneyTest.java`
  - Shared PostgreSQL/Testcontainers + MockMvc harness; provider beans are mocked.
- `src/test/java/com/tidymail/support/SyntheticMailboxGenerator.java`
  - Deterministically seeds at least 10,000 realistic metadata rows.
- `src/test/java/com/tidymail/journey/SenderTriageJourneyTest.java`
  - Existing first-screen sender journey should be extended, not duplicated.
- `src/test/java/com/tidymail/journey/UnreadReviewJourneyTest.java`
  - Existing unread UI/action journey should gain explicit Inbox-vs-All-Mail evidence.
- `src/test/java/com/tidymail/journey/AnalyticsJourneyTest.java`
  - Existing size display journey can absorb metadata size assertions.
- `src/test/java/com/tidymail/Phase9MetadataIndexTest.java`
  - Contains the current sync retry, five-page bound, coalescing, and endpoint tests. Its useful behavior should be consolidated into readable freshness journeys/contracts; do not add another large phase-numbered test family.
- `src/test/java/com/tidymail/Phase4GmailProviderTest.java`
  - Protects the METADATA whitelist and body guard, but currently validates the unwanted refresh-on-build behavior.

## Recommended Architecture and Sequencing

### 1. Add provider-neutral sync capabilities without forcing Outlook parity

Do not put Gmail `historyId` directly into the existing generic `MailProvider` methods. Add a separate capability/SPI, for example:

- `MailboxSnapshot profile(UserAccount)` -> current provider cursor, reliable total when available.
- `PageResult<MailMessage> listBackfill(..., BackfillRequest)` -> independent historical cursor.
- `ChangePage listChanges(..., ChangeRequest)` -> additions, label changes, deletions, next cursor.
- `List<MailMessage> getCurrentMetadata(..., ids)` -> current METADATA state for changed IDs.
- `SyncCapabilities` -> `HISTORY`, `DELTA`, or `NONE`; reliable-total and full-scan support.

`GmailMailService` can implement this SPI. The existing mutation contract remains unchanged. Future Outlook support can map Graph delta links to the same change-page concepts; until that exists, Microsoft must report `NONE` and the activity page must say synchronization is unavailable for that provider. It must not run Gmail code, store Gmail history IDs, or report fresh/complete.

### Critical FRESH-10 feasibility finding

Official Gmail `Format.METADATA` semantics are:

https://developers.google.com/workspace/gmail/api/reference/rest/v1/Format

> Returns only email message ID, labels, and email headers.

Unread and `sizeEstimate` are available on the message resource and can be proven through the current METADATA path. MIME attachment presence is not promised by METADATA. The current `toMailMessage` implementation assumes a MIME part tree will be present, so its `hasAttachments` result is not trustworthy.

Planning must explicitly resolve this contradiction without widening to `FULL`/`RAW`:

- Recommended privacy-preserving option: maintain a separate Gmail attachment membership index from `messages.list(q="has:attachment")`, which returns message IDs only under the existing `gmail.modify` scope. Reconcile that index progressively and treat attachment state as `UNKNOWN` until the relevant scan is complete.
- For fresh changed IDs, use bounded Gmail search batches based on their `Message-ID` headers where available, and otherwise retain `UNKNOWN` until attachment-index reconciliation. Do not turn unknown into false.
- Record provenance (`GMAIL_SEARCH`) and observation time. The UI can display Present/None/Checking rather than a false boolean.
- Do not switch to `format=FULL` with a partial `fields` projection unless the locked metadata-only decision is explicitly reopened. The format itself asks Gmail for full parsed message data and is outside current authority.

This means FRESH-10 should audit `toMailMessage` for unread/size and the separate metadata-only attachment-membership path for attachment presence. If the requirement is interpreted as requiring attachment presence from the single `messages.get(format=METADATA)` response, it is technically incompatible with Gmail's documented API and cannot be planned as written.

### 2. Create durable account sync state, separate from run/activity rows

Use one `mailbox_sync_state` row per account as the state machine. `sync_progress` may remain as a bounded activity/run history after adding lane/phase fields, but it should not be the authoritative cursor store.

Suggested lifecycle:

- `NEW`
- `BOOTSTRAPPING`
- `BACKFILLING`
- `ACTIVE` (history is healthy; backfill may still be running)
- `RESCAN_REQUIRED`
- `RESCANNING`
- `REAUTH_REQUIRED`
- `ERROR`

Keep independent fields for:

- incremental/history cursor and last successful provider check;
- backfill page token and completion;
- initial-batch completion;
- provider total snapshot and when it was observed;
- full-rescan generation/start/anchor/completion;
- lane leases and retry/error facts.

### 3. First-run sequence

For a newly persisted Google account:

1. Acquire the bootstrap lease and call `users.getProfile` to capture `H0` and `messagesTotal`.
2. Persist `H0`, the total, and `BOOTSTRAPPING`.
3. Fetch approximately 200 recent useful messages with `messages.list`; use METADATA only.
4. Commit that batch atomically through the metadata upsert path.
5. Mark `initial_batch_complete=true`; the existing sender/category SQL can render immediately.
6. Queue the high-priority history lane from `H0`.
7. Start the independent full backfill from a null backfill cursor with `includeSpamTrash=true`.

The initial recent fetch is not the backfill cursor. Starting full backfill again from page one is intentionally idempotent and avoids pretending the useful first batch and complete-mailbox traversal have identical filters/snapshot semantics.

Capture `H0` before the first batch. Mail arriving after `H0` is then caught by history; if it also appears in the first batch, the unique key/revision-aware upsert deduplicates it.

### 4. Independent worker scheduling

- Freshness: fixed-delay/fixed-rate poll no slower than the existing five-minute cycle, plus immediate request after login/manual sync.
- Backfill: one bounded page/work unit per dispatch, requeueing itself until complete. Never hold a thread, transaction, or account-wide lock for the entire mailbox.
- Use separate executors/queues so queued backfill work cannot occupy every freshness worker.
- Make the Gmail request governor fair or freshness-aware. Reserve capacity or prioritize change-feed/current-metadata calls over backfill metadata batches.
- Use durable compare-and-set leases per `(account_id, lane)` with owner and expiry. Process-local semaphores can remain a fast optimization, not the correctness boundary.

### 5. Atomic commit service

Provider network calls must occur outside database transactions. For each fetched history/backfill unit, call a short `REQUIRES_NEW` commit service that:

1. locks/validates the relevant `mailbox_sync_state` row;
2. atomically upserts current message metadata;
3. conditionally deletes permanent-deletion IDs;
4. updates counts/activity facts;
5. advances only that lane's durable cursor; and
6. commits all five effects together.

Use PostgreSQL `INSERT ... ON CONFLICT ... DO UPDATE`, not JPA load-then-save, for the concurrent lanes. Add Gmail message `historyId` to `MailMessage` as a provider revision and store it safely. Gmail exposes it as an unsigned numeric string; do not parse it into Java `long`. Use `BigInteger`/`NUMERIC(20,0)` or retain the string with numeric comparison implemented safely.

Update a row only when the incoming provider revision is at least the stored revision. This prevents a slower backfill fetch from overwriting newer labels applied by the history lane.

## Gmail History API Semantics and Failure Handling

Official sources:

- Synchronization guide: https://developers.google.com/workspace/gmail/api/guides/sync
- History list reference: https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.history/list
- Profile reference: https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile
- Message list/get references:
  https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list and
  https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get
- Performance/partial response guidance:
  https://developers.google.com/workspace/gmail/api/guides/performance

Important semantics:

- `history.list` requires `startHistoryId`.
- History records are chronological, but IDs have random gaps and are not contiguous.
- A response's `historyId` is the mailbox's current history record.
- `nextPageToken` means more history pages remain.
- History records are usually retained for at least a week, but may be valid for only hours; stale/invalid IDs typically produce HTTP 404.
- History payload messages usually contain only IDs/thread IDs. Changed messages must be fetched with `messages.get(format=METADATA)`.
- Use the specific `messagesAdded`, `messagesDeleted`, `labelsAdded`, and `labelsRemoved` arrays. The generic `messages` collection duplicates specific events.
- Do not filter history by `labelId` or a subset of history types: TidyMail needs full-mailbox additions, deletions, and label/unread changes.

### Event application

For each complete history record:

- Additions and label add/remove events: collect the message ID, then fetch current METADATA. Do not patch local labels from event deltas because one cycle may contain multiple changes and current provider state is authoritative.
- Permanent deletions: conditionally remove the metadata row.
- If an ID is both changed and deleted in the fetched interval, attempt current METADATA; a provider 404 means delete. A successful current fetch wins.
- Deduplicate IDs within the work unit before metadata fetch.
- Treat metadata-get 404 as an idempotent delete; retry 429/5xx with bounded exponential backoff and jitter.
- Do not retry 401 indefinitely. Reauthorize once through the managed client, then mark `REAUTH_REQUIRED` on a terminal refresh failure.

### Cursor advancement and crash semantics

- Never persist the top-level response `historyId` before all pages/events are durable.
- A practical bounded approach is to commit one full history record/page unit and advance the durable cursor to the last completely applied record ID. On retry, ask for records after that ID.
- On a final response with no `nextPageToken`, advance to the response `historyId`, even when no changes were returned.
- A crash after metadata commit but before cursor commit must not be possible; both belong in one transaction.
- A crash before that transaction retries the same provider changes. Atomic upsert and conditional delete make replay harmless.

### Expired history recovery

An HTTP 404 for `startHistoryId` means there may be unknown additions, label changes, and permanent deletions. Do not:

- silently replace the cursor and claim current;
- block fresh polling behind a synchronous full-mailbox scan; or
- delete local rows merely because they were absent from a bounded recent window.

Recommended recovery:

1. Mark `RESCAN_REQUIRED`, then acquire the rescan/backfill lease.
2. Capture profile cursor `H1`, total, and a rescan generation/start time.
3. Reconcile a configured bounded recent window immediately (message-count bound, not storage/GB).
4. Atomically install `H1` as the new history cursor after the bounded reconciliation.
5. Mark `RESCANNING`; fresh changes after `H1` now flow independently.
6. Traverse the full mailbox progressively, stamping rows with the rescan generation.
7. Only after traversal completion, sweep rows not seen in this generation and not updated by the freshness lane since the rescan started.
8. Mark rescan complete and expose the completion time.

The generation/sweep guard matters: without it, a message inserted by history after the full scan's page boundary could be incorrectly deleted at sweep time.

## Data Model/Migration Plan

Use the next Flyway migration after V14, likely V15. Exact names can be finalized in planning.

### `mailbox_sync_state`

Recommended columns:

- `account_id BIGINT PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE`
- `provider VARCHAR(16) NOT NULL`
- `lifecycle VARCHAR(24) NOT NULL`
- `incremental_cursor VARCHAR(64)`
- `incremental_cursor_kind VARCHAR(24)` (`GMAIL_HISTORY`, future `GRAPH_DELTA`)
- `incremental_last_success_at TIMESTAMPTZ`
- `incremental_last_attempt_at TIMESTAMPTZ`
- `backfill_page_token VARCHAR(2048)`
- `backfill_status VARCHAR(24)`
- `backfill_started_at`, `backfill_completed_at`, `backfill_updated_at`
- `initial_batch_completed_at`
- `provider_message_total BIGINT`
- `provider_total_observed_at`
- `rescan_status`, `rescan_generation UUID`, `rescan_started_at`,
  `rescan_anchor_cursor`, `rescan_completed_at`
- safe error code/detail fields with bounded lengths
- lease owner/expiry fields per lane, or a separate `sync_leases` table
- optimistic `version BIGINT`

Do not store ETA as authoritative state. Store observed counts/timestamps and derive a labeled estimate.

### `message_metadata`

Add:

- `provider_revision NUMERIC(20,0)` nullable for providers without a comparable revision.
- `full_scan_generation UUID` nullable.
- Replace the false-default attachment boolean with a tri-state representation, for example
  `attachment_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'` plus
  `attachment_observed_at` and `attachment_source`. A compatibility boolean may be derived as
  `attachment_state = 'PRESENT'`; it must not represent unknown as false.

Keep `(account_id, provider_message_id)` unique. Add an index supporting a guarded generation sweep, for example `(account_id, full_scan_generation, last_synced_at)`.

Clear existing `metadata_snippet` values and stop populating them; preferably remove the column after all consumers are confirmed absent. Do not add body or attachment-content columns.

### `sync_progress`

Either migrate it into a bounded run/activity table or extend it with:

- `lane` (`BOOTSTRAP`, `FRESHNESS`, `BACKFILL`, `RESCAN`)
- `phase`
- indexed/processed/failed counts
- provider total snapshot
- safe retry/error code

Avoid one growing row per five-minute no-op forever; retain only a bounded activity history or update lane summaries.

### OAuth state

Add an explicit account credential status such as:

- `oauth_status` (`ACTIVE`, `REAUTH_REQUIRED`)
- `oauth_error_code`
- `oauth_error_at`

Do not delete an account on refresh failure. If token blobs remain non-null, preserve encrypted values for diagnosis/retry and gate provider calls on status.

### Configuration

Replace contradictory defaults:

- initial batch: default around `200`, validated within the locked 100-250 range;
- backfill page size: at most Gmail's 500 list maximum, with metadata batching still bounded;
- message safety ceiling: nullable/zero means unlimited by default;
- expired-history recent repair bound: separate maintainer setting;
- retention/activity limits and lease durations.

Never present the safety ceiling as Gmail GB/storage.

## Token Refresh Design

Spring's official model assigns refresh and persistence to `OAuth2AuthorizedClientManager`:

https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html#oauth2Client-authorized-manager-provider

For scheduled/background work use `AuthorizedClientServiceOAuth2AuthorizedClientManager`, not the servlet-request-oriented default manager. Configure an `OAuth2AuthorizedClientProvider` with `refreshToken()`.

Recommended call path:

1. `GmailClientFactory` asks a small credential service for an authorized client using registration ID `google` and principal name `UserAccount.providerAccountId`.
2. The manager loads the encrypted client through `JpaOAuth2AuthorizedClientService`.
3. If the access token is still valid, no token endpoint call occurs.
4. If expired and a refresh token exists, the manager refreshes once and its success handler saves the new access token/expiry through the JPA service.
5. Build the Gmail request initializer from that managed access token. Do not give the Google client an independent refresh-token lifecycle.

Required fixes:

- In `loadAuthorizedClient`, pass nullable issued/expiry timestamps through instead of manufacturing `now` and `issuedAt`. A null expiry means unknown, not expired.
- Preserve the existing refresh token when Google omits a replacement; current `save` already does this.
- Serialize refresh for the same account with a short single-flight/lease so freshness and backfill do not both refresh an expired token.
- Replace destructive `removeAuthorizedClient` semantics before attaching failure handling.
- On `invalid_grant`/revocation, mark `REAUTH_REQUIRED`, preserve indexed data, stop provider calls, and render a reconnect action.
- Test that multiple Gmail client builds with a valid token make zero refresh calls and that one expired token produces one persisted refresh.

## First-Run/Progress UI Behavior

Add a dedicated Sync/Activity workspace route, controller, model, template, and rail link. Keep it separate from Analytics.

Show facts:

- account/provider;
- lifecycle/current lane and phase;
- indexed rows;
- provider total and remaining only when the provider declares the total reliable;
- initial batch ready/not ready;
- backfill running/completed/limited-by-maintainer-ceiling;
- last successful Gmail history check;
- "last checked X ago" as freshness age;
- full rescan required/running/completed;
- recoverable failure or reconnect requirement.
- attachment state as Present/None/Checking only where displayed; never infer None from absent METADATA MIME parts.

ETA rules:

- Only show ETA while work is active, a reliable denominator exists, and enough completed samples exist to calculate a stable positive rate.
- Label it "Estimated" and show a range or rounded duration.
- Suppress ETA when the denominator is unknown, rate is unstable/stalled, a retry wait is active, or a rescan changes the work total.
- Never turn Gmail `resultSizeEstimate` into an exact total. Prefer `users.getProfile.messagesTotal` for Gmail's mailbox total and record when it was observed.

Browser warning:

- With server-side executors and durable state, no ingestion phase should require the browser window after OAuth completion.
- Therefore do not show a "do not close" warning. Show "Sync continues in the background; you can close this page" if guidance is needed.
- Keep the model field explicit (`browserRequired=false`) so a future genuinely browser-owned flow cannot accidentally reuse misleading copy.

First-run navigation:

- A new account should land on the activity page while the first batch is pending.
- Once `initial_batch_completed_at` is set, render populated sender/category data or a clear "Open cleanup" action immediately; optional htmx navigation can follow.
- Returning accounts with indexed data should continue to the normal workspace and run freshness in the background.
- Do not make the OAuth callback wait for the full first batch.

Unread labels:

- Introduce one query/service source of truth returning both:
  - Inbox unread: `unread=true AND 'INBOX'=ANY(labels)`.
  - All-Mail unread: `unread=true`, with Gmail messages represented once regardless of how many labels they have.
- Name fields and UI copy explicitly (`inboxUnreadCount`, `allMailUnreadCount`).
- Sender rows using all indexed mail should say "All Mail unread"; the Unread Only page remains "Inbox unread".
- Do not sum label-level unread counts; one `message_metadata` row is the counting unit.

## Testing Strategy Emphasizing Journey Consolidation

Do not create ten requirement-named test classes.

### Consolidated journey work

Add one focused `MailboxFreshnessJourneyTest` (or extend/rename the current Phase 9 sync coverage) with a few readable journeys:

- `newGmailMessageAppearsWhileHistoricalBackfillIsStillRunning`
- `newUserSeesFirstBatchBeforeFullMailboxBackfillCompletes`
- `expiredGmailHistoryTriggersBoundedRepairAndProgressiveRescan`
- `gmailMetadataFlowsFromProviderThroughStorageToVisibleWorkspace`

Extend existing journeys:

- `SenderTriageJourneyTest.userConnectsGmailAndSeesTheirNoisiestSenders` for first-batch render.
- `UnreadReviewJourneyTest.userReviewsUnreadAndClearsTheQueue` for explicit Inbox-vs-All-Mail counts.
- `AnalyticsJourneyTest.userOpensAnalyticsAndSeesHonestCounts` for full-message size display.

Consolidate useful `Phase9MetadataIndexTest` cases into the new journey/contract tests and remove redundant micro-tests when coverage is equivalent.

### Provider contract tests

Use real Google API model objects in a test provider fixture and call
`GmailMailService.toMailMessage`, then pass the result through the real sync commit, PostgreSQL, controller, and Thymeleaf render. This proves the exact extraction path without a live network dependency.

The fixture must reflect the official METADATA shape (headers but no MIME child-part tree). A separate attachment-search fixture should feed the attachment membership index. The test should fail if missing MIME parts are mapped to `ABSENT` rather than `UNKNOWN`.

Separately verify request construction:

- `format=METADATA`
- exact header whitelist
- no raw/body/attachment-content request
- no persisted Gmail snippet/message-text excerpt
- `includeSpamTrash=true` on complete backfill
- no history label/type filter

A live Gmail mailbox is still required for release UAT because CI cannot safely depend on a personal mailbox. Record:

- sent test message ID/time;
- sync cycle start/end;
- indexed row facts;
- rendered Inbox/All-Mail unread, attachment presence, and size;
- observed freshness latency;
- backfill still active during the fresh ingestion.

### Failure and concurrency coverage

Test:

- duplicate history delivery;
- same ID across addition and label events;
- metadata-get 404 and permanent deletion;
- 429/5xx retry without cursor advance;
- 401 -> one managed refresh -> success;
- revoked refresh -> `REAUTH_REQUIRED`, indexed data retained;
- crash before commit and replay;
- crash after commit (cursor and rows both durable);
- concurrent history/backfill writes where the higher provider revision wins;
- stale history 404 recovery and guarded generation sweep;
- two application instances competing for the same lane lease;
- freshness worker runs while a backfill lease is held.

### Large-mailbox performance

Reuse `SyntheticMailboxGenerator` at 10,000 rows for normal CI and add an opt-in 100,000-row profile. Measure:

- atomic upsert batch throughput;
- Inbox/All-Mail unread count queries;
- activity page query count/latency;
- sender/category first render after a 200-row bootstrap;
- generation sweep query plan;
- backfill work-unit memory (never accumulate the full mailbox in Java).

Use `EXPLAIN (ANALYZE, BUFFERS)` evidence for new count/sweep queries. Avoid brittle sub-second CI assertions; set generous regression budgets and retain measured output.

## Risks and One-Way/Costly Decisions

1. **Cursor installation without reconciliation causes permanent gaps.** Replacing an expired history ID with the latest profile ID is cheap but loses unknown changes.
2. **Concurrent JPA read-then-save is insufficient.** Separate lanes require revision-aware PostgreSQL upserts; this is a foundational persistence decision.
3. **Gmail history IDs exceed safe signed assumptions.** Do not choose Java `long` casually. Migrating a populated revision column later is costly.
4. **Full-rescan deletion is dangerous.** A generation sweep without a post-rescan-update guard can delete newly ingested mail.
5. **Page tokens are traversal cursors, not freshness cursors.** They should never be reused as History API state.
6. **Backfill filters define "entire mailbox."** Explicitly choose `includeSpamTrash=true`; otherwise provider total, remaining, and completion can disagree.
7. **The current OAuth removal method is data destructive.** Wiring default refresh-failure behavior before fixing it can erase the user's entire local dataset.
8. **Background OAuth principal identity must remain provider account ID.** Using email instead will fail to load the persisted authorized client.
9. **A single executor or API semaphore can reintroduce starvation.** Separate cursors alone do not satisfy FRESH-01/FRESH-02.
10. **ETA can become a trust bug.** Unknown/estimated totals and retry stalls require suppression, not invented precision.
11. **`sync_progress` can grow without bound.** Five-minute no-op runs need bounded retention or summary updates.
12. **Outlook abstraction can be silently broken.** Gmail history concepts must be a declared capability; Microsoft remains explicitly unavailable until Graph delta sync exists.
13. **First-batch and complete-backfill filters differ intentionally.** This creates duplicate fetches but keeps first-run useful and full-mailbox completion honest.
14. **Changing existing `MailMessage` record shape has broad constructor impact.** Append provider revision and update all positional test constructors together.
15. **Live Gmail UAT is irreducible.** Local fixtures prove deterministic behavior, but provider retention, quota, and real attachment metadata need one controlled live gate.
16. **FRESH-10 contains an API-assumption trap.** Gmail does not document MIME attachment structure in METADATA. Treating the current boolean as proven would ship false negatives; widening to FULL would violate locked privacy.
17. **`metadataSnippet` violates the locked boundary.** Gmail defines snippet as message text. It should be cleared/stopped, not carried into the new sync path.

## Validation Architecture

The eventual `19-VALIDATION.md` should be generated from per-work-unit evidence, not only a final full-suite run.

### State schema and migration

Automated command:

```bash
mvn -Dtest='*PostgresMigrationTest,*MailboxSyncStateTest' test
```

Evidence:

- Flyway applies V15 from `src/main/resources/db/migration`.
- Hibernate validation passes.
- account deletion cascades sync state/activity.
- unique account state and metadata idempotency constraints exist.
- provider revision and generation sweep indexes are present.

### Gmail history/provider contract

Automated command:

```bash
mvn -Dtest='GmailSynchronizationContractTest' test
```

Evidence:

- profile/history/backfill request parameters are exact;
- all four specific history event types are handled;
- METADATA whitelist/body guards remain;
- labels, unread, size, and provider revision are extracted from the Gmail message path;
- absent METADATA MIME parts produce unknown attachment state, while Gmail search membership produces present/absent with provenance;
- Gmail snippet/message text is neither persisted nor rendered;
- stale history 404 is classified distinctly from transient failures.

### Atomic persistence, retry, and concurrency

Automated command:

```bash
mvn -Dtest='MailboxSyncPersistenceTest,MailboxFreshnessConcurrencyTest' test
```

Evidence:

- replay is idempotent;
- higher Gmail revision wins concurrent writes;
- deletion is conditional/idempotent;
- cursor and metadata commit atomically;
- lane leases exclude duplicate lane workers but permit freshness beside backfill;
- generation sweep preserves post-rescan freshness writes.

### Managed OAuth refresh

Automated command:

```bash
mvn -Dtest='OAuthAuthorizedClientRefreshTest,Phase3PersistenceTest' test
```

Evidence:

- valid token builds make no refresh request;
- one expired token causes one refresh and persisted credentials;
- null timestamps are not fabricated as expired;
- omitted replacement refresh token preserves the old encrypted token;
- invalid grant marks reauthorization and does not delete account/metadata.

### First-run, activity, unread, and metadata journeys

Automated command:

```bash
mvn -Dtest='MailboxFreshnessJourneyTest,SenderTriageJourneyTest,UnreadReviewJourneyTest,AnalyticsJourneyTest' test
```

Evidence:

- fresh history message appears while backfill remains active;
- approximately 200-message bootstrap renders populated cleanup data;
- activity page shows factual phase/count/freshness/rescan state;
- ETA and remaining are hidden when unknowable;
- browser warning is absent when work is server-side;
- Inbox unread and All-Mail unread are distinct and count messages once;
- Gmail METADATA unread/size and search-derived attachment state reach PostgreSQL and visible UI.

### Large-mailbox performance

Automated command:

```bash
mvn -Dtest='MailboxSyncPerformanceTest,Phase15UnsubscribePerformanceTest,Phase13PerformanceTest' test
```

Evidence:

- at least 10,000 rows are used;
- work-unit memory is bounded;
- count/activity/sweep query plans use expected indexes;
- fresh-lane work completes while backfill is queued/running;
- no full mailbox list is accumulated in memory.

Optional 100,000-row local profile:

```bash
TIDYMAIL_PERF_ROWS=100000 mvn -Dtest='MailboxSyncPerformanceTest' test
```

### Repository-wide gates

```bash
mvn test
rg -n 'format\\(\"(FULL|RAW)\"\\)|setFormat\\(\"(FULL|RAW)\"\\)' src/main src/test
rg -n 'getSnippet\\(\\)|setMetadataSnippet' src/main/java/com/tidymail
rg -n 'SYNC_MAX_MESSAGES_PER_ACCOUNT=50000|asks the user to archive some mail' .env.example src/main
rg -n 'removeAuthorizedClient|userAccountRepository\\.delete' src/main/java/com/tidymail/security/oauth2
git diff --check
```

Expected:

- full suite passes;
- no production FULL/RAW message retrieval;
- no Gmail snippet persistence;
- no 50,000-message default or user-facing archive demand;
- OAuth removal no longer cascades user data;
- patch whitespace is clean.

### Live Gmail UAT

Manual evidence required for FRESH-01/FRESH-03/FRESH-05/FRESH-10:

1. Connect a controlled Gmail account and record bootstrap start/first populated render.
2. While backfill is visibly active, send a uniquely identified message with an attachment.
3. Record receipt time and first TidyMail appearance within one configured sync cycle.
4. Verify database labels/unread/size/attachment fields and the visible Inbox/All-Mail labels.
5. Force or safely simulate an expired history cursor and record bounded repair plus progressive rescan state.
6. Let an access token expire and record one persisted managed refresh without re-login.

Do not include tokens, message bodies, attachment contents, or personal identifiers in validation artifacts.

## Requirement Mapping

| Requirement | Research conclusion and primary evidence |
|---|---|
| FRESH-01 | High-priority History API lane, separate executor/lease/API priority, immediate post-login trigger; journey plus live test message during active backfill. |
| FRESH-02 | Independent durable history and backfill cursors; one bounded backfill work unit; no account-wide lock; concurrency journey proves overlap. |
| FRESH-03 | Durable recent `historyId`, specific event arrays, atomic cursor commit, 404 -> bounded recent repair + progressive generation rescan. |
| FRESH-04 | Activity state comes from provider-check timestamps and explicit rescan state; freshness is "last checked X ago", never max row write time or a fabricated up-to-date claim. |
| FRESH-05 | `AuthorizedClientServiceOAuth2AuthorizedClientManager` + refresh provider + JPA persistence; null timestamps remain unknown; destructive removal replaced by reauth state. |
| FRESH-06 | Capture profile anchor, ingest about 200 recent messages, commit, mark initial batch ready, render existing deterministic sender/category queries before full backfill. |
| FRESH-07 | Dedicated Sync/Activity route with facts, conditional ETA, reliable total/remaining, rescan and freshness state. Browser warning is absent because work is server-side. |
| FRESH-08 | Backfill runs to token exhaustion with `includeSpamTrash=true`; optional message-count ceiling defaults unlimited and is maintainer-only. |
| FRESH-09 | One service/query exposes Inbox unread (`UNREAD` + `INBOX`) and All-Mail unread (`UNREAD`) from unique message rows; every UI occurrence is explicitly labeled. |
| FRESH-10 | Preserve Gmail METADATA for unread/size and add a separate metadata-only Gmail `has:attachment` membership path with tri-state/provenance. The current MIME-part assumption is not supported by METADATA docs. Provider-model/search-to-Postgres-to-MockMvc journeys plus controlled live UAT close the evidence. |

## Planning Guidance

A good Phase 19 plan should separate work into coherent vertical slices rather than requirement-by-requirement micro-plans:

1. durable sync state + revision-aware persistence;
2. managed OAuth credential path;
3. Gmail profile/history contract and failure classification;
4. first-batch/backfill/freshness orchestration;
5. activity/unread UI read models;
6. consolidated journeys, performance evidence, and live UAT.

Each slice should leave production migrations and the Phase 18 journey suite green. The riskiest ordering dependency is OAuth failure semantics: fix nondestructive reauthorization before enabling the manager's automatic failure path. The next is persistence: land atomic revision-aware upserts before allowing freshness and backfill to run concurrently.
