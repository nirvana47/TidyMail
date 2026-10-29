# Codebase map

Mapped on 2026-10-14. Java paths below are relative to `src/main/java/com/tidymail/`. This guide describes source behavior, not release certification.

Updated 2026-10-28 for [Phase 3 indexed browsing](PHASE-3-INDEXED-DASHBOARD.md).
`DashboardController` owns `/dashboard` and `/dashboard/api/**`; `DashboardService`
and `DashboardRepository` share the scope/filter queries, `DashboardIdentity`
derives conservative unit keys, `DashboardGroupingService` persists corrections,
and `DashboardPreviewService` freezes exact targets using the existing scope hash
and records. UI assets are `dashboard.html`, `dashboard.css` and `dashboard.js`.
`MessageMetadataMapper` and the bulk sync repository retain the new evidence;
V16's trigger maintains derived memberships for all metadata insert paths.

## Layout

| Path | Responsibility |
|---|---|
| `pom.xml`, `InboxCleanerApplication.java` | Java 21/Maven build, Spring Boot 3.5.16 entry point |
| `controller/` | Pages, fragments, form/API boundaries and safe data-access errors |
| `service/` | Ownership, sync, classification, selection, staging, execution, undo and analytics |
| `repository/` | JPA persistence and PostgreSQL decision/analytics queries |
| `entity/`, `dto/`, `action/` | Persisted records, query/page/result models and state enums |
| `mail/`, `mail/sync/` | Provider-neutral read/mutation and sync interfaces |
| `mail/gmail/` | Only production mailbox provider, official Gmail client and rate limiting |
| `security/`, `security/oauth2/`, `security/crypto/` | Login, encrypted authorized clients, managed refresh and key validation |
| `unsubscribe/` | Parsing, eligibility, consent, address checks and pinned HTTP transport |
| `job/`, `config/`, `util/` | Scheduling/executors, provider settings, cursor/RFC helpers |
| `src/main/resources/templates/` | Thymeleaf pages and reusable fragments |
| `src/main/resources/static/` | `workspace.css`, `htmx-lite.js`, `cleanup-categories.js` |
| `src/main/resources/db/migration/` | Sixteen production Flyway migrations, V1–V16; V16 not yet applied to the retained development index |
| `src/test/java/` | Unit, web, PostgreSQL journey, contract, security and performance tests |
| `tidymail-ux-rehaul/`, `tidymail-ux-rehaul-draft/` | Design references and mock HTML, not served application templates |
| `target/` | Ignored build artifacts; regenerate for current evidence |

## Routes and owners

| Route family | Controller / services |
|---|---|
| `/`, `/login` | `HomeController`, `LoginController` |
| `/dashboard`, `/dashboard/api/**` | `DashboardController`, dashboard query/grouping/preview services |
| `/accounts/connect/{provider}`, `/logout/delete-data` | `AccountController`, `AccountSessionService`, `UserDataDeletionService` |
| `/senders`, results and category fragments | `WorkspaceController`, `SenderDecisionQueryService`, `SenderCategoryService` |
| `/unread`, `/unread/results` | `WorkspaceController`, `UnreadDecisionQueryService` |
| `/unsubscribe`, results and candidate inspection | `WorkspaceController`, `UnsubscribeCandidateService`, `UnsubscribeStatsController` |
| `/review`, confirmation/approval/cancel/undo and conflicts | `WorkspaceController`, `ReviewActionsController`, `ReviewActionService`, `ActionCommandService`, `UndoService`, `CompensationService` |
| `/activity`, `/activity/status` | `ActivityController`, `ActivityQueryService` |
| `/analytics`, `/analytics/source` | `AnalyticsController`, `AnalyticsService`, query/metric/link/drilldown services |
| `/reply-zero`, `/reply-zero-frag` | `ReplyZeroPageController`, `FragmentController`, `ReplyZeroDetector` |
| `/api/selection-preview`, `/api/cleanup-actions`, `/api/action-commands` | Selection, cleanup and command controllers |
| `/unsubscribe/execute`, `/unsubscribe/manual-complete`, outcomes | `UnsubscribeExecutionController`, execution/preparation/outcome services |
| `/api/sender-categories/correct`, `/api/sender-categories/organize` | `SenderCategoryController`, category and sender override services |
| `/api/metadata-sync`, `/api/senders/_sync` | Metadata sync and sender aggregation controllers |
| `/api/senders`, `/api/reply-zero`, aggregation/progress fragments | Earlier aggregation/fragment surfaces; class-level `/api` prefixes avoid apparent page-route collisions |

`WorkspaceRoute` defines six rail entries. `WorkspaceModelService` supplies shared account/navigation state. Query DTOs and repository SQL define filters and paging. `htmx-lite.js` is a local implementation; do not assume all upstream htmx features are available.

## Account and sync flow

Google OAuth → encrypted authorized-client persistence → owner/active-account setup → bootstrap request → `IncrementalSyncJob` bounded dispatch.

`AccountSessionService` and `OwnedAccountScopeService` resolve authorized accounts. Added accounts inherit the owner key through a session intent. The workspace operates on one active account and rejects the all-account scope. `ManagedGoogleCredentialService` coordinates refresh and persistence; authorization failure marks `REAUTH_REQUIRED` without deleting indexed records.

`MailboxBootstrapService` obtains a Gmail history anchor and recent page (default 200). `MailboxBackfillService` pages older All Mail data, including Spam/Trash. `MailboxFreshnessService` consumes unfiltered Gmail History separately; `MailboxRescanService` handles expired history. Independent executor lanes and persisted leases allow freshness during backfill. `MailboxSyncCommitService` commits facts and cursors atomically; provider calls stay outside those transactions.

