# Phase 8 — Decisions, Bugs, and Tradeoffs (2026-08-07)

Created: 2026-08-07 · Phase: 8 — Deployment Runbook & Mobile Polish

Records execution-time bugs (B26–B29, 4 bugs resolved), decisions D-8.1 through D-8.7, and tradeoff rationale.

---

## Section A: Bugs Encountered (Class-B, Lessons Learned)

### B26: Thymeleaf progress Optional<SyncProgress> unwrap NPE

- **Fired during:** T8c_01 tracer render of sync-progress-frag with no SyncProgress row in DB. Fragment model attribute was raw `Optional<SyncProgress>` directly from repository finder.
- **Root cause:** `FragmentController.syncProgressFragment` passed the `Optional<SyncProgress>` returned by `findTopByAccountIdAndJobTypeOrderByIdDesc(…)` directly into the Thymeleaf model as attribute `"progress"`. Template expression `progress.status != 'RUNNING'` tried to dereference `.status` on a `java.util.Optional` proxy object in SpEL — not on the wrapped entity. Result: `EL1008E: Property or field 'status' cannot be found on object of type 'java.util.Optional'`.
- **Fix:** Controller unwraps the Optional before adding to model: `model.addAttribute("progress", progressOpt.orElse(null));`. Thymeleaf template then sees either the `SyncProgress` entity (with `.status` field accessible) or `null` (handled via `th:if="${progress} == null"` guard).
- **Lesson:** **NEVER pass `Optional<?>` objects directly into Thymeleaf model attributes.** Always unwrap to the entity value or `null` first in the Controller layer. SpEL cannot reliably traverse Optional's `.get()` in all dialects, and the template should never have to know about `java.util.Optional` semantics.

### B27: sync-progress-frag Thymeleaf full-DOCTYPE wrapper + unquoted RUNNING/SUCCESS enum tokens

- **Fired during:** T8c_02 + T8c_03 render verification after fixing B26. Two distinct symptoms: (i) dashboard DOM had nested `<html>`/`<body>` tags after innerHTML swap, (ii) RUNNING state never matched in `th:if` conditions.
- **Root cause:**
  - (i) The fragment template was wrapped with full `<!DOCTYPE html><html><body>…</body></html>` boilerplate. HTMX innerHTML swap injects the fragment's response inside the dashboard's `#sync-status-slot` `<div>`, resulting in nested html/body tags — invalid DOM, breaks CSS selectors scoped to `body`.
  - (ii) Template used `th:if="${progress.status == RUNNING}"` — comparing against a bare `RUNNING` token which Thymeleaf interprets as a variable reference (unresolvable in model), not a string literal. Result: condition always false.
  - (Bonus issue): Template also tried `#temporals.format(progress.completedAt, 'MMM d')` on `java.time.Instant`. Thymeleaf `#temporals` cannot format `Instant` directly (expects `LocalDateTime` or `ZonedDateTime` with TZ context), throwing template render exception.
- **Fix:**
  - (a) Strip wrapper to a single outer `<div xmlns:th="http://www.thymeleaf.org">` + 4 inner `<div>` blocks (one per UI state). No DOCTYPE, no html, no body.
  - (b) Quote enum status tokens in single quotes: `th:if="${progress.status == 'RUNNING'}"`, `== 'SUCCESS'`, `== 'FAILED'`.
  - (c) Removed `#temporals.format` call entirely. SUCCESS UI simplified to just "Synced X messages" string without timestamp formatting. Avoids both the Instant→LocalDateTime conversion and the TZ-context requirement.
- **Lesson:**
  1. HTMX fragments for innerHTML swap **MUST be partial markup ONLY** — never nested HTML/BODY/DOCTYPE. A single root `<div>` with Thymeleaf namespace is the canonical pattern.
  2. Thymeleaf literal strings always need single quotes. Bare tokens = variable references, not string constants. Write template conditions by copy-pasting actual enum values into quoted strings.
  3. Avoid `#temporals` on `java.time.Instant` → either convert entity timestamps to `LocalDateTime` at persist time, or skip date formatting entirely for MVP and use simple human-readable "X messages" strings instead.

### B28: Dashboard shell hx-trigger attribute substring assertion mismatch in T8c_02

