# Phase 18 Research — Test Foundation: Postgres-only E2E harness, delete H2, delete export

**Question answered:** "What do I need to know to PLAN this phase well?"
**Scope:** TEST-01..TEST-05 (root-fixes C-1, C-2, C-3, C-5; removes the C-4 streaming-transaction path).
**Method:** Grounded in the current codebase. File paths and line numbers are as of this research pass (2026-08-13). This is a research document only — no production code or tests were changed.

---

## 0. Executive summary

- **Two hand-maintained SQL dialect sets exist and have already diverged.** Production migrations live in `src/main/resources/db/migration/` (V1..V14); a parallel H2 set lives in `src/test/resources/db/test-migration/` (V1..V14). `V9` alone differs by **171 lines** (main 380 / test 417); `V6` by 99; `V1` by 94. This is exactly the risk TEST-01/TEST-04 target: a green test can pass on H2 while production Postgres SQL differs.
- **5 production files carry `h2` dialect branches** that must be deleted, not just the test migrations: `DecisionSurfaceSql`, `DecisionSurfaceQueryRepository`, `AnalyticsMailboxRepository`, `AnalyticsLedgerRepository`, `UnsubscribeCandidateRepository`.
- **Testcontainers-Postgres already works in this repo** — 8 `PostgresMigrationTest` classes use `PostgreSQLContainer<>("postgres:16-alpine")` with `@Testcontainers(disabledWithoutDocker = false)`. The pattern to standardize on already exists in `Phase9PostgresMigrationTest.java`. Colima is the local Docker engine (env vars documented below).
- **The export subsystem is fully wired and reachable** — `ExportController` exposes 6 endpoints (`/senders.csv`, `/unread.csv`, `/unsubscribe.csv`, `/review/history.csv`, `/analytics.csv`, `/analytics/snapshot.json`). Removal spans a controller, a service, a 8-file `export/` package, `ExportProperties`, one application property, and a dead `exportUrl`/`csvUrl` DTO field, plus ~6 test files.
- **No shared test base class exists.** 36 test files copy-paste `jdbc:h2:mem:...;MODE=PostgreSQL` datasource URLs and `spring.flyway.locations=classpath:db/test-migration`. TEST-04 requires a single shared harness.
- **No journey-named E2E tests exist yet.** 54 of 66 test files are phase-numbered (`Phase6..Phase17`). A few method names hint at the target style (`userGeneratedSenderCategoryRendersAs...`) but there is no `userConnectsGmailAndSeesTheirNoisiestSenders`-style suite.

**Recommended task ordering (low-risk):** (1) build the shared Testcontainers-Postgres harness *alongside* existing H2 tests, (2) migrate/rewrite tests onto it, (3) delete `h2` branches in production repos, (4) delete the H2 test-migration set + H2 dependency, (5) delete the export subsystem, (6) add the synthetic dataset generator, (7) write the user-journey E2E suite on top. See §7.

---

## 1. Test harness reality (what exists today)

### 1.1 Two parallel migration sets
- Production: `src/main/resources/db/migration/` — `V1__create_schema.sql` … `V14__sender_type_overrides_and_cleanup_indexes.sql` (14 files). App config: `application.properties:87` → `spring.flyway.locations=classpath:db/migration`.
- Test-only H2 set: `src/test/resources/db/test-migration/` — mirror V1..V14 rewritten for H2 (`MODE=PostgreSQL`).

**Measured divergence (main vs test, differing lines):**

| Migration | Main lines | Test lines | Differing lines |
|---|---|---|---|
| V1 create_schema | 51 | 87 | 94 |
| V6 metadata_index_and_action_scopes | 79 | 108 | 99 |
| V9 action_execution_history_and_undo | 380 | 417 | **171** |
| V13 review_queue_lifo | 30 | 20 | 26 |
| V14 sender_type_overrides | 21 | 21 | 6 |