`GmailMailService` enforces `METADATA` and a 24-header whitelist. `MessageMetadataMapper` persists header/label facts. Attachment membership is observed via Gmail search with unknown/present/absent states. `GmailRateLimiter` bounds requests and reserves freshness capacity. A positive unique-message ceiling limits backfill; zero means unlimited.

Activity derives from persisted state. Closing the browser does not cancel server sync. Polling reports factual readiness/freshness and omits unsupported totals.

## Decision and execution flow

Indexed metadata → decision SQL → `DecisionRuleSet` / `DecisionClassifier` → filtered pages → selection/direct staging → materialized `ActionScope` → Review or acknowledged immediate command → execution ledger.

Classification uses deterministic header/thread/age signals and readable reasons. `SenderTypeOverrideService` stores account-local corrections; `SenderCategoryService` organizes senders. These categories do not create Gmail filters. Protection has separate persistence and audit handling.

`SelectionMaterializationService`, `SelectionPreviewTransactionService` and `ActionScopeService` resolve exact IDs/fingerprints. `CleanupActionService` stages direct selections. `ActionCommandService` handles signed confirmations and staged/immediate modes; Review gathers intents and resolves conflicts.

`ExactScopeExecutor` reserves through `ExecutionReservationService`, dispatches one handler and records through `ExecutionCompletionService`:

| Handler | Actions |
|---|---|
| `MailboxMutationHandler` | Archive, Trash, Mark Read |
| `LocalDecisionHandler` | Keep, Review |
| `ProtectionHandler` | Protect, Remove Protection |
| `UnsubscribeActionHandler` | Unsubscribe |

`ActionPreflightService` verifies current protection, typed intent, scope compatibility and target fingerprints. Results distinguish success, failure and verification pending. `ExecutionRecoveryService` handles expired leases; uncertainty after dispatch is not assumed successful or blindly replayed.

`UndoService` reverses recorded successful targets for Archive, Trash and Mark Read only, with intent/lease checks. Completion sets a ten-minute window in code. `CompensationService` handles protection removal separately. Safety properties in configuration should not be assumed to control every engine constant.

## Unsubscribe flow

Header evidence → parser/URI policy → inspection → explicit method and fresh consent → preparation → bounded transport/manual handoff → attempt/outcome events.

`Rfc8058Eligibility` checks HTTPS, POST field and trusted DKIM coverage. Legacy HTTPS GET is separately selected; mailto/account-page/manual paths do not automatically send email. `PublicAddressPolicy`, `DestinationResolver` and `PinnedTransportFactory` validate and pin destinations. Transport bounds DNS, connection, response and total time; redirects and automatic retries are disabled. `ACCEPTED` differs from `CONFIRMED`: HTTP success does not prove future mail has stopped.

## Persistence

| Record family | Models / purpose |
|---|---|
| Ownership/credentials | `UserAccount`: owner, provider, encrypted tokens, authorization status |
| Mailbox state | `MailboxSyncState`, `SyncProgress`: cursors, leases, readiness/errors |
| Indexed facts | `MessageMetadata`, `SenderStat`: unique account/provider message and summaries |
| Corrections/protection | `SenderTypeOverride`, `SenderCategory`, `SenderCategoryAssignment`, `ProtectedSender`, `ProtectedOverrideAudit` |
| Scope/consent | `ActionScope`, `ActionScopeTarget`, `ActionIntent`, `ActionCommandToken` |
| Review/results | `UserAction`, `ActionConflict`, `ActionExecution`, `ActionExecutionTarget`, `ActionEvent` |
| Unsubscribe evidence | `UnsubscribeAttempt`, `UnsubscribeOutcomeEvent`, `UnsubscribeIntentNonce` |

V1–V5 establish accounts, stats, actions and sync/review. V6–V8 add metadata/scopes, protection and unsubscribe. V9–V10 add execution/undo ledger and analytics indexes. V11–V14 add owned connections, shared drafts, queue ordering and sender overrides. V15 adds durable mailbox state and OAuth status, enriches metadata and removes the snippet column. Owner deletion removes accounts and dependent records through database cascades.

Analytics uses `AnalyticsReceivedRepository`, `AnalyticsMailboxRepository`, `AnalyticsCoverageRepository` and `AnalyticsLedgerRepository`. Availability/unknown evidence is explicit. Size estimates represent full messages, not attachment-only bytes or verified storage savings.

## Security and maintenance

`SecurityConfig` permits login/static/OAuth and selected health/info routes; other routes require authentication. Spring CSRF remains enabled. `CryptoUtil` encrypts tokens; startup validation checks keys. Bodies/snippets/attachment content are excluded, but addresses and subjects are sensitive stored metadata.

`CascadeDeleteLogoutSuccessHandler` is a legacy name: logout is session-only. Explicit deletion is the confirmed `AccountController` flow. `UserActionCleanupJob` removes expired unresolved actions hourly. `SenderStatsVacuumJob` removes stats/actions/sync progress for accounts inactive over 90 days. Neither implements full account/metadata/token expiration after a fixed TTL.

Maintenance concerns include large services, duplicated account/provider helpers and validation, and `CleanupActionService`'s per-account in-JVM lock map. Those locks do not serialize multiple instances. See [Status](STATUS.md) and [Testing](TESTING.md).