- **Fired during:** T8c_02 RUNNING-state tracer assertion against dashboard.html rendered output. Test passed for the fragment render but failed the slot-attrs substring check.
- **Root cause:** Actual `dashboard.html` slot uses `hx-trigger="load, every 15s"` — a 2-event HTMX trigger chain: (1) on-load initial fetch when DOM loads, (2) re-poll every 15 seconds thereafter. Test assertion only checked for substring `hx-trigger="every 15s"` — missing the `"load, "` prefix. Result: contains() returned false.
- **Fix:** Updated assertion to check for the exact attribute value `"load, every 15s"` as a substring (or at minimum both `"load"` + `"every 15s"` substrings within the same element's attrs).
- **Lesson:** HTMX combined trigger events serialize with the `", "` separator (comma-space). Always write assertions by **copy-pasting the actual attribute string from the template source** rather than paraphrasing. If paraphrasing must be used, assert on multiple component substrings (both event tokens) rather than one incomplete string.

### B29: Postgres docker compose actual port 5432 vs manual test typed 5433

- **Fired during:** V-8.5 real Postgres jar boot smoke run. App failed with JDBC `Connection refused` on port 5433 while docker ps showed container listening on 5432.
- **Root cause:** V-8.5 smoke run command had `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/tidymail` — a copy-paste error from an unrelated prior docker-compose stack that used custom port 5433. Current project's `docker-compose.yml` exposes the standard Postgres port 5432 only.
- **Fix:** Set `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/tidymail` matching the compose file.
- **Lesson:** When V-smokes involve real Docker Postgres, always copy the port number **directly** from `docker-compose.yml` `ports:` field. Never re-type a port number from memory; always open the compose file and copy-paste. If a non-standard port is used, the compose file is the single source of truth.

---

## Section B: Decisions D-8.1 through D-8.7

| ID | Decision | Rationale / Tradeoff |
|---|---|---|
| D-8.1 | SyncProgress repository top-N finder method name = `findTopByAccountIdAndJobTypeOrderByIdDesc` | Single composite index `idx_sync_progress_account_job_desc` covers both the WHERE clause (account_id, job_type) AND the ORDER BY id DESC sort key. Avoids writing custom JPQL with `ORDER BY`. Spring Data derived query compiles SQL exactly once at context boot; no query-string drift risk. |
| D-8.2 | `@Scheduled` IncrementalSyncJob: `fixedRate = 300000L` (5 min), `initialDelay = 60000L` (60 s) | Tradeoff: `fixedRate` (not `fixedDelay`) ensures clock-aligned ticks even if individual sync runs have slight drift — tick n fires at t=0, t=5m, t=10m wall-clock regardless of how long tick n-1 took. `initialDelay` gives Postgres connection pool + Hikari + Flyway migration time to fully stabilize before the first sync job fires (avoids racing against post-boot transient connection exhaustion). |
| D-8.3 | `ConcurrentHashMap<Long, Semaphore(1)>` per-account non-overlap gate in IncrementalSyncJob | Rationale: Synchronized map + Semaphore pattern gives (a) per-account granularity — no global lock blocking Account B while Account A syncs, (b) survives multi-thread pools (Semaphore is JDK-concurrency safe, ConcurrentHashMap avoids monitor race on lazy init), (c) `finally { semaphore.release() }` always releases even on Exception, so FAILED-progress save and subsequent retries are not blocked. Tradeoff: in-memory only pattern — a future cluster/multi-instance deployment would need a distributed lock (Redis Redisson / PG advisory lock), but that is explicitly out of scope for the personal single-instance MVP. |
| D-8.4 | SenderStatsVacuumJob: 90-day stale window with 2-part qualification gate | Only accounts with BOTH `lastLoginAt != null` AND `lastLoginAt < now - 90 days` qualify for child-row vacuum. Never-logged-in accounts (`lastLoginAt = null`) are preserved in full. Rationale: never-logged-in rows could be freshly added OAuth accounts where the user started the OAuth consent dance but hasn't actually completed a browser login yet — vacuuming those would wipe the valid `encryptedRefreshToken` and require re-auth. Tradeoff: A tiny amount of dead data (accounts created where user bailed mid-OAuth) is acceptable. The never-login set is bounded per real user (≤5 stale partial signups in practice for a personal instance). |
| D-8.5 | Dashboard mobile layout: collapse sender-table `<tbody>` rows to card layout with `data-label` prefix | Tradeoff evaluated: (i) horizontal scrolling with overflow-x: auto, (ii) collapsed card-style rows. Card layout wins for mobile readability — vertical scrolling is more natural on phones than horizontal panning; sender count/score/age fields are labeled inline with their values. CSS rule pattern uses `td::before { content: attr(data-label) }` with the existing Thymeleaf `<td>` tags already rendered with `data-label="Count" / "Unread" / "Age"` attributes in the template. Zero template markup changes needed for mobile card activation. |
| D-8.6 | Touch target `min-height: 44px`, `min-width: 44px` (Apple HIG). Applied at `@media (max-width: 375px)` breakpoint. | Rationale: WCAG 2.5.5 Target Size Level AAA + Apple Human Interface Guidelines 44pt recommendation. Applies to all `.btn`, `.undo-btn`, buttons inside `.rz-card` and `.sender-bulk-actions` selector set. No JS sizing logic needed; pure CSS breakpoint keeps sizing concerns in CSS layer. 375px matches iPhone SE (3rd gen) viewport — the common minimum-width mobile baseline for responsive audits. |
| D-8.7 | DEPLOY.md scope = localhost documentation ONLY. No cloud push automation. | Rationale: User explicitly confirmed they want manual localhost browser dogfood testing FIRST. `cloudflared` quick tunnel is documented as §3 manual run command with a blockquote warning box — no auto-install, no scripted execute. User decides when (and if) to expose the app via tunnel. Keeps DEPLOY.md as a runbook, not an executable installer. |

---

## Section C: Summary Table

| # Delivered Tracer Count | V-Smokes (Auto) | V-8.6 (User) | Status |
|---|---|---|---|
| 10 T8 tracers (T8a×2 + T8b×3 + T8c×3 + T8d×2) | 5/5 auto-PASS (V-8.1 actuator, V-8.2 jobs autowire, V-8.3 sync-slot render, V-8.4 deploy+CSS, V-8.5 real-PG V4 migration) | DEFERRED (user self-manual localhost via DEPLOY.md §1) | ✅ Complete |
