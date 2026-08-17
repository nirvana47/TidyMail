# Decisions

Captured from the design chat on 2026-08-07. Any decision added here is **why** something is the way it is — treat this file as the canonical source of truth for product + architecture choices. Do not override these without updating this file.

---

## 1. Language & runtime

| Key | Value |
|---|---|
| Language | **Java 21+** (LTS) |
| Build tool | **Maven** |
| Project file | `pom.xml` is the first file you create in a Maven project. Everything else (sources, deps, packaging) flows from it. |
| Web framework | **Spring Boot 3.x** (parent POM) |

### Why Java + Maven
- The user explicitly wanted to build this "in the Java language."
- Google and Microsoft ship first-party, well-documented Java SDKs for Gmail + Outlook Graph — no wrapping HTTP APIs by hand.
- Spring Boot gives you OAuth2 login buttons, JPA, `@Scheduled` cron, and Thymeleaf templates with essentially zero glue code.
- Maven's `pom.xml` is the stable industry standard for Java dependency management; a single `mvn package` produces a ready-to-run fat JAR.

---

## 2. Scope: non-AI only, deliberately simpler than Inbox Zero

**Everything AI / LLM is removed from scope.** The reference app (elie222/inbox-zero) ships a broad LLM matrix (default / economy / chat / nano / draft models across Anthropic, OpenAI, OpenRouter, Ollama, Gemini, Groq, Together, etc.) plus AI rules, auto-reply drafts, chat-with-email, AI categorization, meeting briefs, etc. — NONE of this is included.

### Explicitly included features (non-AI core)
1. OAuth sign-in with Google (Gmail) + Microsoft (Outlook/Office 365)
2. Read mail metadata: list messages, threads, senders, labels/folders, attachments, List-Unsubscribe headers
3. Mutate mail in batches: archive, trash, mark read/unread, apply labels/folders, unsubscribe, delete (soft via trash first)
4. **Sender aggregation:** group mail by `From` address + domain, count total/unread/oldest, surface bulk-marketing heuristics (List-Unsubscribe present, `Precedence: bulk`, sender never replied to)
5. **Bulk-actions page:** per-sender checkboxes → "Archive all selected / Unsubscribe / Delete / Mark read" in one click
6. **Reply Zero heuristics (no AI):** emails in the Inbox where you haven't replied yet AND the sender looks like a person (not bulk). Implemented purely via thread metadata (`References`, `In-Reply-To`, message age, participants, no bulk headers) — zero LLM.
7. **Background sync + renewal:** periodic jobs to re-scan, renew Gmail `users.watch` and Outlook change subscriptions, refresh cached sender stats

### Explicitly EXCLUDED features (non-goals)
- All LLM calls: default/economy/chat/nano/draft model matrix, fallbacks, per-user API key UI, AI rule engine
- AI auto-draft replies, chat-with-email, AI summarization, AI categorization, meeting briefs
- Billing / payments / premium checks (Stripe, Lemon Squeezy)
- Apple Sign In, SSO/SCIM, Android/iOS push, Slack/Teams/Telegram bots, recall.ai
- External message queues (BullMQ, RabbitMQ, Kafka) — in-process `@Scheduled` is enough for single-user to small-multi-user deployments

---

## 3. Deployment architecture: **Option A — Java origin + Cloudflare Tunnel in front**

| Concern | Decision |
|---|---|
| Can Java run ON Cloudflare Workers? | **No.** Workers are V8 + Wasm (`workerd` runtime); JVM/Spring Boot don't fit. Wasm ports of Java (TeaVM, GraalVM Wasm) would lose threads/JDBC/scheduling — not worth the fragility. |
| Can we still use Cloudflare for HTTPS + domain + WAF? | **Yes.** Put Cloudflare's network *in front of* the Java origin. |
| How does traffic reach the JVM? | **Cloudflare Tunnel (`cloudflared`)** — a small agent installed next to the JAR that makes outbound connections to Cloudflare. No public IP, no open ports, no self-managed SSL cert needed. |
| Public URL | HTTPS on a Cloudflare-managed domain (e.g. `inbox-cleaner.yourdomain.com`), all traffic terminates at Cloudflare edge. |
| OAuth redirect URIs in Google/Azure | Point at the Cloudflare HTTPS URL, e.g. `https://inbox-cleaner.yourdomain.com/login/oauth2/code/google` and `/code/microsoft`. |
| Where to actually run the JAR? | Any long-lived host with Java 21: a $3–$6/month VPS (Hetzner / DigitalOcean), Oracle Cloud Free Tier, Fly.io (via Dockerfile), or even a Mac at home exposed purely via the tunnel. |
| Database | Managed serverless Postgres: **Neon** or **Supabase free tier** or **Tembo**. Or local Docker Postgres for development. |