The H2 rewrites replace Postgres-native constructs (`BIGSERIAL`, `TIMESTAMPTZ`, `TEXT[]` arrays, `JSONB`, `decode(...,'hex')`, `now()`, partial indexes, `percentile_cont ... WITHIN GROUP`) with H2 equivalents. Every one of these is a place H2 can silently accept SQL that production rejects (or vice versa).

### 1.2 Test-count breakdown
- Total test files: **66** (`find src/test/java -name '*.java'`).
- Files referencing `jdbc:h2`: **36** (all use `MODE=PostgreSQL`, most also `DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=ACTION,VALUE,USER`).
- Files using `PostgreSQLContainer` (Testcontainers): **8** — `Phase9/10/11/12/13/15PostgresMigrationTest`, `Phase13PerformanceTest`, `Phase15UnsubscribePerformanceTest`.
- `@SpringBootTest`/`@WebMvcTest`/`@DataJpaTest` files: **33**.

### 1.3 How H2 tests wire themselves (the copy-paste problem — TEST-04)
Each `@SpringBootTest` H2 test carries its own `@TestPropertySource` block, e.g. `Phase10DecisionQueryTest.java:306`:
```
"spring.datasource.url=jdbc:h2:mem:p10query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=ACTION,VALUE,USER",
...
"spring.flyway.locations=classpath:db/test-migration",   // line 311
```
There is **no base class / no `@TestConfiguration` / no shared fixture** — a repo-wide search for a shared harness returns nothing. Each test also re-creates `user_accounts` rows by hand (see the `insertAccount(...)` helper duplicated in `Phase9PostgresMigrationTest.java:160` and equivalents elsewhere). TEST-04 explicitly calls out removing "copy-pasted datasource/Flyway/`user_accounts` fixtures."

### 1.4 The `h2` dialect branches in PRODUCTION code (must be deleted for TEST-01)
Five production files branch on a runtime `boolean h2` flag (resolved from `DatabaseMetaData.getDatabaseProductName()`):

1. **`src/main/java/com/tidymail/repository/DecisionSurfaceSql.java:287`** — `hasLabel(column,label,h2)`:
   ```java
   return h2 ? "ARRAY_CONTAINS(" + column + ", '" + label + "')"
             : "'" + label + "'=ANY(" + column + ")";
   ```
   Called at `:282` and threaded through `DecisionSurfaceQueryRepository`.
2. **`src/main/java/com/tidymail/repository/DecisionSurfaceQueryRepository.java:48,52`** — holds `boolean h2` (`databaseProduct(jdbc).contains("H2")`), passes it to `DecisionSurfaceSql.unread(...)` at lines 88, 212, 221, 279, 315. `databaseProduct(...)` helper at :570.
3. **`src/main/java/com/tidymail/repository/AnalyticsMailboxRepository.java:25,29,180`** — `boolean h2` + private `hasLabel(...)` with the same `ARRAY_CONTAINS` vs `=ANY` split. `databaseProduct(...)` at :205.
4. **`src/main/java/com/tidymail/repository/AnalyticsLedgerRepository.java:40,45-48,329`** — two branches:
   - array indexing: `arrayValueExpression = h2 ? "ARRAY_GET(s.message_ids, t.scope_ordinal + 1)" : "s.message_ids[t.scope_ordinal + 1]"` (:46).
   - median: H2 path computes median in Java (`:329-339`); Postgres path uses `percentile_cont(0.5) WITHIN GROUP (...)` (:342).
5. **`src/main/java/com/tidymail/repository/UnsubscribeCandidateRepository.java:124,128,541,782`** — `boolean h2` + `isH2(...)` helper; fingerprint hashing branch (:541):
   ```java
   h2 ? "lower(rawtohex(hash('SHA-256', stringtoutf8(...), 1)))"
      : "encode(sha256(convert_to(..., 'UTF8')), 'hex')";
   ```

**Recommended approach:** once all tests run on real Postgres, delete each `h2` field, the `databaseProduct/isH2` helpers, and collapse each ternary to the Postgres-only branch (`=ANY(...)`, `s.message_ids[...]`, `percentile_cont`, `encode(sha256(...))`). The Postgres branch is the one already exercised in production, so this is a deletion, not a rewrite.

