Ok, but I want a human readable PRD. I want to create the app from scratch again. I have better idea of what I want now. It will be deployed on Cloudflare, but that will come later. First it needs to run on localhost on my laptop. 
So, one of the things that we need to do different this time is maybe think about it in terms of what are the actions that a user can take and start building those actions one by one. Instead of trying to create the whole interface in one go, instead what we do is we create one user action at a time. And so some of the different user actions that can be done is, number one is, first we connect our inbox. So that would be the first thing to build. I think we already have the stuff to do that. But just need to make sure that, you know, that's the thing that we get ready. Then in the next phase, we start importing data. Now it is important that we don't spend 10 minutes on some kind of a waiting screen while we are importing data. and so as soon as we have imported let's say 100 or 500 emails we should at that point get inside the application that should be action item number two now once this is done then you know a user can take any number of actions but here are the different actions that a user can take start looking at their unread emails versus red emails start they can want to look at what are the emails that are the most number of unread like you know who's the sender or the domain from which they have the most number of unread emails and maybe they want to mark it as red, archive it or delete it. 

# TidyGmail V2 — PRD (Detective's Notebook)

## TLDR

V1 was a Gmail cleanup tool you built twice — once as a Node CLI (11,000 lines, 13 commands, never really used), then as a Cloudflare Workers web app (1,400 lines, partly working). The web version has the right bones — server-rendered HTML, session-only data, a scoring engine that buckets emails into Important / Reference / Low / Promo / Unsure — but the core flows are broken: seeding is shaky, undo doesn't undo, "archive 24" archives 78, search URLs are malformed, recommendation cards aren't sorted by impact, mobile overflows, and the report page tables are empty. The lesson: you over-planned the architecture and under-shipped the basic loop. **V2 should be one screen that works end-to-end, not three surfaces that half-work.** Stop adding features until the loop "see → decide → done" is fast, correct, and feels safe. Then add the AI chat.

---

## 1. What V1 actually was (the receipts)

| Era | Stack | Size | What worked | What didn't |
|---|---|---|---|---|
| V1.0 (Node CLI) | Node + sqlite + 13 CLI commands | ~11,000 lines | Scoring logic, normalized JSON schema | No UI you'd use, too many commands, abandoned |
| V1.5 (Workers app) | Hono + htmx + Pico + D1 + KV | ~1,400 lines | Recommendations page, review queue, bucket tabs | Seed unstable, undo broken, search broken, no Explorer, mobile broken |
| V1 docs | 21 markdown files | ~3,800 lines | Lots of thinking | Most of it never shipped |

You wrote 11 architecture decisions, a 9-slice plan, four rounds of UI feedback, and two QA audits. Code shipped: a partially working web app. The ratio of planning to shipping is the main story.

---

## 2. What V1 got right — KEEP for V2

| # | Thing | Why keep |
|---|---|---|
| K1 | Scoring engine + bucket model (Important / Reference / Low / Promo / Unsure) | Gives you a deterministic, fast first pass without an LLM |
| K2 | Triage modes (Hoarder / It Depends / Burn It All) only change the *default suggestion*, never auto-act | Right safety model |
| K3 | Server-rendered HTML + htmx + Pico + Hono on Workers | Tiny, fast, no SPA tax |
| K4 | Session-only data, sign-out wipes D1 + KV, 24h TTL | Strong privacy story, easy to explain |
| K5 | Gmail `metadata` format only (headers + labels + snippet) | Never reads bodies, fewer permission worries |
| K6 | E-commerce domain list and calendar invite signals | Keeps Amazon/UPS out of "promo noise" |
| K7 | Centralized label vocabulary in `BUCKET_LABELS` / `MODE_LABELS` | Renaming buckets stays a one-line change |
| K8 | Dev seed + reset routes (when working) | Lets you test without a real inbox |
| K9 | Undo as a first-class concept on every action | Right instinct, just needs to actually work |
| K10 | Name "TidyGmail" | You picked it, keep it, stop renaming |

---

## 3. What V1 got wrong — AVOID for V2

| # | Mistake | New rule for V2 |
|---|---|---|
| A1 | Three surfaces (Recommendations + Explorer + AI Chat) before any one surface worked | Ship ONE screen end-to-end before adding a second |
| A2 | Recommendation cards in hard-coded order, not by impact | Sort cards by `messageCount` descending, always |
| A3 | "Archive all" archived more than the card showed | Action MUST act on the exact rows shown — same query, no exceptions |
| A4 | Undo navigates away and doesn't restore state | Undo replays the inverse decision in place; no redirect |
| A5 | Search URL `?q=...&q=...&bucket=...&bucket=...` duplicated params | One source of truth for query state — server form, not client string-stitching |
| A6 | Action removed row in JS but didn't refresh totals | After every action, refresh the queue fragment from the server |
| A7 | Empty report tables (sender_stats / domain_stats had no rows) | Ship with a fallback empty state AND a backend test that fails the build if seed → empty tables |
| A8 | 375px viewport overflows ~595px on every page | Mobile-first CSS, max-width 100% on every card/table |
| A9 | 13 CLI commands for a single-user tool | Zero CLI commands. If a maintainer needs one, write a `/dev/*` route |
| A10 | 27 decisions documented before the first usable build | Cap at 10 decisions per release. Anything else lives in the issue tracker |
| A11 | Free-tier-only deploy caps you at ~15 users/day | Pick a tier deliberately, document the math, OR keep it personal-use only |
| A12 | Single Worker invocation handled the whole pipeline (30s CPU limit) | Use `ctx.waitUntil()` + a queue/cron for any inbox >5K emails |
| A13 | "Review all" auto-decided 143 items as Keep | Buttons that say "Review" must navigate, never decide |
| A14 | Mark-for-unsubscribe didn't persist | Every action writes a decision row before showing "done" |
| A15 | "Inbox Triage", "Clues", "Signals (6)" — names users disliked | Plain-English labels: "Why this looks like noise", "Senders", "What we found" |

