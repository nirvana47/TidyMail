# Phase 7 — Decisions and Trade-offs

Created: 2026-08-07 · Phase: 7 — Bulk Actions UI + Controllers + Reply Zero Page

Records execution-time bugs (B20–B25, 6 bugs resolved), decisions D-7.1 through D-7.6, and rejected alternatives.

---

## Bugs Fixed during this phase

### B20: Ambiguous GET /dashboard: DashboardPageController vs pre-existing LoginController

- **Fired during:** Phase 7 initial controller wiring — two `@GetMapping("/dashboard")` handlers registered simultaneously.
- **Root cause:** Phase 2 shipped `LoginController.getDashboard()` with `@GetMapping("/dashboard")` to serve the OAuth2-callback redirect landing page. Phase 7 plan initially created a separate `DashboardPageController` with the same mapping. Spring MVC threw `IllegalStateException: Ambiguous handler methods mapped for '/dashboard'`.
- **Fix:** Retired the new DashboardPageController. `LoginController` retains ownership of page-shell endpoints (including `/dashboard`). HTMX fragment endpoints live in a new `FragmentController`. This cleanly separates full-page shells (LoginController: requires OAuth2 context extraction) from partial HTML fragments (FragmentController: shared by page-render + post-action hx-get re-fetch).
- **Verification:** T7_d_01 dashboard MockMvc GET /dashboard → 200 with full Thymeleaf shell. GET /senders-frag → 200 with just the table fragment. No ambiguous-mapping errors in startup log.

### B21: Ambiguous GET / root: LoginController.root() vs HomeController.root()

- **Fired during:** Same B20 wave — `HomeController` (Phase 1 leftover: simple redirect to /login) and `LoginController.root()` (added Phase 2: same redirect) both mapped `GET "/"`.
- **Root cause:** Duplicate controller with same root mapping. Phase 2 added LoginController.root() but never removed HomeController's identical handler.
- **Fix:** Removed duplicate mapping from HomeController. HomeController now owns a **single** `/` redirect (the canonical one); LoginController owns `/login` page + `/dashboard` page. Clear ownership per controller: HomeController = root redirect only, LoginController = auth-flows + page shells, FragmentController = htmx HTML fragments, BulkActionApiController = REST POST mutations, SenderAggregationController = REST GET JSON APIs.
- **Verification:** SecurityConfigIntegrationTest root redirect 302 still green. Boot log no longer shows "Ambiguous mapping" WARN.

### B22: @RegisteredOAuth2AuthorizedClient required=true eager client registration lookup on tests where GOOGLE_CLIENT_ID=your-*

- **Fired during:** T7(c/d) MockMvc test bootstrap. Phase 2/6 tests used `@RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient client` as controller method parameter in `LoginController.dashboard()`. Spring resolves this parameter by looking up `ClientRegistrationRepository.findByRegistrationId("google")` → requires the google ClientRegistration bean to exist in the repo. In offline unit tests, SecurityFilterChain skips loading google ClientRegistration (OAuthProviderAvailability.isGoogleConfigured() returns false when env vars use the `your-` prefix convention from D-4.3). Result: `ClientRegistrationRepository.findByRegistrationId("google")` returns null → parameter resolver throws `IllegalArgumentException: Could not resolve OAuth2AuthorizedClient`.
- **Root cause:** `@RegisteredOAuth2AuthorizedClient` parameter resolution eagerly invokes ClientRegistration lookup. When the ClientRegistration isn't loaded (offline test convention), the parameter resolver fails even when the caller doesn't actually need the client (we only needed `authorizedClientRegistrationId()` to determine which provider the user signed in with — already available from the Authentication token directly).
- **Fix:** `LoginController.dashboard` parameter changed from `@RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient client` → `Authentication authentication`. Inside the method: cast to `OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication; String registrationId = token.getAuthorizedClientRegistrationId();`. The Authentication object is always present in an authenticated MockMvc session (set via `SecurityMockMvcRequestPostProcessors.authentication(oauth2Token)`); we don't touch the ClientRegistrationRepository at all.
- **Verification:** T7(c/d) 9/9 MockMvc tests green. SecurityConfigIntegrationTest 6/6 green. No `findByRegistrationId("google")` call path hit during test bootstrap.

