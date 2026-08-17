---
gsd_state_version: 1.0
milestone: v1.2
milestone_name: Trustworthy Data & Maintainable Core
current_phase: 19
current_phase_name: Mailbox Freshness & First-Run Onboarding — Gmail History API sync
status: executing
stopped_at: Phase 18 complete; ready to plan Phase 19
last_updated: "2026-08-14T17:50:41.237Z"
last_activity: 2026-08-14
last_activity_desc: Phase 19 execution started
progress:
  total_phases: 5
  completed_phases: 1
  total_plans: 13
  completed_plans: 6
  percent: 20
---

# Project State

**Project:** TidyMail (Java/Spring Boot Inbox Cleaner)
**Created:** 2026-08-07
**Last Updated:** 2026-08-13 — v1.1 closed; v1.2 (Trustworthy Data & Maintainable Core) opened, planned, and expanded to Phases 18–22 from functionality/performance feedback.

---

## Current Position

Milestone: v1.2 Trustworthy Data & Maintainable Core (Phases 18–22) — PLANNED
Phase: 19 (Mailbox Freshness & First-Run Onboarding — Gmail History API sync) — EXECUTING
Plan: 1 of 7
Status: Executing Phase 19
Last activity: 2026-08-14 — Phase 19 execution started

---

## Overall Progress

| Artifact | Status | Notes |
|---|---|---|
| `.planning/PROJECT.md` | Complete | Current milestone goals and evolution rules |
| `.planning/research/` | Complete | Stack, features, architecture, pitfalls, and synthesis |
| `.planning/REQUIREMENTS.md` | Complete | 76 v1.1 requirements with 100% traceability |
| `.planning/ROADMAP.md` | Complete | All currently defined phases are complete |
| Milestone progress | 100% | 50/50 plans complete; new functionality and data-quality scope has not yet been added |
| Phase 9 | Complete | 3/3 plans; 14/14 requirements independently verified |
| Phase 10 | Complete | 3/3 plans; 14/14 requirements independently verified |
| Phase 11 | Complete | 5/5 plans; 8/8 requirements independently verified |
| Phase 12 | Complete | 5/5 plans; 11/11 requirements verified |
| Phase 13 | Complete With Constraints | 6/6 plans executed; latest Build Notes aligned; QUAL-02 live-browser gate pending |
| Phase 14 | Complete | 3/3 plans; UI fidelity and route recovery delivered |
| Phase 15 | Complete | 11/11 plans; workspace cleanup navigation and classification UI accepted |
| Phase 16 | Complete | 8/8 plans; UI reconciliation accepted |
| Phase 17 | Complete | 6/6 plans; authenticated UI blocker follow-ups accepted |

---

## Decisions Reference

Canonical decision source: **[docs/DECISIONS.md](../docs/DECISIONS.md)**

Key locked decisions (do not override without updating that file):

1. **Language:** Java 21+ LTS, Maven, Spring Boot 3.x
2. **Scope:** Non-AI only. No LLM, no AI features.
3. **Deployment:** JAR behind Cloudflare Tunnel (NOT Workers — JVM cannot run on V8/Wasm)
4. **Auth:** Both Gmail + Outlook OAuth via Spring Security OAuth2 Client
5. **Frontend:** Thymeleaf server-rendered + htmx fragments (no SPA, no React build pipeline)
6. **DB:** Postgres via Spring Data JPA (Neon/Supabase/Tembo managed, or local Docker Postgres)
7. **Jobs:** In-process Spring `@Scheduled` only (no RabbitMQ/Kafka/BullMQ)
8. **Privacy:** Gmail METADATA format only; no email bodies stored; sign-out wipes user data

---

## Lessons from Previous Attempt (InboxTriage / TidyGmail V1)

Carried forward from `~/Documents/trae_projects/InboxTriage/.planning/research/PITFALLS.md` and `.planning/REQUIREMENTS.md`:

**DO:**

