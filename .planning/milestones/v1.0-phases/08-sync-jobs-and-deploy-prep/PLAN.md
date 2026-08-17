# Phase 8 — Sync Jobs, Lazy Loading, Dashboard Polish, Deployment Prep (PLAN)

_Updated: 2026-08-07_ · _Critical path: 4 → 6 → 7 → **8**_ · _Phase 5 (Outlook) intentionally skipped; provider dispatch via Map<String, MailProvider> already locked in SenderAggregationController. MVP DOGFOOD phase: user runs localhost via docker compose + manual browser login before any future deployment docs._

---

## 1. Context & Phase Goal

**Why now.** Phase 7 locked the product UI: dashboard.html sidebar + senders table with Archive/Read/Trash buttons with htmx undo banners (54/54 tests green, V-7.1–V-7.5 PASS, B20–B25 6 bugs resolved). What's missing is (a) scheduled background jobs so the app stays useful after first login (hourly action cleanup, daily 90d stale account stats vacuum, 5-min incremental sync), (b) sync progress tracking so users know background jobs are healthy, (c) mobile-first polish at 375px so the app is actually usable on iPhone SE (A8 pitfall — 375px overflow = release blocker), and (d) a DEPLOY.md runbook so the user can dogfood the real inbox on localhost TODAY via docker compose + manual browser login. This is the final phase before MVP closeout — user touches real Gmail on their own machine.