---

## 2. Testcontainers-Postgres wiring (recommended approach)

### 2.1 What already works
`Phase9PostgresMigrationTest.java` is the canonical pattern:
```java
@Testcontainers(disabledWithoutDocker = false)
class Phase9PostgresMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("tidymail").withUsername("tidymail").withPassword("tidymail");
  ...
  Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), ...).locations("classpath:db/migration").load().migrate();
```
It also boots the full app against the container via `SpringApplicationBuilder(InboxCleanerApplication.class)` with `--spring.datasource.url=` + `--spring.flyway.enabled=false` + `--spring.jpa.hibernate.ddl-auto=validate` (lines 132-152) — proving the production schema passes Hibernate validation on real Postgres. This is the anti-drift assertion TEST-01 wants generalized.

### 2.2 Dependencies (already present — `pom.xml`)
- Spring Boot parent **3.5.16** (supports `@ServiceConnection`).
- `org.testcontainers:junit-jupiter` (test) — `pom.xml:190-193`.
- `org.testcontainers:postgresql` (test) — `pom.xml:194-198`.
- `com.h2database:h2` (test) — `pom.xml:184-188` → **delete in this phase**.
- `org.postgresql:postgresql` (runtime) — `:89`. Flyway `11.8.2` with `flyway-database-postgresql` — `:94-101`.

### 2.3 Recommended shared harness (TEST-04)
Introduce a single reusable base that all integration tests extend. Two viable shapes:

**Option A — `@ServiceConnection` singleton container (recommended for Spring-context tests).**
```java
@SpringBootTest
@Testcontainers
abstract class PostgresJourneyTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  // Flyway auto-runs classpath:db/migration (production location) — no override needed.
}
```
`@ServiceConnection` (Boot 3.1+) auto-binds `spring.datasource.*` from the container, so tests no longer hard-code URLs. Use a **static container shared across the suite** (do not restart per class) for speed; clean state per test via `@Sql` truncation or transactional rollback.

**Option B — manual `@DynamicPropertySource`** for tests that need `SpringApplicationBuilder` boots (like the migration/validation tests). Keep this only where a full external boot is required.

**Key wins the harness must deliver (TEST-04):**
- One place that starts Postgres and points Flyway at `classpath:db/migration` (production migrations).
- A shared `user_accounts` fixture factory (replaces the ~duplicated `insertAccount(...)` helpers) — ideally seeding via the synthetic dataset generator (§5).
- Shared MockMvc / `OAuth2AuthenticationToken` setup for authenticated HTTP journeys (the current pattern is in `Phase10SenderUnreadWebTest.java` using `SecurityMockMvcRequestPostProcessors.authentication(...)` + `csrf()`).

### 2.4 Docker/Colima environment (local run)
Testcontainers needs Docker. This machine runs **Colima**, not Docker Desktop. From Phase 17 verification, the working env is:
```
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```
`@Testcontainers(disabledWithoutDocker = false)` currently forces these tests to fail (not skip) if Docker is absent — decide in planning whether the new harness keeps hard-fail (recommended for CI trust) or allows skip locally. Note: `.withReuse(true)` + a shared static container materially cuts suite time once the H2 fast-path is gone.

---

## 3. Export subsystem inventory (TEST-03 — delete everything, on the wire and in code)

### 3.1 Reachable endpoints (all in `ExportController.java`, `@Controller`)
| Endpoint | Method | Line |
|---|---|---|
| `/senders.csv` | `senders(...)` | :108 |
| `/unread.csv` | `unread(...)` | :129 |
| `/unsubscribe.csv` | `unsubscribe(...)` | :139 |
| `/review/history.csv` | `history(...)` | :149 |
| `/analytics.csv` | `analytics(...)` | :159 |
| `/analytics/snapshot.json` | `snapshot(...)` | :190 |

TEST-03 requires **none** of these to be reachable by any authenticated user after this phase.

