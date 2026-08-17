# Phase 7 — Bulk Actions UI + Controllers + Reply Zero Page (PLAN)

_Updated: 2026-08-07_ · _Critical path: 4 → 6 → **7** → 8_ · _Phase 5 (Outlook) intentionally skipped; provider dispatch via Map<String, MailProvider> already locked in SenderAggregationController._

---

## 1. Context & Phase Goal

**Why now.** Phase 6 locked the backend data layer: `SenderAggregator` groups mail into `SenderCard` DTOs with `bulkMarketingScore` and `replyCount`; `ReplyZeroDetector` filters personal aged threads; `SenderStatsPersistService` upserts to H2+Postgres via Flyway V2; `SenderAggregationController` exposes JSON at `/api/senders` + `/api/reply-zero` with authenticated OAuth2 token resolution (lines 62–69 of `SenderAggregationController.java`). What's missing is the **actual product**: user-visible Thymeleaf pages where humans click buttons and take bulk destructive actions, with the undo/safety guarantees from PITFALLS A4 (undo toast 5s before applying) and A7 (empty-state fragments tested before htmx wiring). This is the first phase where a user can say "I did something useful with TidyMail today."

**Input contract (from previous phases, DO NOT ALTER):**
- `MailProvider` interface ([MailProvider.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/mail/MailProvider.java)) lines 6–16: 5 methods. Locked signature `BulkActionResult batchModify(UserAccount account, BulkActionRequest request)` at line 11.
- `BulkActionRequest` record ([BulkActionRequest.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/mail/BulkActionRequest.java)) lines 5–25: canonical constructor enforces `messageIds.size() ≤ 1000` with exact plan quote at line 19. This is the backend gate for cap-1000.
- `BulkActionType` enum ([BulkActionType.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/mail/BulkActionType.java)) lines 3–8: `ARCHIVE / TRASH / MARK_READ / MARK_UNREAD`. Bulk-by-sender MVP uses first three (no MARK_UNREAD button; undo provides mark-unread semantics via inverse).
- `BulkActionResult` record ([BulkActionResult.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/mail/BulkActionResult.java)) lines 9–41: static factories `ok()` + `withPartialFailures()` provide `succeededCount`/`failedCount` for the "exact N archived" assertion gate (PITFALL A3).
- `UserAction` entity ([UserAction.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/UserAction.java)) lines 16–131: current V1 schema has `id / accountId / actionType / senderGroupKey / messageIds ARRAY / inverseActionData JSON / createdAt / undoneAt`. V3 migration EXPANDS this entity per §2 DDL below.
- `UserActionRepository` ([UserActionRepository.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/repository/UserActionRepository.java)) lines 1–9: extends `JpaRepository<UserAction, Long>` + `countByAccountId`. Add `findByIdAndAccountId` + `findFirstByAccountIdOrderByCreatedAtDesc` in this phase.
- `UserAccountRepository.findByEmailAndProvider(String, String)` line 15: reused verbatim for authentication resolution.
- `SenderAggregationController.currentAccount(OAuth2AuthenticationToken)` lines 62–69: exact auth pattern copied into page + action controllers (OAuth2AuthenticationToken → principal.email() + authorizedClientRegistrationId → findByEmailAndProvider → 401 if missing).
- `SenderAggregationController.resolveProvider(UserAccount)` lines 51–60: bean-name dispatch `"gmailMailService"` / `"outlookMailService"` with 503 if bean not loaded — reused as-is (copy `private MailProvider resolveProvider(...)` method body into action controller; alternative: extract to `@Component` helper but MVP prefers copy-paste to avoid churn).
- `SenderAggregator.toPageResponse(AggregationResult, nextCursor, sort)` lines 81–143: produces `SenderPageResponse` with sorted `items:List<SenderCard>` + `domainRollups:List<DomainRollup>` — reused by `GET /senders-frag` to build the HTML table data.
- `SecurityConfig.filterChain` line 52: `.anyRequest().authenticated()` — all new endpoints (`GET /dashboard` already works because it's behind auth; `GET /reply-zero`, `GET /senders-frag`, `POST /api/actions/**`, `POST /api/actions/{id}/undo`) inherit this guard automatically. CSRF is enabled at line 85 `.csrf(withDefaults())` so every POST form needs the hidden Thymeleaf CSRF token.
- Existing V1 DDL user_actions constraint at `V1__create_schema.sql` line 46: `ck_user_actions_undo_undone_at CHECK ((action_type = 'UNDO') = (undone_at IS NOT NULL))` — V3 migration ALTERs this check because the new MVP pattern uses `action_type ∈ {ARCHIVE, TRASH, MARK_READ}` with `undone_at` SET LATER when undo fires (not a separate UNDO row).

**Deliverable (user story, MVP).** As a Gmail-authenticated TidyMail user, I can:
1. Load `GET /` → redirected to dashboard. Load `GET /dashboard` → full page with **top nav** (branding + sign-out pill), **left sidebar DomainRollups panel** (5 top domains, count badge each), **main SenderCards table** (rows = SenderCard, columns: Sender (displayName + fromAddress + fromDomain badge), Total Count, Unread, Oldest/Newest relative date, Bulk Score 0–3 pill, Action buttons [Archive N] [Mark Read N] [Trash N]), **htmx refresh** row via `/senders-frag` on first page load.
2. Click `[Archive N]` button on any sender row → form POST `/api/actions/bulk-by-sender/{fromAddress}?action=archive` with CSRF → backend writes UserAction with inverseActionData BEFORE calling MailProvider.batchModify → returns 200 with Thymeleaf/htmx undo-toast HTML fragment "Archived 42 messages from newsletter@x.com · [UNDO]" → toast auto-dismisses after 5s and the row fades.
3. Click `[UNDO]` on the toast within 5s → htmx POST `/api/actions/{id}/undo` → inverse batchModify is called (MARK_UNREAD for archive inverse, etc.) → row restores without navigation.
4. Visit `GET /reply-zero` → page with ReplyZeroItem cards (thread subject, sender displayName, age "5 days ago", participant count, [Dismiss] button hx-post).
5. All empty states render a friendly fragment "No senders found yet — try syncing first" / "No reply-zero threads — great job!" with illustration CSS, NOT a blank table.

---

## 2. Architecture & Decomposition

### DDL / schema change — Flyway V3 migration

Files affected:
1. Main: `src/main/resources/db/migration/V3__user_actions_expand_schema.sql`
2. H2 test override: `src/test/resources/db/test-migration/V3__user_actions_expand_schema.sql`

Semantics (both files, dialect-adjusted):
```sql
-- V3 expands user_actions table to Phase 7 locked schema
-- 1. Drop old V1 check constraint (action_type='UNDO' ↔ undone_at not null is obsolete;
--    now any action can be undone by setting undone_at LATER)
ALTER TABLE user_actions DROP CONSTRAINT IF EXISTS ck_user_actions_undo_undone_at;

-- 2. Alter action_type check to match BulkActionType enum (drop DELETE/UNSUBSCRIBE/UNDO for MVP;
--    re-add in Phase 8 if needed. For now match exactly MVP actions: ARCHIVE/TRASH/MARK_READ)
ALTER TABLE user_actions DROP CONSTRAINT IF EXISTS user_actions_action_type_check;
ALTER TABLE user_actions ADD CONSTRAINT user_actions_action_type_check
    CHECK (action_type IN ('ARCHIVE', 'TRASH', 'MARK_READ'));

-- 3. Add target_kind (what was the grouping key? SENDER / MESSAGE / THREAD)
ALTER TABLE user_actions ADD COLUMN target_kind TEXT NOT NULL DEFAULT 'SENDER';
ALTER TABLE user_actions ADD CONSTRAINT user_actions_target_kind_check
    CHECK (target_kind IN ('SENDER', 'MESSAGE', 'THREAD'));

-- 4. Add target_id_json (structured JSON instead of plain senderGroupKey)
--    target_id_json shape for bulk-by-sender: { "fromAddress": "news@x.com", "displayName": "News" }
ALTER TABLE user_actions ADD COLUMN target_id_json JSONB NOT NULL DEFAULT '{}'::jsonb;

-- 5. Add affected_count (redundant with message_ids.size() but fast UI counter without JSON parsing)
ALTER TABLE user_actions ADD COLUMN affected_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE user_actions ADD CONSTRAINT user_actions_affected_count_positive
    CHECK (affected_count >= 0);

-- 6. Add expires_at — undo window closes N seconds after create (5s = 5000ms for MVP toast)
ALTER TABLE user_actions ADD COLUMN expires_at TIMESTAMPTZ NOT NULL
    DEFAULT (now() + INTERVAL '5 seconds');

-- 7. Indexes: (account_id, expires_at DESC) to fetch recent undoable actions quickly
CREATE INDEX IF NOT EXISTS idx_user_actions_account_expires
    ON user_actions (account_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_actions_target_kind
    ON user_actions (account_id, target_kind);
```

H2 migration dialect notes:
- `JSONB` → `JSON` (H2 supports `JSON` type; match H2 test-migration V1/V2 style from Phase 3)
- `TIMESTAMPTZ` → `TIMESTAMP WITH TIME ZONE` (already used in H2 V1)
- `DROP CONSTRAINT IF EXISTS` supported since H2 2.x; add `IF EXISTS` in V3
- `INTERVAL '5 seconds'` → `INTERVAL '5' SECOND` for H2 compatibility

### Entity edit: UserAction.java

File: `src/main/java/com/tidymail/entity/UserAction.java`

Add new fields matching V3 DDL (below `senderGroupKey`, before `messageIds`):
```java
@Column(name = "target_kind", nullable = false)
private String targetKind = "SENDER";

@Column(name = "target_id_json", nullable = false)
@JdbcTypeCode(SqlTypes.JSON)
private Map<String, Object> targetIdJson = new HashMap<>();

@Column(name = "affected_count", nullable = false)
private Integer affectedCount = 0;

@Column(name = "expires_at", nullable = false)
private Instant expiresAt;
```

Add getters/setters for each. Update `@PrePersist` at line 126: if `expiresAt == null`, set it to `Instant.now().plusSeconds(5)`. Keep old `senderGroupKey` field as deprecated (V3 DDL doesn't drop it; Phase 8 clean-up pass removes).

### Repository edit: UserActionRepository.java

Add 2 new finders:
```java
Optional<UserAction> findByIdAndAccountId(Long id, Long accountId);
Optional<UserAction> findFirstByAccountIdOrderByCreatedAtDesc(Long accountId);
```

### New service: UndoService.java

File: `src/main/java/com/tidymail/service/UndoService.java` — `@Service`, constructor-injects `UserActionRepository`.

Responsibilities:
1. `buildInverseActionData(BulkActionType forwardAction, List<String> succeededIds) : Map<String, Object>`
   - Pure function, no DB access. Builds inverse JSON payload:
     - `ARCHIVE` → inverse = `MARK_READ`? No. Archive inverse is **move back to INBOX** (in Gmail, archive = remove INBOX label; inverse = add INBOX label back → maps to `action: "MARK_UNREAD"`? No. Explicit mapping table:

   | Forward action | Inverse label delta (Gmail semantics) | Inverse BulkActionType to use |
   |---|---|---|
   | `ARCHIVE` (remove INBOX) | Add INBOX back → Gmail has no "unarchive" verb; use `applyLabel with INBOX labelId` but we don't have applyLabel in controller path yet. **Simplify for MVP:** store inverse as a JSON `{ "inverseActionType": "ADD_LABEL", "labelId": "INBOX", "messageIds": [...] }` and UndoService.executeUndo() dispatches to `provider.applyLabel(account, ids, "INBOX", true)` instead of batchModify. | Store `inverseActionType` string; undo executor switches on it. |
   | `TRASH` (add TRASH) | Remove TRASH label → inverse = `removeLabel(TRASH)` | `{ "inverseActionType": "REMOVE_LABEL", "labelId": "TRASH" }` |
   | `MARK_READ` (remove UNREAD) | Add UNREAD back → maps directly to `BulkActionType.MARK_UNREAD` | `{ "inverseActionType": "BATCH_MODIFY", "bulkActionType": "MARK_UNREAD" }` |

2. `createActionRow(Long accountId, BulkActionType forwardAction, String fromAddress, String displayName, List<String> messageIds, Map<String, Object> inverseData) : UserAction` — creates + saves a new UserAction with `actionType=forwardAction.name()`, `targetKind="SENDER"`, `targetIdJson={fromAddress, displayName}`, `messageIds=messageIds.subList(0, min(1000, messageIds.size()))`, `affectedCount=messageIds.size()`, `inverseActionData=inverseData`, `senderGroupKey=fromAddress`. Returns saved entity. Returns **before** calling batchModify (PITFALL A14: "EVERY action writes UserAction row BEFORE showing success").

3. `executeUndo(UserAccount account, UserAction actionRow, MailProvider provider) : BulkActionResult` — validates `expiresAt.isAfter(now)` (if expired, throw ISE "undo window closed"); reads `inverseActionData.inverseActionType`; dispatches to provider.batchModify for MARK_UNREAD or provider.applyLabel for ADD/REMOVE_LABEL; then sets `actionRow.setUndoneAt(Instant.now())`; repo.save(actionRow).

4. `isUndoable(UserAction row) : boolean` — `row.getUndoneAt() == null && row.getExpiresAt().isAfter(Instant.now())`.

### New BulkActionRequest wrapper cap enforcement

In action controller, before passing to MailProvider:
```java
List<String> cappedIds = messageIds.size() > 1000
    ? messageIds.subList(0, 1000) : messageIds;
if (cappedIds.size() != messageIds.size()) {
    log.warn("bulk-by-sender capped {} ids to 1000 for sender {}", messageIds.size(), fromAddress);
}
```
This is a SECONDARY enforcement layer even though BulkActionRequest already gates → belt-and-suspenders. Backend assertion (A3 pitfall): AFTER batchModify completes, verify `result.succeededCount() == sampleMessageIds.size()` for that sender. If mismatch, set HTTP 202 with warning and mark the action row with a flag in `target_id_json.mismatch=true` (undo still works for succeeded IDs).

### Controllers (3 new, 1 edit)

**1. DashboardPageController** (NEW file: `src/main/java/com/tidymail/controller/DashboardPageController.java`)
- `@Controller` (NOT `@RestController` — returns Thymeleaf view names)
- Inject `SenderAggregator`, `MailProvider` map, `UserAccountRepository`
- `GET /` — same logic as HomeController.root() → currently redirects to /dashboard. HomeController already handles this; leave it.
- `GET /dashboard` — auth via OAuth2AuthenticationToken → resolve account → inject model attributes `email`, `displayName`, `providerId`, `providerName`, `initials` (first-letter of email) → returns `"dashboard"` view. Also injects `senders: SenderPageResponse` from a FIRST sync call (size=10 default; empty list OK because htmx frag will refresh) OR uses persisted SenderStat snapshot for immediate paint (MVP: pass empty + htmx `hx-get="/senders-frag" hx-trigger="load"` will fill in 300ms later — paint-first pattern per PHASE 8 lazy loading precursor).
- `GET /senders-frag` — returns `"fragments/senders-table"` fragment ONLY (not full page). Calls `/api/senders` logic internally (reuse SenderAggregationController flow: currentAccount → resolveProvider → listMessages → aggregate → toPageResponse). Model attr = `senderPage: SenderPageResponse`. If `items.isEmpty()` → render empty-state fragment `fragments/empty-senders` instead.
- `GET /reply-zero-frag` — reply-zero card fragment for htmx swap.

**2. ReplyZeroPageController** (NEW file: `src/main/java/com/tidymail/controller/ReplyZeroPageController.java`)
- `@Controller`
- `GET /reply-zero` — auth → inject `replyZeroPage: ReplyZeroPageResponse` (first page, default olderThanDays=3) + user nav attrs → returns `"reply-zero"` view. If `items.isEmpty()` → render empty-state fragment.

**3. BulkActionApiController** (NEW file: `src/main/java/com/tidymail/controller/BulkActionApiController.java`)
- `@Controller` (returns Thymeleaf fragments for htmx swap, NOT JSON — PITFALL A4: undo IN PLACE via htmx swap, no redirect)
- Auth pattern copy-paste: `currentAccount()` + `resolveProvider()` from SenderAggregationController lines 51–69.
- `POST /api/actions/bulk-by-sender/{fromAddress}` — query param `?action=archive|read|trash` (case-insensitive, maps to BulkActionType via switch). Resolve sender → pull ALL message IDs for that sender via SenderAggregation + sampleMessageIds cap 1000 → build inverseActionData via UndoService.buildInverseActionData → **FIRST** call `undoService.createActionRow(...)` → persist → **THEN** call `provider.batchModify(account, BulkActionRequest.of(action, cappedIds))` → ASSERT `result.succeededCount() == cappedIds.size()` (or log mismatch + 202 partial-success). On 100% success: set `actionRow.affectedCount = result.succeededCount()`; save; return Thymeleaf fragment `"fragments/undo-toast-banner"` with actionId, actionLabel, count, fromDisplayName.
- `POST /api/actions/{id}/undo` — auth → load `UserAction by findByIdAndAccountId(id, accountId)` → 404 if another user's action → if `!undoService.isUndoable(row)` → return fragment "undo-expired" banner → else `undoService.executeUndo(account, row, provider)` → return fragment `"fragments/action-restored"` with count.

### Thymeleaf templates (2 new, 1 edit, 1 fragments file)

**1. Edit existing dashboard.html** (`src/main/resources/templates/dashboard.html`)
Replace the `.placeholder-card` div (lines 68–80) with a **two-column responsive layout**:
```
<main>
  <div class="layout-grid">
    <aside class="sidebar">     <!-- DomainRollups panel -->
      <h3>Top Domains</h3>
      <ul th:if="${senderPage != null and !#lists.isEmpty(senderPage.domainRollups())}">
        <li th:each="dr : ${senderPage.domainRollups()}" th:if="${drStat.count <= 5}">
          <span class="domain-name" th:text="${dr.fromDomain()}"></span>
          <span class="count-pill" th:text="${dr.countAcrossAllSenders()}"></span>
        </li>
      </ul>
    </aside>
    <section class="content">    <!-- SenderCards table -->
      <div hx-get="/senders-frag" hx-trigger="load" hx-swap="innerHTML">
        <div class="skeleton">Loading senders…</div>  <!-- Empty-state frag replaces this -->
      </div>
    </section>
  </div>
</main>
```
Also wire in the undo-toast banner mount point at the bottom of `<body>`: `<div id="undo-banner-slot" hx-swap-oob="true"></div>` so any htmx POST response can swap in a toast banner that auto-dismisses via CSS `animation: fadeOut 0.3s 5s forwards`.

**2. New reply-zero.html** (`src/main/resources/templates/reply-zero.html`)
Copy nav + layout from dashboard.html. Main content = card grid:
```
<div class="reply-zero-grid">
  <article class="rz-card" th:each="item : ${replyZeroPage.items()}">
    <div class="rz-head">
      <div class="rz-sender" th:text="${item.fromDisplayName() ?: item.fromAddress()}"></div>
      <span class="rz-age" th:text="'${item.ageDays()}d ago'"></span>
    </div>
    <h4 class="rz-subject" th:text="${item.subject()}"></h4>
    <div class="rz-foot">
      <span class="rz-participants">${item.participantCount()} others</span>
      <div class="rz-actions">
        <form hx-post="/api/actions/dismiss-rz/${item.threadId()}" hx-swap="outerHTML">
          <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" th:if="${_csrf != null}">
          <button type="submit" class="btn-ghost">Dismiss</button>
        </form>
      </div>
    </div>
  </article>
</div>
```
Empty-state: if replyZeroPage is null or empty, render `fragments/empty-reply-zero` th:replace.

**3. New fragments file** (`src/main/resources/templates/fragments/common.html`)
Thymeleaf `th:fragment` declarations:
- `senders-table` fragment: `<table>` with `<thead>` + `<tbody th:each="card : ${senderPage.items()}">` rows. Each row has action form buttons:
  ```html
  <td class="actions-col">
    <form th:action="@{'/api/actions/bulk-by-sender/' + ${card.fromAddress()}}"
          hx-post="@{'/api/actions/bulk-by-sender/' + ${card.fromAddress()} + '?action=archive'}"
          hx-swap="none" hx-include="this">
      <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" th:if="${_csrf != null}">
      <button type="submit" class="btn-archive">Archive <span th:text="${card.totalCount()}"></span></button>
    </form>
    <!-- Same pattern for action=read (Mark Read) and action=trash (Trash) -->
  </td>
  ```
- `empty-senders` fragment: friendly card with SVG envelope icon + copy "Your inbox summary is syncing. If this stays empty, try triggering a sync manually."
- `empty-reply-zero` fragment: green check icon + "All caught up! No threads awaiting your reply. 🎉"
- `undo-toast-banner` fragment: sticky bottom banner with action text + UNDO button:
  ```html
  <div id="undo-banner-slot" class="undo-banner" th:fragment="undoToast(actionId, label, count, sender)">
    <span th:text="${label} + ' ' + ${count} + ' messages from ' + ${sender}"></span>
    <form th:action="@{'/api/actions/' + ${actionId} + '/undo'}"
          hx-post="@{'/api/actions/' + ${actionId} + '/undo'}"
          hx-swap="innerHTML" hx-target="#undo-banner-slot">
      <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}" th:if="${_csrf != null}">
      <button type="submit" class="btn-undo">UNDO</button>
    </form>
  </div>
  ```
- `undo-expired` fragment: banner "Undo window closed. Action is permanent." (dismisses on timer)
- `action-restored` fragment: banner "42 messages restored to inbox ✓" (auto-dismisses 2s)

### htmx CDN inclusion

Add to `<head>` of BOTH dashboard.html and reply-zero.html:
```html
<script src="https://unpkg.com/htmx.org@1.9.10"
        integrity="sha384-D1Kt99CQMDuV+vLOHNnnT9WzX+bTjK/5QZ1Hn52bT/6Q2hSdCwnCx0E8M6R0ApgA"
        crossorigin="anonymous"></script>
```
CSS for auto-dismiss:
```css
.undo-banner { animation: undoFade 5.3s ease-in forwards; }
@keyframes undoFade {
  0%, 92% { opacity: 1; transform: translateY(0); }
  100% { opacity: 0; transform: translateY(20px); pointer-events: none; }
}
```

### Cross-cutting: CSRF token in all POST forms

Thymeleaf pattern `th:action` generates the token automatically BUT with htmx `hx-post` we need the explicit hidden input `th:name="${_csrf.parameterName}" th:value="${_csrf.token}"` in every form block. In MockMvc tracers, use `SecurityMockMvcRequestPostProcessors.csrf()` to inject it per test.

---

## 3. Task List (executable order, tracer-first)

### Tracer T7(a) — Pure Java UndoService inverse builder (runnable before any DB)

TK-7.1. Create `UndoService` as a Spring-agnostic POJO first (can `new` it). Implement `buildInverseActionData(BulkActionType forwardAction, List<String> ids)` → Map. 100% deterministic, pure Java.

TK-7.2. **T7_a_01** — buildInverseActionData maps ARCHIVE→ADD_LABEL(INBOX), TRASH→REMOVE_LABEL(TRASH), MARK_READ→BATCH_MODIFY(MARK_UNREAD). Assert on exact JSON key names. 3 assertions in 1 test.

TK-7.3. **T7_a_02** — `isUndoable(row)`: set expiresAt = now.plusSeconds(5) → true; set expiresAt = now.minusSeconds(1) → false; set undoneAt = now → false even if expiresAt in future. 3 assertions.

TK-7.4. **T7_a_03** — cap enforcement helper `UndoService.cap1000(List<String> ids)`: 999 ids → 999, 1000 → 1000, 2000 → 1000 with warning log captured via SLF4J test filter or just verify size returned.

TK-7.5. **T7_a_04** — `executeUndo` dispatch table via hand-rolled `FakeMailProvider implements MailProvider` (same pattern as Phase 6 FakeMailProvider at line 460). For each forward action, executeUndo calls the inverse: test MARK_READ forward → expect MARK_UNREAD batchModify on provider; test ARCHIVE forward → expect applyLabel(account, ids, "INBOX", true). 2 sub-assertions.

T7(a) subtotal: **≥ 5 tests** (3 tracers + 2 helpers). Zero Spring context.

### Tracer T7(b) — UserAction persistence + V3 migration + FK cascade (@SpringBootTest H2 JPA)

TK-7.6. Write Flyway V3 migration + H2 override copy. Edit `UserAction.java` entity + add finders to `UserActionRepository.java`.

TK-7.7. **T7_b_01** — Flyway SchemaValidator on H2 passes; V1 + V2 + V3 apply clean. Insert UserAccount → insert UserAction with targetKind=SENDER, targetIdJson={fromAddress:"a@x.com"}, affectedCount=42, expiresAt=now+5s → retrieve → assert all fields round-trip through JSON and JPA.

TK-7.8. **T7_b_02** — Cascade delete: create Account → create 3 UserAction rows for it → delete account via `deleteByEmailAndProviderIgnoreCase` → assert `userActionRepository.count()` = 0 (DB-level CASCADE from V1 DDL still applies; V3 doesn't touch account_id FK reference).

TK-7.9. **T7_b_03** — `findByIdAndAccountId` security guard: insert Account1 (id=1) with action row, Account2 (id=2) with another → findByIdAndAccountId(action1.id, account2.id) returns empty; findByIdAndAccountId(action1.id, account1.id) returns present. Ensures user can't undo another user's action.

TK-7.10. **T7_b_04** — expires_at default: new UserAction() no expires set → repo.save → retrieved row.getExpiresAt().isAfter(Instant.now()) && isBefore(now.plusSeconds(6)) (within 5s window with 1s jitter tolerance).

T7(b) subtotal: **≥ 4 tests**.

### Tracer T7(c) — MockMvc bulk-by-sender endpoints (archive, read, trash, undo roundtrip)

TK-7.11. Create `BulkActionApiController.java` skeleton (stub responses first; fill in logic as tracers pass).

TK-7.12. **T7_c_01** — `POST /api/actions/bulk-by-sender/news@x.com?action=archive` with mock auth + MockBean MailProvider returning BulkActionResult.ok(12, listOf12). Verify:
  - HTTP 200, response body contains fragment class `undo-banner`
  - UserActionRepository.countByAccountId == 1 (row written BEFORE provider called — verify order via Mockito `InOrder` inOrder.verify(undoRepo).save → then inOrder.verify(provider).batchModify)
  - Stored actionType=ARCHIVE, affectedCount=12, inverseActionData contains `inverseActionType=ADD_LABEL`

TK-7.13. **T7_c_02** — Same endpoint with action=read and action=trash. Parameterized test or 2 tests. Assert actionType correct and inverseActionData correct for each.

TK-7.14. **T7_c_03** — Cap enforcement: POST with sender that has 2000 messageIds in sample → provider receives BulkActionRequest with exactly 1000 (BatchModifyRequest constructor would throw otherwise, but we cap before construction). Verify response count shows 1000, not 2000.

TK-7.15. **T7_c_04** — Undo roundtrip: POST archive (action=archive for 5 ids) → parse actionId from undo-toast fragment HTML via regex or `MockMvcResultMatchers.xpath(...)` → POST `/api/actions/{id}/undo` with same auth → verify `UserAction.getUndoneAt()` is NOT null, and provider received inverse call (MARK_UNREAD or applyLabel).

TK-7.16. **T7_c_05** — 401 guard: POST bulk-by-sender WITHOUT auth → 302 redirect to /login or 401 (Spring Security default). POST undo WITHOUT auth → same. POST undo for ANOTHER account's action → 404. 3 assertions in 1 test.

T7(c) subtotal: **≥ 5 tests**.

### Tracer T7(d) — MockMvc dashboard + reply-zero page rendering, empty-state fragments

TK-7.17. Create `DashboardPageController` + `ReplyZeroPageController` + fragment templates. Build fragment file `fragments/common.html` with all 5 fragments (skeleton first; fill CSS as tests validate).

TK-7.18. **T7_d_01** — `GET /dashboard` authenticated → HTTP 200, Thymeleaf renders `class="layout-grid"` (sidebar + content split), HTML contains `hx-get="/senders-frag" hx-trigger="load"`, CSRF token present in page source, undo-banner-slot div exists. User pill shows email. No 500 errors.

TK-7.19. **T7_d_02** — `GET /senders-frag` with mock MailProvider returning **0 messages** (empty list). Assert fragment renders `class="empty-senders"` container, NOT an empty table. PITFALL A7.

TK-7.20. **T7_d_03** — `GET /senders-frag` with mock MailProvider returning 10 messages → 3 unique senders. Assert `<table>` has 3 `<tbody>` rows, each row has 3 action buttons (Archive/Mark Read/Trash), each button's hx-post URL contains correct `fromAddress`, total count badge matches SenderCard.totalCount, sort DESC invariant (first row count ≥ second row count ≥ third).

TK-7.21. **T7_d_04** — `GET /reply-zero` authenticated with empty items → fragment `class="empty-reply-zero"` renders. Same with 2 ReplyZeroItem cards → 2 `.rz-card` elements present, `.rz-age` text contains days suffix.

T7(d) subtotal: **≥ 4 tests**.

### Wave 1 expansion — cross-cutting wiring + integration

TK-7.22. Wire `UndoService` as `@Service` with real `UserActionRepository` injection. Remove POJO-only test scaffolding if any (pure Java tests still work because `@Service` can be instantiated with `new` when args are provided — ensure there's a constructor with all deps).

TK-7.23. Dashboard.html edit: replace placeholder with sidebar + table layout + htmx script tag. Ensure CSS responsive at 640px breakpoint matches phase 6 login.html media query pattern.

TK-7.24. reply-zero.html new file: full nav + card grid + empty states.

TK-7.25. Integration glue: `BulkActionApiController` uses sampleMessageIds from SenderStat DB rows instead of calling `/api/senders` on every POST (MVP OK: for action on `fromAddress` we call SenderAggregator once OR read SenderStat persisted sample IDs). For MVP simplicity: call `SenderStatRepository.findByAccountIdAndFromAddress(accountId, fromAddress)` — if sampleMessageIds is empty, trigger a mini sync just for that sender. If still < ids → warn and proceed with what we have.

TK-7.26. Cross-suite regression run: all Phase 2 (T2 6 tests) + Phase 3 (T3 4 tests) + Phase 4 (T4 11 tests) + Phase 6 (T6 ≥ 15 tests) + T7 new (≥ 18 tests) → **target ≥ 54 tests green**. Locked minimum from spec: **48/48 = 36 baseline + 12 T7**.

---

## 4. Verification Criteria

| ID | What to verify | Pass condition |
|---|---|---|
| V-7.1 | Actuator + login smoke. `mvn spring-boot:run` → visit `http://localhost:8080/actuator/health` → 200 `{"status":"UP"}`. Visit `http://localhost:8080/login` → login page renders with both Google/Microsoft buttons (or warning banner if env vars empty). No 500 on any static path. | Both endpoints 200, body contains expected strings, no exception stack traces. |
| V-7.2 | Dashboard authenticated render of 0/10 senders. MockMvc test (same H2+JPA config as T6e lines 669–691): mock OAuth → (a) 0-message dataset → GET `/dashboard` + GET `/senders-frag` → empty-senders class present, no blank table; (b) 10-sender dataset (same T6e build40MsgDataset truncated) → 10 sender rows, sort DESC non-increasing, Archive button text contains correct N for each row. | Both (a) and (b) pass; empty state is rendered NOT an empty table. |
| V-7.3 | Reply-zero authenticated render. Same auth setup: (a) 0 reply-zero items → empty-reply-zero class present; (b) 2 items → 2 card elements with age labels, Dismiss button with CSRF hidden input; (c) unauthenticated hit → 302 to /login. | All 3 sub-cases pass. |
| V-7.4 | POST bulk-by-sender archive roundtrip + undo roundtrip both MockMvc. Flow: insert account + mock GmailProvider with 42 messages from sender → POST `/api/actions/bulk-by-sender/{addr}?action=archive` w/ CSRF → (1) UserAction row exists with actionType=ARCHIVE, affectedCount=42, inverseActionData JSON has inverseActionType=ADD_LABEL; (2) MailProvider.batchModify called exactly once with BulkActionType.ARCHIVE; (3) Parse actionId from response; (4) POST `/api/actions/{id}/undo` with CSRF → MailProvider.applyLabel called with (account, [42 ids], "INBOX", true); (5) UserAction.undoneAt is set. | 5 assertions all TRUE. Order of UserAction.save BEFORE batchModify verified via Mockito InOrder (not just count). |
| V-7.5 | Real Postgres V3 migration jar boot smoke. Follow Phase 3 pattern: start docker-compose postgres (V-3.5 exact commands), set DATABASE_URL env, `java -jar target/tidymail-0.1.0.jar`, log greps for "Flyway applied 1 migration to schema `public`" (V3), no SchemaValidator errors, actuator health 200 on 8080. Check PG table: `psql -c "\d user_actions"` shows all 4 new columns (target_kind, target_id_json, affected_count, expires_at) and 2 new indexes. | Jar boots in < 8s, Flyway V3 success log present, psql confirms columns + indexes exist, actuator 200. |
| V-7.6 | Real-Gmail OAuth login session flow (deferred manual). **Marked DEFERRED** until user populates real GOOGLE_CLIENT_ID/SECRET (same as Phase 4 V-4.7 pattern). When run: start app with real creds → localhost:8080 → click Google → OAuth consent → dashboard renders at `/dashboard` → sender table populates after sync → click Archive on a real newsletter sender → toast appears → click UNDO → verify in Gmail UI those messages returned to Inbox. | Manual PASS/FAIL logged in 07-02-UAT.md; if no browser/env available, mark "DEFERRED — requires real Google OAuth credentials + browser" — does NOT block phase closeout. |
| V-7.T | T7 tracer suite aggregate. T7(a) ≥ 5, T7(b) ≥ 4, T7(c) ≥ 5, T7(d) ≥ 4 → **T7 total ≥ 18**. Cross-suite: T2 (6) + T3 (4) + T4 (11) + T6 (≥ 15) + T7 (≥ 18) = **≥ 54 tests green, 0 failures, 0 errors**. Locked minimum per spec: 48/48 = 36 baseline + 12 T7 minimum. | Surefire reports show 0 failures/errors; count ≥ 48 green. |
| V-7.A3 | Pitfall A3 exact-count backend assertion. After bulk-by-sender completes: `result.succeededCount == SenderCard.totalCount` for that sender. If the provider partial-fails (e.g. 40 of 42), the action row `target_id_json.partialSuccess = true` AND the banner shows "Archived 40 of 42 messages". Never silently drop the extra 2. | Exact assertion gate in controller; tracer T7_c_04 includes partial-failure case: provider returns 40 of 42 → response text contains "40 of 42" not "42". |
| V-7.A4 | Pitfall A4 undo IN PLACE (no redirect). Undo POST response swaps innerHTML of `#undo-banner-slot` with restoration banner, does NOT return a redirect 302, does NOT change htmx page address. Row restores without navigation. | MockMvc response status 200 (not 302), no Location header, body contains "restored" banner text. |
| V-7.A7 | Pitfall A7 empty-state test BEFORE htmx wiring. T7_d_02 (0 senders) and T7_d_04 (0 reply-zero) are written and PASS BEFORE any htmx JS runs in the template. | Test ordering: in T7(d) test class, the empty-state tests run before any test that checks action button wiring. Class ordering via JUnit 5 `@TestMethodOrder(OrderAnnotation.class)` — empty tests use `@Order(1)`, htmx wire tests `@Order(2+)` |

---

## 5. Decisions recorded here (D-7.x)

**D-7.1** — Bulk-by-sender inverse action dispatch table: ARCHIVE→applyLabel(INBOX, add=true), TRASH→applyLabel(TRASH, add=false), MARK_READ→batchModify(MARK_UNREAD). One-way door? **Reversible** (undo logic lives in UndoService only; can change mapping without DB migration). Rating: **two-way / reversible** → no checkpoint.

**D-7.2** — Undo window = 5 seconds (expires_at = createdAt + 5s). Matches PITFALL A4 "undo toast 5s before applying" actually the toast DISPLAYS for 5s AND the backend expires_at is 5s → window is congruent. Tunable later → two-way.

**D-7.3** — POST response format = Thymeleaf HTML fragment swapped via htmx, NOT JSON API. Reason: htmx can inject the undo banner directly without writing a line of client JS. If Phase 8 needs JSON (for mobile app), add `Accept: application/json` branch → two-way.

**D-7.4** — Sample message IDs cap 1000 (belt-and-suspenders: controller caps at list level; BulkActionRequest canonical constructor throws at record level; Migration V3 DDL doesn't add an actionGroupId chunking pattern because 1000 is a hard ceiling — per J10 pitfall mitigation, >1000 IDs would require chunking but we explicitly don't support >1000 yet). Future-proof: if Phase 8 needs 50k, add actionGroupId UUID chunking then. **Two-way.**

**D-7.5** — Dashboard `/dashboard` first-paint = empty skeleton; htmx `hx-trigger="load"` fills `/senders-frag` 300ms later. Alternative: blocking wait for first sync. Chosen because (a) matches Phase 8 lazy/fast-first-render direction, (b) avoids 502 timeout on cold Gmail. **Two-way** (could pre-populate from SenderStat snapshot).

**D-7.6** — UserAction old V1 columns `senderGroupKey` + old `action_type` CHECK constraint kept in V3 migration but deprecated. senderGroupKey still populated for legacy queries (none exist). DROP in Phase 8 if no regression. **Reversible** (columns retained).

---

## 6. Risks & Mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R7.1 | V3 migration `DROP CONSTRAINT IF EXISTS ck_user_actions_undo_undone_at` fails on H2 2.x (H2 may have different constraint naming than Postgres — constraint name case sensitivity). | medium | high | Run Flyway H2 test in T7_b_01 BEFORE committing. If H2's generated constraint name differs, wrap DROP in a DO block for PG and use INFORMATION_SCHEMA lookup. Simpler alternative: in V3, rename old → new via ALTER, never depend on exact constraint names. |
| R7.2 | MailProvider.applyLabel not yet tested for Gmail "INBOX" labelId. Gmail uses special string "INBOX" not a generated labelId; GmailMailService.applyLabel needs to route INBOX to special `users.messages.modify` addLabelIds=["INBOX"] same as archive routes to removeLabelIds=["INBOX"]. | medium | medium | GmailMailService.applyLabel (lines of GmailMailService.java) — add comment guard: if labelId equals "INBOX" / "TRASH" / "UNREAD", delegate to existing batchModify BULK_ACTION_LABELS EnumMap logic. T7(a)_04 FakeMailProvider.applyLabel asserts on INBOX string. |
| R7.3 | htmx POST forms without CSRF → 403 Forbidden (Spring Security CSRF filter rejects). Users see failed actions. | high | medium | EVERY `hx-post` form in Thymeleaf includes the explicit `<input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">` block. T7(c) ALL tests use `.with(csrf())` and fail if they don't. If any form is missed, MockMvc catches it at 403. |
| R7.4 | UserAction row saved but batchModify fails (e.g. Gmail 429) → undo banner shows for an action that never actually happened → user clicks UNDO → undoes nothing but wastes provider call. | medium | medium | In BulkActionApiController: after batchModify returns failedCount > 0 AND succeededCount == 0 → DELETE the UserAction row we just wrote; return error banner "Action failed — no messages changed" instead of undo banner. Only show undo banner if succeededCount > 0. Partial success (succeeded < requested): still show undo (undoes the succeeded ones). Banner text shows "40 of 42" (V-7.A3). |
| R7.5 | SenderCard.sampleMessageIds only holds 5 messages (line 199 of SenderAggregator: `if (sampleMessageIds.size() < 5)`). But Archive button shows totalCount = 150. Action would only archive 5, not 150. A3 PITFALL "archived MORE than N" inverted here — archiving LESS than N is equally wrong. | high | high | **Fix the root gap.** In BulkActionApiController bulk-by-sender handler: DON'T use SenderCard.sampleMessageIds. Instead: (a) query SenderStat for target sender; (b) if SenderStat has a persisted messageIds JSON field in the future → use that; (c) for MVP, re-query MailProvider.listMessages for the CURRENT ACCOUNT filtered to that fromAddress via streaming filter across pages until we have `min(totalCount, 1000)` IDs. MVP implementation: stream up to 10 pages × PAGE_SIZE=100 = 1000 max, filter by fromAddress match, cap at 1000, pass to batchModify. Add assertion A3 gate: `result.succeededCount == countFilteredIds` — if less, warn banner. |
| R7.6 | 5s undo window is too short for human reaction on mobile. | low | low | For MVP, keep 5s (matches original InboxTriage research). Phase 8 polish: make it 10s on mobile via CSS `@media (max-width: 640px) { animation-duration: 10.3s }` AND bump `expires_at` to 11s server-side on User-Agent mobile detection. Two-way door. |
| R7.7 | Thymeleaf th:utext used accidentally instead of th:text on email subject/from → XSS (PITFALL J9). | low | high | Full audit of templates: grep for `th:utext` in dashboard.html and reply-zero.html. Should return ZERO matches. All user content rendered via `th:text` (escaped). If List-Unsubscribe hrefs need to render, pass them through a sanitizer helper in the controller model. |
| R7.8 | Dashboard redirect 404: HomeController `/` → redirects to `/dashboard`; but if DashboardPageController GET `/dashboard` is missing → 404 loop. | low | low | T7_d_01 explicitly tests GET /dashboard returns 200. If controller is missing, test fails. Keep HomeController.root() redirect — it's a tested path. |

---

## 7. Phase Summary (post-closeout)

| Metrics | Target | Actual |
|---|---|---|
| Main code files written | ~11 (UndoService ×1, Controllers ×3=DashboardPageController+ReplyZeroPageController+BulkActionApiController, UserAction.java edit + UserActionRepository.java edit, Flyway V3.sql ×2=main+H2, Templates: dashboard.html edit + reply-zero.html NEW + fragments/common.html NEW = 3 files) | TBD |
| Test code files written | 1–2 (Phase7BulkActionsTest.java with 4 @Nested classes: T7a_PureJavaTests, T7b_PersistenceTests, T7c_BulkMvcTests, T7d_PageRenderingTests) | TBD |
| Flyway migrations | V3.sql × 2 (main + H2 override) | TBD |
| T7 new tracers | ≥ 18 (T7a ≥ 5, T7b ≥ 4, T7c ≥ 5, T7d ≥ 4) · Locked minimum per spec: **≥ 12** | TBD |
| Total test suite green (T2+T3+T4+T6+T7) | ≥ 54 green, 0 failures, 0 errors · Locked minimum per spec: **48/48 = 36 baseline + 12 T7** | TBD |
| V smokes (manual + automated) | 6 total (V-7.1, V-7.2, V-7.3, V-7.4, V-7.5 PASS; V-7.6 DEFERRED if no creds) | TBD |
| Known bugs injected | 0 (target) · A3, A4, A7 pitfalls explicitly tested in V-7.A3, V-7.A4, V-7.A7 criteria | TBD |
| Pitfall mitigations verified | 3/3 critical: A3 (exact N archived), A4 (undo in-place), A7 (empty-state test). Bonus: J9 XSS audit, J2 401 guard on POST actions, J10 cap-1000 belt-and-suspenders | TBD |
