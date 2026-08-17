# Project Research Summary
**Project:** TidyMail (Java/Spring Boot Inbox Cleaner)
**Domain:** Inbox cleanup web application (non-AI)
**Researched:** 2026-08-07
**Confidence:** HIGH

Executive Summary:
This is a greenfield project in Java 21/Spring Boot 3 replacing a previously-abandoned TypeScript/Cloudflare Workers attempt (InboxTriage/TidyGmail, path ~/Documents/trae_projects/InboxTriage). The Workers version had correct bones (htmx, scoring engine, session-scoped data) but broken core loops: inaccurate counts, non-working undo, broken search, empty report tables, bad mobile. The new version reuses product decisions (scoring model, privacy model, cleanup UX) but switches to Java because that's the language the user understands best, and official Gmail + Outlook Java SDKs exist. Architecture: JVM origin behind Cloudflare Tunnel (NOT Workers native, since JVM can't run on V8/Wasm). Scope is deliberately smaller than the reference inbox-zero app. The critical rule from past failure: ship ONE loop end-to-end perfectly before adding surfaces.

Key Findings:
- Tech stack choice is well-validated. Both Google and Microsoft ship first-party well-documented Java SDKs. Spring Boot gives OAuth login buttons, JPA, @Scheduled, Thymeleaf with ~0 glue.
- Competitor patterns (inbox-zero reference app at ~/Documents/trae_projects/inbox-zero, Mailstrom) reinforce: cleanup tools work best as distinct action destinations — Bulk Archive, Bulk Unsubscribe, Analytics, Review — each with its own page, not mixed.
- Privacy model inherited from InboxTriage D2 is strong and defensible: METADATA only, sign-out = wipe, 24h TTL, no body content ever.
- The "Reply Zero" feature CAN be implemented purely via thread metadata (References, In-Reply-To, participants, bulk headers) without any LLM. Gmail METADATA format already returns the needed headers.
- Unsubscribe CAN work via List-Unsubscribe header (one-click URL fetch for http: URLs, not mailto:. mailto: needs email send capability which is out of initial scope).
- Gmail rate limit mitigation needs: concurrency window (~5 parallel) + small inter-batch delay (~100ms). This was proven to work up to 2000 messages in the previous attempt.

Recommended Phase Structure (matches ROADMAP.md):
1. Scaffold (pom.xml + entrypoint)
2. Security Config + OAuth Login Page (first user-visible win)
3. Postgres + JPA (persistence layer)
4. Gmail MailProvider (first real mail access)
5. Outlook MailProvider (parity)
6. Sender Aggregation + Heuristics (the core product logic)
7. Bulk Actions UI + Controllers + Reply Zero Page (the product surface)
8. Sync Jobs, Lazy Loading, Polish, Deployment Prep (production-ready)

Confidence Assessment:
| Area | Confidence | Notes |
| Stack | HIGH | All libraries are stable, first-party SDKs exist |
| Gmail Provider | HIGH | InboxTriage already proved API patterns work |
| Outlook Provider | MEDIUM-HIGH | Graph SDK mature, but thread metadata parity needs testing |
| Sender Heuristics | HIGH | All deterministic from METADATA headers |
| Reply Zero | HIGH | No LLM needed; References/In-Reply-To sufficient |
| Cloudflare Tunnel Deploy | HIGH | Standard pattern, documented by Cloudflare |
| Gaps: Gmail filter CREATE/LIST/DELETE API, Outlook change subscriptions (push), bulk unsubscribe mailto fallback, attachments viability as a standalone page |

Sources (PRIMARY = HIGH confidence):
- PRIMARY: ~/Documents/trae_projects/TidyMail/docs/DECISIONS.md (locked product+architecture decisions)
- PRIMARY: ~/Documents/trae_projects/TidyMail/docs/InboxTriage Master Plan.md (user's stated goals)
- PRIMARY: ~/Documents/trae_projects/TidyMail/docs/Progress Check - mid-PRD.md (PRD with avoid-list)
- PRIMARY: ~/Documents/trae_projects/InboxTriage/docs/DECISIONS.md (D1-D16 product decisions, validated)
- PRIMARY: ~/Documents/trae_projects/InboxTriage/.planning/research/PITFALLS.md (critical pitfalls from previous attempt)
- PRIMARY: ~/Documents/trae_projects/InboxTriage/.planning/REQUIREMENTS.md (nav/IA/UX requirements)
- PRIMARY: ~/Documents/trae_projects/InboxTriage/docs/QA-UX-AUDIT.md (10-point zero-fail QA list + 6 audits)
- REFERENCE (for UI/UX patterns only, NOT code copy): ~/Documents/trae_projects/inbox-zero/ (elie222/inbox-zero reference)
- OFFICIAL DOCS: https://developers.google.com/gmail/api/guides | https://learn.microsoft.com/en-us/graph/api/resources/mail-api-overview
- OFFICIAL DOCS: Spring Security OAuth2 Client, Spring Boot Reference 3.x

Ready for roadmap: YES