### 3.2 Files to delete
**Controller / service / config:**
- `src/main/java/com/tidymail/controller/ExportController.java`
- `src/main/java/com/tidymail/service/ExportService.java` (this is the C-4 "transaction held across streaming response I/O" path — `properties.maxRows()` streaming loop at `:95-121`)
- `src/main/java/com/tidymail/config/ExportProperties.java` (`@ConfigurationProperties("tidymail.export")`, `maxRows` default 50000)

**Entire `src/main/java/com/tidymail/export/` package (8 files):**
- `CsvSchema.java`, `FilteredCsvSource.java`, `CsvExportSnapshot.java`, `AnalyticsCsvSource.java`, `UnsubscribeCsvSource.java`, `ReviewHistoryCsvSource.java`, `UnreadCsvSource.java`, `SenderCsvSource.java`

**Property (application.properties:126):**
- `tidymail.export.max-rows=${TIDYMAIL_EXPORT_MAX_ROWS:50000}` — delete.

### 3.3 Dead `exportUrl` / `csvUrl` DTO fields and their producers
- `src/main/java/com/tidymail/dto/SenderPageModel.java:19` — `String exportUrl,` (the dead field TEST-03 names explicitly).
- `src/main/java/com/tidymail/dto/AnalyticsPageModel.java:19,35,52,73` and `AnalyticsSourcePage.java:13` — `csvUrl` fields.
- `src/main/java/com/tidymail/service/AnalyticsLinkService.java:41-42` — populates `page.csvUrl()` and builds the `/analytics/snapshot.json` URL. Must stop emitting these.
- Templates: a broad grep of `src/main/resources/templates/` for `csv|download|export|snapshot` matched **only `login.html`** (unrelated). **No workspace template renders an export/download link today** — so removing the DTO fields is safe UI-wise, but verify `AnalyticsPageModel`/`SenderPageModel` construction sites compile after field removal.

### 3.4 Coupled service to review (NOT necessarily export-only)
- `SanitizedSnapshotService.java` (`snapshots.create(...)`, used by `/analytics/snapshot.json` at `ExportController.java:208`) — check whether it's used anywhere besides the snapshot endpoint before deleting. Grep shows references in `ExportController` + `Phase13*` tests only, so it is a strong deletion candidate, but confirm during planning.
- `UnsubscribeCandidateService.java` has `scanForExport(...)` (:178) and `exportOrder(...)` (:220). `exportOrder` is *also* used for in-page ordering (:125), so **do not blanket-delete** — `scanForExport` is export-only and removable, `exportOrder` may need renaming/keeping. This is a god-class flagged for Phase 21; keep Phase 18 changes minimal (remove only the export-only surface).

### 3.5 Export test files to delete/rewrite
- `src/test/java/com/tidymail/Phase13ExportTest.java` (30 KB — delete)
- `src/test/java/com/tidymail/service/UnsubscribeExportSnapshotTest.java` (delete)
- `src/test/java/com/tidymail/Phase13SnapshotPrivacyTest.java` (snapshot.json privacy — delete/repurpose)
- `Phase13IntegratedProductTest.java`, `Phase13IntegratedCycleTest.java` reference export/snapshot — prune export assertions.
- Add a **negative journey test** asserting the 6 routes now return 404 (TEST-03 "no longer exists on the wire").

---

## 4. User-journey E2E test strategy (TEST-02)

### 4.1 What exists today
- **54 of 66** test files are phase-numbered (`Phase3..Phase17`). This is the I-11 readability problem.
- **No** `userConnects.../userBulk...`-style journey suite exists. Closest existing style hints: method names like `userGeneratedSenderCategoryRendersAsOwnedFirstClassCategory` (`Phase16WorkspaceFidelityTest`), `userCreatedCategoryFlowRemainsReachableThroughContextualDialog` (`Phase17CleanupCategoryTest`), `userAccountSaveAndFindRoundtrip` (`Phase3PersistenceTest`).
- Existing HTTP-route test mechanics to reuse: `Phase10SenderUnreadWebTest.java` shows the authenticated MockMvc pattern (`SecurityMockMvcRequestPostProcessors.authentication(...)`, `csrf()`, `get(...)`, `post(...)`), and `verifyNoInteractions(mailProvider)` to prove metadata-only safety.

