# Phase 19 Pattern Map

Source-grounded mapping only. This document describes current code patterns and likely
Phase 19 touchpoints named or implied by `19-RESEARCH.md`, `19-UI-SPEC.md`, and
`19-VALIDATION.md`. It does not define an implementation plan.

## Status Legend

- **Existing**: symbol and file exist in the current tree.
- **Likely modified**: existing file is directly named by the Phase 19 artifacts.
- **Likely created**: artifact names a responsibility or test that has no current file.
- **Name not locked**: research gives an example or responsibility, not a final Java symbol.

## Current End-to-End Data Flow

```text
OAuth callback
  -> OAuthLoginSuccessListener
  -> JpaOAuth2AuthorizedClientService.save(...)
  -> UserAccount token columns
  -> IncrementalSyncJob.requestSync(accountId)
  -> applicationTaskExecutor
  -> one process-local account semaphore
  -> MessageMetadataSyncService.synchronize(...)
  -> MailProvider.listMessages(...)
  -> GmailMailService messages.list + batches of messages.get(METADATA)
  -> MessageMetadataPageService.upsertPage(...) [REQUIRES_NEW]
  -> MessageMetadataMapper.apply(...)
  -> MessageMetadataRepository.saveAll(...)
  -> message_metadata
  -> DecisionSurfaceQueryRepository / analytics repositories
  -> WorkspaceModelService and page services
  -> Thymeleaf full pages/fragments
```

The current flow has one page token, one job type, one executor, and one account-wide
semaphore. There is no profile/history/change-feed flow, independent lane cursor, durable
lease, revision-aware native upsert, rescan generation, activity route, or polling support
inside `htmx-lite.js`.

## Persistence Patterns

### Flyway migrations

**Closest analogs**

- `src/main/resources/db/migration/V6__metadata_index_and_action_scopes.sql`
- `src/main/resources/db/migration/V12__shared_draft_foundations.sql`
- `src/main/resources/db/migration/V14__sender_type_overrides_and_cleanup_indexes.sql`

Preserve these concrete conventions:

```sql
account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE
```

```sql
CONSTRAINT uq_message_metadata_account_provider_message
    UNIQUE (account_id, provider_message_id)
```

```sql
CONSTRAINT sender_type_overrides_type_check CHECK (
    sender_type IN ('LIST', 'AUTOMATED', 'TRANSACTIONAL', 'PERSONAL')
)
```

```sql
CREATE INDEX idx_message_metadata_account_received
    ON message_metadata (account_id, received_at DESC, id DESC);
```

The migration style is append-only, PostgreSQL-native DDL with named constraints,
account-first indexes, explicit enum-like `CHECK` constraints, and cascades from
`user_accounts`.

**Likely created**

`src/main/resources/db/migration/V15__*.sql` does not exist. Research expects the next
migration to carry durable mailbox state, message revision/generation/attachment-state
changes, OAuth status, and any activity/lease schema. The exact migration filename and
table split are not locked.

### Entities

**Closest analog: `MessageMetadata`**

```java
@Entity
@Table(
    name = "message_metadata",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_message_metadata_account_provider_message",
        columnNames = {"account_id", "provider_message_id"})
)
public class MessageMetadata
```

PostgreSQL arrays are mapped with:

```java
@JdbcTypeCode(SqlTypes.ARRAY)
@Column(name = "labels", nullable = false)
private List<String> labels = new ArrayList<>();
```

Lifecycle defaults are set in `@PrePersist`, while schema defaults also exist in Flyway.
Current stale defaults that Phase 19 must not preserve semantically:

```java
private Boolean hasAttachments = false;
...
if (hasAttachments == null) hasAttachments = false;
```

`metadataSnippet` is also current production state:

```java
@Column(name = "metadata_snippet", length = 512)
private String metadataSnippet;
```

Research identifies this as body-derived Gmail text that must not continue through the
new path.

**Closest analog: `SyncProgress`**

```java
@Entity
@Table(name = "sync_progress")
public class SyncProgress
```

It is an identity-keyed run row with string status/job type, bounded error text, counters,
and one checkpoint. It is not a one-row-per-account state entity.

**Closest analog: `UserAccount`**

`UserAccount` keeps provider identity and encrypted token blobs. Provider identity is:

```java
@Column(name = "provider_account_id", nullable = false)
private String providerAccountId;
```

Token timestamps are nullable `Instant`s. Token blobs use:

```java
@Lob
@JdbcTypeCode(SqlTypes.LONGVARBINARY)
@Column(name = "encrypted_access_token", nullable = false)
private byte[] encryptedAccessToken;
```