- Ship ONE screen end-to-end before adding a second surface (avoided A1 pitfall)
- Sort recommendation cards & top-senders tables by IMPACT (count descending), not alphabetically
- Actions MUST act on the exact rows shown — same query, no exceptions (avoided A3)
- Undo replays inverse decision in place; no navigation redirect (avoided A4)
- One source of truth for query state — server form, not client string-stitching (avoided A5)
- After every action, refresh the queue fragment from the server (avoided A6)
- Ship with empty states AND a backend test that fails if seed → empty tables (avoided A7)
- Mobile-first CSS, max-width 100% on every card/table (avoided A8)
- Every action writes a decision row BEFORE showing "done" (avoided A14)
- Use plain-English labels, not cryptic internal names (avoided A15)
- Cap at 10 decisions per release; extra decisions go to issue tracker
- DO explicitly export JAVA_HOME and PATH for Homebrew openjdk@21 before every `mvn` / `java -jar` invocation on macOS (macOS /usr/bin/java is a broken stub)
- DO pin third-party SDK versions via direct Maven Central solrsearch JSON API query (curl+python3) to avoid fabricated version cache misses from timed-out web searches
- DO declare env-var config classes (`@Component` with `@Value("${UPPER_SNAKE:}")` fields) and inject them via CONSTRUCTOR injection at the Spring bean/class level. NEVER declare such components as Spring MVC handler method parameters — ServletModelAttributeMethodProcessor will instantiate a fresh copy via the no-arg constructor, skipping AutowiredAnnotationBeanPostProcessor's `@Value` injection, resulting in silent empty values. (Phase 2 B8 root cause)
- DO call Spring Security 6 Lambda DSL's `.authorizeHttpRequests(closure)` EXACTLY ONCE per SecurityFilterChain. A second call overwrites the first config OR throws a DSL re-entry exception. Use `{}` closure body with multiple matcher lines inside a single authorizeHttpRequests invocation. (Phase 2 B7 root cause)
- DO use `InMemoryClientRegistrationRepository` ONLY when registrations.size() ≥ 1. When zero providers configured, return a lightweight anonymous `ClientRegistrationRepository` whose `findByRegistrationId` always returns null — `InMemoryClientRegistrationRepository(emptyList)` throws `IllegalArgumentException("registrations cannot be empty")` during bean construction. (Phase 2 B6 root cause)
- DO use OS `export VAR=value` when running JVM smoke tests with fake OAuth env vars. `-DVAR=value` must come BEFORE `-jar` (JVM arg syntax). Spring Boot `--VAR=value` after JAR filename works for Spring properties but still prefer OS exports as the canonical environment setup pattern (matches how actual spring-dotenv `.env` file + shell wrapper runtime configures things).
- For debugging env-var resolution during bean wiring, add a temporary `/debug-env` @RestController with `Environment` + the target component injected via constructor; return a JSON map of values with masking. This is the fastest way to rule out env-var-not-found vs component-not-injected vs method-param-bypassing-injection bugs.
- DO put H2-compatible Flyway migrations under `src/test/resources/db/test-migration/` (separate classpath dir) and set `spring.flyway.locations=classpath:db/test-migration` in test @TestPropertySource. NEVER put H2 overrides under the same `classpath:db/migration` directory with main PG migrations — Flyway will see BOTH copies simultaneously across test-classes + main-classes classpath roots and throw "Found more than one migration with version N". (Phase 3 B13 root cause)
- DO mark `byte[]` fields as `@Lob` when the schema declares them as Postgres BYTEA or H2 BINARY LARGE OBJECT. Unannotated `byte[]` defaults to `VARBINARY(255)` in Hibernate's type resolver, which fails `ddl-auto=validate` with "found [BLOB] expecting [VARBINARY(255)]". (Phase 3 B14 root cause)
- DO verify base64-encoded AES-256 key length: 32 bytes encodes to EXACTLY 44 base64 characters (43 content chars + 1 `=` padding char). Any 48-char string with double-equals (`==`) decodes to 36 bytes and is too long. Quick check with `python3 -c 'import base64; print(len(base64.b64decode(s)))'` must print `32`. (Phase 3 B11 root cause)
- DO use derived-delete queries in Spring Data (`void deleteByXxx(...)`) only when you also annotate the method with `@Modifying @Transactional`. Interface methods without these two annotations throw `InvalidDataAccessApiUsageException` at query execution time. Mutating methods are not the JPA default; finders are. (Phase 3 B9 fix pattern)
- DO NOT rely on `JPA CascadeType.REMOVE` when you run `@Modifying delete*` bulk queries. Those queries skip the PersistenceContext entirely. To guarantee child rows are wiped, ALWAYS add `ON DELETE CASCADE` at the DDL level (in the migration SQL CREATE TABLE FK declaration). Works for entity-delete, bulk-delete, and even psql CLI deletes. (Phase 3 D-3.6)
- DO persist OAuth user info (email, display name, avatar) by listening to Spring's `AuthenticationSuccessEvent` published at OAuth handshake completion. The standard `OAuth2AuthorizedClientService.saveAuthorizedClient()` method does NOT receive the Authentication object itself; the principal attributes are only reachable via the event. (Phase 3 TK-3.5 pattern)
- **DO resolve crypto secrets with hex-first decode, then base64 fallback, then derive final 256-bit AES key via HKDF-SHA256.** The approved .env.example §6 contract uses `openssl rand -hex 32` (→ 64 hex chars = 32 bytes) for `TOKEN_ENCRYPT_SECRET` and `openssl rand -hex 16` (→ 32 hex chars = 16 bytes) for `TOKEN_ENCRYPT_SALT`. Use `HexFormat.of().parseHex()` first (requires all chars in [0-9a-fA-F] and even length), then fall back to `Base64.getDecoder().decode()`. Final AES key = RFC-5869 HKDF-SHA256 Extract+HMACExpand over ikm=salt=info_label=`"tidymail/oauth-token-aes-256-gcm/v1"`. Do NOT rely on JEP-329 `HKDFParameterSpec` (javax.crypto.spec) under Homebrew OpenJDK 21 through Maven compiler — classpath visibility is broken; implement Extract+Expand manually with standard `Mac.getInstance("HmacSHA256")` (~40 lines, mathematically identical, JDK 8+ portable). Preserve backward-compat: when ONLY old single-var `AES_256_KEY` base64 is present, use it as IKM directly with salt=16 zero-bytes and legacy aes_key_id label `v1`. (Phase 3 crypto alignment to approved .env.example + compile fix)
- **DO prefer native Spring `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` / `SPRING_JPA_HIBERNATE_DDL_AUTO` env var names over custom `${DATABASE_*}` placeholders in application.properties — and keep them in sync with docker-compose.yml `POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB` values.** Spring Boot relaxed-binding resolution treats SPRING_* vars as native property overrides, which ALWAYS take precedence over custom `${DATABASE_USER:tidymail}` placeholders in application.properties. So a user copying an old .env.example with `SPRING_DATASOURCE_USERNAME=postgres` + `SPRING_JPA_HIBERNATE_DDL_AUTO=update` will silently trump the tidy defaults — causing "password auth failed for user postgres" against a docker-compose DB that only created role `tidymail`, AND enabling ddl-auto=update which issues OID ALTER TABLE side effects on BYTEA columns. The canonical fix is to put docker-compose-matching SPRING_* values into .env.example directly (tidymail / tidymaildev / validate) so users copy the correct set on first cp. (Phase 3 B16 root cause)
- **DO run on Colima (macOS Docker runtime) with `--disk ≥ 60 GiB` when two independent docker-compose stacks (e.g., LibreChat parallel envs with vector DBs + TidyMail Postgres) share one VM.** The default `colima start --disk 20` 20 GiB disk fills silently after ~50 docker images across duplicate envs + build cache; symptoms are non-deterministic: initdb `No space left on device` → Postgres container crash loop → JDBC `Connection refused` (user sees DB down but host disk shows plenty free — issue is inside VM not on host). Diagnose with `colima list` (shows DISK column) and `docker system df` inside VM. Fix = prune THEN grow: (1) `docker image prune -af` (removes unref/dangling images) + `docker builder prune -af` (removes build cache), (2) `colima stop` + `colima start --disk N` with N > current (colima emits warning and IGNORES shrink, allows grow without warning). Host disk MUST be checked first with `df -h /` before growing the VM. Containers with `unless-stopped` restart policy (default from `docker compose up -d`) auto-recover within ~30 s after VM returns from grow — no manual docker compose re-up needed. (Phase 3 B15)
- **DO add `spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true` globally when using Hibernate 6 with PostgreSQLDialect and `@Lob byte[]` fields over BYTEA columns.** Without this flag, Hibernate defaults `@Lob byte[]` → PostgreSQL OID/pg_largeobject routing (lo_create/lo_import). When Flyway DDL declares the column as plain `BYTEA`, any accidental ddl-auto=update (or even validate's constraint drop/re-create pass) emits `alter column … set data type oid` which fails (BYTEA→OID cast is not implicit in Postgres). The flag forces Hibernate to use plain `setBinaryStream`/`getBytes` JDBC calls over BYTEA, which matches Flyway DDL exactly and avoids pg_largeobject table creation entirely. Flag is no-op on H2 and other dialects. (Phase 3 B16 class-B fix)
- **DO prefer `@Component + @Value("${UPPER_SNAKE_VAR:default}")` over `@ConfigurationProperties(prefix=…)` whenever the canonical env-var contract in `.env.example` uses flat `UPPER_SNAKE` names WITHOUT an `APP_` prefix.** Spring Boot relaxed-binding maps `@ConfigurationProperties(prefix="app.sync.gmail")` class field `apiConcurrency` → env var `APP_SYNC_GMAIL_API_CONCURRENCY`. If the user sets plain `SYNC_GMAIL_API_CONCURRENCY=5` per the .env.example docs, Spring silently falls back to the @ConfigurationProperties default and ignores the user's override (no error, silent drift). `@Value("${SYNC_GMAIL_API_CONCURRENCY:5}")` reads the exact documented name and avoids double-declaration. Bonus: `@Value` fields work on component classes in any package without needing `@ConfigurationPropertiesScan`, though keeping the scan annotation is harmless and useful for future phases. (Phase 4 — D-4.3)
- **DO use `public static` visibility on provider-internal helper methods (like `GmailMailService.toMailMessage` and `extractDomain`) when the Java project convention places `*Test.java` files in the **parent package `com.tidymail`** not the same sub-package as the implementation (i.e. tests in `com.tidymail`, impl in `com.tidymail.mail.gmail`).** Package-private (`static`) visibility across different packages → javac refuses compile. Alternative: move test classes into matching sub-packages (`src/test/java/com/tidymail/mail/gmail/Phase4GmailProviderTest.java`), which requires per-feature test package restructuring. For single-project repos, promoting helpers to `public static` is the lower-friction choice — any downstream caller can still choose to use them, and the exported-API boundary is clearer. (Phase 4 compile gate)
- **In Gmail Java SDK v1, `users.messages.list().setFormat("METADATA")` DOES NOT EXIST — the `List` class only has `setMaxResults(Long)`, `setPageToken(String)`, `setIncludeSpamTrash(Boolean)`, etc. Format and metadataHeaders only apply to individual `messages.get(id)` and `threads.get(id)` calls.** The list endpoint returns slim `{id, threadId}` only; to get headers you must re-fetch each message via `get(id, format=METADATA + metadataHeaders=WHITELIST)`. This costs 1 list + N gets per page (101 calls for a 100-message page); mitigate with concurrency = GmailRateLimiter semaphore permits. (Phase 4 — D-4.4, compile gate fix #3)
- **Gmail `List.setMaxResults` takes `java.lang.Long`, not `int`.** If your call site has an `int` parameter (e.g., listMessages method takes `int maxResults` per MailProvider interface), wrap with `Long.valueOf(maxResults)` before passing to the SDK. The SDK was auto-generated from a discovery doc that uses uint64 throughout and maps unsigned to Long in Java. (Phase 4 compile gate fix #4)
- **`extractDomain` helper must reject empty local-part emails.** The guard for "@" position should be `at <= 0` on the `@` found check, not `at < 0`. Otherwise `"@nodomainprefix.com"` (at == 0, empty local-part, violates RFC5322 addr-spec) passes and returns a valid-looking domain, causing Phase 6 sender-aggregation to group malformed entries under a phantom domain bucket. Same guard check on both the "Name <addr>" parse branch and the plain "addr" branch. (Phase 4 — B18 / D-4.8)
- **When validating bounded semaphore concurrency inside `@SpringBootTest`, don't fight the `@Component` bean if it's initialized from a single shared `GmailSyncConfigProperties` with `@Value` defaults.** The bean uses `private Semaphore semaphore` initialized in `@PostConstruct`; to reconfigure to concurrency=3 for a tracer you'd need reflection + a `@TestConfiguration` override. Cheaper and equally correct: validate the semaphore bounded-concurrency pattern directly in pure Java `Semaphore(N) + tasks + AtomicInteger(maxObserved)` test, and separately call `GmailSyncConfigProperties.validate()` directly on a fresh instance with the bounds edge values (concurrency=0 → ISE, concurrency=21 → ISE, delay=-1 → ISE). Pattern coverage + bounds coverage is sufficient when the wrapper class's only logic is `acquire/try/finally release + sleep`. (Phase 4 — B17 / D-4.9)
- **"your-dummy-*" prefix convention splits two independent `isConfigured()` readers cleanly.** In tests without live Google HTTP, we need `GoogleOAuthConfigProperties.isConfigured() == true` (so GmailClientFactory proceeds past its early guard) but `OAuthProviderAvailability.isGoogleConfigured() == false` (so SecurityFilterChain's conditional bean skips `ClientRegistrations.fromOidcIssuerLocation("https://accounts.google.com")` which would synchronously do OIDC discovery HTTP and fail offline with `UnknownHostException: accounts.google.com` → context load failure). Using values starting with "your-" satisfies both because `GoogleOAuthConfigProperties.isConfigured()` only checks non-blank, while `OAuthProviderAvailability.isGoogleConfigured()` uses the stronger "does not start with `your-` AND does not equal `skipped`" check from the login-button placeholder convention. This "your-" differential is the minimal-invasive way to split the two readers without editing SecurityConfig. (Phase 4 T4(c) — D-4.3 mitigation)
- **Hibernate 6.6 Postgres @Lob byte[] SchemaValidator resolution: B19 CLASS-B ROOT CAUSE.** On Hibernate 6.6.53, `spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true` NO LONGER resolves the `@Lob byte[]` → PostgreSQLDialect OID routing issue by itself. SchemaValidator reports "found [bytea (Types#BINARY)] but expecting [oid (Types#BLOB)]" even with the flag set. Property `hibernate.type.preferred_long_length_lob_code=LONGVARBINARY` is also ineffective on real Postgres PG JDBC driver (works on H2 but not PG). None of `@JdbcTypeCode(BINARY)`, `@JdbcTypeCode(LONGVARBINARY)`, or `@DialectOverride.Column` work simultaneously on both dialects: BINARY passes PG but breaks H2; LONGVARBINARY passes PG but breaks H2; DialectOverride.Column DOES NOT EXIST as of Hibernate 6.6.53 (only ColumnDefault, Formula, GeneratedColumn, etc. DialectOverride$ inner classes present). **THE ONLY PORTABLE RESOLUTION (verified):** Use `@Lob + @JdbcTypeCode(SqlTypes.LONGVARBINARY)` on entity encrypted fields (passes Postgres validator: LONGVARBINARY → BYTEA, matches Flyway PG V1 BYTEA columns). Simultaneously EDIT H2 Flyway override V1__create_schema.sql to declare encrypted_*_token columns as `VARBINARY(65536)` instead of `BINARY LARGE OBJECT`. H2 reports VARBINARY(65536) → SchemaValidator LONGVARBINARY → MATCH. Verified in cross-suite Phase3PersistenceTest 4/4 green after H2 V1 edit, and real-PG V-6.6 jar boot 0 errors. Add explanatory comments to the V1__create_schema.sql H2 override file AND UserAccount entity documenting the asymmetry. Cap 64 KiB = 131,072× headroom vs realistic encrypted OAuth blobs (~500 bytes). Reusable pattern for any future @Lob byte[] on Postgres+H2 projects. (Phase 6 V-4.7 recurrence → D-6.B19)
- **MailProvider interface dispatch to multiple concurrent impls: use `Map<String, MailProvider>` (Spring auto-injected as bean-name→bean) with a `resolveProvider(account)` dispatcher method.** A controller that injects multiple provider implementations via separate `@Qualifier` fields would have to duplicate logic for each provider and add a new injection site for the Outlook provider work. Instead, inject `private final Map<String, MailProvider> mailProviderMap` (Spring's default bean-naming convention: GmailMailService → gmailMailService, OutlookMailService → outlookMailService); `resolveProvider` does a `switch(account.getProvider()) { case "google" -> "gmailMailService"; case "microsoft" -> "outlookMailService"; }` map lookup; returns `BAD_REQUEST / SERVICE_UNAVAILABLE ResponseStatusException` respectively when missing or not loaded. Single code path works for the Gmail-only baseline and survives Outlook addition with zero controller edits. (Phase 6 D-6.6)
- **SenderStats upsert on re-sync: LATEST-WINS snapshot (not increment-sum).** When `SenderStatsPersistService.upsertSnapshot` processes SenderStatDelta values from a fresh aggregate pass, overwrite totalCount/unreadCount/oldest/newest/hasListUnsubscribe*/replyCount with the delta's values (NOT additively increment). Why: SenderAggregator.aggregate() already sees ALL messages from a scan window (up to 500 in POST /_sync 5-page concat) and computes groupBy totals globally. Multiple sequential calls correspond to different sync passes (not sub-pages of a scan). If callers later decide to merge sub-page aggregates as a streaming optimization, they can either (a) concat messages first then aggregate once, or (b) write a separate merge function handling max(newest)/min(oldest)/OR for booleans etc. For now snapshot semantics keep the persist layer simple and explicit; T6(b) tracer enforces latest-wins contract. (Phase 6 D-6.4)
- **Reply Zero personal-sender filter: ESP blocklist suffix match + SLD-match-To TWO-GATE.** A bulkMarketingScore == 0 is insufficient to trust "this thread is personal". Phishing domains often send traffic without List-Unsubscribe/Precedence headers and would otherwise show up with replyCount == 0 in a reply-zero queue, yielding false personal-urgent items. GATE 1: Suffix-match sender domain against 15 ESP domains. Use helper `(domain.equals(esp) || domain.endsWith("." + esp))` to match both "sendgrid.net" and "e.sender.retailer.sendgrid.net". GATE 2: SLD (second-level domain = penultimate dot-label pair of from-domain, e.g. corp.work.com → SLD=work.com) MUST match the SLD of AT LEAST one address in the thread's To: header. Reasoning: if random phish writes to me alone on a random domain, no To-address SLD matches the sender SLD → correctly excluded. If coworker@work.com writes to multiple other coworkers@work.com (plus me@work.com), SLD work.com matches → correctly included. Known blind spot: cold B2B one-off outreach from bd@startup.io to only me@example.com (correctly excluded as "bulk/phishy adjacent" behavior — acceptable false negative). Combined reply-zero gate also uses (a) age ≥ olderThanDays clamp (≥1 default), (b) deep getThreadMetadata dive <=50 max calls cap (R6.3 rate-limit guard), (c) dive confirms me.getEmail() NOT present via Rfc5322Util.extractAddrSpec equalsIgnoreCase in thread summary participants → meaning user never sent a reply in that thread. (Phase 6 D-6.3)
- **Cursor opaque pagination: 3-part `googlePageToken|localOffset|localSort` base64url-without-padding encode/decode.** CursorParts record exposes all three. `CursorUtil.decode` throws IllegalArgumentException for ANY of: invalid base64 URL chars, fewer/more than 3 pipe parts, Long.parseLong of part 1 fails. Controller catches IAE and maps to HTTP 400 `bad_cursor`. Sort is part of cursor so R6.4 guard "if cursor present and sort != countDesc → 400 sort_only_on_first_page" can be enforced by comparing cursor's stored sort to current request's query-param sort. Empty-string googlePageToken correctly encodes/decodes for first-page scenarios (cursor supported but no Gmail token yet). T6_a_05 validates 50 random token+offset+sort roundtrip + empty-token edge + 2 invalid (garbled base64 / 2 part content) → both throw. (Phase 6 D-6.5)
- **Flyway DDL migrations naming: `V{N}__descriptive-name.sql` exactly.** When writing subsequent migrations after V1, do NOT use a prefix that matches the main migration's description (e.g. don't name "V1__add_reply_count.sql" because Flyway version is numeric prefix before `__`; V2 means second migration regardless of description length). Do NOT use `IF NOT EXISTS` syntax for H2 indexes on versions older than 2.3; plain `CREATE INDEX` works on H2 mem DBs because tests start fresh every run. Postgres production DDL CAN use `CREATE INDEX IF NOT EXISTS` for idempotency on re-runs (supported since PG 9.5 and used for V2 here). Dual-dialect asymmetry for indexes is fine: both variants produce equivalent index objects. (Phase 6 — B19 DDL alignment side lessons learned during writing PG/H2 V2 migrations.)
- **MailMessage record field additions are source-compatible, not binary-compatible if caller compiled against old field-count.** Safe pattern: only new record fields append as LAST positional constructor argument; all existing accessor methods unchanged. Update ALL call sites that use positional constructors (not accessor reads) to append the new field with a safe default (e.g. MailMessage.labelIds append Set.of() for all 5-param → 6-param edits in existing Phase4GmailProviderTest; GmailMailService.toMailMessage returns a proper 6-param constructor wrapping real labelIds). After edits, run full test suite to catch any remaining 5-param constructors — Phase4 suite 5/5 green guarantees no missed callsites. (Phase 6 D-6.1 / TK-6.2 execution lesson.)
- **findByEmailAndProvider repository method needed before Phase 6.** Phase 3's `UserAccountRepository.find…` family only contained `findByProviderAndProviderAccountId` (used by OAuth2 success event listener). The sender API controller needs to look up a `UserAccount` entity from the OAuth2 principal's email claim + authorizedClientRegistrationId. Add `Optional<UserAccount> findByEmailAndProvider(String email, String provider)` to the repository interface. Spring Data derives the query correctly; composite unique index on `uq_user_accounts_email_provider` ensures fast plan. (Phase 6 D-6.7.)
- **B20: Ambiguous GET /dashboard mapping — DashboardPageController vs pre-existing LoginController.** Multiple `@GetMapping("/dashboard")` handlers → Spring MVC `IllegalStateException: Ambiguous handler methods`. Fix: 5-way controller responsibility split — HomeController=root "/" redirect, LoginController=page shells (/dashboard /login /reply-zero with OAuth2 principal→UserAccount lookup), FragmentController=htmx fragments (/senders-frag /reply-zero-frag), BulkActionApiController=REST POST mutations, SenderAggregationController=REST GET JSON. Each mapping owned by exactly one controller.
- **B21: Ambiguous GET "/" root mapping — LoginController.root() vs HomeController.root().** Phase 2 added LoginController.root() but never removed HomeController's identical handler. Fix: HomeController owns single canonical "/" redirect; LoginController owns /login + /dashboard shells only.
- **B22: @RegisteredOAuth2AuthorizedClient(required=true) eager ClientRegistration lookup fails offline unit tests.** D-4.3 "your-" convention skips ClientRegistration load in SecurityFilterChain; @RegisteredOAuth2AuthorizedClient parameter resolver needs findByRegistrationId("google") → returns null → IllegalArgumentException. Fix D-7.6: LoginController.dashboard accepts `Authentication authentication` → cast `OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication` → read `token.getAuthorizedClientRegistrationId()` directly. RegistrationId is already in the Authentication token (set at OAuth login success); zero ClientRegistrationRepository dependency. Offline MockMvc tests work with SecurityMockMvcRequestPostProcessors.authentication(oauth2Token) fabricated directly.
- **B23: UserAction target_id_json NOT NULL with JPA new UserAction() — Hibernate sends explicit NULL overriding DDL DEFAULT.** V3 DDL declares `target_id_json TEXT NOT NULL DEFAULT '{}'` but JPA INSERT enumerates all columns; unset fields sent as SQL NULL → Postgres NOT NULL violation. Fix D-7.3 dual-safety: (1) Entity declaration-site defaults `private String targetIdJson = "{}"; private Integer affectedCount = 0; private String targetKind = "SENDER_GROUP";`; (2) @PrePersist guard overwrites nulls; (3) V3 DDL NOT NULL DEFAULT retained for raw SQL. Three layers converge on same safe defaults.
- **B24: Phase3 flyway count assertion 2→3 after V3 migration added.** Phase3PersistenceTest hardcoded `migrationsApplied == 2`; Phase 7 V3 made it 3 → assertion fail. Fix: update assertion from `==2` → `==3` with inline comment noting future migrations will increment.
- **B25: T7(b) PersistenceTests @Transactional missing → TransactionRequiredException on em.flush()/clear().** Without @Transactional on test method, Spring Data repo.save() commits its own short-lived tx then closes EM; subsequent em.clear() has no active persistence context. Fix: annotate @Nested T7b_PersistenceTests class with @Transactional (rolls back per test method).
- **D-7.1 5-way controller responsibility split (B20/B21 resolution).** HomeController="/" redirect only; LoginController=page shells /login /dashboard /reply-zero (OAuth principal→UserAccount entity lookup); FragmentController=htmx HTML fragments /senders-frag /reply-zero-frag (pure Thymeleaf render); BulkActionApiController=REST POST /api/actions/* mutations (write-before-mutate + cap1000); SenderAggregationController=REST GET /api/senders /api/reply-zero JSON. Single-responsibility, no ambiguous mappings, easy per-scope testing.
- **D-7.2 Undo write-before-mutate ordering (A4 pitfall).** UndoService.createActionRow persists UserAction with fully-populated inverseActionData FIRST → THEN calls MailProvider.batchModify → THEN returns htmx swap undo banner. Gmail batchModify label-delta operations are idempotent (applying REMOVE(INBOX) twice on archived messages = no-op), so inverse replay on undo corrects both half-succeeded and fully-succeeded batches. Undo always available regardless of provider partial-failure state.
- **D-7.3 UserAction 4 new columns triple-layer dual-safety (B23 resolution).** Entity declaration defaults + @PrePersist null guards + DDL NOT NULL DEFAULT all three converge on same values. Catches JPA persists, explicit null setters, and raw psql inserts respectively.
- **D-7.4 cap1000 defense-in-depth (A3 pitfall no scope drift).** One single `UndoService.cap1000(ids)` static method called TWICE: (a) BulkActionApiController BEFORE save → returns 400 for >1000; (b) UndoService immediately BEFORE provider.batchModify → truncates with overflow flag if controller bypassed. Gmail 1000-ids-per-batch API hard limit never exceeded; DB affected_count never overstates actual mutation. T7_c_03 ArgumentCaptor invariant verifies both calls see identical 5/5 size (no truncation drift between layers).
- **D-7.5 Empty-state @Order(1) BEFORE populated cases (A7 pitfall).** T7_d_02 annotated @Order(1) so it runs first in its @Nested class. @Transactional rollback between methods provides actual isolation; @Order(1) is a belt-and-suspenders ordering guarantee that makes the A7 invariant self-documenting when reading test source. No DirtiesContext overhead.
- **D-7.6 LoginController.dashboard Authentication param replaces @RegisteredOAuth2AuthorizedClient (B22 resolution).** OAuth2AuthenticationToken.getAuthorizedClientRegistrationId() reads registrationId directly from the token set at OAuth login time. No ClientRegistrationRepository lookup at all. Offline tests work (D-4.3 "your-" ClientRegistration-skip convention preserved). Defensive instanceof guard added for non-OAuth2 auth types.

### Phase 8 (2026-08-07)

- **B26: Thymeleaf Optional<SyncProgress> model-attribute unwrap (EL1008E).** Root cause: `FragmentController.syncProgressFragment` passed `Optional<SyncProgress>` returned by repository finder directly into Thymeleaf model as `"progress"`. Template expression `progress.status != 'RUNNING'` tried to dereference `.status` on `java.util.Optional` proxy object in SpEL → `EL1008E: Property or field 'status' cannot be found on object of type 'java.util.Optional'`. Fix: chain `.orElse(null)` when unwrapping repository Optional result before adding to model (entity value or null, never the Optional wrapper). **Lesson: NEVER pass Optional<?> objects directly into Thymeleaf model attributes; always unwrap to entity or null first in the Controller layer.** SpEL cannot reliably traverse Optional.get() across dialects.
- **B27: sync-progress-frag DOCTYPE/html/body wrapper + unquoted enum tokens + #temporals on Instant. Three root causes.** (i) Wrapping fragment with full `<!DOCTYPE html><html><body>` caused innerHTML swap inside dashboard slot to inject nested html/body tags into layout — invalid DOM, breaks CSS selectors. (ii) `th:if="${progress.status == RUNNING}"` compared against bare unresolvable variable token, not string literal `'RUNNING'`. Condition always false. (iii) `#temporals.format(Instant)` threw template render exception — Thymeleaf temporals cannot format `java.time.Instant` directly (expects LocalDateTime/ZonedDateTime with TZ). Fixes: (a) strip wrapper to single outer `<div xmlns:th=…>` with inner divs only — partial markup for innerHTML swap. (b) quote enum statuses in single quotes: `'RUNNING'/'SUCCESS'/'FAILED'`. (c) simplify SUCCESS UI to "Synced X messages" without timestamp to skip Instant formatting entirely. **Lessons: (1) HTMX fragments for innerHTML swap MUST be partial markup ONLY — never nested HTML/BODY/DOCTYPE. (2) Thymeleaf literal strings always need single quotes; bare tokens = variable references. (3) Avoid #temporals on java.time.Instant → use LocalDateTime in entity OR skip date formatting for MVP.**
- **B28: Dashboard hx-trigger substring assertion mismatch. Copy-paste, don't paraphrase.** Root cause: `dashboard.html` slot uses actual `hx-trigger="load, every 15s"` (2-event HTMX chain: on-load initial fetch + re-poll every 15s). Test asserted only `"hx-trigger=\"every 15s\""` — missing the `"load, "` prefix → contains() returned false. Fix: update assertion to exact value `"load, every 15s"` substring. **Lesson: HTMX combined trigger events serialize with ", " separator. Always write assertions by copy-pasting the actual attribute string from template source rather than paraphrasing. If paraphrasing, assert on BOTH component substrings individually.**
- **B29: Postgres docker compose port 5432 vs manual smoke test typed 5433. Copy from compose.yml, never re-type.** Root cause: V-8.5 smoke run command had `SPRING_DATASOURCE_URL` pointing at port 5433 (copy-paste error from unrelated prior stack using custom port), while project `docker-compose.yml` exposes standard 5432. JDBC Connection refused. Fix: use port 5432 matching compose.yml. **Lesson: When V-smokes involve real Docker Postgres, first copy the port number DIRECTLY from docker-compose.yml `ports:` field. Never re-type a port number from memory; compose file is the single source of truth.**
- **D-8.1 SyncProgress finder name `findTopByAccountIdAndJobTypeOrderByIdDesc`.** Single composite index covers WHERE + ORDER BY id DESC; avoids custom JPQL; Spring Data derived query compiles once.
- **D-8.2 IncrementalSyncJob `fixedRate=5min` `initialDelay=60s`.** fixedRate (not fixedDelay) ensures clock-aligned ticks even with sync drift; initialDelay gives Postgres + Hikari pool time to stabilize before first tick.
- **D-8.3 `ConcurrentHashMap<Long, Semaphore(1)>` per-account non-overlap gate.** Synchronized map + Semaphore: (a) per-account granularity (no global lock), (b) survives multi-thread pools, (c) finally { release } always releases even on Exception → save FAILED progress. Tradeoff: in-memory only — cluster deployment would need distributed lock (Redis/PG advisory), but out of scope for personal single-instance MVP.
- **D-8.4 SenderStatsVacuumJob 90-day stale window with 2-part gate.** Only accounts with BOTH `lastLoginAt != null` AND `lastLoginAt < 90 days-ago` qualify. Never-logged-in (`lastLoginAt = null`) accounts preserved — could be freshly added OAuth where user hasn't completed browser login yet (would lose encryptedRefreshToken if vacuumed). Tradeoff: tiny amount of dead OAuth-start-but-not-finished data acceptable.
- **D-8.5 Mobile collapse sender-table rows to card layout via `td::before content: attr(data-label)`.** Card layout (not horizontal scroll) wins for mobile readability. Zero template changes — Thymeleaf `<td>` already has `data-label="Count/Unread/Age"` attrs. CSS rule activates at 375px breakpoint.
- **D-8.6 Touch target min-height: 44px, min-width: 44px (Apple HIG / WCAG 2.5.5) at `@media (max-width: 375px)`.** Applied to .btn, .undo-btn, buttons inside .rz-card and .sender-bulk-actions. Pure CSS — no JS sizing logic. 375px = iPhone SE baseline.
- **D-8.7 DEPLOY.md scope = localhost documentation ONLY (no cloud pushes).** User explicitly wants manual localhost browser dogfood testing FIRST. cloudflared tunnel documented as §3 manual run with blockquote warning — no auto-install/execute. User decides when/if to expose.

**DO NOT (explicit non-goals for V2):**

- Do NOT build 3 surfaces before any one works
- Do NOT auto-decide items on a page called "Review" (buttons labeled "Review" must navigate, never decide)
- Do NOT promise Gmail rules/labels/attachments/filters UI before API capability is validated
- Do NOT add React SPA build pipeline
- Do NOT add AI/LLM features in this iteration

## Accumulated Context

### Roadmap Evolution

- Phase 14 added: UI Fidelity And Route Recovery
- Phase 15 added: Implement the pending workspace cleanup navigation and classification todo using the authoritative tidymail-ux-rehaul-draft.design contract
- Phase 16 added: Reconcile UI with mocks using the Phase 15 walkthrough UAT capture.
- Phase 17 added: Fix Phase 15/16 authenticated UI blocker follow-ups from the latest 2026-08-13 continuation feedback.
- v1.2 opened (Phases 18–21): Test Foundation → Freshness → Classification → Architectural Remediation.
- v1.2 expanded to Phases 18–22 on 2026-08-13 from functionality/performance feedback:
  - Phase 18 gained TEST-05 (large PII-stripped synthetic dataset, ≥10,000 messages).
  - Phase 19 renamed "Mailbox Freshness & First-Run Onboarding" and gained FRESH-06..10 (cold-start fast render, dedicated background-sync page, no GB cap, honest Inbox-vs-All-Mail unread, metadata-extraction audit).
  - Phase 20 gained CLASS-06 (user re-categorization persists across progressive ingestion).
  - Phase 22 added: "Gmail Rules & Bulk Action Feedback" — SCOPE-01 (add `gmail.settings.basic` + `contacts.readonly`), RULE-01/02 (Gmail-side filters/labels + open-in-Gmail), PERF-01/02 (live progress/ETA + durable background execution).

### Closed UI Todos

- `.planning/todos/completed/2026-08-11-remove-cleanup-page-status-clutter.md` was resolved by Phase 16.
- `.planning/todos/completed/2026-08-11-refine-workspace-cleanup-navigation-and-classification.md` was resolved by Phases 16 and 17 and accepted by the user on 2026-08-13.

---

## Next Steps

1. Run `gsd-plan-phase 19` to plan Gmail History API freshness and first-run onboarding.
2. Keep the Phase 18 PostgreSQL journey suite green while Phase 19 changes sync behavior.
3. Keep public deployment blocked until the v1.2 freshness/classification phases define and clear their live gates.
4. Before Phase 20 execution, confirm the chosen embedded ML library (Smile/Tribuo/Weka-class) makes zero network model calls, per DECISIONS.md §10.
5. Before Phase 22 execution, the maintainer must add `gmail.settings.basic` and `contacts.readonly` in Google Cloud Console and re-consent (DECISIONS.md §12); the app must degrade gracefully until then.

## Blockers / Concerns

- Gate C lacks a genuinely eligible safe and successful real unsubscribe alternative.
- Phase 22 depends on a maintainer-side Google Cloud Console scope change + re-consent (`gmail.settings.basic`, `contacts.readonly`); scope-dependent features stay hidden until done.
- Data freshness, classification accuracy, first-run onboarding, and functional behavior are scoped across Phases 19–22.
- No public deployment or tunnel was attempted.

## Session Continuity

Last session: 2026-08-14T14:55:24.210Z
Stopped at: Phase 18 complete; ready to plan Phase 19
Resume file: None
Known Phase 18 plan inventory: 6 total; 6 complete

## Performance Metrics

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 18 P01 | 6 min | 2 tasks | 4 files |
| Phase 18 P02 | 8 min | 3 tasks | 3 files |
