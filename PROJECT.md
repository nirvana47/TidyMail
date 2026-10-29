# TidyMail

**MVP platform scope:** Desktop-first. Mobile implementation and testing are
deferred unless the user explicitly requests them later. Existing mobile work
may remain, but is not an acceptance requirement. See the
[recorded scope decision](docs/MVP-PLAN.md#desktop-first-scope--2026-10-28).

TidyMail is a self-hosted, non-LLM email cleanup application. It indexes Gmail metadata, groups messages by sender, and lets users preview, stage, approve and inspect bulk cleanup actions. This document describes source mapped on 2026-10-14; implemented does not mean verified against a live mailbox.

## Implemented product

Phase 3 adds the landing dashboard at `/dashboard`: account-owned indexed
Inbox/All received browsing, literal subject/sender search, timezone-aware dates,
10-row group/message paging, conservative groups and manual corrections, and
persisted exact previews with no-op/protection accounting. The dashboard's final
execution control remains disabled until Phase 4 supplies durable background jobs.
V16 is additive and has passed isolated fresh/retained fixtures; the retained
development database has not been upgraded. See the
[Phase 3 report](docs/PHASE-3-INDEXED-DASHBOARD.md). The inventory below describes
the retained legacy surfaces.

The [2026-10-28 MVP plan](docs/MVP-PLAN.md) defines the next product direction:
one sender dashboard, Inbox by default, All received mail excluding Spam/Trash,
and asynchronous bulk actions with live progress. It takes precedence over older
product priorities, but does not change the implementation inventory below.

| Surface | Behavior | Entry point |
|---|---|---|
| Bulk Archive/Delete | Sender summaries, filters, categories, classification corrections and exact cleanup staging | `/senders` |
| Unread Only | Inbox unread groups, recommendations, selection and staging | `/unread` |
| Bulk Unsubscribe | Candidates, sender statistics, method inspection, explicit confirmation and outcomes | `/unsubscribe` |
| Review | Queue, conflicts, approval, execution history and eligible undo | `/review` |
| Sync / Activity | Indexing progress, freshness, rescan and retry state | `/activity` |
| Analytics | Received-mail facts, outcomes, UTC trends, comparisons and drilldowns | `/analytics` |
| Reply Zero | Metadata-based reply heuristics, outside the main workspace rail | `/reply-zero` |

Google OAuth and the Gmail provider are implemented. Microsoft OAuth configuration and provider abstractions exist, but there is no production Outlook mail provider. Multiple accounts can be linked to an owner; the workspace selects one active account and rejects `account=all`.

Archive, Trash and Mark Read mutate the mailbox. Keep, Protect, Review and Remove Protection are local decisions. Unsubscribe supports RFC 8058 POST, explicitly selected legacy HTTPS GET and manual paths. Request acceptance is recorded separately from confirmation.

## Architecture and privacy

The stack is Java 21, Maven, Spring Boot 3.5.16, PostgreSQL, Flyway, Spring Data JPA, Thymeleaf and local JavaScript for fragment interactions. Deployment produces an executable JAR. Background work uses Spring scheduling and in-process executors with persisted state and leases. Cloudflare Tunnel is an optional front door to a Java host, not a Workers backend.

Gmail reads use `METADATA` with a fixed header whitelist. Addresses, subject headers, labels, timestamps, unsubscribe evidence and message-size estimates can be stored; bodies, snippets and attachment contents are excluded. Attachment presence is separate membership evidence, not downloaded content.

Ordinary logout ends the session and retains account data. Explicit deletion requires `DELETE` at `/logout/delete-data` and removes the owner's linked accounts and cascading records. Session expiry is not a data-retention timer. Housekeeping does not provide a complete mailbox purge after 24 hours.

Actions bind to materialized message IDs and record per-target results. Undo applies to successful targets of eligible Archive, Trash and Mark Read actions within the engine's ten-minute window. Protection removal has a separate compensation path. Unsubscribe is not reversible through mailbox undo.

There is no LLM integration, Gmail filter-creation workflow, embedded trained classifier, billing or live export subsystem in the mapped source. Older plans for those features describe historical intent.

## Documentation authority

[Codebase map](docs/CODEBASE.md) explains flows and ownership; [Status](docs/STATUS.md) records evidence and remaining work; [Deployment](DEPLOY.md) and [Testing](docs/TESTING.md) cover operation and verification. [History](docs/HISTORY.md) explains recovery of removed plans.

Source, migrations and executable tests are the implementation authority. Update these documents when behavior changes; historical phase names and old source comments are not current requirements.