**Likely created**

An entity for one durable mailbox state row per account does not exist.
`MailboxSyncState` is a validation-artifact name, not a current symbol. Follow the
existing entity conventions: explicit `@Column(name=...)`, Java `Instant`, named table,
and Flyway-owned schema validated by Hibernate.

### Repositories and native SQL

`MessageMetadataRepository` is currently JPA-only:

```java
public interface MessageMetadataRepository
        extends JpaRepository<MessageMetadata, Long> {
    List<MessageMetadata> findByAccountIdAndProviderMessageIdIn(
        Long accountId, Collection<String> providerMessageIds);
}
```

No production migration or Java repository currently contains PostgreSQL
`INSERT ... ON CONFLICT`. The closest native-SQL pattern is the repository classes using
`NamedParameterJdbcTemplate`, for example `AnalyticsMailboxRepository`:

```java
@Repository
public class AnalyticsMailboxRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsMailboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
}
```

Parameters use `MapSqlParameterSource`; result mapping uses lambdas or small record
mappers. SQL is expressed as Java text blocks and is always account-scoped.

Therefore a revision-aware native upsert repository is a **new repository pattern for
writes**, but its JDBC construction, parameter binding, text blocks, account validation,
and record mapping have strong existing analogs.

**Likely created**

- Durable mailbox-state repository: no current file; closest state lookup analog is
  `SyncProgressRepository`.
- Native metadata write repository: no current file; closest SQL infrastructure analogs
  are `AnalyticsMailboxRepository`, `DecisionSurfaceQueryRepository`, and
  `UnsubscribeCandidateRepository`.
- Lease compare-and-set methods: no current production symbol.

## Transaction and Commit Patterns

### Existing page transaction

`MessageMetadataPageService` establishes the exact short-transaction signature:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public int upsertPage(
        Long accountId, List<MailMessage> messages, Instant syncedAt)
```

It deduplicates in encounter order:

```java
Map<String, MailMessage> uniqueMessages = new LinkedHashMap<>();
uniqueMessages.put(message.id(), message);
```

It then performs one collection read and one `saveAll`. This batching shape is reusable,
but the read-then-save write strategy is not concurrency-safe for separate lanes.

### Existing network-outside-transaction orchestration

`ExactScopeExecutor` is the closest explicit transaction-orchestration analog:

```java
if (TransactionSynchronizationManager.isActualTransactionActive()) {
    throw new IllegalStateException("Executor entry must not have a transaction");
}
```

It performs short reservation/dispatch/completion service calls around provider work.
`UnsubscribeOutcomeService.accepted(...)` and `.failed(...)` then use
`REQUIRES_NEW` to durably record terminal outcomes after network activity.

Pattern to preserve:

```text
non-transactional orchestrator
  -> provider/network call
  -> short REQUIRES_NEW persistence service
```

The current sync code only partially follows this: Gmail calls occur outside
`MessageMetadataPageService`, but metadata and cursor progress are committed in separate
transactions. Phase 19's atomic cursor+metadata commit service does not exist yet.

## Scheduling, Executors, and Coalescing

### Scheduling configuration

`ScheduleConfig` is the scheduling gate:

```java
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ScheduleConfig
```

Journey tests disable jobs with:

```properties
spring.task.scheduling.enabled=false
```

`InboxCleanerApplication` has `@EnableAsync`, but there is no explicit production
freshness/backfill executor configuration. `IncrementalSyncJob` injects Boot's:

```java
@Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor
```

### Existing request coalescing

`IncrementalSyncJob.requestSync(Long)` preserves one queued request per account/job:

```java
if (latest != null && "QUEUED".equals(latest.getStatus())) {
    return latest;
}
```

Submission failure is converted from a stranded `QUEUED` row to `FAILED`, which is a
useful durability pattern to preserve.

### Existing exclusion boundary to replace

```java
private final ConcurrentHashMap<Long, Semaphore> perAccountSemaphores =
    new ConcurrentHashMap<>();
...
new Semaphore(1)
```

This is process-local, account-wide, and excludes freshness from backfill. The associated
`requestMonitors` map is also process-local and never cleaned up. These structures are
stale patterns, not durable lease analogs.

### Test executor analog

`Phase9MetadataIndexTest.ControllableTaskExecutor` is the deterministic queue fixture:

```java
static final class ControllableTaskExecutor implements TaskExecutor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean rejectNext;
}
```

It supports `runAll()`, `runNextAsync()`, and `rejectNext()`. A Phase 19 executor test can
reuse this behavior, but separate executor beans/lane queues do not exist yet.

## Provider and Gmail Patterns

### Provider capability declaration

`MailProvider` keeps defaults provider-neutral:

```java
default ProviderCapabilities capabilities() {
    return ProviderCapabilities.unknown();
}
```

`ProviderCapabilities` uses immutable records and explicit reliability:

```java
public record ProviderCapabilities(
    DenominatorReliability mailboxTotal,
    DenominatorReliability arbitraryFilteredTotal)