**Input contract (from previous phases, DO NOT ALTER):**
- `UserAction` entity ([UserAction.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/UserAction.java)) lines 16–131: V3 schema has `expiresAt` (added Phase 7), `undoneAt`, `actionType`. Cleanup job predicate: delete rows where `expiresAt < now AND undoneAt IS NULL` — keep undone rows as audit trail.
- `UserAccount` entity ([UserAccount.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/UserAccount.java)) lines 10–110: current fields `id / email / provider / providerAccountId / displayName / imageUrl / encryptedAccessToken / encryptedRefreshToken / accessTokenExpiresAt / scope / createdAt / updatedAt`. V4 migration ADDS `lastLoginAt Instant nullable` (not present yet — add entity field + Flyway V4).
- `SenderStat` entity + `SenderStatRepository` ([SenderStat.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/entity/SenderStat.java) + [SenderStatRepository.java](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/java/com/tidymail/repository/SenderStatRepository.java)): SenderStatsVacuumJob deletes by account_id IN subquery against user_accounts.last_login_at < 90d ago.
- `MailProvider` interface + `Map<String, MailProvider>` dispatch pattern (Phase 6 D-6.6): IncrementalSyncJob reuses `resolveProvider(account)` → `provider.listMessages(account, null, 100)` one-page cheap scan, aggregates via `SenderAggregator`, persists via `SenderStatsPersistService` — does NOT touch Threads or reply-zero (incremental = cheap).
- `SecurityConfig.filterChain` line 52: `.anyRequest().authenticated()`. New GET `/sync-progress-frag` inherits auth guard automatically. CSRF enabled at line 85. GET endpoint = no CSRF token needed.
- `dashboard.html` template ([dashboard.html](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/resources/templates/dashboard.html)): Phase 7 2-column layout with sidebar + content. V4 injects `<div id="sync-status-slot" hx-get="/sync-progress-frag" hx-trigger="load, every 15s" hx-swap="innerHTML">Loading…</div>` into sidebar as a new widget. Mobile CSS @media blocks added inline inside existing `<head><style>` block (NO external CSS file — keep no asset pipeline per user instruction).
- `reply-zero.html` template ([reply-zero.html](file:///Users/bytedance/Documents/trae_projects/TidyMail/src/main/resources/templates/reply-zero.html)): same inline `<style>` mobile audit — @media 375px / 640px breakpoints.
- Flyway migration naming convention V1…V3 already in `src/main/resources/db/migration/` + H2 override copies in `src/test/resources/db/test-migration/`. Both locations get V4.
- State §LL J3 pitfall: `@Scheduled` jobs on multiple instances = double-run. Mitigation: deployment runbook §1 explicitly says "SINGLE INSTANCE ONLY for personal user. No horizontal scale behind LB." If multi-instance needed later, add ShedLock. Not MVP blocker.
- State §LL J4 pitfall: Hikari pool exhaustion under sync + UI. Mitigation: sync semaphore per-account (1 concurrent per account) so even with 10 accounts, only 10 provider connections; Hikari default 10 = sufficient for MVP 1-user scenario.

**Deliverable (user story, MVP dogfood).** As a real Gmail user dogfooding TidyMail on localhost, I can:
1. Follow DEPLOY.md §0 docker-runtime precheck → §1 one-liners: start colima/DB, build jar, boot on port 8080, open browser → click Continue with Google → real Gmail OAuth → dashboard loads with sender table.
2. While I browse the dashboard, the app runs three scheduled jobs in-process: (a) every hour, deletes expired undo UserActions that were never undone; (b) at 2am daily, drops sender_stats rows for accounts that haven't logged in for 90+ days; (c) every 5 minutes (with 1-min initial delay), does a cheap 1-page incremental resync of my inbox and persists fresh sender counts.
3. The dashboard sidebar has a sync-status widget showing last run time + colored badge (green=success, yellow=running, red=failed) that auto-refreshes every 15s via htmx. I never stare at a stale dashboard wondering if sync is alive.
4. I can shrink my browser to iPhone SE 375px width and everything fits: buttons are 44pt+ touch targets, tables collapse to cards, no horizontal scroll anywhere on dashboard / reply-zero / fragments.
5. Archive a real newsletter sender → undo banner appears → click UNDO within 5s → Gmail UI confirms messages are back in Inbox. (This is the DEFERRED V-8.6 full live dogfood executed by user; agent does not run it.)

---

## 2. Architecture & Decomposition

### GROUP 1 — SCHEDULED SYNC JOBS

#### ScheduleConfig class (@Configuration @EnableScheduling)

File: `src/main/java/com/tidymail/config/ScheduleConfig.java`
```java
@Configuration
@EnableScheduling
public class ScheduleConfig {
    // No explicit ThreadPoolTaskScheduler config for MVP — Spring's default
    // single-threaded scheduler is fine (only 3 jobs, staggered, per-account
    // semaphore prevents overlap even if ticks queue). If needed later, add
    // @Bean public TaskScheduler taskScheduler() with pool size.
}
```

#### (a) UserActionCleanupJob — @Scheduled(cron="0 0 * * * *") hourly

File: `src/main/java/com/tidymail/job/UserActionCleanupJob.java` — `@Component`, constructor-injects `UserActionRepository`.

Logic:
```java
@Scheduled(cron = "0 0 * * * *")
@Transactional
public void cleanupExpiredActions() {
    // Predicate: expiresAt < now AND undoneAt IS NULL
    // Delete all expired actions that were never undone (undo window closed).
    // DO NOT touch rows where undoneAt IS NOT NULL — those are permanent audit rows.
    int deleted = userActionRepository.deleteByExpiresAtBeforeAndUndoneAtIsNull(Instant.now());
    log.info("UserActionCleanupJob: deleted {} expired undone-at-null rows", deleted);
}
```

Repository addition to `UserActionRepository.java`:
```java
@Modifying
@Transactional
int deleteByExpiresAtBeforeAndUndoneAtIsNull(Instant cutoff);
```

#### (b) SenderStatsVacuumJob — @Scheduled(cron="0 0 2 * * *") 2am daily

File: `src/main/java/com/tidymail/job/SenderStatsVacuumJob.java` — `@Component`, constructor-injects `SenderStatRepository` + `UserAccountRepository`.

First, add `lastLoginAt` field to `UserAccount` entity:
```java
// Add to UserAccount.java, after updatedAt:
@Column(name = "last_login_at")
private Instant lastLoginAt;
// getter + setter
```

Update `OAuthLoginSuccessListener.java` (Phase 3 listener at auth success) to set `lastLoginAt = Instant.now()` on the UserAccount entity after save.

Repository additions:
- `UserAccountRepository`: `List<Long> findIdsByLastLoginAtBefore(Instant cutoff)` — returns account IDs that haven't logged in since cutoff.
- `SenderStatRepository`:
  ```java
  @Modifying
  @Transactional
  int deleteByAccountIdIn(Collection<Long> accountIds);
  ```

Job logic:
```java
@Scheduled(cron = "0 0 2 * * *")
@Transactional
public void vacuumStaleAccounts() {
    Instant cutoff90d = Instant.now().minus(90, ChronoUnit.DAYS);
    List<Long> staleAccountIds = userAccountRepository.findIdsByLastLoginAtBefore(cutoff90d);
    if (staleAccountIds.isEmpty()) {
        log.info("SenderStatsVacuumJob: no stale accounts (>90d inactive)");
        return;
    }
    int deleted = senderStatRepository.deleteByAccountIdIn(staleAccountIds);
    log.info("SenderStatsVacuumJob: deleted {} sender_stats rows across {} stale accounts",
        deleted, staleAccountIds.size());
}
```

#### (c) IncrementalSyncJob — @Scheduled(fixedRate = 300000, initialDelay = 60000) = 5-min fixed rate, 1-min initialDelay

File: `src/main/java/com/tidymail/job/IncrementalSyncJob.java` — `@Component`, constructor-injects `UserAccountRepository`, `Map<String, MailProvider>`, `SenderAggregator`, `SenderStatsPersistService`.

Per-account semaphore to prevent overlap:
```java
// ConcurrentHashMap<Long, Semaphore> accountSemaphores = new ConcurrentHashMap<>();
// For each account, computeIfAbsent(acctId, k -> new Semaphore(1)).tryAcquire() →
//   if acquired, run sync; if not, log skip "previous tick still running, skip this one".
// Finally block always releases.
```

Logic body:
```java
@Scheduled(fixedRate = 300000, initialDelay = 60000)
public void incrementalSyncAllActiveAccounts() {
    List<UserAccount> active = userAccountRepository.findAllActiveLoggedInAccounts();
    // findAllActiveLoggedInAccounts = findAll where provider IN ("google","microsoft")
    //   AND encryptedAccessToken IS NOT NULL (successful login completed).
    for (UserAccount account : active) {
        Semaphore sem = accountSemaphores.computeIfAbsent(account.getId(), k -> new Semaphore(1));
        if (!sem.tryAcquire()) {
            log.warn("IncrementalSyncJob: account {} previous tick still running, skip", account.getId());
            continue;
        }
        try {
            syncProgressTracker.markStart(account.getId(), "INCREMENTAL_SYNC");
            MailProvider provider = resolveProvider(account); // same D-6.6 dispatch
            PageResult<MailMessage> page = provider.listMessages(account, null, 100);
            AggregationResult agg = senderAggregator.aggregate(page.items());
            senderStatsPersistService.upsertSnapshot(account.getId(), agg.perSender());
            int scanned = page.items().size();
            syncProgressTracker.markSuccess(account.getId(), "INCREMENTAL_SYNC", scanned);
            log.info("IncrementalSyncJob: account {} scanned {} messages", account.getId(), scanned);
        } catch (Exception ex) {
            log.error("IncrementalSyncJob: account {} failed: {}", account.getId(), ex.getMessage(), ex);
            syncProgressTracker.markFailed(account.getId(), "INCREMENTAL_SYNC", ex.toString());
        } finally {
            sem.release();
        }
    }
}
```

Repository addition `UserAccountRepository.findAllActiveLoggedInAccounts()`:
```java
@Query("SELECT u FROM UserAccount u WHERE u.provider IN ('google','microsoft') AND u.encryptedAccessToken IS NOT NULL")
List<UserAccount> findAllActiveLoggedInAccounts();
```

### GROUP 2 — SYNC PROGRESS TRACKING + LAZY RENDER

#### SyncProgress entity + Flyway V4 migration cols

Entity file: `src/main/java/com/tidymail/entity/SyncProgress.java`

```java
@Entity
@Table(name = "sync_progress", indexes = {
    @Index(name = "idx_sync_progress_account_job", columnList = "account_id, job_type, id DESC")
})
public class SyncProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "job_type", length = 32, nullable = false)
    private String jobType;

    @Column(name = "status", length = 16, nullable = false)
    private String status; // RUNNING | SUCCESS | FAILED

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "messages_scanned", nullable = false)
    private Integer messagesScanned = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // All getters + setters
}
```

Repository: `SyncProgressRepository.java` extends `JpaRepository<SyncProgress, Long>`:
```java
Optional<SyncProgress> findTopByAccountIdAndJobTypeOrderByIdDesc(Long accountId, String jobType);
```

Also: helper service `SyncProgressTracker.java` @Component that wraps repo.save() for markStart / markSuccess / markFailed — used by all 3 jobs.

#### Flyway V4 migration (BOTH main + H2 override copies)

Main: `src/main/resources/db/migration/V4__sync_progress_and_last_login.sql`
H2: `src/test/resources/db/test-migration/V4__sync_progress_and_last_login.sql`

Semantics (both files, dialect-adjusted):
```sql
-- V4.1: Add last_login_at to user_accounts
ALTER TABLE user_accounts ADD COLUMN last_login_at TIMESTAMPTZ;
-- (H2: TIMESTAMP WITH TIME ZONE)

-- V4.2: Create sync_progress table
CREATE TABLE sync_progress (
    id BIGSERIAL PRIMARY KEY,                         -- (H2: BIGINT AUTO_INCREMENT PRIMARY KEY)
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    messages_scanned INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    CONSTRAINT sync_progress_status_check
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
);
CREATE INDEX idx_sync_progress_account_job
    ON sync_progress (account_id, job_type, id DESC);
```

#### Controller fragment endpoint GET /sync-progress-frag

Add to existing `FragmentController.java` (Phase 7 — owns htmx fragments):

```java
@GetMapping("/sync-progress-frag")
public String syncProgressFrag(Authentication auth, Model model) {
    UserAccount account = currentAccountOrNull(auth); // same LoginController pattern
    if (account == null) return "fragments/sync-progress-empty"; // never seen via auth guard
    Optional<SyncProgress> last = syncProgressRepository
        .findTopByAccountIdAndJobTypeOrderByIdDesc(account.getId(), "INCREMENTAL_SYNC");
    model.addAttribute("progress", last.orElse(null));
    return "fragments/sync-progress-badge";
}
```

New fragment file: `src/main/resources/templates/fragments/sync-progress-badge.html`
```html
<div th:if="${progress == null}" class="sync-badge sync-never">
    <span class="dot"></span>
    <span class="label">Never run</span>
</div>
<div th:if="${progress != null and progress.status == 'RUNNING'}"
     class="sync-badge sync-running"
     hx-trigger="every 15s">
    <span class="dot"></span>
    <span class="label">Syncing… <span class="muted" th:text="${#temporals.format(progress.startedAt, 'HH:mm')}"></span></span>
</div>
<div th:if="${progress != null and progress.status == 'SUCCESS'}"
     class="sync-badge sync-success">
    <span class="dot"></span>
    <span class="label">Synced <span class="muted" th:text="${#temporals.format(progress.finishedAt, 'HH:mm')}"></span> · <span th:text="${progress.messagesScanned}"></span> msgs</span>
</div>
<div th:if="${progress != null and progress.status == 'FAILED'}"
     class="sync-badge sync-failed">
    <span class="dot"></span>
    <span class="label">Failed</span>
    <span class="muted" th:text="${#strings.abbreviate(progress.errorMessage, 60)}"></span>
</div>
```

#### dashboard.html sidebar widget injection

In dashboard.html, inside the existing `<aside class="sidebar">` element, insert as the LAST widget BEFORE closing `</aside>`:
```html
<div id="sync-status-slot"
     hx-get="/sync-progress-frag"
     hx-trigger="load, every 15s"
     hx-swap="innerHTML">Loading…</div>
```

### GROUP 3 — MOBILE FIRST POLISH (375px)

Audit targets:
1. `src/main/resources/templates/dashboard.html` — add `<style>` block inside `<head>` if not present, or APPEND to existing `<style>`.
2. `src/main/resources/templates/reply-zero.html` — same pattern.
3. Fragment files (senders-frag, reply-zero-frag, undo-banner-frag, sync-progress-badge) — inherit page styles; add scoped classes if needed.

CSS to write (INLINE inside templates' `<head><style>`, NO external CSS file):

```css
/* === MOBILE FIRST: 375px baseline, enhance upward === */
*, *::before, *::after { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body { margin: 0; min-width: 320px; overflow-x: hidden; }

/* === Touch targets ≥ 44pt === */
button, .btn, [role="button"] {
    min-height: 44px;
    min-width: 44px;
    padding: 10px 14px;
    border-radius: 8px;
    font-size: 15px;
}

/* === Dashboard layout collapse < 640px === */
@media (max-width: 640px) {
    .layout-grid {
        grid-template-columns: 1fr !important;
        gap: 12px;
        padding: 8px;
    }
    .sidebar {
        order: 2;
        border-top: 1px solid var(--border);
        padding-top: 12px;
    }
    .content { order: 1; }
    .nav-bar {
        padding: 10px 12px;
        flex-wrap: wrap;
        gap: 8px;
    }
    .nav-user-pill { min-height: 36px; font-size: 13px; }
}

/* === Tables collapse to cards < 640px === */
@media (max-width: 640px) {
    table.senders-table, table.senders-table thead, table.senders-table tbody,
    table.senders-table tr, table.senders-table td {
        display: block;
        width: 100%;
    }
    table.senders-table thead { display: none; }
    table.senders-table tr {
        border: 1px solid var(--border);
        border-radius: 10px;
        margin-bottom: 12px;
        padding: 12px;
        background: var(--bg-elevated);
    }
    table.senders-table td {
        border: none;
        padding: 4px 0;
        text-align: left;
    }
    table.senders-table td::before {
        content: attr(data-label);
        font-weight: 600;
        color: var(--muted);
        margin-right: 6px;
        font-size: 12px;
        text-transform: uppercase;
    }
    .actions-col { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
    .actions-col form, .actions-col button { flex: 1 1 auto; min-width: 0; }
}

/* === Min-width guard 375px — NO horizontal scroll === */
@media (min-width: 375px) and (max-width: 639px) {
    main, .layout-grid, .reply-zero-grid {
        max-width: 100%;
        padding-left: 12px;
        padding-right: 12px;
    }
    .rz-card {
        margin: 0 0 10px 0;
        padding: 14px;
    }
    .domain-rollup-item { padding: 8px 0; }
    h1.page-title { font-size: 20px; }
    h3.section-head { font-size: 16px; }
    .undo-banner {
        left: 8px; right: 8px;
        padding: 12px;
        font-size: 14px;
        border-radius: 10px;
    }
}

/* === Sync badge colors (consistent semantic palette) === */
.sync-badge { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; font-size: 13px; }
.sync-badge .dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.sync-never .dot { background: #9ca3af; }
.sync-never { background: #f3f4f6; color: #4b5563; }
.sync-running .dot { background: #f59e0b; animation: pulse 1.5s infinite; }
.sync-running { background: #fffbeb; color: #92400e; }
.sync-success .dot { background: #10b981; }
.sync-success { background: #ecfdf5; color: #065f46; }
.sync-failed .dot { background: #ef4444; }
.sync-failed { background: #fef2f2; color: #991b1b; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
.muted { color: #6b7280; }
```

Also add `data-label` attributes to each `<td>` in the senders-table fragment so the collapse-to-cards CSS shows labels:
```html
<td data-label="Sender">…</td>
<td data-label="Count">…</td>
<td data-label="Unread">…</td>
<td data-label="Oldest">…</td>
<td data-label="Score">…</td>
<td data-label="Actions" class="actions-col">…</td>
```

### GROUP 4 — DEPLOYMENT DOCUMENTATION RUNBOOK (LOCALHOST LANDING PAGE FIRST)

New file at repository ROOT: `/Users/bytedance/Documents/trae_projects/TidyMail/DEPLOY.md`

5 sections:

#### §0 Prerequisites

- macOS host (tested on Sonoma 14.x)
- Homebrew installed (`/usr/local/bin/brew` or `/opt/homebrew/bin/brew`)
- `JAVA_HOME=21` already set; verify with `java -version` should print `openjdk version "21.x.x"`. If not, run `brew install openjdk@21 && echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc && source ~/.zshrc`.
- Docker runtime (EXPLICIT precheck walkthrough):
  1. Open Terminal.
  2. Run: `docker info`.
  3. If output shows `Server Version: 2x.x.x` and `Operating System:` (Colima / Docker Desktop) with no `Cannot connect to the Docker daemon` error → **GREEN GO, skip rest of §0**.
  4. If `docker info` FAILS:
     - **Path A (Colima, recommended):**
       ```
       HOMEBREW_NO_AUTO_UPDATE=1 brew install colima
       colima start --cpu 2 --memory 3 --disk 20
       ```
       Wait for `INFO[00xx] colima started` message. Then re-run `docker info` to confirm.
     - **Path B (Docker Desktop alternative):** Download from docker.com → install → open app → wait for "Docker Desktop is running" whale in menu bar → re-run `docker info`.
  5. **CRITICAL LESSON (experience-988340):** NEVER run `docker compose up` BEFORE confirming `docker info` succeeds. Starting compose before the docker VM is ready causes: Postgres initdb inside a corrupted volume, `Connection refused` that requires full `docker compose down -v` volume wipe + restart. Precheck. Every. Time.

#### §1 Quickstart localhost

Copy-paste one-liners in order:

```bash
# 1. Ensure colima/docker is running (§0 precheck already passed!):
#    (rerun if VM stopped)
colima status || colima start --cpu 2 --memory 3 --disk 20

# 2. Start Postgres container in background (first-run: pulls PG 16 image ~100MB)
docker compose up -d db

# 3. Wait 10 seconds for PG initdb to finish (count silently in head)
sleep 10

# 4. Build fat JAR with tests skipped (tests already green 54/54 from Phase 7 closeout)
mvn -q package -DskipTests

# 5. Boot app with Flyway migrations enabled, port 8080
java -Xms512m -Xmx1g -jar target/tidymail-0.1.0.jar \
  --server.port=8080 \
  --spring.flyway.enabled=true

# Wait for log line: "Started InboxCleanerApplication in X.XXX seconds"

# 6. Open in your default browser
open http://localhost:8080
```

Expected flow in browser:
- Redirects to `/login`
- Click **Continue with Google** (real OAuth — requires you have `.env` with real `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`; if not, see Phase 4 UAT for how to get them)
- OAuth consent screen → Allow
- Back at `/dashboard` — sender table populates, sync-status widget in sidebar shows "Never run" → after 1 min shows "Synced HH:MM · 100 msgs"

#### §2 Env var production rollover guide (for future public deployment)

**Regenerate crypto secrets once before exposing publicly:**
```bash
# New TOKEN_ENCRYPT_SECRET = 64 hex chars (32 bytes → AES-256)
openssl rand -hex 32
# Output example: a1b2c3d4e5f601234567890abcdef01234567890abcdef01234567890abcdef

# New TOKEN_ENCRYPT_SALT = 32 hex chars (16 bytes for HKDF)
openssl rand -hex 16
# Output example: deadbeefcafebabe0123456789abcdef
```
**WARNING:** Changing either secret invalidates ALL existing encrypted tokens in DB. Users must re-login. Don't change after dogfood unless you're OK with that.

**Update OAuth redirect URIs in Google Cloud Console:**
1. Go to console.cloud.google.com → APIs & Services → Credentials → your OAuth 2.0 Client ID.
2. Under **Authorized redirect URIs**, ADD your future public tunnel host's callback:
   - `https://your-random-subdomain.trycloudflare.com/login/oauth2/code/google`
   - Keep the existing `http://localhost:8080/login/oauth2/code/google` entry so localhost dogfood still works.
3. Click **Save**. (Microsoft Azure has the same pattern — add your tunnel URL under App Registration → Authentication → Redirect URIs.)

#### §3 Optional: cloudflared quick-tunnel for a public HTTPS test URL

Install the CLI (install once, don't auto-run):
```bash
brew install cloudflared
```

> **Run MANUALLY when you want a public HTTPS URL:**
> ```
> cloudflared tunnel --url http://localhost:8080
> ```
> Cloudflare prints a URL like `https://some-random-words.trycloudflare.com`. Share it with yourself / testers for HTTPS OAuth callbacks.
>
> **KILL SWITCH:** This tunnel EXPOSES `localhost:8080` publicly on the internet. Anyone with the URL can hit your TidyMail instance (including `/login` page, though they can't log in without Google OAuth). When you're done testing, press **Ctrl-C** in the cloudflared terminal to kill the tunnel immediately. The URL stops working instantly after Ctrl-C.

#### §4 Troubleshooting (bullets for common localhost issues)

- **`postgres password authentication failed` error on app boot:** Check your `.env` file's `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` match the `docker-compose.yml` `POSTGRES_USER` / `POSTGRES_PASSWORD` values. Defaults in compose are `tidymail` / `tidymaildev`. If you copied old `.env` with `postgres` user, you'll hit this. Quick fix: `cp .env.example .env` then edit.
- **`SchemaValidator: missing column 'X' in 'Y'`** / Flyway "found no migrations" error: You forgot `--spring.flyway.enabled=true` on the `java -jar` command in §1 step 5. Flyway never ran V1–V4 migrations so tables are missing columns. Stop app, re-boot with flag added. If still broken, `docker compose down -v` (wipes data volume) → re-start from §1 step 2.
- **Docker disk full errors** (`No space left on device` in Postgres container log or `docker info` shows data-space near zero):
  ```bash
  docker image prune -af
  docker builder prune -af
  colima list
  # If DISK column shows 95%+ used:
  colima stop && colima start --cpu 2 --memory 3 --disk 40
  # (colima auto-ignores shrinking; grow from 20 → 40 is fine)
  ```
- **`Connection refused` on localhost:8080:** App crashed during boot. Check terminal output for: (a) missing `TOKEN_ENCRYPT_SECRET` env var (CryptoStartupValidator logs a stacktrace with openssl one-liners — copy into `.env`), (b) Postgres container not running (`docker compose ps` shows db status not `Up`), (c) Java 21 not set (`java -version` prints 17 or earlier).

---

## 3. Task List (executable order, tracer-first)

### Tracer T8(a) — Pure Java Tests (2 tracers)

TK-8.1. Create POJO test patterns for ScheduleConfig annotation presence + cron validation. No Spring context.

TK-8.2. **T8a_pureJavaTests_01** — Verify via reflection that 3 `@Scheduled` beans exist with correct annotation attributes:
- `UserActionCleanupJob.cleanupExpiredActions` → `@Scheduled.cron() = "0 0 * * * *"` (non-blank string match).
- `SenderStatsVacuumJob.vacuumStaleAccounts` → `@Scheduled.cron() = "0 0 2 * * *"`.
- `IncrementalSyncJob.incrementalSyncAllActiveAccounts` → `@Scheduled.fixedRate() = 300000L` AND `@Scheduled.initialDelay() = 60000L`.
- Bonus: `ScheduleConfig` class has `@Configuration` + `@EnableScheduling` annotations present.
Use Java reflection `Class.forName("com.tidymail.job.UserActionCleanupJob")` + `getMethod("cleanupExpiredActions")` + `getAnnotation(Scheduled.class)`. Cron string non-blank check + expected value equals. 3 sub-assertions.

TK-8.3. **T8a_pureJavaTests_02** — UserAction cleanup predicate filter expired vs undone. Pure Java:
- Build 4 UserAction rows in memory: (1) expiresAt = past, undoneAt = null → SHOULD delete; (2) expiresAt = past, undoneAt = past (already undone) → KEEP (audit); (3) expiresAt = future, undoneAt = null → KEEP (undo window open); (4) expiresAt = past, undoneAt = future → KEEP (undone case).
- Apply predicate `(row) -> row.getExpiresAt().isBefore(now) && row.getUndoneAt() == null` (same logic the job uses) → assert exactly 1 row passes filter.
- Verify Semaphore guard helper: 2 sequential tryAcquire calls on `new Semaphore(1)` → first true, second false (overlap prevention). 3 sub-assertions.

T8(a) subtotal: **≥ 2 tests**, 0 Spring context.

### Tracer T8(b) — Persistence Tests (3 tracers)

TK-8.4. Write Flyway V4 migration + H2 override copy. Add `lastLoginAt` field to UserAccount entity. Create `SyncProgress` entity + `SyncProgressRepository` + findTopBy finder. Create V4 in both main/test migration dirs.

TK-8.5. **T8b_persistenceTests_01** — SyncProgress V4 migration + findTopBy desc order. H2 @DataJpaTest:
- SchemaValidator passes (V1+V2+V3+V4 all green).
- Insert Account(id=1) → insert 3 SyncProgress rows for accountId=1, jobType="INCREMENTAL_SYNC" with (status=RUNNING, id=10), then (status=SUCCESS, id=11), then (status=FAILED, id=12) — same timestamp on all.
- `findTopByAccountIdAndJobTypeOrderByIdDesc(1L, "INCREMENTAL_SYNC")` returns row with id=12 (desc order, latest by id wins). Assert status=="FAILED".
- Insert 4th row (status=SUCCESS, id=13) → next findTopBy returns id=13 status=="SUCCESS". 2 sub-assertions.

TK-8.6. **T8b_persistenceTests_02** — lastLoginAt on UserAccount roundtrip + 90d-stale subquery. H2 @DataJpaTest:
- Insert Account A: lastLoginAt = now().minus(91, DAYS). Insert Account B: lastLoginAt = now().minus(1, DAYS). Insert Account C: lastLoginAt = null (never logged in).
- Insert 2 SenderStat rows for Account A, 3 for Account B, 1 for Account C.
- `userAccountRepository.findIdsByLastLoginAtBefore(now-90d)` returns exactly [A.id].
- `senderStatRepository.deleteByAccountIdIn([A.id])` returns 2. After delete, SenderStat.count() = 4 (B's 3 + C's 1). 2 sub-assertions.

TK-8.7. **T8b_persistenceTests_03** — SenderStatsVacuumJob delete 90d-stale correctly end-to-end. H2 @SpringBootTest:
- Same fixture as T8b_02 (A stale, B fresh, C null).
- `@Autowired SenderStatsVacuumJob vacuumJob;` call `vacuumJob.vacuumStaleAccounts()` directly.
- Assert SenderStat total count drops from 6 → 4.
- Account B's 3 rows still present. Account C's 1 row still present (null lastLoginAt treated as active). 2 sub-assertions.

T8(b) subtotal: **≥ 3 tests**.

### Tracer T8(c) — MVC Tests (3 tracers)

TK-8.8. Create `SyncProgressRepository.java` + wire into `FragmentController`. Add sync-progress-badge.html fragment. Inject `<div id="sync-status-slot" …>` into dashboard.html sidebar.

TK-8.9. **T8c_mvcTests_01** — GET /sync-progress-frag empty → badge "Never run". MockMvc with mock OAuth (no SyncProgress rows in DB yet):
- HTTP 200.
- Response body contains class `sync-never` (or text "Never run").
- Response body does NOT contain `sync-running` / `sync-success` / `sync-failed` classes. 2 sub-assertions.

TK-8.10. **T8c_mvcTests_02** — GET /sync-progress-frag with RUNNING row → yellow badge + auto-refresh attribute. Insert SyncProgress(accountId=loggedInUser, jobType=INCREMENTAL_SYNC, status=RUNNING):
- HTTP 200.
- Contains `class="sync-badge sync-running"` (yellow class present).
- Contains `hx-trigger="every 15s"` attribute in some element (FragmentController injects hx-trigger into the returned div's root element when status==RUNNING, OR the dashboard container has it; either way the hx-trigger="every 15s" string is somewhere in the GET /dashboard page — tracer verifies on dashboard.html render). 2 sub-assertions.

TK-8.11. **T8c_mvcTests_03** — GET /sync-progress-frag SUCCESS → green; /dashboard auth → sync-status-slot div present. 2 assertions:
- (a) Insert SUCCESS SyncProgress row. GET /sync-progress-frag → body contains `sync-success` class and text "Synced".
- (b) MockMvc GET /dashboard authenticated → response body CONTAINS the string `id="sync-status-slot"` AND `hx-get="/sync-progress-frag"` AND `hx-trigger="load, every 15s"` (the 3 key attributes of the injected div). 3 sub-assertions (2a green badge + 2b slot div attrs).

T8(c) subtotal: **≥ 3 tests**.

### Tracer T8(d) — Lint + Doc Tests (2 tracers)

TK-8.12. Write DEPLOY.md to repo root. Add @media blocks to dashboard.html + reply-zero.html <style> sections.

TK-8.13. **T8d_lintAndDocTests_01** — DEPLOY.md file exists + contains 3 required substrings. Pure Java `java.nio.file.Files.readString`:
- File exists at repo root `/DEPLOY.md`.
- Contains literal string `docker info` (§0 precheck).
- Contains literal string `cloudflared tunnel --url http://localhost:8080` (§3 manual run blockquote).
- Contains §4 troubleshooting string `SPRING_DATASOURCE` (env-var mismatch bullet). 3 sub-assertions.

TK-8.14. **T8d_lintAndDocTests_02** — dashboard.html contains media-query for 375px OR 640px breakpoint. Files.read both dashboard.html + reply-zero.html:
- dashboard.html contains either `@media (max-width: 640px)` OR `@media (min-width: 375px)` (both acceptable; at least one mobile breakpoint present).
- dashboard.html contains `min-height: 44px` (touch target ≥ 44pt rule).
- reply-zero.html contains at least one `@media` block (mobile audit applied). 3 sub-assertions.

T8(d) subtotal: **≥ 2 tests**.

### Wave 1 expansion — cross-cutting wiring + integration

TK-8.15. Wire ScheduleConfig + all 3 job beans. Add `lastLoginAt` setter call inside `OAuthLoginSuccessListener.onAuthenticationSuccessEvent(...)` after the userAccount is loaded (set `userAccount.setLastLoginAt(Instant.now()); repo.save(account);`). Add new repository methods to UserAccountRepository / SenderStatRepository / UserActionRepository.

TK-8.16. Add `OAuthLoginSuccessListener` test tracer (if not already covered) or just wire the setter. Confirm Phase 2 SecurityConfigIntegrationTest still passes (listener shouldn't break auth event chain).

TK-8.17. Dashboard.html CSS audit: actually add the @media blocks inline. Manually shrink browser to 375px after jar boot to spot-check (no automated browser = V smoke deferred to user eye-test; T8d_02 at least confirms breakpoint strings are PRESENT in the HTML).

TK-8.18. Reply-zero.html same CSS audit copy-paste + tweak for reply-zero-grid layout collapse.

TK-8.19. **Cross-suite regression run:** All Phase 2 (6 T2) + Phase 3 (4 T3) + Phase 4 (11 T4) + Phase 6 (≥ 15 T6) + Phase 7 (≥ 18 T7) + T8 new (≥ 10 T8) → **target ≥ 64 tests green (54 baseline + 10 T8)**. Locked minimum: 54 baseline + 10 T8 = **64/64**.

---

## 4. Verification Criteria

| ID | What to verify | Pass condition |
|---|---|---|
| V-8.1 | Existing actuator + login (mockMvc shortcut). Reuse Phase 7 V-7.1 patterns: `MockMvc` GET `/actuator/health` → 200 body contains `"status":"UP"`; GET `/login` → 200 HTML with class `login-brand` (still renders after adding ScheduleConfig — no bean wiring conflict). | Both endpoints 200. No bean conflict from @EnableScheduling (some Spring Boot versions need extra config — if actuator fails, the conflict is caught here fast). |
| V-8.2 | MockMvc schedule reflection: IncrementalSyncJob scheduled method present with correct fixedRate 300000 annotation attribute. Same assertion pattern as T8a_01 but done inside a @SpringBootTest that actually loads the bean context (catches cases where reflection pass but bean wasn't registered because a dependency was missing — e.g. UserActionRepository not injected so @Component never scanned). Load context → `@Autowired IncrementalSyncJob job` → job is not null → reflection on job class method confirms `@Scheduled(fixedRate=300000, initialDelay=60000)`. Same check for cron job classes (UserActionCleanupJob + SenderStatsVacuumJob both autowire OK). | All 3 job beans @Autowire successfully. fixedRate 300000 + initialDelay 60000 on Incremental verified. Cron attrs non-blank matches for other two. |
| V-8.3 | Dashboard mockMvc render has sync-status-slot htmx every 15s. Same as T8c_03 but done as V-smoke: MockMvc auth → GET `/dashboard` → `content()` contains: (1) `id="sync-status-slot"`, (2) `hx-get="/sync-progress-frag"`, (3) `hx-trigger="load, every 15s"`. Three substring matches. All three must be in the same HTML response. | All 3 strings present in response body. htmx attributes use exact comma-space separator from spec (not "every 15s" without load trigger — the dashboard widget fires BOTH on page load AND every 15s thereafter). |
| V-8.4 | (a) DEPLOY.md exists on disk + 3 required substrings: `docker info`, `cloudflared tunnel --url http://localhost:8080`, `SPRING_DATASOURCE`. (b) dashboard.html has `@media` blocks with 375px OR 640px breakpoints AND has `min-height: 44px` touch-target rule. Same as T8d_01/02 but bundled as V-smoke gate (fail if any missing). | 3 substrings in DEPLOY.md (3/3). @media + min-height:44px in dashboard.html (2/2). reply-zero.html @media present (1/1). Total 6/6 substring checks PASS. |
| V-8.5 | Real Postgres V4 migration PASS (jar boot + psql confirm sync_progress + user_accounts.last_login_at columns). Follow Phase 3 V-3.5 exact commands pattern: (1) `docker compose up -d db` wait 10s; (2) `mvn -q package -DskipTests`; (3) `java -Xms512m -Xmx1g -jar target/tidymail-0.1.0.jar --server.port=8080 --spring.flyway.enabled=true` wait for Started message; (4) actuator health 200; (5) `psql $DATABASE_URL -c "\d user_accounts"` → column `last_login_at` present with type `timestamp with time zone`; (6) `psql $DATABASE_URL -c "\d sync_progress"` → all 8 cols present (id, account_id, job_type, status, started_at, finished_at, messages_scanned, error_message). | Jar boots in <10s. Flyway log "Successfully applied 1 migration (execution time …)" for V4. psql confirms both new column + new table. No SchemaValidator errors. |
| V-8.6 | FULL LIVE DOGFOOD — **DEFERRED TO USER MANUAL, not executed by agent.** User runs: (1) §0 docker info precheck PASS; (2) §1 one-liners (colima → compose up -d db → sleep 10 → mvn -q package → java -jar → open http://localhost:8080); (3) browser loads login page → clicks Continue with Google → real OAuth consent screen → back to dashboard; (4) waits up to 60 seconds for initial incremental sync tick → sidebar shows "Synced HH:MM · 100 msgs" green badge; (5) clicks Archive on a real newsletter sender → undo banner "Archived N messages from X · [UNDO]" appears; (6) clicks UNDO within 5s → banner changes to "N messages restored to inbox ✓"; (7) opens real Gmail UI in another tab → confirms those N messages ARE back in Inbox (undo inverse actually worked on live Gmail). PASS/FAIL marked by user in 08-02-UAT.md; does NOT block phase closeout if user chooses to defer — but is the MVP "done" gate. | Manual. If user runs: 7 steps all PASS = V-8.6 PASS. If user defers: mark "DEFERRED — requires user to run localhost dogfood with real Google OAuth + browser" in 08-02-UAT.md. Phase 8 can close with V-8.6 DEFERRED (explicit user instruction: agent doesn't execute browser login). |
| V-8.T | T8 tracer suite aggregate. T8(a) ≥ 2, T8(b) ≥ 3, T8(c) ≥ 3, T8(d) ≥ 2 → **T8 total ≥ 10 tracers minimum.** Cross-suite regression: baseline 54 (T2 6 + T3 4 + T4 11 + T6 15 + T7 18) + T8 ≥ 10 = **64 tests green minimum, 0 failures, 0 errors.** | Surefire reports 0 failures, 0 errors. Total count ≥ 64. Locked per spec: 54 baseline + T8 ≥ 10. |
| V-8.A8 | Pitfall A8 mobile audit — 375px no horizontal scroll. V-smoke is T8d_02 automated string-presence check. For manual eye-test after jar boot: shrink Chrome DevTools device mode to iPhone SE (375×667) → open Dashboard → Senders table collapses to cards, buttons all clickable, no x-overflow scrollbar; open Reply-Zero → cards stack, no x-overflow; Sync badge widget readable. QA-UX-AUDIT checklist item 10 ("No horizontal scroll at 375px") = PASS. | Automated: T8d_02 PASS (breakpoint + touch-target strings present). Manual eye-test deferred to user in V-8.6 browser session. If user runs, explicitly document 375px pass/fail for each page. |

---

## 5. Decisions recorded here (D-8.x)

**D-8.1** — Spring default single-threaded TaskScheduler used (no custom pool). Why? 3 staggered jobs: (a) cron hourly runs in ~1ms (bulk delete), (b) cron 2am runs in ~50ms (bulk delete), (c) fixedRate 5min runs per-account with semaphore so each account syncs sequentially — worst-case 10 accounts × 3 seconds each = 30 seconds, far less than 5-min interval. Single thread keeps thread-pool configuration out of MVP (no spring.task.scheduling pool config). Rating: **Two-way.** Easy to add pool size later via ScheduleConfig @Bean if accounts > 100.

**D-8.2** — SyncProgress findTopBy DESC by ID (not timestamp). Why? Multiple rows can have identical started_at / finished_at within a clock tick (1ms resolution). IDs are monotonic per Postgres BIGSERIAL (INSERT order), so `ORDER BY id DESC` = latest run. Simpler than adding unique-timestamp constraint. Rating: **Two-way.** Could change to finished_at DESC NULLS LAST + id DESC if needed; no migration required (index already covers account_id + job_type + id DESC).

**D-8.3** — SenderStatsVacuumJob treats NULL lastLoginAt as ACTIVE (not deleted). Why? backward compat: rows created by Phase 3/4 OAuth listeners BEFORE lastLoginAt was added will have NULL. If we treated NULL as stale, we'd wipe all existing sender_stats on first 2am run. Explicit guard in T8b_02 + T8b_03: Account C (null) has rows retained. Fix-up migration could backfill lastLoginAt = createdAt for old rows, but MVP doesn't need it. Rating: **Two-way.** Add backfill migration later if needed.

**D-8.4** — IncrementalSyncJob syncs ONLY 1 page of 100 messages (provider.listMessages(account, null, 100)). No Threads, no reply-zero recompute. Why? "cheap incremental" — 1 page = 1 list + 100 metadata gets = 101 Gmail API calls per account per 5 minutes. Over 1 hour = 1212 calls per account; Gmail free-tier quota is 1,000,000,000 calls/day → 12k calls is nothing. Full sync (500+ messages, reply-zero thread dives) would be way more expensive; that's reserved for on-demand manual _sync POST or Phase 9. Rating: **Two-way.** Page size or interval can be bumped via future env var config.

**D-8.5** — IncrementalSyncJob tryAcquire (non-blocking) not acquire(). If a per-account sync takes longer than 5 min (e.g. Gmail API slow 429), next 5-min tick fires and skips that account (semaphore tryAcquire → false → log warn → skip). No queue, no backup of pending ticks. Next tick in 5 min gets another shot. Prevents dead semaphores if exception escapes before finally block (shouldn't happen because finally always releases — but belt-and-suspenders). Rating: **Two-way.** Could switch to blocking acquire() with timeout if we want guaranteed-run instead of skip.

**D-8.6** — Mobile CSS written inline inside `<head><style>` of dashboard.html + reply-zero.html, NO external CSS file. Why? User explicitly said "NO external css file (keep simple, no asset pipeline complexity)". Avoids Spring MVC resource handler config, versioning, cache-busting query params, etc. Duplication between two files is acceptable (copy-paste ~200 lines, can extract to Thymeleaf th:include in Phase 9 if needed). Rating: **Two-way** refactor to common CSS later if pipeline is added.

**D-8.7** — DEPLOY.md §3 cloudflared is "install only, manual run". CLI installed via brew but NOT launched as a service. User explicitly runs `cloudflared tunnel --url http://localhost:8080` when they want a tunnel, and Ctrl-C kills it. No auto-start, no systemd/launchd, no config.yml. Kill-switch is immediate + explicit (documented in §3 blockquote). Why? minimize always-on exposure of a JVM with OAuth token decryption keys on the public internet — tunnel only lives for 10 minutes while user demos to someone. Rating: **Two-way.** Can write cloudflared config.yml + launchd plist in Phase 9 if user wants always-on.

---

## 6. Risks & Mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R8.1 | `docker info` precheck skipped by user → starts `docker compose up -d db` before Colima/Docker Desktop daemon is ready → Postgres initdb runs partially in corrupted volume → `Connection refused` that can't be recovered without `docker compose down -v` volume wipe. (Experience-988340 lesson from STATE.md B15.) | high | medium | (a) DEPLOY.md §0 puts docker info precheck BEFORE ANY compose command, with explicit RED warning and numbered steps. (b) §4 troubleshooting bullet 3 documents the `docker compose down -v` wipe recovery. (c) T8d_01 tracer asserts that DEPLOY.md literally contains `docker info` string — if the precheck paragraph is accidentally deleted, test fails. |
| R8.2 | `@Scheduled` + `@Transactional` on job methods: Spring's default TaskScheduler thread does NOT have a transaction-bound EntityManager by default → UserActionRepository.deleteBy... (marked @Modifying @Transactional) throws `TransactionRequiredException` or runs in auto-commit and fails. | medium | high | ALL job methods annotated with `@Transactional` explicitly — import `org.springframework.transaction.annotation.Transactional`, NOT javax. AND verify: job classes are `@Component` (so CGLIB proxy wraps @Transactional calls). T8b_03 calls SenderStatsVacuumJob.vacuumStaleAccounts() directly from @SpringBootTest context → if tx is missing, the test throws and fails FAST before closeout. |
| R8.3 | IncrementalSyncJob ConcurrentHashMap.computeIfAbsent(Semaphore) under concurrent ThreadPoolTaskScheduler threads — if Spring scheduler is upgraded to multi-thread later, 2 threads could race on computeIfAbsent and leak semaphores. | low | low | Use `ConcurrentHashMap` (thread-safe map) + `Semaphore` (thread-safe sync primitive). computeIfAbsent is atomic per ConcurrentHashMap contract. MVP uses single-threaded scheduler so even with buggy code it can't trigger. If pool grows later, code is already correct. T8a_02 pure-java Semaphore(1) tryAcquire guard test validates overlap behavior. |
| R8.4 | Flyway V4 H2 vs Postgres dialect mismatch. V3 had dialect gaps (JSONB→JSON, INTERVAL syntax) — V4 adds TIMESTAMPTZ differences and BIGSERIAL vs BIGINT AUTO_INCREMENT for sync_progress.id. | medium | high | Mirror Phase 3 B13 pattern: H2 migration file under `src/test/resources/db/test-migration/V4__...sql` with explicit H2 types. T8b_01 H2 @DataJpaTest runs SchemaValidator on H2 first → catches dialect drift BEFORE jar boot against real Postgres. V-8.5 real-PG psql confirm gate catches any remaining drift. |
| R8.5 | UserAccount.lastLoginAt = null for rows created before this migration. SenderStatsVacuumJob deletes rows WHERE last_login_at < now-90d → NULL lastLoginAt rows pass the comparison? Postgres: NULL < anything → NULL (not true) → NOT included in < comparison → preserved. H2 same behavior. BUT if a future developer writes a "WHERE NOT (last_login_at >= cutoff)" inverted query, NULL would be included (de Morgan's law with 3-valued logic). | medium | medium | Explicit D-8.3 + T8b_02 + T8b_03 both assert: account with null lastLoginAt → NOT included in stale list, rows NOT deleted. If inverted query pattern ever introduced, new tracer needed. V-8.T cross-suite regression catches any deletion drift. |
| R8.6 | htmx `hx-trigger="load, every 15s"` on the sync-status-slot div causes an N+1 problem on the database. 1 user = 1 request every 15s × 8h workday = 1920 queries. With 10 users = 19,200. All hitting findTopBy... which uses index `idx_sync_progress_account_job` (B-tree index on 2 cols + DESC id). Even at 1M rows in sync_progress table, indexed lookup < 1ms. | low | low | Index covers query perfectly. If N becomes 1000 users, add in-memory Caffeine cache 10s TTL, or bump every 15s → every 60s. For MVP 1-user dogfood: N=1920/day, negligible. Could also remove `load,` trigger and rely only on `every 15s` after first paint — but load is needed to show status immediately on page open. Keep both. |
| R8.7 | Mobile CSS inline duplication between dashboard.html + reply-zero.html gets out of sync. Edit breakpoint in one file, forget the other → reply-zero looks broken on 375px. | medium | low | T8d_02 asserts BOTH files have @media blocks (presence check). No automated diff check of inline block content. Manual eye-test in V-8.6 browser session catches visual drift. If visual regression found post-closeout, refactor to Thymeleaf th:include of a shared CSS fragment. |
| R8.8 | Cloudflared tunnel user forgets Ctrl-C → TidyMail JVM with decryption keys exposed publicly on random trypage URL for hours/days. JVM port 8080 proxied through Cloudflare to the world. | medium | high | (a) DEPLOY.md §3 KILL SWITCH box is BLOCKQUOTE with explicit Ctrl-C instruction. T8d_01 asserts the `cloudflared tunnel --url ...` manual-run string is present (not a background `--daemon` flag pattern). (b) SecurityConfig still requires auth on all pages — random visitors hit `/login` page; they can Google/Microsoft OAuth but would need YOUR google account to get past login. Still bad (phishing / fake login page). (c) Recommendation in §3: only run tunnel when actively demoing to someone, never leave overnight. (d) For permanent deployment later: add explicit IP allowlist or Basic Auth secondary gate before public exposure. |
| R8.9 | DEPLOY.md regenerate-crypto-secrets step (§2) user runs `openssl rand -hex 32` → outputs 64 chars → pastes into `.env` as `TOKEN_ENCRYPT_SECRET` → CryptoUtil hex-first decode succeeds (correct). BUT if user accidentally truncates to 63 chars (misses last char), hex decode throws and app hard-fails boot. | low | medium | CryptoStartupValidator (Phase 3) already validates both env vars present + non-blank; CryptoUtil hex decode fails fast with good error message if invalid hex. §2 instructions show output of EXACT commands (64-char / 32-char outputs) — copy-paste not manual type. If user still messes up: §4 troubleshooting add a crypto bullet referencing the validator error and openssl re-run. |
| R8.10 | Dashboard.html `data-label` attributes missing on table `<td>` elements → 375px collapse-to-cards CSS `td::before content: attr(data-label)` shows empty labels → cards have 6 empty "label:" prefixes before values. Looks broken. Aesthetic regression. | medium | low | Manually add `data-label="Sender"`, `data-label="Count"`, etc. to each `<td>` in senders-table fragment when editing the fragment template. T8d_02 doesn't catch this (string-only check) — relies on V-8.6 user eye-test at 375px. If visual bug found in dogfood, fix is trivial: add 6 attributes. |

---

## 7. Phase Summary (post-closeout)

| Metrics | Target | Actual |
|---|---|---|
| Main code files written | ~16 (ScheduleConfig config ×1, Job classes ×3=UserActionCleanupJob+SenderStatsVacuumJob+IncrementalSyncJob, SyncProgress entity ×1, SyncProgressRepository ×1, SyncProgressTracker helper ×1, Entity edits: UserAccount.java (+lastLoginAt), UserActionRepository (+delete method), SenderStatRepository (+deleteByAccountIdIn), UserAccountRepository (+findIdsByLastLoginAtBefore + findAllActiveLoggedInAccounts), OAuthLoginSuccessListener (+lastLoginAt setter on auth), FragmentController (+sync-progress-frag endpoint), Flyway V4.sql ×2=main+H2, Templates: dashboard.html sidebar slot + inline CSS edit, reply-zero.html inline CSS edit, fragments/sync-progress-badge.html NEW = 1 file, DEPLOY.md NEW = 1 file) | TBD |
| Test code files written | 1 (Phase8SyncDeployTest.java with 4 @Nested classes: T8a_PureJavaTests, T8b_PersistenceTests, T8c_MvcTests, T8d_LintAndDocTests) | TBD |
| Flyway migrations | V4.sql × 2 (main PG + H2 test override) | TBD |
| T8 new tracers | ≥ 10 (T8a ≥ 2, T8b ≥ 3, T8c ≥ 3, T8d ≥ 2) · Locked minimum per spec: **≥ 10** | TBD |
| Total test suite green (T2+T3+T4+T6+T7+T8) | ≥ 64 green, 0 failures, 0 errors · Locked minimum per spec: **54 baseline + 10 T8 = 64** | TBD |
| V smokes (automated + manual deferred) | 6 total (V-8.1, V-8.2, V-8.3, V-8.4, V-8.5 PASS automated; V-8.6 DEFERRED user-manual live dogfood if no creds/browser yet) | TBD |
| Known bugs injected | 0 (target) · A8 mobile, J3 scheduler, J4 pool pitfalls explicitly tested. Experience-988340 colima/docker precheck documented in DEPLOY.md §0 + enforced by T8d_01 string-presence tracer | TBD |
| Pitfall mitigations verified | Critical: A8 (375px no horizontal scroll — T8d_02 CSS string presence + V-8.6 eye-test); J3 (single-instance deployment in DEPLOY.md §1); J4 (per-account semaphore 1 concurrent); experience-988340 (docker info precheck). Bonus: J9 (all templates th:text, no th:utext audit carried from Phase 7) | TBD |