### B23: UserAction target_id_json NOT NULL with legacy seed inserts → entity field defaults + PrePersist guards

- **Fired during:** V3 migration apply + T7(b) persistence round-trip tests. V3 DDL declares `target_id_json TEXT NOT NULL DEFAULT '{}'` AND `affected_count INTEGER NOT NULL DEFAULT 0`. But when JPA persists a `new UserAction()` with no explicit setters, Hibernate explicitly sends `NULL` for all unset fields (overriding the DDL DEFAULT) → Postgres throws `NOT NULL violation on target_id_json`.
- **Root cause:** JPA/Hibernate INSERT statements enumerate ALL entity fields. Fields without explicit values are sent as SQL NULL parameters (not "omitted from INSERT"). DDL DEFAULT only applies when the column is omitted from the INSERT statement entirely.
- **Fix:** Dual-safety pattern: (1) Entity field defaults at declaration site: `private String targetKind = "SENDER_GROUP"; private String targetIdJson = "{}"; private Integer affectedCount = 0;`. (2) `@PrePersist` guard on UserAction entity: `if (targetIdJson == null) targetIdJson = "{}"; if (affectedCount == null) affectedCount = 0; if (targetKind == null) targetKind = "SENDER_GROUP";`. (3) V3 DDL still has the `NOT NULL DEFAULT` as a belt-and-suspenders safety net for raw SQL inserts.
- **Verification:** T7b_01_userAction_4cols_defaults green. T7b_04_userAction_expiresAt_nullOk green (expires_at intentionally nullable, no DDL default). Real Postgres Chunk 1 V3 migration apply clean.

### B24: Phase3 flyway count assertion 2→3 after V3 added

- **Fired during:** Full cross-suite regression after writing V3 migration. `Phase3PersistenceTest` has an assertion counting applied Flyway migrations (SchemaValidator) — expected `==2` (V1 + V2) but after V3 was added it returned 3 → test fail.
- **Root cause:** Hardcoded migration count in Phase 3 tracer wasn't updated when Phase 7 added V3__user_actions_expand.sql.
- **Fix:** Updated Phase3PersistenceTest assertion from `==2` → `==3`. Future-safe: add a comment above the assertion noting Phase >3 will increment this number as migrations are added to the chain.
- **Verification:** Cross-suite regressions T2(6)+T3(4)+T4(11)+T6(15)+T7(18) = 54 total, all green.

### B25: T7b/T7d PersistenceTests @Transactional missing → TransactionRequiredException

- **Fired during:** T7(b) nested class first run. `@SpringBootTest` class without `@Transactional` on test methods → `repository.save(entity)` at the end of a test method works (Spring Data gets a transaction for the single save call), but then `entityManager.flush()` + `entityManager.clear()` + `repository.findById()` re-read throws `TransactionRequiredException: no transaction is in progress`.
- **Root cause:** Hibernate EntityManager operations (flush/clear/find after clear) require an active persistence context. Without `@Transactional` on the test method, each Spring Data repo call gets its own short-lived transaction that commits immediately after. After `save()` commits, the EntityManager is closed; subsequent `em.clear()` has no active transaction.
- **Fix:** Annotate `@Nested T7b_PersistenceTests` class with `@Transactional` (so each test method runs in one transaction that rolls back after). T7(d) doesn't need it (MockMvc web tests get their own request-bound transactions), but applied @Transactional defensively to any nested class using EntityManager directly.
- **Verification:** T7b_01 through T7b_04 all 4/4 green. No more TransactionRequiredException.

---

## Decisions

### D-7.1 Dashboard endpoint ownership: LoginController.get /dashboard keeps OAuth2 context extraction; FragmentController created for HTMX fragment endpoints

