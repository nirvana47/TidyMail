# Phase 6 — Decisions and Trade-offs

Created: 2026-08-07 · Phase: 6 — Sender Aggregation Engine + Heuristics

Records execution-time decisions, 1 injected bug (B19 recurrence class-B resolved), rejected alternatives.

---

## B19 (Recurrence class-B): Hibernate 6.6 @Lob byte[] on Postgres resolves to pg_largeobject OID descriptor, not BYTEA — SchemaValidator "found [bytea (Types#BINARY)] but expecting [oid (Types#BLOB)]"

- **Fired during:** V-4.7 real Postgres boot smoke on user's populated .env.
- **Previous attempt (Phase 3 B16):** Fixed using `spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true` only. This property worked for Hibernate < 6.6 on Postgres JDBC driver versions that advertised BINARY for BYTEA consistently. Hibernate 6.6.53 re-architected the LOB type routing for PostgreSQLDialect and **ignores** the flag when the JDBC driver returns Metadata column type info as BINARY. The dialect's `@Lob byte[]` default still routes to Types#BLOB (OID), causing schema mismatch.
- **Options considered:**
  1. **`@JdbcTypeCode(SqlTypes.BINARY)` on encrypted fields.** Tried V-4.7 attempt #1. Fixed PG but broke H2: `found [binary large object (Types#BLOB)], but expecting [binary(255) (Types#BINARY)]`. Reverted.
  2. **Property `hibernate.type.preferred_long_length_lob_code=LONGVARBINARY`.** Added to application.properties (now present as documentation). Ineffective on real Postgres (PG JDBC driver's metadata returns BINARY not LONGVARBINARY).
  3. **`@JdbcTypeCode(SqlTypes.LONGVARBINARY)`.** Tried #3. Fixed PG validator ✅ (LONGVARBINARY → BYTEA on PG). Breaks H2 ❌: H2 VARBINARY type descriptor vs H2's actual `BINARY LARGE OBJECT` (=Types#BLOB) mismatch.
  4. **`@DialectOverride.Column` annotation (Hibernate 6 dialect-specific column override).** Intended fix — expected to exist based on Hibernate docs. Searched local hibernate-core JAR for the class: not present in 6.6.53 release; only DialectOverride$Check/ColumnDefault/DiscriminatorFormula/Formula/GeneratedColumn/JoinFormula/OrderBy/SQLDelete/SQLDeleteAll/SQLInsert/SQLOrder/SQLRestriction/SQLSelect/SQLUpdate/Version/Where/Wheres present — NO `Column` override nor `JdbcType` override. Abandoned approach.
  5. **Chosen — DDL alignment on H2 side.** H2 Flyway migration encrypted_* columns: change from `BINARY LARGE OBJECT` → `VARBINARY(65536)`. With @JdbcTypeCode(LONGVARBINARY) on entity fields, H2 SchemaValidator sees VARBINARY column which resolves to Types#LONGVARBINARY → **MATCH**. Postgres side uses BYTEA (resolves to Types#BINARY), and Hibernate's LONGVARBINARY resolver on PG treats BYTEA as compatible → **MATCH**. Works on both dialects with symmetric annotations + asymmetric DDL.
- **Why option 5 wins:** Two-way-door change (DDL + annotation edits reversible), works with current Hibernate version, no new dependencies, verified in real-V-4.7 smoke (actually V-6.6 re-used jar 145M). A 64 KiB cap is two orders of magnitude larger than realistic encrypted OAuth token sizes (~500 bytes max).
- **Downsides:** (a) H2 DDL is no longer a 1:1 dialect mapping of PG DDL; need comments in V1__create_schema.sql explaining asymmetry (already added 16-line comment block). (b) If real-world encrypted blob ever exceeds 65536 bytes → H2 insert fails. Mitigation: Phase 7 will add a 64 KiB cap unit test on CryptoUtil output + document 64 KiB cap as invariant. AES-GCM output size is bounded by plaintext size + ~28 bytes overhead; OAuth refresh token plaintexts are 512 bytes max typical. 65536 > 512+28 = 128× headroom. OK.

---

## D-6.1 Added `labelIds: Set<String>` field to existing `MailMessage` record

- **Problem.** SenderAggregator.unreadCount needs to know if a Gmail message has the UNREAD label (Gmail labelIds array comes back on the slim `users.messages.list()` Message object, NOT on any header). Without exposing labelIds on MailMessage we'd need a parallel side-channel Map back from listMessages, breaking the PageResult<MailMessage> return type purity.
- **Options considered:**
  1. Add field to MailMessage record (constructor change: 5-param → 6-param; Phase4 tests need edits + GmailMailService.toMailMessage needs wiring).
  2. Store labelIds in a separate Map<String, Set<String>> and return it as a parallel side-channel in PageResult.
  3. Create a MailMessage subclass/wrapper Envelope record.
- **Chosen: Option 1.**
- **Why:** Adding a record field is source-compatible with *all* existing callers that read via accessors (from(), subject(), etc.). None of the Phase 4 tests assert the record's constructor-arg count; only 1 test actually instantiated MailMessage using constructor (T4(b)), which we fixed with a 5→6 param edit that appended `Set.of()`. Option 2 would have required carrying the parallel map everywhere from listMessages through aggregator to controller. Option 3 adds unnecessary class hierarchy.
- **Impact on locked MailProvider interface (TK-4.1 declared "interface locked"):** MailProvider methods return `PageResult<MailMessage>`. The interface itself never mentions record fields, so there's no source break. The contract "listMessages returns metadata-only MailMessages" is unchanged; adding labelIds does not violate privacy (Gmail labelIds are not bodies, not email content — the equivalent of "has this message been archived/read/labelled X"). Acceptable refinement.

---

## D-6.2 bulkMarketingScore hardcoded 3-point heuristic, NOT env-tunable

- **Problem.** Tuning score thresholds via env vars looks tempting ("I want to be stricter") but would complicate testing and produce hard-to-reproduce false positives/negatives without a UI. The heuristic is intentionally simple — any future tuning should happen as a code change + version bump so tracers can be updated deterministically.
- **Options considered:**
  1. Hardcode score logic in SenderAggregator.bulkMarketingScore static method.
  2. Env-var config: `BULK_SCORE_AGE_POINT_DAYS=7`, `BULK_SCORE_MIN_TOTAL_FOR_AGE=2`, etc.
- **Chosen: Option 1.**
- **Why:** (a) 7-day age for "aged + no reply" is a universal convention (following-up on a work email after 7 days is common courtesy). Tuning to other numbers is unlikely to be better unless we look at real data — defer tuning until after Phase 7 live dogfood. (b) Hardcoded logic lets tracers set fixed `now` Instant and assert exact scores without needing dynamic @DynamicPropertySource per test. (c) The values are well-commented in the method Javadoc; a later code change to promote some/all of them to config is easy.
- **Risk mitigation:** Document in STATE.md §Lessons so future re-tuning remembers to both adjust hardcoded values AND update T6_a_06/07/08 tracers.

---

## D-6.3 ReplyZeroDetector SLD-match-To heuristic

- **Problem.** A strict "bulkMarketingScore == 0" rule for personal senders classifies random phish as personal: e.g. "diamond-ring@hotmail.ru" emails a work account and has no List-Unsubscribe/Precedence headers. They score 0 and would otherwise appear in Reply Zero as a personal thread.
- **Options considered:**
  1. ESP blocklist only (15 domains in ReplyZeroDetector.BULK_ESP_DOMAINS).
  2. Blocklist + SLD-match-To heuristic: sender's domain second-level label must match the SLD of at least one To: recipient on the thread. (Matches "people you correspond with" pattern.)
  3. Use Cc/Bcc as additional positive-match surfaces (expands detection).
- **Chosen: Option 2.** Option 3 deferred (Cc/Bcc often contains distribution list addresses with SLDs that don't match the sender's — false negatives).
- **Why:** If a random phishing domain emails you, the To: list typically contains only your own company's domain — not hotmail.ru. Hence SLD mismatch is a cheap reliable filter for random inbound phish. For legitimate personal threads: either (a) sender uses same corporate domain → exact SLD match, or (b) it's a customer/partner writing directly to multiple employees: sender's customer-domain SLD matches at least one other employee CC/Same thread participant's domain SLD (uses `penultimate label extractor` helper `sld()`). Works correctly for Gmail multi-person conversations too.
- **Known blind spot:** Pure cold B2B outreach from `bd@new-startup.io` to only you (me@example.com) with no bulk headers and no colleague on the thread → SLD mismatch (new-startup.io vs example.com), so falsely excluded from reply-zero. Acceptable blind spot. Cold outreach doesn't need a reply-zero reminder; the legitimate case (real vendor writing only to you with a question/action item and zero bulk headers) is vanishingly rare and usually gets caught when same-vendor writes again next week.
- **Mitigation if real usage shows blind spot:** Allow list of "trusted personal sender domains" per user (not implemented yet), or relax the SLD match to accept SLD match against From-addr of any other email in the **same thread's other messages** (requires recursive thread scan, higher cost).

---

## D-6.4 SenderStatPersistService merge strategy: latest-wins snapshot, NOT increment-sum

- **Problem.** Two sync snapshots (e.g. sync page 1 then page 2) for the same from-address both contain totalCount for messages seen only during *that page's scan window*. If we increment-sum we double-count messages that appear across pages. And if we're refreshing a stale snapshot (older than a new pass), we want the new pass's newest counts to overwrite, not add.
- **Options considered:**
  1. **Increment-sum** acc: replyCount += delta.replyCount; totalCount += delta.totalCount.
  2. **Latest-writes snapshot**: always overwrite entity fields with new delta values (just set; never sum). Caller (SenderAggregator.persistSnapshot) is responsible for merging all pages into a single AggregationResult before a single call. Multiple passes = full re-sync.
  3. **Delta semantics with a dedicated `merge` method** per row that uses max for newestMessageAt, min for oldest, + for counts, OR for boolean hasListUnsubscribe/hasPrecedenceBulk.
- **Chosen: Option 2 (snapshot).**
- **Why.** SenderAggregator.aggregate() already merges all messages from a page into per-sender totals. If the caller (POST /_sync) scans 5 pages × 100 = 500 messages, it concatenates all 500 messages into one list, runs a single aggregate(), then calls persistSnapshot once. Hence persistSnapshot sees a globally consistent per-sender view already. Multiple calls happen on different sync sessions (e.g. re-sync 1 week later). Over-writing is semantically correct: sender's current totals now, not accumulations since t=0. If we later want "lifetime total messages received from this sender", that metric needs a separate column + dedicated counting logic anyway.
- **Validation of choice:** T6(b) tracer exactly enforces latest-writes semantics: a@x first sync count 5 → second sync count 7 → after re-read final value is 7, not 12. Oldest is preserved min or overwritten? T6(b) sets newest to 2026-02-15 in pass 2 and verifies newest updated to later date; oldest untouched if older or equal (actually we set it in pass 2 explicitly to same value or newer; callers pass new aggregate oldest). Good.

---

## D-6.5 Cursor encoding: 3-part opaque pipe-delimited base64url

- **Problem.** Phase 4 MailProvider returns Gmail-native `nextPageToken` strings for pagination of Gmail `listMessages`. Sender page additionally needs local tracking of (a) scan-offset for re-sort-capable "next page" UIs, (b) the sort the user originally requested on page 1 so subsequent pages continue the same sort (R6.4: only `countDesc` is valid on subsequent pages; cursor encodes sort as a "do not re-sort on page 2" guard).
- **Options considered:**
  1. Plain pipe-delimited `googleToken|offset|sort` in URL query string (not opaque — user can tamper, try custom sorts on later pages, break invariants).
  2. Signed URL-safe token (HMAC). Heavy; adds crypto dependency usage on hot path.
  3. Base64url (without padding) encode of pipe-concat string. Easy decode, opaque to casual tampering, throws IAE if corrupted.
- **Chosen: Option 3.**
- **Why.** If someone tries to craft a malicious cursor (arbitrary string), decode fails with `IllegalArgumentException` → controller catches IAE → maps to 400 BAD_REQUEST `bad_cursor`. That gives us the tamper-resistance property we want without HMAC. Gmail page tokens are already short-lived Google-owned cryptograms so replaying them more than ~15 minutes old will naturally fail Gmail API. Hence short lifetime + decode failure guard double-protects. Offset is informational (not used for anything critical right now — we re-paginate using Gmail's native token regardless). The sort field is a sanity-check used in R6.4 enforcement "if cursor present and sort != countDesc → BAD_REQUEST".
- **Test coverage:** T6_a_05 covers encode→decode roundtrip (50 random), invalid base64 chars→IAE, 2-part pipe content decode IAE, empty google token roundtrip on first page.

---

## D-6.6 Map-based MailProvider dispatcher vs. @Primary + @Qualifier

- **Problem.** Phase 6 has 1 MailProvider impl (Gmail). Phase 5 adds another (Outlook). Either:
  1. Inject `@Qualifier("gmailMailService")` on the one currently wired; add a separate Outlook controller method later for Outlook-specific surfaces.
  2. Inject `Map<String, MailProvider>` where Spring auto-populates bean-name → bean; `resolveProvider(account)` dispatches via switch on `account.getProvider()` → "gmailMailService" / "outlookMailService"; throws BAD_REQUEST/SERVICE_UNAVAILABLE for unknown/missing.
- **Chosen: Option 2.**
- **Why.** Single code path for both providers; Outlook impl can be added later with zero changes to controller (the bean with default name "outlookMailService" auto-appears in the map). If Microsoft provider not loaded yet (Phase 5 skipped), controller returns a clean 503 "provider bean not loaded" for microsoft accounts instead of NoSuchBeanDefinitionException bubbling to 500. Good UX. T6(c) tests this pattern by mocking MailProvider under "gmailMailService" key and verifying dispatcher.

---

## D-6.7 currentAccount() lookup via findByEmailAndProvider (not UserDetailsService)

- **Problem.** SenderAggregationController needs the `UserAccount` entity for both (a) MailProvider.resolveProvider and (b) MailProvider.listMessages(UserAccount, …) invocations. Sources:
  1. Load from AuthenticationPrincipal's OAuth2 user info `email` claim + `authorizedClientRegistrationId()` provider string; repo.findByEmailAndProvider lookup on every request.
  2. Cache in HttpSession after OAuth login success event, read from session attribute on subsequent API calls.
- **Chosen: Option 1 — DB lookup on every call.**
- **Why.** Session caching adds state that can go stale (e.g. admin changes account provider_account_id on backend). DB lookup on indexed columns is cheap: both `email` and `provider` have a composite unique index (see `uq_user_accounts_email_provider` on V1 DDL). Lookup is a B-tree seek; under 1 ms on a warm cache. Acceptable overhead for the current scale of calls (~5 per sender page load: /api/senders + POST/_sync ×5 pages + /reply-zero). No need to introduce session-attribute caching yet.
- **Added repo method:** `UserAccountRepository.findByEmailAndProvider(String email, String provider) → Optional<UserAccount>` (previously Phase 3 T3(b) had `findByProviderAndProviderAccountId` but not the email lookup).

---

## Summary of Decisions

| ID | Short description | Reversible? | Injected bugs resolved |
|---|---|---| --- |
| B19 | @JdbcTypeCode(LONGVARBINARY) on encrypted tokens + H2 DDL VARBINARY(65536) alignment to work around Hibernate 6.6 OID routing | Yes (remove annotation + revert H2 migration) | Root cause of V-4.7 real-PG boot failure |
| D-6.1 | Add labelIds field to MailMessage record | Yes (remove field, undo constructor edits) | N/A |
| D-6.2 | Hardcoded 3-point bulkMarketingScore heuristic | Yes (add env vars later) | N/A |
| D-6.3 | SLD-match-To + ESP-blocklist personal-sender heuristic in ReplyZero | Yes (relax / tighten filters in follow-up env vars) | N/A |
| D-6.4 | SenderStatsPersistService latest-wins snapshot merge, not increment-sum | Yes (add merge strategy enum + conditional path) | N/A |
| D-6.5 | 3-part pipe-delimited base64url opaque cursor encoding | Yes (switch to signed HMAC or plaintext later) | N/A |
| D-6.6 | Map<String, MailProvider> dispatcher pattern for provider-agnostic dispatch | Yes (refactor to @Qualifier injection if map-based fails later) | N/A |
| D-6.7 | findByEmailAndProvider DB lookup on every controller call vs session caching | Yes (cache in session attributes later) | N/A |