### 4.2 Recommended journeys (named per project convention; assert visible text/behavior, real HTTP + real DB)
Suggested first-class journeys, each on the shared Postgres harness with the synthetic dataset seeded:
- `userConnectsGmailAndSeesTheirNoisiestSenders` — sender aggregation ranking over real rows.
- `userBulkArchivesAndUndoesExactly` — exact-ID action safety + undo compensation (covers Phase7/12 territory).
- `userReviewsUnreadAndClearsTheQueue` — decision surface + review queue LIFO.
- `userFindsBulkUnsubscribeCandidatesAndUnsubscribes` — candidate query + safe unsubscribe.
- `userOpensAnalyticsAndSeesHonestCounts` — analytics dashboard over realistic volume.
- `exportEndpointsAreGoneForAuthenticatedUsers` — negative test for TEST-03.

**Constraints from ROADMAP §Cross-cutting (must hold in every rewritten test):** preserve metadata-only privacy, exact-ID action safety, CSRF, protected-sender handling; **assert visible text/behavior, never raw HTML template strings**; do not reintroduce export anywhere.

### 4.3 Migration approach
Don't rewrite all 54 phase tests at once. TEST-02 says replace *the worst phase-named/mock-heavy* tests. Recommended: build the journey suite as the new primary integration layer, migrate the high-value flows (sender/unread/bulk/unsubscribe/analytics), and delete the redundant phase tests they subsume. Keep pure-unit contract tests (e.g. `Phase10DecisionQueryTest.PureContracts` nested class — query parsing, no DB) — those don't touch H2 and can stay or be renamed.

---

## 5. Synthetic dataset generation (TEST-05)

### 5.1 Target shape (from `docs/DECISIONS.md:180`)
Real measured baseline: **119,811 messages / 267 MB in `message_metadata` incl. indexes / ~2.3 KB per message**. Metadata-only (no bodies/attachments). Requirement: **≥10,000 messages, ideally larger**, modeled on this shape, with **all PII anonymized/derived, never copied verbatim**.

### 5.2 Schema the generator must populate
Two tables carry the message shape (`src/main/resources/db/migration/V6__metadata_index_and_action_scopes.sql:1`):

`message_metadata` columns: `id BIGSERIAL`, `account_id BIGINT FK→user_accounts`, `provider VARCHAR(16)`, `provider_message_id VARCHAR(255)` (UNIQUE per account), `provider_thread_id`, `from_address VARCHAR(320)`, `from_display_name`, `from_domain VARCHAR(253)`, `subject_header VARCHAR(2048)`, `received_at TIMESTAMPTZ`, `first_seen_at`, `last_synced_at`, `unread BOOLEAN`, `size_estimate`, `has_attachments`, `metadata_snippet`, and **17 `TEXT[]` array columns**: `labels`, `to_values`, `cc_values`, `bcc_values`, `list_unsubscribe_values`, `list_unsubscribe_post_values`, `list_id_values`, `references_values`, `in_reply_to_values`, `reply_to_values`, `return_path_values`, `delivered_to_values`, `precedence_values`, `auto_submitted_values`, `message_id_values`, `feedback_id_values`. Entity mirror: `src/main/java/com/tidymail/entity/MessageMetadata.java` (fields at :28-116).

The generator must also seed `user_accounts` (email, provider, provider_account_id, encrypted tokens via `decode('..','hex')`/BYTEA, `scopes TEXT[]`) — see the existing `insertAccount(...)` at `Phase9PostgresMigrationTest.java:160`.

