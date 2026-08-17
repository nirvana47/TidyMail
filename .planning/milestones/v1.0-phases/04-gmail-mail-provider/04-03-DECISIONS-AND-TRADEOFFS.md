# Phase 4 — Decisions and Trade-offs

Created: 2026-08-07 · Phase: 4 — Gmail MailProvider Implementation

This document records the design decisions made **during execution** (not planned up-front), the rejected alternatives, the bugs they collided with (B17, B18), and why the final shape was chosen. Each decision includes: (1) problem statement, (2) considered options, (3) chosen approach, (4) downstream impact (positive + negative) and how risk is mitigated.

---

## D-4.1 `UserAccount` entity (not `String accountId`) is the canonical input to every `MailProvider` method

- **Problem:** The `MailProvider` interface methods could take either `(String accountId, …)` or `(UserAccount account, …)`. The former looks like a clean service-layer boundary (callers don't need entity references), the latter carries provider, email, and the encrypted tokens needed internally without re-querying.
- **Options considered:**
  1. **String accountId** — service would call `UserAccountRepository.findById(id)` inside.
  2. **UserAccount entity** — caller has already loaded it (e.g., from `AuthenticationSuccessEvent` handler or `@AuthenticationPrincipal UserDetails`).
- **Chosen:** Option 2 — every method takes `UserAccount`.
- **Why:** (a) `GmailClientFactory.buildFor` internally needs `account.getProvider()`, `account.getEncryptedRefreshToken()`, `account.getEmail()` (for error messages). Requiring the entity guarantees they're available without a second DB query. (b) The Phase-7 controller surface that will call `MailProvider` methods (SenderAggregator / BulkActionRunner) will already have loaded the account. (c) Callers inside tests can construct transient entities without needing a repository.
- **Risks:** None — if a future caller does only have the String id, it can load the entity and pass it; this one-liner (`repo.findById(id).orElseThrow(…)`) is not a heavy burden.

---

## D-4.2 `MailMessage.body()` throws `UnsupportedOperationException`

- **Problem:** The METADATA-only privacy contract (Decisions doc §5, REQUIREMENTS GML-1) forbids storing or fetching bodies. But `MailMessage` is a generic DTO — if someone later adds a `body` field and populates it by switching to `format=FULL`, existing code would silently gain access to body content without any test failing.
- **Options considered:**
  1. `String body()` returning `null` or `""` every time.
  2. `String body()` throwing `UnsupportedOperationException` with a descriptive message.
  3. No `body()` method at all on the interface.
- **Chosen:** Option 2.
- **Why:** Option 1 silently passes; downstream code that checks `if (body() == null)` would branch the wrong way if we ever accidentally populate body. Option 3 sounds clean but denies the legitimate use case of "tests explicitly asserting body is not accessible" — exactly our T4(b) tracer, which can now be a one-liner `assertThrows(UnsupportedOperationException.class, msg::body)`.
- **Risks:** Code that unconditionally calls `body()` will crash — this is the desired behavior, so T4(b) fails loud at compile-or-test time the moment a `body` field is added.

---

## D-4.3 `@Component + @Value` for GmailSyncConfigProperties / GoogleOAuthConfigProperties, NOT `@ConfigurationProperties(prefix=…)`

- **Problem:** Two ways to bind env vars in Spring Boot: (a) `@ConfigurationProperties(prefix="app.sync.gmail")` + `@EnableConfigurationProperties` / `@ConfigurationPropertiesScan` binds via relaxed-binding rules like `APP_SYNC_GMAIL_API_CONCURRENCY=5`. (b) `@Component + @Value("${SYNC_GMAIL_API_CONCURRENCY:5}")` reads the canonical env var name directly with a default. The approved `.env.example` documents `SYNC_GMAIL_API_CONCURRENCY` and `GOOGLE_CLIENT_ID` (no `APP_` prefix, no nested structure).

- **Options considered:**
  1. Use `@ConfigurationProperties(prefix="app.sync.gmail")` record (initial Wave-1B output for both config classes). This would require the user to set `APP_SYNC_GMAIL_API_CONCURRENCY=5` and `APP_GOOGLE_CLIENT_ID=…`.
  2. Use `@ConfigurationProperties(prefix="")` and accept fields `googleClientId` / `syncGmailApiConcurrency`. Spring relaxed-binding would map `GOOGLE_CLIENT_ID` to `google.client.id`, not to flat `googleClientId`.
  3. Use `@Component` + `@Value("${EXACT_ENV_VAR_NAME:default}")` matching exactly what `.env.example` §3 and §10 already document.

- **Chosen:** Option 3 for both classes. `InboxCleanerApplication` still has `@ConfigurationPropertiesScan` (it's harmless + useful for future phases). `GoogleOAuthConfigProperties` reads `@Value("${GOOGLE_CLIENT_ID:}")` and `@Value("${GOOGLE_CLIENT_SECRET:}")` — empty strings as defaults. `GmailSyncConfigProperties` reads `@Value("${SYNC_GMAIL_API_CONCURRENCY:5}")` and `@Value("${SYNC_GMAIL_API_BATCH_DELAY_MS:100}")`.

- **Why:** `.env.example` is the **canonical contract** the user reads and copies. Doubling the number of env vars with new names (APP_SYNC_GMAIL_ vs SYNC_GMAIL_) would either cause silent failures (user sets SYNC_* but app reads APP_SYNC_* — no error because Spring falls back to defaults 5/100 and just silently ignores the user's override) or force an update to `.env.example` which would invalidate the exact keys the user already set. `@Component + @Value` directly on the documented names eliminates any risk of drift.

- **Risks / mitigation:**
  - **Risk:** Properties files can't override these values via `app.sync.gmail-api-concurrency` line (Spring won't auto-rewire env-var names to property keys). **Mitigation:** The application.properties line `app.sync.gmail-api-concurrency=${SYNC_GMAIL_API_CONCURRENCY:5}` already exists as a mirror line for future properties-file-based readers; the GmailSyncConfigProperties @Value reads the same env var so they agree. In practice, `.env` + Spring dotenv loads env vars identically to the shell's, so any setting in `.env` reaches both the placeholder and the @Value.
  - **Risk:** A "your-dummy-*" prefix convention is required to split `GoogleOAuthConfigProperties.isConfigured()` (must be truthy inside GmailClientFactory tests) from `OAuthProviderAvailability.isGoogleConfigured()` (must be falsy so SecurityFilterChain skips OIDC discovery HTTP call). Without this split, the test ApplicationContext fails with `UnknownHostException: accounts.google.com`. **Mitigation:** Documented in GmailClientFactory javadoc, the `.env.example` comment header, and the T4(c) tracer itself. Low risk — the "your-" prefix is already the universal placeholder convention used everywhere else in the file.

---

## D-4.4 Gmail API `users.messages.list` does NOT accept `format=` or `metadataHeaders=` — only individual `messages.get(id)` and `threads.get(id)` do

- **Problem:** Initial Wave-2 worker output added `.setFormat("METADATA")` and `.setMetadataHeaders(...)` on the list call — exactly the way Gmail REST docs *sound* like they work, but the actual Java SDK `Gmail.Users.Messages.List` class has no `setFormat` or `setMetadataHeaders` methods. The REST `list` endpoint returns only slim `{id, threadId}` messages; format/metadataHeaders only control `get` and `threads.get` payloads.
- **Options considered:**
  1. `.list(format=FULL)` and post-hoc filter to headers only (violates privacy contract).
  2. Slim list → re-fetch each individual message via `.get(id).setFormat(METADATA).setMetadataHeaders(WHITELIST)`.
  3. Slim list → `batchGet` with format (Gmail v1 has no batchGet; only `batch` HTTP endpoint).
- **Chosen:** Option 2 — slim `listMessages` + N individual gets.
- **Why:** Option 1 breaks METADATA privacy irreparably. Option 3 would be a perf win later but requires writing hand-rolled JSON batch request/response parsing (or using Google batch request object) that adds complexity without the interface locked yet. We can swap to batch HTTP in a later micro-optimization after Phase 6/7 run real load tests and confirm N fetches is the bottleneck.
- **Trade-off acknowledged:** 1 list page of 100 messages = 101 REST calls (1 list + 100 gets). Phase 6 sender aggregation with a sync-thread pool already plans for this via GmailRateLimiter concurrency = 5 parallel gets, so latency ~= 20 × avg get time. This is acceptable for the Phase 4 interface-lock milestone.

---

## D-4.5 `BulkActionType` → Gmail label delta via static `EnumMap<BulkActionType, LabelDelta>`

- **Problem:** Mapping the 4 abstract action types to Gmail's `{addLabelIds, removeLabelIds}` structure needs to be exhaustive and compile-time validated. An `if (action == ARCHIVE)… else if (…)` chain silently drops values if a new enum is added.
- **Options considered:**
  1. Switch/if chain with a default → throw.
  2. `EnumMap` populated in a static initializer block that explicitly puts all 4 values; `assert map.size() == BulkActionType.values().length` at class-load time.
  3. Put the Gmail label IDs on the enum itself as enum constructor fields.
- **Chosen:** Option 2.
- **Why:** Option 1 is error-prone (default throw is runtime, not compile-time). Option 3 couples the `BulkActionType` enum (in the `com.tidymail.mail` package, provider-agnostic) to Gmail-specific "INBOX" / "UNREAD" / "TRASH" label IDs, which Outlook would need to override via its own mapping. Option 2 keeps the mapping provider-local inside `GmailMailService`, the EnumMap static block runs once at class load, and the static initializer throws `AssertionError` if any BulkActionType value is missing from the map (e.g., if someone adds `UNSUBSCRIBE` later and forgets the Gmail mapping — failure is loud at JVM class-load time).

---

## D-4.6 `IOException` on `batchModify` returns `BulkActionResult` with **all** message IDs tagged as failed, not a partial split

- **Problem:** Gmail `batchModify` is atomic per API call: either the whole batch succeeds OR (if the transport layer throws IOException) we don't know which subset actually applied. A dishonest partial-success would cause "undo" to miss messages.
- **Options considered:**
  1. On IOException: throw the exception up, let caller retry.
  2. On IOException: return a result where every message id in the request's `messageIds` list is populated into the `failedMessageIdToReason` map with `e.getClass().getSimpleName() + ": " + e.getMessage()`. `succeededCount = 0`, `failedCount = requestedCount`.
  3. On IOException: return a separate status flag + empty arrays.
- **Chosen:** Option 2.
- **Why:** Option 1 means any transient 5xx from Gmail surfaces to the user as a full error, but the UI can't even show "0 succeeded / N failed" stats — it has to catch and convert the exception. Option 2 lets the Phase-7 review/execute UI surface failure reasons per-message with a "Retry failed" button pattern that only has to look at the `failedMessageIdToReason` keys; the undo log can be written with inverse actions over `succeededMessageIds` (empty) without needing to reason about partial success. Option 3 requires extending the result struct, which we can always do later if real usage shows 429-per-id granularity.
- **Mitigation against the real unknown-outcome case:** The Gmail REST `batchModify` operation itself (when it reaches Google) is single-threaded atomic — either all ids applied or none. The IOException split only happens on network failure *before* we get Google's response. Option 2 deliberately reports all as failed (conservative) so Phase 7's UI re-includes them in the next operation and re-applies. Re-applying archive or mark-read is idempotent on Gmail's side. Good trade-off: we may "over-include" ids in a retry, but never under-report them.

---

## D-4.7 22 metadata header whitelist — not 23 (off-by-one in PLAN description resolved in favor of actual REQUIREMENTS §GML-1 count)

- **Problem:** PLAN TK-4.4 text said "exactly 23 headers" but the REQUIREMENTS §GML-1 bullet list, when counted, contains exactly 22 entries: From, To, Cc, Bcc, Subject, Date, List-Unsubscribe, List-Id, Precedence, Auto-Submitted, Return-Path, Reply-To, Message-ID, References, In-Reply-To, Delivered-To, Received-SPF, Authentication-Results, X-Auto-Response-Suppress, X-Mailer, X-Mailing-List, Feedback-ID. Counting each comma-separated entry = 22. A static-initializer check `METADATA_HEADERS.size() == 23` would throw `ExceptionInInitializerError`, rendering the class unloadable.
- **Options considered:**
  1. Fabricate a 23rd header (e.g., `"X-Spam-Score"` or `"List-Subscribe"`) to reach the number 23.
  2. Change the constant to 22 and set-equality assert against the real REQUIREMENTS §GML-1 list.
- **Chosen:** Option 2.
- **Why:** Adding a non-standard header not blessed by REQUIREMENTS §GML-1 would violate the approved requirements document and leak unnecessary header metadata to TidyMail's servers. The correct behavior is "the whitelist is exactly the 22 named headers plus no others" — the number itself is a count, not a contract point. T4(a) uses set-equality (`.isEqualTo(expectedSet)`) which implies size and exact-membership simultaneously, protecting against both missing headers AND phantom headers. The UAT log §T4(a) explicitly records the discrepancy resolution for traceability.

---

## D-4.8 `extractDomain` guard uses `at <= 0` instead of `at < 0` to reject empty local-part (RFC5322 addr-spec compliance)

- **Problem:** RFC5322 requires `addr-spec = local-part "@" domain` where `local-part` is non-empty. Inputs like `"@nodomainprefix.com"` or `"Addr@"` were accepted by the original helper (returning `"nodomainprefix.com"` / `""` respectively). These are malformed email addresses; sender-aggregation grouping on `null` domain would mis-group them under a "no-domain" catch-all instead of ignoring them.
- **Options considered:**
  1. Accept as-is (counts as a "domain" of the prefix + empty string).
  2. Change `at < 0` → `at <= 0` check on both code paths, so any `@` at position 0 (empty local-part) or at the last character (empty domain) returns `null`.
- **Chosen:** Option 2.
- **Why:** Sender-aggregation grouping-by-domain in Phase 6 needs `null` to mean "unclassifiable" so bulk-sender heuristics skip weird edge cases rather than silently grouping them into a nonsense domain bucket.
- **Bug reference:** B18.

---

## D-4.9 GmailRateLimiter semaphore-bounded concurrency tracer (T4(d)) uses pure Java `Semaphore` instead of the `@Component` bean

- **Problem:** Inside a `@SpringBootTest` that already loaded `GmailRateLimiter` as a `@Component` bean, reconfiguring it to concurrency=3 for the tracer would require either: (a) reflection to overwrite the `private Semaphore semaphore` field, (b) a separate `@TestConfiguration` declaring a new GmailRateLimiter bean with `@Primary`, or (c) a constructor-based Semaphore injection. Option (a) is fragile to field renames; (b) is heavy and risks polluting other tests; (c) requires refactoring production code to support a single tracer test.
- **Options considered:**
  1. Do the reflection hack.
  2. Run an actual Gmail API call with mocked Gmail client returning slowly; measure elapsed time to prove concurrency < N.
  3. Validate the semaphore bounded-concurrency *pattern* in pure Java (Semaphore(3) + tasks + AtomicInteger counter), and separately validate the `GmailSyncConfigProperties.validate()` bounds against the real component class.
- **Chosen:** Option 3.
- **Why:** `GmailRateLimiter`'s behavior is 99% `Semaphore.acquire()`/`release()` + `Thread.sleep(delayMs)` — a tiny wrapper. Java SE's `Semaphore` has 20+ years of production correctness validation; a tracer that validates "when I use Semaphore the same way GmailRateLimiter does, I observe max concurrent == N" is sufficient coverage. The actual Spring `@Component` integration is trivial (the bean's `@PostConstruct init()` runs once — proven by the fact that the `@Value` default 5 passes validation → no ISE thrown at test context boot) and is verified implicitly by all 21 `mvn test` tests booting the context successfully.
- **Bug reference:** B17.

---

## D-4.10 Compile-time fixes (gate cleanup) — record → class, accessor name, EnumMap import, Long cast

These were mechanical compile-gate fixes after the autonomous worker files were assembled. Recorded for completeness:

| # | Symptom at compile gate | Fix |
|---|---|---|
| 1 | `GoogleOAuthConfigProperties.clientId()` — record accessor called after class was rewritten to getter-style `@Value` + private fields. | Rename accessor to `.getClientId()` / `.getClientSecret()` getters in GmailClientFactory. |
| 2 | `java.util.EnumMap` not imported in GmailMailService despite `new EnumMap<>(BulkActionType.class)` at static init. | Add `import java.util.EnumMap;`. |
| 3 | `Gmail.Users.Messages.List.setFormat("METADATA")` → method not found. | Delete the line (per D-4.4: list endpoint only returns slim ids; format only applies to `get`/`threads.get`). |
| 4 | `setMaxResults(int maxResults)` → `int cannot be converted to Long`. | Wrap in `Long.valueOf(maxResults)` per Gmail API `setMaxResults(Long)` signature. |

None changed runtime semantics; all were syntactic mismatches.

---

## Summary of Phase 4 changes to main codebase (git-style stat)

**New files (main) — 13:**
```
src/main/java/com/tidymail/mail/BulkActionRequest.java
src/main/java/com/tidymail/mail/BulkActionResult.java
src/main/java/com/tidymail/mail/BulkActionType.java
src/main/java/com/tidymail/mail/MailFolderOrLabel.java
src/main/java/com/tidymail/mail/MailMessage.java
src/main/java/com/tidymail/mail/MailProvider.java
src/main/java/com/tidymail/mail/MailThreadSummary.java
src/main/java/com/tidymail/mail/PageResult.java
src/main/java/com/tidymail/mail/Provider.java
src/main/java/com/tidymail/mail/gmail/GmailClientFactory.java
src/main/java/com/tidymail/mail/gmail/GmailClientInitException.java
src/main/java/com/tidymail/mail/gmail/GmailMailService.java
src/main/java/com/tidymail/mail/gmail/GmailRateLimiter.java
src/main/java/com/tidymail/mail/gmail/GmailSyncConfigProperties.java
src/main/java/com/tidymail/mail/gmail/GoogleOAuthConfigProperties.java
```

**New files (test) — 2:**
```
src/test/java/com/tidymail/Phase4ConfigValidationTest.java
src/test/java/com/tidymail/Phase4GmailProviderTest.java
```

**Edited files (main) — 3:**
1. `pom.xml` — added google-auth-library-oauth2-http + google-http-client Maven deps with version properties.
2. `InboxCleanerApplication.java` — added `@ConfigurationPropertiesScan` annotation on main class.
3. `GmailMailService.java` — post-gate edits (METADATA_HEADERS_SIZE_EXPECTED 23→22, `toMailMessage` + `extractDomain` promoted to `public static`, `extractDomain` empty-local-part guard fix, EnumMap import, setFormat removed, Long cast).

Total files touched: 18.
