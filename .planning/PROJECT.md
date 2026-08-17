# PROJECT.md — TidyMail

| Field | Value |
|---|---|
| **Project Name** | **TidyMail** |
| **Tagline** | Bulk-clean Gmail and Outlook inboxes with confidence — no AI, no surprises. |
| **One-line description** | A self-hosted Java/Spring Boot web app that connects to Gmail and Outlook, summarizes inboxes by sender, and lets you take safe, reversible bulk actions on thousands of emails at once. |

---

## What This Is

TidyMail is a self-hosted Java/Spring Boot inbox cleanup tool for Gmail-first metadata-only triage. It groups mail by sender and deterministic category, lets the user stage exact message-ID cleanup actions for Review, and keeps workspace analytics explainable without reading message bodies.

## Core Value

Users can clean large amounts of inbox clutter through exact, explainable, reversible actions without exposing message bodies or relying on AI.

## Requirements

See the current milestone requirements in this document and the canonical requirement ledger in `.planning/REQUIREMENTS.md`.

---

## 1. What TidyMail Is — And What It Is NOT

### What it IS

TidyMail is a **deliberately small**, **non-AI** inbox cleanup tool. It does one family of things well:

1. You sign in with Google or Microsoft via OAuth.
2. It pulls your **email metadata only** (headers, labels, sender, subject line, `List-Unsubscribe`, thread references — never full bodies).
3. It groups mail by sender / domain, counts totals and unreads, and applies deterministic heuristics to surface bulk-marketing noise (has `List-Unsubscribe` header, `Precedence: bulk`, sender you've never replied to).
4. You see a **bulk-actions page**: checkboxes next to sender rows → one click to archive / unsubscribe / mark-read / trash every email from that sender.
5. You see a **Reply Zero queue**: a short list of personal-looking emails in your Inbox that you haven't replied to yet (pure thread-metadata heuristics — no LLM reading).
6. All actions are **reversible** via an append-only undo log.

TidyMail is **inspired by** the open-source project [elie222/inbox-zero](https://github.com/elie222/inbox-zero) but is **not a fork** and is **deliberately simpler in every dimension**. Where inbox-zero ships an LLM matrix, AI auto-drafts, chat-with-email, AI categorization, Stripe billing, a React SPA, and BullMQ queues, TidyMail ships **none of those**. TidyMail is the boring, deterministic, maintainable 20% of features that handle 80% of the actual inbox-clutter problem.

### What it IS NOT

- It is **NOT an AI or LLM product**. No model calls, no chat, no summarization, no auto-drafts, no AI-powered categorization.
- It is **NOT a full email client**. You can't compose or read individual emails in it. Use Gmail / Outlook on the web for that.
- It is **NOT a SaaS product with sign-ups open to the internet by default**. It starts as a personal self-hosted tool for you (and optionally family / a small trusted group).
- It is **NOT a Cloudflare Workers app**. Workers run V8/Wasm; TidyMail is a JAR and needs a real JVM. Cloudflare's network sits in front via a tunnel, it doesn't *host* the backend.
- It is **NOT multi-tenant SaaS-ready on day one**. The data model supports multiple linked mailboxes, but there is no per-user quota, billing, or signup page.

---

## 2. Core Value Proposition

> **Clean an overwhelming inbox in under 10 minutes, one high-leverage sender-group at a time, with zero risk of accidentally deleting something important because every action is exact, counted, previewable, and undoable.**

Most people with 5,000–50,000 emails in their inboxes share the same problem: **individual triage is too slow** (deleting one-by-one) and **bulk "archive all" feels too risky** (was that one important receipt in there?). TidyMail solves this by:

1. **Grouping by sender, not by individual message.** The 500th Amazon order notification is the same decision as the first — decide once, act on all 500.
2. **Showing an exact count before any button runs.** "This will archive 247 emails from newsletters@example.com" — the button runs the same query that produced the count. Always.
3. **Keeping everything reversible.** Every bulk write writes an undo-log row first. "Undo" replays the inverse operation on the exact same message IDs.
4. **Never hiding behind AI magic.** If TidyMail calls something "bulk marketing," it tells you why (`List-Unsubscribe` header present, `Precedence: bulk`, zero replies from you). No black boxes.
5. **Never reading email bodies.** Metadata-only access means fewer OAuth-scopes to approve, a stronger privacy story, and a faster sync.

---

## 3. Target Users

### Phase 1 (v1.0): Personal self-hosted user (one person)

- **Primary user:** You. The person running the JAR on your own VPS / home machine, with your own Postgres database, connecting your own Gmail and/or Outlook accounts.
- **Attributes:** Technically capable enough to `java -jar` a fat JAR, set environment variables, and install `cloudflared`. Values control, privacy, and deterministic behavior over "AI smarts."
- **Mailbox size:** 1,000–50,000 emails per connected account.

### Phase 2 (post-v1, if shipped): Small trusted group

- **Users:** Family members, a small team of friends, a startup of 5–20 people.
- **Deployment model:** Same JAR, same Postgres, same tunnel — just more `UserAccount` rows. The app supports multiple accounts from day one (data model is per-account-scoped), but there is no public sign-up UI; accounts are added via env-var allowlist or manual DB insert.

### Explicitly NOT the target (v1.0)

- Random internet strangers signing up on a public landing page.
- Enterprises needing SSO/SCIM, audit trails, SLAs.
- Teams wanting Slack/Teams bots or mobile push notifications.

---

## 4. Tech Stack Decisions

All decisions below are **locked** and canonicalized in [`docs/DECISIONS.md`](../docs/DECISIONS.md). Do not override any choice without editing that file first.

| Layer | Choice | Decision Reference |
|---|---|---|
| **Language & build** | Java 21+ LTS, Maven, Spring Boot 3.x (parent POM) | `DECISIONS.md` §1 |
| **Auth / OAuth** | Spring Security OAuth2 Client → "Sign in with Google" + "Sign in with Microsoft" buttons (both coexist; buttons render only when real credentials env-vars are set) | `DECISIONS.md` §4 |
| **Frontend** | Thymeleaf server-rendered HTML + minimal htmx for partial DOM swaps. No React, no SPA, no frontend bundler. | `DECISIONS.md` §5 |
| **Persistence** | Spring Data JPA / Hibernate + Postgres JDBC. Options: Neon free tier, Supabase free tier, Tembo, or local Docker Postgres. | `DECISIONS.md` §3, §5 |
| **Background jobs** | Spring `@Scheduled` in-process. No external MQ (no BullMQ / RabbitMQ / Kafka). Personal→small-group traffic does not need a broker. | `DECISIONS.md` §5 |
| **Edge / HTTPS** | Cloudflare network in **front** of the JVM origin via Cloudflare Tunnel (`cloudflared` outbound agent). NOT native Cloudflare Workers — JVM cannot run on V8/Wasm. | `DECISIONS.md` §3 |
| **Gmail client** | Official `com.google.apis:google-api-services-gmail:v1-*`. Request format = **`METADATA` only** with `metadataHeaders=From,To,Subject,Date,List-Unsubscribe,References,In-Reply-To,Message-ID`. Never `full` format. | `DECISIONS.md` §4, §6 |
| **Outlook / O365 client** | Official `com.microsoft.graph:microsoft-graph` Java SDK + `azure-identity`, on-behalf-of refresh token flow. | `DECISIONS.md` §4, §5 |
| **Secrets** | OS env vars override `application.properties`. Optionally `spring-dotenv` for `.env` files. Never commit secrets to the repo. | `DECISIONS.md` §5 |

### Suggested module layout (from DECISIONS.md §5)

```
src/main/java/com/example/inboxcleaner/
  ├── InboxCleanerApplication.java
  ├── config/
  │   ├── SecurityConfig.java          // OAuth2 login + form + CSRF
  │   └── ScheduleConfig.java          // @EnableScheduling
  ├── mail/
  │   ├── MailProvider.java            // interface: listFolders, listMessages, modifyBatch, ...
  │   ├── GmailMailService.java
  │   └── OutlookMailService.java
  ├── senders/
  │   ├── SenderAggregator.java        // group by From, bulk-marketing heuristics, Reply Zero detection
  │   └── BulkActionRunner.java
  ├── jobs/
  │   └── SyncJobs.java                // @Scheduled: refreshCaches, renewWatches
  ├── web/
  │   ├── HomeController.java
  │   ├── SendersController.java       // bulk actions page
  │   └── ReplyZeroController.java
  └── db/
      ├── UserAccount.java             // @Entity one row per linked mailbox
      ├── SenderStats.java
      └── UserAction.java              // append-only decision log for undo
```

---

## 5. Deployment Model

### Shape: Fat JAR + `cloudflared` tunnel → Cloudflare HTTPS domain

```
         [ Browser, any device ]
                  │
                  ▼ HTTPS (443)
         [ Cloudflare Edge (DDoS, WAF, DNS, SSL) ]
                  │
                  ▼ Cloudflare Tunnel (outbound-only from origin)
         [ cloudflared agent, running next to the JAR ]
                  │
                  ▼ localhost:8080
         [ Spring Boot fat JAR — java -jar tidymail.jar ]
                  │
                  ▼ JDBC
         [ Postgres DB — Neon / Supabase / Docker ]
```

### Why this shape (locked decision, `DECISIONS.md` §3)

- Cloudflare Workers run V8 + Wasm. A JVM (threads, JDBC pools, `@Scheduled`) cannot run there. GraalVM Wasm / TeaVM ports are fragile and lose features — not worth it.
- Cloudflare Tunnel gives the **exact user-facing outcome** of a public HTTPS URL on a Cloudflare-managed domain without requiring a public IP, open firewall ports, or self-managed SSL certs on the origin.
- OAuth redirect URIs in Google Cloud Console and Azure App Registration point at the **Cloudflare HTTPS URL** (e.g., `https://tidymail.yourdomain.com/login/oauth2/code/google` and `/code/microsoft`).

### Where to host the JAR

Any long-lived host with Java 21 installed:
- $3–$6/month VPS (Hetzner, DigitalOcean)
- Oracle Cloud Free Tier (Ampere or x86)
- Fly.io via Dockerfile
- A Mac or Linux box at home, exposed purely via the tunnel (no public IP needed)

### Package & run

```bash
mvn clean package                 # produces target/tidymail-0.1.0.jar
# copy JAR + .env to host, then:
java -jar tidymail-0.1.0.jar      # listens on localhost:8080
cloudflared tunnel run tidymail   # outbound connections to CF edge
```

---

## 6. Trust & Privacy Model

Trust is the single most important product property. If a user suspects TidyMail will lose their email, leak it, or read things it shouldn't, they will close the tab and never come back — even if the UI is perfect.

Baseline is carried forward from TidyGmail / InboxTriage and locked in `DECISIONS.md` §6.

### 6.1 Metadata-only access, by default and always

| Concern | Policy |
|---|---|
| Gmail API format | **`format=METADATA` exclusively.** Headers + labels + snippet only. Never request `format=full`. |
| Gmail metadata headers requested | `From, To, Subject, Date, List-Unsubscribe, References, In-Reply-To, Message-ID`. |
| Outlook Graph select | `from,toRecipients,subject,receivedDateTime,internetMessageHeaders,conversationId,parentFolderId,hasAttachments`. Never `bodyPreview` or `body.content` unless a future feature opts in. |
| What is stored in Postgres | Per-row counts, sender/domain strings, label IDs, message IDs, thread IDs, presence-of-header booleans. **Never the Subject text, snippet, or body.** (If Subject is needed for a future UI feature, it should be fetched at render time and never persisted, or stored only with explicit user consent and a TTL.) |
| Logging | No email content (Subject, snippet, body) ever hits an application log. Logs contain counts only ("archived 247 messages for account 42") and opaque IDs (Gmail `messageId` hashes, not the raw RFC 822 `Message-ID`). |

### 6.2 Data retention: the user owns it, and can wipe it

| Concern | Policy |
|---|---|
| Who owns the DB | The person running the JAR. It is their Postgres instance (Neon / Supabase / Docker). |
| Sign-out = full wipe | When a user clicks "Sign out," every database row scoped to their `UserAccount` ID is deleted: `SenderStats`, `UserAction` rows, cached thread metadata, and the OAuth refresh token row. The session cookie is invalidated. |
| Orphaned-session TTL | A Spring `@Scheduled` cron job (daily) deletes any `UserAccount` row whose `last_seen` is older than 24 hours, cascading all user-scoped data. Covers the case where the user closes the tab and never signs out. |
| No cross-user leakage | Every JPA query joins on `account_id` in the WHERE clause. A repository method without an account filter is a P0 security bug, not a nit. |

### 6.3 Reversible decisions: append-only undo log

Every bulk write (archive, trash, mark-read, unsubscribe, delete) happens in two phases:

1. **Write the log first.** Insert a row into `user_actions` with: `account_id`, `action_type`, `sender_key` (or query hash), `message_ids[]` (the exact list), `created_at`. The row is immutable after insert.
2. **Then execute the action.** The Gmail/Graph batch-modify call uses `message_ids[]` from the log row — never a fresh query.

**Undo** = find the latest `user_actions` row for that account + sender_key, load the same `message_ids[]` array, and run the inverse operation (`archive` → `moveToInbox`, `trash` → `restore`, `markRead` → `markUnread`). No navigation away. The undo response returns the updated fragment in place (htmx swap).

### 6.4 Refresh tokens: encrypted at rest

OAuth refresh tokens are long-lived credentials. They are **never stored in plaintext in Postgres**:

- On write: encrypted with AES-256-GCM using a secret loaded from an env var (`ENCRYPTION_KEY`).
- On read: decrypted only within the `MailProvider` implementation that needs it.
- Never logged, never serialized into a response body, never included in a stack trace.

---

## 7. Explicit Non-Goals & Excluded Features

The following features are **out of scope for v1.0** and are explicitly **not to be built** without first re-opening `docs/DECISIONS.md` and recording a new decision. This list is the project's "scope fence" — if a future feature idea touches any of these, stop and re-evaluate whether it belongs in TidyMail at all.

### 7.1 All AI / LLM features — completely removed

| # | Excluded thing | Why |
|---|---|---|
| N1 | Any LLM call (OpenAI / Anthropic / Gemini / Groq / OpenRouter / Ollama / Together / local model) | DECISIONS.md §2: entire AI matrix from inbox-zero reference is deliberately removed. |
| N2 | AI auto-draft replies, chat-with-email, AI summarization, AI categorization, meeting briefs | Same. The product is deterministic heuristics only. |
| N3 | Per-user API-key UI for LLM providers | N/A if no LLM. |
| N4 | "AI rule engine" or natural-language → SQL queries | Deterministic sender-grouping + checkboxes is the v1 model. |

### 7.2 Billing, payments, and SaaS-isms

| # | Excluded thing | Why |
|---|---|---|
| N5 | Stripe / Lemon Squeezy / any payment provider | Personal tool first. No premium tiers. |
| N6 | Sign-up page, public open registration, invite codes | Phase 1 target = one self-hosted user. Phase 2 = small trusted group via manual allowlist. |
| N7 | Usage quotas, rate-limit tiers, freemium vs premium gates | No billing → no tiers. |

### 7.3 Auth providers & integrations beyond Gmail + Outlook

| # | Excluded thing | Why |
|---|---|---|
| N8 | Apple Sign In | Not requested; OAuth surface is already 2 providers. |
| N9 | SSO / SAML / SCIM / enterprise directory sync | Not an enterprise product. |
| N10 | Slack / Teams / Telegram / Discord bots | Integrations come after the core loop is shipped and proven. Start with browser web UI only. |
| N11 | Mobile push notifications (APNs / FCM) | No native mobile app in v1. |
| N12 | recall.ai, browser-extension-based auth | Pure OAuth only. |

### 7.4 Architecture exclusions

| # | Excluded thing | Why |
|---|---|---|
| N13 | External message queues (BullMQ / RabbitMQ / Kafka / Redis streams) | `@Scheduled` in-process is sufficient for personal → small-group. |
| N14 | Native Cloudflare Workers / Pages Functions backend | JVM cannot run on V8/Wasm (DECISIONS.md §3). Cloudflare is edge in front, not origin host. |
| N15 | React / Vue / Svelte SPA with a separate build pipeline | Thymeleaf + htmx gives the same partial-swap UX with zero frontend tooling (DECISIONS.md §5). |

---

## 8. Project Goals for v1.0 (MVP)

The MVP scope is intentionally tight. v1.0 is **done** when a single user can run the full loop: sign in → see actionable data → act → undo → repeat — and every step is correct, counted, and feels safe.

| # | Goal | Description |
|---|---|---|
| G1 | **Login works for both providers** | A user visiting the Cloudflare HTTPS URL sees two working OAuth buttons: "Sign in with Google" and "Sign in with Microsoft" (each rendered only if the corresponding env vars contain real credentials, not dummy `your-client-id` strings). Clicking either completes the OAuth code exchange, creates a `UserAccount` row, stores an encrypted refresh token, and lands on the authenticated home page. |
| G2 | **Can list last 100 messages** | For a linked Gmail account, hitting the "Sync now" button calls Gmail API with `format=METADATA` + the whitelisted metadata headers and persists sender / label / thread metadata for the most recent 100 messages. For a linked Outlook account, parity via Graph `/me/messages`. |
| G3 | **Sender aggregation page** | A `/senders` page that groups ingested messages by `From` address (with domain rollup), showing for each row: total count, unread count, oldest date, boolean flags for "has List-Unsubscribe header", "has Precedence: bulk", "user has never replied to this sender". Rows are sorted by **total count descending** (impact first, never alphabetical). |
| G4 | **Bulk actions page works correctly** | Each sender row on `/senders` has checkboxes and a per-row action menu + a page-level "Apply to selected" button. Available actions: Archive, Mark as read, Trash, Unsubscribe (one-click HTTP via `List-Unsubscribe` URL if present). Every action: (a) shows an exact count in its confirmation dialog, (b) writes an append-only `user_actions` row with the exact `message_ids[]` before executing, (c) executes only those IDs, (d) refreshes the row fragment via htmx with updated counts, (e) surfaces an "Undo" link that, when clicked, replays the inverse on the same IDs. |
| G5 | **Reply Zero queue** | A `/reply-zero` page showing a list of emails that meet all of: (a) currently in the Inbox folder, (b) sender looks like a person (not bulk — fails the "has List-Unsubscribe" and "Precedence: bulk" checks), (c) current user has not replied in-thread (checked via `References` / `In-Reply-To` chain against the user's own sent messages metadata). Each item has "Mark as replied" / "Archive" / "Open in Gmail / Outlook web" actions. This page is heuristics only — no LLM. |
| G6 | **Sync jobs run in the background** | Spring `@Scheduled` methods run on a fixed delay: (a) re-scan new messages for each linked account, (b) renew Gmail `users.watch` pubsub subscription (or just refresh the poll timestamp; push is optional per DECISIONS.md §7), (c) recompute cached `SenderStats` aggregates. Jobs are idempotent: running twice in a row does not duplicate rows. |

### The end-to-end loop v1.0 must nail

> Sign in → Sync 100 messages → Open `/senders` → Check 3 sender rows → Click "Archive selected (187 messages)" → Confirm the exact count → See the UI update → Click "Undo" on one of them → See the counts restore → Go to `/reply-zero` → Process 2 items → Sign out → Confirm DB has zero rows for that account.

If this loop is broken, nothing else matters. No features are added on top of a broken loop.

---

## Current Milestone: v1.2 Trustworthy Data & Maintainable Core

**Status:** Active — opened 2026-08-13 after v1.1 (Phases 9–17) closed with the UI accepted at ~90%.

**Goal:** Make the tool reflect the user's *actual current inbox*, load fast and non-blank for a brand-new user while it backfills in the background, classify senders and messages well enough that its recommendations are trusted, act on Gmail with honest progress feedback (including Gmail-side rules), and rest the whole product on a single-dialect, readable-test foundation the maintainer could own alone. This milestone deliberately turns away from UI polish toward data trustworthiness, first-run experience, and code health.

**Core value for v1.2:** Fresh data the user can trust, a fast trustworthy first run, classification good enough to act on, honest bulk-action feedback, and a maintainable core — proven by user-journey tests against realistic data volume, not phase-named boilerplate.

**Why now:** Four problem streams surfaced during v1.1 live use and review, and they stack rather than run independently:
1. **Testing** — the suite is phase-named, mock-heavy, and unreadable; H2 tests silently diverge from production Postgres, so green tests do not prove production behavior; and nothing is proven at realistic mailbox volume.
2. **Code review** — `.planning/phases/16-reconcile-ui-with-mocks/16-CODEBASE-QUALITY-REVIEW.md` found 6 Critical / 14 Warning / 12 Info issues: dual SQL dialect (C-1/2/3), a live export subsystem that violates a hard constraint (C-5), in-JVM locking presented as cross-instance safety (C-6), god-classes, and unmanaged OAuth refresh.
3. **Data quality / functionality** — freshly-arrived Gmail does not appear in the tool (sync uses pagination-token backfill that starves new-mail ingestion), a brand-new user would face a slow/blank first run, unread totals are inconsistent (Inbox vs All Mail double-counting), metadata extraction (unread/attachments/size) is unaudited, and classification relies on shallow deterministic signals that don't remember user corrections across ingestion.
4. **Action capability & feedback** — the app cannot create Gmail-side rules/labels or open a scoped set in Gmail (capabilities the inbox-zero reference has), lacks two Gmail scopes to do so, and gives no progress/ETA feedback for slow bulk actions.

The test foundation is a *prerequisite*, not just cleanup: freshness and classification cannot be changed safely without trustworthy tests at realistic volume, and tests cannot be trusted while H2 diverges from Postgres. Fixing the harness also removes 4 of the 6 Critical findings at the root.

**Target outcomes:**
- A Postgres-only Testcontainers harness seeded by a large (≥10,000-message), PII-stripped synthetic dataset; H2 test-migration set and the `h2` query branches deleted; a small set of readable user-journey E2E tests replacing the worst phase-named ones (fixes C-1/2/3, addresses I-11).
- The export subsystem deleted end-to-end — controller, service, `export/` package, config, dead fields — honoring the hard "no export" constraint (fixes C-5, and the C-4 streaming-transaction anti-pattern with it).
- Gmail incremental sync moved to the History API so new mail lands within a sync cycle while backfill runs independently; a brand-new user sees populated data within seconds of first sign-in via a fast first batch, with a dedicated background-sync page (progress, ETA, "don't close this window"); no GB/storage cap (per DECISIONS.md §11); one honest Inbox-vs-All-Mail unread definition; audited metadata extraction (unread/attachments/size); OAuth token refresh managed and persisted (fixes W-14).
- Classification accuracy measurably improved against a labeled ground-truth benchmark, using enriched deterministic signals plus an embedded/on-device ML classifier (no LLM, no external AI-API calls — see DECISIONS.md §10), with the deterministic rules retained as an explainable, user-fixable fallback, and user re-categorizations persisting across progressive ingestion.
- A dedicated architectural-remediation phase decomposing the god-classes, replacing the in-JVM lock (C-6), adding Bean Validation + shared error handling (W-10/11/12), and a housekeeping sweep — behavior held constant by the Phase 18 journey tests. jOOQ adoption is a decision gate here, not a mandate.
- Expanded Gmail scopes (`gmail.settings.basic`, `contacts.readonly` — per DECISIONS.md §12) enabling Gmail-side filters/labels and open-in-Gmail from a scoped set, plus honest live progress/ETA and durable background execution for slow bulk actions.

### Active requirements (v1.2)

See `.planning/REQUIREMENTS.md` for the canonical TEST-/FRESH-/CLASS-/ARCH-/SCOPE-/RULE-/PERF- requirement ledger (33 requirements across Phases 18–22). Summary:
- [ ] Users can trust that recently arrived mail appears in the tool within one sync cycle, with honest freshness state and one consistent Inbox-vs-All-Mail unread definition.
- [ ] A brand-new user gets a fast, non-blank first run — a first batch shows within seconds while the full mailbox backfills in the background, visible on a dedicated sync page that says when it's safe to close the window.
- [ ] Users get sender/message classifications good enough to act on, measurably better than the v1.1 rules baseline, still explained in plain English, still correctable, and with corrections that survive progressive ingestion.
- [ ] Users can create Gmail-side rules/labels and open a scoped message set in Gmail, and see honest progress/ETA and durable background execution for slow bulk actions.
- [ ] The maintainer can read the test suite as user journeys against real Postgres at realistic volume, with no H2 dialect and no dead export code on the wire.
- [ ] The maintainer inherits a core with decomposed services, no false cross-instance locking, unified validation/error handling, and no known Critical review findings outstanding.

**Locked constraints carried in:** metadata-only privacy (no bodies/attachments), exact immutable message-ID action scopes, reversible undo, CSRF, protected-sender auditing, no user-facing export/snapshot/download, no new SPA framework, and — per DECISIONS.md §10 — no LLM or network AI-API calls even as ML is introduced. Gmail-side rule/label creation (Phase 22) is reversible and visible; `contacts.readonly` is read-only classification signal only; expanded scopes require a Google Cloud Console change and re-consent and degrade gracefully until then (DECISIONS.md §12).

---

## Prior Milestone: v1.1 Complete Cleanup Workspace (Complete)

**Status:** Complete — Phases 9–17 accepted and closed 2026-08-13; UI accepted at ~90%. Functionality and data-quality follow-ups deferred to v1.2.

**Goal:** Turn every workflow implied by the UX mock suite into a real, coherent, metadata-only product surface backed by durable state, exact mailbox mutations, and explainable analytics.

**Target features:**
- Replace the partial dashboard with connected Senders, Unread, Unsubscribe, Review, and Analytics pages that share navigation, filters, account scope, responsive behavior, and deterministic recommendations.
- Support sender-level and message-level decisions, protected/keep states, saved selections, bulk selection, exact previews, staged approval, configurable immediate execution, partial-failure reporting, and durable undo.
- Implement standards-aware one-click unsubscribe plus explicit manual fallback paths without fetching or storing message bodies.
- Persist enough metadata and action outcomes for date-window analytics, comparison periods, drilldowns, cleanup coverage, action confidence, and unsubscribe completion reporting without user-facing export or snapshot affordances.
- Resolve the known localhost UAT failures in sender/domain loading, action execution, undo reliability, score explanation, and sync performance as milestone acceptance gates.

**Key context:** The mocks are product specifications, not static styling references. When a mock is ambiguous, v1.1 implements the useful alternatives behind explicit controls rather than silently omitting one interpretation. Gmail remains the first fully verified provider; provider-neutral contracts must preserve a clean path to Outlook parity.

---

## 9. Success Metrics (Functional Checkboxes)

These are **binary, verifiable QA gates**. v1.0 does **not ship** until every single checkbox is true. There are no "we'll fix it in v1.1" exceptions.

### Authentication

- [ ] **M1.** Both OAuth buttons render on the login page **only** when their corresponding env vars are set to non-dummy values. Dummy values (strings starting with `your-`, or exactly equal to `skipped`) → button is hidden.
- [ ] **M2.** "Sign in with Google" completes the full OAuth flow with a real Google account. After the redirect, the app shows the authenticated home page (not the login page) and a `UserAccount` row with `provider=google` exists in Postgres.
- [ ] **M3.** "Sign in with Microsoft" completes the full OAuth flow with a real Microsoft account (personal or work/school, since `tenant_id` = `common`). After redirect, a `UserAccount` row with `provider=microsoft` exists in Postgres.
- [ ] **M4.** Refresh tokens in the `UserAccount` / `oauth_tokens` table are **not** plaintext. A `SELECT token FROM ...` returns a base64 AES-GCM ciphertext, not a readable `ya29.xxx` / `0.Axxx` string.
- [ ] **M5.** Clicking "Sign out" deletes all rows scoped to that `account_id` across all tables. A subsequent sign-in with the same account shows zero residual sender stats, zero cached messages, zero undo-log rows.

### Gmail metadata listing

- [ ] **M6.** `GmailMailService.listMessages(account, limit=100)` returns 100 message headers using `format=METADATA`. Verifiable via test: the Gmail client's underlying HTTP request log (or a spy) shows the `format=METADATA` and `metadataHeaders=` parameters exactly matching the whitelist.
- [ ] **M7.** The persisted `message_refs` rows for Gmail contain no `subject_text` / `snippet_text` columns (or those columns are NULL for every row). Only `from_address`, `from_domain`, `message_id`, `thread_id`, `received_at`, `label_ids[]`, header-booleans are stored.
- [ ] **M8.** For an inbox with at least one `List-Unsubscribe` header and one `Precedence: bulk` header, the booleans on the corresponding `message_refs` rows are set correctly.

### Outlook parity (not optional)

- [ ] **M9.** `OutlookMailService.listMessages(account, limit=100)` returns parity data to the Gmail path for the same 100-message slice. Same column schema; booleans for bulk-marketing headers are derived from `internetMessageHeaders`.

### Sender aggregation

- [ ] **M10.** `/senders` page HTML shows rows sorted by total message count **descending**. For seeded data where sender A has 42 messages, sender B has 18, sender C has 120, the order on the page is C, A, B. No alphabetical or insertion-order sorts.
- [ ] **M11.** Each sender row's "count" column matches a raw SQL count for that sender + account exactly. QA test: `SELECT count(*) FROM message_refs WHERE account_id=? AND from_address=?` equals the number shown in the `<td>` for that row.
- [ ] **M12.** The "bulk marketing" indicator on a sender row is a plain-English tooltip listing the reasons: e.g. "List-Unsubscribe header present (82%), never replied". No cryptic internal names.

### Bulk actions (the highest-risk surface)

- [ ] **M13.** For a sender card showing **N** messages, clicking "Archive all" archives **exactly N** messages. QA verifies by: (a) count in Gmail UI before = X, (b) click Archive with confirmed N, (c) count in Gmail UI after = X − N. Not X − N ± something. Exact.
- [ ] **M14.** An append-only `user_actions` row is **inserted before** the Gmail/Graph batch call runs. DB transaction: insert log → commit → then call APIs. If the API call fails mid-way, the log row persists so a partial undo can still run.
- [ ] **M15.** "Undo" on an "Archive 247 messages" action moves exactly those 247 message IDs back to the Inbox. Undo uses `message_ids[]` from the log row, not a fresh query.
- [ ] **M16.** "Mark selected read" on 3 checked rows with counts 12 + 5 + 41 = 58 total marks exactly 58 messages read. Confirmation dialog shows "58" as the number.
- [ ] **M17.** Unsubscribe action: for a sender with `List-Unsubscribe: <https://example.com/unsub?x=abc>`, clicking "Unsubscribe" makes an HTTP GET to that URL. For a sender with only a `mailto:` List-Unsubscribe (no HTTP), a mailto link opens in a new tab (one-click mailto is not auto-followed to avoid unintended sends).

### Reply Zero

- [ ] **M18.** `/reply-zero` page only contains items that are (a) in the Inbox folder, (b) flagged as NOT-bulk, (c) user has no "sent" reply in the same `References` / `In-Reply-To` chain. An email already replied to by the user must not appear. A bulk-newsletter email must not appear.
- [ ] **M19.** "Open in Gmail web" action opens `https://mail.google.com/mail/#all/{gmail_message_id}` in a new tab with the correct per-message ID. "Open in Outlook web" parity with `https://outlook.office.com/mail/inbox/id/{graph_item_id}`.

### Sync jobs

- [ ] **M20.** `SyncJobs.refreshCaches()` runs on a `@Scheduled` fixed delay (default: 15 minutes). Running it twice in a row does not duplicate rows (idempotency verified by integration test).
- [ ] **M21.** Orphaned-session TTL job: inserting a `UserAccount` with `last_seen = NOW() - 25h` and running the job results in that row (and all cascaded rows) being deleted. An account with `last_seen = NOW() - 23h` is kept.

### Mobile & layout (carry-forward from InboxTriage pitfall A8)

- [ ] **M22.** Every page renders without horizontal scroll at **375×667 viewport** (iPhone SE size). QA: Chrome DevTools device mode, scrollWidth <= clientWidth on all routes.
- [ ] **M23.** Tap targets on `/senders` checkboxes and action buttons are ≥ 44×44px at 375px.
- [ ] **M24.** Tables on `/senders` and `/reply-zero` wrap, truncate, or collapse correctly — no fixed 800px table escaping the viewport.

### Data quality & empty states (carry-forward from InboxTriage pitfall A7)

- [ ] **M25.** Running the dev-seed data fixture (`/dev/seed` or JUnit test data loader) produces **non-zero** rows in both `sender_stats` and `domain_stats` aggregates. A backend build test asserts `count(*) > 0` for both tables after seed and fails the build if either is empty.
- [ ] **M26.** An empty inbox (zero messages synced) on `/senders` shows a friendly empty-state card explaining how to sync, not a blank page or a broken table.

### Build-time & deploy-time

- [ ] **M27.** `mvn clean package` produces a fat JAR that runs with `java -jar ...` and binds to `localhost:8080` with no errors.
- [ ] **M28.** The same fat JAR deployed behind `cloudflared tunnel` completes both OAuth flows end-to-end against the real Cloudflare HTTPS URL (not localhost). Google and Azure redirect URIs are configured to the Cloudflare URL and the flow succeeds.

---

## 10. Risks Inherited from Previous InboxTriage / TidyGmail Attempts + Mitigations

TidyMail is not a first attempt. It is at least the third incarnation (after a Node CLI, then a Cloudflare Workers app). The previous attempts failed in **predictable and well-documented ways** — the mistakes are known, and this project encodes the corrections in both the plan and (eventually) in tests.

The canonical source for these pitfalls is `docs/Progress Check - mid-PRD.md` §3 ("What V1 got wrong — AVOID for V2") and `.planning/STATE.md` §"Lessons from Previous Attempt". What follows is each risk, its root cause from the previous attempt, and the concrete mitigation TidyMail implements.

### Risk R1: Building 3 surfaces before any one works (Pitfall A1)

**Root cause in InboxTriage/TidyGmail V1:** Built Recommendations, Explorer, and AI Chat surfaces in parallel. None of them shipped end-to-end. The planning-to-shipping ratio was ~27 documented decisions before the first usable build.

**Mitigation in TidyMail:**
- **Lock the boot order** (DECISIONS.md §8): Login → JPA → Gmail list-100 → Outlook list-100 → Senders page → Reply Zero → Jobs → Deploy. Only one active surface at a time.
- The project's "one loop" (§8 above) must pass QA checkboxes M1–M13 before Reply Zero, jobs, or deploy are even started.
- **Cap at 10 decisions per release** (DECISIONS.md §3 "A10", `STATE.md` lessons). Any new architectural choice beyond the locked 10 in DECISIONS.md goes to an issue tracker, not into the plan, until after v1.0 ships.

### Risk R2: "Archive all" archived MORE than the card showed (Pitfall A3)

**Root cause in InboxTriage/TidyGmail V1:** The "Archive all" button re-ran the sender-query at execution time, but the execution-time query used different filters / different pagination than the query that populated the card count. Result: card said "Archive 24" → actually archived 78. Breaks trust irrevocably.

**Mitigation in TidyMail:**
- **The count and the action share the exact same code path at the exact same time.** Step 1: run query → produce `(sender_row, message_ids[])` pair. Step 2: render the count from `message_ids.length`. Step 3: store `message_ids[]` in the form state (server-side session attribute). Step 4: when user clicks "Archive", operate **only** on that stored `message_ids[]` array — never re-run the query.
- QA checkbox **M13** is a build-failing assertion, not a "nice to have" manual check.
- Action confirmation dialog shows the message-IDs-hash and the count. User confirms *both* the number and the scope.

### Risk R3: Undo didn't undo (Pitfall A4)

**Root cause in InboxTriage/TidyGmail V1:** Undo navigated to a different route ("undo page"), lost the context of *which* exact messages were in the original action, and ran a heuristic "restore" query that was the inverse of *today's* inbox state, not the inverse of *what the original action actually did*. Result: "Undo archive" restored 3 messages of 24, or 40 messages that were never archived in the first place.

**Mitigation in TidyMail:**
- **Append-only `user_actions` log with `message_ids[]`.** Every action is a log row with the exact list of IDs it operated on. The log row is the single source of truth.
- **Undo = replay inverse on the exact same IDs.** Not a new query. Not "all messages in inbox from sender X". Just "for each ID in `user_actions.message_ids[]`, run the inverse operation."
- Undo is an htmx in-place swap, not a navigation. No route change, no context loss.
- QA checkbox **M15** is build-failing.

### Risk R4: Empty report tables after seed (Pitfall A7)

**Root cause in InboxTriage/TidyGmail V1:** `sender_stats` / `domain_stats` aggregates were populated by a derived-query pipeline that had a silent `WHERE 1=0`-style bug in the seed-data path. Inbox looked empty → user thought the app was broken.

**Mitigation in TidyMail:**
- **Build-time test: seed must produce non-zero stats.** A JUnit test loads the dev seed fixture, runs the aggregate computation, and asserts `SELECT count(*) FROM sender_stats > 0` and same for `domain_stats`. Test failure = Maven build failure.
- Empty inbox still shows a friendly empty-state component (QA checkbox M26) — never a blank page.

### Risk R5: Broken mobile at 375px (Pitfall A8)

**Root cause in InboxTriage/TidyGmail V1:** Desktop-first CSS. Every page had hardcoded table widths, no `overflow-x: auto`, cards that didn't wrap. Viewport at 375px had a 595px content width — 60% horizontal overscroll on every screen.

**Mitigation in TidyMail:**
- **Mobile-first CSS from day one.** Layout rules are written at 375px first, then media queries up. Not the other way around.
- Every card, every table, every container has `max-width: 100%`. Tables use `overflow-x: auto` wrappers.
- QA checkboxes **M22–M24** are part of the v1.0 gate, not a "later" concern.

### Risk R6: Planning / shipping ratio too high (Pitfall A10)

**Root cause in InboxTriage/TidyGmail V1:** 21 markdown files (~3,800 lines) of plans and decisions. 1,400 lines of code that partially worked. Every feature was planned in 4 rounds of design docs before any button was wired.

**Mitigation in TidyMail:**
- **Locked boot order = 8 phases** (DECISIONS.md §8). Each phase has one verifiable output. No phase starts until the previous phase's QA gate is green.
- **10-decision cap per release.** DECISIONS.md has 8 locked sections. That leaves 2 slots for the entire v1.0 cycle. Everything else → issue tracker, not a design doc.
- GSD workflow: each phase is `discuss → plan → execute → verify`, with concrete per-phase deliverables, not open-ended exploration.

### Summary table of risks & mitigations

| ID | Risk (from InboxTriage) | Mitigation | QA Gate |
|---|---|---|---|
| R1 | 3 surfaces, 0 working | One loop end-to-end; locked boot order; ≤10 decisions | All G1–G4 pass before new surface added |
| R2 | Archive ≠ card count | Same `message_ids[]` for count and action; no re-query | M13 (exact archive count) |
| R3 | Undo doesn't undo | Append-only log + inverse replay on stored IDs | M15 (exact restore count) |
| R4 | Empty report tables | Build-failing seed→non-zero test; empty-state UI | M25, M26 |
| R5 | Mobile overflow | Mobile-first CSS; max-width 100%; table wrappers | M22, M23, M24 |
| R6 | Too much planning, not enough shipping | 8-phase boot order; 10-decision cap | GSD per-phase verify gates |

---

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check - still the right priority?
3. Audit Out of Scope - reasons still valid?
4. Update Context with current state

---

## 11. Key References

These files (within this repository) are the canonical sources of truth for the project. If this PROJECT.md ever conflicts with one of these, **the listed reference wins** — come back and update PROJECT.md after resolving the conflict.

| Reference | Path in repo | What it contains |
|---|---|---|
| **DECISIONS.md** | [`docs/DECISIONS.md`](../docs/DECISIONS.md) | Locked product + architecture decisions. The 8 sections (language/runtime, scope, deployment architecture, OAuth providers, tech stack inside the JAR, privacy model, considered-and-rejected, boot order) are the canonical source for any "why is X this way?" question. |
| **InboxTriage Lessons** | [`docs/InboxTriage Master Plan.md`](../docs/InboxTriage%20Master%20Plan.md) | Master plan and open feature ideas from the previous Node CLI + Workers attempt. Also contains the AI Layer / Mobile / Security / 100K-Inbox explorations that fed into v2's thinking. |
| **Progress Check / PRD** | [`docs/Progress Check - mid-PRD.md`](../docs/Progress%20Check%20-%20mid-PRD.md) | The "Detective's Notebook" PRD for TidyGmail V2 / InboxTriage. Source of the 15 pitfalls (A1–A15), the 10 QA-quality-bar checks, and the "what V1 got right" (K1–K10) that are carried forward as TidyMail's design instincts. |
| **Inbox-zero reference repo** | *(External) `~/Documents/trae_projects/inbox-zero` or `github:elie222/inbox-zero`* | The Next.js reference implementation TidyMail is deliberately simplified and de-AI'd from. Useful for understanding the UI patterns TidyMail reimplements (sender aggregation cards, bulk-unsubscribe action menu, Reply Zero concept). **Do not copy the LLM matrix, BullMQ, Stripe, or React SPA portions.** |
| **Project State** | [`.planning/STATE.md`](./STATE.md) | Current GSD workflow phase, artifact completion checklist, and a short "Lessons from Previous Attempt" bullet summary. |
| **Workflow config** | [`.planning/config.json`](./config.json) | Machine-readable workflow preferences, tech-stack tags, auth/privacy settings, logging policy. Read by GSD tooling. |
| **Existing PROJECT.md (root, pre-created)** | [`PROJECT.md`](../PROJECT.md) | Shorter, earlier version of this document. Superseded by this `.planning/PROJECT.md` for GSD workflow purposes; can be replaced with a copy of this file when `.planning/` is finalized. |

### Reference reading order for a new maintainer

1. This document (`.planning/PROJECT.md`) — "what is this, why does it exist, what will it do"
2. `docs/DECISIONS.md` — "how and why did we pick these specific building blocks"
3. `docs/Progress Check - mid-PRD.md` — "what went wrong last time, and how is this build avoiding those traps"
4. `.planning/REQUIREMENTS.md` (next artifact) — "what exactly is in-scope for v1.0, v1.1, v3+"
5. `.planning/ROADMAP.md` (next artifact) — "what are the 8 phases, in order, with verifiable outputs"
6. Source code under `src/main/java/` — once phases 1–2 have run.

---
*Last updated: 2026-08-13 — v1.2 milestone scope expanded (first-run onboarding, unread truth, metadata-extraction audit, Gmail scope expansion + rules, bulk-action feedback, correction persistence, large synthetic dataset); Phase 22 added.*
