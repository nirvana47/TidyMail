# TidyMail

Self-hosted Gmail cleanup with sender grouping, unread triage, unsubscribe inspection, queued approval, analytics and eligible undo. Java 21 + Spring Boot + PostgreSQL; no LLM integration.

Start with [local setup](DEPLOY.md). Read [PROJECT.md](PROJECT.md) for product and privacy behavior.

The agreed next MVP is a simpler sender dashboard with Inbox / All received mail,
bulk Gmail actions, and live background-action progress. See the
[MVP plan](docs/MVP-PLAN.md) for scope and preserve-first testing decisions.
Phase 3 now implements indexed browsing, grouping corrections and exact-selection
previews at `/dashboard`; see the [implementation report](docs/PHASE-3-INDEXED-DASHBOARD.md).
Background cleanup and live action progress remain for Phase 4 onward. The older
cleanup surfaces remain available until the navigation cleanup phase.

| Reference | Contents |
|---|---|
| [MVP plan](docs/MVP-PLAN.md) | Agreed dashboard scope, progress requirements, and data-preservation plan |
| [Codebase map](docs/CODEBASE.md) | Where features live and how data moves |
| [Status](docs/STATUS.md) | Implemented capabilities and verification gaps |
| [Testing](docs/TESTING.md) | PostgreSQL harness and test commands |
| [History](docs/HISTORY.md) | Archived planning documents and recovery |

Gmail is the implemented mailbox backend. Microsoft sign-in wiring exists, but Outlook cleanup is incomplete. Ordinary logout retains indexed data; explicit confirmed deletion removes it.

Licensed under [GPL v3](LICENSE).
