# TidyMail

Self-hosted Gmail cleanup: browse senders, review exact selections, and run Mark as Read, Archive or Move to Trash in the background. Java 21 + Spring Boot + PostgreSQL, with optional metadata-only Jev classification through OpenRouter.

Optional [Jev classification](docs/JEV-CLASSIFICATION.md) adds message categories and service-aware grouping using `OPENROUTER_API_KEY` in `.env`. It never performs mailbox actions automatically.

Start with [local setup](DEPLOY.md). Read [PROJECT.md](PROJECT.md) for product and privacy behavior.

The agreed next MVP is a simpler sender dashboard with Inbox / All received mail,
bulk Gmail actions, and live background-action progress. See the
[MVP plan](docs/MVP-PLAN.md) for scope and preserve-first testing decisions.
Phase 3 now implements indexed browsing, grouping corrections and exact-selection
previews at `/dashboard`; see the [implementation report](docs/PHASE-3-INDEXED-DASHBOARD.md).
Phase 4 adds confirmed background Mark as Read, cancellation and restored progress;
see the [Phase 4 report](docs/PHASE-4-BACKGROUND-MARK-READ.md). Phase 5 extends this
to Archive and Trash with bounded read retries and shared Gmail capacity;
see the [Phase 5 report](docs/PHASE-5-ACTIONS-AND-RESILIENCE.md) for verification status.
Phase 6 finishes Activity, pending indicators and desktop focus, and disables legacy
workflows by default; see the [Phase 6 report](docs/PHASE-6-SIMPLIFIED-EXPERIENCE.md).
Legacy code and records are retained behind the MVP-mode restrictions.
Phase 7 fixes fresh-page loading, background refresh and concurrent cleanup/browsing,
and has upgraded the retained index to V19 with a verified backup. See the
[acceptance matrix](docs/PHASE-7-UPGRADE-AND-ACCEPTANCE.md) for the completed desktop,
upgrade, regression and user-selected live-action checks, with their limits.

| Reference | Contents |
|---|---|
| [MVP plan](docs/MVP-PLAN.md) | Agreed dashboard scope, progress requirements, and data-preservation plan |
| [Codebase map](docs/CODEBASE.md) | Where features live and how data moves |
| [Status](docs/STATUS.md) | Implemented capabilities and verification gaps |
| [Testing](docs/TESTING.md) | PostgreSQL harness and test commands |
| [History](docs/HISTORY.md) | Archived planning documents and recovery |

Gmail is the implemented mailbox backend. Microsoft sign-in wiring exists, but Outlook cleanup is incomplete. Ordinary logout retains indexed data; explicit confirmed deletion removes it.

Licensed under [GPL v3](LICENSE).