```

```java
public enum DenominatorReliability {
    RELIABLE, ESTIMATE_ONLY, UNAVAILABLE
}
```

This is the closest analog for an incremental-sync capability declaration. No history,
delta, full-scan, or current-metadata capability exists.

### Provider-neutral message/page records

`PageResult<T>` is a small immutable transport record with a static factory:

```java
public record PageResult<T>(
    List<T> items, String nextPageToken, int requestedMax, boolean hasMore) {
    public static <T> PageResult<T> of(...) { ... }
}
```

`MailMessage` is a positional record with a compatibility constructor:

```java
public record MailMessage(
    String id,
    String threadId,
    String provider,
    Instant receivedAt,
    Map<String, List<String>> headers,
    Set<String> labelIds,
    Long sizeEstimate,
    boolean hasAttachments,
    String metadataSnippet)
```

Appending provider revision or changing attachment state affects every positional
constructor in production and tests. Gmail `historyId` is not currently represented.

The privacy guard is explicit:

```java
public String body() {
    throw new UnsupportedOperationException(
        "METADATA-only privacy contract ...");
}
```

### Gmail list and metadata batching

`GmailMailService.listMessages(...)` currently:

1. Calls `gmail.users().messages().list("me").setMaxResults(...)`.
2. Applies a page token when present.
3. Splits message IDs into batches of 10.
4. Queues `messages.get` with exact METADATA restrictions.
5. Reorders results to the original `messages.list` order.

Request construction to preserve:

```java
gmail.users().messages()
    .get(USER_ID_ME, slim.getId())
    .setFormat("METADATA")
    .setMetadataHeaders(new ArrayList<>(METADATA_HEADERS))
```

The whitelist is a static exact 24-header `Set<String>`. The service fails the whole
batch if any callback reports a failure.

Current omissions:

- `messages.list` does not call `setIncludeSpamTrash(true)`.
- No `users.getProfile`.
- No `users.history.list`.
- No history event models or error classification.
- No attachment-search membership request.

Current stale mapping:

```java
boolean hasAttachments = payload != null && hasAttachmentMetadata(payload);
return new MailMessage(
    ..., sizeEstimate, hasAttachments, gmailMessage.getSnippet());
```

METADATA does not guarantee MIME children, so absence cannot mean no attachment.
`getSnippet()` is message text and must not be preserved.

`toMailMessage` already rejects raw and MIME body data recursively. That fail-fast guard
and exact whitelist are the closest provider contract patterns.

### Gmail request governor

`GmailRateLimiter` wraps whole operations with one non-fair process-wide semaphore:

```java
this.semaphore = new Semaphore(concurrency);
...
return supplier.get();
```

`GmailSyncConfigProperties` uses `@Component + @Value`, getters/setters for tests, and
`@PostConstruct` validation. Preserve startup validation and explicit bounds. Do not
preserve a single undifferentiated semaphore as correctness or freshness priority.

### Planned provider artifacts that do not exist

The following names are examples from research/validation, not current symbols:

- `MailboxSnapshot`
- `BackfillRequest`
- `ChangeRequest`
- `ChangePage`
- `SyncCapabilities`
- `GmailSynchronizationContractTest`

There is also no Outlook production provider bean. `IncrementalSyncJob` currently maps
Microsoft to `"outlookMailService"` even though only tests supply that bean.

## OAuth Authorized-Client Patterns

### Persistence service

`JpaOAuth2AuthorizedClientService` keys lookup by registration ID and principal name:

```java
findByProviderAndProviderAccountId(clientRegistrationId, principalName)
```

The principal name must remain the provider account ID. Existing save behavior correctly
retains the old refresh token when a refreshed client omits a replacement:

```java
if (hasRefreshToken) {
    account.setEncryptedRefreshToken(encryptedRefreshToken);
}
```

Stale timestamp fabrication:

```java
Instant issuedAt = account.getTokenIssuedAt() != null
    ? account.getTokenIssuedAt() : Instant.now();
Instant expiresAt = account.getAccessExpiresAt() != null
    ? account.getAccessExpiresAt() : issuedAt;