- **Problem:** B20 showed we can't have two controllers owning `/dashboard`. Need to split ownership of (a) page shells that render the full Thymeleaf layout with sidebar/footer and require OAuth principal extraction, vs (b) small HTML fragments returned via `hx-get` that only render inner content and don't care about the auth context beyond standard Spring Security access rules.
- **Options considered:**
  1. All in LoginController: `/dashboard`, `/senders-frag`, `/reply-zero-frag` as methods of LoginController.
  2. New DashboardPageController owns `/dashboard`; FragmentController owns fragments; retire LoginController's /dashboard.
  3. **Chosen: LoginController owns page shells (/dashboard, /reply-zero, /login); FragmentController owns fragments (/senders-frag, /reply-zero-frag, undo-banner-frag.html via template only); HomeController owns root / redirect; BulkActionApiController owns REST POST; SenderAggregationController owns REST GET.**
- **Why option 3 wins:** Single-responsibility per controller class. LoginController handles the OAuth principal → current UserAccount entity lookup flow (shared with /dashboard + /reply-zero pages). FragmentController is pure "resolve data → render fragment Thymeleaf template" with no auth specialism. No B20 ambiguous-mapping recurrence; easy to test: LoginController page tests verify shell layout; FragmentController tests verify just the inner HTML table.
- **Risks:** Fragment endpoints must still enforce authenticated access (no leaking sender fragments to unauthed users). Mitigated: SecurityFilterChain has `.anyRequest().authenticated()` catch-all; FragmentController inherits this.

### D-7.2 Undo write-then-mutate: createActionRow saves BEFORE MailProvider.batchModify called (A4 pitfall)