### Why Option A
This gives the exact user-facing outcome of "a webpage on Cloudflare where people click Sign in with Google / Sign in with Microsoft" **without** porting the app to a language Workers understands. Java runs where it was designed to run — a real JVM with threads and JDBC connection pools — while Cloudflare takes care of SSL, DDoS, WAF, cache, and the public DNS entry.

---

## 4. OAuth providers: both Gmail + Outlook (coexist)

| Provider | How | Scopes minimum | Credentials env vars |
|---|---|---|---|
| Gmail | Official `google-api-services-gmail` Java client + Spring Security OAuth2 `google` client registration | Gmail API `https://www.googleapis.com/auth/gmail.modify` + openid profile email offline_access | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`. Optionally PubSub for push (personal single-user can rely on polling cron instead). |
| Outlook / Office 365 | Official `microsoft-graph` Java SDK + Spring Security OAuth2 `microsoft` client registration | Graph delegated: `Mail.ReadWrite`, `Mail.Send`, `MailboxSettings.ReadWrite`, `Calendars.ReadWrite` optional, plus openid profile email User.Read offline_access | `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `MICROSOFT_TENANT_ID` (empty = `common`, i.e. personal + work/school accounts) |

- Both providers can coexist; users pick which one to connect at login.
- Buttons only render on the login page once the corresponding `CLIENT_ID`/`CLIENT_SECRET` env vars are set and non-dummy (a safety pattern inherited from the reference app's "skip if starts with `your-` / equals `skipped`" check).

---

## 5. Suggested tech stack inside the JAR

| Layer | Choice | Why |
|---|---|---|
| HTTP server | Spring Boot Starter Web (Tomcat) | Default, zero config |
| Security + Login buttons | Spring Security + Spring Boot Starter OAuth2 Client | Gives "Sign in with Google/Microsoft" buttons with ~30 lines of config. Handles state, code exchange, refresh tokens. |
| Templates | Thymeleaf + minimal htmx for partial swaps | Avoids frontend bundler; mirrors the "server-rendered + htmx" model already proven in this workspace's TidyGmail and in inbox-zero's pre-JSON API era. Personal projects don't need a React build pipeline. |
| Persistence | Spring Data JPA / Hibernate + PostgreSQL JDBC | SQL schema small enough to hand-write or let `ddl-auto: validate` + migrations via Flyway/Liquibase manage later |
| Gmail | `com.google.apis:google-api-services-gmail:v1-*` + `google-oauth-client` with stored refresh token | First-party SDK from Google |
| Outlook | `com.microsoft.graph:microsoft-graph` + `azure-identity` with on-behalf-of refresh token flow | First-party SDK from Microsoft |
| Cron / scheduling | Spring `@Scheduled` methods on fixed delay or cron | Replace inbox-zero's BullMQ queue + docker-compose cron container entirely. For tiny traffic, in-process scheduled beats are simpler and safer than running a broker. |
| Secrets | `application.properties` override via OS env vars (`GOOGLE_CLIENT_ID` etc.) or `.env` via `spring-dotenv`; never commit secrets | Standard Spring practice |

Suggested starter module layout:
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

## 6. Trust & privacy model (unchanged baseline from TidyGmail)

- **Metadata-only access by default.** Don't store or process email bodies unless the user explicitly needs it for a future feature (and even then, scoped + short TTL). Gmail's `metadata` format + `format=METADATA` + `metadataHeaders=From,To,Subject,Date,List-Unsubscribe,References,In-Reply-To,Message-ID` is the target.
- **Reversible decisions.** Every bulk action writes an append-only `user_actions` row with the list of Gmail `messageId` / Graph message IDs affected. Undo reverses the latest action per sender-group. Actions aren't final until the user confirms (review-as-confirmation step).
- **No retained inbox data across sessions for personal self-host.** User is the only user, the DB is theirs. If they ever delete their account row, drop all rows referencing their account ID.

---

## 7. What was considered and rejected

| Idea | Rejected reason |
|---|---|
| Rewrite in TypeScript/Hono to deploy on Cloudflare Workers natively | User explicitly wanted Java. Workers don't run JVM. |
| GraalVM Wasm / TeaVM to compile Java → Wasm for Workers | Fragile. Loses threads, JDBC pools, `@Scheduled`. Development experience far worse than a plain JAR. Not worth the "Workers native" badge. |
| Spring Boot deployed to Cloudflare Pages Functions | Pages Functions are also V8-based Workers under the hood. No JVM. |
| RabbitMQ / Kafka for background jobs | Overkill for personal-to-small-multi-user. In-process `@Scheduled` is simpler and sufficient. |
| React SPA frontend | Adds a build pipeline with no product benefit for a single-user, server-rendered admin-style UI. Thymeleaf + htmx gives the same feel (partial swaps without full reload) with zero tooling. |
| Stripe / billing tiers | Scope excluded — personal tool first. Can be added later if/when opening signups. |
| PubSub push (Gmail) required at launch | Personal users can live with periodic polling via `@Scheduled` — much simpler to start. Add PubSub later only if push is needed for scale. |

---

## 8. Boot order when actually starting to code

1. Scaffold `pom.xml` (Spring Boot parent + Web + Security + OAuth2 Client + JPA + Thymeleaf + Gmail client + Graph SDK + Postgres JDBC + Spring Boot Maven plugin)
2. Create the standard Maven source tree + `InboxCleanerApplication.java` with `@SpringBootApplication`
3. Add `SecurityConfig` → verify you see "Sign in with Google" and "Sign in with Microsoft" buttons on a localhost login page when credentials are filled in
4. Add Postgres config + one JPA entity (`UserAccount`) and verify JPA connects
5. Implement `MailProvider` interface + Gmail implementation → first win: "list last 100 messages" works end-to-end
6. Add Outlook implementation → parity
7. `SenderAggregator`, `BulkActionRunner`, controllers + Thymeleaf pages — the actual product UI
8. `SyncJobs.java` — `@Scheduled` beats for cache refresh and watch renewal
9. Package JAR, set up `cloudflared` tunnel, test OAuth callbacks against real Cloudflare HTTPS URL

---

## 10. Scope evolution (v1.2): embedded/on-device ML permitted; LLM and AI-API calls still forbidden

**Decided:** 2026-08-13, during v1.2 planning. This refines — it does not replace — §2.

The original §2 constraint ("everything AI / LLM is removed from scope") was written to exclude the
inbox-zero LLM matrix: hosted model calls, chat-with-email, AI drafts, AI categorization. Classification
accuracy in v1.1 proved too shallow when limited to a handful of deterministic header signals. To improve
it without reintroducing the excluded behavior, v1.2 draws a sharper line:

| Now permitted | Still forbidden |
|---|---|
| Embedded, on-device ML libraries running **inside the JVM** (e.g. Smile / Tribuo / Weka-class), trained on the user's own metadata | Any call to an LLM or hosted AI API (OpenAI / Anthropic / Gemini / Groq / OpenRouter / Ollama-as-a-service / Together / any network model endpoint) |
| Enriched deterministic signals (header cadence, reply ratios, domain/ESP heuristics) | AI auto-draft replies, chat-with-email, AI summarization, meeting briefs |
| A trained classifier whose output feeds recommendations, **with the deterministic rule set retained as an explainable baseline/fallback** | Sending message metadata or content to any external model for inference |

**Guardrails that keep this consistent with the product's trust story:**
- **No network model calls, ever.** Inference and training happen in-process against locally stored metadata. This preserves the metadata-only privacy contract (§6) and the "no black boxes" promise.
- **Explainability is preserved.** Every recommendation still shows a plain-English reason. The deterministic rules remain a first-class, user-fixable fallback so the maintainer is never stuck with an output they can't correct.
- **Accuracy is measured, not asserted.** ML is only adopted where it beats the rules baseline on a labeled ground-truth benchmark built for that purpose.

**Why the refinement is safe:** the spirit of §2 was "no dependency on opaque, external, non-deterministic model services." A local classifier trained on the user's own data honors that spirit while giving classification the depth it needs. If a future idea proposes any *network* model call, that is a new decision and must be re-opened here.

---

## 11. Data-volume policy: full inbox, progressive; storage scales by message count, not Gmail GB

**Decided:** 2026-08-13, during v1.2 planning. Answers a gap the earlier architecture never addressed: "how much of a mailbox do we pull, and what does that cost to store?"

**Measured baseline (real data, not an estimate):** the maintainer's own linked Gmail account had **119,811** messages synced into Postgres, occupying **267 MB** in `message_metadata` including indexes — **~2.3 KB per message**. TidyMail stores **metadata only** — no message bodies, no attachment content (see [`MessageMetadata`](../src/main/java/com/tidymail/entity/MessageMetadata.java): `size_estimate` is a number, `has_attachments` is a boolean). Therefore our storage footprint is **decoupled from Gmail's storage quota**: Gmail's 15/17 GB is dominated by attachments and bodies we never download.

| Mailbox size (messages) | Approx. TidyMail Postgres storage |
|---|---|
| 50,000 | ~115 MB |
| 250,000 | ~575 MB |
| 500,000 | ~1.15 GB |
| 1,000,000 | ~2.3 GB |

**Decision:**
- **Pull the entire mailbox, progressively.** No hard cap on how much of a user's Gmail is indexed. A first small batch (~100–250 messages) is shown fast; the remaining history backfills in the background.
- **A GB-based cap is explicitly rejected** as the wrong unit — our cost is per-message, not per-byte, and a million-message mailbox is only ~2.3 GB. Mirroring Gmail's GB quota would be misleading.
- **An optional maintainer safety ceiling is expressed as a message COUNT, defaulting to unlimited.** It exists only to bound pathologically large mailboxes on constrained hosts; it is off by default and never presented to the end user as a Gmail-storage figure.

**Guardrails:** metadata-only privacy (§6) is unchanged — expanding volume never expands what per-message data we store. Backfill must not starve new-mail ingestion (see the v1.2 Freshness phase).

---

## 12. Gmail OAuth scope expansion (v1.2): add `gmail.settings.basic` and `contacts.readonly`

**Decided:** 2026-08-13. Extends §4. The app previously requested exactly 4 Google scopes (`openid`, `email`, `profile`, `gmail.modify`) — see [`SecurityConfig`](../src/main/java/com/tidymail/security/SecurityConfig.java). The reference app (inbox-zero) requests ~6; the two extra were identified as filter/label management and contacts.

| Scope | Unlocks | Note |
|---|---|---|
| `https://www.googleapis.com/auth/gmail.settings.basic` | Creating persistent **Gmail filters/rules and labels from inside TidyMail** ("always archive this sender in Gmail itself"), matching an inbox-zero capability we do not have today | Net-new feature, not required for existing cleanup |
| `https://www.googleapis.com/auth/contacts.readonly` | A strong **person-vs-bulk classification signal**: whether a sender is an actual contact | Feeds Phase 20 classification; read-only |

**Important boundary — what these are NOT needed for:** cleanup, unread state, attachment presence, and size are **all fully served by the existing `gmail.modify` scope** via `format=METADATA`. These two scopes are purely additive capability, not a fix for missing metadata.

**Maintainer action required (out of code's control):** both scopes must be added to the Google Cloud Console OAuth consent screen / client, and existing users must **re-consent** (the app already sends `prompt=consent`). Until the console is updated, requesting these scopes will fail the handshake — so the code change and the console change ship together, gated behind the phase that uses them.

**Consistency with the trust story:** `contacts.readonly` is read-only and its data is used only for local, explainable classification — never sent to any external service (consistent with §10). `gmail.settings.basic` performs user-initiated, reversible mailbox-organization writes; it does not read or store bodies.