```

Stale destructive removal:

```java
userAccountRepository.delete(accountOpt.get());
```

Because database relationships cascade from `user_accounts`, this is full local-data
deletion, not credential invalidation.

### Current client construction

`GmailClientFactory.buildFor(...)` decrypts only the refresh token, creates a fresh
Google `UserCredentials`, and calls:

```java
userCredentials.refreshIfExpired();
```

The persisted access token is ignored and refreshed credentials are not saved.

### Security configuration analog

`SecurityConfig` already wires `OAuth2AuthorizedClientService` into `oauth2Login` and
builds registrations programmatically. Google registration ID is `"google"` and current
scopes are:

```java
.scope("openid", "email", "profile",
       "https://www.googleapis.com/auth/gmail.modify")
```

Offline access is added through authorization parameters:

```java
params.put("access_type", "offline");
params.put("prompt", "consent");
```

`AuthorizedClientServiceOAuth2AuthorizedClientManager`, refresh-token provider
configuration, single-flight refresh, and nondestructive failure handling do not exist.

### Login callback flow

`OAuthLoginSuccessListener` saves the callback client, updates `lastLoginAt`, and queues
sync. It does not retain a pre-save `new account` fact.

`AccountLoginSuccessHandler` always uses:

```java
setDefaultTargetUrl("/dashboard");
setAlwaysUseDefaultTargetUrl(true);
```

No first-run `/activity` routing branch exists.

## Workspace Web Patterns

### Route/controller/model

`AnalyticsController` is the closest full-page controller analog:

```java
@GetMapping("/analytics")
public String analytics(
        @RequestParam MultiValueMap<String, String> parameters,
        OAuth2AuthenticationToken authentication,
        Model model)
```

It resolves account scope in a query service, builds `WorkspacePageModel`, adds one page
model attribute, and returns a Thymeleaf template name.

`WorkspaceRoute` is the route registry. Each enum value owns:

- stable value and path;
- title/eyebrow/purpose;
- rail title;
- capability copy;
- `nextRoute()` behavior.

Adding `ACTIVITY` affects the exhaustive `switch` in `nextRoute()`.

`MetadataSyncController` has a fixed return allowlist:

```java
Set.of("/senders", "/unread", "/unsubscribe", "/review", "/analytics")
```

`/activity` is not currently accepted.

`WorkspaceModelService` is account-scope and chrome composition, but its readiness is
derived from the latest generic `SyncProgress`. It treats a recent
`lastSuccessfulAt` as ready and computes Inbox unread through
`DecisionSurfaceQueryRepository.countUnread(...)`.

**Likely created, names not locked**

- Activity page controller for `GET /activity` and fragment `GET /activity/status`.
- Activity read-model service.
- Activity page/status/lane/event DTO records.

No `ActivityController`, activity model, or `/activity` mapping exists.

### Full-page template

`analytics.html` is the closest sibling page:

```html
<a class="skip-link" href="#workspace-main">...</a>
<div th:replace="~{fragments/workspace-nav :: nav(${page.workspace})}"></div>
<div class="workspace-shell">
  <div th:replace="~{fragments/workspace-rail :: rail(${page.workspace})}"></div>
  <main id="workspace-main">
```

Header help uses the accepted focus/hover pattern:

```html
<span class="page-help" tabindex="0"
      aria-label="..."
      aria-describedby="analytics-page-help">
  <sup class="page-help-icon" aria-hidden="true">?</sup>
  <span id="analytics-page-help"
        class="page-help-tooltip"
        role="tooltip">...</span>