---

## 4. V2 Product Definition

**Goal:** Help one person clean their Gmail inbox in under 10 minutes, without ever auto-deleting anything.

**Target user:** A person with 1,000–50,000 emails who feels overwhelmed and wants a guided, safe cleanup.

**Non-goals:**
- Real-time email client
- Multi-user team product
- AI that reads email bodies
- Anything outside Gmail

**The one loop V2 must nail:**

> Sign in → see a report → pick one card → confirm → see it apply → undo if wrong → repeat.

If that loop is broken, nothing else matters.

---

## 5. V2 Features — must / should / later

### Must-have (V2.0 ships without these, V2.0 is not done)
- Sign in with Google (read + modify scopes only)
- Ingest up to 50,000 emails using Gmail `metadata` format
- Score every email into one of 5 buckets
- Recommendations page: cards sorted by impact, accurate counts, working Apply
- Working Undo on every action (replays inverse, no redirect)
- Working Review queue: bucket tabs, search, pagination, action removes the row AND updates totals
- Inbox Report with NON-empty Top Senders + Top Domains tables
- Sign-out wipes all data
- Mobile works at 375px without horizontal scroll

### Should-have (V2.1)
- Triage mode toggle (Hoarder / It Depends / Burn It All)
- Pre-ingest options: Inbox only / All mail / Last 30d / 6mo / 1y / All time
- Protect sender (all from `from_email`) and Protect domain (all from `*@domain`)
- Calendar invite detection (keep silently)
- E-commerce domain detection (own bucket, not promo)
- Bulk unsubscribe via `List-Unsubscribe` header (one-click only, no mailto)

### Later (V3+)
- AI chat: natural language → SQL over the structured table → preview → confirm
- Cron-trigger cleanup of orphaned KV/D1 rows
- Allowlist of full-access users (SHA-256 hash of email)
- 100K+ inbox handling via Queue + Durable Object

---

## 6. V2 Architecture — decision table

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Runtime | Cloudflare Workers + Hono | Already working, tiny |
| D2 | UI | htmx + Pico CSS, server-rendered HTML | No SPA tax |
| D3 | DB | D1 (SQLite at edge) | Already working |
| D4 | Sessions | KV, 24h TTL | Already working |
| D5 | Email fetch | Gmail API `metadata` format only | Never read bodies |
| D6 | Concurrency | 5 parallel Gmail requests, 100ms delay | Avoid 429 |
| D7 | Cap | 50,000 emails per session | Past this we need a queue |
| D8 | Pipeline >5K emails | `ctx.waitUntil()` + progress stored in KV | Beats 30s CPU limit |
| D9 | Privacy | No body, no logs of email content, sign-out wipes everything | One sentence to explain |
| D10 | Tier | Personal use on free, document the 15-user/day cap. Switch to $5 paid before any public link is shared | Avoid surprise outage |

Stop at 10. New decisions go in the issue tracker.

---

## 7. Privacy / Security must-haves

- Never request `gmail.readonly` with `full` format. `metadata` only.
- No email subject, body, or snippet ever logged.
- Sign-out deletes every D1 row keyed to the session, plus the KV entry.
- One Cron Trigger that wipes any session older than 24h, even if user never signed out.
- OAuth refresh tokens stored in KV, encrypted at rest.
- No third-party analytics. If you must measure, count actions server-side, no user IDs.

---

## 8. Quality bar before V2.0 ships

A QA pass like the one in your `Browser Automation MCP from Trae.md` must produce **zero FAIL** rows on these 10 checks:

1. `POST /dev/seed` returns 200 and `/report` shows non-zero rows in Top Senders and Top Domains.
2. Recommendations cards are ordered by `messageCount` descending.
3. "Archive all" on a card with N emails archives exactly N emails.
4. Undo restores the card to its pre-Apply state without navigation.
5. Mark-for-unsubscribe persists decisions and updates the decided count.
6. Review search URL has exactly one `?q=` and one `bucket=`.
7. Pagination updates the URL (`offset=25`, etc.) and Back works.
8. Keep / Archive in the review detail panel removes the row and decrements the total.
9. At 375px viewport width, no page has horizontal scroll.
10. Sign-out followed by sign-in shows zero residual data.

If any of these fail, V2.0 is not done. No exceptions, no "I'll fix it next slice."

---

## 9. Open questions for you to decide

- Do you want V2 to be **personal use only** (free tier, no public link) or **shareable** (paid tier, friends can sign in)? This changes the deploy plan.
- Final name lock: TidyGmail — keep, or change once and never again?
- Is the AI chat surface a V3 thing, or do you want a tiny version (one prompt → one preview → one confirm) in V2.1?
- Do you want a "math puzzle" confirm step (you mentioned this in Master Plan) for any action over 100 emails? Yes/No now, so it's designed in, not bolted on.
- Hard cap on emails ingested per session: 50,000 or higher? Higher = paid tier required from day one.

---

## 10. The one-line rule for V2

> Ship the loop. Stop planning surfaces. The scoring engine is fine — your problem is that the buttons don't do what they say.

Tell me which sections you want expanded into a full PRD doc (Feishu), and which open questions you want to answer first. I'll turn this into the next artifact based on what you pick.