### 5.3 Recommended generator design
- **Java fixture, committed, deterministic** (seeded `Random`) so runs are reproducible — matches the user's "reproducible generator/fixture" requirement and the "large-scale synthetic dataset" testing preference.
- **Realistic distributions, anonymized values:** derive sender domains/addresses from a synthetic pool (e.g. `sender{n}@example{d}.test`), Zipf-ish sender frequency (a few noisy senders dominate), realistic `labels` mixes (`INBOX`,`UNREAD`,`SENT`,`CATEGORY_PROMOTIONS`…), `list_unsubscribe_values` populated for a bulk-sender subset (drives unsubscribe-candidate journeys), timestamps spread across a window so date-bucket analytics has signal.
- **Bulk insert path:** for ≥10k rows, use `COPY`/JDBC batch inserts (not per-row Hibernate) so the Testcontainers seed stays fast. Postgres `TEXT[]` literals via `ARRAY[...]` or `java.sql.Array`.
- **Performance journeys:** existing `Phase13PerformanceTest`/`Phase15UnsubscribePerformanceTest` already use Testcontainers — point them at the seeded dataset instead of ad-hoc row builders.
- **PII guard:** the generator must contain no verbatim real addresses/names/subjects/IDs. Consider a lightweight assertion/test that scans generated values against a disallow-list to enforce the constraint.

---

## 6. Validation architecture

How the harness proves it is trustworthy (maps to success criteria):

1. **Schema-parity gate (TEST-01):** every integration test runs Flyway `classpath:db/migration` against real Postgres and boots the app with `ddl-auto=validate`. Generalize the assertion already in `Phase9PostgresMigrationTest.java:132-152` into the shared base so any entity/schema drift fails the whole suite. After this, delete `src/test/resources/db/test-migration/` entirely and the H2 dependency.
2. **No-dialect-branch gate (TEST-01):** after deleting the 5 `h2` branches (§1.4), a repo grep for `contains("H2")` / `ARRAY_CONTAINS` / `boolean h2` should return zero in `src/main`. Consider an ArchUnit-style or simple grep check in CI.
3. **Export-gone gate (TEST-03):** negative journey test asserting the 6 routes 404 for an authenticated user; plus a source check that `com.tidymail.export` package, `ExportController`, `ExportService`, `ExportProperties`, `tidymail.export.max-rows`, and `exportUrl`/`csvUrl` fields no longer exist.
4. **Readability gate (TEST-02):** primary suite methods read as user journeys and exercise real HTTP + real DB rows (MockMvc against the Spring context bound to the container).
5. **No-copy-paste gate (TEST-04):** all integration tests extend one base; grep for `jdbc:h2:mem` and per-test `insertAccount` duplication returns zero.
6. **Realistic-volume gate (TEST-05):** journey/perf tests seed ≥10k synthetic rows via the committed generator; a PII-guard assertion enforces anonymization.

---

## 7. Risks, ordering, and dependencies

### 7.1 Recommended task ordering (each step keeps the suite green)
1. **Build the shared Testcontainers-Postgres harness** (`@ServiceConnection` base + fixture factory) *without deleting anything yet*. New base coexists with H2.
2. **Migrate integration tests onto the base**, one cluster at a time (sender → unread → bulk/undo → unsubscribe → analytics), converting them to journey names as you go (TEST-02 + TEST-04 together). Run against production migrations.
3. **Delete the 5 `h2` production branches** (§1.4) once nothing runs on H2 — pure deletion collapsing to the Postgres branch.
4. **Delete the H2 test-migration set** (`src/test/resources/db/test-migration/`, 14 files) **and the H2 pom dependency** (`pom.xml:184-188`). Suite must stay green (TEST-01).
5. **Delete the export subsystem** (§3) — controller, service, `export/` package, `ExportProperties`, property, dead DTO fields, `AnalyticsLinkService` emission, export tests; add the negative journey test (TEST-03).
6. **Add the synthetic dataset generator** (§5) and wire it into the harness fixture + perf tests (TEST-05).
7. **Round out the user-journey E2E suite** and delete phase tests it subsumes (TEST-02).

Rationale: stand up the new engine before removing the old one (steps 1-2), so at no point is the suite un-runnable; delete dialect branches only after the last H2 consumer is gone (step 3 before 4); export removal (5) is independent of the DB work and can parallelize, but do it after the harness so the negative test lands on the real harness.

