# TidyMail

Self-hosted Gmail cleanup with sender grouping, unread triage, unsubscribe inspection, queued approval, analytics and eligible undo. Java 21 + Spring Boot + PostgreSQL; no LLM integration.

Start with [local setup](DEPLOY.md). Read [PROJECT.md](PROJECT.md) for product and privacy behavior.

| Reference | Contents |
|---|---|
| [Codebase map](docs/CODEBASE.md) | Where features live and how data moves |
| [Status](docs/STATUS.md) | Implemented capabilities and verification gaps |
| [Testing](docs/TESTING.md) | PostgreSQL harness and test commands |
| [History](docs/HISTORY.md) | Archived planning documents and recovery |

Gmail is the implemented mailbox backend. Microsoft sign-in wiring exists, but Outlook cleanup is incomplete. Ordinary logout retains indexed data; explicit confirmed deletion removes it.

Licensed under [GPL v3](LICENSE).