</span>
```

The likely `activity.html` file does not exist. Preserve shell, skip link, rail, page
header, stylesheet loading, and server-rendered model ownership.

### Rail/account switching

`workspace-rail.html` renders account forms against:

```html
th:action="${workspace.route.path}"
```

This automatically preserves the current route when `WorkspaceRoute.ACTIVITY` exists.
The current responsive CSS assumes five links:

```css
grid-template-columns: repeat(5, minmax(0, 1fr));
```

and on mobile:

```css
.rail-navigation a:last-child { grid-column: 1 / -1; }
```

Both are stale for six normal cells.

### Existing state fragments

`workspace-state.html` is the closest server-owned status fragment. It preserves the
target root in all branches and conditionally emits htmx attributes on the root.
However, its facts are generic scanned/pages/indexed counts, and its READY copy relies on
the old sync semantics.

`sync-progress-frag.html` is legacy badge markup with pill/badge classes and generic
per-run counts. It is not an activity-surface analog to copy visually.

**Likely created**

- `src/main/resources/templates/activity.html`
- `src/main/resources/templates/fragments/activity-status.html` or equivalent

Exact fragment filename is not locked by the artifacts. The root ID is locked by the UI
spec as `activity-status`.

### CSS

`workspace.css` owns all tokens and responsive behavior. Existing relevant patterns:

```css
.page-help:hover .page-help-tooltip,
.page-help:focus .page-help-tooltip,
.page-help:focus-within .page-help-tooltip { ... }
```

```css
@media (max-width: 980px) { ... }
@media (max-width: 720px) { ... }
@media (max-width: 375px) { ... }
@media (prefers-reduced-motion: reduce) { ... }
```

The activity surface should consume existing `--tm-*`, `--space-*`, focus, and semantic
color variables. Existing `.state-summary` uses card-like bordered cells and
`font-weight: 700`; those details conflict with the activity UI contract and are not the
visual analog to preserve.

### htmx-lite behavior

Current `htmx-lite.js` supports click and submit requests only. It does not scan
`hx-trigger`, so existing `every 15s` markup is inert.

Current swap behavior:

```javascript
if (swapMode === "outerHTML") {
    target.outerHTML = html;
} else {
    target.innerHTML = html;
}
```

Current request behavior preserves:

- `HX-Request: true`;
- same-origin credentials;
- optional CSRF header;
- 8-second `AbortController` timeout;
- `htmx:beforeRequest`, `responseError`, `afterSwap`, and `afterRequest`;
- local date formatting after a successful swap.

Stale limitations:

- `hx-swap="outerHTML settle:120ms"` is treated as unknown and falls through to
  `innerHTML`.
- No polling timer, overlap guard, disconnected-element cleanup, settle delay, focus
  hold/restore, failure counter, or backoff.
- `afterRequest` is not emitted on failed HTTP/network requests.

`htmx-lite.js` is therefore a likely modified file, not a reusable polling
implementation.

## Unread, Size, and Attachment Read Models

### Inbox unread

`DecisionSurfaceSql.unread(...)` applies both:

```sql
m.unread = true
AND 'INBOX'=ANY(m.labels)
```

`WorkspaceModelService` and `/unread` already expose the name `inboxUnreadCount`.
`unread.html` visibly labels the total `Inbox unread`. This is the existing source of
truth to preserve.

### All Mail unread

Sender aggregation currently uses:

```sql
SUM(CASE WHEN unread THEN 1 ELSE 0 END) AS unread_count
```

without an Inbox predicate. This is already one count per `message_metadata` row, hence
All Mail semantics. Existing sender templates still need explicit label alignment; no
shared DTO currently returns Inbox and All Mail counts together.

### Size

`AnalyticsMailboxRepository.sizeMetrics(...)` scopes unread size to Inbox:

```sql
SUM(CASE WHEN 'INBOX'=ANY(labels) AND unread=TRUE
         THEN COALESCE(size_estimate, 0) ELSE 0 END)
```

It returns `Optional.empty()` when relevant size values are missing. Preserve this
"unknown is not zero" approach.

### Attachment state and modal flow

Current flow:

```text
ReviewActionQueryRepository / UnsubscribeCandidateRepository
  -> EmailStatsModal.EmailRow(sender, subject, receivedAt, unread)
  -> EmailStatsModalService or UnsubscribeStatsService
  -> fragments/email-stats-modal-frag.html
```

Likely modified files:

- `MessageMetadata`
- `DecisionSurfaceSql`
- `DecisionSurfaceQueryRepository`
- `UnreadDecisionQueryService`
- `EmailStatsModal`
- `ReviewActionQueryRepository`
- `UnsubscribeCandidateRepository` and/or `UnsubscribeStatsService`
- `email-stats-modal-frag.html`

The current modal has four columns and a four-field positional `EmailRow`. Every
constructor and mapper must stay aligned if attachment state is appended. Current empty
row uses `colspan="4"`, another positional template detail.

Do not preserve boolean `has_attachments` as evidence of absence. The current unread
category predicate:

```java
case "attachments" -> "has_attachments = true";
```

is a useful positive-membership shape only after it maps exclusively to confirmed
`PRESENT`.

## Configuration Touchpoints

Likely modified:

- `src/main/resources/application.properties`
- `.env.example`
- `GmailSyncConfigProperties` or additional sync properties class

Current contradictory defaults:

```properties
app.sync.initial-page-size=${SYNC_INITIAL_PAGE_SIZE:500}
app.sync.max-messages-per-account=${SYNC_MAX_MESSAGES_PER_ACCOUNT:50000}
```

`.env.example` describes the ceiling as a memory guard and tells users to archive mail.
That text is a stale pattern. Existing config classes validate bounds in `@PostConstruct`;
that validation style is the closest analog for new tunables.

## Test Foundation and Consolidation Map

### Phase 18 Postgres journey harness

`PostgresJourneyTest` is the required journey base:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class PostgresJourneyTest
```