### 7.2 Specific SQL-compatibility risks when tests move H2 → Postgres
Places where H2-mode tests may have masked Postgres behavior (verify during migration):
- **Array semantics:** `TEXT[]` + `=ANY(...)` / `s.message_ids[idx]` (Postgres is 1-based) vs H2 `ARRAY_CONTAINS`/`ARRAY_GET`. Off-by-one and empty-array (`'{}'`) handling differ.
- **`percentile_cont(...) WITHIN GROUP`** (median) — Postgres-native; H2 path computed in Java. After collapsing, ensure the Postgres query returns identical medians on the fixtures.
- **Hashing:** `encode(sha256(convert_to(...,'UTF8')),'hex')` vs H2 `rawtohex(hash('SHA-256',...))` — candidate fingerprints must match expectations post-switch.
- **Identifier casing / reserved words:** H2 tests set `DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=ACTION,VALUE,USER`. Postgres folds unquoted identifiers to lower-case and treats `user`/`value` differently — quoted-vs-unquoted usage in queries must be re-checked (columns named `unread`, `labels` are fine; watch any bare `user`/`value`/`action`).
- **`JSONB`, `TIMESTAMPTZ`, `BIGSERIAL`, `decode(..,'hex')`, partial indexes** — all Postgres-native in the main migrations; H2 rewrites approximated them. The `ddl-auto=validate` boot (§6.1) is the safety net.

### 7.3 Other risks
- **Suite runtime:** 33 `@SpringBootTest` files each booting a context against a container could be slow. Mitigate with a **single shared static container** (+ `.withReuse(true)` locally) and context caching (keep `@TestPropertySource`/`@MockBean` sets consistent so Spring reuses contexts).
- **Docker dependency in CI:** `disabledWithoutDocker = false` hard-fails without Docker. Confirm CI provides Docker; document Colima env vars (§2.4) for local runs.
- **Export removal compile-fan-out:** removing `csvUrl`/`exportUrl` fields breaks every `AnalyticsPageModel`/`SenderPageModel` constructor call site — grep and fix all constructors (multiple overloads exist in `AnalyticsPageModel.java`).
- **Don't over-reach into Phase 21:** `UnsubscribeCandidateService`/`AnalyticsService` are god-classes slated for Phase 21. In Phase 18, remove only export-only members (`scanForExport`), not the broader refactor.
- **`SanitizedSnapshotService` scope:** confirm it has no non-export consumer before deleting (current grep says export-only).

---

## 8. Open Questions (RESOLVED)

Resolutions recorded during Phase 18 planning (2026-08-13). Each decision is reflected in the PLAN.md set.

1. **CI Docker guarantee:** RESOLVED — hard-fail without Docker is kept for CI trust; CI-Docker confirmation is deferred to the `checkpoint:decision` in 18-04 before the first irreversible deletion. Local runs use the Colima env vars in §2.4.
2. **`disabledWithoutDocker` policy:** RESOLVED — keep hard-fail (`disabledWithoutDocker = false`). Document the Colima env vars (§2.4) for local contributors. (18-01, 18-05)
3. **Synthetic dataset size & location:** RESOLVED — target ≥10k rows via a committed, deterministic Java generator/fixture (`SyntheticMailboxGenerator`) regenerated each run, NOT a large committed SQL/CSV artifact. (18-02)
4. **Container reuse strategy:** RESOLVED — one shared static container for the whole suite (fastest), with truncate-between-tests for isolation. (18-01)
5. **`SanitizedSnapshotService` fate:** RESOLVED — export-only per grep; delete with the export subsystem. (18-06)
6. **Scope of phase-test deletion:** RESOLVED — keep the `PostgresMigrationTest` schema-upgrade tests (they guard Flyway ordering); convert/subsume the rest into journey tests. (18-05, 18-06)
7. **`exportOrder` in `UnsubscribeCandidateService`:** RESOLVED — leave the name as-is (used for in-page ordering, not just export); any rename is out of scope, deferred to Phase 21. Only `scanForExport` (export-only) is removed in 18-06.
