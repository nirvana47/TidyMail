---
status: issues_found
scope: full-codebase (architectural quality review, not phase-scoped)
depth: deep
files_reviewed: 277 main + 61 test (spot-audited hotspots + fan-out survey)
date: 2026-08-12
findings:
  critical: 6
  warning: 14
  info: 12
  total: 32
---

# TidyMail — Codebase Quality Review

This is a **whole-codebase architectural review**, not the usual phase-scoped diff review.
The request was explicit: find quality issues both major and minor, judge by "can this be done
better / more robust / more maintainable / more modular" rather than by implementation cost.

Scope: 277 main Java files (~32.6K LOC), 61 test files, 14 Flyway migrations, config, templates,
build. Findings below cite `file:line`. Severity uses GSD conventions: **Critical** (correctness /
security / data-integrity risk that can ship a bug), **Warning** (maintainability / robustness debt
that will bite), **Info** (polish, consistency, modularity opportunities).

Overall: this is a **mature, disciplined codebase** — constructor injection everywhere, CSRF on,
tokens encrypted at rest (AES-GCM + HKDF + hard-fail startup gate), `ddl-auto=validate`, Flyway as
schema source of truth, rich domain models in newer code, defensive record validation. The issues
are real but sit on top of a solid foundation. The single biggest structural risk is the
**dual hand-maintained SQL dialect** (see C-1/C-2/C-3), which is also the strongest argument for the
jOOQ direction you raised.

---

## On your jOOQ suggestion — the strategic finding

You floated adopting jOOQ instead of hand-writing SQL in `.java`. After reading the SQL layer, I
**agree, and the justification is stronger than "it's cleaner."** The codebase currently pays for
raw SQL in three compounding ways:

1. **Two dialects maintained by hand.** Production SQL is Postgres-native; tests run a *separately
   authored* H2 translation. They have diverged by up to **171 lines in one migration** (V9), and
   production code carries an `h2` boolean that forks query text in 5 places. jOOQ generates
   dialect-correct SQL from one query definition — it is the canonical fix for exactly this problem.
2. **No compile-time safety.** Column lists, CTE names, and result mappings are strings
   (`DecisionSurfaceQueryRepository.SENDER_COLUMNS`) hand-aligned with `RowMapper` positional reads.
   A renamed column compiles fine and fails at runtime. jOOQ's generated schema makes this a compile
   error.
3. **String interpolation into SQL.** `String.formatted(...)` splices predicate fragments into query
   bodies (`DecisionSurfaceSql.java:178`, `hasLabel` at `:289`). Today the interpolated values are
   trusted constants, but it's a pattern one careless edit away from injection.