It provides:

- one PostgreSQL 16 Testcontainer via `@ServiceConnection`;
- real Flyway/Hibernate validation and real repositories/controllers/templates;
- `MockMvc` and `JdbcTemplate`;
- mocked provider beans only;
- deterministic OAuth authentication helpers;
- CSRF helper;
- truncate/reset before each journey.

`src/test/resources/META-INF/spring.factories` registers the Phase 18 context customizer
and database cleanup listener. New Postgres journey tests should inherit the base instead
of rebuilding container lifecycle.

### Synthetic performance fixture

`SyntheticMailboxGenerator` locks these patterns:

```java
public static final long DEFAULT_SEED = 18_2026L;
public static final int DEFAULT_ROW_COUNT = 10_000;
private static final int BATCH_SIZE = 1_000;
```

It rejects fewer than 10,000 rows, uses a seeded `Random`, PostgreSQL arrays, one prepared
statement, and batched inserts. The generator currently writes `has_attachments` and
`metadata_snippet`; schema changes require its column list and bind order to change.

`SeedResult(long accountId, int rowCount, long seed)` is the existing fixture return
shape. An opt-in 100,000-row count can use `seed(rowCount)` without a new generator.

### Existing journeys to extend

`SenderTriageJourneyTest.userConnectsGmailAndSeesTheirNoisiestSenders`

- Real `/senders` route and Postgres rows.
- Verifies sender ordering and no provider interactions.
- Closest first-batch-to-cleanup rendering journey.

`UnreadReviewJourneyTest.userReviewsUnreadAndClearsTheQueue`

- Uses the 10,000-row generator.
- Exercises `/unread`, selection preview, staging, `/review`, cancel, and DB state.
- Closest Inbox-vs-All-Mail visible evidence journey.

`AnalyticsJourneyTest.userOpensAnalyticsAndSeesHonestCounts`

- Uses 10,000 rows, real `/analytics`, model assertions, and SQL median evidence.
- Closest size-display and large-mailbox route fixture.

These journey tests currently strip HTML with:

```java
html.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ")
```

This is brittle raw-HTML processing. Preserve semantic/model/DB assertions and real HTTP
routes; do not multiply substring assertions for activity fragment structure. Existing
MockMvc model access is a stronger pattern where a model object is available.

### `Phase9MetadataIndexTest` consolidation targets

Useful behavior to move into focused Phase 19 persistence/concurrency/journey tests:

- page replay preserves row identity and `firstSeenAt`;
- failed unit does not advance checkpoint;
- committed unit resumes from its checkpoint;
- rejected executor submission does not strand queued state;
- duplicate requests coalesce;
- provider calls do not overlap for the same old lane;
- endpoint requires authentication, ownership, and CSRF;
- privacy/schema/index/account-isolation checks;
- deterministic controllable executor fixture.

Stale assertions not to preserve as desired behavior:

- exactly 500 rows;
- exactly five provider pages;
- `MAX_PAGES = 5`;
- all account work has `maxActive == 1`;
- one `INCREMENTAL_SYNC` checkpoint represents freshness and backfill;
- `metadataSnippet` and boolean `hasAttachments` must exist;
- one JPA collection read plus one `saveAll` is the upsert contract.

### `Phase4GmailProviderTest` consolidation targets

Useful behavior to retain:

- exact 24-header whitelist;
- raw and nested body-data rejection;
- `MailMessage.body()` privacy failure;
- non-Google account rejection;
- Gmail config bounds;
- provider-model conversion helpers.

Stale assertions to replace:

- a valid Google account is expected to attempt network refresh during
  `GmailClientFactory.buildFor`;
- standalone semaphore concurrency is treated as the desired governor;
- MIME child metadata can prove no attachment;
- snippet persistence is acceptable.

The Phase 19 validation names `GmailSynchronizationContractTest`; that file does not
exist. It should absorb request-construction/history/attachment-search contracts while
`Phase4GmailProviderTest` remains the privacy baseline or is reduced after equivalent
coverage exists.

### Planned tests that do not exist

The following validation targets are all **likely created**, with names supplied by
`19-VALIDATION.md`:

