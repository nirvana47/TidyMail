# Inbox Cleaner (Java)

**Tagline:** A simple, non-AI Gmail and Outlook bulk cleanup web app for people who want control over their inboxes without the LLM complexity.

## What it is
A self-hosted, single-binary (Spring Boot JAR) tool that lets you sign in with Google or Microsoft, see your inbox summarized by sender, and take high-leverage bulk actions — archive, unsubscribe, mark read, trash — on entire groups of email at once. Also surfaces a "reply zero" queue of personal emails that still need your response.

Inspired by [Inbox Zero](https://github.com/elie222/inbox-zero), but:
- **No AI features.** No LLM, no auto-drafts, no chat assistant, no AI categorization.
- **Java/Spring Boot instead of Next.js**, so deployment is one `java -jar` on any machine with a JVM.
- **Deliberately smaller surface area.** Designed to be maintainable by one person for personal use first, with the option to open up sign-ups later.

## Core value
Help users confidently clean large amounts of inbox clutter through clear, high-leverage sender-grouped actions without making the experience feel confusing, risky, or overwhelming.

## Non-goals
- No LLM, no AI-assisted replies, no AI categorization, no meeting briefs
- No billing, subscriptions, premium tiers, Stripe/Lemon Squeezy
- No Apple Sign In, SSO/SCIM, Slack/Teams/Telegram bots, mobile push
- No queues on external brokers (BullMQ/RabbitMQ/Kafka) — in-process `@Scheduled` only
- No native Workers/Pages backend — Java requires a real JVM host

## Target runtime shape (per decisions — see `docs/DECISIONS.md`)
- **Web framework:** Spring Boot 3, Java 21+, Maven build
- **Auth:** Spring Security OAuth2 Client — "Sign in with Google" + "Sign in with Microsoft" buttons
- **Mail APIs:** Official Gmail Java client + Microsoft Graph SDK
- **Persistence:** Spring Data JPA + PostgreSQL (Neon / Supabase / Tembo managed, or local Docker Postgres)
- **Templates:** Server-rendered Thymeleaf + htmx fragments (no SPA bundler)
- **Scheduling:** Spring `@Scheduled` for sync, watch renewal, follow-up reminders
- **Deployment:** Single executable JAR behind a **Cloudflare Tunnel** — no open ports, free end-to-end HTTPS on Cloudflare-managed domain