**Caveat / honesty:** jOOQ is not a free win here. The `DecisionSurfaceSql` queries are genuinely
advanced — multi-stage CTEs, window functions, `percentile_cont`, array cardinality signals. jOOQ
*can* express all of this, but porting it is a large effort and jOOQ's commercial license is required
for closed-source use against commercial databases (Postgres is covered by the free/open tier, so
you're fine). A pragmatic middle path if full jOOQ is too big a lift: keep the raw SQL but
**eliminate the second dialect by running Testcontainers-Postgres everywhere** (kills C-1/C-2/C-3 at
the root without a rewrite). See recommendations at the end.

---

## CRITICAL

### C-1 — Two hand-maintained SQL dialects that have already diverged
`src/main/resources/db/migration/V1..V14` (Postgres) vs `src/test/resources/db/test-migration/V1..V14` (H2 `MODE=PostgreSQL`)

The production migrations are Postgres-native (`BYTEA`, `TIMESTAMPTZ`, `TEXT[]`, `JSONB`, `BIGSERIAL`,
`now()`, `pgcrypto`, `unnest ... WITH ORDINALITY`). Tests select a *parallel, hand-translated* H2 set
via inline `spring.flyway.locations=classpath:db/test-migration` in each `@SpringBootTest`. The two
sets share the same version numbers, so Flyway computes independent checksums per environment — **edit
one and not the other and nothing trips.** Measured divergence: V9 differs by 171 lines, V6 by 99,
V1 by 94. Only V10/V11 are identical. This is the highest-leverage risk in the repo: the tests that
gate merges validate a schema that is *not what ships*.

**Fix:** run integration tests against Testcontainers-Postgres against `src/main/resources/db/migration`
directly (the `*PostgresMigrationTest` files already prove this works), and delete `db/test-migration`.
Or adopt jOOQ + a single migration set.

### C-2 — `medianDelaySeconds` is two different algorithms for H2 vs Postgres
[AnalyticsLedgerRepository.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java#L326-L359)

The H2 branch (`:330-339`) fetches all delay values and computes the median **in Java**; the Postgres
branch (`:341-356`) uses `percentile_cont(0.5) WITHIN GROUP`. Two independent implementations of the
same statistic. Worse, the eligibility filters differ subtly between the two paths (the Postgres SQL
inlines `decision.occurred_at>=metadata.received_at`; the H2 path delegates to
`eligibleDelaySeconds`). Even-count median tie-breaking and null handling can legitimately differ.
Tests exercise the Java path; production runs the SQL path. A metric bug can ship green.

### C-3 — Security-relevant fingerprint hashing is duplicated across dialects and must stay byte-identical by hand
prod [V9__action_execution_history_and_undo.sql](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/resources/db/migration/V9__action_execution_history_and_undo.sql) `:107-114` vs test `V9 :144-150`; [UnsubscribeCandidateRepository.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java) `:458-461`

Target fingerprints (used for idempotency / exact-scope matching, a correctness-and-safety primitive)
are computed by SQL that differs per dialect: Postgres `encode(digest(convert_to(id,'UTF8'),'sha256'),'hex')`
over `unnest WITH ORDINALITY`; H2 `LOWER(RAWTOHEX(HASH('SHA-256', STRINGTOUTF8(ARRAY_GET(...)))))` over
`JOIN SYSTEM_RANGE(1,1000)`. The `SYSTEM_RANGE(1,1000)` cap **silently truncates arrays longer than
1000** in H2 while Postgres `unnest` does not. Two SHA-256 code paths that must produce identical hex
forever, with no test asserting they agree on the same input. This is exactly the class of divergence
that jOOQ or single-dialect testing removes.

### C-4 — `@Transactional(readOnly=true)` held open across streaming HTTP response I/O
[AnalyticsService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/AnalyticsService.java) `:393-429` (`writeSourceCsv`), class-level `@Transactional` at `:50`

The CSV/source streaming path opens a DB `queryForStream` and writes each row to the caller's `Writer`
(the servlet response) **while the transaction/connection is held**. A slow HTTP client pins a pooled
DB connection for the full duration of the network write. With Hikari `maximum-pool-size=20`, a handful
of slow readers can exhaust the pool and stall the app. Streaming to a response must not hold a
transaction across the flush. (Note: this lives in the export path, which per C-5 should not exist at
all — but the anti-pattern is worth recording regardless.)

### C-5 — Export functionality is fully wired and reachable, contradicting the hard "no export" constraint
[ExportController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/ExportController.java) `:36-37`, endpoints at `:108,129,139,149,159,190`

Project memory records a hard constraint: **"Export: No export functionality permitted on any page."**
Yet `ExportController` is an active `@Controller` exposing six live endpoints (`/senders.csv`,
`/unread.csv`, `/unsubscribe.csv`, `/review/history.csv`, `/analytics.csv`, `/analytics/snapshot.json`).
Supporting beans are all live: [ExportService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/ExportService.java) (`@Service`),
[ExportProperties.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/config/ExportProperties.java) (`@ConfigurationProperties`), and the whole `com/tidymail/export/` package.
`SecurityConfig` `anyRequest().authenticated()` means any logged-in user can hit these by URL. No
template links to them (dead in UI) but they are **live on the wire** — a maintained feature that
directly violates a stated constraint. Recommend deleting the controller, service, `export/` package,
`ExportProperties`, the `tidymail.export.max-rows` property, and the unused `exportUrl` field on
[SenderPageModel.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/dto/SenderPageModel.java).

### C-6 — In-JVM `ReentrantLock` used as cross-instance mutual exclusion (false safety + memory leak)
[CleanupActionService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/CleanupActionService.java) `:35-36, 58-71`

`accountLocks` is a process-local `ConcurrentHashMap<Long, ReentrantLock>` guarding cleanup staging.
It provides **zero mutual exclusion across multiple app instances** — and the project targets
server/Postgres deployment where >1 instance is plausible. It layers a false guarantee on top of the
DB row locks (`SELECT ... FOR UPDATE` + `REPEATABLE_READ`). Separately, the map is **never pruned** —
one lock object per account id, retained forever = a slow memory leak. Either rely solely on the DB
row locks (they're already there) or use a real advisory/distributed lock (`pg_advisory_xact_lock`).

---

## WARNING

### W-1 — `UnsubscribeCandidateService.classify()` is a ~169-line god-method
[UnsubscribeCandidateService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/UnsubscribeCandidateService.java#L504-L672)

One method builds RFC2369/RFC8058 method facts, HTTPS/mailto target lists, provider deep links, runs
a large branching confidence/reason state machine (`:604-644`), and constructs a 21-arg row. Highest
complexity unit in the service layer; hard to test or change safely. Extract classification into its
own component.

### W-2 — `UnsubscribeCandidateService` (887 lines) violates single-responsibility
Mixes querying/paging, RFC classification, URI-policy validation, provider deep-linking, fingerprint
hashing, and export ordering. Split classification (`classify` + `method` + confidence) into a
dedicated `UnsubscribeClassifier`.

### W-3 — `AnalyticsService` (722 lines) mixes 5 concerns and uses `null` beans as feature flags
[AnalyticsService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/AnalyticsService.java) constructors `:60,64,73,86`

Three overloaded constructors permit `ledgerRepository`, `coverageRepository`, `mailboxRepository`,
`metricService` to be `null`, used as capability toggles. This forces scattered `== null` guards
(`:122,126,143,153,157,179,193,206`); any missed guard is an NPE. `completeDashboard` (`:494-620`) is
127 lines. Prefer `Optional`/`ObjectProvider` (already done correctly for `mailboxCapability` at `:57`)
or split into distinct beans. Also carries C-4.

### W-4 — Near-duplicate ~100-line preparation methods in the selection layer
[SelectionPreviewTransactionService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/SelectionPreviewTransactionService.java) `prepareCleanupStages` `:188-299` vs `prepareUnreadCleanupStages` `:304-399`

Both repeat: order+dedupe locators → lock accounts → materialize → cap-check the union set → build
`idsBySender` → per-sender `createOrGetRequired` + `confirmationIntents.issue`. The cap-check and
`idsBySender` blocks are nearly identical. `createPreview` (`:84-183`) is a third ~100-line method with
mixed concerns. Extract the shared skeleton.

### W-5 — O(n) full-scan-and-classify to resolve a single candidate row
[UnsubscribeCandidateService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/UnsubscribeCandidateService.java) `:462-485` (`executionCandidateByFingerprint`), `:487-502` (`find`)

Both paginate through the *entire* candidate set in `CLASSIFICATION_BATCH_SIZE` chunks, classifying
every candidate, just to find one by key/fingerprint (also reached via `candidateForSender`/`inspect`).
Duplicated code *and* a latent performance problem — should be a targeted repository lookup.

### W-6 — Catch-and-swallow without logging in several services
[WorkspaceModelService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/WorkspaceModelService.java) `:132-141, 144-151`; [UndoService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/UndoService.java) `:133-135`

`catch (RuntimeException ...)` blocks silently null counts and downgrade workspace state to
ERROR/PARTIAL with **no log statement**, so operational failures are invisible. `UndoService` discards
the provider exception (`result = null`) before treating it as an after-dispatch failure. The service
layer has essentially one logger anywhere ([MessageMetadataSyncService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/MessageMetadataSyncService.java#L21)) — observability is thin. Log at WARN with context.

### W-7 — 21–27-argument positional object construction
[UnsubscribeCandidateService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/UnsubscribeCandidateService.java) `:299-326` (~27 args), `:647-667`, `:875-881`; also `UnsubscribeInspection` `:348-365`

Constructing records with 20+ positional args of mostly `String`/`boolean` is highly transposition-prone
and the compiler can't catch a swap. Use builders or grouped sub-records. This also afflicts the
`RowMapper`↔`SENDER_COLUMNS` alignment in [DecisionSurfaceQueryRepository.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java#L499-L521).

### W-8 — Mutable secret material and identity exposed by reference on entities
[UserAccount.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/UserAccount.java) `:144, 196-210`

`getEncryptedAccessToken`/`setEncryptedRefreshToken` return/accept the internal `byte[]` by reference
(callers can mutate token ciphertext in place), and `id` has a public setter (a footgun on a generated
identity). Return defensive copies of `byte[]`; drop the `id` setter.

### W-9 — Inconsistent domain modeling: anemic setter-bags vs rich factories
`entity/` package

`UserAccount`, [MessageMetadata.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/MessageMetadata.java), [UserAction.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/UserAction.java), `SenderStat` are fully-mutable public-setter
bags, while [ActionExecution.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/ActionExecution.java) and [SenderTypeOverride.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/SenderTypeOverride.java) use protected ctors + factories +
intent methods. The codebase knows the better pattern but applies it inconsistently. Also: `UserAction`
leaks internal mutable collections (`getMessageIds` `:152`, `getInverseActionData` `:160`,
`getTargetIdJson` `:176`) by reference, unlike `MessageMetadata` which copies (`:186-217`).

### W-10 — No bean validation anywhere; manual validation copy-pasted across ~8 controllers
Repo-wide: zero `@Valid`/`@Validated`/`jakarta.validation` usages.

Near-identical hand-rolled param validators are duplicated: `single(...)` in
[CleanupActionController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/CleanupActionController.java#L185),
[SelectionPreviewController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/SelectionPreviewController.java#L179),
[ActionCommandController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/ActionCommandController.java#L85); `one/optionalOne` in
[UnsubscribeExecutionController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/UnsubscribeExecutionController.java#L125-L141); `required`, `rejectUnknown`, `singleCategoryParameter` elsewhere. Largest
single source of controller duplication. Bean Validation + a shared `@ControllerAdvice` would remove
most of it.

### W-11 — No global exception handling for HTML/view controllers
[SafeDataAccessErrorHandler.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/SafeDataAccessErrorHandler.java#L13) is `@RestControllerAdvice` handling only `DataAccessException` → `text/plain`.
The many `ResponseStatusException`/`IllegalArgumentException` throws across controllers have no unified
handling, so error rendering is inconsistent (default error page vs plain text vs per-controller HTMX
retarget fragments). Add a `@ControllerAdvice` that maps domain exceptions to consistent fragments.

### W-12 — Duplicated `currentAccount(...)` / `resolveProvider(...)` helpers across controllers
[BulkActionApiController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/BulkActionApiController.java#L51), [SenderAggregationController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/SenderAggregationController.java#L62), [FragmentController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/FragmentController.java#L64), [ReplyZeroPageController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/ReplyZeroPageController.java#L53)

Identical `currentAccount(OAuth2AuthenticationToken)` in 4 controllers; identical `resolveProvider`
in 3; plus a competing `ownerKey(...)` path in `ActionCommandController`. Consolidate into one
`@ModelAttribute` resolver or a shared component.

### W-13 — GET endpoints with side effects (unsafe HTTP semantics, bypass CSRF)
[SenderAggregationController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/SenderAggregationController.java#L103) — GET `/api/senders` calls `persistSnapshot(...)` (a write); `:147` and
[FragmentController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/FragmentController.java#L104) trigger live external Gmail calls on GET. GETs should be safe/idempotent and are
not CSRF-protected. Move writes/external effects to POST.

### W-14 — OAuth access-token refresh is unmanaged; refreshed tokens are not persisted
[GmailClientFactory.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/mail/gmail/GmailClientFactory.java#L52-L58); [JpaOAuth2AuthorizedClientService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/security/oauth2/JpaOAuth2AuthorizedClientService.java#L42-L96)

No `OAuth2AuthorizedClientManager`/refresh provider is wired. Gmail rebuilds `UserCredentials` from the
stored refresh token and calls `refreshIfExpired()` on **every client build**, and the freshly minted
access token is never written back to `UserAccount` — so a network refresh happens each time instead of
caching until expiry. `JpaOAuth2AuthorizedClientService` `:73-74` also falls back to
`issuedAt = expiresAt = now()` (an already-expired token) when timestamps are null. No visible refresh
path for Microsoft/Graph at all.

---

## INFO

### I-1 — Duplicated SHA-256 boilerplate in 6+ places
`UnsubscribeCandidateService` `:736-746` and `:748-755` (two in one class), [ActionScopeHasher.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/ActionScopeHasher.java#L123), [ProtectedOverrideAuditService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/ProtectedOverrideAuditService.java#L68), [ActionFingerprints.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/ActionFingerprints.java#L40), [AnalyticsDrilldownKeyService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/AnalyticsDrilldownKeyService.java#L156). Extract one `Sha256` utility.

### I-2 — Duplicated account-label and provider→bean mapping, with an inconsistency
`email + " (Gmail)"` hardcoded in `SelectionPreviewTransactionService:465`, `ActionConflictService:132`,
`UnsubscribeSenderActionService:76,97`, but `WorkspaceModelService:281-283` uses email-only — a
user-visible discrepancy risk. The `"google"→"gmailMailService"` switch is duplicated in
`UndoService:335-340` and `MailboxMutationHandler:58-59`. Centralize both.

### I-3 — Broad `catch (Exception ...)` in crypto/token paths
`AnalyticsDrilldownKeyService:77,126`; `ActionConfirmationIntentService:159,194,205`;
`ReviewOperationIntentService:104`. Mostly defensible (they rethrow domain exceptions) but can mask
`NullPointerException`/programming errors as "invalid token." Catch the specific crypto/IO exceptions.

### I-4 — Dead / tombstone routes retained as GONE stubs
[UnsubscribeExecutionController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/UnsubscribeExecutionController.java#L43-L47), [ReviewActionsController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/ReviewActionsController.java#L330-L333), [BulkActionApiController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/BulkActionApiController.java#L115-L123) (`queueScope`
even does a pointless DB lookup before throwing GONE). Also `UndoService.executeUndo` `:398-402` is a
test-only always-throws stub. Remove once no client depends on the 410.

### I-5 — `CascadeDeleteLogoutSuccessHandler` is a misleading no-op
[CascadeDeleteLogoutSuccessHandler.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/security/CascadeDeleteLogoutSuccessHandler.java#L22-L27) overrides `onLogoutSuccess` only to call `super`; the name implies
cascade deletion that actually lives in `AccountController.deleteDataAndLogout`. Delete the override or
rename to reflect reality.

### I-6 — Unused injected dependency
[SenderAggregationController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/SenderAggregationController.java#L36-L48) injects `SenderStatsPersistService` but never reads it. Dead field.

### I-7 — `UndoService` hardcodes its `Clock` instead of injecting
[UndoService.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/service/UndoService.java#L59) uses `Clock.systemUTC()` while every peer service injects a `Clock`, making its
time-dependent eligibility logic non-deterministic in tests. Inject a `Clock` bean.

### I-8 — `OAuthProviderAvailability` mixes `@Value` fields + manual ctors; placeholder detection uses magic strings
[OAuthProviderAvailability.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/config/OAuthProviderAvailability.java#L8-L69). Passing it as an MVC handler parameter silently instantiates a
non-injected copy (documented footgun in [LoginController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/LoginController.java#L16-L23)). `isGoogleConfigured` rejects via
`startsWith("your-")`/`equals("skipped")` sentinels, duplicated per provider. Convert to a
`@ConfigurationProperties` record.

### I-9 — Inconsistent controller return types
Raw DTOs, `String` view names, `void` writing to `HttpServletResponse`, `ResponseEntity<Void>`,
`ResponseEntity<?>`, `redirect:` strings, and `Object` returning either `RedirectView` or
`ResponseEntity` ([SenderCategoryController.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/controller/SenderCategoryController.java#L43-L95)). The `Object` return is the worst for
readability/testing. Standardize per endpoint class (view controllers → `String`/fragment; API → typed
`ResponseEntity`).

### I-10 — Enum persistence inconsistency
[UserAction.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/UserAction.java#L302-L304) stores enums as raw `String` with manual `valueOf` in `@PrePersist`, while
[SenderTypeOverride.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/SenderTypeOverride.java#L29) uses `@Enumerated(EnumType.STRING)`. Prefer the latter everywhere.

### I-11 — Test suite: phase-named tests, integration-heavy, `Thread.sleep`, duplicated fixtures
50/61 test files are `Phase*Test` (coupled to delivery timeline, not behavior; Phase13/14/15/16 all
re-test "Workspace*Fidelity" → overlapping setup). 31/61 use full `@SpringBootTest`; zero use
`@DataJpaTest`/`@WebMvcTest` slices. `Thread.sleep` timing in 7 tests
(`Phase11UnsubscribeSecurityTest:172,180,546`, `Phase12ExecutionServiceTest:406`,
`Phase12UndoCompensationTest:407`, `Phase4GmailProviderTest:191`, and a GC+heap assertion in
`Phase13PerformanceTest:913`). No `application-test.properties` → datasource/flyway blocks and
`user_accounts` insert fixtures copy-pasted across dozens of tests. `Phase13PerformanceTest:929-932`
fakes fingerprints via `Integer.toHexString(hashCode())`. (No `@Disabled` tests — good.)

### I-12 — Stray repo artifacts
[remove_rule_interactions.py](file:///Users/bytedance/Documents/trae_projects/TidyMail/remove_rule_interactions.py) is a tracked one-off script that hardcodes an absolute path to
`tidymail-ux-rehaul/...` which **does not exist** in the repo (the design dir is
`tidymail-ux-rehaul-draft`) — dead, broken, and tracked. `.DS_Store` files exist on disk (correctly
gitignored). Remove the script.

---

## Recommendations (prioritized)

1. **Kill the dual-dialect risk at the root (fixes C-1, C-2, C-3, and de-risks W-anything-SQL).**
   Either (a) run all integration tests on Testcontainers-Postgres against
   `src/main/resources/db/migration` and delete `src/test/resources/db/test-migration` + the `h2`
   branches in the 5 repositories; or (b) adopt jOOQ with a single migration set and generated schema.
   Option (a) is far less effort and removes the immediate correctness risk; option (b) is the durable
   answer to your modularity/type-safety goal and I'd pursue it next if the query complexity is going to
   keep growing. Both eliminate the "H2 passes / Postgres differs" failure mode.

2. **Delete the export subsystem (C-5).** It contradicts a hard constraint and carries the C-4
   transaction anti-pattern. Remove controller, service, `export/` package, `ExportProperties`, the
   property, and the dead `exportUrl` field.

3. **Replace the in-JVM cleanup lock with `pg_advisory_xact_lock` or rely on existing row locks (C-6).**

4. **Decompose the god-classes** (`UnsubscribeCandidateService`, `AnalyticsService`) and extract the
   duplicated selection/preparation skeleton — this is where future bugs will concentrate.

5. **Introduce Bean Validation + a `@ControllerAdvice`** to delete the copy-pasted controller validators
   and unify error rendering (W-10, W-11, W-12).

6. **Wire a proper `OAuth2AuthorizedClientManager`** and persist refreshed tokens (W-14).

7. Housekeeping sweep: `remove_rule_interactions.py`, GONE stubs, no-op logout handler, unused injected
   field, hardcoded `Clock`, SHA-256/label-mapping duplication.

**Kudos (don't regress these):** constructor injection throughout; CSRF enabled with explicit `_csrf`
allowlisting; AES-GCM token encryption with a hard-fail startup gate; `ddl-auto=validate` + Flyway;
`spring.jpa.open-in-view=false`; rich domain models in `ActionExecution`/`SenderTypeOverride`;
defensive record validation; the reserve→commit→external-I/O→reopen-transaction pattern in
`UndoService.execute` (`:120-138`) is the *correct* way to keep external calls out of DB transactions —
worth copying to C-4's streaming path.