- `MailboxSyncStateTest`
- `MailboxSyncPersistenceTest`
- `MailboxFreshnessConcurrencyTest`
- `OAuthAuthorizedClientRefreshTest`
- `GmailSynchronizationContractTest`
- `MailboxFreshnessJourneyTest`
- `MailboxActivityJourneyTest`
- `MailboxSyncPerformanceTest`

`*PostgresMigrationTest` is a wildcard convention, not a current Phase 19 class.

Closest analog mapping:

| Planned test | Closest current analog |
|---|---|
| `MailboxSyncStateTest` | `Phase9PostgresMigrationTest`, Phase 10-15 migration tests |
| `MailboxSyncPersistenceTest` | `Phase9MetadataIndexTest.IntegrationTests` |
| `MailboxFreshnessConcurrencyTest` | Phase 9 controllable executor and Phase 12 concurrent reservation tests |
| `OAuthAuthorizedClientRefreshTest` | `Phase3PersistenceTest`, `Phase4GmailProviderTest` |
| `GmailSynchronizationContractTest` | `Phase4GmailProviderTest` |
| `MailboxFreshnessJourneyTest` | `SenderTriageJourneyTest` plus Phase 9 sync integration |
| `MailboxActivityJourneyTest` | workspace web tests plus `PostgresJourneyTest` |
| `MailboxSyncPerformanceTest` | `Phase13PerformanceTest`, `Phase15UnsubscribePerformanceTest`, synthetic generator |

## Likely Production File Touchpoints