- **Problem:** If we call `MailProvider.batchModify` first and then save the UserAction row, and the provider call succeeds but the UserAction INSERT fails (e.g. transient DB connection blip), the user has no way to undo because there's no undo row in the DB. A4 pitfall explicitly requires: "Every action writes a decision row BEFORE showing 'done'."
- **Options considered:**
  1. Mutate-first then write: provider.batchModify → then repository.save(actionRow). Simpler ordering but loses undo if DB write fails.
  2. **Chosen: Write-then-mutate:** repository.save(actionRow with inverseActionData fully populated) → then provider.batchModify → return htmx swap body referencing the already-persisted action row ID.
  3. Full JTA transaction spanning both DB + Gmail API (impossible: Gmail API isn't transactional, can't roll back a batchModify that already executed on Google's servers).
- **Why option 2 wins:** Undo available even if provider call partially fails. In the worst case: action row saved, provider.batchModify throws IOException mid-flight (half the IDs archived). The user can still click Undo which runs the inverse batchModify on ALL original IDs — Gmail batchModify is idempotent per label (applying REMOVE(INBOX) twice on already-archived messages is a no-op), so the inverse pass corrects both the half that succeeded and the half that failed. This is the A4 pitfall's exact "undo replays inverse decision in place" pattern.
- **Verification:** T7_c_01 ArgumentCaptor order: `userActionRepository.save(...)` invocation #1, THEN `mailProvider.batchModify(...)` invocation #2. Both called in correct order. MockMvc response includes undo-banner-frag with the action ID.

### D-7.3 UserAction 4 new columns with DDL defaults + entity field defaults dual-safety

- **Problem:** B23 showed that JPA/Hibernate overrides DDL DEFAULTs by sending explicit NULL for unset entity fields. Need a pattern that works for both (a) JPA entity persists (most common) and (b) raw SQL inserts via psql/scripts (less common but needed for manual data fixes).
- **Options considered:**
  1. Only entity field defaults (no DDL DEFAULTs). Breaks raw SQL INSERTs that omit the columns → NULL instead of "{}".
  2. Only DDL DEFAULTs (no entity defaults). Breaks JPA INSERTs (B23) → NOT NULL violation.
  3. **Chosen: Dual-safety: DDL NOT NULL DEFAULT '{}' + entity field declaration-site defaults + @PrePersist guard.**
- **Why option 3 wins:** Three layers of safety: Layer 1 catches accidental `new UserAction()` in Java code (declaration-site defaults populate the fields). Layer 2 @PrePersist catches cases where code explicitly set targetIdJson to null (defensive programming). Layer 3 DDL DEFAULT catches manual INSERTs via psql that omit columns. All three layers converge on the same safe defaults.
- **Risks:** Slight duplication of default values in two places (SQL + Java). Mitigation: defaults are trivial string/int literals; a unit test (T7b_01) asserts the actual persisted values match expectations, so any future drift is caught immediately.

### D-7.4 Bulk cap: UndoService.cap1000 belt-and-suspenders (controller filters BEFORE save AND before provider.batchModify call via ArgumentCaptor invariant)

- **Problem:** A3 pitfall (exact count, no scope drift) + Gmail API hard limit "1000 ids per batchModify request". Need to enforce this cap at multiple layers so a bug at any single layer doesn't cause >1000 IDs to be sent to Gmail.
- **Options considered:**
  1. Cap only in BulkActionRequest canonical constructor (the request DTO). If controller forgets to use BulkActionRequest constructor and calls provider directly with a raw List, cap is bypassed.
  2. Cap only at MailProvider.batchModify implementation. Doesn't protect against: controller saving an oversize UserAction row with affected_count=2000 that never actually gets sent to provider (data inconsistency).
  3. **Chosen: cap1000 called TWICE: (a) in BulkActionApiController before save → ensures DB write never exceeds 1000, and returns 400 to user with actionable error. (b) in UndoService immediately before calling provider.batchModify → ensures no path (controller bug, future new controller) can invoke provider with >1000 ids even if the pre-save check is somehow bypassed.**
- **Why option 3 wins:** Defense-in-depth. T7_c_03 ArgumentCaptor invariant verifies BOTH calls see size==5 (no truncation drift between layers). T7_c_02 verifies controller-layer 400 return for 1500 ids. T7a_05 verifies cap1000 utility itself: 999→pass, 1000→pass, 1001→truncate to 1000 + overflow flag.
- **Risks:** Two caps might return different truncations if edited inconsistently. Mitigation: one single static `UndoService.cap1000()` used by both call sites. No duplication.

### D-7.5 Empty states run with @Order(1) before populated cases in tests (A7 pitfall)

- **Problem:** A7 pitfall: "Ship with empty states AND a backend test that fails if seed → empty tables." If empty-state tests run after populated seeding tests, they might accidentally pass because the Thymeleaf model still has seeded data (test isolation issue). Need to guarantee empty-state renders against a truly empty dataset.
- **Options considered:**
  1. Run empty test in a separate @Nested class that has its own @DirtiesContext. Heavy; slows test suite.
  2. **Chosen: Annotate the empty-state test method with @Order(1) so it runs first within its @Nested class, before any test that populates the fixture map.** @Transactional rollback between methods guarantees zero cross-test data leakage anyway; @Order(1) is a belt-and-suspenders "empty first" ordering that makes the A7 invariant visually obvious when reading the test source.
  3. Don't order — rely on @Transactional rollback only. Works technically but obscures the A7 pitfall intent.
- **Why option 2 wins:** @Order(1) is self-documenting. When a future engineer looks at T7_d_02 they immediately see "this runs first" and understand it's validating against a clean slate. No DirtiesContext overhead. Combined with @Transactional rollback we get two layers of isolation guarantee.
- **Verification:** T7_d_02 annotated @Order(1). Test passes and renders empty-state text. T7_d_01/T7_d_03 (populated cases) run after without cross-contamination.

### D-7.6 LoginController.dashboard Authentication param (OAuth2AuthenticationToken) replaces @RegisteredOAuth2AuthorizedClient to avoid client registration repository lookup

- **Problem:** B22 root cause. `@RegisteredOAuth2AuthorizedClient("google")` parameter requires ClientRegistration to be loaded. In offline unit tests (where `GOOGLE_CLIENT_ID=your-dummy-*` convention from D-4.3 is used), the google ClientRegistration is intentionally NOT loaded (so SecurityFilterChain doesn't try to hit Google's OIDC discovery endpoint over HTTP). The parameter resolver fails.
- **Options considered:**
  1. Remove ClientRegistration skip logic — always load google ClientRegistration even with `your-` dummy values. Breaks offline tests because they'll synchronously try `ClientRegistrations.fromOidcIssuerLocation("https://accounts.google.com")` → UnknownHostException: accounts.google.com.
  2. Use `@RegisteredOAuth2AuthorizedClient(required=false)` + manual null-check. Works but still incurs the ClientRegistrationRepository lookup; and the null branch is dead code 99.9% of the time (in production, real OAuth login always happens through an actual ClientRegistration). Adds noise.
  3. **Chosen: Accept `Authentication authentication` as method parameter → cast to `OAuth2AuthenticationToken token` → read `token.getAuthorizedClientRegistrationId()` directly.** The registration ID is already embedded in the Authentication token created at OAuth login success time; we don't need to do a secondary repository lookup just to get a string we already have.
- **Why option 3 wins:** Zero ClientRegistrationRepository dependency in the controller path. Offline unit tests work because the Authentication token is fabricated directly by `SecurityMockMvcRequestPostProcessors.authentication(oauth2Token)` with a valid registrationId. Production path is still correct: the OAuth2AuthenticationToken created by Spring Security after a real OAuth dance has a legitimate registrationId.
- **Risks:** If a non-OAuth2 authentication type ever reaches this endpoint (e.g., form login, HTTP Basic), the cast throws ClassCastException. Mitigation: SecurityFilterChain only permits OAuth2 login (formLogin().disable() except for /login page itself). Authenticated endpoints only accept OAuth2AuthenticationToken. A guard `if (!(authentication instanceof OAuth2AuthenticationToken)) throw new ResponseStatusException(FORBIDDEN)` is added defensively.

---

## Summary table

| Decision | Options considered | Chosen | Why | Risks |
|---|---|---|---|---|
| D-7.1 Dashboard + Fragment endpoint ownership | (1) All in LoginController; (2) New DashboardPageController + retire LoginController's /dashboard; (3) 5-way split by responsibility | (3) 5-controller split (Home=/, Login=shells, Fragment=fragments, BulkActionApi=POST REST, SenderAggregation=GET REST) | Single-responsibility, no B20 ambiguous-mapping, easy per-controller testing | Fragment endpoints must not leak data — mitigated by SecurityFilterChain `.anyRequest().authenticated()` |
| D-7.2 Undo ordering: write-before-mutate (A4 pitfall) | (1) Mutate-first then write; (2) Write-then-mutate; (3) Full JTA transaction (impossible) | (2) Write UserAction row → then batchModify → return undo banner | Undo available even if provider partially fails; batchModify is idempotent so inverse replay corrects half-succeeded cases | Provider succeeds + undo write fails → no undo possible; rare, mitigated by action row written first (the more common failure is provider not DB) |
| D-7.3 UserAction 4 new columns NOT NULL safety | (1) Entity defaults only; (2) DDL DEFAULT only; (3) Dual-safety entity defaults + @PrePersist + DDL DEFAULT | (3) Three-layer dual-safety | Layer 1 catches JPA new UserAction() (B23 fix); layer 2 catches explicit-null setters; layer 3 catches raw SQL psql inserts | Slight default-value duplication — mitigated by T7b_01 tracer asserting on actual persisted values |
| D-7.4 Bulk cap1000 enforcement (A3 pitfall) | (1) Cap in BulkActionRequest DTO only; (2) Cap at provider.batchModify only; (3) cap1000 called at controller-save AND provider-call (defense-in-depth) | (3) cap1000 called TWICE via same static method | Defense-in-depth; no scope drift (A3); Gmail 1000-API limit never exceeded | Two call sites might drift — mitigated: one static method reused, T7_c_03 ArgumentCaptor verifies both calls see same size |
| D-7.5 Empty-state test ordering (A7 pitfall) | (1) Separate @Nested + @DirtiesContext; (2) @Order(1) on empty test within class; (3) No order, rely on @Transactional only | (2) @Order(1) + @Transactional rollback | Self-documenting "empty-first" intent, no DirtiesContext speed penalty, dual isolation guarantee | Test ordering is cosmetic — @Transactional alone is sufficient technically |
| D-7.6 LoginController.dashboard Authentication param vs @RegisteredOAuth2AuthorizedClient (B22 fix) | (1) Always load google ClientReg (breaks offline); (2) required=false + null-check (dead code); (3) Authentication cast → OAuth2AuthenticationToken.getAuthorizedClientRegistrationId() | (3) Authentication param, read registrationId from token directly | Zero ClientRegistrationRepository dependency. Offline tests work (D-4.3 your- convention preserved). Production still correct | ClassCastException if non-OAuth2 auth reaches endpoint — mitigated by instanceof guard + SecurityFilterChain config |