| File or responsibility | Status | Role and data flow | Closest analog/pattern to preserve |
|---|---|---|---|
| `db/migration/V15__*.sql` | Likely created, name not locked | Durable state, revision/generation, attachment/OAuth/activity schema | V6/V12/V14 named constraints, cascades, account-first indexes |
| `entity/MailboxSyncState.java` | Likely created, name not locked | One account's lane cursors/lifecycle/leases | `SyncProgress` field mapping; `UserAccount` one-account ownership |
| `entity/MessageMetadata.java` | Likely modified | Current message state and provider revision/generation/attachment provenance | Existing unique account/message identity and array mapping |
| `entity/SyncProgress.java` | Likely modified or narrowed | Activity/run facts, not authoritative cursor | Current bounded error/counter/timestamp fields |
| `entity/UserAccount.java` | Likely modified | Credential status without deleting indexed data | Existing encrypted token/timestamp/provider identity mapping |
| mailbox-state repository | Likely created, name not locked | Account/lane state lookup and lease CAS | `SyncProgressRepository`; native JDBC repositories for CAS |
| native metadata write repository | Likely created, name not locked | Revision-guarded upsert/delete/generation sweep | `NamedParameterJdbcTemplate` repository style |
| `MessageMetadataRepository.java` | Likely modified | Reads/counts and possibly compatibility methods | Account-scoped derived queries and freshness summary |
| `SyncProgressRepository.java` | Likely modified | Lane/activity lookup and bounded history | `findTop...OrderByIdDesc` naming |
| `MailMessage.java` | Likely modified | Carries provider revision and non-boolean attachment evidence | Immutable record plus compatibility constructor |
| sync capability/request/change records | Likely created, names not locked | Provider-neutral profile/backfill/change contracts | `ProviderCapabilities`, `PageResult` immutable records |
| `MailProvider.java` | Likely modified or left mutation-only | Existing generic provider boundary | Default unknown capability; do not force Gmail semantics on Outlook |
| `ProviderCapabilities.java` | Likely modified or sibling created | Explicit sync support/reliability | Enum-based reliability, immutable validation |
| `GmailMailService.java` | Likely modified | Profile/history/backfill/current metadata/search requests | `"me"`, exact METADATA whitelist, batch size 10, body guards |
| `GmailClientFactory.java` | Likely modified | Build Gmail client from managed persisted access token | Existing provider/config/timeouts; remove independent refresh lifecycle |
| `GmailRateLimiter.java` | Likely modified | API concurrency with freshness priority | Permit wrapper/finally release; current single semaphore is stale |
| `GmailSyncConfigProperties.java` | Likely modified | Validated sync tunables | `@Value`, setters for tests, `@PostConstruct` bounds |
| atomic sync commit service | Likely created, name not locked | Short `REQUIRES_NEW` rows+cursor+counts commit | `MessageMetadataPageService`, `UnsubscribeOutcomeService` |
| sync orchestrator(s) | Likely created or existing services split | Network outside transaction; bounded work units | `ExactScopeExecutor` transaction guard and short commit services |
| `MessageMetadataPageService.java` | Likely replaced/modified | Current page transaction | Dedup/bounded batch useful; JPA read-then-save stale |
| `MessageMetadataMapper.java` | Likely modified | Provider record to persistence values | Existing approved-header mapping/provider-gated facts |
| `MessageMetadataSyncService.java` | Likely replaced/modified | Current single pagination loop | Safe error handling only; `MAX_PAGES` and one cursor stale |
| `IncrementalSyncJob.java` | Likely replaced/modified | Poll/queue/dispatch lanes | Submission-failure state and coalescing intent; account semaphore stale |
| executor configuration | Likely created | Separate freshness/backfill pools | Boot `TaskExecutor` injection and Phase 9 controllable bean |
| `JpaOAuth2AuthorizedClientService.java` | Likely modified | Managed token load/save/failure status | Preserve refresh token on omitted replacement |
| `SecurityConfig.java` | Likely modified | Background authorized-client manager/provider beans | Existing registration repository and authorized-client service |
| `OAuthLoginSuccessListener.java` | Likely modified | Distinguish first run and trigger bootstrap/freshness | Existing save -> login timestamp -> async request flow |
| `AccountLoginSuccessHandler.java` | Likely modified | First-run destination | Existing session initialization before redirect |
| activity controller/model/service files | Likely created, names not locked | Server-owned activity page and fragment model | `AnalyticsController`, `WorkspaceModelService`, immutable DTO records |
| `WorkspaceRoute.java` | Likely modified | Register `/activity` and rail metadata | Existing enum registry; update exhaustive switch |
| `WorkspacePageModel.java` / `WorkspaceModelService.java` | Likely modified | Shared chrome/unread facts | Existing account-scope resolution; old readiness is stale |
| `MetadataSyncController.java` | Likely modified | `/activity` safe return and lane-aware retry/check | Existing ownership + CSRF-protected POST |
| `activity.html` | Likely created | Full workspace page | `analytics.html` shell/header/help |
| activity status fragment | Likely created, name not locked | Stable `#activity-status` replacement root | `workspace-state.html` conditional root attributes |
| `workspace-rail.html` | Likely modified | Sixth Manage link | Existing `aria-current` and route-preserving account form |
| `workspace.css` | Likely modified | Flat activity layout and six-link breakpoints | Existing tokens/help/focus/media/reduced-motion rules |
| `htmx-lite.js` | Likely modified | One-shot polling, settle parsing, focus/failure handling | Existing request/events/CSRF/timeout/swap entry points |
| unread/sender analytics SQL/services/templates | Likely modified | Explicit Inbox vs All Mail semantics | Existing Inbox predicate and one-row-per-message sender aggregate |
| `EmailStatsModal.java` and data repositories/services | Likely modified | Carry tri-state attachment to rendered email rows | Existing positional row mapping; update all constructors together |
| `email-stats-modal-frag.html` | Likely modified | Plain Attachment column/caption | Existing table/caption/data-label pattern |
| `application.properties`, `.env.example` | Likely modified | First batch, unlimited default, lane/lease/repair settings | Existing env interpolation and startup validation |

## Source-Grounded Avoid List

- Do not preserve `MAX_PAGES = 5` as mailbox completion behavior.
- Do not use `checkpointToken` as both backfill and freshness state.
- Do not use one account-wide semaphore across freshness and backfill.
- Do not treat process-local maps as durable coalescing or leases.
- Do not use JPA read-then-save as the concurrent-lane upsert boundary.
- Do not parse Gmail history IDs as Java `long`.
- Do not advance a cursor separately from the metadata/delete effects it represents.
- Do not call `JpaOAuth2AuthorizedClientService.removeAuthorizedClient` on refresh failure
  while it deletes `UserAccount`.
- Do not rebuild Google refresh credentials for every Gmail client.
- Do not fabricate OAuth issued/expiry timestamps.
- Do not persist or render `gmailMessage.getSnippet()`.
- Do not map absent METADATA MIME children to `hasAttachments=false`.
- Do not present `resultSizeEstimate` or local indexed count as an exact provider total.
- Do not infer freshness from `MAX(message_metadata.last_synced_at)`.
- Do not copy legacy `sync-badge`/card-like status visuals into Activity.
- Do not assume `hx-trigger="every ..."` currently works; `htmx-lite.js` ignores it.
- Do not pass `outerHTML settle:120ms` to the current swap function unchanged.
- Do not add another phase-numbered sync test family that duplicates Phase 9 behavior.
- Do not rely on regex-stripped raw HTML as the primary proof of fragment semantics.

